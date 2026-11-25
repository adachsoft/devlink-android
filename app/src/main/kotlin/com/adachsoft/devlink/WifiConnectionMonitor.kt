package com.adachsoft.devlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.adachsoft.devlink.extensions.WifiConnection
import com.adachsoft.devlink.extensions.WifiConnectionState
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal class WifiConnectionMonitor(context: Context) : WifiConnection {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiNetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    @Volatile
    private var lastDisconnectAt: String? = null
    private var callbackRegistered = false

    private val monitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            lastDisconnectAt = Instant.now().toString()
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
            rssiDbm = wifiInfo?.rssi?.takeUnless { it == WifiInfo.INVALID_RSSI },
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

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        private const val RECONNECT_TIMEOUT_MS = 15_000
    }
}
