package com.adachsoft.devlink

import android.content.Context

interface ExtensionSettings {
    fun isEnabled(extensionId: String): Boolean

    fun setEnabled(extensionId: String, enabled: Boolean)

    fun isAdbEnableOnBoot(): Boolean

    fun setAdbEnableOnBoot(enabled: Boolean)
}

class SharedPreferencesExtensionSettings(context: Context) : ExtensionSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isEnabled(extensionId: String): Boolean = preferences.getBoolean(
        "$EXTENSION_ENABLED_KEY_PREFIX$extensionId",
        DEFAULT_ENABLED[extensionId] ?: false,
    )

    override fun setEnabled(extensionId: String, enabled: Boolean) {
        preferences.edit()
            .putBoolean("$EXTENSION_ENABLED_KEY_PREFIX$extensionId", enabled)
            .apply()
    }

    override fun isAdbEnableOnBoot(): Boolean = preferences.getBoolean(ADB_ENABLE_ON_BOOT_KEY, false)

    override fun setAdbEnableOnBoot(enabled: Boolean) {
        preferences.edit().putBoolean(ADB_ENABLE_ON_BOOT_KEY, enabled).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "devlink_extension_settings"
        private const val EXTENSION_ENABLED_KEY_PREFIX = "extension_enabled."
        private const val ADB_ENABLE_ON_BOOT_KEY = "android.adb.enable_on_boot"

        private val DEFAULT_ENABLED = mapOf(
            "device.battery" to true,
            "network.wifi" to true,
            "android.adb" to false,
            "power.keep-awake" to false,
        )
    }
}
