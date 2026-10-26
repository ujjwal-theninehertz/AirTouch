package com.airtouch.gesture

/**
 * Every threshold the engine uses. Distances are in hand scales (wrist to middle-finger knuckle), so the
 * same values work near and far from the camera; speeds are hand scales per second.
 */
data class GestureConfig(
    // Gates
    val minHandScale: Float = 0.06f,
    val minHandScore: Float = 0.5f,

    // Arming: an open palm held this steady for this long before any gesture is accepted.
    // A resting hand on the Note 8 Pro moves 0.2–0.7 hand scales per second (recorded 24 Oct 2026).
    val armStillSpeed: Float = 1.2f,
    val armHoldMs: Long = 300,

    /** Mid-rhythm (a gesture fired within [engagedWindowMs]) re-arming needs only this much stillness. */
    val rearmHoldMs: Long = 150,
    val engagedWindowMs: Long = 4_000,
    val lostAfterMs: Long = 500,

    // Swipes. Recorded flicks covered 1.35–1.75 scales in 0.5–1 s; slow 3–4 s drifts are not swipes.
    // Speeds are measured over [velocityWindowMs] so one noisy frame neither starts nor ends a swipe.
    val velocityWindowMs: Long = 150,
    val swipeStartSpeed: Float = 1.5f,
    val swipeEndSpeed: Float = 0.8f,
    val swipeMinDistance: Float = 1.0f,
    val swipeMinMs: Long = 80,
    val swipeMaxMs: Long = 1_500,
    val swipeMinAverageSpeed: Float = 0.8f,

    /** Once a motion has covered this many scales, moving back the other way ends it. */
    val reversalAfter: Float = 0.3f,
    val swipeDirectionRatio: Float = 2.0f,
    val swipeMinOpenShare: Float = 0.5f,
    val swipeMaxGapMs: Long = 150,

    // Poses
    val fingerExtendedRatio: Float = 1.15f,
    val fingerCurledRatio: Float = 1.0f,
    val pinchEnter: Float = 0.30f,
    val pinchExit: Float = 0.45f,

    /**
     * A pinch taps only once held this long. A finger flick also brings thumb and index together for up to
     * 0.7 s (recorded 24 Oct 2026), and recorded pinches were held 1.2–2.7 s.
     */
    val pinchHoldMs: Long = 800,

    // Finger flicks: the fingertips, relative to the wrist and in hand scales, leave their resting place by at
    // least [flickMinExcursion] and come back while the wrist moves less than [flickMaxPalmMove]. The fast
    // stroke is the flick. Recorded 24 Oct 2026: the fast stroke reached 6–22 scales per second, and was
    // 1.9–6 times faster than the opposite one.
    val flickRestLift: Float = 0.45f,
    val flickNear: Float = 0.3f,
    val flickStart: Float = 0.5f,
    val flickEnd: Float = 0.4f,
    // Small recorded flicks moved 0.7–0.8; the speed rules below keep ordinary finger movement out.
    val flickMinExcursion: Float = 0.65f,
    // Recorded flicks stay away 0.8–1.1 s (fast fold, slow way back). A shape held longer fires at
    // [poseHoldMs] and its cooldown blocks the flick, so only shapes held past this are dropped here.
    val flickMaxAwayMs: Long = 1_200,
    val flickMaxCycleMs: Long = 1_800,

    /** A flick is judged once the fingertips are back within this share of their farthest point. */
    val flickJudgeShare: Float = 0.5f,

    /** Live flicks paused at their far point for up to 640 ms; a fist held there (850 ms and more) is a shape, not a flick. */
    val flickMaxHeldMs: Long = 750,
    val flickMinSpeed: Float = 5.0f,

    /**
     * The fast stroke must beat the opposite one by this much. Live flicks missed at 1.52–1.58 (24 Oct 2026,
     * second test); a fold and opening at one pace (ordinary movement) measured 1.1.
     */
    val flickSpeedRatio: Float = 1.4f,

    /**
     * Repeating the same flick within [flickRhythmMs], the fast stroke need only beat the other by this much: flicking
     * twice a second, the hand snaps both ways (third live test, 24 Oct 2026: 1.34 and 1.36 on intended repeats).
     * Changing direction still needs [flickSpeedRatio]: a recorded stray stroke opposite to the flicks before it
     * measured 1.37, and the stroke made switching from flicks down to flicks up 1.22.
     */
    val flickRhythmSpeedRatio: Float = 1.25f,
    val flickRhythmMs: Long = 2_000,
    // Live flicks moved the wrist up to 0.61; swipes move it a hand's length (1.0) or more.
    val flickMaxPalmMove: Float = 0.8f,

    /** Sideways if the sideways part is at least this share of the vertical part (left flicks ≈ 1.0, down flicks ≤ 0.45). */
    val flickSidewaysShare: Float = 0.6f,
    val thumbExtendedRatio: Float = 1.3f,
    val thumbAboveMargin: Float = 0.1f,
    val poseVotes: Int = 4,
    val poseWindow: Int = 5,

    /** Fist, V, three fingers and thumbs-up act only after being held this long. */
    val poseHoldMs: Long = 1_000,

    /**
     * While a call rings, an open hand held still this long answers it. Longer than other shapes, and only for a
     * hand opened after the ringing began: an open hand is also the resting pose between gestures.
     */
    val callPalmHoldMs: Long = 1_500,

    /** …and held this still (hand scales from where it settled): a slow wave is within arming's stillness, not this. */
    val callPalmStillScales: Float = 0.15f,
    val pinchAnchorLookbackMs: Long = 120,

    // Cursor: pointing (index finger alone) for [cursorShowMs] shows it at the centre of the screen; it follows the
    // index knuckle while pointing or pinching and fades [cursorHideMs] after that stops. Recorded pinches (24 Oct
    // 2026) all began by pointing for about 1.4 s, so a pinch acts at the cursor whenever it is shown. Recorded
    // flicks down fold the index last and look like pointing for up to 0.6 s, hence the wait (plus voting, 0.7 s).
    val cursorShowMs: Long = 500,
    val cursorHideMs: Long = 2_000,

    /** Hand scales the knuckle moves, at an even pace, to cross the screen's height; the width takes the same distance per pixel. */
    val cursorRangeScales: Float = 2.0f,

    // Pointer acceleration, as for a mouse: slow movement for precision, fast for reach.
    val cursorSlowSpeed: Float = 0.3f,
    val cursorFastSpeed: Float = 2.5f,
    val cursorSlowGain: Float = 0.6f,
    val cursorFastGain: Float = 1.6f,

    // One-euro smoothing of the knuckle (frame units): steady at rest, little lag when moving.
    val cursorMinCutoffHz: Float = 1.0f,
    val cursorBeta: Float = 20f,

    // Pinch as a finger on the screen. Contact starts and ends after this many frames in a row, so one noisy
    // frame neither taps nor lifts a dragging finger.
    val pinchContactFrames: Int = 2,

    /** Closing the fingers drags the knuckle a little; the finger goes down where the cursor was this long before. */
    val pinchLookbackMs: Long = 150,

    /**
     * Only a pinch made straight from pointing acts at the cursor. One that follows an open hand or a fist is the
     * fold of a flick, or a pinch meant for the centre (recorded pinches went from pointing to pinching in 0.1–0.2 s).
     */
    val pointToPinchMs: Long = 400,

    /**
     * At the cursor: a pinch released sooner than this is a tap; held this long and still, it becomes a long-press.
     * Long, because pinching and holding is how taps were made before the cursor: the first live test (24 Oct 2026)
     * held pinches 0.6–1.3 s on buttons that were meant to be tapped.
     */
    val longPressMs: Long = 1_500,

    /** At the cursor: a second pinch this soon after a tap makes it a double-tap, so taps wait this long. */
    val doubleTapWindowMs: Long = 300,

    /**
     * At the cursor: moving the (smoothed) knuckle this far, in hand scales, while pinching starts a drag. Recorded
     * pinches shifted it 0.04–0.06 while closing and drifted up to 0.1 while held (24 Oct 2026).
     */
    val dragSlopScales: Float = 0.12f,

    /**
     * Without the cursor, a drag needs an open-hand pinch held steady for [centreDragMinPinchMs], then a clear move:
     * finger flicks pinch for a moment too, often while the hand moves (24 Oct 2026 recordings).
     */
    val centreDragStartScales: Float = 0.35f,
    val centreDragMinPinchMs: Long = 300,
    val centreSettleScales: Float = 0.15f,

    /** Pulling the pinched hand this much closer (or pushing it away) starts zooming; landmarks put hand size noise at ±5 %. */
    val zoomStartRatio: Float = 1.15f,
    val zoomExponent: Float = 2.0f,
    val zoomMaxFactor: Float = 3.5f,

    /** A held pinch only becomes a long-press if the hand also stays this steady in size (no zoom under way). */
    val longPressMaxSizeChange: Float = 0.08f,

    /** Without the cursor: two pinches, each at most this long, at most [doublePinchGapMs] apart. */
    val doublePinchMaxMs: Long = 450,
    val doublePinchGapMs: Long = 500,

    /** A hand lost for this long lifts any virtual finger where it last was. */
    val touchLostMs: Long = 300,

    // Volume knob: a fist held [knobMinFistMs], then moved up or down; each step is [knobStepScales] hand scales.
    val knobMinFistMs: Long = 400,
    val knobStepScales: Float = 0.3f,
    val knobMinStepMs: Long = 120,

    /** A fist that moved this far (hand scales) is a knob, so it no longer plays or pauses when held. */
    val knobConsumeScales: Float = 0.2f,

    // After a gesture
    // Short: re-arming needs a still hand anyway, so one motion cannot fire twice. Return strokes have their own block below.
    val cooldownMs: Long = 300,

    /** An opposite swipe starting this soon after a swipe ends is its return stroke (recorded pauses reached 1.5 s). */
    val oppositeSuppressMs: Long = 2_000,
)
