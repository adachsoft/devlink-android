package com.adachsoft.devlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var networkAddress: TextView
    private lateinit var tokenView: TextView
    private lateinit var toggleButton: Button
    private lateinit var token: String

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, STATUS_REFRESH_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = TokenStore(applicationContext).getOrCreateToken()
        setContentView(createContentView())
        requestNotificationPermissionIfNeeded()
        startAgentService()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    private fun createContentView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)

        addView(TextView(this@MainActivity).apply {
            text = "devlink"
            textSize = 24f
        }, defaultLayoutParams())

        serviceStatus = addStatusLine(this, "Service: starting")
        networkAddress = addStatusLine(this, "Wi-Fi: unavailable:${DevlinkHttpServer.PORT}")
        tokenView = addStatusLine(this, "Token: $token", selectable = true)
        toggleButton = Button(this@MainActivity).apply {
            setOnClickListener { toggleAgentService() }
        }
        addView(toggleButton, defaultLayoutParams())
    }

    private fun addStatusLine(
        parent: LinearLayout,
        initialText: String,
        selectable: Boolean = false,
    ): TextView = TextView(this).apply {
        text = initialText
        textSize = 16f
        setTextIsSelectable(selectable)
        parent.addView(this, defaultLayoutParams())
    }

    private fun defaultLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = (16 * resources.displayMetrics.density).toInt()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun startAgentService() {
        startForegroundService(Intent(this, DevlinkService::class.java))
    }

    private fun toggleAgentService() {
        if (DevlinkService.isRunning) {
            stopService(Intent(this, DevlinkService::class.java))
        } else {
            startAgentService()
        }
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, STATUS_REFRESH_DELAY_MS)
    }

    private fun refreshStatus() {
        if (!::serviceStatus.isInitialized) {
            return
        }
        serviceStatus.text = "Service: ${if (DevlinkService.isRunning) "running" else "stopped"}"
        networkAddress.text = "Wi-Fi: ${WifiIpAddressProvider.currentAddress(this)}:${DevlinkHttpServer.PORT}"
        tokenView.text = "Token: $token"
        toggleButton.text = if (DevlinkService.isRunning) "Stop" else "Start"
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1
        private const val STATUS_REFRESH_DELAY_MS = 1_000L
    }
}
