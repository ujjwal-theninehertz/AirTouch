package com.airtouch.gesture

import com.airtouch.gesture.model.HandFrame
import com.airtouch.gesture.model.HandInput
import com.airtouch.gesture.model.Landmark
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds hand streams for engine tests. Hands are drawn in hand-scale units around the wrist (y grows
 * downwards), then placed in a 480×640 portrait frame like the Note 8 Pro's, at 20 frames per second.
 */
internal class SyntheticHands(scale: Float = 0.13f, private val frameMs: Long = 50) {

    /**
     * FOLD folds the fingertips down past the knuckles, as deep as recorded finger flicks (about 1.6 hand scales).
     * PINCH is an open-hand pinch; POINT_PINCH the recorded kind, which closes from pointing with the other fingers folded.
     */
    enum class Shape { OPEN, FIST, PINCH, V, THREE, THUMBS_UP, THUMBS_DOWN, FOLD, POINT, ROCK, CALL, POINT_PINCH }

    /** Hand size in frame heights: grows as the hand comes closer to the camera. */
    private var scale = scale

    val aspect = 480f / 640f
    var t = 0L
        private set
    private var x = 0.375f // palm position, in frame heights
    private var y = 0.60f
    private val frames = mutableListOf<HandInput>()

    fun build(): List<HandInput> = frames.toList()

    fun rest(ms: Long, shape: Shape = Shape.OPEN) = repeat((ms / frameMs).toInt()) { emit(shape, jitter = true) }

    /** Moves the palm by ([dx], [dy]) frame heights in [ms], at constant speed. */
    fun move(dx: Float, dy: Float, ms: Long, shape: Shape = Shape.OPEN) {
        val steps = (ms / frameMs).toInt()
        repeat(steps) {
            x += dx / steps
            y += dy / steps
            emit(shape, jitter = false)
        }
    }

    /**
     * Changes the hand from one shape and wrist tilt to another over [ms], at an even pace. Tilt turns the
     * hand about the wrist in degrees; positive swings the fingertips to the user's right.
     */
    fun morph(from: Shape, to: Shape, ms: Long, fromTilt: Float = 0f, toTilt: Float = 0f) {
        val steps = (ms / frameMs).toInt()
        val a = local(from)
        val b = local(to)
        for (k in 1..steps) {
            val f = k.toFloat() / steps
            val points = a.zip(b) { (ax, ay), (bx, by) -> (ax + (bx - ax) * f) to (ay + (by - ay) * f) }
            emitPoints(tilted(points, fromTilt + (toTilt - fromTilt) * f), jitter = false)
        }
    }

    /** Brings the hand closer (factor above 1) or takes it away, holding [shape], at an even pace. */
    fun approach(factor: Float, ms: Long, shape: Shape = Shape.OPEN) {
        val steps = (ms / frameMs).toInt()
        val from = scale
        for (k in 1..steps) {
            scale = from * (1f + (factor - 1f) * k / steps)
            emit(shape, jitter = false)
        }
    }

    fun absent(ms: Long) = repeat((ms / frameMs).toInt()) {
        frames += HandInput(t, null, aspect)
        t += frameMs
    }

    private fun emit(shape: Shape, jitter: Boolean) = emitPoints(local(shape), jitter)

    /** Turns the hand about its wrist, so the wrist stays where it is. */
    private fun tilted(points: List<Pair<Float, Float>>, degrees: Float): List<Pair<Float, Float>> {
        if (degrees == 0f) return points
        val r = Math.toRadians(degrees.toDouble())
        val (wx, wy) = points[0]
        return points.map { (px, py) ->
            val dx = px - wx
            val dy = py - wy
            (wx + (dx * cos(r) - dy * sin(r)).toFloat()) to (wy + (dx * sin(r) + dy * cos(r)).toFloat())
        }
    }

    private fun emitPoints(local: List<Pair<Float, Float>>, jitter: Boolean) {
        // MediaPipe landmarks wobble by about 0.002 of the frame at rest.
        val wobble = if (jitter) 0.002f * sin(t / 37.0).toFloat() else 0f
        val points = local.map { (lx, ly) ->
            val worldX = x + wobble + lx * scale
            val worldY = y + wobble + ly * scale
            Landmark(worldX / aspect, worldY, 0f)
        }
        frames += HandInput(t, HandFrame(points, isRightHand = true, score = 0.95f, timestampMs = t), aspect)
        t += frameMs
    }

    companion object {
        private val knuckles = listOf(-0.30f to -1.0f, 0f to -1.0f, 0.25f to -0.95f, 0.48f to -0.85f)

        /** 21 landmarks relative to the palm centre; wrist to middle knuckle is exactly 1. */
        fun local(shape: Shape): List<Pair<Float, Float>> {
            val p = MutableList(21) { 0f to 0f }
            p[0] = 0f to 0f
            p[1] = -0.35f to -0.25f
            p[2] = -0.60f to -0.45f
            p[3] = -0.80f to -0.65f
            p[4] = -0.95f to -0.85f
            knuckles.forEachIndexed { f, (mx, my) ->
                val base = 5 + f * 4
                p[base] = mx to my
                val curled = when (shape) {
                    Shape.FIST, Shape.THUMBS_UP, Shape.THUMBS_DOWN, Shape.FOLD -> true
                    Shape.V -> f >= 2
                    Shape.THREE -> f == 3
                    Shape.POINT, Shape.POINT_PINCH -> f >= 1
                    Shape.ROCK -> f == 1 || f == 2
                    Shape.CALL -> f <= 2
                    else -> false
                }
                p[base + 1] = mx to my - if (curled) 0.35f else 0.45f
                p[base + 2] = mx to my - if (curled) 0.15f else 0.75f
                p[base + 3] = mx to my + if (shape == Shape.FOLD) 0.6f else if (curled) 0.05f else -1.0f
            }
            when (shape) {
                // The thumb holds the folded fingers down.
                Shape.V, Shape.POINT, Shape.ROCK -> p[4] = 0.20f to -0.75f
                // The index bends to meet the thumb, its tip still above the knuckle, as recorded.
                Shape.POINT_PINCH -> {
                    p[6] = -0.30f to -1.45f
                    p[7] = -0.42f to -1.58f
                    p[8] = -0.50f to -1.55f
                    p[2] = -0.55f to -0.60f
                    p[3] = -0.60f to -1.05f
                    p[4] = -0.52f to -1.50f
                }
                Shape.CALL -> Unit
                Shape.THREE -> p[4] = 0.35f to -0.70f
                Shape.THUMBS_UP -> {
                    p[1] = -0.35f to -0.30f
                    p[2] = -0.45f to -0.75f
                    p[3] = -0.50f to -1.20f
                    p[4] = -0.55f to -1.65f
                }
                Shape.THUMBS_DOWN -> {
                    p[1] = -0.35f to -0.20f
                    p[2] = -0.50f to 0.10f
                    p[3] = -0.55f to 0.45f
                    p[4] = -0.60f to 0.80f
                }
                Shape.PINCH -> {
                    p[6] = -0.30f to -1.40f
                    p[7] = -0.45f to -1.55f
                    p[8] = -0.60f to -1.50f
                    p[2] = -0.55f to -0.60f
                    p[3] = -0.65f to -1.05f
                    p[4] = -0.62f to -1.45f
                }
                Shape.FIST, Shape.FOLD -> p[4] = 0f to -0.75f
                Shape.OPEN -> Unit
            }
            // Centre on the palm (wrist + four knuckles), as the engine measures from there.
            val cx = listOf(0, 5, 9, 13, 17).map { p[it].first }.average().toFloat()
            val cy = listOf(0, 5, 9, 13, 17).map { p[it].second }.average().toFloat()
            return p.map { (a, b) -> (a - cx) to (b - cy) }
        }
    }
}
