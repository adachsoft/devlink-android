package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.SecureSettings
import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.DevlinkExtension
import com.adachsoft.devlink.http.ProblemResponses
import org.json.JSONArray
import org.json.JSONObject

class PowerKeepAwakeExtension(
    private val secureSettings: SecureSettings,
    private val extensionSettings: ExtensionSettings,
) : DevlinkExtension {
    override val id: String = ID
    override val version: String = VERSION
    override val enabled: Boolean
        get() = extensionSettings.isEnabled(id)

    override fun statusSection(): JSONObject = state()

    override fun handle(method: String, subPath: String, body: JSONObject?): ApiResponse? {
        if (subPath != "/state") {
            return null
        }
        return when {
            method.equals("GET", ignoreCase = true) -> ApiResponse.json(state())
            method.equals("PUT", ignoreCase = true) -> updateState(body)
            else -> ProblemResponses.methodNotAllowed("GET, PUT")
        }
    }

    private fun updateState(body: JSONObject?): ApiResponse {
        val request = body ?: return invalidRequest()
        val enabled = request.opt("enabled")
        if (enabled !is Boolean) {
            return invalidRequest()
        }

        val sourcesMask = parseSourcesMask(request)
            ?: return ProblemResponses.badRequest(
                detail = "The 'sources' field must contain only ac, usb, or wireless.",
                code = INVALID_REQUEST_CODE,
            )

        if (!secureSettings.hasWritePermission()) {
            return ProblemResponses.conflict(
                title = "Permission missing",
                detail = "Grant WRITE_SECURE_SETTINGS on Android before changing this setting.",
                code = PERMISSION_MISSING_CODE,
            )
        }

        val mask = if (enabled) sourcesMask else 0
        if (!secureSettings.putInt(STAY_ON_WHILE_PLUGGED_IN_SETTING, mask)) {
            return ProblemResponses.internalServerError()
        }
        return ApiResponse.json(state())
    }

    private fun invalidRequest(): ApiResponse = ProblemResponses.badRequest(
        detail = "The 'enabled' field must be a boolean.",
        code = INVALID_REQUEST_CODE,
    )

    private fun parseSourcesMask(body: JSONObject): Int? {
        if (!body.has("sources")) {
            return DEFAULT_SOURCES_MASK
        }
        val sources = body.optJSONArray("sources") ?: return null
        var mask = 0
        for (index in 0 until sources.length()) {
            val source = sources.opt(index) as? String ?: return null
            mask = mask or (SOURCE_MASKS[source] ?: return null)
        }
        return mask
    }

    private fun state(): JSONObject {
        val mask = secureSettings.getInt(STAY_ON_WHILE_PLUGGED_IN_SETTING) ?: 0
        val sources = JSONArray()
        SOURCE_MASKS.forEach { (source, sourceMask) ->
            if ((mask and sourceMask) != 0) {
                sources.put(source)
            }
        }
        return JSONObject()
            .put("enabled", mask != 0)
            .put("sources", sources)
    }

    companion object {
        const val ID = "power.keep-awake"
        const val VERSION = "0.1.0"
        const val STAY_ON_WHILE_PLUGGED_IN_SETTING = "stay_on_while_plugged_in"
        const val PERMISSION_MISSING_CODE = "permission-missing"
        const val INVALID_REQUEST_CODE = "invalid-request"

        private const val DEFAULT_SOURCES_MASK = 7
        private val SOURCE_MASKS = linkedMapOf(
            "ac" to 1,
            "usb" to 2,
            "wireless" to 4,
        )
    }
}
