package dev.hassglass.agent.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

class ProtocolCodecTest {
    @Test
    fun encodesAndDecodesMicAudioFrame() {
        val encoded = ProtocolCodec.encodeAudioFrame(
            channel = AudioChannel.MIC_UP,
            seq = 42,
            payload = byteArrayOf(1, 2, 3),
        )

        val decoded = ProtocolCodec.decodeAudioFrame(encoded)

        assertEquals(AudioChannel.MIC_UP, decoded.channel)
        assertEquals(42, decoded.seq)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.payload)
    }

    @Test
    fun preservesUnsignedAudioSequence() {
        val encoded = ProtocolCodec.encodeAudioFrame(
            channel = AudioChannel.TTS_DOWN,
            seq = 0xffffffffL,
            payload = byteArrayOf(),
        )

        val decoded = ProtocolCodec.decodeAudioFrame(encoded)

        assertEquals(AudioChannel.TTS_DOWN, decoded.channel)
        assertEquals(0xffffffffL, decoded.seq)
    }

    @Test
    fun encodesAndDecodesHelloMessage() {
        val encoded = ProtocolCodec.encodeMessage(
            MessageType.HELLO,
            mapOf(
                "device_id" to JsonPrimitive("rokid-1"),
                "protocol_version" to JsonPrimitive(PROTOCOL_VERSION),
            ),
        )

        val decoded = ProtocolCodec.decodeMessage(encoded)

        assertEquals(MessageType.HELLO, decoded.type)
        assertEquals(JsonPrimitive("rokid-1"), decoded.fields["device_id"])
        assertEquals(JsonPrimitive(PROTOCOL_VERSION), decoded.fields["protocol_version"])
    }
}
