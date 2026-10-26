package com.airtouch.gesture.geometry

import com.airtouch.gesture.model.HandFrame
import com.airtouch.gesture.model.Landmark
import kotlin.math.hypot

/** Area of the landmarks' bounding box in normalised image units: a rough measure of how close the hand is. */
fun HandFrame.boundingArea(): Float {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in landmarks) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return (maxX - minX) * (maxY - minY)
}

/** Straight-line distance in the image plane, ignoring depth. */
fun Landmark.distanceTo(other: Landmark): Float = hypot(x - other.x, y - other.y)

/** Flips x, turning landmarks from an unflipped front-camera image into what the user sees on screen. */
fun Landmark.mirroredX(): Landmark = copy(x = 1f - x)

/**
 * Moves a point from the camera's raw image into the upright image the user sees: turns it
 * [rotationDegrees] clockwise, then mirrors it for the front camera. MediaPipe reports landmarks in the
 * raw image even when it is told the rotation, so every landmark goes through this once.
 */
fun Landmark.toUserView(rotationDegrees: Int, mirror: Boolean): Landmark {
    val upright = when (rotationDegrees) {
        0 -> this
        90 -> Landmark(1f - y, x, z)
        180 -> Landmark(1f - x, 1f - y, z)
        270 -> Landmark(y, 1f - x, z)
        else -> throw IllegalArgumentException("rotation must be 0, 90, 180 or 270, was $rotationDegrees")
    }
    return if (mirror) upright.mirroredX() else upright
}
