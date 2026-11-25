package com.adachsoft.devlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adachsoft.devlink.extensions.AndroidAdbExtension

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val extensionSettings = SharedPreferencesExtensionSettings(context)
        val secureSettings = AndroidSecureSettings(context)
        if (extensionSettings.isEnabled(AndroidAdbExtension.ID) &&
            extensionSettings.isAdbEnableOnBoot() &&
            secureSettings.hasWritePermission()
        ) {
            secureSettings.putInt(AndroidAdbExtension.ADB_WIFI_ENABLED_SETTING, 1)
        }
        context.startForegroundService(Intent(context, DevlinkService::class.java))
    }
}
