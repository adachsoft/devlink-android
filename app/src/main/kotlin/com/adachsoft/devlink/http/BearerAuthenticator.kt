package com.adachsoft.devlink.http

import java.security.MessageDigest

class BearerAuthenticator(
    private val storedToken: String,
    extensionScopes: Collection<String> = emptyList(),
) {
    private val scopes = (setOf(CORE_SCOPE) + extensionScopes).toSet()

    init {
        require(storedToken.isNotEmpty()) { "The stored bearer token must not be empty." }
    }

    fun authenticate(authorizationHeader: String?): Boolean {
        val presentedToken = authorizationHeader
            ?.trim()
            ?.let(AUTHORIZATION_PATTERN::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        return MessageDigest.isEqual(
            storedToken.toByteArray(Charsets.UTF_8),
            presentedToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun hasScope(requiredScope: String): Boolean = requiredScope in scopes

    companion object {
        const val CORE_SCOPE = "core"

        private val AUTHORIZATION_PATTERN = Regex("^Bearer\\s+(\\S+)$", RegexOption.IGNORE_CASE)
    }
}
