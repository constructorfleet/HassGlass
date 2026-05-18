package dev.hassglass.agent

import dev.hassglass.agent.audio.AudioTrackPlaybackSink
import dev.hassglass.agent.audio.AudioTrackWriter
import dev.hassglass.agent.audio.FakeMicSource
import dev.hassglass.agent.hud.HudCardEnvelope
import dev.hassglass.agent.hud.HudRenderer
import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AgentRuntimeTest {
    @Test
    fun hudMessagesRenderThroughTheRuntime() {
        val renderer = RecordingHudRenderer()
        val runtime =
                AgentRuntime(
                        hudRenderer = renderer,
                        micSourceFactory = { FakeMicSource(emptyList()) },
                        playbackSink = AudioTrackPlaybackSink(RecordingAudioTrackWriter()),
                )

        runtime.handleText(
                ProtocolCodec.encodeMessage(
                        MessageType.HUD_SHOW,
                        mapOf(
                                "id" to JsonPrimitive("weather"),
                                "priority" to JsonPrimitive(30),
                                "card" to
                                        JsonObject(
                                                mapOf(
                                                        "kind" to JsonPrimitive("icon_text"),
                                                        "title" to JsonPrimitive("62F"),
                                                ),
                                        ),
                        ),
                ),
        )

        assertEquals("weather", renderer.rendered.single()?.id)
    }

    @Test
    fun binaryTtsFramesPlayThroughTheRuntimeSink() {
        val writer = RecordingAudioTrackWriter()
        val runtime =
                AgentRuntime(
                        hudRenderer = RecordingHudRenderer(),
                        micSourceFactory = { FakeMicSource(emptyList()) },
                        playbackSink = AudioTrackPlaybackSink(writer),
                )

        runtime.handleBinary(
                ProtocolCodec.encodeAudioFrame(
                        channel = AudioChannel.TTS_DOWN,
                        seq = 0,
                        payload = "pcm".toByteArray(),
                ),
        )

        assertEquals(1, writer.playCalls)
        assertContentEquals("pcm".toByteArray(), writer.writes.single())
    }

    @Test
    fun pushToTalkUsesTheAttachedMicSession() {
        val connection = RecordingConnection()
        val runtime =
                AgentRuntime(
                        hudRenderer = RecordingHudRenderer(),
                        micSourceFactory = {
                            FakeMicSource(listOf("abc".toByteArray(), "def".toByteArray()))
                        },
                        playbackSink = AudioTrackPlaybackSink(RecordingAudioTrackWriter()),
                )

        runtime.attachConnection(connection)
        runtime.startPushToTalk()

        val start = ProtocolCodec.decodeMessage(connection.sentText.first())
        assertEquals(MessageType.AUDIO_START, start.type)
        assertEquals(2, connection.sentBytes.size)
    }
}

private class RecordingHudRenderer : HudRenderer {
    val rendered = mutableListOf<HudCardEnvelope?>()

    override fun render(card: HudCardEnvelope?) {
        rendered += card
    }
}

private class RecordingAudioTrackWriter : AudioTrackWriter {
    val writes = mutableListOf<ByteArray>()
    var playCalls = 0
    var flushCalls = 0
    var releaseCalls = 0

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        writes += pcm.copyOfRange(offset, offset + length)
        return length
    }

    override fun play() {
        playCalls += 1
    }

    override fun flush() {
        flushCalls += 1
    }

    override fun release() {
        releaseCalls += 1
    }
}

private class RecordingConnection : WsConnection {
    val sentText = mutableListOf<String>()
    val sentBytes = mutableListOf<ByteArray>()

    override fun sendText(text: String) {
        sentText += text
    }

    override fun sendBytes(bytes: ByteArray) {
        sentBytes += bytes
    }

    override fun close() = Unit
}
