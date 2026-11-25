package com.adachsoft.devlink

import android.content.Context
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.net.Inet4Address

internal object WifiIpAddressProvider {
    @Suppress("DEPRECATION")
    fun currentAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val address = wifiManager.connectionInfo?.ipAddress ?: return null
        if (address == 0) {
            return null
        }
        return (0..3).joinToString(".") { byteIndex ->
            ((address ushr (byteIndex * 8)) and 0xff).toString()
        }
    }

    fun ipv4Address(linkProperties: LinkProperties?): String? {
        val address = linkProperties?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address as? Inet4Address
        return address?.hostAddress
    }
}
