package com.adachsoft.devlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAutoEnablePolicyTest {
    @Test
    fun enablesWhenAllConditionsAreMet() {
        assertTrue(shouldEnable())
    }

    @Test
    fun doesNotEnableWhenAnyConditionIsNotMet() {
        assertFalse(shouldEnable(wifiNetworkAvailable = false))
        assertFalse(shouldEnable(hasIpv4Address = false))
        assertFalse(shouldEnable(adbExtensionEnabled = false))
        assertFalse(shouldEnable(enableOnBoot = false))
        assertFalse(shouldEnable(hasWriteSecureSettingsPermission = false))
        assertFalse(shouldEnable(adbWifiEnabled = true))
    }

    @Test
    fun enablesAtMostOncePerConnectionAndAllowsANewConnection() {
        assertTrue(shouldEnable())
        assertFalse(shouldEnable(attemptedNetworkHandles = setOf(DEFAULT_NETWORK_HANDLE)))
        assertTrue(
            shouldEnable(
                networkHandle = OTHER_NETWORK_HANDLE,
                attemptedNetworkHandles = setOf(DEFAULT_NETWORK_HANDLE),
            ),
        )
    }

    private fun shouldEnable(
        networkHandle: Long = DEFAULT_NETWORK_HANDLE,
        attemptedNetworkHandles: Set<Long> = emptySet(),
        wifiNetworkAvailable: Boolean = true,
        hasIpv4Address: Boolean = true,
        adbExtensionEnabled: Boolean = true,
        enableOnBoot: Boolean = true,
        hasWriteSecureSettingsPermission: Boolean = true,
        adbWifiEnabled: Boolean = false,
    ): Boolean = AdbAutoEnablePolicy.shouldEnable(
        networkHandle = networkHandle,
        attemptedNetworkHandles = attemptedNetworkHandles,
        wifiNetworkAvailable = wifiNetworkAvailable,
        hasIpv4Address = hasIpv4Address,
        adbExtensionEnabled = adbExtensionEnabled,
        enableOnBoot = enableOnBoot,
        hasWriteSecureSettingsPermission = hasWriteSecureSettingsPermission,
        adbWifiEnabled = adbWifiEnabled,
    )

    private companion object {
        const val DEFAULT_NETWORK_HANDLE = 1L
        const val OTHER_NETWORK_HANDLE = 2L
    }
}
