package dev.hassglass.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.hassglass.agent.pairing.AgentIdentity

/**
 * Publishes a `_hassglass._tcp.local.` mDNS record so HA's zeroconf integration surfaces the
 * device under "Integrations → Discovered" while pairing is in flight. TXT records carry the
 * device's identity plus the active pairing code — HA reads them in the discovery flow.
 *
 * The port is symbolic (the agent does not run a server); HA only needs the TXT records.
 */
class HassGlassAdvertiser(
    private val nsdManager: NsdManager,
) {
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun advertise(identity: AgentIdentity, code: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = identity.name.ifBlank { "HassGlass" } + "-${identity.deviceId.take(6)}"
            serviceType = SERVICE_TYPE
            port = 1
            setAttribute("device_id", identity.deviceId)
            setAttribute("name", identity.name)
            setAttribute("serial", identity.serial)
            setAttribute("firmware", identity.firmware)
            setAttribute("agent_version", identity.agentVersion)
            setAttribute("code", code)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrationListener = listener
    }

    fun unregister() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    companion object {
        const val SERVICE_TYPE = "_hassglass._tcp."

        fun fromContext(context: Context): HassGlassAdvertiser =
            HassGlassAdvertiser(context.getSystemService(Context.NSD_SERVICE) as NsdManager)
    }
}
