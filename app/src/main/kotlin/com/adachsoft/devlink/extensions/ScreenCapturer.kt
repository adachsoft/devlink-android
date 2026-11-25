package com.adachsoft.devlink.extensions

internal enum class ScreenshotFormat(val contentType: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
}

internal data class ScreenshotState(
    val available: Boolean,
    val method: String?,
    val reason: String?,
    val minIntervalMs: Int?,
)

internal enum class ScreenCaptureFailure {
    UNSUPPORTED_OS,
    SERVICE_NOT_ENABLED,
    RATE_LIMITED,
    INVALID_DISPLAY,
    SECURE_CONTENT,
    INTERNAL_ERROR,
}

internal sealed interface ScreenCaptureResult {
    data class Success(
        val image: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: String,
    ) : ScreenCaptureResult

    data class Failure(val reason: ScreenCaptureFailure) : ScreenCaptureResult
}

internal interface ScreenCapturer {
    fun state(): ScreenshotState

    fun capture(
        displayId: Int,
        format: ScreenshotFormat,
        quality: Int,
    ): ScreenCaptureResult
}
