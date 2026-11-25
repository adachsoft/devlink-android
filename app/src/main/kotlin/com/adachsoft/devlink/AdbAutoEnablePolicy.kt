package com.adachsoft.devlink

/**
 * Decides whether wireless debugging should be enabled for a Wi-Fi connection.
 * This policy has no Android dependencies; the caller supplies the current conditions and prior attempts.
 */
internal object AdbAutoEnablePolicy {
    fun shouldEnable(
        networkHandle: Long,
        attemptedNetworkHandles: Set<Long>,
        wifiNetworkAvailable: Boolean,
        hasIpv4Address: Boolean,
        adbExtensionEnabled: Boolean,
        enableOnBoot: Boolean,
        hasWriteSecureSettingsPermission: Boolean,
        adbWifiEnabled: Boolean,
    ): Boolean =
        networkHandle !in attemptedNetworkHandles &&
            wifiNetworkAvailable &&
            hasIpv4Address &&
            adbExtensionEnabled &&
            enableOnBoot &&
            hasWriteSecureSettingsPermission &&
            !adbWifiEnabled
}
