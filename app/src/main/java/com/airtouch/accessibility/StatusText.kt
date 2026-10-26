package com.airtouch.accessibility

import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.face.FaceEngine
import com.airtouch.settings.GestureMode

/**
 * The status pill's texts in hands-free mode: what AirTouch sees in the current gesture mode, and whether a gesture
 * can be made now. Hands and face take turns (one mode at a time), so face mode never asks for the hand.
 */
object StatusText {

    fun of(mode: GestureMode, paused: Boolean, ringing: Boolean, hand: GestureEngine.State?, face: FaceEngine.Presence): String = when {
        paused -> "AirTouch · paused · ${resumeHint(mode)}"
        ringing -> "📞 ${callHint(mode)}"
        mode == GestureMode.HANDS -> "AirTouch · " + when (hand) {
            GestureEngine.State.ARMED -> "✋ ready"
            GestureEngine.State.TRACKING, GestureEngine.State.COOLDOWN -> "✋ hold still"
            else -> "show your hand"
        }
        else -> "AirTouch · " + when (face) {
            FaceEngine.Presence.READY -> "🙂 ready"
            FaceEngine.Presence.TURNED_AWAY -> "🙂 look at the phone"
            FaceEngine.Presence.NO_FACE -> "show your face"
        }
    }

    /** How to answer or reject a ringing call in this mode. */
    fun callHint(mode: GestureMode): String = when (mode) {
        GestureMode.HANDS -> "✋ hold to answer · ✊ to reject"
        GestureMode.FACE -> "Left eyebrow twice to answer · right eyebrow twice to reject"
    }

    /** No face gesture resumes AirTouch, so in face mode the notification's Resume button does. */
    fun resumeHint(mode: GestureMode): String = when (mode) {
        GestureMode.HANDS -> "👎 to resume"
        GestureMode.FACE -> "Resume in the notification"
    }
}
