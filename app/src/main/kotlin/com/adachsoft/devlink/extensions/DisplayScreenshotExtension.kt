package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.DevlinkExtension
import com.adachsoft.devlink.http.ProblemResponses
import org.json.JSONObject

internal class DisplayScreenshotExtension(
    private val screenCapturer: ScreenCapturer,
    private val extensionSettings: ExtensionSettings,
) : DevlinkExtension {
    override val id: String = ID
    override val version: String = VERSION
    override val enabled: Boolean
        get() = extensionSettings.isEnabled(id)

    override fun statusSection(): JSONObject = stateJson()

    override fun handle(
        method: String,
        subPath: String,
        body: JSONObject?,
        query: Map<String, String>,
    ): ApiResponse? = when (subPath) {
        "/state" -> if (method.equals("GET", ignoreCase = true)) {
            ApiResponse.json(stateJson())
        } else {
            ProblemResponses.methodNotAllowed("GET")
        }

        "/image" -> if (method.equals("GET", ignoreCase = true)) {
            capture(query)
        } else {
            ProblemResponses.methodNotAllowed("GET")
        }

        else -> null
    }

    private fun stateJson(): JSONObject {
        val state = screenCapturer.state()
        return JSONObject()
            .put("available", state.available)
            .put("method", state.method ?: JSONObject.NULL)
            .put("reason", state.reason ?: JSONObject.NULL)
            .put("minIntervalMs", state.minIntervalMs ?: JSONObject.NULL)
    }

    private fun capture(query: Map<String, String>): ApiResponse {
        val format = when (query["format"] ?: DEFAULT_FORMAT) {
            "png" -> ScreenshotFormat.PNG
            "jpeg" -> ScreenshotFormat.JPEG
            else -> return ProblemResponses.invalidParameter(
                "The format parameter must be either png or jpeg.",
            )
        }

        val qualityValue = query["quality"]
        val quality = if (qualityValue == null) {
            DEFAULT_QUALITY
        } else {
            qualityValue.toIntOrNull()
                ?.takeIf { it in MIN_QUALITY..MAX_QUALITY }
                ?: return ProblemResponses.invalidParameter(
                    "The quality parameter must be an integer between 1 and 100.",
                )
        }

        val displayValue = query["display"]
        val displayId = if (displayValue == null) {
            DEFAULT_DISPLAY_ID
        } else {
            displayValue.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return ProblemResponses.invalidParameter(
                    "The display parameter must be an integer greater than or equal to 0.",
                )
        }

        val captureQuality = if (format == ScreenshotFormat.JPEG) quality else DEFAULT_QUALITY
        return when (val result = screenCapturer.capture(displayId, format, captureQuality)) {
            is ScreenCaptureResult.Success -> successResponse(result, format)
            is ScreenCaptureResult.Failure -> failureResponse(result.reason)
        }
    }

    private fun successResponse(
        screenshot: ScreenCaptureResult.Success,
        format: ScreenshotFormat,
    ): ApiResponse = ApiResponse.binary(
        body = screenshot.image,
        contentType = format.contentType,
        headers = mapOf(
            "X-Screenshot-Width" to screenshot.width.toString(),
            "X-Screenshot-Height" to screenshot.height.toString(),
            "X-Screenshot-Timestamp" to screenshot.timestamp,
        ),
    )

    private fun failureResponse(reason: ScreenCaptureFailure): ApiResponse = when (reason) {
        ScreenCaptureFailure.UNSUPPORTED_OS -> ProblemResponses.conflict(
            title = "Screenshot unavailable",
            detail = "Screen capture requires Android 11 or later.",
            code = "unsupported-os",
        )

        ScreenCaptureFailure.SERVICE_NOT_ENABLED -> ProblemResponses.conflict(
            title = "Screenshot service not enabled",
            detail = "Enable the devlink screenshots accessibility service in Android settings.",
            code = "service-not-enabled",
        )

        ScreenCaptureFailure.RATE_LIMITED -> ProblemResponses.tooManyRequests()

        ScreenCaptureFailure.INVALID_DISPLAY -> ProblemResponses.invalidParameter(
            "The requested display is not available.",
        )

        ScreenCaptureFailure.SECURE_CONTENT -> ProblemResponses.conflict(
            title = "Protected content cannot be captured",
            detail = "The screen is showing protected content and cannot be captured.",
            code = "secure-content",
        )

        ScreenCaptureFailure.INTERNAL_ERROR -> ProblemResponses.internalServerError()
    }

    companion object {
        const val ID = "display.screenshot"
        const val VERSION = "0.1.0"

        private const val DEFAULT_FORMAT = "png"
        private const val DEFAULT_QUALITY = 90
        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100
        private const val DEFAULT_DISPLAY_ID = 0
    }
}
