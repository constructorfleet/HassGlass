package dev.hassglass.agent.ws

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch

class OkHttpWsTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : WsTransport {
    override fun connect(request: WsRequest, listener: WsListener): WsConnection {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val closed = CountDownLatch(1)
        val webSocket = client.newWebSocket(builder.build(), listener.asOkHttpListener(closed))
        return OkHttpWsConnection(webSocket, closed)
    }

    private fun WsListener.asOkHttpListener(closed: CountDownLatch): WebSocketListener =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinary(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    onClosed()
                } finally {
                    closed.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try {
                    onFailure(t)
                } finally {
                    closed.countDown()
                }
            }
        }
}

private class OkHttpWsConnection(
    private val webSocket: WebSocket,
    private val closed: CountDownLatch,
) : WsConnection {
    override fun sendText(text: String) {
        if (!webSocket.send(text)) {
            throw WsException("failed to enqueue WebSocket text frame")
        }
    }

    override fun sendBytes(bytes: ByteArray) {
        if (!webSocket.send(bytes.toByteString())) {
            throw WsException("failed to enqueue WebSocket binary frame")
        }
    }

    override fun close() {
        webSocket.close(1000, "closed")
        closed.countDown()
    }

    override fun awaitClose() {
        closed.await()
    }
}
