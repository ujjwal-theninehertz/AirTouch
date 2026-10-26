package com.airtouch.accessibility

import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Adds an accessibility overlay window, or logs why Android refused it. Refusals happen when the service has just
 * been disconnected (its window token is gone); an exception escaping would crash the service, and a crashed
 * accessibility service is switched off by Android, as happened on 25 Oct 2026.
 */
internal fun addSafely(windowManager: WindowManager, view: View, params: WindowManager.LayoutParams): Boolean =
    try {
        windowManager.addView(view, params)
        true
    } catch (e: RuntimeException) {
        Log.w("AirTouch", "Overlay window refused", e)
        false
    }
