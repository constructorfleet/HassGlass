package dev.hassglass.agent.ws

data class AgentConnectionConfig(
    val haBaseUrl: String,
    val deviceId: String,
    val serial: String,
    val firmware: String,
    val agentVersion: String,
    val token: String,
    val telemetry: TelemetrySnapshot? = null,
)

data class TelemetrySnapshot(
    val batteryPct: Int? = null,
    val rssiDbm: Int? = null,
    val worn: Boolean? = null,
)

data class WsRequest(
    val url: String,
    val headers: Map<String, String>,
)

interface WsTransport {
    fun connect(request: WsRequest, listener: WsListener): WsConnection
}

interface AgentConnector {
    fun connectWithRetry(config: AgentConnectionConfig, maxAttempts: Int = 5): WsConnection
}

interface WsConnection {
    fun sendText(text: String)
    fun sendBytes(bytes: ByteArray)
    fun close()
    fun awaitClose() = Unit
}

interface WsListener {
    fun onText(text: String)
    fun onBinary(bytes: ByteArray) = Unit
    fun onClosed() = Unit
    fun onFailure(error: Throwable) = Unit
}

data class RetryPolicy(
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
) {
    fun delayForAttempt(failedAttempt: Int): Long {
        val multiplier = 1L shl (failedAttempt - 1).coerceAtMost(30)
        return (initialDelayMs * multiplier).coerceAtMost(maxDelayMs)
    }
}

class WsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
