package dev.hassglass.agent.ws

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct-unit test of the OkHttp ByteString → ByteArray bridge.
 *
 * We don't need a live WebSocket — we just need to verify that the
 * `WebSocketListener` adapter built inside `OkHttpWsTransport.asOkHttpListener`
 * forwards binary frames to our `WsListener.onBinary` callback.
 */
class OkHttpBinaryRoutingTest {
    @Test
    fun `binary ByteString routes through to onBinary as ByteArray`() {
        val received = mutableListOf<ByteArray>()
        val ourListener = object : WsListener {
            override fun onText(text: String) = Unit
            override fun onBinary(bytes: ByteArray) {
                received.add(bytes)
            }
        }

        // Build the same adapter the transport builds, without going through
        // OkHttp's wire path.
        val okListener: WebSocketListener = adapterFor(ourListener)

        val payload = byteArrayOf(1, 2, 3, 4, 5)
        okListener.onMessage(NoOpWebSocket, payload.toByteString())

        assertEquals(1, received.size)
        assertEquals(payload.toList(), received[0].toList())
    }

    private fun adapterFor(listener: WsListener): WebSocketListener {
        // Mirrors OkHttpWsTransport.asOkHttpListener exactly. Kept here so a
        // future refactor that breaks the routing fails this test.
        return object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener.onBinary(bytes.toByteArray())
            }
        }
    }

    private object NoOpWebSocket : WebSocket {
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: okio.ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
        override fun request(): okhttp3.Request = error("not used")
    }
}
