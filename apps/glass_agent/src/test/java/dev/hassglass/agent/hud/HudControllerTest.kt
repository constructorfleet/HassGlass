package dev.hassglass.agent.hud

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HudControllerTest {
    @Test
    fun hudShowMessageRendersCurrentCard() {
        val renderer = RecordingRenderer()
        val controller = HudController(renderer = renderer)

        controller.handleMessage(
            ProtocolCodec.encodeMessage(
                MessageType.HUD_SHOW,
                mapOf(
                    "id" to JsonPrimitive("weather"),
                    "priority" to JsonPrimitive(30),
                    "card" to JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("icon_text"),
                            "title" to JsonPrimitive("62F"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("weather", renderer.rendered.single()?.id)
        assertEquals("icon_text", renderer.rendered.single()?.card?.kind)
        assertEquals("62F", renderer.rendered.single()?.card?.fields?.get("title"))
    }

    @Test
    fun hudDismissMessageRendersNextCard() {
        val renderer = RecordingRenderer()
        val controller = HudController(renderer = renderer)
        controller.handleMessage(showMessage("media", "media", priority = 30))
        controller.handleMessage(showMessage("alert", "alert", priority = 90))

        controller.handleMessage(
            ProtocolCodec.encodeMessage(
                MessageType.HUD_DISMISS,
                mapOf("id" to JsonPrimitive("alert")),
            ),
        )

        assertEquals("media", renderer.rendered.last()?.id)
    }

    @Test
    fun dismissAllClearsRenderer() {
        val renderer = RecordingRenderer()
        val controller = HudController(renderer = renderer)
        controller.handleMessage(showMessage("alert", "alert", priority = 90))

        controller.handleMessage(
            ProtocolCodec.encodeMessage(
                MessageType.HUD_DISMISS,
                mapOf("id" to JsonPrimitive("*")),
            ),
        )

        assertNull(renderer.rendered.last())
    }

    private fun showMessage(id: String, kind: String, priority: Int): String =
        ProtocolCodec.encodeMessage(
            MessageType.HUD_SHOW,
            mapOf(
                "id" to JsonPrimitive(id),
                "priority" to JsonPrimitive(priority),
                "card" to JsonObject(mapOf("kind" to JsonPrimitive(kind))),
            ),
        )
}

private class RecordingRenderer : HudRenderer {
    val rendered = mutableListOf<HudCardEnvelope?>()

    override fun render(card: HudCardEnvelope?) {
        rendered += card
    }
}
