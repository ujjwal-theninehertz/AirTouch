package com.airtouch.accessibility

import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.airtouch.gesture.mapping.ScreenAction

/**
 * Pixel paths for actions. Every stroke stays inside a safe zone (15–85 % of the width, 30–70 % of the
 * height) so it can never start in the system's Back, Home or notification gesture areas at the edges.
 * The screen size is read for every action, so rotation needs no extra handling.
 */
class ScreenGeometry(private val context: Context) {

    fun scroll(direction: ScreenAction.Direction): Path {
        val (w, h) = size()
        val (fromX, fromY, toX, toY) = when (direction) {
            // The finger moves up to show what is below.
            ScreenAction.Direction.FORWARD -> listOf(0.5f, 0.70f, 0.5f, 0.30f)
            ScreenAction.Direction.BACK -> listOf(0.5f, 0.30f, 0.5f, 0.70f)
            // The finger moves left to show what is to the right.
            ScreenAction.Direction.NEXT_PAGE -> listOf(0.80f, 0.5f, 0.20f, 0.5f)
            ScreenAction.Direction.PREVIOUS_PAGE -> listOf(0.20f, 0.5f, 0.80f, 0.5f)
        }
        return Path().apply {
            moveTo(fromX * w, fromY * h)
            lineTo(toX * w, toY * h)
        }
    }

    fun tap(x: Float, y: Float): Path {
        val (w, h) = size()
        return Path().apply { moveTo(x.coerceIn(0.05f, 0.95f) * w, y.coerceIn(0.05f, 0.95f) * h) }
    }

    /** The screen in its current orientation, in pixels. */
    fun size(): Pair<Float, Float> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") // getRealMetrics is the one call that works from a service on API 26–37.
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }
}
