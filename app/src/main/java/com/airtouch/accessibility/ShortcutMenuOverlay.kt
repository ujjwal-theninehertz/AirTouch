package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.airtouch.gesture.mapping.ShortcutMenu

/**
 * The 🤘 shortcut menu on screen: four actions arranged by the flick that picks them. An accessibility overlay
 * that never takes touches. Call from the main thread.
 */
class ShortcutMenuOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show() {
        if (view != null) return
        val item = { menuItem: ShortcutMenu.Item, arrow: String -> label("$arrow ${menuItem.label}", 16f) }
        val middle = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(item(ShortcutMenu.Item.LEFT, "⬅"))
            addView(label("🤘", 28f).apply { setPadding(dp(16), 0, dp(16), 0) })
            addView(item(ShortcutMenu.Item.RIGHT, "➡"))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E6000000"))
            }
            addView(item(ShortcutMenu.Item.UP, "⬆"))
            addView(middle)
            addView(item(ShortcutMenu.Item.DOWN, "⬇"))
            addView(label("Flick to choose · anything else closes", 12f).apply { alpha = 0.7f; setPadding(0, dp(10), 0, 0) })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        if (!addSafely(windowManager, card, params)) return
        view = card
    }

    fun hide() {
        view?.let(windowManager::removeView)
        view = null
    }

    private fun label(text: String, sizeSp: Float) = TextView(service).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
