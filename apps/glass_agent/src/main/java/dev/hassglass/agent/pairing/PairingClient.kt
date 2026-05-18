package dev.hassglass.agent.pairing

import dev.hassglass.agent.settings.AgentSettingsStore
import dev.hassglass.agent.settings.PairedAgentSettings

private const val PAIRING_PATH = "/api/hassglass/pair"

class PairingClient(
    private val transport: PairingTransport,
    private val settingsStore: AgentSettingsStore,
) {
    fun claim(
        haBaseUrl: String,
        code: String,
        identity: AgentIdentity,
    ): PairedAgentSettings {
        val response = transport.post(
            PairingRequest(
                url = "${haBaseUrl.trimEnd('/')}$PAIRING_PATH",
                payload = PairingClaimPayload(
                    code = code,
                    deviceId = identity.deviceId,
                    serial = identity.serial,
                    firmware = identity.firmware,
                    agentVersion = identity.agentVersion,
                    name = identity.name,
                ),
            ),
        )
        val settings = PairedAgentSettings(
            haBaseUrl = haBaseUrl,
            deviceId = response.deviceId,
            serial = identity.serial,
            firmware = identity.firmware,
            agentVersion = identity.agentVersion,
            token = response.token,
        )
        settingsStore.savePairedSettings(settings)
        return settings
    }
}
