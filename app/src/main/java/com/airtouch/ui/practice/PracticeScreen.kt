package com.airtouch.ui.practice

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.airtouch.camera.CameraStatus
import com.airtouch.camera.FrameStats
import com.airtouch.diagnostics.TraceRecorder
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.face.FaceFrame
import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.HandFrame
import com.airtouch.settings.GestureMode
import com.airtouch.vision.DetectionStats
import com.airtouch.vision.HandResult
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

private val Scrim = Color(0xB3000000)
private val PrimaryHand = Color(0xFF4FCDBB)
private val OtherHand = Color(0xFFB0B8C0)
private val RecordRed = Color(0xFFF2554A)
private val Recognised = Color(0xE60B7C70)
private const val BANNER_MS = 1_200L

/** Hand trace labels: file name prefix to button text. "none" records ordinary hand movement as negative examples. */
private val HandTraceLabels = listOf(
    "swipe_up" to "Swipe ↑",
    "swipe_down" to "Swipe ↓",
    "swipe_left" to "Swipe ←",
    "swipe_right" to "Swipe →",
    "pinch" to "Pinch",
    "fist" to "Fist",
    "v_sign" to "✌ V",
    "thumbs_up" to "👍 Thumb",
    "three_fingers" to "3 fingers",
    "thumbs_down" to "👎 Down",
    "flick_down" to "Flick ↓",
    "flick_left" to "Flick ←",
    "flick_right" to "Flick →",
    "point" to "☝ Point",
    "pinch_drag" to "Pinch drag",
    "pinch_zoom" to "Pinch zoom",
    "double_pinch" to "Double pinch",
    "fist_volume" to "Fist volume",
    "rock" to "🤘 Rock",
    "call" to "🤙 Call",
    "none" to "No gesture",
)

/** Face trace labels (P4c). "face_none" records talking, eating and laughing as negative examples. */
private val FaceTraceLabels = listOf(
    "face_up" to "Face ⬆",
    "face_down" to "Face ⬇",
    "face_left" to "Face ⬅",
    "face_right" to "Face ➡",
    "eyes_closed" to "Eyes closed",
    "brow_left" to "Left brow",
    "brow_right" to "Right brow",
    "brow_left_twice" to "Left brow ×2",
    "brow_right_twice" to "Right brow ×2",
    "brows_twice" to "Both brows ×2",
    "brow_left_hold" to "Left brow hold",
    "brow_right_hold" to "Right brow hold",
    "kiss" to "Kiss",
    "face_none" to "Face: none",
)

@Composable
internal fun PracticeScreen(
    mode: GestureMode,
    previewView: PreviewView,
    status: CameraStatus,
    cameraStats: FrameStats.Snapshot?,
    detectorStats: DetectionStats?,
    result: HandResult?,
    decision: GestureEngine.Output?,
    lastGesture: GestureEvent?,
    hint: Hint?,
    touchNote: Hint?,
    face: FaceFrame?,
    faceGesture: GestureEvent?,
    thumbnail: ImageBitmap?,
    recording: TraceRecorder.Recording?,
    onRecord: (String) -> Unit,
    onStopRecording: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        HandOverlay(result, Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .background(Scrim, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text(status.label(), color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(
                statsText(cameraStats, detectorStats, result),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            if (decision != null) {
                Text(
                    "gestures ${decision.state.label()}" + (decision.pose?.let { " · ${it.name.lowercase()}" } ?: "") +
                        (decision.pointer?.let { " · cursor %.2f, %.2f".format(it.x, it.y) } ?: ""),
                    color = if (decision.state == GestureEngine.State.ARMED) PrimaryHand else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (mode == GestureMode.FACE) {
                Text(
                    face?.let {
                        String.format(
                            Locale.US, "face brows L %.2f R %.2f · eyes %.2f/%.2f · kiss %.2f · jaw %.2f\nhead yaw %.0f° pitch %.0f°",
                            it.browUpLeft, it.browUpRight, it.eyeClosedLeft, it.eyeClosedRight, it.pucker, it.jawOpen, it.yaw, it.pitch,
                        )
                    } ?: "face not in view",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        GestureBanner(lastGesture, Modifier.align(Alignment.Center))
        GestureBanner(faceGesture, Modifier.align(Alignment.Center).padding(top = 220.dp))
        HintBanner(touchNote, Modifier.align(Alignment.Center).padding(bottom = 120.dp), Recognised)
        HintBanner(hint, Modifier.align(Alignment.Center).padding(top = 120.dp))

        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Frame passed to the detector, turned upright and mirrored",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .width(96.dp)
                    .aspectRatio(thumbnail.width.toFloat() / thumbnail.height)
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp)),
            )
        }

        RecorderBar(
            labels = if (mode == GestureMode.HANDS) HandTraceLabels else FaceTraceLabels,
            recording = recording,
            onRecord = onRecord,
            onStop = onStopRecording,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp)
                .background(Scrim, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Shown for the few seconds the model takes to load. */
@Composable
internal fun PracticeStarting() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Starting the camera and the gesture model…", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** Draws every detected hand over the preview; the followed hand in teal. Matches PreviewView's FILL_CENTER crop. */
@Composable
private fun HandOverlay(result: HandResult?, modifier: Modifier) {
    Canvas(modifier) {
        val r = result ?: return@Canvas
        if (r.imageWidth == 0 || r.imageHeight == 0) return@Canvas
        val scale = max(size.width / r.imageWidth, size.height / r.imageHeight)
        val dx = (size.width - r.imageWidth * scale) / 2
        val dy = (size.height - r.imageHeight * scale) / 2
        for (hand in r.hands) {
            val color = if (hand === r.primary) PrimaryHand else OtherHand
            val points = hand.landmarks.map { Offset(it.x * r.imageWidth * scale + dx, it.y * r.imageHeight * scale + dy) }
            for ((a, b) in HandFrame.CONNECTIONS) {
                drawLine(color, points[a], points[b], strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
            for (p in points) drawCircle(Color.White, radius = 3.5.dp.toPx(), center = p)
        }
    }
}

@Composable
private fun RecorderBar(
    labels: List<Pair<String, String>>,
    recording: TraceRecorder.Recording?,
    onRecord: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    if (recording == null) {
        Row(
            modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Record", color = Color.White, style = MaterialTheme.typography.labelLarge)
            for ((label, text) in labels) {
                AssistChip(
                    onClick = { onRecord(label) },
                    label = { Text(text) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
                )
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (recording.faceFrames > 0) {
                    "● ${recording.label} · ${recording.seconds} s · ${recording.faceFrames} face frames (face in ${recording.framesWithFace})"
                } else {
                    "● ${recording.label} · ${recording.seconds} s · ${recording.frames} frames (hand in ${recording.framesWithHand})"
                },
                color = RecordRed,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

/** Flashes each recognised gesture for a moment. In practice mode this is all that happens: nothing reaches other apps. */
@Composable
private fun GestureBanner(event: GestureEvent?, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        visible = true
        delay(BANNER_MS)
        visible = false
    }
    if (visible && event != null) {
        Text(
            event.type.label(),
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            modifier = modifier
                .background(Recognised, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/** Why the last deliberate-looking swipe was refused. [id] changes on every refusal so repeats show again. */
internal data class Hint(val text: String, val id: Long)

@Composable
private fun HintBanner(hint: Hint?, modifier: Modifier, background: Color = Scrim) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint == null) return@LaunchedEffect
        visible = true
        delay(BANNER_MS)
        visible = false
    }
    if (visible && hint != null) {
        Text(
            hint.text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier
                .background(background, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun GestureType.label(): String = when (this) {
    GestureType.SWIPE_UP -> "Swipe ↑"
    GestureType.SWIPE_DOWN -> "Swipe ↓"
    GestureType.SWIPE_LEFT -> "Swipe ←"
    GestureType.SWIPE_RIGHT -> "Swipe →"
    GestureType.FLICK_UP -> "Flick ↑"
    GestureType.FLICK_DOWN -> "Flick ↓"
    GestureType.FLICK_LEFT -> "Flick ←"
    GestureType.FLICK_RIGHT -> "Flick →"
    GestureType.PINCH -> "Pinch"
    GestureType.FIST -> "Fist (play / pause)"
    GestureType.THUMBS_DOWN -> "👎 Thumbs down"
    GestureType.V_SIGN -> "✌ V sign"
    GestureType.THUMBS_UP -> "👍 Thumbs up"
    GestureType.THREE_FINGERS -> "Three fingers"
    GestureType.ROCK -> "🤘 Shortcuts"
    GestureType.CALL -> "🤙 Your app"
    GestureType.DOUBLE_PINCH -> "Double pinch (❤ like)"
    GestureType.FIST_RAISE -> "🔊 Volume up"
    GestureType.FIST_LOWER -> "🔉 Volume down"
    GestureType.OPEN_PALM -> "✋ Answer (call ringing)"
    GestureType.FACE_UP -> "🙂⬆ Face up"
    GestureType.FACE_DOWN -> "🙂⬇ Face down"
    GestureType.FACE_LEFT -> "🙂⬅ Face left"
    GestureType.FACE_RIGHT -> "🙂➡ Face right"
    GestureType.EYES_CLOSED -> "😌 Eyes closed (Home)"
    GestureType.BROW_LEFT -> "🤨 Left eyebrow (Back)"
    GestureType.BROW_RIGHT -> "🤨 Right eyebrow (play / pause)"
    GestureType.BROW_LEFT_TWICE -> "Left eyebrow ×2 (answer call)"
    GestureType.BROW_RIGHT_TWICE -> "Right eyebrow ×2 (reject call)"
    GestureType.BROWS_TWICE -> "Both eyebrows ×2 (screenshot)"
    GestureType.BROW_LEFT_HOLD -> "🔉 Left eyebrow held"
    GestureType.BROW_RIGHT_HOLD -> "🔊 Right eyebrow held"
    GestureType.KISS -> "😗 Kiss (like)"
}

private fun GestureEngine.State.label(): String = when (this) {
    GestureEngine.State.IDLE -> "waiting for a hand"
    GestureEngine.State.TRACKING -> "hold your hand still to arm"
    GestureEngine.State.ARMED -> "armed"
    GestureEngine.State.COOLDOWN -> "cooling down"
}

private fun CameraStatus.label(): String = when (this) {
    CameraStatus.Idle -> "Camera off"
    CameraStatus.Opening -> "Opening the front camera…"
    CameraStatus.Streaming -> "Practice mode · front camera on"
    is CameraStatus.Problem -> reason.message
}

private fun statsText(camera: FrameStats.Snapshot?, detector: DetectionStats?, result: HandResult?): String = buildString {
    if (camera != null) {
        append(String.format(Locale.US, "camera %.0f → %.1f fps · rotation %d°", camera.inputFps, camera.processedFps, camera.rotationDegrees))
    }
    if (detector != null) {
        append(
            String.format(
                Locale.US,
                "\nhands %s %.1f fps · %d ms (p95 %d)\nhand in view %d%%",
                detector.delegate, detector.resultsFps, detector.inferenceP50Ms, detector.inferenceP95Ms, detector.handPercent,
            ),
        )
    }
    val primary = result?.primary
    if (primary != null) {
        append(String.format(Locale.US, "\nfollowing %s hand (%.2f) · %d in view", if (primary.isRightHand) "right" else "left", primary.score, result.hands.size))
    }
}
