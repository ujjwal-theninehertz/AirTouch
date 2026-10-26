package com.airtouch.gesture.face

import com.airtouch.gesture.model.GestureType
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the face lines of practice recordings (P4c) through the face engine. A face recording must produce its
 * gesture and no other face gesture; "face_none" (talking, eating, laughing) must produce none at all. Other
 * recordings only report what their faces did, since nobody keeps a still face while swiping.
 */
class FaceReplayTest {

    private val expected = mapOf(
        "face_up" to GestureType.FACE_UP,
        "face_down" to GestureType.FACE_DOWN,
        "face_left" to GestureType.FACE_LEFT,
        "face_right" to GestureType.FACE_RIGHT,
        "eyes_closed" to GestureType.EYES_CLOSED,
        "brow_left" to GestureType.BROW_LEFT,
        "brow_right" to GestureType.BROW_RIGHT,
        "brow_left_twice" to GestureType.BROW_LEFT_TWICE,
        "brow_right_twice" to GestureType.BROW_RIGHT_TWICE,
        "brows_twice" to GestureType.BROWS_TWICE,
        "brow_left_hold" to GestureType.BROW_LEFT_HOLD,
        "brow_right_hold" to GestureType.BROW_RIGHT_HOLD,
        "kiss" to GestureType.KISS,
    )

    private class Trace(val name: String, val label: String, val frames: List<FaceInput>)

    @Test
    fun everyFaceRecordingGivesItsGestureAndNothingElse() {
        val dir = javaClass.getResource("/traces")?.let { File(it.toURI()) }
        val traces = dir?.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.mapNotNull(::load).orEmpty()
        assumeTrue("no recordings with faces", traces.isNotEmpty())
        val failures = mutableListOf<String>()
        for (trace in traces) {
            val engine = FaceEngine()
            val outputs = trace.frames.map(engine::process)
            val fired = outputs.mapNotNull { it.event?.type }
            val rejected = outputs.mapNotNull { it.rejected }.groupingBy { it }.eachCount()
            val want = expected[trace.label]
            val ok = when {
                want != null -> fired.isNotEmpty() && fired.all { it == want }
                trace.label == "face_none" -> fired.isEmpty()
                else -> true
            }
            val verdict = if (want == null && trace.label != "face_none") "INFO" else if (ok) "PASS" else "FAIL"
            println("$verdict  ${trace.name.padEnd(36)} face fired=$fired rejected=$rejected")
            if (!ok) failures += trace.name
        }
        assertTrue("face recordings with wrong or missing gestures: $failures", failures.isEmpty())
    }

    /** The face lines of one recording, or null if it has none (recorded before P4c). */
    private fun load(file: File): Trace? {
        val lines = file.readLines()
        val label = Regex(""""label":"([a-z_]+)"""").find(lines.first())?.groupValues?.get(1) ?: return null
        val faces = lines.drop(1).filter { "\"face\":" in it }
        if (faces.isEmpty()) return null
        val frames = faces.map { line ->
            val t = Regex(""""t":(\d+)""").find(line)!!.groupValues[1].toLong()
            fun value(key: String) = Regex(""""$key":(-?[\d.]+)""").find(line)?.groupValues?.get(1)?.toFloat()
            val face = value("browL")?.let {
                FaceFrame(
                    browUpLeft = it,
                    browUpRight = value("browR")!!,
                    eyeClosedLeft = value("eyeL")!!,
                    eyeClosedRight = value("eyeR")!!,
                    pucker = value("pucker")!!,
                    jawOpen = value("jaw")!!,
                    yaw = value("yaw")!!,
                    pitch = value("pitch")!!,
                )
            }
            FaceInput(t, face)
        }
        return Trace(file.name, label, frames)
    }
}
