package dev.hassglass.agent.wake

data class WakeWordEvent(
    val phrase: String,
)

interface RokidWakeBridge {
    fun setListener(listener: (WakeWordEvent) -> Unit)
    fun start()
    fun stop()
}
