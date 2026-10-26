package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import com.airtouch.gesture.model.Pointer
import kotlin.math.abs
import kotlin.math.exp

/**
 * The cursor, drawn over every app as a full-screen accessibility overlay that never takes touches, so injected
 * taps reach the app below. Positions arrive about 20 times a second; the ring glides between them at the
 * display's frame rate. It fills while a finger is down and shows a ring filling up while a pinch is held.
 * Call from the main thread.
 */
class CursorOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null
    private var suppressed = false

    /** [pointer] in screen fractions; null hides the cursor. */
    fun update(pointer: Pointer?, screenWidth: Float, screenHeight: Float) {
        if (pointer == null || suppressed) {
            view?.visibility = View.INVISIBLE
            return
        }
        val cursor = view ?: add() ?: return
        cursor.visibility = View.VISIBLE
        cursor.target(pointer.x * screenWidth, pointer.y * screenHeight, pointer.pressed, pointer.holdProgress)
    }

    /** Hides the cursor for a moment (a screenshot) without forgetting it. */
    fun setSuppressed(value: Boolean) {
        suppressed = value
        if (value) view?.visibility = View.INVISIBLE
    }

    fun remove() {
        view?.let(windowManager::removeView)
        view = null
    }

    private fun add(): CursorView? {
        val cursor = CursorView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Cover the camera cutout too, so the overlay's pixels are the screen's pixels.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (!addSafely(windowManager, cursor, params)) return null
        view = cursor
        return cursor
    }
}

@SuppressLint("ViewConstructor")
private class CursorView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val radius = 14 * density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        color = Color.parseColor("#99000000")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
        color = TEAL
    }
    private val arc = RectF()
    private val origin = IntArray(2)

    private var shownX = Float.NaN
    private var shownY = Float.NaN
    private var targetX = 0f
    private var targetY = 0f
    private var pressed = false
    private var progress = 0f
    private var lastFrameMs = 0L

    fun target(px: Float, py: Float, isPressed: Boolean, holdProgress: Float) {
        targetX = px
        targetY = py
        if (shownX.isNaN()) {
            shownX = px
            shownY = py
        }
        pressed = isPressed
        progress = holdProgress
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrameMs).coerceIn(1, 100)
        lastFrameMs = now
        // Glide towards the latest position, a third of the way every ~16 ms: smooth, about 40 ms behind.
        val share = 1f - exp(-dt / GLIDE_MS)
        shownX += (targetX - shownX) * share
        shownY += (targetY - shownY) * share
        // Screen pixels to this window's pixels, in case the overlay does not start at the screen's corner.
        getLocationOnScreen(origin)
        val cx = shownX - origin[0]
        val cy = shownY - origin[1]
        fill.color = if (pressed) TEAL else Color.parseColor("#33FFFFFF")
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, outline)
        canvas.drawCircle(cx, cy, radius, ring)
        if (progress > 0f) {
            val r = radius + 6 * density
            arc.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arc, -90f, 360f * progress, false, progressPaint)
        }
        if (abs(targetX - shownX) > 0.5f || abs(targetY - shownY) > 0.5f) postInvalidateOnAnimation()
    }

    private companion object {
        const val GLIDE_MS = 40f
        val TEAL = Color.parseColor("#E60B7C70")
    }
}
