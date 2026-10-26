package com.airtouch.gesture

import com.airtouch.gesture.features.HandFeatures
import com.airtouch.gesture.features.Pose
import com.airtouch.gesture.interaction.HandInteractions
import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.HandInput
import com.airtouch.gesture.model.Pointer
import com.airtouch.gesture.model.Touch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the followed hand, frame by frame, into at most one gesture per physical motion (P3).
 *
 * Recognition and the safety guard live together because they share one state machine:
 * Idle → Tracking → Armed → Cooldown → Tracking. A gesture counts only if it starts while Armed, and
 * arming takes a steady hand, not already in a fist or pinch, for [GestureConfig.armHoldMs]. Swipes also
 * need an open palm while moving. After every gesture the engine
 * cools down and must be re-armed, which is what stops the hand's return stroke from firing again.
 *
 * Not thread-safe: feed it from one thread, in timestamp order.
 */
class GestureEngine(private val config: GestureConfig = GestureConfig()) {

    enum class State { IDLE, TRACKING, ARMED, COOLDOWN }

    /**
     * What happened on one frame. [event] is set only when a gesture was accepted; [rejected] says why a candidate
     * was not. [pointer] is the cursor while it is shown, and [touch] a virtual-finger command for the app to inject.
     */
    data class Output(
        val state: State,
        val pose: Pose?,
        val event: GestureEvent?,
        val rejected: String?,
        val pointer: Pointer? = null,
        val touch: Touch? = null,
    )

    /** While paused only the thumbs-down (resume) gesture is reported. */
    @Volatile
    var paused = false

    /** A call is ringing: an open hand, opened after the ringing began and held still, reports [GestureType.OPEN_PALM]. */
    @Volatile
    var callRinging = false
    private var ringingSinceMs = -1L
    private var palmStillSinceMs = 0L
    private var palmAnchorX = Float.NaN
    private var palmAnchorY = 0f

    var state = State.IDLE
        private set

    /** Optional diagnostics: why each flick was judged the way it was. */
    var trace: ((String) -> Unit)? = null

    /** Screen width ÷ height in its current orientation, so the cursor moves evenly across and down. */
    var screenAspect: Float
        get() = interactions.screenAspect
        set(value) {
            interactions.screenAspect = value
        }

    private val interactions = HandInteractions(config)

    private class Sample(val t: Long, val x: Float, val y: Float, val scale: Float, val open: Boolean, val tipX: Float, val tipY: Float)

    private class Segment(val start: Sample, val startedArmed: Boolean) {
        val samples = mutableListOf(start)
        val last: Sample get() = samples.last()
    }

    private var aspect = 1f
    private var previous: Sample? = null
    private var lastSeenMs = Long.MIN_VALUE
    private var stillSinceMs = 0L
    private var cooldownUntilMs = 0L
    private var segment: Segment? = null
    private var lastSwipe: GestureType? = null
    private var lastSwipeAtMs = Long.MIN_VALUE
    private var lastFiredMs = Long.MIN_VALUE / 2

    private val recentPoses = ArrayDeque<Pose>()
    private var stablePose: Pose? = null
    private var lastRawPose: Pose? = null
    private var pinchOnsetMs = -1L
    private var heldPose: Pose? = null
    private var heldSinceMs = 0L
    private var heldStillSinceMs = 0L
    private var heldFired = false
    private class Flick(val startMs: Long, val awaySinceMs: Long, val wristX: Float, val wristY: Float, val startedArmed: Boolean) {
        var peakDistance = 0f
        var peakDx = 0f
        var peakDy = 0f
        var peakMs = startMs
        var lastAtPeakMs = startMs
        var sawThumb = false

        /** A shape held during the motion (pointing, V, three fingers…): the fingers moved to make it, not to flick. */
        var sawShape = false

        // Fastest fingertip speed seen each way, in hand scales per second (y grows downwards).
        var maxUp = 0f
        var maxDown = 0f
        var maxLeft = 0f
        var maxRight = 0f

        fun see(vx: Float, vy: Float) {
            maxRight = max(maxRight, vx)
            maxLeft = max(maxLeft, -vx)
            maxDown = max(maxDown, vy)
            maxUp = max(maxUp, -vy)
        }
    }

    /** Fingertips relative to the wrist, in hand scales, and their velocity over [FLICK_VELOCITY_MS]. */
    private class TipMotion(val t: Long, val fx: Float, val fy: Float, var vx: Float = 0f, var vy: Float = 0f)

    private var flick: Flick? = null
    private var restFx = 0f
    private var restFy = 0f
    private var restKnown = false
    private var nearRest = false
    private var nearRestSinceMs = 0L
    private var lastNearRestMs = 0L
    private var waitingForRest = false
    private var lastFlick: GestureType? = null
    private var lastFlickMs = Long.MIN_VALUE / 2
    private val tipMotion = ArrayDeque<TipMotion>()
    private val recentTips = ArrayDeque<Sample>()

    fun process(input: HandInput): Output {
        val t = input.timestampMs
        aspect = input.imageAspect
        if (callRinging) {
            if (ringingSinceMs < 0) ringingSinceMs = t
        } else {
            ringingSinceMs = -1
        }
        val hand = input.hand ?: return noHand(t, null)
        if (hand.score < config.minHandScore) return noHand(t, "low confidence")
        val features = HandFeatures.of(hand, input.imageAspect, config)
        if (features.scale < config.minHandScale) return noHand(t, "hand too small or too far")

        val pose = features.pose(config, wasPinching = lastRawPose == Pose.PINCH)
        if (pose == Pose.PINCH && lastRawPose != Pose.PINCH) pinchOnsetMs = t
        lastRawPose = pose
        votePose(pose)

        val sample = Sample(t, features.palmX, features.palmY, features.scale, pose == Pose.OPEN, features.indexTipX, features.indexTipY)
        rememberTip(sample)
        val before = previous
        previous = sample
        lastSeenMs = t

        var event: GestureEvent? = null
        var rejected: String? = null

        // A fast swipe can blur the hand out for a frame or two: judge the motion seen so far.
        val gap = before == null || t - before.t > config.swipeMaxGapMs
        if (gap) {
            segment?.let { seg -> judgeSwipe(seg, t).let { (e, r) -> event = e; rejected = r } }
            segment = null
        }
        // Velocity over the last [velocityWindowMs], in hand scales per second.
        val origin = recentTips.firstOrNull { t - it.t <= config.velocityWindowMs && it !== sample } ?: before
        val span = if (origin == null) 0L else t - origin.t
        val vx = if (gap || origin == null || span <= 0) 0f else (sample.x - origin.x) / sample.scale * 1000f / span
        val vy = if (gap || origin == null || span <= 0) 0f else (sample.y - origin.y) / sample.scale * 1000f / span
        val speed = hypot(vx, vy)

        when (state) {
            State.IDLE -> {
                state = State.TRACKING
                stillSinceMs = t
            }
            State.COOLDOWN -> if (t >= cooldownUntilMs) {
                state = State.TRACKING
                stillSinceMs = t
            }
            else -> Unit
        }

        // Pointing, pinching and the volume knob first: while they are under way nothing else may fire.
        val interaction = interactions.update(
            features, pose, stablePose, t,
            armed = state == State.ARMED,
            engaged = t - lastFiredMs < config.engagedWindowMs,
            flickActive = flick != null,
            paused = paused,
        )
        if (event == null) interactionGesture(interaction.gesture, t)?.let { event = it }
        if (interaction.busy) {
            segment = null
            if (flick != null) {
                flick = null
                waitingForRest = true
            }
        }

        // Swipe segments are tracked in every state, so a motion that began before arming never counts halfway through.
        val finished = if (event == null && !interaction.busy) trackSwipe(origin, sample, vx, vy, speed) else null
        if (finished != null) judgeSwipe(finished, t).let { (e, r) -> event = e; rejected = r }
        // Finger flicks barely move the palm, so they are tracked alongside any swipe segment.
        if (event == null && !interaction.busy) {
            trackFlick(features, pose, t, speed).let { (e, r) -> if (e != null || r != null) { event = e; rejected = r } }
        }

        updateHeldShape(t, speed)
        val shapesAllowed = event == null && rejected == null && !interaction.busy
        when (state) {
            State.TRACKING -> {
                // A distinct shape held still is itself a clear signal, so it may fire without arming first.
                if (shapesAllowed) judgeHeldShape(t, armed = false).let { (e, r) -> event = e; rejected = r }
                // A relaxed hand arms too (recorded pinches started from one); swipes still need an open palm.
                // Someone mid-rhythm has already shown intent, so a shorter pause re-arms.
                val hold = if (t - lastFiredMs < config.engagedWindowMs) config.rearmHoldMs else config.armHoldMs
                if (speed > config.armStillSpeed || (stablePose != Pose.OPEN && stablePose != Pose.OTHER)) {
                    stillSinceMs = t
                } else if (t - stillSinceMs >= hold) {
                    state = State.ARMED
                }
            }
            State.ARMED -> if (shapesAllowed && segment == null) {
                judgeHeldShape(t, armed = true).let { (e, r) -> event = e; rejected = r }
            }
            else -> Unit
        }
        return Output(state, stablePose, event, rejected, interaction.pointer, touchOut(interaction.touch))
    }

    /** Fires what the interactions produced: the held centre pinch (a tap), a double pinch, or one volume step. */
    private fun interactionGesture(type: GestureType?, t: Long): GestureEvent? {
        val last = previous ?: return null
        return when (type) {
            null -> null
            GestureType.PINCH -> {
                if (paused) return null
                // Closing the fingers drags the index tip off target, so tap where it was just before.
                val anchor = recentTips.lastOrNull { it.t <= pinchOnsetMs - config.pinchAnchorLookbackMs } ?: recentTips.first()
                fire(GestureType.PINCH, t, confidence = 1f, anchorX = anchor.tipX / aspect, anchorY = anchor.tipY)
            }
            GestureType.DOUBLE_PINCH -> fire(type, t, confidence = 1f, anchorX = 0.5f, anchorY = 0.5f)
            // Volume steps come in a stream, so they neither cool down nor need re-arming between them.
            else -> {
                lastFiredMs = t
                GestureEvent(type, t, confidence = 1f, anchorX = last.x / aspect, anchorY = last.y)
            }
        }
    }

    /** Forgets everything in progress. A finger still down is lifted by the next output. */
    fun reset() {
        interactions.clear()?.let { liftPending = it }
        state = State.IDLE
        lastFiredMs = Long.MIN_VALUE / 2
        previous = null
        segment = null
        recentPoses.clear()
        recentTips.clear()
        stablePose = null
        lastRawPose = null
        heldPose = null
        heldFired = false
        flick = null
        restKnown = false
        nearRest = false
        waitingForRest = false
        tipMotion.clear()
    }

    /** The lift of a finger that [reset] forgot, sent with the next output so no finger stays down. */
    private var liftPending: Touch? = null

    private fun touchOut(touch: Touch?): Touch? {
        if (touch != null) return touch
        return liftPending.also { liftPending = null }
    }

    private fun noHand(t: Long, reason: String?): Output {
        var event: GestureEvent? = null
        var rejected = reason
        val open = segment
        if (open != null && t - open.last.t > config.swipeMaxGapMs) {
            judgeSwipe(open, t).let { (e, r) -> event = e; rejected = r ?: rejected }
            segment = null
        }
        // A finger left down must come up even though the hand is gone.
        val interaction = if (paused) interactions.stop() else interactions.handGone(t, if (lastSeenMs == Long.MIN_VALUE) Long.MAX_VALUE else t - lastSeenMs)
        if (lastSeenMs == Long.MIN_VALUE || t - lastSeenMs > config.lostAfterMs) {
            val keepCooldown = state == State.COOLDOWN && t < cooldownUntilMs
            reset()
            if (keepCooldown) state = State.COOLDOWN
        }
        return Output(state, stablePose, event, rejected, interaction.pointer, touchOut(interaction.touch))
    }

    private fun trackSwipe(origin: Sample?, sample: Sample, vx: Float, vy: Float, speed: Float): Segment? {
        val current = segment
        if (current == null) {
            if (origin != null && speed >= config.swipeStartSpeed) {
                // Start where the velocity window began: that is where the hand still was.
                segment = Segment(origin, startedArmed = state == State.ARMED).also { it.samples += sample }
            }
            return null
        }
        // Coming straight back ends the motion, so a swipe and its return stroke are judged separately.
        val dx = (sample.x - current.start.x) / sample.scale
        val dy = (sample.y - current.start.y) / sample.scale
        if (dx * vx + dy * vy < 0 && hypot(dx, dy) >= config.reversalAfter) {
            segment = null
            return current
        }
        current.samples += sample
        val duration = sample.t - current.start.t
        if (speed < config.swipeEndSpeed || duration > config.swipeMaxMs) {
            segment = null
            return current
        }
        return null
    }

    private fun judgeSwipe(seg: Segment, t: Long): Pair<GestureEvent?, String?> {
        val armed = seg.startedArmed && state == State.ARMED
        val first = seg.start
        val last = seg.last
        val scale = seg.samples.map { it.scale }.sorted()[seg.samples.size / 2]
        val dx = (last.x - first.x) / scale
        val dy = (last.y - first.y) / scale
        val major = max(abs(dx), abs(dy))
        val minor = min(abs(dx), abs(dy))
        val duration = last.t - first.t
        val openShare = seg.samples.count { it.open }.toFloat() / seg.samples.size
        val type = if (abs(dx) > abs(dy)) {
            if (dx < 0) GestureType.SWIPE_LEFT else GestureType.SWIPE_RIGHT
        } else {
            if (dy < 0) GestureType.SWIPE_UP else GestureType.SWIPE_DOWN
        }

        if (major < config.swipeMinDistance) return null to null // ordinary movement, not a candidate
        val averageSpeed = major * 1000f / max(1L, duration)
        // Timed from when this motion started: people pause at the far end before coming back.
        val isReturn = type == lastSwipe?.opposite() && first.t - lastSwipeAtMs < config.oppositeSuppressMs
        // Every swipe-like motion, armed or not, blocks its own return stroke; return strokes themselves
        // are not remembered, so repeated swipes in one direction keep working.
        if (!isReturn) {
            lastSwipe = type
            lastSwipeAtMs = t
        }
        val reason = when {
            isReturn -> "return stroke"
            !armed -> "swipe while not armed"
            duration < config.swipeMinMs -> "swipe too quick"
            duration > config.swipeMaxMs || averageSpeed < config.swipeMinAverageSpeed -> "swipe too slow"
            major < config.swipeDirectionRatio * minor -> "swipe not straight"
            openShare < config.swipeMinOpenShare -> "hand not open during swipe"
            paused -> "paused"
            else -> null
        }
        if (reason != null) {
            // A refused motion must be followed by a steady hand before the next gesture. Return strokes
            // are expected and harmless, so they leave the engine armed.
            if (state == State.ARMED && !isReturn) {
                state = State.TRACKING
                stillSinceMs = t
            }
            return null to reason
        }
        return fire(type, t, confidence = 1f - minor / major, anchorX = first.x / aspect, anchorY = first.y) to null
    }

    /** Times how long the current stable pose has been held, and held still. */
    private fun updateHeldShape(t: Long, speed: Float) {
        if (stablePose != heldPose) {
            heldPose = stablePose
            heldSinceMs = t
            heldStillSinceMs = t
            heldFired = false
            palmAnchorX = Float.NaN
        }
        if (speed > config.armStillSpeed) heldStillSinceMs = t
        // The call answer wants the hand truly still: it restarts whenever the palm leaves where it settled.
        val last = previous ?: return
        if (palmAnchorX.isNaN() || hypot(last.x - palmAnchorX, last.y - palmAnchorY) / last.scale > config.callPalmStillScales) {
            palmAnchorX = last.x
            palmAnchorY = last.y
            palmStillSinceMs = t
        }
    }

    /** Fist, V, three fingers, thumbs-up. Unarmed, the shape must be held still for an extra [GestureConfig.armHoldMs]. */
    private fun judgeHeldShape(t: Long, armed: Boolean): Pair<GestureEvent?, String?> {
        val palm = heldPose == Pose.OPEN && ringingSinceMs >= 0 && heldSinceMs >= ringingSinceMs
        if (palm && !heldFired && t - palmStillSinceMs >= config.callPalmHoldMs && t - heldStillSinceMs >= config.callPalmHoldMs) {
            heldFired = true
            if (paused) return null to "paused"
            val last = previous ?: return null to null
            return fire(GestureType.OPEN_PALM, t, confidence = 1f, anchorX = last.x / aspect, anchorY = last.y) to null
        }
        val held = HELD_SHAPES[heldPose] ?: return null to null
        if (heldFired) return null to null
        // A fist moved up or down is the volume knob, not play/pause.
        if (held == GestureType.FIST && interactions.fistUsed) return null to null
        val ready = if (armed) t - heldSinceMs >= config.poseHoldMs else t - heldStillSinceMs >= config.poseHoldMs + config.armHoldMs
        if (!ready) return null to null
        // Fires once per hold: the shape must change before it can fire again.
        heldFired = true
        if (paused && held != GestureType.THUMBS_DOWN) return null to "paused"
        val last = previous ?: return null to null
        return fire(held, t, confidence = 1f, anchorX = last.x / aspect, anchorY = last.y) to null
    }

    /**
     * Finger flicks. Follows the fingertips relative to the wrist: they leave their resting place, peak and
     * come back. The fast stroke is the flick and sets its direction; the slow half is its wind-up or its way
     * back. Recorded 24 Oct 2026: flicks down fold 2–6 times faster than they open, flicks up snap open about
     * 3 times faster than they fold, and sideways flicks swing out 2–4 times faster than they come back.
     */
    private fun trackFlick(f: HandFeatures, pose: Pose, t: Long, speed: Float): Pair<GestureEvent?, String?> {
        val motion = tipVelocity(f, t)
        val dx = f.tipsFromWristX - restFx
        val dy = f.tipsFromWristY - restFy
        val away = hypot(dx, dy)
        val current = flick
        if (current == null) {
            // A judged flick's fingers are still on their way back: let them arrive before looking for the next one.
            if (waitingForRest && restKnown && away >= config.flickNear) {
                if (t - lastNearRestMs > config.flickMaxCycleMs) {
                    waitingForRest = false
                    restKnown = false
                }
                return null to null
            }
            waitingForRest = false
            if (!restKnown || away < config.flickNear) {
                // Learn and follow the resting hand: fingers up, hand steady.
                if (f.fingerLift >= config.flickRestLift && speed < config.armStillSpeed) {
                    if (restKnown) {
                        restFx += (f.tipsFromWristX - restFx) * REST_FOLLOW
                        restFy += (f.tipsFromWristY - restFy) * REST_FOLLOW
                    } else {
                        restFx = f.tipsFromWristX
                        restFy = f.tipsFromWristY
                        restKnown = true
                    }
                }
                if (restKnown) {
                    if (!nearRest) nearRestSinceMs = t
                    nearRest = true
                    lastNearRestMs = t
                }
                return null to null
            }
            nearRest = false
            if (away >= config.flickStart) {
                // Armed, or mid-rhythm: someone flicking twice a second never holds the hand still in between (third
                // live test: 9 flicks in a row refused). The last flick's way back cannot start this one: the
                // fingertips had to come back to rest first. A flick judged during a cooldown is still refused.
                val ready = state == State.ARMED || t - lastFiredMs < config.engagedWindowMs
                val started = Flick(lastNearRestMs, t, f.wristX, f.wristY, startedArmed = ready)
                // The fast stroke of a flick down starts at rest, before the fingertips are far enough away to count.
                tipMotion.forEach { if (it.t > nearRestSinceMs) started.see(it.vx, it.vy) }
                flick = started
            }
            return null to null
        }
        if (pose == Pose.THUMBS_UP || pose == Pose.THUMBS_DOWN) current.sawThumb = true
        if (stablePose in NOT_FLICK_SHAPES) current.sawShape = true
        current.see(motion.vx, motion.vy)
        if (away > current.peakDistance) {
            current.peakDistance = away
            current.peakDx = dx
            current.peakDy = dy
            current.peakMs = t
        }
        if (away >= PEAK_SHARE * current.peakDistance) current.lastAtPeakMs = t
        // A slow way back is judged halfway, so it does not delay the action; a fast one is watched to the end,
        // since its speed decides the direction.
        val returning = if (away > 0f) -(motion.vx * dx + motion.vy * dy) / away else 0f
        val judgeAt = if (returning >= config.flickMinSpeed) config.flickEnd else max(config.flickEnd, config.flickJudgeShare * current.peakDistance)
        if (away >= judgeAt) {
            // Away too long: a fist, pinch or V being held, or the hand changing shape. Learn the rest again.
            val heldAway = away >= config.flickStart && t - current.awaySinceMs > config.flickMaxAwayMs
            if (heldAway || t - current.startMs > config.flickMaxCycleMs) {
                trace?.invoke("flick at ${t}: dropped, away for ${t - current.awaySinceMs} ms (peak %.2f)".format(current.peakDistance))
                flick = null
                restKnown = false
            }
            return null to null
        }

        flick = null
        lastNearRestMs = t
        waitingForRest = true
        val held = current.lastAtPeakMs - current.peakMs
        val wristMoved = hypot(f.wristX - current.wristX, f.wristY - current.wristY) / f.scale
        val sideways = abs(current.peakDx) >= config.flickSidewaysShare * abs(current.peakDy)
        val type: GestureType
        val fast: Float
        val slow: Float
        when {
            sideways && current.maxLeft >= current.maxRight -> { type = GestureType.FLICK_LEFT; fast = current.maxLeft; slow = current.maxRight }
            sideways -> { type = GestureType.FLICK_RIGHT; fast = current.maxRight; slow = current.maxLeft }
            current.maxDown >= current.maxUp -> { type = GestureType.FLICK_DOWN; fast = current.maxDown; slow = current.maxUp }
            else -> { type = GestureType.FLICK_UP; fast = current.maxUp; slow = current.maxDown }
        }
        trace?.invoke(
            "flick at ${t}: %s peak=%.2f dx=%.2f dy=%.2f speeds up=%.1f down=%.1f left=%.1f right=%.1f held=%dms wrist=%.2f ready=%b state=%s thumb=%b".format(
                type, current.peakDistance, current.peakDx, current.peakDy, current.maxUp, current.maxDown, current.maxLeft, current.maxRight,
                held, wristMoved, current.startedArmed, state, current.sawThumb,
            ),
        )
        // A brief thumbs-up or thumbs-down, or pointing and opening again, is also "fingers moved, then back": never a flick.
        if (current.peakDistance < config.flickMinExcursion || wristMoved > config.flickMaxPalmMove || current.sawThumb || current.sawShape) return null to null
        if (!current.startedArmed || state == State.COOLDOWN) return null to "flick while not armed"
        if (held > config.flickMaxHeldMs) return null to "held, not a flick"
        if (fast < config.flickMinSpeed) return null to "flick too slow"
        val repeat = type == lastFlick && t - lastFlickMs < config.flickRhythmMs
        if (fast < (if (repeat) config.flickRhythmSpeedRatio else config.flickSpeedRatio) * slow) return null to "flick direction unclear"
        // Flicks never block one another, since each one's way back is part of it, but like swipes they block
        // the return stroke of a swipe.
        lastSwipe = FLICK_DIRECTION.getValue(type)
        lastSwipeAtMs = t
        if (paused) return null to "paused"
        lastFlick = type
        lastFlickMs = t
        return fire(type, t, confidence = 1f - slow / fast, anchorX = current.wristX / aspect, anchorY = current.wristY) to null
    }

    private fun tipVelocity(f: HandFeatures, t: Long): TipMotion {
        val now = TipMotion(t, f.tipsFromWristX, f.tipsFromWristY)
        val origin = tipMotion.firstOrNull { t - it.t <= FLICK_VELOCITY_MS } ?: tipMotion.lastOrNull()?.takeIf { t - it.t <= 2 * FLICK_VELOCITY_MS }
        if (origin != null && t > origin.t) {
            now.vx = (now.fx - origin.fx) * 1000f / (t - origin.t)
            now.vy = (now.fy - origin.fy) * 1000f / (t - origin.t)
        }
        tipMotion.addLast(now)
        while (t - tipMotion.first().t > FLICK_LEAD_MS) tipMotion.removeFirst()
        return now
    }

    private fun fire(type: GestureType, t: Long, confidence: Float, anchorX: Float, anchorY: Float): GestureEvent {
        state = State.COOLDOWN
        lastFiredMs = t
        cooldownUntilMs = t + config.cooldownMs
        segment = null
        return GestureEvent(type, t, confidence.coerceIn(0f, 1f), anchorX, anchorY)
    }

    /** Majority vote over the last few frames; [stablePose] stays null until one pose wins. */
    private fun votePose(pose: Pose) {
        recentPoses.addLast(pose)
        if (recentPoses.size > config.poseWindow) recentPoses.removeFirst()
        stablePose = recentPoses.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value >= config.poseVotes }?.key
            ?: stablePose.takeIf { recentPoses.size < config.poseWindow }
    }

    private fun rememberTip(sample: Sample) {
        recentTips.addLast(sample)
        while (recentTips.size > 1 && sample.t - recentTips.first().t > TIP_HISTORY_MS) recentTips.removeFirst()
    }

    private companion object {
        // Covers the pinch hold, so the tap can still go where the fingertip was before the pinch began.
        const val TIP_HISTORY_MS = 2_000L

        /** Fingertip speeds are measured over this window; a recorded snap up took 100–160 ms. */
        const val FLICK_VELOCITY_MS = 100L

        /** How far back a new flick looks for its fast stroke, which may start before it counts as a flick. */
        const val FLICK_LEAD_MS = 250L

        /** Fingertips this close to the farthest point still count as "at the peak". */
        const val PEAK_SHARE = 0.85f

        /** How quickly the remembered resting place follows a steady hand. */
        const val REST_FOLLOW = 0.2f

        /** Flicks share the return-stroke rule with swipes, by direction. */
        val FLICK_DIRECTION = mapOf(
            GestureType.FLICK_UP to GestureType.SWIPE_UP,
            GestureType.FLICK_DOWN to GestureType.SWIPE_DOWN,
            GestureType.FLICK_LEFT to GestureType.SWIPE_LEFT,
            GestureType.FLICK_RIGHT to GestureType.SWIPE_RIGHT,
        )

        val HELD_SHAPES = mapOf(
            Pose.FIST to GestureType.FIST,
            Pose.V to GestureType.V_SIGN,
            Pose.THREE to GestureType.THREE_FINGERS,
            Pose.THUMBS_UP to GestureType.THUMBS_UP,
            Pose.THUMBS_DOWN to GestureType.THUMBS_DOWN,
            Pose.ROCK to GestureType.ROCK,
            Pose.CALL to GestureType.CALL,
        )

        /**
         * Shapes whose making moves the fingertips like a flick would. Not the fist, which a flick down folds into, nor
         * pointing, which recorded flicks down pass through (an index finger folding last); the cursor covers pointing.
         */
        val NOT_FLICK_SHAPES = setOf(Pose.V, Pose.THREE, Pose.ROCK, Pose.CALL)
    }
}
