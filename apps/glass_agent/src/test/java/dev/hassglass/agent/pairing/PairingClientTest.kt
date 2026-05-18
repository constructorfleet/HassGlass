package dev.hassglass.agent.pairing

import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingClientTest {
    @Test
    fun generatedPairingCodeIsSixDigits() {
        repeat(50) {
            val code = PairingCodeGenerator.generate()
            assertEquals(6, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }

    @Test
    fun pairingPostsClaimAndPersistsReturnedToken() {
        val transport = RecordingPairingTransport(
            response = PairingClaimResponse(token = "issued-token", deviceId = "rokid-1"),
        )
        val settingsStore = AgentSettingsStore(InMemoryKeyValueStore())
        val client = PairingClient(transport, settingsStore)

        val settings = client.claim(
            haBaseUrl = "https://ha.example",
            code = "123456",
            identity = AgentIdentity(
                deviceId = "rokid-1",
                serial = "SN123",
                firmware = "1.2.3",
                agentVersion = "0.1.0",
                name = "Kitchen Glasses",
            ),
        )

        assertEquals("https://ha.example/api/hassglass/pair", transport.requests.single().url)
        assertEquals("123456", transport.requests.single().payload.code)
        assertEquals("SN123", transport.requests.single().payload.serial)
        assertEquals("issued-token", settings.token)
        assertEquals(settings, settingsStore.loadPairedSettings())
    }
}

private class RecordingPairingTransport(
    private val response: PairingClaimResponse,
) : PairingTransport {
    val requests = mutableListOf<PairingRequest>()

    override fun post(request: PairingRequest): PairingClaimResponse {
        requests += request
        return response
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
