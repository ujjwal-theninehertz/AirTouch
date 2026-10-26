package com.airtouch.pipeline

import android.content.Context
import android.util.Log
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.airtouch.camera.CameraSource
import com.airtouch.camera.CameraStatus
import com.airtouch.camera.Frame
import com.airtouch.camera.FrameRateGovernor
import com.airtouch.camera.FrameSink
import com.airtouch.camera.FrameStats
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.face.FaceEngine
import com.airtouch.gesture.face.FaceFrame
import com.airtouch.gesture.face.FaceInput
import com.airtouch.gesture.geometry.PrimaryHandSelector
import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.HandInput
import com.airtouch.settings.GestureMode
import com.airtouch.vision.DetectionStats
import com.airtouch.vision.HandDetector
import com.airtouch.vision.HandResult
import com.airtouch.vision.MediaPipeFaceDetector
import com.airtouch.vision.MediaPipeHandDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Camera frames to landmarks to gestures (P1–P3, P4c), for one [mode]: hand landmarks and the gesture engine, or
 * face landmarks and the face engine. Only that mode's model is loaded (which takes seconds: build this off the
 * main thread), and it gets every frame it is free for. Runs at the active rate while a hand or face is in view
 * and drops to the searching rate after [SEARCH_AFTER_MS] without one. Hosted by practice mode and by the
 * accessibility service.
 */
class PerceptionPipeline(private val context: Context, val mode: GestureMode) : AutoCloseable {

    private val governor = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS)
    private val camera = CameraSource(context, governor)
    private val selector = PrimaryHandSelector()
    // Each flick's measured speeds go to the log, for tuning from live tests.
    private val engine = GestureEngine().also { it.trace = { message -> Log.i("AirTouch", "P4 $message") } }
    private val faceEngine = FaceEngine().also { it.trace = { message -> Log.i("AirTouch", "P4c $message") } }
    private var lastHandMs = 0L
    private var faceLoggedMs = 0L
    private var lastFaceMs = Long.MIN_VALUE / 2

    private val detector: HandDetector? = if (mode == GestureMode.HANDS) MediaPipeHandDetector.create(context, ::onResult) else null
    private val faceDetector: MediaPipeFaceDetector? = if (mode == GestureMode.FACE) MediaPipeFaceDetector.create(context, ::onFace) else null

    private val _face = MutableStateFlow<FaceFrame?>(null)

    /** The latest face as the face gestures see it, for display; null in hand mode or with no face in view. */
    val face: StateFlow<FaceFrame?> = _face.asStateFlow()

    /** Called for every face engine output on MediaPipe's face thread. */
    @Volatile
    var faceListener: ((FaceEngine.Output) -> Unit)? = null

    /** Called for every face frame on MediaPipe's face thread, for the trace recorder. */
    @Volatile
    var faceFrameListener: ((Long, FaceFrame?) -> Unit)? = null

    private val _decision = MutableStateFlow<GestureEngine.Output?>(null)

    /** The gesture engine's view of the latest frame: guard state, pose, and an event when one fired. */
    val decision: StateFlow<GestureEngine.Output?> = _decision.asStateFlow()

    private val _lastGesture = MutableStateFlow<GestureEvent?>(null)
    val lastGesture: StateFlow<GestureEvent?> = _lastGesture.asStateFlow()

    /** Screen width ÷ height in its current orientation, so the cursor moves evenly. Set on start and on rotation. */
    var screenAspect: Float
        get() = engine.screenAspect
        set(value) {
            engine.screenAspect = value
        }

    /** The engine is reset on its own thread before the next frame, after a rotation turned the picture. */
    @Volatile
    private var resetPending = false

    /**
     * The screen turned: frames follow at once, and whatever the engine was tracking (a swipe, a pinch) is
     * dropped, since the hand's coordinates jump by a quarter turn. Call from the main thread.
     */
    fun setDisplayRotation(rotation: Int) {
        camera.setTargetRotation(rotation)
        resetPending = true
        faceResetPending = true
    }

    @Volatile
    private var faceResetPending = false

    /** A call is ringing: an open hand held still answers, so the engine watches for one. */
    var callRinging: Boolean
        get() = engine.callRinging
        set(value) {
            engine.callRinging = value
        }

    /** While paused, only the thumbs-down (resume) gesture is reported. */
    var paused: Boolean
        get() = engine.paused
        set(value) {
            engine.paused = value
            faceEngine.paused = value
        }

    /** Called for every engine output on the detector thread, including rejections. */
    @Volatile
    var decisionListener: ((GestureEngine.Output) -> Unit)? = null

    private val _results = MutableStateFlow<HandResult?>(null)

    /** Latest result, for display. Use [resultListener] to see every result. */
    val results: StateFlow<HandResult?> = _results.asStateFlow()
    val cameraStatus: StateFlow<CameraStatus> get() = camera.status
    val cameraStats: StateFlow<FrameStats.Snapshot?> get() = camera.stats
    /** The hand detector's timings; none in face mode. */
    val detectorStats: StateFlow<DetectionStats?> = detector?.stats ?: MutableStateFlow<DetectionStats?>(null).asStateFlow()

    /** Called for every result on the detector thread. */
    @Volatile
    var resultListener: ((HandResult) -> Unit)? = null

    fun start(owner: LifecycleOwner, targetRotation: Int, previewSurface: Preview.SurfaceProvider? = null, onFrame: (Frame) -> Unit = {}) {
        camera.start(
            owner,
            targetRotation,
            previewSurface,
            object : FrameSink {
                override fun isReady() = detector?.isReady() ?: faceDetector?.isReady() ?: false

                override fun onFrame(frame: Frame) {
                    detector?.detect(frame)
                    faceDetector?.detect(frame)
                    onFrame(frame)
                }
            },
        )
    }

    override fun close() {
        camera.close()
        detector?.close()
        faceDetector?.close()
    }

    /** MediaPipe's face thread. */
    private fun onFace(timestampMs: Long, face: FaceFrame?) {
        if (faceResetPending) {
            faceResetPending = false
            faceEngine.reset()
        }
        _face.value = face
        if (face != null || lastFaceMs < 0) lastFaceMs = timestampMs
        if (face != null) {
            governor.targetFps = FrameRateGovernor.ACTIVE_FPS
        } else if (timestampMs - lastFaceMs > SEARCH_AFTER_MS) {
            governor.targetFps = FrameRateGovernor.SEARCHING_FPS
        }
        faceFrameListener?.invoke(timestampMs, face)
        val output = faceEngine.process(FaceInput(timestampMs, face))
        // Every two seconds, what the face rules see: tuning face thresholds starts from these numbers.
        if (face != null && timestampMs - faceLoggedMs >= FACE_LOG_MS) {
            faceLoggedMs = timestampMs
            Log.i("AirTouch", "P4c face ${faceEngine.status}")
        }
        faceListener?.invoke(output)
    }

    /** Detector thread. */
    private fun onResult(raw: HandResult) {
        val result = raw.copy(primary = selector.select(raw.hands))
        if (result.hands.isNotEmpty()) {
            lastHandMs = result.timestampMs
            governor.targetFps = FrameRateGovernor.ACTIVE_FPS
        } else if (result.timestampMs - lastHandMs > SEARCH_AFTER_MS) {
            governor.targetFps = FrameRateGovernor.SEARCHING_FPS
        }
        _results.value = result
        resultListener?.invoke(result)

        if (resetPending) {
            resetPending = false
            engine.reset()
        }
        val output = engine.process(HandInput(result.timestampMs, result.primary, result.imageWidth.toFloat() / result.imageHeight))
        _decision.value = output
        output.event?.let { _lastGesture.value = it }
        decisionListener?.invoke(output)
    }

    private companion object {
        const val SEARCH_AFTER_MS = 3_000L
        const val FACE_LOG_MS = 2_000L
    }
}
