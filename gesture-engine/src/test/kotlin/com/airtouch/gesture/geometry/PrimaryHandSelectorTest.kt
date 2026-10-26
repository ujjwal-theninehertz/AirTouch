package com.airtouch.gesture.geometry

import com.airtouch.gesture.model.HandFrame
import com.airtouch.gesture.model.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PrimaryHandSelectorTest {

    /** A square hand of side [size] whose wrist sits at ([x], [y]). */
    private fun hand(x: Float, y: Float, size: Float): HandFrame = HandFrame(
        landmarks = List(HandFrame.LANDMARK_COUNT) { i ->
            if (i == HandFrame.WRIST) Landmark(x, y, 0f) else Landmark(x + size * (i % 2), y - size * (i % 3) / 2, 0f)
        },
        isRightHand = true,
        score = 0.9f,
        timestampMs = 0,
    )

    @Test
    fun startsWithTheLargestHand() {
        val near = hand(0.3f, 0.8f, 0.30f)
        val far = hand(0.7f, 0.6f, 0.10f)
        assertSame(near, PrimaryHandSelector().select(listOf(far, near)))
    }

    @Test
    fun keepsFollowingTheSameHandWhenABiggerOneAppears() {
        val selector = PrimaryHandSelector()
        selector.select(listOf(hand(0.30f, 0.80f, 0.15f)))
        val followed = hand(0.33f, 0.78f, 0.15f)
        val bystander = hand(0.75f, 0.70f, 0.40f)
        assertSame(followed, selector.select(listOf(bystander, followed)))
    }

    @Test
    fun switchesWhenTheFollowedHandIsGone() {
        val selector = PrimaryHandSelector()
        selector.select(listOf(hand(0.30f, 0.80f, 0.15f)))
        val other = hand(0.80f, 0.40f, 0.20f)
        assertSame(other, selector.select(listOf(other)))
    }

    @Test
    fun noHandsMeansNoPrimary() {
        assertNull(PrimaryHandSelector().select(emptyList()))
    }

    @Test
    fun mirroringFlipsOnlyX() {
        val p = Landmark(0.2f, 0.7f, -0.1f).mirroredX()
        assertEquals(0.8f, p.x, 1e-6f)
        assertEquals(0.7f, p.y, 1e-6f)
        assertEquals(-0.1f, p.z, 1e-6f)
    }
}
