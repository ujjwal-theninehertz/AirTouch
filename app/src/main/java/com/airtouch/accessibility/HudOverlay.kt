package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small status pill at the top of the screen, drawn as an accessibility overlay: no "display over
 * other apps" permission, and it never takes touches, so injected gestures reach the app below.
 * Call from the main thread.
 */
class HudOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var status = ""
    private var flashing = false
    private val restore = Runnable {
        flashing = false
        view?.let { render(it, status, flash = false) }
    }

    fun show() {
        if (view != null) return
        val pill = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        if (!addSafely(windowManager, pill, params)) return
        view = pill
        render(pill, status, flash = false)
    }

    fun hide() {
        view?.let {
            it.removeCallbacks(restore)
            windowManager.removeView(it)
        }
        view = null
        flashing = false
    }

    /** Hides the pill for a moment (a screenshot) without forgetting its text. */
    fun setSuppressed(value: Boolean) {
        view?.visibility = if (value) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    /** The resting text, such as "AirTouch · ready". A flash on screen stays until it times out. */
    fun setStatus(text: String) {
        if (text == status) return
        status = text
        if (!flashing) view?.let { render(it, text, flash = false) }
    }

    /** Shows [text] highlighted for a moment, then returns to the status. */
    fun flash(text: String) {
        val pill = view ?: return
        pill.removeCallbacks(restore)
        flashing = true
        render(pill, text, flash = true)
        pill.postDelayed(restore, FLASH_MS)
    }

    private fun render(pill: TextView, text: String, flash: Boolean) {
        pill.text = text
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (flash) TEAL else SCRIM)
        }
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val FLASH_MS = 1_200L
        val TEAL = Color.parseColor("#E60B7C70")
        val SCRIM = Color.parseColor("#B3000000")
    }
}
