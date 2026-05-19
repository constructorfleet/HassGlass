package dev.hassglass.agent.pairing

import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpPairingTransport(
    private val client: OkHttpClient = defaultClient(),
) : PairingTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override fun post(request: PairingRequest): PairingClaimResponse {
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(json.encodeToString(request.payload).toRequestBody(mediaType))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw PairingException("pairing failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw PairingException("pairing response was empty")
            return json.decodeFromString<PairingClaimResponse>(body)
        }
    }

    companion object {
        /**
         * The pairing endpoint long-polls — HA holds the request open until the user enters the
         * code (or the broker's 120 s TTL elapses). Default OkHttp read timeout is 10 s; bump the
         * read/call budgets so the agent matches the server's window.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

class PairingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
