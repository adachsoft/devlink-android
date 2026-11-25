package com.adachsoft.devlink.http

import java.util.Locale
import org.json.JSONObject

class Router(
    private val authenticator: BearerAuthenticator,
    private val extensionRegistry: ExtensionRegistry,
    private val infoProvider: () -> JSONObject,
) {
    fun handle(
        method: String,
        path: String,
        authorizationHeader: String?,
        body: JSONObject? = null,
    ): ApiResponse = handleWithBodyProvider(method, path, authorizationHeader) { body }

    internal fun handleWithBodyProvider(
        method: String,
        path: String,
        authorizationHeader: String?,
        bodyProvider: () -> JSONObject?,
    ): ApiResponse {
        if (!authenticator.authenticate(authorizationHeader)) {
            return ProblemResponses.unauthorized()
        }

        val extension = extensionRegistry.findForPath(path)
        val requiredScope = extension?.scope ?: BearerAuthenticator.CORE_SCOPE
        if (!authenticator.hasScope(requiredScope)) {
            return ProblemResponses.forbidden()
        }

        return try {
            route(method.uppercase(Locale.ROOT), path, bodyProvider, extension)
        } catch (_: Exception) {
            ProblemResponses.internalServerError()
        }
    }

    private fun route(
        method: String,
        path: String,
        bodyProvider: () -> JSONObject?,
        extension: DevlinkExtension?,
    ): ApiResponse {
        val coreMethod = when (path) {
            INFO_PATH, EXTENSIONS_PATH, STATUS_PATH -> "GET"
            else -> null
        }

        if (coreMethod != null) {
            if (method != coreMethod) {
                return ProblemResponses.methodNotAllowed(coreMethod)
            }
            return when (path) {
                INFO_PATH -> ApiResponse.json(infoProvider())
                EXTENSIONS_PATH -> ApiResponse.json(
                    JSONObject().put("extensions", extensionRegistry.descriptors()),
                )
                STATUS_PATH -> ApiResponse.json(
                    JSONObject()
                        .put("info", infoProvider())
                        .put("extensions", extensionRegistry.statusSections()),
                )
                else -> ProblemResponses.notFound()
            }
        }

        if (extension == null || !extension.enabled) {
            return ProblemResponses.notFound()
        }

        val subPath = path.removePrefix(extension.basePath).ifEmpty { "/" }
        return extension.handle(method, subPath, bodyProvider()) ?: ProblemResponses.notFound()
    }

    companion object {
        private const val INFO_PATH = "/v1/info"
        private const val EXTENSIONS_PATH = "/v1/extensions"
        private const val STATUS_PATH = "/v1/status"
    }
}
