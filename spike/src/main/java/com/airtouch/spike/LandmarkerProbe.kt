package com.airtouch.spike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.Locale

/**
 * Runs Hand Landmarker on every analysed frame and reports, once per window: results per second,
 * frames submitted per second, frame-to-result latency (p50, p95) and how often a hand was found.
 */
class LandmarkerProbe(
    context: Context,
    private val label: String,
    delegate: Delegate,
    private val windowMs: Long,
    private val onStats: (String) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val delegateName = delegate.name
    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "$label landmarker error", e) }
            .build(),
    )

    private val lock = Any()
    private val latencies = ArrayList<Long>()
    private var submitted = 0
    private var withHand = 0
    private var windowStart = SystemClock.uptimeMillis()
    private var lastTimestamp = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            // LIVE_STREAM requires strictly increasing timestamps.
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1).also { t -> lastTimestamp = t }
            val frame = BitmapImageBuilder(it.toBitmap()).build()
            val rotation = ImageProcessingOptions.builder().setRotationDegrees(it.imageInfo.rotationDegrees).build()
            synchronized(lock) { submitted++ }
            landmarker.detectAsync(frame, rotation, timestamp)
        }
    }

    private fun onResult(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val report = synchronized(lock) {
            latencies += now - result.timestampMs()
            if (result.landmarks().isNotEmpty()) withHand++
            val elapsed = now - windowStart
            if (elapsed < windowMs) return
            val sorted = latencies.sorted()
            val seconds = elapsed / 1000f
            String.format(
                Locale.US,
                "%s %s fps=%.1f in=%.1f lat_p50=%dms lat_p95=%dms hand=%d%%",
                label,
                delegateName,
                sorted.size / seconds,
                submitted / seconds,
                sorted[sorted.size / 2],
                sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)],
                withHand * 100 / sorted.size,
            ).also {
                latencies.clear()
                submitted = 0
                withHand = 0
                windowStart = now
            }
        }
        Log.i(TAG, report)
        onStats(report)
    }

    override fun close() = landmarker.close()
}
