package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.SecureSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerKeepAwakeExtensionTest {
    private val secureSettings = FakeSecureSettings()
    private val extension = PowerKeepAwakeExtension(secureSettings, FakeExtensionSettings())

    @Test
    fun mapsTheGlobalMaskToEnabledAndPowerSources() {
        secureSettings.values[PowerKeepAwakeExtension.STAY_ON_WHILE_PLUGGED_IN_SETTING] = 5

        val response = extension.handle("GET", "/state", null)!!
        val state = JSONObject(response.body)
        val sources = state.getJSONArray("sources")

        assertEquals(200, response.statusCode)
        assertTrue(state.getBoolean("enabled"))
        assertEquals(listOf("ac", "wireless"), (0 until sources.length()).map { sources.getString(it) })
    }

    @Test
    fun updatesTheMaskFromRequestedSources() {
        val response = extension.handle(
            "PUT",
            "/state",
            JSONObject()
                .put("enabled", true)
                .put("sources", JSONArray().put("usb").put("wireless")),
        )!!
        val sources = JSONObject(response.body).getJSONArray("sources")

        assertEquals(200, response.statusCode)
        assertEquals(6, secureSettings.values[PowerKeepAwakeExtension.STAY_ON_WHILE_PLUGGED_IN_SETTING])
        assertEquals(listOf("usb", "wireless"), (0 until sources.length()).map { sources.getString(it) })
    }

    @Test
    fun defaultsEnabledRequestsToAllPowerSources() {
        val response = extension.handle(
            "PUT",
            "/state",
            JSONObject().put("enabled", true),
        )!!

        assertEquals(200, response.statusCode)
        assertEquals(7, secureSettings.values[PowerKeepAwakeExtension.STAY_ON_WHILE_PLUGGED_IN_SETTING])
    }

    @Test
    fun rejectsUnknownSourceValues() {
        val response = extension.handle(
            "PUT",
            "/state",
            JSONObject()
                .put("enabled", true)
                .put("sources", JSONArray().put("battery")),
        )!!

        assertEquals(400, response.statusCode)
        assertEquals("invalid-request", JSONObject(response.body).getString("code"))
        assertTrue(secureSettings.values.isEmpty())
    }

    @Test
    fun refusesWritesWithoutSecureSettingsPermission() {
        secureSettings.writePermission = false

        val response = extension.handle(
            "PUT",
            "/state",
            JSONObject().put("enabled", true),
        )!!

        assertEquals(409, response.statusCode)
        assertEquals("permission-missing", JSONObject(response.body).getString("code"))
        assertTrue(secureSettings.values.isEmpty())
    }

    @Test
    fun disablingKeepAwakeClearsAllSources() {
        secureSettings.values[PowerKeepAwakeExtension.STAY_ON_WHILE_PLUGGED_IN_SETTING] = 7

        val response = extension.handle(
            "PUT",
            "/state",
            JSONObject().put("enabled", false),
        )!!

        assertEquals(200, response.statusCode)
        assertEquals(0, secureSettings.values[PowerKeepAwakeExtension.STAY_ON_WHILE_PLUGGED_IN_SETTING])
        assertFalse(JSONObject(response.body).getBoolean("enabled"))
    }

    private class FakeSecureSettings : SecureSettings {
        val values = mutableMapOf<String, Int>()
        var writePermission: Boolean = true

        override fun getInt(name: String): Int? = values[name]

        override fun putInt(name: String, value: Int): Boolean {
            if (!writePermission) {
                return false
            }
            values[name] = value
            return true
        }

        override fun hasWritePermission(): Boolean = writePermission
    }

    private class FakeExtensionSettings : ExtensionSettings {
        private val enabled = mutableMapOf<String, Boolean>()
        private var enableOnBoot = false

        override fun isEnabled(extensionId: String): Boolean = enabled[extensionId] ?: false

        override fun setEnabled(extensionId: String, enabled: Boolean) {
            this.enabled[extensionId] = enabled
        }

        override fun isAdbEnableOnBoot(): Boolean = enableOnBoot

        override fun setAdbEnableOnBoot(enabled: Boolean) {
            enableOnBoot = enabled
        }
    }
}
