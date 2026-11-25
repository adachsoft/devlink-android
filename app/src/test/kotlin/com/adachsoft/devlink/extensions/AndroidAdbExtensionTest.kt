package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.SecureSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdbExtensionTest {
    private val secureSettings = FakeSecureSettings()
    private val extensionSettings = FakeExtensionSettings()
    private var discoveredPort: Int? = null
    private var discoveredPortClearCount = 0
    private val extension = AndroidAdbExtension(
        secureSettings = secureSettings,
        extensionSettings = extensionSettings,
        ipAddressProvider = { "192.168.1.22" },
        discoveredPortProvider = { discoveredPort },
        onWirelessDebuggingDisabled = {
            discoveredPort = null
            discoveredPortClearCount++
        },
    )

    @Test
    fun enablingWirelessDebuggingWithoutPermissionReturnsConflict() {
        secureSettings.writePermission = false

        val response = extension.handle("POST", "/wireless/enable", null)!!

        assertEquals(409, response.statusCode)
        val problem = JSONObject(response.body)
        assertEquals("permission-missing", problem.getString("code"))
        assertTrue(problem.getString("detail").contains(AndroidAdbExtension.WRITE_SECURE_SETTINGS_GRANT_COMMAND))
        assertEquals(0, secureSettings.writeCount)
    }

    @Test
    fun enableOnBootRejectsNonBooleanEnabledField() {
        val response = extension.handle(
            "PUT",
            "/enable-on-boot",
            JSONObject().put("enabled", "true"),
        )!!

        assertEquals(400, response.statusCode)
        assertEquals("invalid-request", JSONObject(response.body).getString("code"))
        assertFalse(extensionSettings.isAdbEnableOnBoot())
    }

    @Test
    fun enablingWirelessDebuggingWritesGlobalSettingAndReturnsState() {
        val response = extension.handle("POST", "/wireless/enable", null)!!

        assertEquals(200, response.statusCode)
        assertEquals(1, secureSettings.values[AndroidAdbExtension.ADB_WIFI_ENABLED_SETTING])
        assertTrue(JSONObject(response.body).getBoolean("wirelessDebuggingEnabled"))
    }

    @Test
    fun disabledWirelessDebuggingReportsNullPortAndClearsDiscoveredPort() {
        secureSettings.values[AndroidAdbExtension.ADB_WIFI_ENABLED_SETTING] = 0
        secureSettings.values[AndroidAdbExtension.ADB_WIFI_PORT_SETTING] = 5555
        discoveredPort = 5556

        val response = extension.handle("GET", "/state", null)!!

        assertEquals(200, response.statusCode)
        val state = JSONObject(response.body)
        assertFalse(state.getBoolean("wirelessDebuggingEnabled"))
        assertTrue(state.isNull("port"))
        assertEquals(null, discoveredPort)
        assertEquals(1, discoveredPortClearCount)
    }

    private class FakeSecureSettings : SecureSettings {
        val values = mutableMapOf<String, Int>()
        var writePermission: Boolean = true
        var writeCount: Int = 0

        override fun getInt(name: String): Int? = values[name]

        override fun putInt(name: String, value: Int): Boolean {
            if (!writePermission) {
                return false
            }
            writeCount++
            values[name] = value
            return true
        }

        override fun hasWritePermission(): Boolean = writePermission
    }

    private class FakeExtensionSettings : ExtensionSettings {
        private var enableOnBoot = false

        override fun isEnabled(extensionId: String): Boolean = extensionId == AndroidAdbExtension.ID

        override fun setEnabled(extensionId: String, enabled: Boolean) = Unit

        override fun isAdbEnableOnBoot(): Boolean = enableOnBoot

        override fun setAdbEnableOnBoot(enabled: Boolean) {
            enableOnBoot = enabled
        }
    }
}
