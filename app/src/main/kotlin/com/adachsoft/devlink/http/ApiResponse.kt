package com.adachsoft.devlink.http

import org.json.JSONObject

data class ApiResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(body: JSONObject, statusCode: Int = 200): ApiResponse =
            ApiResponse(statusCode = statusCode, body = body.toString())
    }
}
