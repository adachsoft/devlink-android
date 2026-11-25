package com.adachsoft.devlink.extensions

import com.adachsoft.devlink.ExtensionSettings
import com.adachsoft.devlink.http.ApiResponse
import com.adachsoft.devlink.http.DevlinkExtension
import com.adachsoft.devlink.http.ProblemResponses
import org.json.JSONObject

interface WifiConnection {
    fun currentState(): WifiConnectionState

    fun requestReconnect()
}

data class WifiConnectionState(
    val connected: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val ipAddress: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val lastDisconnectAt: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("connected", connected)
        .put("ssid", ssid ?: JSONObject.NULL)
        .put("bssid", bssid ?: JSONObject.NULL)
        .put("ipAddress", ipAddress ?: JSONObject.NULL)
        .put("rssiDbm", rssiDbm ?: JSONObject.NULL)
        .put("linkSpeedMbps", linkSpeedMbps ?: JSONObject.NULL)
        .put("frequencyMhz", frequencyMhz ?: JSONObject.NULL)
        .put("lastDisconnectAt", lastDisconnectAt ?: JSONObject.NULL)
}

class NetworkWifiExtension(
    private val wifiConnection: WifiConnection,
    private val extensionSettings: ExtensionSettings,
) : DevlinkExtension {
    override val id: String = ID
    override val version: String = VERSION
    override val enabled: Boolean
        get() = extensionSettings.isEnabled(id)

    override fun statusSection(): JSONObject = wifiConnection.currentState().toJson()

    override fun handle(
        method: String,
        subPath: String,
        body: JSONObject?,
        query: Map<String, String>,
    ): ApiResponse? = when (subPath) {
        "/state" -> if (method.equals("GET", ignoreCase = true)) {
            ApiResponse.json(wifiConnection.currentState().toJson())
        } else {
            ProblemResponses.methodNotAllowed("GET")
        }

        "/reconnect" -> if (method.equals("POST", ignoreCase = true)) {
            wifiConnection.requestReconnect()
            ApiResponse.json(
                JSONObject()
                    .put("accepted", true)
                    .put("method", "network-request"),
                statusCode = 202,
            )
        } else {
            ProblemResponses.methodNotAllowed("POST")
        }

        else -> null
    }

    companion object {
        const val ID = "network.wifi"
        const val VERSION = "0.1.0"
    }
}
