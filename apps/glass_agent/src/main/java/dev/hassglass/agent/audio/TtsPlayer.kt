package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.ProtocolCodec

/**
 * Sink that receives ordered PCM bytes from the integration's tts_relay.
 *
 * In production this wraps `android.media.AudioTrack` configured for
 * 16 kHz mono PCM with the lowest available buffer size; in unit tests it
 * collects bytes to a [ByteArrayOutputStream]-style buffer so we can
 * assert on the decoded stream.
 */
interface TtsPlaybackSink {
    fun write(pcm: ByteArray)
    fun flush()
}

/**
 * Decodes inbound channel-0x02 binary frames and routes the payload to a
 * [TtsPlaybackSink]. Detects out-of-order / dropped frames by tracking
 * the expected sequence number; out-of-order frames are dropped (the
 * Assist pipeline doesn't gracefully recover from reordered audio).
 *
 * The class is fully synchronous and side-effect-free except for the
 * sink — so a real Android implementation can drive it from the OkHttp
 * dispatcher thread without locking, and tests can drive it without
 * threads.
 */
class TtsPlayer(
    private val sink: TtsPlaybackSink,
    private val onDropout: (expected: Long, got: Long) -> Unit = { _, _ -> },
) {
    private var nextSeq: Long = 0
    private var streamStarted = false

    fun accept(frame: ByteArray) {
        val decoded = ProtocolCodec.decodeAudioFrame(frame)
        if (decoded.channel != AudioChannel.TTS_DOWN) {
            return
        }

        if (!streamStarted) {
            nextSeq = decoded.seq
            streamStarted = true
        }

        when {
            decoded.seq == nextSeq -> {
                sink.write(decoded.payload)
                nextSeq = (nextSeq + 1) and 0xffffffffL
            }
            decoded.seq < nextSeq -> {
                // Late frame — already played past this seq. Drop.
            }
            else -> {
                // Gap. Report and resync to the new seq so we keep playing.
                onDropout(nextSeq, decoded.seq)
                sink.write(decoded.payload)
                nextSeq = (decoded.seq + 1) and 0xffffffffL
            }
        }
    }

    /** Indicates upstream signalled end-of-utterance — flush the sink. */
    fun endOfUtterance() {
        sink.flush()
        streamStarted = false
        nextSeq = 0
    }
}

/** Trivial sink for tests + diagnostics: appends to a mutable list of chunks. */
class CollectingPlaybackSink : TtsPlaybackSink {
    private val _chunks: MutableList<ByteArray> = mutableListOf()
    private var _flushCount: Int = 0

    val chunks: List<ByteArray> get() = _chunks.toList()
    val flushCount: Int get() = _flushCount

    override fun write(pcm: ByteArray) {
        _chunks.add(pcm)
    }

    override fun flush() {
        _flushCount += 1
    }
}
