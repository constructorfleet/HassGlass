package dev.hassglass.agent

import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.PairedAgentSettings
import dev.hassglass.agent.ws.AgentConnectionConfig
import dev.hassglass.agent.ws.AgentConnector
import dev.hassglass.agent.ws.TelemetrySnapshot
import dev.hassglass.agent.ws.WsConnection

enum class AgentStartResult {
    CONNECTED,
    PAIRING_REQUIRED,
}

class AgentController(
        private val settingsStore: AgentSettingsStore,
        private val connector: AgentConnector,
        private val telemetryProvider: () -> TelemetrySnapshot? = { null },
        private val reconnectDelayMs: Long = 1_000,
        private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
        private val onConnected: (WsConnection) -> Unit = {},
        private val onDisconnected: () -> Unit = {},
) {
    @Volatile private var stopped = false
    private var connection: WsConnection? = null

    fun start(): AgentStartResult {
        val settings =
                settingsStore.loadPairedSettings() ?: return AgentStartResult.PAIRING_REQUIRED
        stopped = false
        connection = connector.connectWithRetry(settings.toConnectionConfig(telemetryProvider()))
        connection?.let(onConnected)
        return AgentStartResult.CONNECTED
    }

    fun runUntilStopped(): AgentStartResult {
        val settings =
                settingsStore.loadPairedSettings() ?: return AgentStartResult.PAIRING_REQUIRED
        stopped = false
        while (!stopped) {
            try {
                connection = connector.connectWithRetry(settings.toConnectionConfig(telemetryProvider()))
                val active = connection
                active?.let(onConnected)
                active?.awaitClose()
                if (connection === active) {
                    connection = null
                    onDisconnected()
                }
            } catch (_: Throwable) {
                connection = null
            }
            if (!stopped) {
                sleeper(reconnectDelayMs)
            }
        }
        return AgentStartResult.CONNECTED
    }

    fun stop() {
        stopped = true
        val active = connection
        active?.close()
        connection = null
        if (active != null) {
            onDisconnected()
        }
    }

    private fun PairedAgentSettings.toConnectionConfig(
            telemetry: TelemetrySnapshot?,
    ): AgentConnectionConfig =
            AgentConnectionConfig(
                    haBaseUrl = haBaseUrl,
                    deviceId = deviceId,
                    serial = serial,
                    firmware = firmware,
                    agentVersion = agentVersion,
                    token = token,
                    telemetry = telemetry,
            )
}
