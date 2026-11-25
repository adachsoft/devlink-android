package com.adachsoft.devlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

interface SecureSettings {
    fun getInt(name: String): Int?

    fun putInt(name: String, value: Int): Boolean

    fun hasWritePermission(): Boolean
}

class AndroidSecureSettings(context: Context) : SecureSettings {
    private val applicationContext = context.applicationContext

    override fun getInt(name: String): Int? = try {
        Settings.Global.getInt(applicationContext.contentResolver, name)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override fun putInt(name: String, value: Int): Boolean =
        Settings.Global.putInt(applicationContext.contentResolver, name, value)

    override fun hasWritePermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}
