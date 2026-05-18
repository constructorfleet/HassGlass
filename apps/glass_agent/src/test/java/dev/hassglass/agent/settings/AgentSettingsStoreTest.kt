package dev.hassglass.agent.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSettingsStoreTest {
    @Test
    fun loadReturnsNullWhenRequiredFieldsAreMissing() {
        val store = AgentSettingsStore(InMemoryKeyValueStore())

        assertNull(store.loadPairedSettings())
    }

    @Test
    fun saveAndLoadRoundTripsPairedSettings() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = AgentSettingsStore(keyValueStore)
        val settings = PairedAgentSettings(
            haBaseUrl = "https://ha.example",
            deviceId = "rokid-1",
            serial = "SN123",
            firmware = "1.2.3",
            agentVersion = "0.1.0",
            token = "device-token",
        )

        store.savePairedSettings(settings)

        assertEquals(settings, store.loadPairedSettings())
    }

    @Test
    fun clearPairingRemovesStoredSettings() {
        val store = AgentSettingsStore(InMemoryKeyValueStore())
        store.savePairedSettings(
            PairedAgentSettings(
                haBaseUrl = "https://ha.example",
                deviceId = "rokid-1",
                serial = "SN123",
                firmware = "1.2.3",
                agentVersion = "0.1.0",
                token = "device-token",
            ),
        )

        store.clearPairing()

        assertNull(store.loadPairedSettings())
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
