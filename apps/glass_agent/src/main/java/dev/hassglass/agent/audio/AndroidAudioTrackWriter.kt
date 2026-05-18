package dev.hassglass.agent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Production [AudioTrackWriter] backed by `android.media.AudioTrack`.
 *
 * Configured for 16 kHz mono PCM-16 little-endian — matching the
 * integration's `TtsRelay` output and the Assist pipeline's default STT
 * audio format. Uses `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` so the
 * audio routes through the YodaOS speech-output pipeline rather than
 * the music output.
 *
 * The buffer is sized at the system minimum: AudioTrack.write blocks
 * when the buffer is full, but the sink-level retry loop respects
 * partial writes, so we trade a small amount of blocking for lower
 * latency between TTS frame arrival and audible output.
 */
class AndroidAudioTrackWriter(
    sampleRate: Int = SAMPLE_RATE_16K,
) : AudioTrackWriter {

    private val track: AudioTrack
    private var released: Boolean = false

    init {
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = if (minBuffer > 0) minBuffer else FALLBACK_BUFFER_BYTES

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        if (released) {
            return -1
        }
        return track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
    }

    override fun play() {
        if (released) {
            return
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    override fun flush() {
        if (released) {
            return
        }
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
        }
        track.flush()
    }

    override fun release() {
        if (released) {
            return
        }
        released = true
        try {
            track.stop()
        } catch (_: IllegalStateException) {
            // Already stopped; safe to ignore.
        }
        track.release()
    }

    /** Exposed for diagnostics — also keeps [AudioManager] usage stable. */
    @Suppress("unused")
    val streamType: Int = AudioManager.STREAM_VOICE_CALL

    private companion object {
        const val SAMPLE_RATE_16K = 16_000
        const val FALLBACK_BUFFER_BYTES = 4096
    }
}
