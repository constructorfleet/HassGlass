package dev.hassglass.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Resolves Home Assistant's `_home-assistant._tcp.local.` advertisement via Android NSD.
 *
 * HA's zeroconf integration advertises this service by default, so the agent never has to ask
 * the user for a base URL. We prefer the HTTPS URLs HA publishes in its TXT records
 * (`internal_url`, then `base_url`) so we don't have to whitelist cleartext traffic on
 * Android's network security policy. Resolution happens off the main thread because NSD's
 * callbacks fire on internal worker threads.
 */
class HomeAssistantDiscovery(
    private val nsdManager: NsdManager,
) {
    /**
     * A resolved HA advertisement.
     *
     * `baseUrl` is the preferred URL to talk to HA — derived from the `internal_url` / `base_url`
     * TXT records when they're present and HTTPS. If neither is usable, `baseUrl` is null and the
     * caller should fail the operation rather than fall back to cleartext over [host]:[port].
     */
    data class Discovered(
        val host: String,
        val port: Int,
        val baseUrl: String?,
        val source: String,
    )

    fun discover(listener: Listener): Handle {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service found: name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        val port = resolved.port
                        val (baseUrl, source) = pickBaseUrl(resolved.attributes.orEmpty())
                        Log.i(
                            TAG,
                            "service resolved: name=${resolved.serviceName} host=$host port=$port " +
                                "baseUrl=$baseUrl source=$source",
                        )
                        if (host == null || port <= 0) {
                            listener.onError("resolve returned host=$host port=$port")
                            return
                        }
                        listener.onDiscovered(Discovered(host, port, baseUrl, source))
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed: code=$errorCode name=${serviceInfo.serviceName}")
                        listener.onError("resolve failed: $errorCode")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service lost: name=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: code=$errorCode type=$serviceType")
                listener.onError("discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: code=$errorCode type=$serviceType")
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
        private const val TAG = "HassGlass"

        fun fromContext(context: Context): HomeAssistantDiscovery =
            HomeAssistantDiscovery(context.getSystemService(Context.NSD_SERVICE) as NsdManager)

        /**
         * Returns the first HTTPS URL HA advertised, in order of preference, along with the TXT
         * record name it came from. Returns null + "none" if no usable URL is present — in that
         * case the caller should NOT silently fall back to a cleartext IP+port URL.
         */
        internal fun pickBaseUrl(attributes: Map<String, ByteArray?>): Pair<String?, String> {
            for (key in listOf("internal_url", "base_url", "external_url")) {
                val raw = attributes[key]?.toString(Charsets.UTF_8)?.trim()
                if (!raw.isNullOrEmpty() && raw.startsWith("https://")) {
                    return raw.trimEnd('/') to key
                }
            }
            return null to "none"
        }
    }
}
