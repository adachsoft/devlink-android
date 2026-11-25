package com.adachsoft.devlink.http

import org.json.JSONObject

interface DevlinkExtension {
    val id: String
    val version: String
    val enabled: Boolean
    val scope: String
        get() = "ext:$id"
    val basePath: String
        get() = "/ext/$id/v1"

    fun statusSection(): JSONObject

    fun handle(method: String, subPath: String, body: JSONObject?): ApiResponse?
}
