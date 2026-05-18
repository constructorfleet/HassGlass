package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.AudioChannel
import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class CapturingConnection : WsConnection {
    val sentText: MutableList<String> = mutableListOf()
    val sentBytes: MutableList<ByteArray> = mutableListOf()
    var closed: Boolean = false

    override fun sendText(text: String) {
        sentText.add(text)
    }

    override fun sendBytes(bytes: ByteArray) {
        sentBytes.add(bytes)
    }

    override fun close() {
        closed = true
    }
}

class MicSessionTest {
    @Test
    fun `wake-word session sends audio_start, frames, then audio_stop`() {
        val conn = CapturingConnection()
        val source = FakeMicSource(
            listOf("aaa".toByteArray(), "bbb".toByteArray()),
        )
        val sender = MicFrameSender(conn)
        val session = MicSession(conn, source, sender)

        session.start(trigger = "wake_word", phrase = "Hi Rokid")
        session.stop()

        // First text: audio.start with phrase normalized
        val first = ProtocolCodec.decodeMessage(conn.sentText[0])
        assertEquals(MessageType.AUDIO_START, first.type)
        assertEquals("wake_word", first.fields["trigger"]!!.toString().trim('"'))
        assertEquals("hi rokid", first.fields["phrase"]!!.toString().trim('"'))

        // Two binary frames in order
        assertEquals(2, conn.sentBytes.size)
        val frame0 = ProtocolCodec.decodeAudioFrame(conn.sentBytes[0])
        val frame1 = ProtocolCodec.decodeAudioFrame(conn.sentBytes[1])
        assertEquals(AudioChannel.MIC_UP, frame0.channel)
        assertEquals(0L, frame0.seq)
        assertEquals(1L, frame1.seq)
        assertEquals("aaa", String(frame0.payload))
        assertEquals("bbb", String(frame1.payload))

        // Last text: audio.stop
        val last = ProtocolCodec.decodeMessage(conn.sentText.last())
        assertEquals(MessageType.AUDIO_STOP, last.type)
    }

    @Test
    fun `button trigger omits phrase`() {
        val conn = CapturingConnection()
        val session = MicSession(conn, FakeMicSource(emptyList()), MicFrameSender(conn))

        session.start(trigger = "button")
        session.stop()

        val start = ProtocolCodec.decodeMessage(conn.sentText[0])
        assertEquals(MessageType.AUDIO_START, start.type)
        assertEquals("button", start.fields["trigger"]!!.toString().trim('"'))
        assertFalse(start.fields.containsKey("phrase"))
    }

    @Test
    fun `double start is a no-op`() {
        val conn = CapturingConnection()
        val session = MicSession(
            conn,
            FakeMicSource(listOf("x".toByteArray())),
            MicFrameSender(conn),
        )

        session.start(trigger = "button")
        // Second call before stop — must not emit a second audio.start.
        session.start(trigger = "wake_word", phrase = "Hi")
        session.stop()

        val startMessages = conn.sentText.filter {
            ProtocolCodec.decodeMessage(it).type == MessageType.AUDIO_START
        }
        assertEquals(1, startMessages.size)
    }

    @Test
    fun `stop before start is a no-op`() {
        val conn = CapturingConnection()
        val session = MicSession(conn, FakeMicSource(emptyList()), MicFrameSender(conn))

        session.stop()
        assertTrue(conn.sentText.isEmpty())
        assertTrue(conn.sentBytes.isEmpty())
    }
}
