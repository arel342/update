package com.nodelix.coreos.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

data class LocalNetworkStatus(
    val isOnline: Boolean = false,
    val isWifiOrEthernet: Boolean = false,
    val localIpv4Address: String? = null
)

/**
 * Observes Android network connectivity and Wi-Fi/LAN link status without blocking main thread.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(readCurrentStatus())
    val status: StateFlow<LocalNetworkStatus> = _status.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _status.value = readCurrentStatus()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _status.value = readCurrentStatus()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            _status.value = readCurrentStatus()
        }

        override fun onLost(network: Network) {
            _status.value = readCurrentStatus()
        }
    }

    fun startMonitoring() {
        if (registered) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }
        _status.value = readCurrentStatus()
    }

    fun stopMonitoring() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun refreshNow(): LocalNetworkStatus {
        val current = readCurrentStatus()
        _status.value = current
        return current
    }

    private fun readCurrentStatus(): LocalNetworkStatus {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
                ?: return LocalNetworkStatus(isOnline = false, isWifiOrEthernet = false, localIpv4Address = null)
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return LocalNetworkStatus(isOnline = false, isWifiOrEthernet = false, localIpv4Address = null)

            val wifiOrEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val online = wifiOrEth || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            val linkProps = connectivityManager.getLinkProperties(activeNetwork)
            val ipv4 = linkProps?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress

            LocalNetworkStatus(
                isOnline = online,
                isWifiOrEthernet = wifiOrEth,
                localIpv4Address = ipv4
            )
        } catch (_: Exception) {
            LocalNetworkStatus()
        }
    }
}
