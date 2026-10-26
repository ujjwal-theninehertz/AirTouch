package com.airtouch.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.Delegate
import java.util.concurrent.Executors

/** Spike A on screen, plus buttons that start and stop spike C inside the accessibility service. */
class SpikeActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statsView: TextView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var delegate = Delegate.CPU

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var probe: LandmarkerProbe? = null
    private var starting = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpikeA() else statsView.text = "Camera permission denied. Spikes A and C need it."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(EXTRA_DELEGATE) == "GPU") delegate = Delegate.GPU

        previewView = PreviewView(this)
        statsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setPadding(32, 32, 32, 32)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(button("A: switch CPU / GPU") { switchDelegate() })
            addView(button("C: start in service, foreground") { startSpikeC(foreground = true) })
            addView(button("C: start in service, no foreground") { startSpikeC(foreground = false) })
            addView(button("C: stop") { stopSpikeC() })
        }
        setContentView(
            FrameLayout(this).apply {
                fitsSystemWindows = true
                addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(statsView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
                addView(buttons, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            },
        )
    }

    override fun onStart() {
        super.onStart()
        when {
            SpikeState.cameraInService -> statsView.text = "Spike C is running in the service.\nTap \"C: stop\" to return to spike A."
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> startSpikeA()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        stopSpikeA()
        super.onStop()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startSpikeA() {
        if (SpikeState.cameraInService || analysis != null || starting) return
        starting = true
        statsView.text = "Spike A (${delegate.name})\nHold one hand 30–80 cm from the screen.\nFirst numbers in 5 s."
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            starting = false
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@addListener
            val cameraProvider = future.get().also { provider = it }
            val newProbe = LandmarkerProbe(this, "A", delegate, WINDOW_A_MS) { stats ->
                runOnUiThread { statsView.text = stats.replace(" ", "\n") }
            }
            val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val newAnalysis = analysisUseCase().also { it.setAnalyzer(analysisExecutor, newProbe) }
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, newPreview, newAnalysis)
            probe = newProbe
            preview = newPreview
            analysis = newAnalysis
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopSpikeA() {
        val cameraProvider = provider ?: return
        analysis?.clearAnalyzer()
        listOfNotNull(preview, analysis).forEach { cameraProvider.unbind(it) }
        // Close on the analysis thread, after any frame still being processed.
        probe?.let { p -> analysisExecutor.execute { p.close() } }
        preview = null
        analysis = null
        probe = null
    }

    private fun startSpikeC(foreground: Boolean) {
        if (!serviceEnabled()) {
            statsView.text = "Turn on \"AirTouch Spike\" in\nSettings → Accessibility first."
            return
        }
        stopSpikeA()
        sendToService(Intent(ACTION_CAMERA_START).putExtra(EXTRA_FOREGROUND, foreground).putExtra(EXTRA_LANDMARKS, true))
        statsView.text = "Spike C started (foreground=$foreground).\nNow open Instagram Reels.\nResults go to logcat every 10 s."
    }

    private fun stopSpikeC() {
        sendToService(Intent(ACTION_CAMERA_STOP))
        statsView.text = "Spike C stopped."
        statsView.postDelayed({ startSpikeA() }, 500)
    }

    private fun sendToService(intent: Intent) = sendBroadcast(intent.setPackage(packageName))

    private fun serviceEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    private fun switchDelegate() {
        val next = if (delegate == Delegate.CPU) "GPU" else "CPU"
        finish()
        startActivity(Intent(this, SpikeActivity::class.java).putExtra(EXTRA_DELEGATE, next))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
