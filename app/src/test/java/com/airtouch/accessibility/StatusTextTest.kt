package com.airtouch.accessibility

import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.face.FaceEngine
import com.airtouch.settings.GestureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {

    private val handStates = listOf(null) + GestureEngine.State.entries
    private val faceStates = FaceEngine.Presence.entries

    private fun text(
        mode: GestureMode,
        hand: GestureEngine.State? = null,
        face: FaceEngine.Presence = FaceEngine.Presence.NO_FACE,
        paused: Boolean = false,
        ringing: Boolean = false,
    ) = StatusText.of(mode, paused, ringing, hand, face)

    @Test
    fun handModeSaysWhatTheHandIsDoing() {
        assertEquals("AirTouch · ✋ ready", text(GestureMode.HANDS, hand = GestureEngine.State.ARMED))
        assertEquals("AirTouch · ✋ hold still", text(GestureMode.HANDS, hand = GestureEngine.State.TRACKING))
        assertEquals("AirTouch · ✋ hold still", text(GestureMode.HANDS, hand = GestureEngine.State.COOLDOWN))
        assertEquals("AirTouch · show your hand", text(GestureMode.HANDS, hand = GestureEngine.State.IDLE))
        assertEquals("AirTouch · show your hand", text(GestureMode.HANDS))
    }

    @Test
    fun faceModeSaysWhatTheFaceIsDoing() {
        assertEquals("AirTouch · 🙂 ready", text(GestureMode.FACE, face = FaceEngine.Presence.READY))
        assertEquals("AirTouch · 🙂 look at the phone", text(GestureMode.FACE, face = FaceEngine.Presence.TURNED_AWAY))
        assertEquals("AirTouch · show your face", text(GestureMode.FACE, face = FaceEngine.Presence.NO_FACE))
    }

    @Test
    fun eachModeIgnoresTheOther() {
        // Whatever the other engine last said, in every situation: paused, ringing or not.
        for (paused in listOf(false, true)) for (ringing in listOf(false, true)) {
            for (hand in handStates) for (face in faceStates) {
                val faceText = text(GestureMode.FACE, hand, face, paused, ringing)
                assertFalse("face mode mentions the hand: $faceText", "✋" in faceText || "✊" in faceText || "👎" in faceText || "hand" in faceText)
                assertEquals(faceText, text(GestureMode.FACE, null, face, paused, ringing))
                val handText = text(GestureMode.HANDS, hand, face, paused, ringing)
                assertFalse("hand mode mentions the face: $handText", "🙂" in handText || "face" in handText || "eyebrow" in handText)
                assertEquals(handText, text(GestureMode.HANDS, hand, FaceEngine.Presence.NO_FACE, paused, ringing))
            }
        }
    }

    @Test
    fun pausedSaysHowToResumeInThisMode() {
        assertEquals("AirTouch · paused · 👎 to resume", text(GestureMode.HANDS, hand = GestureEngine.State.ARMED, paused = true))
        assertEquals("AirTouch · paused · Resume in the notification", text(GestureMode.FACE, face = FaceEngine.Presence.READY, paused = true))
    }

    @Test
    fun aRingingCallSaysHowToAnswerInThisMode() {
        assertEquals("📞 ✋ hold to answer · ✊ to reject", text(GestureMode.HANDS, ringing = true))
        val face = text(GestureMode.FACE, face = FaceEngine.Presence.READY, ringing = true)
        assertTrue(face, face.startsWith("📞") && "Left eyebrow twice to answer" in face && "right eyebrow twice to reject" in face)
    }
}
