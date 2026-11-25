package com.adachsoft.devlink.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    @Test
    fun returnsUnauthorizedWhenTokenIsMissing() {
        val router = createRouter()

        val response = router.handle("GET", "/v1/info", null)

        assertEquals(401, response.statusCode)
        assertEquals("application/problem+json", response.contentType)
        assertEquals("Bearer realm=\"devlink\"", response.headers["WWW-Authenticate"])
    }

    @Test
    fun returnsForbiddenWhenTokenDoesNotHaveTheRequiredScope() {
        val extension = FakeExtension()
        val router = createRouter(listOf(extension))

        val response = router.handle(
            method = "GET",
            path = "${extension.basePath}/state",
            authorizationHeader = "Bearer test-token",
        )

        assertEquals(403, response.statusCode)
    }

    @Test
    fun returnsNotFoundForUnknownPaths() {
        val router = createRouter()

        val response = router.handle("GET", "/v1/unknown", "Bearer test-token")

        assertEquals(404, response.statusCode)
    }

    @Test
    fun returnsMethodNotAllowedForWrongCoreMethod() {
        val router = createRouter()

        val response = router.handle("POST", "/v1/info", "Bearer test-token")

        assertEquals(405, response.statusCode)
        assertEquals("GET", response.headers["Allow"])
    }

    @Test
    fun listsAllExtensionsWithTheirDescriptorFields() {
        val extension = FakeExtension()
        val router = createRouter(listOf(extension), listOf(extension.scope))

        val response = router.handle("GET", "/v1/extensions", "Bearer test-token")
        val descriptor = JSONObject(response.body)
            .getJSONArray("extensions")
            .getJSONObject(0)

        assertEquals(200, response.statusCode)
        assertEquals(extension.id, descriptor.getString("id"))
        assertEquals(extension.version, descriptor.getString("version"))
        assertTrue(descriptor.getBoolean("enabled"))
        assertEquals(extension.scope, descriptor.getString("scope"))
        assertEquals(extension.basePath, descriptor.getString("basePath"))
    }

    @Test
    fun doesNotRouteDisabledExtensions() {
        val extension = FakeExtension(enabled = false)
        val router = createRouter(listOf(extension))

        val response = router.handle(
            method = "GET",
            path = extension.basePath,
            authorizationHeader = "Bearer test-token",
        )

        assertEquals(404, response.statusCode)
        assertFalse(JSONObject(response.body).has("trace"))
    }

    @Test
    fun returnsBadRequestWhenRequestBodyIsInvalidJson() {
        val extension = FakeExtension()
        val router = createRouter(listOf(extension), listOf(extension.scope))

        val response = router.handleWithBodyProvider(
            method = "POST",
            path = "${extension.basePath}/state",
            authorizationHeader = "Bearer test-token",
            bodyProvider = { JSONObject("{") },
        )

        assertEquals(400, response.statusCode)
        assertEquals("application/problem+json", response.contentType)
        assertEquals("invalid-json", JSONObject(response.body).getString("code"))
    }

    private fun createRouter(
        extensions: List<DevlinkExtension> = emptyList(),
        extensionScopes: Collection<String> = emptyList(),
    ): Router = Router(
        authenticator = BearerAuthenticator("test-token", extensionScopes),
        extensionRegistry = ExtensionRegistry(extensions),
        infoProvider = {
            JSONObject()
                .put("id", "device-id")
                .put("name", "test-device")
                .put("platform", "android")
                .put("osVersion", "11")
                .put("agentVersion", "0.1.0")
                .put("apiVersion", "0.1.0")
        },
    )

    private class FakeExtension(
        override val enabled: Boolean = true,
    ) : DevlinkExtension {
        override val id: String = "fake.extension"
        override val version: String = "1.0.0"

        override fun statusSection(): JSONObject = JSONObject().put("state", "ready")

        override fun handle(method: String, subPath: String, body: JSONObject?): ApiResponse? {
            if (subPath != "/state") {
                return null
            }
            if (method != "GET") {
                return ProblemResponses.methodNotAllowed("GET")
            }
            return ApiResponse.json(JSONObject().put("state", "ready"))
        }
    }
}
