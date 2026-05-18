package dev.hassglass.agent.ws

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class OkHttpWsTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : WsTransport {
    override fun connect(request: WsRequest, listener: WsListener): WsConnection {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val webSocket = client.newWebSocket(builder.build(), listener.asOkHttpListener())
        return OkHttpWsConnection(webSocket)
    }

    private fun WsListener.asOkHttpListener(): WebSocketListener =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinary(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure(t)
            }
        }
}

private class OkHttpWsConnection(
    private val webSocket: WebSocket,
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
    }
}
