package com.airtouch.camera

/**
 * Lets only [targetFps] frames per second through, before any conversion work is done. Phase 0
 * showed that feeding all 30 camera frames per second overloads the Note 8 Pro while Instagram plays
 * video. Frames are picked on a fixed cadence, so a 30 fps camera yields a steady 20, 15 or 8 fps
 * rather than every other frame.
 *
 * Call [shouldProcess] from the camera thread only; [targetFps] may be changed from any thread.
 */
class FrameRateGovernor(initialFps: Int) {

    @Volatile
    var targetFps: Int = initialFps
        set(value) {
            require(value > 0) { "targetFps must be positive, was $value" }
            field = value
        }

    private var scheduleFps = 0
    private var nextDueMs = 0.0

    init {
        targetFps = initialFps
    }

    fun shouldProcess(timestampMs: Long): Boolean {
        val fps = targetFps
        val interval = 1000.0 / fps
        // Restart the cadence on the first frame, after a rate change, or after a gap of a whole slot.
        if (fps != scheduleFps || timestampMs >= nextDueMs + interval) {
            scheduleFps = fps
            nextDueMs = timestampMs + interval
            return true
        }
        if (timestampMs < nextDueMs - interval * EARLY_TOLERANCE) return false
        nextDueMs += interval
        return true
    }

    companion object {
        /** Hand in view: enough samples to see a 200 ms swipe. */
        const val ACTIVE_FPS = 20

        /** No hand for a few seconds. */
        const val SEARCHING_FPS = 8

        /** Paused: only watching for the resume gesture. */
        const val STANDBY_FPS = 4

        /** A frame may arrive this fraction of an interval early and still take the slot. */
        private const val EARLY_TOLERANCE = 0.25
    }
}
