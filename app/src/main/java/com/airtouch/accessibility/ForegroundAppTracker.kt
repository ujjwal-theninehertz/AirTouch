package com.airtouch.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * The app the user is looking at, from window-state events. Other windows send these events too and are not the
 * app in front: system UI (the notification shade, MIUI's floating tools), the keyboard, and AirTouch's own
 * overlays (status pill, cursor, shortcut menu), which report a change every time they appear. Before that last
 * rule, showing the cursor over Instagram made AirTouch think it was in front of itself, where gestures are off.
 */
class ForegroundAppTracker(private val ownPackage: String) {

    @Volatile
    var current: String? = null
        private set

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        onWindowState(event.packageName?.toString(), event.className?.toString())
    }

    /** One window-state change; [className] is the activity, dialog or view class that reported it. */
    fun onWindowState(packageName: String?, className: String?) {
        val pkg = packageName ?: return
        if (pkg in SYSTEM_UI || className == KEYBOARD_WINDOW) return
        // AirTouch's own screens count (gestures are off there); its overlays over other apps do not.
        if (pkg == ownPackage && className?.startsWith("$ownPackage.ui.") != true) return
        current = pkg
    }

    private companion object {
        val SYSTEM_UI = setOf("android", "com.android.systemui", "miui.systemui.plugin")

        /** The window every keyboard app shows its keys in. */
        const val KEYBOARD_WINDOW = "android.inputmethodservice.SoftInputWindow"
    }
}
