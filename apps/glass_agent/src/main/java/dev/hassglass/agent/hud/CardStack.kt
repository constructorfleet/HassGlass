package dev.hassglass.agent.hud

class CardStack(
        private val clock: Clock = SystemClock,
) {
    private val cards = linkedMapOf<String, ActiveHudCard>()

    val size: Int
        @Synchronized
        get() {
            pruneExpired()
            return cards.size
        }

    @Synchronized
    fun show(card: HudCardEnvelope) {
        cards[card.id] =
                ActiveHudCard(
                        envelope = card,
                        shownAtMs = clock.nowMs(),
                        expiresAtMs = card.ttlMs?.let { clock.nowMs() + it },
                )
    }

    @Synchronized
    fun dismiss(id: String) {
        if (id == "*") {
            cards.clear()
        } else {
            cards.remove(id)
        }
    }

    @Synchronized
    fun current(): HudCardEnvelope? {
        pruneExpired()
        return cards.values.maxWithOrNull(
                        compareBy<ActiveHudCard> { it.envelope.priority }.thenBy { it.shownAtMs }
                )
                ?.envelope
    }

    // Must be called from a synchronized context.
    private fun pruneExpired() {
        val now = clock.nowMs()
        cards.entries.removeIf { (_, active) ->
            active.expiresAtMs != null && active.expiresAtMs <= now
        }
    }
}

private data class ActiveHudCard(
        val envelope: HudCardEnvelope,
        val shownAtMs: Long,
        val expiresAtMs: Long?,
)
