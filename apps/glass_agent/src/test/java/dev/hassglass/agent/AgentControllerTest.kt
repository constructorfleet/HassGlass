package dev.hassglass.agent

import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.KeyValueStore
import dev.hassglass.agent.settings.PairedAgentSettings
import dev.hassglass.agent.ws.AgentConnectionConfig
import dev.hassglass.agent.ws.AgentConnector
import dev.hassglass.agent.ws.TelemetrySnapshot
import dev.hassglass.agent.ws.WsConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentControllerTest {
    @Test
    fun startReturnsPairingRequiredWhenNoSettingsExist() {
        val controller = AgentController(
            settingsStore = AgentSettingsStore(InMemoryKeyValueStore()),
            connector = RecordingConnector(),
        )

        assertEquals(AgentStartResult.PAIRING_REQUIRED, controller.start())
    }

    @Test
    fun startConnectsWhenPairedSettingsExist() {
        val settingsStore = AgentSettingsStore(InMemoryKeyValueStore())
        settingsStore.savePairedSettings(
            PairedAgentSettings(
                haBaseUrl = "https://ha.example",
                deviceId = "rokid-1",
                serial = "SN123",
                firmware = "1.2.3",
                agentVersion = "0.1.0",
                token = "device-token",
            ),
        )
        val connector = RecordingConnector()
        val controller = AgentController(
            settingsStore = settingsStore,
            connector = connector,
            telemetryProvider = { TelemetrySnapshot(batteryPct = 90) },
        )

        assertEquals(AgentStartResult.CONNECTED, controller.start())
        assertEquals("rokid-1", connector.configs.single().deviceId)
        assertEquals(90, connector.configs.single().telemetry?.batteryPct)
    }
}

private class RecordingConnector : AgentConnector {
    val configs = mutableListOf<AgentConnectionConfig>()

    override fun connectWithRetry(config: AgentConnectionConfig, maxAttempts: Int): WsConnection {
        configs += config
        return object : WsConnection {
            override fun sendText(text: String) = Unit
            override fun sendBytes(bytes: ByteArray) = Unit
            override fun close() = Unit
        }
    }
}

private class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(vararg keys: String) {
        keys.forEach(values::remove)
    }
}
