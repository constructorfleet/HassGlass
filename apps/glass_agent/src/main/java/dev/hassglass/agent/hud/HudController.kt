package dev.hassglass.agent.hud

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

interface HudRenderer {
    fun render(card: HudCardEnvelope?)
}

class HudController(
    private val renderer: HudRenderer,
    private val stack: CardStack = CardStack(),
) {
    fun handleMessage(raw: String) {
        val message = ProtocolCodec.decodeMessage(raw)
        when (message.type) {
            MessageType.HUD_SHOW -> {
                stack.show(message.toHudCardEnvelope())
                renderer.render(stack.current())
            }
            MessageType.HUD_DISMISS -> {
                val id = message.fields["id"]?.jsonPrimitive?.contentOrNull ?: return
                stack.dismiss(id)
                renderer.render(stack.current())
            }
            else -> Unit
        }
    }

    private fun dev.hassglass.agent.protocol.ProtocolMessage.toHudCardEnvelope(): HudCardEnvelope {
        val id = fields["id"]?.jsonPrimitive?.contentOrNull ?: error("hud.show missing id")
        val priority = fields["priority"]?.jsonPrimitive?.intOrNull ?: 30
        val ttlMs = fields["ttl_ms"]?.jsonPrimitive?.longOrNull
        val cardObject = fields["card"]?.jsonObject ?: error("hud.show missing card")
        val kind = cardObject["kind"]?.jsonPrimitive?.contentOrNull ?: error("hud.show missing card.kind")
        return HudCardEnvelope(
            id = id,
            card = HudCard(kind = kind, fields = cardObject.stringFieldsExcluding("kind")),
            priority = priority,
            ttlMs = ttlMs,
        )
    }

    private fun JsonObject.stringFieldsExcluding(excluded: String): Map<String, String> =
        entries
            .filter { (key, value) -> key != excluded && value is JsonPrimitive }
            .associate { (key, value) -> key to value.jsonPrimitive.content }
}
