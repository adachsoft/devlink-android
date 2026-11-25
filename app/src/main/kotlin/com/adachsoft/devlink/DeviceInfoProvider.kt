package com.adachsoft.devlink

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

class DeviceInfoProvider(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getInfo(): JSONObject = JSONObject()
        .put("id", getOrCreateDeviceId())
        .put("name", deviceName())
        .put("platform", "android")
        .put("osVersion", Build.VERSION.RELEASE)
        .put("agentVersion", BuildConfig.VERSION_NAME)
        .put("apiVersion", API_VERSION)

    private fun getOrCreateDeviceId(): String = synchronized(preferences) {
        preferences.getString(DEVICE_ID_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().also { deviceId ->
                check(preferences.edit().putString(DEVICE_ID_KEY, deviceId).commit()) {
                    "Unable to persist the device identifier."
                }
            }
    }

    private fun deviceName(): String =
        Settings.Global.getString(applicationContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf(String::isNotBlank)
            ?: Build.MODEL

    companion object {
        const val API_VERSION = "0.1.0"

        private const val PREFERENCES_NAME = "devlink"
        private const val DEVICE_ID_KEY = "device_id"
    }
}
