package com.airtouch.vision

import com.airtouch.gesture.model.HandFrame
import java.util.Locale

/**
 * One detection. Landmarks are normalised to the upright frame as the user sees it (mirrored for the
 * front camera), which is [imageWidth]×[imageHeight] pixels. [hands] is empty when no hand is in view;
 * [primary] is the hand AirTouch follows.
 */
data class HandResult(
    val timestampMs: Long,
    val hands: List<HandFrame>,
    val primary: HandFrame?,
    val imageWidth: Int,
    val imageHeight: Int,
)

data class DetectionStats(
    val delegate: String,
    val resultsFps: Float,
    val handPercent: Int,
    val inferenceP50Ms: Long,
    val inferenceP95Ms: Long,
    val skippedWhileBusy: Int,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "det=%s fps=%.1f hand=%d%% inf_p50=%dms inf_p95=%dms skipped=%d",
        delegate, resultsFps, handPercent, inferenceP50Ms, inferenceP95Ms, skippedWhileBusy,
    )
}
