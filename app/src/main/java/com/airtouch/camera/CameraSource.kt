package com.airtouch.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front-camera frames for the gesture pipeline (P1). Binds CameraX ImageAnalysis, plus a Preview when
 * a surface is given, to a [LifecycleOwner]: the activity in practice mode, the accessibility service
 * in live mode (P5). The camera opens while the owner is started and closes when it stops.
 */
class CameraSource(private val context: Context, private val governor: FrameRateGovernor = FrameRateGovernor(FrameRateGovernor.ACTIVE_FPS)) {

    private val _status = MutableStateFlow<CameraStatus>(CameraStatus.Idle)
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow<FrameStats.Snapshot?>(null)
    val stats: StateFlow<FrameStats.Snapshot?> = _stats.asStateFlow()

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "airtouch-camera") }
    private var provider: ProcessCameraProvider? = null
    private var useCases: List<UseCase> = emptyList()
    private var camera: Camera? = null
    private val stateObserver = Observer<CameraState> { _status.value = it.toStatus() }

    /** Starts streaming. [targetRotation] is the display rotation, a `Surface.ROTATION_*` value. */
    fun start(owner: LifecycleOwner, targetRotation: Int, previewSurface: Preview.SurfaceProvider? = null, sink: FrameSink) {
        check(useCases.isEmpty()) { "CameraSource is already started" }
        _status.value = CameraStatus.Opening
        val sessionStartMs = SystemClock.elapsedRealtime()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            val cameraProvider = future.get().also { provider = it }
            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                _status.value = CameraStatus.Problem(CameraProblem.NoFrontCamera, recoverable = false)
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ANALYSIS_RESOLUTION)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(targetRotation)
                .build()
            analysis.setAnalyzer(cameraExecutor, FrameProcessor(sessionStartMs, sink))
            val preview = previewSurface?.let { surface ->
                Preview.Builder().setTargetRotation(targetRotation).build().also { it.setSurfaceProvider(surface) }
            }
            val cases = listOfNotNull(preview, analysis)
            try {
                camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *cases.toTypedArray()).also {
                    it.cameraInfo.cameraState.observe(owner, stateObserver)
                }
                useCases = cases
            } catch (e: Exception) {
                Log.e(TAG, "Could not bind the front camera", e)
                _status.value = CameraStatus.Problem(CameraProblem.BindFailed, recoverable = true)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Follows a display rotation (a `Surface.ROTATION_*` value) without reopening the camera: frames then carry the
     * new rotation, which the detector uses to turn landmarks upright. Call from the main thread.
     */
    fun setTargetRotation(rotation: Int) {
        useCases.forEach { useCase ->
            when (useCase) {
                is ImageAnalysis -> useCase.targetRotation = rotation
                is Preview -> useCase.targetRotation = rotation
            }
        }
    }

    /** Releases the camera; call from the main thread. The source cannot be restarted after [close]. */
    fun close() {
        camera?.cameraInfo?.cameraState?.removeObserver(stateObserver)
        useCases.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        provider?.unbind(*useCases.toTypedArray())
        useCases = emptyList()
        camera = null
        cameraExecutor.shutdown()
        _status.value = CameraStatus.Idle
    }

    /** Runs on the camera thread: drop early, convert the rest, always close the proxy. */
    private inner class FrameProcessor(private val sessionStartMs: Long, private val sink: FrameSink) : ImageAnalysis.Analyzer {
        private val converter = FrameConverter()
        private val frameStats = FrameStats { _stats.value = it }

        override fun analyze(image: ImageProxy) {
            // A closed ImageProxy may throw on any access, so read what the stats need first.
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            frameStats.onReceived(SystemClock.elapsedRealtime(), sessionStartMs)
            try {
                // Ask the sink first so a frame skipped while the detector is busy does not use up a governor
                // slot. Skipping before conversion also keeps the reused bitmap untouched while it is read.
                if (sink.isReady() && governor.shouldProcess(image.imageInfo.timestamp / 1_000_000)) {
                    val startNs = SystemClock.elapsedRealtimeNanos()
                    val frame = converter.convert(image, isMirrored = true)
                    frameStats.onProcessed(SystemClock.elapsedRealtimeNanos() - startNs)
                    sink.onFrame(frame)
                }
            } finally {
                image.close()
                frameStats.onClosed()
            }
            frameStats.maybePublish(SystemClock.elapsedRealtime(), width, height, rotation, converter.allocations)
        }
    }

    private companion object {
        const val TAG = "AirTouch"

        /** 640×480 is plenty: MediaPipe scales to 192 px for palm detection and 224 px for landmarks. */
        val ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
    }
}

private fun CameraState.toStatus(): CameraStatus {
    val error = error
    if (error != null) {
        val reason = when (error.code) {
            CameraState.ERROR_CAMERA_IN_USE -> CameraProblem.InUseByAnotherApp
            CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraProblem.TooManyCamerasOpen
            CameraState.ERROR_CAMERA_DISABLED -> CameraProblem.DisabledByPolicy
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CameraProblem.DoNotDisturb
            CameraState.ERROR_STREAM_CONFIG -> CameraProblem.StreamConfiguration
            CameraState.ERROR_CAMERA_FATAL_ERROR -> CameraProblem.Fatal
            else -> CameraProblem.Unknown
        }
        return CameraStatus.Problem(reason, recoverable = error.type == CameraState.ErrorType.RECOVERABLE)
    }
    return when (type) {
        CameraState.Type.OPEN -> CameraStatus.Streaming
        CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING -> CameraStatus.Opening
        CameraState.Type.CLOSING, CameraState.Type.CLOSED -> CameraStatus.Idle
    }
}
