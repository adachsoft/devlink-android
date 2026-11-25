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
    ): ApiResponse = ApiResponse(
        statusCode = status,
        body = JSONObject()
            .put("type", "about:blank")
            .put("title", title)
            .put("status", status)
            .put("detail", detail)
            .toString(),
        contentType = CONTENT_TYPE,
        headers = headers,
    )
}
