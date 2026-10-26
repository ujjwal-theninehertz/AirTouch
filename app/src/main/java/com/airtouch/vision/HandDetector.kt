package com.airtouch.vision

import com.airtouch.camera.Frame
import kotlinx.coroutines.flow.StateFlow

/** Finds hands in camera frames. Results arrive on the detector's own thread through the listener given at creation. */
interface HandDetector : AutoCloseable {

    /** Hardware running the model: "GPU" or "CPU". */
    val delegate: String

    val stats: StateFlow<DetectionStats?>

    /** False while the previous frame is still being processed. Skipping keeps latency low instead of building a queue. */
    fun isReady(): Boolean

    fun detect(frame: Frame)
}
