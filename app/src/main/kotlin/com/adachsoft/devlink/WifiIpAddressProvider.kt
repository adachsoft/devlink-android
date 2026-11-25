package com.adachsoft.devlink

import android.content.Context
import android.net.wifi.WifiManager

internal object WifiIpAddressProvider {
    @Suppress("DEPRECATION")
    fun currentAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return UNAVAILABLE
        val address = wifiManager.connectionInfo.ipAddress
        if (address == 0) {
            return UNAVAILABLE
        }
        return (0..3).joinToString(".") { byteIndex ->
            ((address ushr (byteIndex * 8)) and 0xff).toString()
        }
    }

    private const val UNAVAILABLE = "unavailable"
}
