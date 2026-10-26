package com.airtouch.gesture.geometry

import com.airtouch.gesture.model.Landmark
import org.junit.Assert.assertEquals
import org.junit.Test

class UserViewTest {

    private fun assertAt(expectedX: Float, expectedY: Float, actual: Landmark) {
        assertEquals("x", expectedX, actual.x, 1e-6f)
        assertEquals("y", expectedY, actual.y, 1e-6f)
    }

    // The raw image's top-left corner, followed through each clockwise turn.
    private val topLeft = Landmark(0f, 0f, 0.5f)

    @Test
    fun noRotation() = assertAt(0f, 0f, topLeft.toUserView(0, mirror = false))

    @Test
    fun quarterTurnMovesTopLeftToTopRight() = assertAt(1f, 0f, topLeft.toUserView(90, mirror = false))

    @Test
    fun halfTurnMovesTopLeftToBottomRight() = assertAt(1f, 1f, topLeft.toUserView(180, mirror = false))

    @Test
    fun threeQuarterTurnMovesTopLeftToBottomLeft() = assertAt(0f, 1f, topLeft.toUserView(270, mirror = false))

    @Test
    fun frontCameraPortraitOnTheNote8Pro() {
        // Portrait on the Note 8 Pro gives rotation 270. A point near the raw top-right (fingertips of an
        // upright hand) must land near the top of the user's view, mirrored.
        val p = Landmark(0.9f, 0.3f, 0f).toUserView(270, mirror = true)
        assertAt(0.7f, 0.1f, p)
    }

    @Test
    fun depthIsKept() = assertEquals(0.5f, topLeft.toUserView(90, mirror = true).z, 1e-6f)

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherAngles() {
        topLeft.toUserView(45, mirror = false)
    }
}
