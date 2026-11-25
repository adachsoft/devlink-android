package com.adachsoft.devlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
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
    private var registrationRequest: NsdRegistrationRequest? = null
    private var desiredNsdRegistration: NsdRegistrationRequest? = null
    private var registrationIsActive = false
    private var unregistrationRequested = false
    private var adbDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var wifiConnectionMonitor: WifiConnectionMonitor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val attemptedAdbAutoEnableConnections = mutableSetOf<Long>()

    @Volatile
    private var discoveredAdbPort: Int? = null

    @Volatile
    private var discoveredAdbHostAddress: String? = null

    @Volatile
    private var discoveredAdbServiceName: String? = null

    @Volatile
    private var currentWifiIpAddress: String? = null

    @Volatile
    private var isShuttingDown = false

    private data class NsdRegistrationRequest(
        val serviceInfo: NsdServiceInfo,
        val wifiIpAddress: String?,
        val networkHandle: Long?,
    )

    override fun onCreate() {
        super.onCreate()
        currentWifiIpAddress = WifiIpAddressProvider.currentAddress(this)
        createNotificationChannel()
        startForegroundServiceNotification()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAgent()
        return START_STICKY
    }

    override fun onDestroy() {
        isShuttingDown = true
        desiredNsdRegistration = null
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
        registrationRequest = null
        registrationIsActive = false
        unregistrationRequested = false
        nsdManager = null
        wifiConnectionMonitor?.stop()
        wifiConnectionMonitor = null
        try {
            httpServer?.stop()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to stop the HTTP server cleanly.", exception)
        }
        httpServer = null
        clearDiscoveredAdbPort()
        currentWifiIpAddress = null
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
            val wifiMonitor = WifiConnectionMonitor(applicationContext) { network, ipAddress ->
                onWifiNetworkChanged(network, ipAddress, extensionSettings, secureSettings, infoProvider)
            }
            wifiConnectionMonitor = wifiMonitor

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
                        onWirelessDebuggingDisabled = ::clearDiscoveredAdbPort,
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
            requestNsdRegistration(infoProvider.getInfo(), currentWifiIpAddress, null)
            wifiMonitor.start()
            isRunning = true
            notificationManager().notify(NOTIFICATION_ID, createNotification())
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start the devlink agent.", exception)
            stopSelf()
        }
    }

    @Synchronized
    private fun onWifiNetworkChanged(
        network: Network?,
        ipAddress: String?,
        extensionSettings: ExtensionSettings,
        secureSettings: SecureSettings,
        infoProvider: DeviceInfoProvider,
    ) {
        if (isShuttingDown) {
            return
        }

        val ipChanged = currentWifiIpAddress != ipAddress
        currentWifiIpAddress = ipAddress
        if (ipChanged) {
            clearDiscoveredAdbPort()
        }

        if (network == null || ipAddress == null) {
            unregisterNsdForWifiLoss()
        } else {
            if (ipChanged) {
                requestNsdRegistration(infoProvider.getInfo(), ipAddress, network.networkHandle)
            }
            maybeEnableAdbOnWifiConnection(network, ipAddress, extensionSettings, secureSettings)
        }

        notificationManager().notify(NOTIFICATION_ID, createNotification())
    }

    private fun maybeEnableAdbOnWifiConnection(
        network: Network,
        ipAddress: String,
        extensionSettings: ExtensionSettings,
        secureSettings: SecureSettings,
    ) {
        val networkHandle = network.networkHandle
        val shouldEnable = AdbAutoEnablePolicy.shouldEnable(
            networkHandle = networkHandle,
            attemptedNetworkHandles = attemptedAdbAutoEnableConnections,
            wifiNetworkAvailable = true,
            hasIpv4Address = ipAddress.isNotBlank(),
            adbExtensionEnabled = extensionSettings.isEnabled(AndroidAdbExtension.ID),
            enableOnBoot = extensionSettings.isAdbEnableOnBoot(),
            hasWriteSecureSettingsPermission = secureSettings.hasWritePermission(),
            adbWifiEnabled = secureSettings.getInt(AndroidAdbExtension.ADB_WIFI_ENABLED_SETTING) == 1,
        )
        if (!shouldEnable) {
            return
        }

        attemptedAdbAutoEnableConnections.add(networkHandle)
        if (!secureSettings.putInt(AndroidAdbExtension.ADB_WIFI_ENABLED_SETTING, 1)) {
            Log.w(TAG, "Unable to enable wireless debugging for the current Wi-Fi connection.")
        }
    }

    @Synchronized
    private fun requestNsdRegistration(info: JSONObject, ipAddress: String?, networkHandle: Long?) {
        val manager = nsdManager ?: getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        if (adbDiscoveryListener == null) {
            startAdbPortDiscovery(manager)
        }

        val request = NsdRegistrationRequest(
            serviceInfo = createNsdServiceInfo(info),
            wifiIpAddress = ipAddress,
            networkHandle = networkHandle,
        )
        desiredNsdRegistration = request

        val listener = registrationListener
        if (listener == null) {
            registerNsd(request)
            return
        }

        val registeredRequest = registrationRequest
        if (registrationIsActive && registeredRequest != null &&
            (registeredRequest.wifiIpAddress != ipAddress || registeredRequest.networkHandle != networkHandle)
        ) {
            unregisterNsd(listener)
        }
    }

    private fun createNsdServiceInfo(info: JSONObject): NsdServiceInfo = NsdServiceInfo().apply {
        serviceName = info.getString("name")
        serviceType = NSD_SERVICE_TYPE
        port = DevlinkHttpServer.PORT
        setAttribute("id", info.getString("id"))
        setAttribute("name", info.getString("name"))
        setAttribute("platform", "android")
        setAttribute("api", DeviceInfoProvider.API_VERSION)
        setAttribute("path", "/v1")
    }

    @Synchronized
    private fun registerNsd(request: NsdRegistrationRequest) {
        val manager = nsdManager ?: return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@DevlinkService) {
                    if (registrationListener !== this) {
                        return@synchronized
                    }
                    registrationIsActive = true
                    unregistrationRequested = false
                    val desired = desiredNsdRegistration
                    if (isShuttingDown || desired == null ||
                        desired.wifiIpAddress != request.wifiIpAddress ||
                        desired.networkHandle != request.networkHandle
                    ) {
                        unregisterNsd(this)
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@DevlinkService) {
                    if (registrationListener === this) {
                        registrationListener = null
                        registrationRequest = null
                        registrationIsActive = false
                        unregistrationRequested = false
                    }
                }
                Log.w(TAG, "NSD registration failed with code $errorCode.")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                var nextRequest: NsdRegistrationRequest? = null
                synchronized(this@DevlinkService) {
                    if (registrationListener !== this) {
                        return@synchronized
                    }
                    registrationListener = null
                    registrationRequest = null
                    registrationIsActive = false
                    unregistrationRequested = false
                    nextRequest = desiredNsdRegistration
                }
                if (!isShuttingDown) {
                    nextRequest?.let(::registerNsd)
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@DevlinkService) {
                    if (registrationListener === this) {
                        registrationIsActive = true
                        unregistrationRequested = false
                    }
                }
                Log.w(TAG, "NSD unregistration failed with code $errorCode.")
            }
        }
        registrationRequest = request
        registrationListener = listener
        registrationIsActive = false
        unregistrationRequested = false
        manager.registerService(request.serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    private fun unregisterNsd(listener: NsdManager.RegistrationListener) {
        if (registrationListener !== listener || !registrationIsActive || unregistrationRequested) {
            return
        }
        val manager = nsdManager ?: return
        unregistrationRequested = true
        try {
            manager.unregisterService(listener)
        } catch (_: IllegalArgumentException) {
            unregistrationRequested = false
            Log.w(TAG, "The NSD service was not registered when unregistration began.")
        }
    }

    @Synchronized
    private fun unregisterNsdForWifiLoss() {
        desiredNsdRegistration = null
        val listener = registrationListener ?: return
        if (registrationIsActive) {
            unregisterNsd(listener)
        }
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
                    clearDiscoveredAdbPort()
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
                val hostAddress = resolved.host?.hostAddress ?: return
                val currentAddress = currentWifiIpAddress ?: return
                if (hostAddress == currentAddress && resolved.port > 0) {
                    discoveredAdbHostAddress = currentAddress
                    discoveredAdbPort = resolved.port
                    discoveredAdbServiceName = resolved.serviceName
                }
            }
        })
    }

    private fun discoveredAdbPortForCurrentIp(): Int? {
        val currentAddress = currentWifiIpAddress ?: return null
        if (discoveredAdbHostAddress != currentAddress) {
            clearDiscoveredAdbPort()
            return null
        }
        return discoveredAdbPort
    }

    private fun clearDiscoveredAdbPort() {
        discoveredAdbPort = null
        discoveredAdbHostAddress = null
        discoveredAdbServiceName = null
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
        val address = currentWifiIpAddress?.takeIf { it.isNotBlank() }
        val title = if (address == null) {
            "devlink: waiting for Wi-Fi"
        } else {
            "devlink: listening on $address:${DevlinkHttpServer.PORT}"
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
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
