package dev.hassglass.agent.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudDisplayStoreTest {
    @Test
    fun rendererStoresLatestHudCardForVisibleUi() {
        val store = HudDisplayStore()
        val renderer = HudDisplayRenderer(store)
        val card =
                HudCardEnvelope(
                        id = "doorbell",
                        card =
                                HudCard(
                                        kind = "icon_text",
                                        fields =
                                                mapOf(
                                                        "title" to "Doorbell",
                                                        "subtitle" to "Front door",
                                                ),
                                ),
                )

        renderer.render(card)

        assertEquals(card, store.current)
    }

    @Test
    fun rendererClearsVisibleHudCard() {
        val store = HudDisplayStore()
        val renderer = HudDisplayRenderer(store)
        renderer.render(HudCardEnvelope("notify", HudCard("toast", mapOf("text" to "Hi"))))

        renderer.render(null)

        assertNull(store.current)
    }

    @Test
    fun listenersReceiveImmediateAndFutureCards() {
        val store = HudDisplayStore()
        val first = HudCardEnvelope("first", HudCard("toast", mapOf("text" to "One")))
        val second = HudCardEnvelope("second", HudCard("toast", mapOf("text" to "Two")))
        store.render(first)
        val received = mutableListOf<HudCardEnvelope?>()

        val unsubscribe = store.addListener { received += it }
        store.render(second)
        unsubscribe()
        store.render(null)

        assertEquals(listOf<HudCardEnvelope?>(first, second), received)
    }
}
