package dev.hassglass.agent.audio

/**
 * Thin abstraction over `android.media.AudioTrack` so the byte-handling
 * + WAV-stripping logic in [AudioTrackPlaybackSink] is JVM-testable.
 *
 * Production binds to a real AudioTrack via [AndroidAudioTrackWriter];
 * tests inject a fake that records what would have been written.
 */
interface AudioTrackWriter {
    /** Push PCM-16 little-endian bytes; returns the count actually accepted. */
    fun write(pcm: ByteArray, offset: Int, length: Int): Int

    /** Begin playback. Idempotent. */
    fun play()

    /** Stop and drop any buffered audio. */
    fun flush()

    /** Permanently free the underlying resource. */
    fun release()
}

/**
 * [TtsPlaybackSink] that pipes inbound PCM bytes into a real Android
 * `AudioTrack`. Handles two practical wrinkles that the HA TTS path
 * exposes today:
 *
 *  1. **WAV header stripping.** HA's `tts.speak` returns whatever bytes
 *     the configured engine produced. The cloud/Piper paths emit WAV by
 *     default — a 44-byte RIFF header followed by PCM samples. AudioTrack
 *     expects raw PCM, so feeding the header through produces ~22 ms of
 *     garbled audio at the start of every utterance. We detect a `RIFF`
 *     magic on the first received chunk and skip past `data ` to the
 *     samples. If the bytes are already raw PCM (future state, once
 *     `tts_relay` transcodes), nothing happens.
 *  2. **Lazy play().** `AudioTrack.play()` should only be called once
 *     per utterance, on the first non-empty PCM write — calling it
 *     before any data is buffered can produce an underrun warning in
 *     logcat on some devices.
 *
 * The sink does **not** own the AudioTrack lifecycle. Construction
 * wires up an open writer; the holder is responsible for calling
 * [release] when the agent service is stopping.
 */
class AudioTrackPlaybackSink(
    private val writer: AudioTrackWriter,
) : TtsPlaybackSink {

    private var headerBytesPending: Int = -1 // -1 = haven't seen any data yet
    private var playStarted: Boolean = false

    override fun write(pcm: ByteArray) {
        val payload = stripWavHeaderIfPresent(pcm)
        if (payload.isEmpty()) {
            return
        }
        if (!playStarted) {
            writer.play()
            playStarted = true
        }
        var offset = 0
        while (offset < payload.size) {
            val wrote = writer.write(payload, offset, payload.size - offset)
            if (wrote <= 0) {
                // Writer is full or in a bad state — drop the rest of this chunk
                // rather than busy-spin. The TtsPlayer's gap detection will see
                // the missed seq on the next utterance.
                return
            }
            offset += wrote
        }
    }

    override fun flush() {
        writer.flush()
        playStarted = false
        headerBytesPending = -1
    }

    /** Call when the agent service is shutting down. */
    fun release() {
        writer.release()
    }

    private fun stripWavHeaderIfPresent(chunk: ByteArray): ByteArray {
        when (headerBytesPending) {
            -1 -> {
                // First chunk of a utterance — inspect.
                if (looksLikeWav(chunk)) {
                    val dataStart = findWavDataOffset(chunk)
                    if (dataStart < 0) {
                        // Header was split across chunks; for simplicity drop
                        // this whole chunk and resume parsing on the next one.
                        headerBytesPending = 0
                        return ByteArray(0)
                    }
                    headerBytesPending = 0
                    return chunk.copyOfRange(dataStart, chunk.size)
                }
                headerBytesPending = 0
                return chunk
            }
            else -> return chunk
        }
    }

    private fun looksLikeWav(chunk: ByteArray): Boolean {
        if (chunk.size < WAV_MAGIC_LEN) return false
        return chunk[0] == 'R'.code.toByte() &&
            chunk[1] == 'I'.code.toByte() &&
            chunk[2] == 'F'.code.toByte() &&
            chunk[3] == 'F'.code.toByte() &&
            chunk[8] == 'W'.code.toByte() &&
            chunk[9] == 'A'.code.toByte() &&
            chunk[10] == 'V'.code.toByte() &&
            chunk[11] == 'E'.code.toByte()
    }

    private fun findWavDataOffset(chunk: ByteArray): Int {
        // Walk the RIFF chunks looking for "data".
        var i = WAV_MAGIC_LEN // past RIFF<size>WAVE
        while (i + 8 <= chunk.size) {
            val tag = String(chunk, i, 4, Charsets.US_ASCII)
            val size = readLeInt32(chunk, i + 4)
            if (tag == "data") {
                return i + 8
            }
            i += 8 + size
            if (size <= 0) return -1
        }
        return -1
    }

    private fun readLeInt32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private companion object {
        const val WAV_MAGIC_LEN = 12
    }
}
