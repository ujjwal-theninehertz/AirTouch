package com.airtouch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.airtouch.camera.Frame
import com.airtouch.gesture.face.FaceFrame
import com.airtouch.gesture.geometry.toUserView
import com.airtouch.gesture.model.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2

/**
 * MediaPipe Face Landmarker in live-stream mode (P4c), one frame in flight. It reads its own half-size copy of a
 * camera frame, made while the frame is handed over, so the hand detector's frame can be reused at once and
 * neither waits for the other. Results become [FaceFrame]s: blendshape scores for the user's own left and right,
 * and head angles from the nose and the eyes in the user's view. Results arrive on MediaPipe's thread.
 */
class MediaPipeFaceDetector private constructor(
    context: Context,
    mediaPipeDelegate: Delegate,
    private val onFace: (timestampMs: Long, face: FaceFrame?) -> Unit,
) : AutoCloseable {

    val delegate: String = mediaPipeDelegate.name

    private val busy = AtomicBoolean(false)
    private var copy: Bitmap? = null
    private var copyCanvas: Canvas? = null
    private val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Describe the frame in flight; written before detectAsync, read when its result arrives.
    @Volatile private var submittedAtMs = 0L
    @Volatile private var rotation = 0
    @Volatile private var mirrored = false
    @Volatile private var uprightWidth = 1f
    @Volatile private var uprightHeight = 1f
    @Volatile private var rawWidth = 1f

    private val rotationOptions: Map<Int, ImageProcessingOptions> =
        listOf(0, 90, 180, 270).associateWith { ImageProcessingOptions.builder().setRotationDegrees(it).build() }

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(FaceModel.ASSET).setDelegate(mediaPipeDelegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e ->
                Log.e(TAG, "Face Landmarker error", e)
                busy.set(false)
            }
            .build(),
    )

    fun isReady(): Boolean = !busy.get() || SystemClock.elapsedRealtime() - submittedAtMs > STUCK_AFTER_MS

    /** Camera thread: copies the frame at half size, then hands the copy to MediaPipe. */
    fun detect(frame: Frame) {
        val source = frame.bitmap
        val target = copy?.takeIf { it.width == source.width / 2 && it.height == source.height / 2 }
            ?: Bitmap.createBitmap(source.width / 2, source.height / 2, Bitmap.Config.ARGB_8888).also {
                copy = it
                copyCanvas = Canvas(it)
            }
        copyCanvas?.drawBitmap(source, null, Rect(0, 0, target.width, target.height), copyPaint)
        val sideways = frame.rotationDegrees % 180 != 0
        uprightWidth = (if (sideways) target.height else target.width).toFloat()
        uprightHeight = (if (sideways) target.width else target.height).toFloat()
        rawWidth = target.width.toFloat()
        rotation = frame.rotationDegrees
        mirrored = frame.isMirrored
        submittedAtMs = SystemClock.elapsedRealtime()
        busy.set(true)
        try {
            landmarker.detectAsync(BitmapImageBuilder(target).build(), rotationOptions.getValue(frame.rotationDegrees), frame.timestampMs)
        } catch (e: RuntimeException) {
            busy.set(false)
            Log.e(TAG, "Face Landmarker rejected a frame", e)
        }
    }

    override fun close() = landmarker.close()

    /** MediaPipe's result thread. */
    private fun onResult(result: FaceLandmarkerResult) {
        val turn = rotation
        val flip = mirrored
        val width = uprightWidth
        val height = uprightHeight
        val depthScale = rawWidth
        busy.set(false)
        val points = result.faceLandmarks().firstOrNull()
        if (points == null || points.size <= RIGHT_EYE_OUTER) {
            onFace(result.timestampMs(), null)
            return
        }
        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }.orEmpty()
        fun score(name: String) = shapes[name] ?: 0f
        // Like the hand points, MediaPipe reports face points in the raw image: turn them to the user's view.
        fun point(i: Int) = points[i].let { Landmark(it.x(), it.y(), it.z()).toUserView(turn, flip) }
        val nose = point(NOSE_TIP)
        val eyeA = point(LEFT_EYE_OUTER)
        val eyeB = point(RIGHT_EYE_OUTER)
        // Head direction: from between the eyes to the nose tip, in pixels (depth is on the raw image's width scale).
        val vx = (nose.x - (eyeA.x + eyeB.x) / 2) * width
        val vy = (nose.y - (eyeA.y + eyeB.y) / 2) * height
        val vz = (nose.z - (eyeA.z + eyeB.z) / 2) * depthScale
        val left = if (SWAP_SIDES) "Right" else "Left"
        val right = if (SWAP_SIDES) "Left" else "Right"
        onFace(
            result.timestampMs(),
            FaceFrame(
                browUpLeft = score("browOuterUp$left"),
                browUpRight = score("browOuterUp$right"),
                eyeClosedLeft = score("eyeBlink$left"),
                eyeClosedRight = score("eyeBlink$right"),
                pucker = score("mouthPucker"),
                jawOpen = score("jawOpen"),
                yaw = Math.toDegrees(atan2(vx, -vz).toDouble()).toFloat(),
                pitch = Math.toDegrees(atan2(vy, -vz).toDouble()).toFloat(),
            ),
        )
    }

    companion object {
        private const val TAG = "AirTouch"
        private const val MIN_CONFIDENCE = 0.5f
        private const val STUCK_AFTER_MS = 500L

        // Face-mesh indices: the nose tip and the outer corners of the two eyes.
        private const val NOSE_TIP = 1
        private const val LEFT_EYE_OUTER = 33
        private const val RIGHT_EYE_OUTER = 263

        /**
         * MediaPipe names blendshapes for the face's own sides when the frame is not mirrored, as ours are. Flip
         * this if a recorded left-eyebrow raise shows up on the right.
         */
        private const val SWAP_SIDES = false

        /** GPU first, as for hands; CPU if the GPU cannot run the model. */
        fun create(context: Context, onFace: (Long, FaceFrame?) -> Unit): MediaPipeFaceDetector =
            try {
                MediaPipeFaceDetector(context, Delegate.GPU, onFace)
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable for faces, falling back to CPU", e)
                MediaPipeFaceDetector(context, Delegate.CPU, onFace)
            }
    }
}
