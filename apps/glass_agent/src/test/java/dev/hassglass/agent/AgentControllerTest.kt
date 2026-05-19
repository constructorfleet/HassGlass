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
import kotlin.test.assertTrue

class AgentControllerTest {
    @Test
    fun startReturnsPairingRequiredWhenNoSettingsExist() {
        val controller =
                AgentController(
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
        val controller =
                AgentController(
                        settingsStore = settingsStore,
                        connector = connector,
                        telemetryProvider = { TelemetrySnapshot(batteryPct = 90) },
                )

        assertEquals(AgentStartResult.CONNECTED, controller.start())
        assertEquals("rokid-1", connector.configs.single().deviceId)
        assertEquals(90, connector.configs.single().telemetry?.batteryPct)
    }

    @Test
    fun lifecycleCallbacksTrackConnectedAndStoppedState() {
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
        var connected = false
        var disconnected = false
        val controller =
                AgentController(
                        settingsStore = settingsStore,
                        connector = connector,
                        onConnected = { connected = true },
                        onDisconnected = { disconnected = true },
                )

        controller.start()
        controller.stop()

        assertTrue(connected)
        assertTrue(disconnected)
        assertTrue(connector.connection.closed)
    }

    @Test
    fun runUntilStoppedReconnectsAfterConnectionCloses() {
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
        lateinit var controller: AgentController
        var disconnects = 0
        controller =
                AgentController(
                        settingsStore = settingsStore,
                        connector = connector,
                        sleeper = {},
                        onDisconnected = {
                            disconnects += 1
                            if (disconnects == 2) {
                                controller.stop()
                            }
                        },
                )

        assertEquals(AgentStartResult.CONNECTED, controller.runUntilStopped())

        assertEquals(2, connector.configs.size)
        assertEquals(2, disconnects)
    }

    @Test
    fun runUntilStoppedKeepsTryingAfterConnectRetryBudgetFails() {
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
        val connector = RecordingConnector(failuresBeforeSuccess = 1)
        lateinit var controller: AgentController
        controller =
                AgentController(
                        settingsStore = settingsStore,
                        connector = connector,
                        sleeper = {},
                        onDisconnected = { controller.stop() },
                )

        assertEquals(AgentStartResult.CONNECTED, controller.runUntilStopped())

        assertEquals(2, connector.configs.size)
    }
}

private class RecordingConnector : AgentConnector {
    constructor()

    constructor(failuresBeforeSuccess: Int) {
        this.failuresBeforeSuccess = failuresBeforeSuccess
    }

    private var failuresBeforeSuccess = 0
    val configs = mutableListOf<AgentConnectionConfig>()
    val connections = mutableListOf<RecordingWsConnection>()
    val connection: RecordingWsConnection
        get() = connections.last()

    override fun connectWithRetry(config: AgentConnectionConfig, maxAttempts: Int): WsConnection {
        configs += config
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess -= 1
            throw RuntimeException("connect failed")
        }
        val connection = RecordingWsConnection()
        connections += connection
        return connection
    }
}

private class RecordingWsConnection : WsConnection {
    var closed = false

    override fun sendText(text: String) = Unit

    override fun sendBytes(bytes: ByteArray) = Unit

    override fun close() {
        closed = true
    }

    override fun awaitClose() {
        closed = true
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
