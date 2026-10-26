package com.airtouch.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateGovernorTest {

    /** Feeds [seconds] of frames at [sourceFps] and returns how many the governor let through. */
    private fun FrameRateGovernor.accepted(sourceFps: Double, seconds: Int, startMs: Long = 0): Int {
        val frames = (sourceFps * seconds).toInt()
        return (0 until frames).count { i -> shouldProcess(startMs + (i * 1000.0 / sourceFps).toLong()) }
    }

    @Test
    fun thirtyFpsCameraIsThinnedToEachTarget() {
        for (target in listOf(FrameRateGovernor.ACTIVE_FPS, 15, FrameRateGovernor.SEARCHING_FPS, FrameRateGovernor.STANDBY_FPS)) {
            val passed = FrameRateGovernor(target).accepted(sourceFps = 30.0, seconds = 10)
            assertEquals("target $target fps", target * 10.0, passed.toDouble(), target * 10 * 0.03)
        }
    }

    @Test
    fun slowerCameraPassesEveryFrame() {
        // In dim light the Note 8 Pro camera drops to about 20 fps.
        val passed = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS).accepted(sourceFps = 20.0, seconds = 10)
        assertEquals(200, passed)
    }

    @Test
    fun cameraJitterDoesNotChangeTheRate() {
        val governor = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS)
        val jitter = longArrayOf(0, 4, -3, 2, -4, 3, 1, -2)
        val passed = (0 until 300).count { i -> governor.shouldProcess(i * 33L + jitter[i % jitter.size]) }
        assertEquals(200.0, passed.toDouble(), 6.0)
    }

    @Test
    fun firstFrameAfterAGapIsProcessedAtOnce() {
        val governor = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS)
        governor.accepted(sourceFps = 30.0, seconds = 1)
        assertTrue(governor.shouldProcess(5_000))
    }

    @Test
    fun rateChangeAppliesImmediately() {
        val governor = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS)
        governor.accepted(sourceFps = 30.0, seconds = 2)
        governor.targetFps = FrameRateGovernor.STANDBY_FPS
        val passed = governor.accepted(sourceFps = 30.0, seconds = 10, startMs = 2_000)
        assertEquals(40.0, passed.toDouble(), 2.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroFps() {
        FrameRateGovernor(0)
    }
}
