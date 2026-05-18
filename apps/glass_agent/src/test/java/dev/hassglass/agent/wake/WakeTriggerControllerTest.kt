package dev.hassglass.agent.wake

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

class WakeTriggerControllerTest {
    @Test
    fun hiRokidWakeSendsAudioStartWithWakePhrase() {
        val connection = RecordingConnection()
        val controller = WakeTriggerController(connection)

        controller.onNativeWakeWord(WakeWordEvent(phrase = "Hi Rokid"))

        val message = ProtocolCodec.decodeMessage(connection.sentText.single())
        assertEquals(MessageType.AUDIO_START, message.type)
        assertEquals("wake_word", message.fields["trigger"]?.jsonPrimitive?.content)
        assertEquals("hi rokid", message.fields["phrase"]?.jsonPrimitive?.content)
    }

    @Test
    fun sideButtonSendsAudioStartWithoutPhrase() {
        val connection = RecordingConnection()
        val controller = WakeTriggerController(connection)

        controller.onPushToTalk()

        val message = ProtocolCodec.decodeMessage(connection.sentText.single())
        assertEquals(MessageType.AUDIO_START, message.type)
        assertEquals("button", message.fields["trigger"]?.jsonPrimitive?.content)
        assertEquals(null, message.fields["phrase"])
    }
}

private class RecordingConnection : WsConnection {
    val sentText = mutableListOf<String>()

    override fun sendText(text: String) {
        sentText += text
    }

    override fun sendBytes(bytes: ByteArray) = Unit

    override fun close() = Unit
}
