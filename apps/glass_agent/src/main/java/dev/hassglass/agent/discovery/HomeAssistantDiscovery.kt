package dev.hassglass.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Resolves Home Assistant's `_home-assistant._tcp.local.` advertisement via Android NSD.
 *
 * HA's zeroconf integration advertises this service by default, so the agent never has to ask
 * the user for a base URL. Resolution happens off the main thread because NSD's callbacks fire
 * on internal worker threads.
 */
class HomeAssistantDiscovery(
    private val nsdManager: NsdManager,
) {
    data class Discovered(val host: String, val port: Int) {
        val baseUrl: String get() = "http://$host:$port"
    }

    fun discover(listener: Listener): Handle {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val port = resolved.port.takeIf { it > 0 } ?: return
                        listener.onDiscovered(Discovered(host, port))
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        listener.onError("resolve failed: $errorCode")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onError("discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onError("discovery stop failed: $errorCode")
            }
        }
        nsdManager.discoverServices(HA_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        return Handle { runCatching { nsdManager.stopServiceDiscovery(discoveryListener) } }
    }

    fun interface Listener {
        fun onDiscovered(result: Discovered)
        fun onError(message: String) = Unit
    }

    fun interface Handle {
        fun stop()
    }

    companion object {
        const val HA_SERVICE_TYPE = "_home-assistant._tcp."

        fun fromContext(context: Context): HomeAssistantDiscovery =
            HomeAssistantDiscovery(context.getSystemService(Context.NSD_SERVICE) as NsdManager)
    }
}
