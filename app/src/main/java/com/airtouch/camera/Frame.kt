package com.airtouch.camera

import android.graphics.Bitmap

/**
 * One camera frame, ready for hand detection. The same instance and [bitmap] are reused for every
 * frame, so read them only inside [FrameSink.onFrame] and copy anything you need to keep.
 *
 * Pixels are as the sensor delivered them: [rotationDegrees] is the clockwise rotation that makes the
 * image upright on the current display, and [isMirrored] says whether it still needs a horizontal flip
 * to match what the user sees (true for the front camera). Both are applied to landmarks later, which
 * is far cheaper than rotating every pixel.
 */
class Frame internal constructor(val bitmap: Bitmap) {
    var timestampMs: Long = 0
        internal set
    var rotationDegrees: Int = 0
        internal set
    var isMirrored: Boolean = false
        internal set
}

/** Receives processed frames on the camera thread. */
fun interface FrameSink {
    fun onFrame(frame: Frame)

    /** Checked before a frame is converted. Return false to skip it, for example while the detector is still busy. */
    fun isReady(): Boolean = true
}
