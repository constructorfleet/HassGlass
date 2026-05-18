package dev.hassglass.agent.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapsCommandTranslatorTest {
    @Test
    fun `null envelope clears the hud`() {
        val commands = CapsCommandTranslator.translate(null)
        assertEquals(listOf(CapsCommand.Clear, CapsCommand.Render), commands)
    }

    @Test
    fun `every translation starts with Clear and ends with Render`() {
        val envelope = HudCardEnvelope(
            id = "x",
            card = HudCard(kind = "toast", fields = mapOf("text" to "hi")),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        assertEquals(CapsCommand.Clear, commands.first())
        assertEquals(CapsCommand.Render, commands.last())
    }

    @Test
    fun `toast emits severity + body`() {
        val envelope = HudCardEnvelope(
            id = "t",
            card = HudCard(
                kind = "toast",
                fields = mapOf("text" to "Lights on", "severity" to "warning"),
            ),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        assertEquals(
            listOf(
                CapsCommand.Clear,
                CapsCommand.Severity(CapsCommand.SeverityLevel.WARNING),
                CapsCommand.Body("Lights on"),
                CapsCommand.Render,
            ),
            commands,
        )
    }

    @Test
    fun `icon_text emits icon then title then subtitle in stable order`() {
        val envelope = HudCardEnvelope(
            id = "w",
            card = HudCard(
                kind = "icon_text",
                fields = mapOf(
                    "icon" to "weather-rainy",
                    "title" to "62°F",
                    "subtitle" to "Rain in 20 min",
                ),
            ),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        assertEquals(
            listOf(
                CapsCommand.Clear,
                CapsCommand.Icon("weather-rainy"),
                CapsCommand.Title("62°F"),
                CapsCommand.Subtitle("Rain in 20 min"),
                CapsCommand.Render,
            ),
            commands,
        )
    }

    @Test
    fun `list truncates at the max-items cap from cards_py`() {
        val envelope = HudCardEnvelope(
            id = "l",
            card = HudCard(
                kind = "list",
                fields = mapOf(
                    "title" to "Shopping",
                    "items_0" to "milk",
                    "items_1" to "eggs",
                    "items_2" to "bread",
                    "items_3" to "butter",
                    "items_4" to "rejected",
                ),
            ),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        val items = commands.filterIsInstance<CapsCommand.ListItem>()
        assertEquals(4, items.size)
        assertEquals("butter", items.last().text)
        assertTrue(items.none { it.text == "rejected" })
    }

    @Test
    fun `timer uses TimerCountdown when expires_at_ms is set, falls back to Title`() {
        val withExpiry = HudCardEnvelope(
            id = "t",
            card = HudCard(
                kind = "timer",
                fields = mapOf("label" to "Pasta", "expires_at_ms" to "1700000000000"),
            ),
        )
        val commands = CapsCommandTranslator.translate(withExpiry)
        assertEquals(
            CapsCommand.TimerCountdown(1700000000000L, "Pasta"),
            commands[1],
        )

        val withoutExpiry = HudCardEnvelope(
            id = "t",
            card = HudCard(kind = "timer", fields = mapOf("label" to "Pasta")),
        )
        val fallback = CapsCommandTranslator.translate(withoutExpiry)
        assertEquals(CapsCommand.Title("Pasta"), fallback[1])
    }

    @Test
    fun `alert promotes severity to WARNING regardless of payload`() {
        val envelope = HudCardEnvelope(
            id = "a",
            card = HudCard(
                kind = "alert",
                fields = mapOf("title" to "Doorbell", "body" to "Front door"),
            ),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        assertEquals(CapsCommand.Severity(CapsCommand.SeverityLevel.WARNING), commands[1])
        assertEquals(CapsCommand.Title("Doorbell"), commands[2])
        assertEquals(CapsCommand.Body("Front door"), commands[3])
    }

    @Test
    fun `unknown kind falls back to a generic body if 'text' is set`() {
        val envelope = HudCardEnvelope(
            id = "u",
            card = HudCard(kind = "hologram", fields = mapOf("text" to "future")),
        )
        val commands = CapsCommandTranslator.translate(envelope)
        assertEquals(CapsCommand.Body("future"), commands[1])
    }
}
