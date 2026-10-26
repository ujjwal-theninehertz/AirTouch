package com.airtouch.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airtouch.AirTouchApp
import com.airtouch.camera.Frame
import com.airtouch.diagnostics.TraceRecorder
import com.airtouch.gesture.model.Touch
import com.airtouch.pipeline.PerceptionPipeline
import com.airtouch.ui.theme.AirTouchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Practice mode: live preview with the detected hand skeleton drawn on top (or the face's scores, in face mode),
 * the gestures AirTouch recognises (a dry run: nothing is sent to other apps), camera and detector statistics, a
 * thumbnail of the frame passed to the detector, and a recorder for labelled landmark traces. It follows the
 * gesture mode in settings, like hands-free mode.
 */
class PracticeActivity : ComponentActivity() {

    private var pipeline: PerceptionPipeline? = null
    private var recorder: TraceRecorder? = null
    private val thumbnail = MutableStateFlow<ImageBitmap?>(null)
    private val hint = MutableStateFlow<Hint?>(null)
    private val touchNote = MutableStateFlow<Hint?>(null)
    private val faceGesture = MutableStateFlow<com.airtouch.gesture.model.GestureEvent?>(null)
    private var lastThumbnailMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // The setup screen owns the permission flow.
            finish()
            return
        }
        enableEdgeToEdge()
        val traces = TraceRecorder(File(filesDir, TRACE_DIR)).also { recorder = it }
        val previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val ready = MutableStateFlow<PerceptionPipeline?>(null)
        lifecycleScope.launch {
            // Loading a model onto the GPU takes seconds: off the main thread, or the screen freezes (Android's "not
            // responding", 25 Oct 2026).
            val mode = (application as AirTouchApp).graph.settings.settings.first().gestureMode
            val perception = withContext(NonCancellable + Dispatchers.Default) { PerceptionPipeline(applicationContext, mode) }
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                perception.close()
                return@launch
            }
            pipeline = perception
            startPipeline(perception, traces, previewView)
            ready.value = perception
        }
        setContent {
            AirTouchTheme {
                val perception = ready.collectAsStateWithLifecycle().value
                if (perception == null) {
                    PracticeStarting()
                } else {
                    PracticeScreen(
                        mode = perception.mode,
                        previewView = previewView,
                        status = perception.cameraStatus.collectAsStateWithLifecycle().value,
                        cameraStats = perception.cameraStats.collectAsStateWithLifecycle().value,
                        detectorStats = perception.detectorStats.collectAsStateWithLifecycle().value,
                        result = perception.results.collectAsStateWithLifecycle().value,
                        decision = perception.decision.collectAsStateWithLifecycle().value,
                        lastGesture = perception.lastGesture.collectAsStateWithLifecycle().value,
                        hint = hint.collectAsStateWithLifecycle().value,
                        touchNote = touchNote.collectAsStateWithLifecycle().value,
                        face = perception.face.collectAsStateWithLifecycle().value,
                        faceGesture = faceGesture.collectAsStateWithLifecycle().value,
                        thumbnail = thumbnail.collectAsStateWithLifecycle().value,
                        recording = traces.recording.collectAsStateWithLifecycle().value,
                        onRecord = { label ->
                            traces.start(label)
                            Log.i(TAG, "P2 trace started $label")
                        },
                        onStopRecording = ::stopRecording,
                    )
                }
            }
        }
    }

    private fun startPipeline(perception: PerceptionPipeline, traces: TraceRecorder, previewView: PreviewView) {
        perception.resultListener = traces::record
        perception.faceFrameListener = traces::recordFace
        perception.faceListener = { out ->
            out.event?.let {
                Log.i(TAG, "P4c practice gesture ${it.type}")
                faceGesture.value = it
            }
            out.rejected?.let { Log.i(TAG, "P4c practice rejected $it") }
        }
        perception.decisionListener = { out ->
            out.event?.let { Log.i(TAG, "P3 gesture ${it.type} conf=${"%.2f".format(it.confidence)}") }
            out.rejected?.let { reason ->
                Log.i(TAG, "P3 rejected $reason")
                coachingFor(reason)?.let { hint.value = Hint(it, SystemClock.elapsedRealtime()) }
            }
            out.touch?.let { touch ->
                if (touch !is Touch.Move && touch !is Touch.Zoom) Log.i(TAG, "P4b practice touch $touch")
                describe(touch)?.let { touchNote.value = Hint(it, SystemClock.elapsedRealtime()) }
            }
        }
        perception.start(this, ContextCompat.getDisplayOrDefault(this).rotation, previewView.surfaceProvider) { onFrame(it) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                perception.detectorStats.filterNotNull().collect { det ->
                    Log.i(TAG, "P2 practice ${det.logLine()} | cam ${perception.cameraStats.value?.logLine()}")
                }
            }
        }
    }

    override fun onStop() {
        stopRecording()
        super.onStop()
    }

    override fun onDestroy() {
        pipeline?.close()
        recorder?.close()
        super.onDestroy()
    }

    private fun stopRecording() {
        recorder?.stop()?.let {
            Log.i(
                TAG,
                "P2 trace saved ${it.fileName} frames=${it.frames} with_hand=${it.framesWithHand} face_frames=${it.faceFrames} " +
                    "with_face=${it.framesWithFace} seconds=${it.seconds}",
            )
        }
    }

    /** Camera thread. Copies a small upright, mirrored thumbnail a few times a second. */
    private fun onFrame(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastThumbnailMs < THUMBNAIL_INTERVAL_MS) return
        lastThumbnailMs = now
        thumbnail.value = frame.uprightThumbnail().asImageBitmap()
    }

    private fun Frame.uprightThumbnail(): Bitmap {
        val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / THUMBNAIL_DIVISOR, bitmap.height / THUMBNAIL_DIVISOR, false)
        val upright = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isMirrored) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, upright, false)
    }

    /** What the virtual finger would do in hands-free mode. Moves and zoom steps are too frequent to show. */
    private fun describe(touch: Touch): String? = when (touch) {
        is Touch.Tap -> "Tap"
        is Touch.DoubleTap -> "Double tap"
        is Touch.Down -> if (touch.hold) "Long press" else "Drag"
        Touch.Up -> "Lift"
        is Touch.ZoomStart -> "Zoom"
        Touch.ZoomEnd -> "Zoom done"
        is Touch.Move, is Touch.Zoom -> null
    }

    /** Explains refused swipes that were clearly meant as swipes. Ordinary movement and return strokes stay silent. */
    private fun coachingFor(reason: String): String? = when (reason) {
        "swipe too slow" -> "Too slow: flick your hand faster"
        "swipe not straight" -> "Move straight up, down, left or right"
        "hand not open during swipe" -> "Keep your palm open while swiping"
        "swipe too quick" -> "Too short: move a little further"
        else -> null
    }

    private companion object {
        const val TAG = "AirTouch"
        const val TRACE_DIR = "traces"
        const val THUMBNAIL_INTERVAL_MS = 300L
        const val THUMBNAIL_DIVISOR = 4
    }
}
