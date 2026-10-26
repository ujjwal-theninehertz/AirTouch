package com.airtouch.gesture.model

enum class GestureType {
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,

    // Finger flicks: the fingers move fast while the wrist stays still. The fast half of the motion sets the
    // direction and the slow way back is ignored. Each does what the matching swipe does.
    FLICK_UP,
    FLICK_DOWN,
    FLICK_LEFT,
    FLICK_RIGHT,
    PINCH,

    // Shapes held for [com.airtouch.gesture.GestureConfig.poseHoldMs].

    /** By default plays or pauses the video or music. */
    FIST,

    /** Fingers curled, thumb pointing down. Pauses or resumes AirTouch; the only gesture that works while paused. */
    THUMBS_DOWN,

    /** Index and middle finger up. By default: Home. */
    V_SIGN,

    /** Index, middle and ring finger up. By default: Recents. */
    THREE_FINGERS,

    /** Fingers curled, thumb pointing up. By default: Back. */
    THUMBS_UP,

    /** Index and little finger up (🤘). By default: opens the shortcut menu. */
    ROCK,

    /** Little finger up, thumb out (🤙). By default: opens the app chosen in AirTouch. */
    CALL,

    /** Two quick pinches while the cursor is hidden. By default: double-taps the centre, which likes a Reel. */
    DOUBLE_PINCH,

    /** One step of a fist moved up or down: about a third of a hand's length. By default: volume up or down. */
    FIST_RAISE,
    FIST_LOWER,

    /** An open hand held still while a call rings, opened after the ringing began: answers the call. */
    OPEN_PALM,

    // Face gestures (P4c). Directions are the user's own; the head moves a little and comes back.
    FACE_UP,
    FACE_DOWN,
    FACE_LEFT,
    FACE_RIGHT,

    /** Both eyes closed for a second. By default: Home. */
    EYES_CLOSED,

    /** One eyebrow raised and lowered. By default: left Back, right play/pause. */
    BROW_LEFT,
    BROW_RIGHT,

    /** One eyebrow raised twice: answers (left) or rejects (right) a ringing call. */
    BROW_LEFT_TWICE,
    BROW_RIGHT_TWICE,

    /** Both eyebrows raised twice. By default: screenshot. */
    BROWS_TWICE,

    /** One step of an eyebrow held up. By default: left volume down, right volume up. */
    BROW_LEFT_HOLD,
    BROW_RIGHT_HOLD,

    /** Lips puckered as for a kiss, held. By default: double-tap the centre, which likes a Reel. */
    KISS,
    ;

    val isSwipe: Boolean get() = this == SWIPE_UP || this == SWIPE_DOWN || this == SWIPE_LEFT || this == SWIPE_RIGHT

    fun opposite(): GestureType? = when (this) {
        SWIPE_UP -> SWIPE_DOWN
        SWIPE_DOWN -> SWIPE_UP
        SWIPE_LEFT -> SWIPE_RIGHT
        SWIPE_RIGHT -> SWIPE_LEFT
        else -> null
    }
}

/**
 * One accepted gesture. [anchor] is where it happened in the user's view, normalised 0..1: the palm
 * centre where a swipe started, or the index fingertip just before a pinch closed (P4 taps there).
 */
data class GestureEvent(
    val type: GestureType,
    val timestampMs: Long,
    val confidence: Float,
    val anchorX: Float,
    val anchorY: Float,
)

/** What the engine receives each frame: the followed hand (null when none) and the frame's width ÷ height. */
data class HandInput(
    val timestampMs: Long,
    val hand: HandFrame?,
    val imageAspect: Float,
)
