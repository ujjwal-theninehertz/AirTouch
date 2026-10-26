package com.airtouch.gesture.geometry

import com.airtouch.gesture.model.HandFrame
import com.airtouch.gesture.model.Landmark

/**
 * Picks the one hand AirTouch follows when several are in view. It keeps following the same hand while
 * its wrist stays within [maxJump] of where it was; otherwise it takes the largest, usually closest,
 * hand. That stops a bystander's hand from taking over mid-gesture.
 */
class PrimaryHandSelector(private val maxJump: Float = 0.15f) {

    private var lastWrist: Landmark? = null

    fun select(hands: List<HandFrame>): HandFrame? {
        if (hands.isEmpty()) {
            lastWrist = null
            return null
        }
        val previous = lastWrist
        val chosen = previous
            ?.let { p -> hands.minBy { it.wrist.distanceTo(p) }.takeIf { it.wrist.distanceTo(p) <= maxJump } }
            ?: hands.maxBy { it.boundingArea() }
        lastWrist = chosen.wrist
        return chosen
    }
}
