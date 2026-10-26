package com.airtouch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.airtouch.accessibility.TapProbe
import java.util.Locale
import kotlin.math.hypot

/**
 * The cursor tap test (P4 exit test). Nine targets, one at a time, across the screen in whatever rotation the phone
 * is in: point at the highlighted ring and pinch. Each tap is compared with where AirTouch aimed it (the pixel
 * path from cursor to touch, which must be within 8 dp) and with the target's centre (your aim, cursor and all).
 * Rotating the phone starts the test again in the new orientation. AirTouch acts inside its own app only here.
 */
class TapTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TapTestView(this))
    }

    override fun onResume() {
        super.onResume()
        TapProbe.testScreenShown = true
    }

    override fun onPause() {
        TapProbe.testScreenShown = false
        super.onPause()
    }
}

@SuppressLint("ViewConstructor")
private class TapTestView(context: Context) : View(context) {

    private class Hit(val target: PointF, val touch: PointF, val aim: PointF?)

    private val density = resources.displayMetrics.density
    private val targets = mutableListOf<PointF>()
    private val hits = mutableListOf<Hit>()
    private val origin = IntArray(2)

    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#55FFFFFF")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#4FCDBB")
    }
    private val good = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FCDBB") }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2994A") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.parseColor("#101418"))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        targets.clear()
        hits.clear()
        // Rows top to bottom, columns left to right: corners and edges too, where system gestures start.
        for (fy in FRACTIONS) for (fx in FRACTIONS) targets += PointF(w * fx, h * fy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || targets.isEmpty()) return true
        if (hits.size == targets.size) {
            hits.clear()
            invalidate()
            return true
        }
        getLocationOnScreen(origin)
        val aim = TapProbe.lastAim
            ?.takeIf { SystemClock.uptimeMillis() - it.atMs < AIM_FRESH_MS }
            ?.let { PointF(it.x - origin[0], it.y - origin[1]) }
        hits += Hit(targets[hits.size], PointF(event.x, event.y), aim)
        if (hits.size == targets.size) Log.i(TAG, summaryLine())
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        targets.forEach { canvas.drawCircle(it.x, it.y, dp(TARGET_RADIUS_DP), faint) }
        hits.forEach { hit ->
            val paint = if (aimErrorDp(hit) <= TOLERANCE_DP) good else off
            canvas.drawLine(hit.target.x, hit.target.y, hit.touch.x, hit.touch.y, paint)
            canvas.drawCircle(hit.touch.x, hit.touch.y, dp(5f), paint)
        }
        val lines = if (hits.size < targets.size) {
            val next = targets[hits.size]
            canvas.drawCircle(next.x, next.y, dp(TARGET_RADIUS_DP), ring)
            canvas.drawLine(next.x - dp(8f), next.y, next.x + dp(8f), next.y, ring)
            canvas.drawLine(next.x, next.y - dp(8f), next.x, next.y + dp(8f), ring)
            listOf("Tap test · ${rotationDegrees()}° · target ${hits.size + 1} of ${targets.size}", "Point ☝ at the green ring, then pinch")
        } else {
            summaryText()
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, width / 2f, height * 0.30f + i * dp(22f), text) }
    }

    private fun summaryText(): List<String> {
        val pipeline = hits.mapNotNull(::pipelineErrorDp)
        val aim = hits.map(::aimErrorDp).sorted()
        val within = aim.count { it <= TOLERANCE_DP }
        return listOf(
            "Done · ${rotationDegrees()}°",
            if (pipeline.isEmpty()) "No cursor taps measured" else String.format(Locale.US, "Taps vs where AirTouch aimed: max %.1f dp (pass ≤ 8)", pipeline.max()),
            String.format(Locale.US, "Taps vs target centre (your aim): median %.0f dp, %d of %d within 8 dp", aim[aim.size / 2], within, aim.size),
            "Rotate the phone to test another side · pinch to start again",
        )
    }

    private fun summaryLine(): String {
        val pipeline = hits.mapNotNull(::pipelineErrorDp)
        val aim = hits.map(::aimErrorDp).sorted()
        return String.format(
            Locale.US,
            "P4 tapgrid rotation=%d measured=%d/%d pipeline_max_dp=%.1f aim_median_dp=%.1f aim_max_dp=%.1f within8=%d/%d",
            rotationDegrees(), pipeline.size, hits.size, pipeline.maxOrNull() ?: Float.NaN, aim[aim.size / 2], aim.last(),
            aim.count { it <= TOLERANCE_DP }, aim.size,
        )
    }

    private fun pipelineErrorDp(hit: Hit): Float? = hit.aim?.let { hypot(hit.touch.x - it.x, hit.touch.y - it.y) / density }

    private fun aimErrorDp(hit: Hit): Float = hypot(hit.touch.x - hit.target.x, hit.touch.y - hit.target.y) / density

    private fun rotationDegrees(): Int = (display?.rotation ?: 0) * 90

    private fun dp(value: Float) = value * density

    private companion object {
        const val TAG = "AirTouch"
        val FRACTIONS = listOf(0.1f, 0.5f, 0.9f)
        const val TARGET_RADIUS_DP = 20f
        const val TOLERANCE_DP = 8f

        /** A touch this soon after a cursor tap was aimed is that tap; anything else is a finger. */
        const val AIM_FRESH_MS = 1_000L
    }
}
