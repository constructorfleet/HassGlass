package dev.hassglass.agent.ws

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.PROTOCOL_VERSION
import dev.hassglass.agent.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val WS_PATH = "/api/hassglass/ws/v1"

class WsClient(
    private val transport: WsTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : AgentConnector {
    fun connectOnce(config: AgentConnectionConfig): WsConnection {
        lateinit var connection: WsConnection
        val listener = object : WsListener {
            override fun onText(text: String) {
                val message = ProtocolCodec.decodeMessage(text)
                if (message.type == MessageType.PING) {
                    connection.sendText(ProtocolCodec.encodeMessage(MessageType.PONG))
                }
            }
        }

        connection = transport.connect(requestFor(config), listener)
        connection.sendText(helloMessage(config))
        config.telemetry?.let { connection.sendText(telemetryMessage(it)) }
        return connection
    }

    override fun connectWithRetry(config: AgentConnectionConfig, maxAttempts: Int): WsConnection {
        var attempt = 1
        var lastError: Throwable? = null
        while (attempt <= maxAttempts) {
            try {
                return connectOnce(config)
            } catch (error: Throwable) {
                lastError = error
                if (attempt == maxAttempts) {
                    break
                }
                sleeper(retryPolicy.delayForAttempt(attempt))
                attempt += 1
            }
        }
        throw WsException("failed to connect after $maxAttempts attempt(s)", lastError)
    }

    private fun requestFor(config: AgentConnectionConfig): WsRequest =
        WsRequest(
            url = websocketUrl(config.haBaseUrl),
            headers = mapOf("Authorization" to "Bearer ${config.token}"),
        )

    private fun helloMessage(config: AgentConnectionConfig): String =
        ProtocolCodec.encodeMessage(
            MessageType.HELLO,
            mapOf(
                "device_id" to JsonPrimitive(config.deviceId),
                "serial" to JsonPrimitive(config.serial),
                "firmware" to JsonPrimitive(config.firmware),
                "agent_version" to JsonPrimitive(config.agentVersion),
                "protocol_version" to JsonPrimitive(PROTOCOL_VERSION),
            ),
        )

    private fun telemetryMessage(snapshot: TelemetrySnapshot): String {
        val fields = linkedMapOf<String, JsonElement>()
        snapshot.batteryPct?.let { fields["battery_pct"] = JsonPrimitive(it) }
        snapshot.rssiDbm?.let { fields["rssi_dbm"] = JsonPrimitive(it) }
        snapshot.worn?.let { fields["worn"] = JsonPrimitive(it) }
        return ProtocolCodec.encodeMessage(MessageType.TELEMETRY, fields)
    }

    private fun websocketUrl(haBaseUrl: String): String {
        val trimmed = haBaseUrl.trimEnd('/')
        val withScheme = when {
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
            trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
            else -> trimmed
        }
        return "$withScheme$WS_PATH"
    }
}
