package com.airtouch.spike

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.core.Delegate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spike B: dispatches one swipe per command. Spike C: runs the front camera (and the landmarker)
 * inside this service, optionally promoted to a camera foreground service, and logs a heartbeat
 * every 10 s with the frame rate and the app in front.
 */
class SpikeAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val framesInWindow = AtomicInteger()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var probe: LandmarkerProbe? = null
    private var receiverRegistered = false
    private var startedAt = 0L
    private var foreground = false
    private var cameraState = "none"
    private var frontPackage = "?"

    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SWIPE -> swipe(intent.getStringExtra(EXTRA_DIRECTION) ?: "up")
                ACTION_CAMERA_START -> startCamera(
                    asForeground = intent.getBooleanExtra(EXTRA_FOREGROUND, true),
                    withLandmarks = intent.getBooleanExtra(EXTRA_LANDMARKS, true),
                    // Spike A showed GPU is the only delegate that reaches 15 fps on the Note 8 Pro.
                    delegate = if (intent.getStringExtra(EXTRA_DELEGATE) == "CPU") Delegate.CPU else Delegate.GPU,
                )
                ACTION_CAMERA_STOP -> stopCamera()
            }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val frames = framesInWindow.getAndSet(0)
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "C up=%ds fg=%b frames=%d fps=%.1f cam=%s front=%s",
                    (SystemClock.uptimeMillis() - startedAt) / 1000,
                    foreground,
                    frames,
                    frames * 1000f / HEARTBEAT_MS,
                    cameraState,
                    frontPackage,
                ),
            )
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SWIPE)
            addAction(ACTION_CAMERA_START)
            addAction(ACTION_CAMERA_STOP)
        }
        ContextCompat.registerReceiver(this, commands, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        Log.i(TAG, "B/C service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != frontPackage) {
            frontPackage = pkg
            Log.i(TAG, "window front=$pkg")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopCamera()
        if (receiverRegistered) unregisterReceiver(commands)
        registry.currentState = Lifecycle.State.DESTROYED
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun swipe(direction: String) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val x = metrics.widthPixels / 2f
        // Middle 40 % of the height keeps clear of the system Home and notification gesture areas.
        val (from, to) = if (direction == "down") 0.30f to 0.70f else 0.70f to 0.30f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * from)
            lineTo(x, metrics.heightPixels * to)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
            .build()
        val sentAt = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "B swipe $direction completed in ${SystemClock.uptimeMillis() - sentAt} ms front=$frontPackage")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "B swipe $direction cancelled front=$frontPackage")
                }
            },
            null,
        )
        if (!accepted) Log.e(TAG, "B swipe $direction rejected by dispatchGesture")
    }

    private fun startCamera(asForeground: Boolean, withLandmarks: Boolean, delegate: Delegate) {
        if (analysis != null) {
            Log.w(TAG, "C already running")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "C needs the camera permission: open AirTouch Spike once and allow it")
            return
        }
        foreground = asForeground && promoteToForeground()
        SpikeState.cameraInService = true
        registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get().also { provider = it }
            val newProbe = if (withLandmarks) LandmarkerProbe(this, "C", delegate, WINDOW_C_MS) {} else null
            val newAnalysis = analysisUseCase().also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    framesInWindow.incrementAndGet()
                    if (newProbe != null) newProbe.analyze(image) else image.close()
                }
            }
            try {
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, newAnalysis)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    cameraState = state.type.name + (state.error?.let { "/error${it.code}" } ?: "")
                    Log.i(TAG, "C camera state=$cameraState")
                }
            } catch (e: Exception) {
                Log.e(TAG, "C could not bind the camera: $e")
            }
            probe = newProbe
            analysis = newAnalysis
            startedAt = SystemClock.uptimeMillis()
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
            Log.i(TAG, "C started fg=$foreground landmarks=$withLandmarks delegate=${delegate.name}")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun promoteToForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Spike C", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AirTouch spike C")
            .setContentText("Front camera running inside the accessibility service")
            .setOngoing(true)
            .build()
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            Log.i(TAG, "C startForeground(camera) accepted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "C startForeground(camera) refused: $e")
            false
        }
    }

    private fun stopCamera() {
        handler.removeCallbacks(heartbeat)
        analysis?.let { useCase ->
            useCase.clearAnalyzer()
            provider?.unbind(useCase)
        }
        probe?.let { p -> analysisExecutor.execute { p.close() } }
        analysis = null
        probe = null
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
        if (SpikeState.cameraInService) Log.i(TAG, "C stopped")
        SpikeState.cameraInService = false
    }

    private companion object {
        const val HEARTBEAT_MS = 10_000L
        const val SWIPE_MS = 250L
        const val CHANNEL_ID = "spike"
        const val NOTIFICATION_ID = 7
    }
}
