package com.airtouch.gesture.model

/** One MediaPipe hand landmark. [x] and [y] are normalised to the image (0..1); [z] is depth relative to the wrist. */
data class Landmark(val x: Float, val y: Float, val z: Float)
