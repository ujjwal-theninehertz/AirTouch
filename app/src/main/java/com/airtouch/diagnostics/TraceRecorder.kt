package com.airtouch.diagnostics

import android.os.Build
import android.os.SystemClock
import com.airtouch.gesture.face.FaceFrame
import com.airtouch.vision.HandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Saves landmark streams as JSON Lines for the gesture-engine replay tests (P3). One file per recording;
 * the first line describes it, then one line per detection. Files stay in the app's private storage:
 * `adb shell run-as com.airtouch ls files/traces`.
 */
class TraceRecorder(private val directory: File) : AutoCloseable {

    /** [frames] counts hand-detector results (hand mode); [faceFrames] face results, with a face in [framesWithFace]. */
    data class Recording(
        val label: String,
        val fileName: String,
        val seconds: Int,
        val frames: Int,
        val framesWithHand: Int,
        val faceFrames: Int = 0,
        val framesWithFace: Int = 0,
    )

    private val io = Executors.newSingleThreadExecutor { Thread(it, "airtouch-traces") }
    private var writer: BufferedWriter? = null // io thread only
    private var startedAtMs = 0L

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    fun start(label: String) {
        if (_recording.value != null) return
        val now = Date()
        val name = "${label}_${fileStamp.format(now)}.jsonl"
        val header = """{"format":1,"label":"$label","device":"${Build.MANUFACTURER} ${Build.MODEL}","android":${Build.VERSION.SDK_INT},""" +
            """"started":"${isoStamp.format(now)}","coordinates":"upright, mirrored like the preview, normalised 0..1"}"""
        startedAtMs = SystemClock.elapsedRealtime()
        _recording.value = Recording(label, name, seconds = 0, frames = 0, framesWithHand = 0)
        io.execute {
            directory.mkdirs()
            writer = File(directory, name).bufferedWriter().apply {
                write(header)
                newLine()
            }
        }
    }

    /** Detector thread. Cheap when not recording. */
    fun record(result: HandResult) {
        val current = _recording.value ?: return
        val line = result.toJsonLine()
        io.execute { writer?.apply { write(line); newLine() } }
        _recording.value = current.copy(
            seconds = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000).toInt(),
            frames = current.frames + 1,
            framesWithHand = current.framesWithHand + if (result.hands.isEmpty()) 0 else 1,
        )
    }

    /** Face thread (P4c): a face line, for the face replay tests. */
    fun recordFace(timestampMs: Long, face: FaceFrame?) {
        val current = _recording.value ?: return
        val values = face?.let {
            String.format(
                Locale.US,
                """{"browL":%.3f,"browR":%.3f,"eyeL":%.3f,"eyeR":%.3f,"pucker":%.3f,"jaw":%.3f,"yaw":%.1f,"pitch":%.1f}""",
                it.browUpLeft, it.browUpRight, it.eyeClosedLeft, it.eyeClosedRight, it.pucker, it.jawOpen, it.yaw, it.pitch,
            )
        } ?: "null"
        val line = """{"t":$timestampMs,"face":$values}"""
        io.execute { writer?.apply { write(line); newLine() } }
        _recording.value = current.copy(
            seconds = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000).toInt(),
            faceFrames = current.faceFrames + 1,
            framesWithFace = current.framesWithFace + if (face == null) 0 else 1,
        )
    }

    /** Stops recording and returns what was saved, or null if nothing was recording. */
    fun stop(): Recording? {
        val finished = _recording.value ?: return null
        _recording.value = null
        io.execute {
            writer?.close()
            writer = null
        }
        return finished
    }

    override fun close() {
        stop()
        io.shutdown()
    }

    private fun HandResult.toJsonLine(): String = buildString {
        append("""{"t":""").append(timestampMs).append(""","w":""").append(imageWidth).append(""","h":""").append(imageHeight)
        append(""","hands":[""")
        hands.forEachIndexed { i, hand ->
            if (i > 0) append(',')
            append("""{"right":""").append(hand.isRightHand)
            append(""","score":""").append(hand.score)
            append(""","primary":""").append(hand === primary)
            append(""","lm":[""")
            hand.landmarks.forEachIndexed { j, p ->
                if (j > 0) append(',')
                append(p.x).append(',').append(p.y).append(',').append(p.z)
            }
            append("]}")
        }
        append("]}")
    }

    private companion object {
        val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val isoStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
