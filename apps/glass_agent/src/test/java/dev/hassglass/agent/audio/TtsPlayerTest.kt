package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.ProtocolCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class TtsPlayerTest {
    @Test
    fun `in-order frames write to sink contiguously`() {
        val sink = CollectingPlaybackSink()
        val player = TtsPlayer(sink)

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 0, "aaa".toByteArray()))
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 1, "bbb".toByteArray()))
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 2, "ccc".toByteArray()))

        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            sink.chunks.map { String(it) },
        )
    }

    @Test
    fun `mic-up frames are ignored even on the tts channel handler`() {
        val sink = CollectingPlaybackSink()
        val player = TtsPlayer(sink)

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.MIC_UP, 0, "x".toByteArray()))
        assertEquals(0, sink.chunks.size)
    }

    @Test
    fun `late frames are dropped`() {
        val sink = CollectingPlaybackSink()
        val player = TtsPlayer(sink)

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 0, "a".toByteArray()))
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 1, "b".toByteArray()))
        // Re-delivery of seq 0
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 0, "DUP".toByteArray()))

        assertEquals(listOf("a", "b"), sink.chunks.map { String(it) })
    }

    @Test
    fun `gap reports dropout and continues playback`() {
        val sink = CollectingPlaybackSink()
        val dropouts = mutableListOf<Pair<Long, Long>>()
        val player = TtsPlayer(sink) { expected, got -> dropouts.add(expected to got) }

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 0, "a".toByteArray()))
        // Skip seq 1 (lost in transit)
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 2, "c".toByteArray()))
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 3, "d".toByteArray()))

        assertEquals(listOf<Pair<Long, Long>>(1L to 2L), dropouts)
        assertEquals(listOf("a", "c", "d"), sink.chunks.map { String(it) })
    }

    @Test
    fun `accept starts tracking from the first seq seen`() {
        // Real glasses might not get seq 0 — first frame could be seq=17 if a
        // play_media restart happens. Player must not refuse to start.
        val sink = CollectingPlaybackSink()
        val player = TtsPlayer(sink)

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 17, "first".toByteArray()))
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 18, "second".toByteArray()))

        assertEquals(listOf("first", "second"), sink.chunks.map { String(it) })
    }

    @Test
    fun `end-of-utterance flushes and resets seq tracking`() {
        val sink = CollectingPlaybackSink()
        val player = TtsPlayer(sink)

        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 0, "a".toByteArray()))
        player.endOfUtterance()
        assertEquals(1, sink.flushCount)

        // After flush, a new utterance starting at a non-zero seq must work.
        player.accept(ProtocolCodec.encodeAudioFrame(AudioChannel.TTS_DOWN, 100, "new".toByteArray()))
        assertEquals("new".toByteArray().toList(), sink.chunks.last().toList())
    }
}
