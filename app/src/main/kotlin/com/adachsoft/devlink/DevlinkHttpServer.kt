package com.adachsoft.devlink

import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.ProblemResponses
import com.adachsoft.devlink.http.Router
import com.adachsoft.devlink.http.SourceAddressPolicy
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.DataInputStream
import org.json.JSONObject

internal class DevlinkHttpServer(
    private val router: Router,
) : NanoHTTPD("0.0.0.0", PORT) {
    override fun serve(session: IHTTPSession): Response {
        val remoteAddress = session.remoteIpAddress
        if (remoteAddress == null || !SourceAddressPolicy.accepts(remoteAddress)) {
            return toNanoResponse(
                ProblemResponses.forbidden("Requests are only accepted from private or local addresses."),
            )
        }

        val authorizationHeader = session.headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value
        val method = session.method.name

        return toNanoResponse(
            router.handleWithBodyProvider(
                method = method,
                path = session.uri,
                authorizationHeader = authorizationHeader,
                bodyProvider = {
                    if (method == "GET" || method == "HEAD") null else parseBody(session)
                },
            ),
        )
    }

    private fun parseBody(session: IHTTPSession): JSONObject? {
        // Read the raw body ourselves: NanoHTTPD.parseBody() stores PUT bodies in a temp file
        // under "content" (only POST lands in "postData"), so PUT payloads were lost.
        val length = session.headers["content-length"]?.trim()?.toIntOrNull() ?: return null
        if (length <= 0) {
            return null
        }
        require(length <= MAX_BODY_BYTES) { "Request body too large." }
        val bytes = ByteArray(length)
        DataInputStream(session.inputStream).readFully(bytes)
        return String(bytes, Charsets.UTF_8).takeIf(String::isNotBlank)?.let(::JSONObject)
    }

    private fun toNanoResponse(response: ApiResponse): Response {
        val status = when (response.statusCode) {
            200 -> Response.Status.OK
            202 -> Response.Status.ACCEPTED
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            405 -> Response.Status.METHOD_NOT_ALLOWED
            409 -> Response.Status.CONFLICT
            else -> Response.Status.INTERNAL_ERROR
        }
        return NanoHTTPD.newFixedLengthResponse(status, response.contentType, response.body).apply {
            response.headers.forEach { (name, value) -> addHeader(name, value) }
        }
    }

    companion object {
        const val PORT = 8787
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
