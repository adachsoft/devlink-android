package com.adachsoft.devlink.extensions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.SharedPreferencesExtensionSettings
import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.DevlinkExtension
import com.adachsoft.devlink.http.ProblemResponses
import org.json.JSONObject
import kotlin.math.roundToInt

class DeviceBatteryExtension(
    context: Context,
    private val extensionSettings: ExtensionSettings = SharedPreferencesExtensionSettings(context),
) : DevlinkExtension {
    private val applicationContext = context.applicationContext

    override val id: String = "device.battery"
    override val version: String = "0.1.0"
    override val enabled: Boolean
        get() = extensionSettings.isEnabled(id)

    override fun statusSection(): JSONObject = batteryState()

    override fun handle(method: String, subPath: String, body: JSONObject?): ApiResponse? {
        if (subPath != "/state") {
            return null
        }
        if (!method.equals("GET", ignoreCase = true)) {
            return ProblemResponses.methodNotAllowed("GET")
        }
        return ApiResponse.json(batteryState())
    }

    private fun batteryState(): JSONObject {
        val battery = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            (level * 100.0 / scale).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperature: Any = battery?.let { batteryIntent ->
            if (batteryIntent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            } else {
                JSONObject.NULL
            }
        } ?: JSONObject.NULL

        return JSONObject()
            .put("levelPercent", levelPercent)
            .put(
                "charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            )
            .put("plugType", plugType(plugged))
            .put("temperatureCelsius", temperature)
            .put("health", batteryHealth(battery))
    }

    private fun plugType(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "none"
    }

    private fun batteryHealth(battery: Intent?): Any {
        val batteryIntent = battery ?: return JSONObject.NULL
        if (!batteryIntent.hasExtra(BatteryManager.EXTRA_HEALTH)) {
            return JSONObject.NULL
        }

        return when (batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> JSONObject.NULL
        }
    }
}
