package com.airtouch.gesture.features

import com.airtouch.gesture.GestureConfig
import com.airtouch.gesture.model.HandFrame
import kotlin.math.hypot

/**
 * Shape of one hand in one frame, before any voting across frames. POINT is the index finger alone (it steers
 * the cursor), ROCK the index and little finger, CALL the little finger alone or with the thumb out.
 */
enum class Pose { OPEN, FIST, PINCH, V, THREE, THUMBS_UP, THUMBS_DOWN, POINT, ROCK, CALL, OTHER }

/**
 * Geometry of one hand in isotropic image units (x scaled by the frame's aspect ratio, so one unit is
 * the frame height in both directions). [scale] is the wrist-to-middle-knuckle length.
 */
class HandFeatures private constructor(
    val palmX: Float,
    val palmY: Float,
    val indexTipX: Float,
    val indexTipY: Float,
    val thumbTipX: Float,
    val thumbTipY: Float,
    /** The index finger's knuckle: it barely moves when the finger bends to pinch, so the cursor follows it. */
    val indexKnuckleX: Float,
    val indexKnuckleY: Float,
    val scale: Float,
    /** Index, middle, ring and pinky, in that order. A finger can be neither extended nor curled. */
    private val extended: BooleanArray,
    private val curled: BooleanArray,
    /** Thumb straight and above every other point of the hand. */
    val thumbUp: Boolean,
    /** Thumb straight and below every other point of the hand. */
    val thumbDown: Boolean,
    /** Thumb tip to index tip, in hand scales. */
    val pinchDistance: Float,
    /** How far the four fingertips sit above their knuckles on average, in hand scales: about 1 straight, below 0 folded. */
    val fingerLift: Float,
    /** Average of the four fingertips relative to the wrist, in hand scales (y grows downwards). */
    val tipsFromWristX: Float,
    val tipsFromWristY: Float,
    val wristX: Float,
    val wristY: Float,
) {
    val extendedFingers: Int get() = extended.count { it }
    val curledFingers: Int get() = curled.count { it }

    /** [finger] 0 is the index, 3 the little finger. */
    fun isExtended(finger: Int): Boolean = extended[finger]

    fun pose(config: GestureConfig, wasPinching: Boolean): Pose {
        val pinchLimit = if (wasPinching) config.pinchExit else config.pinchEnter
        val (index, middle, ring, pinky) = extended.toList()
        // Curled shapes first: a fist also brings the thumb close to the index finger.
        return when {
            curled.all { it } -> when {
                thumbUp -> Pose.THUMBS_UP
                thumbDown -> Pose.THUMBS_DOWN
                else -> Pose.FIST
            }
            pinchDistance < pinchLimit -> Pose.PINCH
            extended.all { it } -> Pose.OPEN
            index && middle && ring && curled[3] -> Pose.THREE
            index && middle && curled[2] && curled[3] && !pinky -> Pose.V
            index && curled[1] && curled[2] && pinky -> Pose.ROCK
            index && curled[1] && curled[2] && curled[3] -> Pose.POINT
            pinky && curled[0] && curled[1] && curled[2] -> Pose.CALL
            else -> Pose.OTHER
        }
    }

    companion object {
        private val PALM = intArrayOf(0, 5, 9, 13, 17)

        // Knuckle, middle joint and tip of the four fingers.
        private val FINGERS = arrayOf(intArrayOf(5, 6, 8), intArrayOf(9, 10, 12), intArrayOf(13, 14, 16), intArrayOf(17, 18, 20))

        fun of(hand: HandFrame, aspect: Float, config: GestureConfig): HandFeatures {
            val p = hand.landmarks
            fun x(i: Int) = p[i].x * aspect
            fun y(i: Int) = p[i].y
            fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))

            var palmX = 0f
            var palmY = 0f
            for (i in PALM) {
                palmX += x(i)
                palmY += y(i)
            }
            val scale = dist(HandFrame.WRIST, HandFrame.MIDDLE_MCP).coerceAtLeast(1e-4f)

            val extended = BooleanArray(4)
            val curled = BooleanArray(4)
            var lift = 0f
            FINGERS.forEachIndexed { f, (knuckle, pip, tip) ->
                lift += (y(knuckle) - y(tip)) / scale / FINGERS.size
                // A straight finger's tip is well beyond its middle joint, seen from the wrist; a curled one folds back.
                val ratio = dist(tip, HandFrame.WRIST) / dist(pip, HandFrame.WRIST).coerceAtLeast(1e-4f)
                extended[f] = ratio > config.fingerExtendedRatio
                curled[f] = ratio < config.fingerCurledRatio
            }

            val thumbStraight = dist(HandFrame.THUMB_TIP, HandFrame.WRIST) / dist(THUMB_MCP, HandFrame.WRIST).coerceAtLeast(1e-4f) > config.thumbExtendedRatio
            val others = (0 until HandFrame.LANDMARK_COUNT).filter { it !in 1..4 }
            val thumbUp = thumbStraight && y(HandFrame.THUMB_TIP) < others.minOf { y(it) } - config.thumbAboveMargin * scale
            val thumbDown = thumbStraight && y(HandFrame.THUMB_TIP) > others.maxOf { y(it) } + config.thumbAboveMargin * scale

            return HandFeatures(
                palmX = palmX / PALM.size,
                palmY = palmY / PALM.size,
                indexTipX = x(HandFrame.INDEX_TIP),
                indexTipY = y(HandFrame.INDEX_TIP),
                thumbTipX = x(HandFrame.THUMB_TIP),
                thumbTipY = y(HandFrame.THUMB_TIP),
                indexKnuckleX = x(INDEX_MCP),
                indexKnuckleY = y(INDEX_MCP),
                scale = scale,
                extended = extended,
                curled = curled,
                thumbUp = thumbUp,
                thumbDown = thumbDown,
                pinchDistance = dist(HandFrame.THUMB_TIP, HandFrame.INDEX_TIP) / scale,
                fingerLift = lift,
                tipsFromWristX = (TIPS.sumOf { x(it).toDouble() }.toFloat() / TIPS.size - x(HandFrame.WRIST)) / scale,
                tipsFromWristY = (TIPS.sumOf { y(it).toDouble() }.toFloat() / TIPS.size - y(HandFrame.WRIST)) / scale,
                wristX = x(HandFrame.WRIST),
                wristY = y(HandFrame.WRIST),
            )
        }

        private const val THUMB_MCP = 2
        private const val INDEX_MCP = 5
        private val TIPS = intArrayOf(8, 12, 16, 20)
    }
}
