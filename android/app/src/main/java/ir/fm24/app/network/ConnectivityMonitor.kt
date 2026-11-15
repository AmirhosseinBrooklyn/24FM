package ir.fm24.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** Snapshot of the device's default network. */
data class NetState(
    val available: Boolean,
    /** Android verified that this network actually reaches the internet. */
    val validated: Boolean,
    /** Changes whenever the default network changes (Wi-Fi to mobile, VPN on/off...). */
    val networkId: String?,
)

/**
 * Callback-based connectivity monitoring (no polling). The flow is shared app-wide and only
 * holds a system callback while something is collecting it.
 */
class ConnectivityMonitor(context: Context, scope: CoroutineScope) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val state: StateFlow<NetState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(snapshot(network))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(snapshot(network, caps))
            }

            override fun onLost(network: Network) {
                // The default network is gone. activeNetwork can still return it at this
                // moment, so don't query it; onAvailable follows if another network takes over.
                trySend(OFFLINE)
            }

            override fun onUnavailable() {
                trySend(OFFLINE)
            }
        }
        trySend(currentState())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
        .conflate()
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentState())

    fun currentState(): NetState {
        val network = cm.activeNetwork ?: return OFFLINE
        return snapshot(network)
    }

    private fun snapshot(network: Network, caps: NetworkCapabilities? = cm.getNetworkCapabilities(network)): NetState {
        if (caps == null) return OFFLINE
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetState(available = internet, validated = internet && validated, networkId = network.toString())
    }

    companion object {
        val OFFLINE = NetState(available = false, validated = false, networkId = null)
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
