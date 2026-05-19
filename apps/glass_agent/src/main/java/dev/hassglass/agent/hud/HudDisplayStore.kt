package dev.hassglass.agent.hud

open class HudDisplayStore : HudRenderer {
    private val listeners = mutableSetOf<(HudCardEnvelope?) -> Unit>()

    @Volatile var current: HudCardEnvelope? = null
        private set

    @Synchronized
    override fun render(card: HudCardEnvelope?) {
        current = card
        listeners.toList().forEach { it(card) }
    }

    @Synchronized
    fun addListener(listener: (HudCardEnvelope?) -> Unit): () -> Unit {
        listeners += listener
        listener(current)
        return {
            synchronized(this) {
                listeners -= listener
            }
        }
    }
}

object SharedHudDisplayStore : HudDisplayStore()

class HudDisplayRenderer(
        private val store: HudDisplayStore = SharedHudDisplayStore,
) : HudRenderer {
    override fun render(card: HudCardEnvelope?) {
        store.render(card)
    }
}
