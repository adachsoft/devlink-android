package com.adachsoft.devlink.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BearerAuthenticatorTest {
    private val authenticator = BearerAuthenticator(
        storedToken = "test-token",
        extensionScopes = listOf("ext:device.battery"),
    )

    @Test
    fun rejectsMissingAndWrongAuthorizationHeaders() {
        assertFalse(authenticator.authenticate(null))
        assertFalse(authenticator.authenticate("Basic test-token"))
        assertFalse(authenticator.authenticate("Bearer wrong-token"))
    }

    @Test
    fun acceptsMatchingBearerTokenAndChecksScopes() {
        assertTrue(authenticator.authenticate("Bearer test-token"))
        assertTrue(authenticator.hasScope(BearerAuthenticator.CORE_SCOPE))
        assertTrue(authenticator.hasScope("ext:device.battery"))
        assertFalse(authenticator.hasScope("ext:unknown"))
    }
}
