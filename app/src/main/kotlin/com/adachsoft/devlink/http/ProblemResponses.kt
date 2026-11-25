package com.adachsoft.devlink.http

import org.json.JSONObject

object ProblemResponses {
    private const val CONTENT_TYPE = "application/problem+json"

    fun unauthorized(): ApiResponse = problem(
        status = 401,
        title = "Unauthorized",
        detail = "A valid bearer token is required.",
        headers = mapOf("WWW-Authenticate" to "Bearer realm=\"devlink\""),
    )

    fun forbidden(detail: String = "The bearer token does not have the required scope."): ApiResponse =
        problem(status = 403, title = "Forbidden", detail = detail)

    fun badRequest(detail: String, code: String? = null): ApiResponse =
        problem(status = 400, title = "Bad Request", detail = detail, code = code)

    fun invalidParameter(detail: String): ApiResponse =
        problem(status = 400, title = "Invalid parameter", detail = detail, code = "invalid-parameter")

    fun tooManyRequests(): ApiResponse = problem(
        status = 429,
        title = "Too Many Requests",
        detail = "Wait before requesting another screenshot.",
        headers = mapOf("Retry-After" to "1"),
        code = "rate-limited",
    )

    fun conflict(title: String, detail: String, code: String? = null): ApiResponse =
        problem(status = 409, title = title, detail = detail, code = code)

    fun notFound(): ApiResponse =
        problem(status = 404, title = "Not Found", detail = "The requested resource was not found.")

    fun methodNotAllowed(allowedMethods: String): ApiResponse = problem(
        status = 405,
        title = "Method Not Allowed",
        detail = "The requested method is not allowed for this resource.",
        headers = mapOf("Allow" to allowedMethods),
    )

    fun internalServerError(): ApiResponse = problem(
        status = 500,
        title = "Internal Server Error",
        detail = "An unexpected error occurred.",
    )

    private fun problem(
        status: Int,
        title: String,
        detail: String,
        headers: Map<String, String> = emptyMap(),
        code: String? = null,
    ): ApiResponse {
        val body = JSONObject()
            .put("type", "about:blank")
            .put("title", title)
            .put("status", status)
            .put("detail", detail)
        if (code != null) {
            body.put("code", code)
        }
        return ApiResponse(
            statusCode = status,
            body = body.toString(),
            contentType = CONTENT_TYPE,
            headers = headers,
        )
    }
}
