package dev.hassglass.agent.audio

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import dev.hassglass.agent.ws.WsConnection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Orchestrator that glues a [MicSource] to the [MicFrameSender] and brackets
 * the stream with `audio.start` / `audio.stop` control-plane messages.
 *
 * One session per utterance. Triggered by either:
 *  - the on-glass "Hi Rokid" wake word (via [start] with trigger="wake_word"),
 *  - the side-button push-to-talk path (via [start] with trigger="button").
 *
 * In both cases the integration's `audio.py` receives the same shape of
 * frames and feeds them into the same `assist_pipeline.async_pipeline_from_audio_stream`
 * call — the trigger string is what carries the "should we skip the wake-word
 * pipeline stage" hint.
 */
class MicSession(
    private val connection: WsConnection,
    private val source: MicSource,
    private val sender: MicFrameSender,
) {
    @Volatile
    private var active = false

    fun start(trigger: String, phrase: String? = null) {
        if (active) {
            return
        }
        active = true
        sendAudioStart(trigger, phrase)
        // Note: MicSource.start is synchronous in production for AudioRecord-
        // backed sources (runs the read loop on the caller's thread). The
        // caller — typically WakeTriggerController on a dispatcher thread —
        // is responsible for end-of-utterance detection via stop().
        source.start { pcmChunk ->
            if (active) {
                sender.send(pcmChunk)
            }
        }
    }

    fun stop() {
        if (!active) {
            return
        }
        source.stop()
        connection.sendText(ProtocolCodec.encodeMessage(MessageType.AUDIO_STOP))
        active = false
    }

    private fun sendAudioStart(trigger: String, phrase: String?) {
        val fields = linkedMapOf<String, JsonElement>("trigger" to JsonPrimitive(trigger))
        if (!phrase.isNullOrBlank()) {
            fields["phrase"] = JsonPrimitive(phrase.trim().lowercase())
        }
        connection.sendText(ProtocolCodec.encodeMessage(MessageType.AUDIO_START, fields))
    }
}
