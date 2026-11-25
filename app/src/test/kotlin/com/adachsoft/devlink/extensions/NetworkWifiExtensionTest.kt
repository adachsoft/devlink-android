package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkWifiExtensionTest {
    private val wifiConnection = FakeWifiConnection()
    private val extension = NetworkWifiExtension(wifiConnection, FakeExtensionSettings())

    @Test
    fun returnsTheCurrentWifiState() {
        val response = extension.handle("GET", "/state", null)!!

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("\"connected\":true"))
        assertTrue(response.body.contains("\"lastDisconnectAt\":\"2026-01-02T03:04:05Z\""))
    }

    @Test
    fun reconnectReturnsAcceptedNetworkRequest() {
        val response = extension.handle("POST", "/reconnect", null)!!

        assertEquals(202, response.statusCode)
        assertTrue(response.body.contains("\"accepted\":true"))
        assertTrue(response.body.contains("\"method\":\"network-request\""))
        assertEquals(1, wifiConnection.reconnectRequests)
    }

    private class FakeWifiConnection : WifiConnection {
        var reconnectRequests = 0

        override fun currentState(): WifiConnectionState = WifiConnectionState(
            connected = true,
            ssid = "test-network",
            bssid = "00:11:22:33:44:55",
            ipAddress = "192.168.1.22",
            rssiDbm = -48,
            linkSpeedMbps = 144,
            frequencyMhz = 5180,
            lastDisconnectAt = "2026-01-02T03:04:05Z",
        )

        override fun requestReconnect() {
            reconnectRequests++
        }
    }

    private class FakeExtensionSettings : ExtensionSettings {
        override fun isEnabled(extensionId: String): Boolean = true

        override fun setEnabled(extensionId: String, enabled: Boolean) = Unit

        override fun isAdbEnableOnBoot(): Boolean = false

        override fun setAdbEnableOnBoot(enabled: Boolean) = Unit
    }
}
