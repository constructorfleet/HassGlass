package dev.hassglass.agent.ws

import dev.hassglass.agent.protocol.MessageType
import dev.hassglass.agent.protocol.ProtocolCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonPrimitive

class WsClientTest {
    @Test
    fun connectOnceUsesBearerAuthAndSendsHelloThenTelemetry() {
        val transport = FakeTransport()
        val client = WsClient(transport)

        client.connectOnce(
                AgentConnectionConfig(
                        haBaseUrl = "https://ha.example",
                        deviceId = "rokid-1",
                        serial = "SN123",
                        firmware = "1.2.3",
                        agentVersion = "0.1.0",
                        token = "device-token",
                        telemetry = TelemetrySnapshot(batteryPct = 82, rssiDbm = -61, worn = true),
                ),
        )

        assertEquals("wss://ha.example/api/hassglass/ws/v1", transport.requests.single().url)
        assertEquals("Bearer device-token", transport.requests.single().headers["Authorization"])

        val hello = ProtocolCodec.decodeMessage(transport.connection.sentText[0])
        assertEquals(MessageType.HELLO, hello.type)
        assertEquals("rokid-1", hello.fields["device_id"]?.jsonPrimitive?.content)
        assertEquals("SN123", hello.fields["serial"]?.jsonPrimitive?.content)

        val telemetry = ProtocolCodec.decodeMessage(transport.connection.sentText[1])
        assertEquals(MessageType.TELEMETRY, telemetry.type)
        assertEquals("82", telemetry.fields["battery_pct"]?.jsonPrimitive?.content)
        assertEquals("-61", telemetry.fields["rssi_dbm"]?.jsonPrimitive?.content)
    }

    @Test
    fun pingMessageReceivesPong() {
        val transport = FakeTransport()
        val client = WsClient(transport)

        client.connectOnce(minimalConfig())
        assertNotNull(transport.listener).onText("""{"type":"ping"}""")

        val pong = ProtocolCodec.decodeMessage(transport.connection.sentText.last())
        assertEquals(MessageType.PONG, pong.type)
    }

    @Test
    fun nonPingTextAndBinaryFramesRouteToRuntimeCallbacks() {
        val transport = FakeTransport()
        val receivedText = mutableListOf<String>()
        val receivedBinary = mutableListOf<ByteArray>()
        val client =
                WsClient(
                        transport = transport,
                        onTextMessage = { receivedText.add(it) },
                        onBinaryFrame = { receivedBinary.add(it) },
                )

        client.connectOnce(minimalConfig())
        transport.listener!!.onText("""{"type":"hud.dismiss","id":"x"}""")
        transport.listener!!.onBinary(byteArrayOf(1, 2, 3))

        assertEquals(listOf("""{"type":"hud.dismiss","id":"x"}"""), receivedText)
        assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), receivedBinary.map { it.toList() })
    }

    @Test
    fun connectWithRetryRetriesAfterFailure() {
        val transport = FakeTransport(failuresBeforeSuccess = 2)
        val delays = mutableListOf<Long>()
        val client =
                WsClient(
                        transport = transport,
                        retryPolicy = RetryPolicy(initialDelayMs = 100, maxDelayMs = 500),
                        sleeper = delays::add,
                )

        client.connectWithRetry(minimalConfig(), maxAttempts = 3)

        assertEquals(3, transport.requests.size)
        assertEquals(listOf(100L, 200L), delays)
    }

    private fun minimalConfig(): AgentConnectionConfig =
            AgentConnectionConfig(
                    haBaseUrl = "https://ha.example",
                    deviceId = "rokid-1",
                    serial = "SN123",
                    firmware = "1.2.3",
                    agentVersion = "0.1.0",
                    token = "device-token",
            )
}

private class FakeTransport(
        private var failuresBeforeSuccess: Int = 0,
) : WsTransport {
    val requests = mutableListOf<WsRequest>()
    val connection = FakeConnection()
    var listener: WsListener? = null

    override fun connect(request: WsRequest, listener: WsListener): WsConnection {
        requests += request
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess -= 1
            throw WsException("connect failed")
        }
        this.listener = listener
        return connection
    }
}

private class FakeConnection : WsConnection {
    val sentText = mutableListOf<String>()

    override fun sendText(text: String) {
        sentText += text
    }

    override fun sendBytes(bytes: ByteArray) = Unit

    override fun close() = Unit
}
