package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.SecureSettings
import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.DevlinkExtension
import com.adachsoft.devlink.http.ProblemResponses
import org.json.JSONObject

class AndroidAdbExtension(
    private val secureSettings: SecureSettings,
    private val extensionSettings: ExtensionSettings,
    private val ipAddressProvider: () -> String?,
    private val discoveredPortProvider: () -> Int?,
) : DevlinkExtension {
    override val id: String = ID
    override val version: String = VERSION
    override val enabled: Boolean
        get() = extensionSettings.isEnabled(id)

    override fun statusSection(): JSONObject = state()

    override fun handle(method: String, subPath: String, body: JSONObject?): ApiResponse? = when (subPath) {
        "/state" -> if (method.equals("GET", ignoreCase = true)) {
            ApiResponse.json(state())
        } else {
            ProblemResponses.methodNotAllowed("GET")
        }

        "/wireless/enable" -> if (method.equals("POST", ignoreCase = true)) {
            setWirelessDebugging(true)
        } else {
            ProblemResponses.methodNotAllowed("POST")
        }

        "/wireless/disable" -> if (method.equals("POST", ignoreCase = true)) {
            setWirelessDebugging(false)
        } else {
            ProblemResponses.methodNotAllowed("POST")
        }

        "/enable-on-boot" -> if (method.equals("PUT", ignoreCase = true)) {
            setEnableOnBoot(body)
        } else {
            ProblemResponses.methodNotAllowed("PUT")
        }

        else -> null
    }

    private fun setWirelessDebugging(enabled: Boolean): ApiResponse {
        if (!secureSettings.hasWritePermission()) {
            return permissionMissing()
        }
        if (!secureSettings.putInt(ADB_WIFI_ENABLED_SETTING, if (enabled) 1 else 0)) {
            return ProblemResponses.internalServerError()
        }
        return ApiResponse.json(state())
    }

    private fun setEnableOnBoot(body: JSONObject?): ApiResponse {
        val enabled = body?.opt("enabled")
        if (enabled !is Boolean) {
            return ProblemResponses.badRequest(
                detail = "The 'enabled' field must be a boolean.",
                code = INVALID_REQUEST_CODE,
            )
        }
        extensionSettings.setAdbEnableOnBoot(enabled)
        return ApiResponse.json(state())
    }

    private fun state(): JSONObject {
        val port = secureSettings.getInt(ADB_WIFI_PORT_SETTING)
            ?.takeIf { it > 0 }
            ?: discoveredPortProvider()?.takeIf { it > 0 }
        val ipAddress = ipAddressProvider()?.takeIf(String::isNotBlank)

        return JSONObject()
            .put("wirelessDebuggingEnabled", secureSettings.getInt(ADB_WIFI_ENABLED_SETTING) == 1)
            .put("port", port ?: JSONObject.NULL)
            .put("ipAddress", ipAddress ?: JSONObject.NULL)
            .put("secureSettingsPermission", secureSettings.hasWritePermission())
            .put("enableOnBoot", extensionSettings.isAdbEnableOnBoot())
    }

    private fun permissionMissing(): ApiResponse = ProblemResponses.conflict(
        title = "Permission missing",
        detail = "Grant WRITE_SECURE_SETTINGS with: $WRITE_SECURE_SETTINGS_GRANT_COMMAND",
        code = PERMISSION_MISSING_CODE,
    )

    companion object {
        const val ID = "android.adb"
        const val VERSION = "0.1.0"
        const val ADB_WIFI_ENABLED_SETTING = "adb_wifi_enabled"
        const val ADB_WIFI_PORT_SETTING = "adb_wifi_port"
        const val PERMISSION_MISSING_CODE = "permission-missing"
        const val INVALID_REQUEST_CODE = "invalid-request"
        const val WRITE_SECURE_SETTINGS_GRANT_COMMAND =
            "adb shell pm grant com.adachsoft.devlink android.permission.WRITE_SECURE_SETTINGS"
    }
}
