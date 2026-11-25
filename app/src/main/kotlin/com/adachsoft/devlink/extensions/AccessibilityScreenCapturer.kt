package com.adachsoft.devlink.extensions

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import com.adachsoft.devlink.DevlinkAccessibilityService
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class AccessibilityScreenCapturer : ScreenCapturer {
    private val rateLimitLock = Any()
    private var lastCaptureAtMs: Long? = null

    override fun state(): ScreenshotState {
        val reason = when {
            Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK -> "unsupported-os"
            DevlinkAccessibilityService.connectedInstance == null -> "service-not-enabled"
            else -> null
        }
        return ScreenshotState(
            available = reason == null,
            method = CAPTURE_METHOD,
            reason = reason,
            minIntervalMs = MIN_CAPTURE_INTERVAL_MS.toInt(),
        )
    }

    override fun capture(
        displayId: Int,
        format: ScreenshotFormat,
        quality: Int,
    ): ScreenCaptureResult {
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) {
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.UNSUPPORTED_OS)
        }
        val service = DevlinkAccessibilityService.connectedInstance
            ?: return ScreenCaptureResult.Failure(ScreenCaptureFailure.SERVICE_NOT_ENABLED)
        if (isRateLimited()) {
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.RATE_LIMITED)
        }

        val completion = CountDownLatch(1)
        var result: ScreenCaptureResult? = null
        try {
            service.takeScreenshot(displayId, CALLBACK_EXECUTOR, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    result = encodeScreenshot(screenshot, format, quality)
                    completion.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result = failureFor(errorCode)
                    completion.countDown()
                }
            })
        } catch (_: Exception) {
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
        }

        val completed = try {
            completion.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
        }
        if (!completed) {
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
        }
        return result ?: ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
    }

    private fun isRateLimited(): Boolean = synchronized(rateLimitLock) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastCaptureAtMs
        if (previous != null && now - previous < MIN_CAPTURE_INTERVAL_MS) {
            true
        } else {
            lastCaptureAtMs = now
            false
        }
    }

    private fun failureFor(errorCode: Int): ScreenCaptureResult.Failure = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            ScreenCaptureResult.Failure(ScreenCaptureFailure.RATE_LIMITED)

        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            ScreenCaptureResult.Failure(ScreenCaptureFailure.SERVICE_NOT_ENABLED)

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            ScreenCaptureResult.Failure(ScreenCaptureFailure.INVALID_DISPLAY)

        else -> if (
            Build.VERSION.SDK_INT >= SECURE_WINDOW_API_LEVEL &&
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
        ) {
            ScreenCaptureResult.Failure(ScreenCaptureFailure.SECURE_CONTENT)
        } else {
            ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
        }
    }

    private fun encodeScreenshot(
        screenshot: AccessibilityService.ScreenshotResult,
        format: ScreenshotFormat,
        quality: Int,
    ): ScreenCaptureResult {
        val hardwareBuffer = screenshot.hardwareBuffer
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        return try {
            val timestamp = Instant.now().toString()
            val sourceBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
            hardwareBitmap = sourceBitmap
            val bitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
            softwareBitmap = bitmap

            val output = ByteArrayOutputStream()
            val compressFormat = when (format) {
                ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
                ScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG
            }
            if (!bitmap.compress(compressFormat, quality, output)) {
                return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
            }
            val image = output.toByteArray()
            if (image.isEmpty()) {
                return ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
            }
            ScreenCaptureResult.Success(
                image = image,
                width = bitmap.width,
                height = bitmap.height,
                timestamp = timestamp,
            )
        } catch (_: Exception) {
            ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR)
        } finally {
            try {
                softwareBitmap?.recycle()
            } finally {
                try {
                    hardwareBitmap?.recycle()
                } finally {
                    hardwareBuffer.close()
                }
            }
        }
    }

    companion object {
        private const val MIN_SUPPORTED_SDK = 30
        private const val SECURE_WINDOW_API_LEVEL = 34
        private const val MIN_CAPTURE_INTERVAL_MS = 333L
        private const val CAPTURE_TIMEOUT_SECONDS = 5L
        private const val CAPTURE_METHOD = "accessibility-service"

        private val CALLBACK_EXECUTOR = Executor { command ->
            Thread(command, "devlink-screenshot-callback").apply {
                isDaemon = true
                start()
            }
        }
    }
}
