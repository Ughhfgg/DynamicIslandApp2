package com.oussama.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper (developer: oussama).
 * Hosts the island window as an accessibility overlay so it is drawn ABOVE the phone's own island.
 */
class IslandAccess : AccessibilityService() {

    companion object {
        @Volatile var instance: IslandAccess? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        IslandService.instance?.rebuildWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        IslandService.instance?.rebuildWindow()
        return super.onUnbind(intent)
    }
}
