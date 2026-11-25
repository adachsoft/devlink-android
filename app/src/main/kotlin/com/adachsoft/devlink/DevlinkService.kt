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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.adachsoft.devlink.extensions.AndroidAdbExtension
import com.adachsoft.devlink.extensions.DeviceBatteryExtension
import com.adachsoft.devlink.extensions.NetworkWifiExtension
import com.adachsoft.devlink.extensions.PowerKeepAwakeExtension
import com.adachsoft.devlink.http.BearerAuthenticator
import com.adachsoft.devlink.http.ExtensionRegistry
import com.adachsoft.devlink.http.Router
import org.json.JSONObject

class DevlinkService : Service() {
    private var httpServer: DevlinkHttpServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var adbDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var wifiConnectionMonitor: WifiConnectionMonitor? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var discoveredAdbPort: Int? = null

    @Volatile
    private var discoveredAdbHostAddress: String? = null

    @Volatile
    private var discoveredAdbServiceName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceNotification()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAgent()
        return START_STICKY
    }

    override fun onDestroy() {
        adbDiscoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "The ADB NSD discovery was not active when shutdown began.")
            }
        }
        adbDiscoveryListener = null
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "The NSD service was not registered when shutdown began.")
            }
        }
        registrationListener = null
        nsdManager = null
        wifiConnectionMonitor?.stop()
        wifiConnectionMonitor = null
        try {
            httpServer?.stop()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to stop the HTTP server cleanly.", exception)
        }
        httpServer = null
        discoveredAdbPort = null
        discoveredAdbHostAddress = null
        discoveredAdbServiceName = null
        releaseWifiLock()
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
            val extensionSettings = SharedPreferencesExtensionSettings(applicationContext)
            val secureSettings = AndroidSecureSettings(applicationContext)
            val wifiMonitor = WifiConnectionMonitor(applicationContext)
            wifiConnectionMonitor = wifiMonitor
            wifiMonitor.start()

            val extensions = ExtensionRegistry(
                listOf(
                    DeviceBatteryExtension(applicationContext, extensionSettings),
                    AndroidAdbExtension(
                        secureSettings = secureSettings,
                        extensionSettings = extensionSettings,
                        ipAddressProvider = {
                            WifiIpAddressProvider.currentAddress(applicationContext)
                        },
                        discoveredPortProvider = ::discoveredAdbPortForCurrentIp,
                    ),
                    PowerKeepAwakeExtension(secureSettings, extensionSettings),
                    NetworkWifiExtension(wifiMonitor, extensionSettings),
                ),
            )
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
        nsdManager = manager
        startAdbPortDiscovery(manager)

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
        registrationListener = listener
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startAdbPortDiscovery(manager: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "ADB NSD discovery failed to start with code $errorCode.")
                try {
                    manager.stopServiceDiscovery(this)
                } catch (_: IllegalArgumentException) {
                    // The discovery may not have been registered.
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == ADB_NSD_SERVICE_TYPE) {
                    resolveAdbService(manager, serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == discoveredAdbServiceName) {
                    discoveredAdbPort = null
                    discoveredAdbHostAddress = null
                    discoveredAdbServiceName = null
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "ADB NSD discovery failed to stop with code $errorCode.")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        adbDiscoveryListener = listener
        manager.discoverServices(ADB_NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun resolveAdbService(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "ADB NSD service resolution failed with code $errorCode.")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val hostAddress = resolved.hostAddress?.hostAddress ?: return
                val currentAddress = WifiIpAddressProvider.currentAddress(applicationContext) ?: return
                if (hostAddress == currentAddress && resolved.port > 0) {
                    discoveredAdbHostAddress = currentAddress
                    discoveredAdbPort = resolved.port
                    discoveredAdbServiceName = resolved.serviceName
                }
            }
        })
    }

    private fun discoveredAdbPortForCurrentIp(): Int? {
        val currentAddress = WifiIpAddressProvider.currentAddress(applicationContext) ?: return null
        if (discoveredAdbHostAddress != currentAddress) {
            discoveredAdbPort = null
            discoveredAdbHostAddress = null
            discoveredAdbServiceName = null
            return null
        }
        return discoveredAdbPort
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val manager = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire()
        wifiLock = lock
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wifiLock = null
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

    private fun createNotification(): Notification {
        val address = WifiIpAddressProvider.currentAddress(this) ?: "unavailable"
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("devlink: listening on $address:${DevlinkHttpServer.PORT}")
            .setContentText("LAN HTTP agent")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_CHANNEL_ID = "devlink_service"
        private const val NOTIFICATION_ID = 8787
        private const val NSD_SERVICE_TYPE = "_as-devlink._tcp."
        private const val ADB_NSD_SERVICE_TYPE = "_adb-tls-connect._tcp."
        private const val WIFI_LOCK_TAG = "devlink:wifi-high-performance"
        private const val TAG = "DevlinkService"
    }
}
