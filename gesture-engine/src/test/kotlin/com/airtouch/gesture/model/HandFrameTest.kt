package com.airtouch.gesture.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HandFrameTest {

    @Test
    fun acceptsTwentyOneLandmarks() {
        val frame = HandFrame(List(21) { Landmark(0.5f, 0.5f, 0f) }, isRightHand = true, score = 0.9f, timestampMs = 0)
        assertEquals(HandFrame.LANDMARK_COUNT, frame.landmarks.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnyOtherLandmarkCount() {
        HandFrame(List(20) { Landmark(0f, 0f, 0f) }, isRightHand = true, score = 0.9f, timestampMs = 0)
    }
}
