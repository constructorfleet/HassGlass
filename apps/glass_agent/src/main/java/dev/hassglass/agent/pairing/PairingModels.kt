package dev.hassglass.agent.pairing

import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class AgentIdentity(
    val deviceId: String,
    val serial: String,
    val firmware: String,
    val agentVersion: String,
    val name: String,
)

@Serializable
data class PairingClaimPayload(
    val code: String,
    @SerialName("device_id")
    val deviceId: String,
    val serial: String,
    val firmware: String,
    @SerialName("agent_version")
    val agentVersion: String,
    val name: String,
)

data class PairingRequest(
    val url: String,
    val payload: PairingClaimPayload,
)

@Serializable
data class PairingClaimResponse(
    val token: String,
    @SerialName("device_id")
    val deviceId: String,
)

interface PairingTransport {
    fun post(request: PairingRequest): PairingClaimResponse
}

object PairingCodeGenerator {
    fun generate(): String = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
}

interface PairingCodeRenderer {
    fun showPairingCode(code: String)
}
