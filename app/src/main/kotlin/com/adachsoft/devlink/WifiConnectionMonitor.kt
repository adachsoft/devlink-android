package com.adachsoft.devlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.adachsoft.devlink.extensions.WifiConnection
import com.adachsoft.devlink.extensions.WifiConnectionState
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal class WifiConnectionMonitor(
    context: Context,
    private val onWifiNetworkChanged: (Network?, String?) -> Unit = { _, _ -> },
) : WifiConnection {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiNetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    @Volatile
    private var lastDisconnectAt: String? = null
    private var callbackRegistered = false
    private var currentWifiNetwork: Network? = null
    private var currentWifiLinkProperties: LinkProperties? = null
    private var currentWifiIpAddress: String? = null

    private val monitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            setCurrentWifiNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                setCurrentWifiNetwork(network)
                updateCurrentWifiAddress()
            } else if (network == currentWifiNetwork) {
                clearCurrentWifiNetwork()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != currentWifiNetwork) {
                return
            }
            currentWifiLinkProperties = linkProperties
            updateCurrentWifiAddress()
        }

        override fun onLost(network: Network) {
            lastDisconnectAt = Instant.now().toString()
            if (network == currentWifiNetwork) {
                clearCurrentWifiNetwork()
            }
        }
    }

    @Synchronized
    fun start() {
        if (callbackRegistered) {
            return
        }
        connectivityManager.registerNetworkCallback(wifiNetworkRequest, monitorCallback)
        callbackRegistered = true
    }

    @Synchronized
    fun stop() {
        if (!callbackRegistered) {
            return
        }
        try {
            connectivityManager.unregisterNetworkCallback(monitorCallback)
        } catch (_: IllegalArgumentException) {
            // The callback may already have been removed by the system.
        }
        callbackRegistered = false
    }

    @Suppress("DEPRECATION")
    override fun currentState(): WifiConnectionState {
        val network = connectivityManager.activeNetwork
        val connected = network != null && connectivityManager
            .getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!connected) {
            return WifiConnectionState(connected = false, lastDisconnectAt = lastDisconnectAt)
        }

        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_SSID, ignoreCase = true) }
        val bssid = wifiInfo?.bssid
            ?.takeIf { it.isNotBlank() && !it.equals(REDACTED_BSSID, ignoreCase = true) }

        return WifiConnectionState(
            connected = true,
            ssid = ssid,
            bssid = bssid,
            ipAddress = WifiIpAddressProvider.currentAddress(applicationContext),
            rssiDbm = wifiInfo?.rssi?.takeUnless { it == INVALID_RSSI },
            linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it >= 0 },
            frequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
            lastDisconnectAt = lastDisconnectAt,
        )
    }

    override fun requestReconnect() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val finished = AtomicBoolean(false)

            private fun unregister() {
                if (!finished.compareAndSet(false, true)) {
                    return
                }
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (_: IllegalArgumentException) {
                    // Timed-out requests may already have been removed by the system.
                }
            }

            override fun onAvailable(network: Network) {
                unregister()
            }

            override fun onUnavailable() {
                unregister()
            }

            override fun onLost(network: Network) {
                unregister()
            }
        }

        try {
            connectivityManager.requestNetwork(wifiNetworkRequest, callback, RECONNECT_TIMEOUT_MS)
        } catch (_: SecurityException) {
            // Reconnection is best effort; the API still reports that the request was accepted.
        } catch (_: IllegalArgumentException) {
            // Reconnection is best effort; there is no deprecated Wi-Fi toggle fallback.
        }
    }

    private fun setCurrentWifiNetwork(network: Network) {
        if (currentWifiNetwork == network) {
            return
        }
        currentWifiNetwork = network
        currentWifiLinkProperties = null
        currentWifiIpAddress = null
        onWifiNetworkChanged(network, null)
    }

    private fun updateCurrentWifiAddress() {
        val network = currentWifiNetwork ?: return
        val address = WifiIpAddressProvider.ipv4Address(currentWifiLinkProperties)
        if (address == currentWifiIpAddress) {
            return
        }
        currentWifiIpAddress = address
        onWifiNetworkChanged(network, address)
    }

    private fun clearCurrentWifiNetwork() {
        if (currentWifiNetwork == null) {
            return
        }
        currentWifiNetwork = null
        currentWifiLinkProperties = null
        currentWifiIpAddress = null
        onWifiNetworkChanged(null, null)
    }

    companion object {
        /** Hidden WifiInfo.INVALID_RSSI value (-127), not part of the public SDK. */
        private const val INVALID_RSSI = -127
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        private const val RECONNECT_TIMEOUT_MS = 15_000
    }
}
