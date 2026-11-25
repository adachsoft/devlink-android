package com.adachsoft.devlink

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class DevlinkAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnectedInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnectedInstance()
        super.onDestroy()
    }

    private fun clearConnectedInstance() {
        if (connectedInstance === this) {
            connectedInstance = null
        }
    }

    companion object {
        @Volatile
        internal var connectedInstance: DevlinkAccessibilityService? = null
    }
}
