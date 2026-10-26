package com.airtouch.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.airtouch.camera.Frame
import com.airtouch.gesture.geometry.toUserView
import com.airtouch.gesture.model.HandFrame
import com.airtouch.gesture.model.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** MediaPipe Hand Landmarker in live-stream mode, one frame in flight at a time. */
class MediaPipeHandDetector private constructor(
    context: Context,
    mediaPipeDelegate: Delegate,
    private val onResult: (HandResult) -> Unit,
) : HandDetector {

    override val delegate: String = mediaPipeDelegate.name

    private val _stats = MutableStateFlow<DetectionStats?>(null)
    override val stats: StateFlow<DetectionStats?> = _stats.asStateFlow()

    private val busy = AtomicBoolean(false)
    private val skipped = AtomicInteger()
    private val window = DetectionWindow()

    // Describe the frame in flight; written before detectAsync, read when its result arrives.
    @Volatile private var submittedAtMs = 0L
    @Volatile private var uprightWidth = 0
    @Volatile private var uprightHeight = 0
    @Volatile private var rotation = 0
    @Volatile private var mirrored = false

    private val rotationOptions: Map<Int, ImageProcessingOptions> =
        listOf(0, 90, 180, 270).associateWith { ImageProcessingOptions.builder().setRotationDegrees(it).build() }

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(HandModel.ASSET).setDelegate(mediaPipeDelegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(MIN_CONFIDENCE)
            .setMinHandPresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setResultListener { result, _ -> onLandmarks(result) }
            .setErrorListener { e ->
                Log.e(TAG, "Hand Landmarker error", e)
                busy.set(false)
            }
            .build(),
    )

    override fun isReady(): Boolean {
        if (!busy.get() || SystemClock.elapsedRealtime() - submittedAtMs > STUCK_AFTER_MS) return true
        skipped.incrementAndGet()
        return false
    }

    override fun detect(frame: Frame) {
        val sideways = frame.rotationDegrees % 180 != 0
        uprightWidth = if (sideways) frame.bitmap.height else frame.bitmap.width
        uprightHeight = if (sideways) frame.bitmap.width else frame.bitmap.height
        rotation = frame.rotationDegrees
        mirrored = frame.isMirrored
        submittedAtMs = SystemClock.elapsedRealtime()
        busy.set(true)
        try {
            landmarker.detectAsync(BitmapImageBuilder(frame.bitmap).build(), rotationOptions.getValue(frame.rotationDegrees), frame.timestampMs)
        } catch (e: RuntimeException) {
            busy.set(false)
            Log.e(TAG, "Hand Landmarker rejected a frame", e)
        }
    }

    override fun close() = landmarker.close()

    /** MediaPipe's result thread. */
    private fun onLandmarks(result: HandLandmarkerResult) {
        val inferenceMs = SystemClock.elapsedRealtime() - submittedAtMs
        val width = uprightWidth
        val height = uprightHeight
        val turn = rotation
        val flip = mirrored
        busy.set(false)

        val handedness = result.handedness()
        val hands = result.landmarks().mapIndexed { i, points ->
            val category = handedness.getOrNull(i)?.firstOrNull()
            HandFrame(
                // Rotation only helps detection: MediaPipe still reports points in the raw camera image.
                landmarks = points.map { p -> Landmark(p.x(), p.y(), p.z()).toUserView(turn, flip) },
                // Our frames are never flipped, and on them MediaPipe's label names the user's actual hand
                // (checked on the Note 8 Pro; older docs that say to swap assume mirrored input).
                isRightHand = category?.categoryName() == "Right",
                score = category?.score() ?: 0f,
                timestampMs = result.timestampMs(),
            )
        }
        window.add(inferenceMs, hands.isNotEmpty())?.let { _stats.value = it }
        onResult(HandResult(result.timestampMs(), hands, primary = null, imageWidth = width, imageHeight = height))
    }

    /** Inference times and hand presence over a two-second window. Used on the result thread only. */
    private inner class DetectionWindow {
        private val inference = LongArray(SAMPLES)
        private val sorted = LongArray(SAMPLES)
        private var count = 0
        private var withHand = 0
        private var startMs = -1L

        fun add(inferenceMs: Long, handFound: Boolean): DetectionStats? {
            val now = SystemClock.elapsedRealtime()
            if (startMs < 0) startMs = now
            inference[count % SAMPLES] = inferenceMs
            count++
            if (handFound) withHand++
            val elapsed = now - startMs
            if (elapsed < WINDOW_MS) return null
            val n = minOf(count, SAMPLES)
            inference.copyInto(sorted, endIndex = n)
            sorted.sort(0, n)
            return DetectionStats(
                delegate = delegate,
                resultsFps = count * 1000f / elapsed,
                handPercent = withHand * 100 / count,
                inferenceP50Ms = sorted[n / 2],
                inferenceP95Ms = sorted[(n * 95 / 100).coerceAtMost(n - 1)],
                skippedWhileBusy = skipped.getAndSet(0),
            ).also {
                count = 0
                withHand = 0
                startMs = now
            }
        }
    }

    companion object {
        private const val TAG = "AirTouch"
        // With room for a second hand that is not there, MediaPipe re-runs palm detection on every frame
        // and halves the frame rate on the Note 8 Pro. PrimaryHandSelector still guards against switches.
        private const val MAX_HANDS = 1
        private const val MIN_CONFIDENCE = 0.5f
        private const val STUCK_AFTER_MS = 500L
        private const val SAMPLES = 128
        private const val WINDOW_MS = 2_000L

        /** GPU first (Phase 0: the only delegate fast enough on the Note 8 Pro), CPU if the GPU cannot run the model. */
        fun create(context: Context, onResult: (HandResult) -> Unit): MediaPipeHandDetector =
            try {
                MediaPipeHandDetector(context, Delegate.GPU, onResult)
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
                MediaPipeHandDetector(context, Delegate.CPU, onResult)
            }
    }
}
