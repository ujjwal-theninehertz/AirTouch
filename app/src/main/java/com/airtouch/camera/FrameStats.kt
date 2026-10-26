package com.airtouch.camera

import java.util.Locale

/** Frame counters for one camera session, updated on the camera thread and published every [WINDOW_MS]. */
class FrameStats internal constructor(private val publish: (Snapshot) -> Unit) {

    data class Snapshot(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val inputFps: Float,
        val processedFps: Float,
        val conversionP50Ms: Float,
        val conversionP95Ms: Float,
        val firstFrameMs: Long,
        val unclosedFrames: Long,
        val bufferAllocations: Int,
    ) {
        fun logLine(): String = String.format(
            Locale.US,
            "res=%dx%d rot=%d in=%.1f out=%.1f conv_p50=%.2fms conv_p95=%.2fms first_frame=%dms unclosed=%d allocs=%d",
            width, height, rotationDegrees, inputFps, processedFps, conversionP50Ms, conversionP95Ms,
            firstFrameMs, unclosedFrames, bufferAllocations,
        )
    }

    private val conversionNanos = LongArray(SAMPLES)
    private val sorted = LongArray(SAMPLES)
    private var samples = 0
    private var received = 0L
    private var closed = 0L
    private var windowReceived = 0
    private var windowProcessed = 0
    private var windowStartMs = -1L
    private var firstFrameMs = -1L

    internal fun onReceived(nowMs: Long, sessionStartMs: Long) {
        if (firstFrameMs < 0) firstFrameMs = nowMs - sessionStartMs
        if (windowStartMs < 0) windowStartMs = nowMs
        received++
        windowReceived++
    }

    internal fun onProcessed(conversionNs: Long) {
        conversionNanos[samples % SAMPLES] = conversionNs
        samples++
        windowProcessed++
    }

    internal fun onClosed() {
        closed++
    }

    /** Called after each frame is closed; publishes a snapshot once per window. */
    internal fun maybePublish(nowMs: Long, width: Int, height: Int, rotationDegrees: Int, allocations: Int) {
        val elapsed = nowMs - windowStartMs
        if (windowStartMs < 0 || elapsed < WINDOW_MS) return
        val count = minOf(samples, SAMPLES)
        conversionNanos.copyInto(sorted, endIndex = count)
        sorted.sort(0, count)
        val seconds = elapsed / 1000f
        publish(
            Snapshot(
                width = width,
                height = height,
                rotationDegrees = rotationDegrees,
                inputFps = windowReceived / seconds,
                processedFps = windowProcessed / seconds,
                conversionP50Ms = if (count > 0) sorted[count / 2] / 1e6f else 0f,
                conversionP95Ms = if (count > 0) sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6f else 0f,
                firstFrameMs = firstFrameMs,
                unclosedFrames = received - closed,
                bufferAllocations = allocations,
            ),
        )
        windowReceived = 0
        windowProcessed = 0
        windowStartMs = nowMs
    }

    private companion object {
        const val SAMPLES = 128
        const val WINDOW_MS = 2_000L
    }
}
