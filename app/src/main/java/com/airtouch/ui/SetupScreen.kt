package com.airtouch.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airtouch.accessibility.LiveState
import com.airtouch.settings.GestureMode
import java.util.Locale

enum class CameraPermission { NotAsked, RefusedOnce, Blocked, Granted }

data class SetupStatus(
    val camera: CameraPermission = CameraPermission.NotAsked,
    val phoneAllowed: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val modelBytes: Long = -1,
)

@Composable
fun SetupScreen(
    status: SetupStatus,
    live: LiveState,
    shortcutApp: String?,
    onChooseShortcutApp: () -> Unit,
    gestureMode: GestureMode,
    onSetGestureMode: (GestureMode) -> Unit,
    onCameraAction: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenPractice: () -> Unit,
    onOpenTapTest: () -> Unit,
    onAllowPhone: () -> Unit,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
) {
    val liveRunning = live == LiveState.ON || live == LiveState.PAUSED
    val cameraReady = status.camera == CameraPermission.Granted
    val hands = gestureMode == GestureMode.HANDS
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AirTouch", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Setup check",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CheckRow(
                title = "Camera permission",
                ok = status.camera == CameraPermission.Granted,
                detail = when (status.camera) {
                    CameraPermission.Granted -> "Granted"
                    CameraPermission.NotAsked -> "Needed to see your hand"
                    CameraPermission.RefusedOnce ->
                        "AirTouch only uses the camera to see your hand. Frames stay on this phone and are never saved."
                    CameraPermission.Blocked -> "Blocked. Open App info → Permissions → Camera and choose Allow."
                },
                action = when (status.camera) {
                    CameraPermission.Granted -> null
                    CameraPermission.Blocked -> "Open App info"
                    else -> "Allow"
                },
                onAction = onCameraAction,
            )
            CheckRow(
                title = "Accessibility service",
                ok = status.accessibilityEnabled,
                detail = if (status.accessibilityEnabled) "On" else "Needed to act inside other apps",
                action = "Open settings".takeUnless { status.accessibilityEnabled },
                onAction = onOpenAccessibility,
            )
            CheckRow(
                title = "Hand model bundled",
                ok = status.modelBytes > 0,
                detail = if (status.modelBytes > 0) {
                    String.format(Locale.US, "%.1f MB, stored uncompressed", status.modelBytes / 1_000_000f)
                } else {
                    "Missing or compressed in the APK"
                },
            )
            CheckRow(
                title = "Hands-free mode",
                ok = liveRunning,
                detail = when {
                    live == LiveState.UNAVAILABLE -> "Turn on the accessibility service first"
                    !cameraReady -> "Allow the camera first"
                    live == LiveState.ON && hands -> "On, by hand. Flick or swipe up: next · down: previous · ☝ point: cursor, then pinch to tap, hold, drag or pull to zoom · fist: play/pause, raise or lower for volume · 👍 Back · ✌ Home · 3 fingers: recent apps · 🤘 shortcuts · 🤙 your app · 👎 pause AirTouch"
                    live == LiveState.ON -> "On, by face. Tip your head up and back: next · down and back: previous · turn left or right and back: like a flick · eyes closed 1 s: Home · left eyebrow: Back · right eyebrow: play/pause · kiss: like"
                    live == LiveState.PAUSED && hands -> "Paused. Hold a thumbs-down for 1 second to resume."
                    live == LiveState.PAUSED -> "Paused. Tap Resume in AirTouch's notification."
                    hands -> "Off. Start it, then open Instagram Reels and swipe your hand up."
                    else -> "Off. Start it, then open Instagram Reels and tip your head up and back."
                },
                action = when {
                    liveRunning -> "Stop"
                    live == LiveState.OFF && cameraReady -> "Start"
                    else -> null
                },
                onAction = if (liveRunning) onStopLive else onStartLive,
            )
            // One at a time: turning one on turns the other off.
            CheckRow(
                title = "✋ Hand gestures",
                ok = hands,
                optional = true,
                detail = if (hands) {
                    "On. Face gestures are off; turning them on turns these off."
                } else {
                    "Off while face gestures are on. Turn on to use your hand; face gestures then turn off."
                },
                action = "Turn on".takeUnless { hands },
                onAction = { onSetGestureMode(GestureMode.HANDS) },
            )
            CheckRow(
                title = "🙂 Face gestures",
                ok = !hands,
                optional = true,
                detail = if (!hands) {
                    "On: nod or turn like a flick · eyes closed 1 s: Home · left eyebrow: Back (held: volume down) · right eyebrow: play/pause (held: volume up) · both eyebrows twice: screenshot · kiss: like. Hand gestures are off."
                } else {
                    "Off. Turn on to control the phone with your face; hand gestures then turn off."
                },
                action = "Turn on".takeIf { hands },
                onAction = { onSetGestureMode(GestureMode.FACE) },
            )
            CheckRow(
                title = "📞 Calls by gesture",
                ok = status.phoneAllowed,
                detail = when {
                    !status.phoneAllowed -> "Allow Phone access to answer or reject calls by gesture. The caller's number is never read."
                    hands -> "While a call rings: ✋ open hand, held 1.5 s, answers on speaker · ✊ fist rejects"
                    else -> "While a call rings: left eyebrow twice answers on speaker · right eyebrow twice rejects"
                },
                action = "Allow".takeUnless { status.phoneAllowed },
                onAction = onAllowPhone,
            )
            CheckRow(
                title = "Cursor tap test",
                ok = live == LiveState.ON && hands,
                detail = when {
                    !hands -> "Uses the hand cursor: turn on hand gestures first"
                    live == LiveState.ON -> "Point ☝ at nine targets and pinch: shows how close each tap lands, in this rotation"
                    else -> "Start hands-free mode first: the test uses the cursor"
                },
                action = "Open".takeIf { live == LiveState.ON && hands },
                onAction = onOpenTapTest,
            )
            CheckRow(
                title = "🤙 Shortcut app",
                ok = shortcutApp != null,
                detail = shortcutApp?.let { "🤙 opens $it" } ?: "Choose the app that 🤙 (thumb and little finger out) opens",
                action = if (shortcutApp == null) "Choose" else "Change",
                onAction = onChooseShortcutApp,
            )
            CheckRow(
                title = "Practice mode",
                ok = cameraReady && !liveRunning,
                detail = when {
                    !cameraReady -> "Allow the camera first"
                    liveRunning -> "Stop hands-free mode to practise: both need the camera"
                    hands -> "See which hand gestures AirTouch recognises. Nothing is sent to other apps."
                    else -> "See which face gestures AirTouch recognises. Nothing is sent to other apps."
                },
                action = "Open".takeIf { cameraReady && !liveRunning },
                onAction = onOpenPractice,
            )
            Text(
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A setup row. [optional] rows are choices, not requirements: off shows a plain circle instead of a warning. */
@Composable
private fun CheckRow(title: String, ok: Boolean, detail: String, action: String? = null, onAction: () -> Unit = {}, optional: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    ok -> "✓"
                    optional -> "○"
                    else -> "!"
                },
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    ok -> MaterialTheme.colorScheme.primary
                    optional -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
