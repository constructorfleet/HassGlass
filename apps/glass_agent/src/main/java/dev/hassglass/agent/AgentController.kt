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
        private val onConnected: (WsConnection) -> Unit = {},
        private val onDisconnected: () -> Unit = {},
) {
    private var connection: WsConnection? = null

    fun start(): AgentStartResult {
        val settings =
                settingsStore.loadPairedSettings() ?: return AgentStartResult.PAIRING_REQUIRED
        connection = connector.connectWithRetry(settings.toConnectionConfig(telemetryProvider()))
        connection?.let(onConnected)
        return AgentStartResult.CONNECTED
    }

    fun stop() {
        connection?.close()
        connection = null
        onDisconnected()
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
