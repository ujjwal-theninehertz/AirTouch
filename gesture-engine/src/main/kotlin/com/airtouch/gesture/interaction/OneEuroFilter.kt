package com.airtouch.gesture.interaction

import kotlin.math.PI
import kotlin.math.abs

/**
 * The one-euro filter (Casiez, Roussel and Vogel, CHI 2012): heavy smoothing while the value is steady, light
 * smoothing while it moves, so a pointer neither shakes at rest nor lags behind the hand. [minCutoffHz] sets the
 * smoothing at rest, [beta] how fast it opens up with speed (in the value's units per second).
 */
internal class OneEuroFilter(private val minCutoffHz: Float, private val beta: Float, private val derivativeCutoffHz: Float = 1f) {

    private var value = 0f
    private var derivative = 0f
    private var lastMs = 0L
    private var started = false

    fun filter(raw: Float, timestampMs: Long): Float {
        if (!started || timestampMs <= lastMs) {
            if (!started) {
                value = raw
                derivative = 0f
                started = true
            }
            lastMs = timestampMs
            return value
        }
        val dt = (timestampMs - lastMs) / 1000f
        lastMs = timestampMs
        derivative += alpha(dt, derivativeCutoffHz) * ((raw - value) / dt - derivative)
        value += alpha(dt, minCutoffHz + beta * abs(derivative)) * (raw - value)
        return value
    }

    fun reset() {
        started = false
    }

    private fun alpha(dt: Float, cutoffHz: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }
}
