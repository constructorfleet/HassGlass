package dev.hassglass.agent.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardStackTest {
    @Test
    fun showMakesCardCurrent() {
        val stack = CardStack(clock = MutableClock(nowMs = 1_000))

        stack.show(
            HudCardEnvelope(
                id = "weather",
                card = HudCard(kind = "icon_text", fields = mapOf("title" to "62F")),
                priority = 30,
            ),
        )

        assertEquals("weather", stack.current()?.id)
    }

    @Test
    fun higherPriorityCardPreemptsLowerPriorityCard() {
        val stack = CardStack(clock = MutableClock(nowMs = 1_000))

        stack.show(HudCardEnvelope("media", HudCard("media"), priority = 30))
        stack.show(HudCardEnvelope("alert", HudCard("alert"), priority = 90))

        assertEquals("alert", stack.current()?.id)
    }

    @Test
    fun dismissCurrentRevealsNextHighestCard() {
        val stack = CardStack(clock = MutableClock(nowMs = 1_000))
        stack.show(HudCardEnvelope("media", HudCard("media"), priority = 30))
        stack.show(HudCardEnvelope("alert", HudCard("alert"), priority = 90))

        stack.dismiss("alert")

        assertEquals("media", stack.current()?.id)
    }

    @Test
    fun wildcardDismissClearsStack() {
        val stack = CardStack(clock = MutableClock(nowMs = 1_000))
        stack.show(HudCardEnvelope("a", HudCard("toast"), priority = 30))
        stack.show(HudCardEnvelope("b", HudCard("toast"), priority = 40))

        stack.dismiss("*")

        assertNull(stack.current())
    }

    @Test
    fun expiredCardsArePrunedBeforeSelectingCurrent() {
        val clock = MutableClock(nowMs = 1_000)
        val stack = CardStack(clock = clock)
        stack.show(HudCardEnvelope("toast", HudCard("toast"), priority = 90, ttlMs = 500))
        stack.show(HudCardEnvelope("media", HudCard("media"), priority = 30))

        clock.nowMs = 1_501

        assertEquals("media", stack.current()?.id)
    }

    @Test
    fun showWithSameIdReplacesExistingCard() {
        val stack = CardStack(clock = MutableClock(nowMs = 1_000))
        stack.show(HudCardEnvelope("status", HudCard("toast"), priority = 30))
        stack.show(HudCardEnvelope("status", HudCard("alert"), priority = 80))

        assertEquals("status", stack.current()?.id)
        assertEquals("alert", stack.current()?.card?.kind)
        assertEquals(1, stack.size)
    }
}

private class MutableClock(
    var nowMs: Long,
) : Clock {
    override fun nowMs(): Long = nowMs
}
