package dev.hassglass.agent.wake

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class WakeTriggerController(
    private val connection: WsConnection,
) {
    fun onNativeWakeWord(event: WakeWordEvent) {
        sendAudioStart(
            trigger = "wake_word",
            phrase = event.phrase.trim().lowercase(),
        )
    }

    fun onPushToTalk() {
        sendAudioStart(trigger = "button")
    }

    private fun sendAudioStart(trigger: String, phrase: String? = null) {
        val fields = linkedMapOf<String, JsonElement>("trigger" to JsonPrimitive(trigger))
        if (!phrase.isNullOrBlank()) {
            fields["phrase"] = JsonPrimitive(phrase)
        }
        connection.sendText(ProtocolCodec.encodeMessage(MessageType.AUDIO_START, fields))
    }
}
