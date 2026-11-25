package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScreenshotExtensionTest {
    @Test
    fun rejectsUnsupportedFormat() {
        val capturer = FakeScreenCapturer()

        val response = createExtension(capturer).handle(
            "GET",
            "/image",
            null,
            mapOf("format" to "bmp"),
        )!!

        assertEquals(400, response.statusCode)
        assertEquals("invalid-parameter", JSONObject(response.body).getString("code"))
        assertFalse(capturer.wasCalled)
    }

    @Test
    fun rejectsQualityOutsideTheSupportedRange() {
        listOf("0", "101").forEach { quality ->
            val capturer = FakeScreenCapturer()

            val response = createExtension(capturer).handle(
                "GET",
                "/image",
                null,
                mapOf("format" to "jpeg", "quality" to quality),
            )!!

            assertEquals(400, response.statusCode)
            assertEquals("invalid-parameter", JSONObject(response.body).getString("code"))
            assertFalse(capturer.wasCalled)
        }
    }

    @Test
    fun rejectsNegativeDisplayIds() {
        val capturer = FakeScreenCapturer()

        val response = createExtension(capturer).handle(
            "GET",
            "/image",
            null,
            mapOf("display" to "-1"),
        )!!

        assertEquals(400, response.statusCode)
        assertEquals("invalid-parameter", JSONObject(response.body).getString("code"))
        assertFalse(capturer.wasCalled)
    }

    @Test
    fun returnsConflictWhenAccessibilityServiceIsUnavailable() {
        val capturer = FakeScreenCapturer(
            state = ScreenshotState(false, "accessibility-service", "service-not-enabled", 333),
            result = ScreenCaptureResult.Failure(ScreenCaptureFailure.SERVICE_NOT_ENABLED),
        )

        val response = createExtension(capturer).handle("GET", "/image", null, emptyMap())!!

        assertEquals(409, response.statusCode)
        assertEquals("service-not-enabled", JSONObject(response.body).getString("code"))
    }

    @Test
    fun returnsRetryAfterWhenCaptureIsRateLimited() {
        val capturer = FakeScreenCapturer(
            result = ScreenCaptureResult.Failure(ScreenCaptureFailure.RATE_LIMITED),
        )

        val response = createExtension(capturer).handle("GET", "/image", null, emptyMap())!!

        assertEquals(429, response.statusCode)
        assertEquals("1", response.headers["Retry-After"])
        assertEquals("rate-limited", JSONObject(response.body).getString("code"))
    }

    @Test
    fun returnsImageBytesAndScreenshotHeaders() {
        val image = byteArrayOf(1, 2, 3, 4)
        val capturer = FakeScreenCapturer(
            result = ScreenCaptureResult.Success(
                image = image,
                width = 1080,
                height = 2400,
                timestamp = "2026-11-25T10:00:00Z",
            ),
        )

        val response = createExtension(capturer).handle(
            "GET",
            "/image",
            null,
            mapOf("format" to "jpeg", "quality" to "75", "display" to "2"),
        )!!

        assertEquals(200, response.statusCode)
        assertEquals("image/jpeg", response.contentType)
        assertTrue(response.binaryBody?.contentEquals(image) == true)
        assertEquals("1080", response.headers["X-Screenshot-Width"])
        assertEquals("2400", response.headers["X-Screenshot-Height"])
        assertEquals("2026-11-25T10:00:00Z", response.headers["X-Screenshot-Timestamp"])
        assertEquals(2, capturer.lastDisplayId ?: -1)
        assertEquals(ScreenshotFormat.JPEG, capturer.lastFormat)
        assertEquals(75, capturer.lastQuality ?: -1)
    }

    @Test
    fun exposesTheScreenshotStateShape() {
        val state = ScreenshotState(
            available = true,
            method = "accessibility-service",
            reason = null,
            minIntervalMs = 333,
        )
        val extension = createExtension(FakeScreenCapturer(state = state))

        val response = extension.handle("GET", "/state", null, emptyMap())!!
        val json = JSONObject(response.body)

        assertEquals(200, response.statusCode)
        assertEquals(4, json.length())
        assertTrue(json.has("available"))
        assertTrue(json.getBoolean("available"))
        assertEquals("accessibility-service", json.getString("method"))
        assertTrue(json.isNull("reason"))
        assertEquals(333, json.getInt("minIntervalMs"))
        assertEquals(json.toString(), extension.statusSection().toString())
    }

    private fun createExtension(capturer: FakeScreenCapturer): DisplayScreenshotExtension =
        DisplayScreenshotExtension(capturer, FakeExtensionSettings())

    private class FakeScreenCapturer(
        private val state: ScreenshotState = ScreenshotState(
            available = true,
            method = "accessibility-service",
            reason = null,
            minIntervalMs = 333,
        ),
        private val result: ScreenCaptureResult = ScreenCaptureResult.Failure(ScreenCaptureFailure.INTERNAL_ERROR),
    ) : ScreenCapturer {
        var wasCalled = false
            private set
        var lastDisplayId: Int? = null
            private set
        var lastFormat: ScreenshotFormat? = null
            private set
        var lastQuality: Int? = null
            private set

        override fun state(): ScreenshotState = state

        override fun capture(
            displayId: Int,
            format: ScreenshotFormat,
            quality: Int,
        ): ScreenCaptureResult {
            wasCalled = true
            lastDisplayId = displayId
            lastFormat = format
            lastQuality = quality
            return result
        }
    }

    private class FakeExtensionSettings : ExtensionSettings {
        override fun isEnabled(extensionId: String): Boolean = extensionId == "display.screenshot"

        override fun setEnabled(extensionId: String, enabled: Boolean) = Unit

        override fun isAdbEnableOnBoot(): Boolean = false

        override fun setAdbEnableOnBoot(enabled: Boolean) = Unit
    }
}
