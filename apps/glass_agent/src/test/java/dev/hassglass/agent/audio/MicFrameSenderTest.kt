package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MicFrameSenderTest {
    @Test
    fun sendsMicAudioFramesWithIncrementingSequence() {
        val connection = RecordingConnection()
        val sender = MicFrameSender(connection)

        sender.send(byteArrayOf(1, 2))
        sender.send(byteArrayOf(3, 4))

        val first = ProtocolCodec.decodeAudioFrame(connection.sentBytes[0])
        val second = ProtocolCodec.decodeAudioFrame(connection.sentBytes[1])
        assertEquals(AudioChannel.MIC_UP, first.channel)
        assertEquals(0, first.seq)
        assertContentEquals(byteArrayOf(1, 2), first.payload)
        assertEquals(1, second.seq)
        assertContentEquals(byteArrayOf(3, 4), second.payload)
    }
}

private class RecordingConnection : WsConnection {
    val sentBytes = mutableListOf<ByteArray>()

    override fun sendText(text: String) = Unit

    override fun sendBytes(bytes: ByteArray) {
        sentBytes += bytes
    }

    override fun close() = Unit
}
