package com.adachsoft.devlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.adachsoft.devlink.extensions.DeviceBatteryExtension
import com.adachsoft.devlink.http.BearerAuthenticator
import com.adachsoft.devlink.http.ExtensionRegistry
import com.adachsoft.devlink.http.Router
import org.json.JSONObject

class DevlinkService : Service() {
    private var httpServer: DevlinkHttpServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAgent()
        return START_STICKY
    }

    override fun onDestroy() {
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "The NSD service was not registered when shutdown began.")
            }
        }
        registrationListener = null
        nsdManager = null
        try {
            httpServer?.stop()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to stop the HTTP server cleanly.", exception)
        }
        httpServer = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgent() {
        if (httpServer != null) {
            return
        }

        try {
            val infoProvider = DeviceInfoProvider(applicationContext)
            val extensions = ExtensionRegistry(listOf(DeviceBatteryExtension(applicationContext)))
            val token = TokenStore(applicationContext).getOrCreateToken()
            val authenticator = BearerAuthenticator(
                storedToken = token,
                extensionScopes = extensions.all.map { it.scope },
            )
            val router = Router(authenticator, extensions, infoProvider::getInfo)
            val server = DevlinkHttpServer(router)
            server.start()
            httpServer = server
            registerNsd(infoProvider.getInfo())
            isRunning = true
            notificationManager().notify(NOTIFICATION_ID, createNotification())
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start the devlink agent.", exception)
            stopSelf()
        }
    }

    private fun registerNsd(info: JSONObject) {
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = info.getString("name")
            serviceType = NSD_SERVICE_TYPE
            port = DevlinkHttpServer.PORT
            setAttribute("id", info.getString("id"))
            setAttribute("name", info.getString("name"))
            setAttribute("platform", "android")
            setAttribute("api", DeviceInfoProvider.API_VERSION)
            setAttribute("path", "/v1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed with code $errorCode.")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed with code $errorCode.")
            }
        }
        nsdManager = manager
        registrationListener = listener
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startForegroundServiceNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "devlink service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the devlink LAN HTTP agent available."
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun createNotification(): Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("devlink: listening on ${WifiIpAddressProvider.currentAddress(this)}:${DevlinkHttpServer.PORT}")
        .setContentText("LAN HTTP agent")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_CHANNEL_ID = "devlink_service"
        private const val NOTIFICATION_ID = 8787
        private const val NSD_SERVICE_TYPE = "_as-devlink._tcp."
        private const val TAG = "DevlinkService"
    }
}
