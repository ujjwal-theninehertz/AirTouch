package com.airtouch.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.airtouch.AirTouchApp
import com.airtouch.accessibility.AirTouchAccessibilityService
import com.airtouch.accessibility.CallMonitor
import com.airtouch.accessibility.LiveState
import com.airtouch.accessibility.LiveStatus
import com.airtouch.settings.AirTouchSettings
import com.airtouch.settings.GestureMode
import com.airtouch.ui.practice.PracticeActivity
import com.airtouch.ui.theme.AirTouchTheme
import com.airtouch.vision.HandModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var status by mutableStateOf(SetupStatus())
    private var pickerApps by mutableStateOf<List<LaunchableApp>?>(null)
    private var phoneAsked = false
    private val settingsRepository get() = (application as AirTouchApp).graph.settings

    // Android stops showing the dialog after the second refusal; then only App info can grant it.
    private var cameraRefusedForGood = false

    private val requestPhone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        LiveStatus.onPermissionsChanged()
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraRefusedForGood = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirTouchTheme {
                val settings = settingsRepository.settings.collectAsStateWithLifecycle(AirTouchSettings()).value
                SetupScreen(
                    status = status,
                    live = LiveStatus.state.collectAsStateWithLifecycle().value,
                    shortcutApp = settings.shortcutApp?.let(::appLabel),
                    onChooseShortcutApp = { pickerApps = launchableApps() },
                    gestureMode = settings.gestureMode,
                    onSetGestureMode = ::setGestureMode,
                    onCameraAction = ::onCameraAction,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenPractice = { startActivity(Intent(this, PracticeActivity::class.java)) },
                    onOpenTapTest = { startActivity(Intent(this, TapTestActivity::class.java)) },
                    onAllowPhone = ::onAllowPhone,
                    // Started while AirTouch is on screen: the documented way to keep camera access afterwards.
                    onStartLive = { LiveStatus.start() },
                    onStopLive = { LiveStatus.stop() },
                )
                pickerApps?.let { apps ->
                    AppPickerDialog(
                        apps = apps,
                        current = settings.shortcutApp,
                        onPick = { app -> setShortcutApp(app.packageName) },
                        onClear = { setShortcutApp(null) },
                        onDismiss = { pickerApps = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** After a second refusal Android stops asking; then only App info can grant it. */
    private fun onAllowPhone() {
        val askable = CallMonitor.PERMISSIONS.any { shouldShowRequestPermissionRationale(it) } ||
            !phoneAsked
        if (askable) {
            phoneAsked = true
            requestPhone.launch(CallMonitor.PERMISSIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /**
     * Hand gestures or face gestures, never both: saved, then hands-free mode restarts in the new mode if it is on,
     * so the change takes effect at once. The mode is handed over, since the service hears of the new setting only
     * a moment later.
     */
    private fun setGestureMode(mode: GestureMode) {
        lifecycleScope.launch {
            settingsRepository.setGestureMode(mode)
            if (LiveStatus.state.value == LiveState.ON || LiveStatus.state.value == LiveState.PAUSED) {
                LiveStatus.stop()
                LiveStatus.start(mode)
            }
        }
    }

    private fun setShortcutApp(packageName: String?) {
        pickerApps = null
        lifecycleScope.launch { settingsRepository.setShortcutApp(packageName) }
    }

    /** Apps with a launcher icon, which the manifest's <queries> lets AirTouch see. AirTouch itself is left out. */
    private fun launchableApps(): List<LaunchableApp> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { LaunchableApp(it, appLabel(it)) }
            .sortedBy { it.label.lowercase() }

    private fun appLabel(pkg: String): String =
        runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)

    private fun onCameraAction() {
        if (status.camera == CameraPermission.Blocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) cameraRefusedForGood = false
        status = SetupStatus(
            camera = when {
                granted -> CameraPermission.Granted
                cameraRefusedForGood -> CameraPermission.Blocked
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> CameraPermission.RefusedOnce
                else -> CameraPermission.NotAsked
            },
            phoneAllowed = CallMonitor.hasPermission(this),
            accessibilityEnabled = isAccessibilityServiceEnabled(),
            modelBytes = bundledModelBytes(),
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val ours = ComponentName(this, AirTouchAccessibilityService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == ours }
    }

    // openFd only succeeds for uncompressed assets, so this also checks the noCompress rule.
    private fun bundledModelBytes(): Long =
        runCatching { assets.openFd(HandModel.ASSET).use { it.length } }.getOrDefault(-1L)
}
