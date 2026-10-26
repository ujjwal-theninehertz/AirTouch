package com.airtouch.gesture.interaction

import com.airtouch.gesture.GestureConfig
import com.airtouch.gesture.features.HandFeatures
import com.airtouch.gesture.features.Pose
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.Pointer
import com.airtouch.gesture.model.Touch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/**
 * Continuous control (P4b): the cursor, the pinch as a finger on the screen, and the fist as a volume knob.
 *
 * Pointing shows the cursor and moves it. A pinch then acts where the cursor is: released, it taps (a double-tap
 * if a second follows at once), held until the ring fills it long-presses, moving it drags, and pulling the pinched hand closer
 * or pushing it away zooms. With no cursor on screen a pinch acts at the centre and is stricter, because finger
 * flicks pinch for a moment too: it taps only when held, double-taps on two quick open-hand pinches, and drags
 * only on a clear move. A fist held still and then moved up or down turns the volume up or down.
 *
 * Every finger that goes down comes up again: on release, when the hand is lost, or when AirTouch pauses.
 * Not thread-safe; [com.airtouch.gesture.GestureEngine] calls it for every frame.
 */
internal class HandInteractions(private val config: GestureConfig) {

    /**
     * What one frame produced. [gesture] is a discrete gesture for the engine to fire: the held centre pinch, a
     * double pinch, or one volume step. While [busy], swipes, flicks and held shapes must not fire.
     */
    class Result(val pointer: Pointer?, val touch: Touch?, val gesture: GestureType?, val busy: Boolean)

    /** Screen width ÷ height, so the cursor crosses as many pixels per hand movement across as up and down. Set from any thread. */
    @Volatile
    var screenAspect = DEFAULT_SCREEN_ASPECT

    /** The fist has moved like a knob, so holding it must not also play or pause. */
    var fistUsed = false
        private set

    private enum class Owner { CURSOR, CENTRE }

    private enum class Phase { PENDING, DRAG, LONG_PRESS, ZOOM, DONE }

    private class Pinch(
        val owner: Owner,
        val startMs: Long,
        val knuckleX: Float,
        val knuckleY: Float,
        val scale: Float,
        /** Where the finger goes down, in screen fractions. */
        val x: Float,
        val y: Float,
        val armed: Boolean,
        val openHand: Boolean,
    ) {
        var phase = Phase.PENDING
        var clean = true

        /** Without the cursor: held steady at first, as a deliberate grab is. */
        var settled = false

        /** Held without the cursor long enough to tap the centre; the engine fires it. */
        var tapDue = false
        var dragKnuckleX = 0f
        var dragKnuckleY = 0f
        var lastX = x
        var lastY = y
        var lastFactor = 1f
    }

    private class PendingTap(val x: Float, val y: Float, val deadlineMs: Long)

    private class Trail(val t: Long, val x: Float, val y: Float)

    // Cursor
    private var cursorShown = false
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var pointingSinceMs = -1L
    private var pointTipX = 0f
    private var pointTipY = 0f
    private var lastSteerMs = 0L
    private var steering = false
    private var lastKnuckleX = 0f
    private var lastKnuckleY = 0f
    private var lastKnuckleMs = 0L
    private val knuckleX = OneEuroFilter(config.cursorMinCutoffHz, config.cursorBeta)
    private val knuckleY = OneEuroFilter(config.cursorMinCutoffHz, config.cursorBeta)
    private val dragX = OneEuroFilter(config.cursorMinCutoffHz, config.cursorBeta)
    private val dragY = OneEuroFilter(config.cursorMinCutoffHz, config.cursorBeta)
    private val trail = ArrayDeque<Trail>()

    // Pinch
    private var contactRun = 0
    private var releaseRun = 0
    private var contact = false
    private var firstContactMs = 0L
    private var pinch: Pinch? = null
    private var pendingTap: PendingTap? = null
    private var quickCentrePinchEndMs = NEVER

    // Volume knob
    private var fistSinceMs = -1L
    private var fistArmed = false
    private var knobReady = false
    private var knobRefX = 0f
    private var knobRefY = 0f
    private var lastStepMs = NEVER

    /**
     * One frame with a hand. [rawPose] is this frame's shape and [stablePose] the voted one; [armed] and [engaged]
     * come from the engine's guard, and [flickActive] is true while the fingertips are away on a possible flick.
     */
    fun update(
        f: HandFeatures,
        rawPose: Pose,
        stablePose: Pose?,
        t: Long,
        armed: Boolean,
        engaged: Boolean,
        flickActive: Boolean,
        paused: Boolean,
    ): Result {
        if (paused) return stop()
        var touch: Touch? = null
        var gesture: GestureType? = null

        // Pinch contact starts and ends only after a few frames in a row. Starting needs real contact: the pose keeps
        // a looser pinch for stability, which a relaxed open hand (thumb near the index) can sit inside for a while.
        if (rawPose == Pose.PINCH && (contact || f.pinchDistance < config.pinchEnter)) {
            if (contactRun == 0) firstContactMs = t
            contactRun++
            releaseRun = 0
        } else {
            releaseRun++
            contactRun = 0
        }
        val wasContact = contact
        if (!contact && contactRun >= config.pinchContactFrames) contact = true
        if (contact && releaseRun >= config.pinchContactFrames) contact = false
        val onset = contact && !wasContact
        val release = !contact && wasContact

        // The cursor appears after a moment of steady pointing and follows the index knuckle while pointing or
        // pinching. Steady: a flick down whose index folds last looks like pointing, but its index is still moving.
        val tipX = (f.indexTipX - f.wristX) / f.scale
        val tipY = (f.indexTipY - f.wristY) / f.scale
        if (stablePose == Pose.POINT) {
            if (pointingSinceMs < 0 || hypot(tipX - pointTipX, tipY - pointTipY) > POINT_STEADY_SCALES) {
                pointingSinceMs = t
                pointTipX = tipX
                pointTipY = tipY
            }
        } else {
            pointingSinceMs = -1
        }
        if (!cursorShown && pointingSinceMs >= 0 && t - pointingSinceMs >= config.cursorShowMs) showCursor()

        if (onset) {
            // A finger still down from before the hand went missing lifts first: replacing it unlifted left a finger
            // down for good, and every later action waited for it (live, 25 Oct 2026).
            pinch?.let(::lift)?.let { touch = it }
            val pending = pendingTap
            val fromPointing = cursorShown && t - lastSteerMs <= config.pointToPinchMs
            when {
                fromPointing && pending != null -> {
                    // A second pinch straight after a tap: one double-tap where the first went.
                    pendingTap = null
                    pinch = newPinch(Owner.CURSOR, f, pending.x, pending.y, armed).also { it.phase = Phase.DONE }
                    if (touch == null) touch = Touch.DoubleTap(pending.x, pending.y)
                }
                fromPointing -> {
                    val at = trailAt(firstContactMs - config.pinchLookbackMs)
                    pinch = newPinch(Owner.CURSOR, f, at.x, at.y, armed)
                }
                else -> {
                    val centre = newPinch(Owner.CENTRE, f, 0.5f, 0.5f, armed).also { it.clean = !flickActive && it.openHand }
                    if (centre.armed && centre.clean && centre.openHand && t - quickCentrePinchEndMs <= config.doublePinchGapMs) {
                        quickCentrePinchEndMs = NEVER
                        centre.phase = Phase.DONE
                        gesture = GestureType.DOUBLE_PINCH
                    }
                    pinch = centre
                }
            }
        }

        val current = pinch
        val zooming = current?.phase == Phase.ZOOM
        val steer = cursorShown && !zooming &&
            (rawPose == Pose.POINT || stablePose == Pose.POINT || (current?.owner == Owner.CURSOR && contact))
        if (steer) {
            moveCursor(f, t)
            lastSteerMs = t
        } else {
            steering = false
        }

        if (contact && current != null && !onset) {
            touch = advance(current, f, t, flickActive) ?: touch
            if (current.tapDue && gesture == null) {
                current.tapDue = false
                gesture = GestureType.PINCH
            }
        }

        if (release) {
            pinch = null
            when (current?.phase) {
                Phase.DRAG, Phase.LONG_PRESS -> touch = Touch.Up
                Phase.ZOOM -> touch = Touch.ZoomEnd
                Phase.PENDING -> when {
                    current.owner == Owner.CURSOR -> pendingTap = PendingTap(current.x, current.y, t + config.doubleTapWindowMs)
                    current.armed && current.clean && current.openHand && t - current.startMs <= config.doublePinchMaxMs ->
                        quickCentrePinchEndMs = t
                }
                else -> Unit
            }
        }

        if (touch == null) touch = expirePendingTap(t)

        if (gesture == null) gesture = turnKnob(f, stablePose, t, armed, engaged)

        if (cursorShown && !steer && pinch == null && pendingTap == null && t - lastSteerMs > config.cursorHideMs) hideCursor()
        if (cursorShown) {
            trail.addLast(Trail(t, cursorX, cursorY))
            while (trail.size > 1 && t - trail.first().t > TRAIL_MS) trail.removeFirst()
        }
        // A centre pinch still pending is not busy: a finger flick pinches for a moment and must still count. Nor is
        // pointing before the cursor shows: flicks down pass through it.
        val active = pinch
        val busy = steer || fistUsed ||
            (active != null && (active.owner == Owner.CURSOR || active.phase == Phase.DRAG || active.phase == Phase.ZOOM))
        return Result(pointer(t), touch, gesture, busy)
    }

    /**
     * A frame without a usable hand, [goneMs] after the last one. A pinch survives a short dropout (a fast hand blurs
     * for a frame or two, or reads as too far): if the hand comes back still pinching, the drag goes on, and if it
     * comes back open, the finger lifts then. Gone [GestureConfig.touchLostMs], the finger lifts now.
     */
    fun handGone(t: Long, goneMs: Long): Result {
        var touch = expirePendingTap(t)
        steering = false
        fistSinceMs = -1
        knobReady = false
        val current = pinch
        if (goneMs >= config.touchLostMs) {
            contact = false
            contactRun = 0
            releaseRun = 0
            if (touch == null && current != null) {
                pinch = null
                touch = lift(current)
            }
        }
        if (cursorShown && pinch == null && pendingTap == null && t - lastSteerMs > config.cursorHideMs) hideCursor()
        return Result(pointer(t), touch, null, busy = pinch != null)
    }

    /** Paused or stopped: lifts any finger and hides the cursor. */
    fun stop(): Result {
        val touch = clear()
        hideCursor()
        return Result(null, touch, null, busy = false)
    }

    /** Forgets the hand (it has been gone too long) but keeps the cursor until it times out. Returns the lift of a finger still down. */
    fun clear(): Touch? {
        val lifted = pinch?.let(::lift)
        pinch = null
        pendingTap = null
        contact = false
        contactRun = 0
        releaseRun = 0
        steering = false
        pointingSinceMs = -1
        quickCentrePinchEndMs = NEVER
        fistSinceMs = -1
        knobReady = false
        fistUsed = false
        return lifted
    }

    private fun advance(p: Pinch, f: HandFeatures, t: Long, flickActive: Boolean): Touch? {
        if (flickActive) p.clean = false
        val (kx, ky) = knuckle(p.owner, f)
        val moved = hypot(kx - p.knuckleX, ky - p.knuckleY) / f.scale
        val size = f.scale / p.scale
        val depth = abs(ln(size))
        return when (p.phase) {
            Phase.PENDING -> {
                val held = t - p.startMs
                if (held <= config.centreDragMinPinchMs && moved > config.centreSettleScales) p.clean = false
                if (held >= config.centreDragMinPinchMs) p.settled = true
                val allowed = p.owner == Owner.CURSOR || (p.armed && p.clean && p.settled)
                // Moving the hand closer also shifts it a little in the picture: zoom wins while depth leads.
                val zoom = allowed && depth >= ln(config.zoomStartRatio) && moved < ZOOM_SHIFT_PER_DEPTH * depth
                val lateral = depth < DEPTH_UNDER_WAY
                when {
                    zoom -> {
                        p.phase = Phase.ZOOM
                        Touch.ZoomStart(p.x, p.y)
                    }
                    p.owner == Owner.CURSOR && moved >= (if (lateral) config.dragSlopScales else config.centreDragStartScales) -> {
                        p.phase = Phase.DRAG
                        Touch.Down(p.x, p.y)
                    }
                    p.owner == Owner.CENTRE && allowed && moved >= config.centreDragStartScales && held >= config.centreDragMinPinchMs -> {
                        p.phase = Phase.DRAG
                        p.dragKnuckleX = f.indexKnuckleX
                        p.dragKnuckleY = f.indexKnuckleY
                        dragX.reset()
                        dragY.reset()
                        Touch.Down(0.5f, 0.5f)
                    }
                    p.owner == Owner.CURSOR && held >= config.longPressMs && abs(size - 1f) <= config.longPressMaxSizeChange -> {
                        p.phase = Phase.LONG_PRESS
                        Touch.Down(p.x, p.y, hold = true)
                    }
                    p.owner == Owner.CENTRE && p.armed && held >= config.pinchHoldMs && moved < config.centreDragStartScales -> {
                        p.phase = Phase.DONE
                        p.tapDue = true
                        null
                    }
                    else -> null
                }
            }
            Phase.DRAG, Phase.LONG_PRESS -> {
                // A held hand drifts, so moving a long-pressed item takes a deliberate move.
                if (p.phase == Phase.LONG_PRESS && moved < LONG_PRESS_DRAG_SLOPS * config.dragSlopScales) return null
                val (x, y) = if (p.owner == Owner.CURSOR) cursorX to cursorY else centreDragPoint(p, f, t)
                if (abs(x - p.lastX) + abs(y - p.lastY) < MOVE_EPSILON) return null
                p.lastX = x
                p.lastY = y
                Touch.Move(x, y)
            }
            Phase.ZOOM -> {
                val factor = size.pow(config.zoomExponent).coerceIn(1f / config.zoomMaxFactor, config.zoomMaxFactor)
                if (abs(factor - p.lastFactor) < ZOOM_EPSILON) return null
                p.lastFactor = factor
                Touch.Zoom(factor)
            }
            Phase.DONE -> null
        }
    }

    /** Without the cursor, the finger starts at the centre and follows the hand at the cursor's pace, inside the safe zone. */
    private fun centreDragPoint(p: Pinch, f: HandFeatures, t: Long): Pair<Float, Float> {
        val kx = dragX.filter(f.indexKnuckleX, t)
        val ky = dragY.filter(f.indexKnuckleY, t)
        val dx = (kx - p.dragKnuckleX) / f.scale / config.cursorRangeScales / screenAspect
        val dy = (ky - p.dragKnuckleY) / f.scale / config.cursorRangeScales
        return (0.5f + dx).coerceIn(SAFE_MIN_X, 1f - SAFE_MIN_X) to (0.5f + dy).coerceIn(SAFE_MIN_Y, 1f - SAFE_MIN_Y)
    }

    private fun turnKnob(f: HandFeatures, stablePose: Pose?, t: Long, armed: Boolean, engaged: Boolean): GestureType? {
        if (stablePose != Pose.FIST || pinch != null) {
            fistSinceMs = -1
            knobReady = false
            fistUsed = false
            return null
        }
        if (fistSinceMs < 0) {
            fistSinceMs = t
            fistArmed = armed || engaged
            knobReady = false
            fistUsed = false
        }
        // The reference is taken once the fist has settled, so making the fist never counts as turning.
        if (fistArmed && !knobReady && t - fistSinceMs >= config.knobMinFistMs) {
            knobReady = true
            knobRefX = f.palmX
            knobRefY = f.palmY
        }
        if (!knobReady) return null
        val dx = (f.palmX - knobRefX) / f.scale
        val dy = (f.palmY - knobRefY) / f.scale
        if (hypot(dx, dy) >= config.knobConsumeScales) fistUsed = true
        if (abs(dy) < config.knobStepScales || t - lastStepMs < config.knobMinStepMs) return null
        knobRefY += sign(dy) * config.knobStepScales * f.scale
        lastStepMs = t
        fistUsed = true
        return if (dy < 0) GestureType.FIST_RAISE else GestureType.FIST_LOWER
    }

    private fun moveCursor(f: HandFeatures, t: Long) {
        if (!steering) {
            knuckleX.reset()
            knuckleY.reset()
        }
        val kx = knuckleX.filter(f.indexKnuckleX, t)
        val ky = knuckleY.filter(f.indexKnuckleY, t)
        if (steering && t > lastKnuckleMs) {
            val dx = (kx - lastKnuckleX) / f.scale
            val dy = (ky - lastKnuckleY) / f.scale
            val speed = hypot(dx, dy) * 1000f / (t - lastKnuckleMs)
            val gain = acceleration(speed) / config.cursorRangeScales
            cursorX = (cursorX + dx * gain / screenAspect).coerceIn(0f, 1f)
            cursorY = (cursorY + dy * gain).coerceIn(0f, 1f)
        }
        lastKnuckleX = kx
        lastKnuckleY = ky
        lastKnuckleMs = t
        steering = true
    }

    private fun acceleration(speed: Float): Float {
        val share = ((speed - config.cursorSlowSpeed) / (config.cursorFastSpeed - config.cursorSlowSpeed)).coerceIn(0f, 1f)
        return config.cursorSlowGain + (config.cursorFastGain - config.cursorSlowGain) * share
    }

    /** At the cursor the smoothed knuckle, which the cursor already follows; without it the raw one, as its thresholds are wide. */
    private fun knuckle(owner: Owner, f: HandFeatures): Pair<Float, Float> =
        if (owner == Owner.CURSOR && steering) lastKnuckleX to lastKnuckleY else f.indexKnuckleX to f.indexKnuckleY

    private fun newPinch(owner: Owner, f: HandFeatures, x: Float, y: Float, armed: Boolean) = Pinch(
        owner = owner,
        startMs = firstContactMs,
        knuckleX = knuckle(owner, f).first,
        knuckleY = knuckle(owner, f).second,
        scale = f.scale,
        x = x,
        y = y,
        armed = armed,
        openHand = (1..3).count { f.isExtended(it) } >= 2,
    )

    private fun expirePendingTap(t: Long): Touch? {
        val pending = pendingTap ?: return null
        if (t < pending.deadlineMs) return null
        pendingTap = null
        return Touch.Tap(pending.x, pending.y)
    }

    private fun lift(p: Pinch): Touch? = when (p.phase) {
        Phase.DRAG, Phase.LONG_PRESS -> Touch.Up
        Phase.ZOOM -> Touch.ZoomEnd
        else -> null
    }

    private fun trailAt(t: Long): Trail = trail.lastOrNull { it.t <= t } ?: trail.firstOrNull() ?: Trail(t, cursorX, cursorY)

    private fun showCursor() {
        cursorShown = true
        cursorX = 0.5f
        cursorY = 0.5f
        steering = false
        trail.clear()
    }

    private fun hideCursor() {
        cursorShown = false
        steering = false
        trail.clear()
    }

    private fun pointer(t: Long): Pointer? {
        if (!cursorShown) return null
        val p = pinch
        val pressed = p?.owner == Owner.CURSOR && (p.phase == Phase.DRAG || p.phase == Phase.LONG_PRESS || p.phase == Phase.ZOOM)
        val progress = if (p?.owner == Owner.CURSOR && p.phase == Phase.PENDING && t - p.startMs >= HOLD_RING_DELAY_MS) {
            ((t - p.startMs).toFloat() / config.longPressMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        return Pointer(cursorX, cursorY, pressed, progress)
    }

    private companion object {
        /** The Redmi Note 8 Pro in portrait, 1080 × 2340. The app sets the real value. */
        const val DEFAULT_SCREEN_ASPECT = 1080f / 2340f
        const val NEVER = Long.MIN_VALUE / 2
        const val TRAIL_MS = 500L

        /** The long-press ring only shows once a pinch is clearly not a quick tap. */
        const val HOLD_RING_DELAY_MS = 150L

        /** Hand size change (as a log ratio) above which the hand is moving in depth, so a small shift is not yet a drag. */
        const val DEPTH_UNDER_WAY = 0.06f

        /** Zoom starts only while sideways shift (hand scales) stays below this many times the depth change. */
        const val ZOOM_SHIFT_PER_DEPTH = 2.5f
        const val LONG_PRESS_DRAG_SLOPS = 2f

        /** How far (hand scales) the index tip may wander relative to the wrist while pointing still counts as steady. */
        const val POINT_STEADY_SCALES = 0.3f
        const val MOVE_EPSILON = 0.002f
        const val ZOOM_EPSILON = 0.01f

        // A drag without the cursor stays clear of the edges, where the system's Back and Home gestures start.
        const val SAFE_MIN_X = 0.12f
        const val SAFE_MIN_Y = 0.15f
    }
}
