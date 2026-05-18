package dev.hassglass.agent.hud

data class HudCard(
    val kind: String,
    val fields: Map<String, String> = emptyMap(),
)

data class HudCardEnvelope(
    val id: String,
    val card: HudCard,
    val priority: Int = 30,
    val ttlMs: Long? = null,
)

interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = java.lang.System.currentTimeMillis()
}
