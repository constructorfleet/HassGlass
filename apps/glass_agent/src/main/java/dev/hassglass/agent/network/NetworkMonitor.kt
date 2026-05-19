package dev.hassglass.agent.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

sealed class NetworkEvent {
    object Available : NetworkEvent()
    object Lost : NetworkEvent()
}

fun interface NetworkObserver {
    fun onNetworkEvent(event: NetworkEvent)
}

/** A small abstraction so we can unit-test without Android framework types. */
interface NetworkRegistrar {
    fun register(cb: Callback)
    fun unregister(cb: Callback)

    interface Callback {
        fun onAvailable()
        fun onLost()
    }
}

private class AndroidNetworkRegistrar(private val cm: ConnectivityManager) : NetworkRegistrar {
    private val mapping = mutableMapOf<NetworkRegistrar.Callback, ConnectivityManager.NetworkCallback>()

    override fun register(cb: NetworkRegistrar.Callback) {
        val nc = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cb.onAvailable()
            }

            override fun onLost(network: Network) {
                cb.onLost()
            }
        }
        mapping[cb] = nc
        try {
            cm.registerDefaultNetworkCallback(nc)
        } catch (e: NoSuchMethodError) {
            // Fallback for older SDKs (unlikely in this project environment)
        }
    }

    override fun unregister(cb: NetworkRegistrar.Callback) {
        val nc = mapping.remove(cb) ?: return
        cm.unregisterNetworkCallback(nc)
    }
}

class NetworkMonitor(
    context: Context?,
    private val observer: NetworkObserver,
    private val registrar: NetworkRegistrar? = null,
) {
    private val registrarImpl: NetworkRegistrar = registrar
        ?: AndroidNetworkRegistrar(context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)

    private var cb: NetworkRegistrar.Callback? = null

    fun start() {
        if (cb != null) return
        cb = object : NetworkRegistrar.Callback {
            override fun onAvailable() {
                observer.onNetworkEvent(NetworkEvent.Available)
            }

            override fun onLost() {
                observer.onNetworkEvent(NetworkEvent.Lost)
            }
        }
        registrarImpl.register(cb!!)
    }

    fun stop() {
        val c = cb ?: return
        registrarImpl.unregister(c)
        cb = null
    }
}
