package com.airtouch.gesture.face

import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One frame of the user's face, as the face gestures need it (P4c). Expression scores are MediaPipe blendshapes,
 * 0..1, for the user's own left and right. Head angles are degrees in the user's view: yaw negative to the user's
 * left, pitch negative with the chin up; only their changes matter, so a tilted phone stand is fine.
 */
data class FaceFrame(
    val browUpLeft: Float,
    val browUpRight: Float,
    val eyeClosedLeft: Float,
    val eyeClosedRight: Float,
    val pucker: Float,
    val jawOpen: Float,
    val yaw: Float,
    val pitch: Float,
)

/** What the face engine receives each face frame: the face, or null when none is in view. */
data class FaceInput(val timestampMs: Long, val face: FaceFrame?)

/** Thresholds for [FaceEngine]. Scores are rises above the user's own neutral face. */
data class FaceConfig(
    /**
     * Eyebrows: one is raised [browRaise] above its neutral level and at least [browSide] above the other; both are
     * raised at [browBothRaise] each. A raise ends below half its threshold. Live (25 Oct 2026): one user's left
     * eyebrow rose +0.21 to +0.33 and their right only +0.19 to +0.23, the other eyebrow at or below neutral.
     */
    val browRaise: Float = 0.18f,
    val browBothRaise: Float = 0.25f,
    val browSide: Float = 0.15f,

    /**
     * An eyebrow that rests high has little room left (scores stop at 1): its raise needs only this share of the
     * room above its neutral level, down to [browRaiseMin]. Live, one right eyebrow rested at 0.7–0.8.
     */
    val browHeadroomShare: Float = 0.6f,
    val browRaiseMin: Float = 0.12f,
    val browConfirmFrames: Int = 2,
    /** A raise lowered within this is a short one: a single (after [browDoubleGapMs]) or half of a double. */
    val browShortMaxMs: Long = 700,
    val browDoubleGapMs: Long = 700,
    /** Held this long, one eyebrow turns the volume, a step every [browHoldRepeatMs]. */
    val browHoldMs: Long = 900,
    val browHoldRepeatMs: Long = 500,

    /**
     * An eyebrow that is up (above half its threshold) is left out of its neutral level until it has stayed up this
     * long. Live, tries that fell short lifted a neutral level from 0.58 to 0.79 within seconds, until no raise could
     * reach the threshold; but an eyebrow that settles higher must still be followed.
     */
    val browSettleMs: Long = 2_000,

    /**
     * A raise rises from below half its threshold within this. An eyebrow that drifts up slowly (the face relaxing
     * differently, the light changing) is no gesture: without this, it read as a held raise and stepped the volume.
     */
    val browRiseMs: Long = 800,

    /** Eyebrows neither start nor end a raise while both eyes are this far closed: blinks move them. */
    val browMaxEyes: Float = 0.3f,

    // Both eyes closed, held: normal blinks last 0.1–0.4 s.
    val eyesClosed: Float = 0.45f,
    val eyesHoldMs: Long = 1_000,

    // Kiss: lips puckered and held, mouth not open (talking "oo" is brief and moves the jaw). Live kisses: 0.27–0.43.
    val kissPucker: Float = 0.28f,
    val kissHoldMs: Long = 450,
    val kissMaxJaw: Float = 0.35f,

    // Head nods and turns: away by [headPeakDeg] within [headMaxOutMs], back within [headMaxCycleMs]. Away longer
    // is a new position, which the rest then follows.
    val headStartDeg: Float = 6f,
    val headPeakDeg: Float = 12f,
    val headReturnDeg: Float = 5f,
    val headMaxOutMs: Long = 600,
    val headMaxCycleMs: Long = 1_300,

    /** Expressions count only while the face looks roughly at the phone. */
    val frontalDeg: Float = 25f,

    /**
     * A gesture counts only if its own part of the face was at rest this long just before (below the gesture's own
     * threshold): the eyebrows for a raise, the eyes for closing them, the lips for a kiss, the head steady for a
     * nod. Each part on its own, and "at rest" generously: at rest, live, the lips read up to +0.16 and the eyebrows
     * up to +0.2 above neutral (25 Oct 2026), which a stricter rule took for "not at rest" and so refused every gesture.
     */
    val quietMs: Long = 200,
    val cooldownMs: Long = 600,
    val lostMs: Long = 500,

    /**
     * Neutral levels: each expression score's 40th percentile over its last [neutralFrames] frames outside its own
     * gestures. Live, eyebrows at rest sat 0.1–0.2 above their lowest moments, which a lowest-value rule took for raises.
     */
    val neutralFrames: Int = 60,
    val neutralQuantile: Float = 0.4f,

    /** The head's rest follows while the head is steady. */
    val restRate: Float = 0.05f,

    /**
     * Head angles come from MediaPipe's depth estimates, which wobble: still heads measured up to 50°/s in the first
     * live test. They are smoothed (this share of each new frame), and "steady" allows for the rest.
     */
    val headSmoothing: Float = 0.6f,
    val steadyDegPerSec: Float = 30f,
)

/**
 * Face gestures (P4c): head nods and turns, both eyes closed, one or both eyebrows raised once, twice or held,
 * and a kiss. A gesture counts only from a quiet moment of its own part of the face, fires once per movement and
 * cools down after. Each eyebrow has three meanings, so a single raise is reported only once a second one has had
 * time to start. Not thread-safe; feed it from one thread, in timestamp order.
 */
class FaceEngine(private val config: FaceConfig = FaceConfig()) {

    /** Whether face gestures can be made now, for the status shown on screen. */
    enum class Presence { NO_FACE, TURNED_AWAY, READY }

    data class Output(val event: GestureEvent?, val rejected: String?, val presence: Presence)

    /** While paused nothing is reported (thumbs-down, a hand gesture, resumes). */
    @Volatile
    var paused = false

    /** Optional diagnostics: each decision, with the scores behind it. */
    var trace: ((String) -> Unit)? = null

    /** The last frame's scores against the user's neutral face, for the log. */
    var status = "no face yet"
        private set

    private enum class Brow { LEFT, RIGHT, BOTH }

    /** The head: at its rest, away on a possible nod, or held away (not a nod; the rest follows it there). */
    private enum class Head { AT_REST, AWAY, HELD }

    /** A score's neutral level: a low percentile of its recent values, so passing expressions barely move it. */
    private class Neutral(size: Int, private val quantile: Float) {
        private val ring = FloatArray(size)
        private val sorted = FloatArray(size)
        private var count = 0
        private var next = 0
        var value = 0f
            private set
        val known: Boolean get() = count > 0

        fun add(x: Float) {
            ring[next] = x
            next = (next + 1) % ring.size
            if (count < ring.size) count++
            ring.copyInto(sorted, endIndex = count)
            sorted.sort(0, count)
            value = sorted[((count - 1) * quantile).toInt()]
        }
    }

    /** Quiet stretches of one part of the face, to tell whether a gesture starts from one. */
    private class Quiet {
        private var since = -1L
        private var last = -1L
        private var lastSpanMs = 0L

        fun update(quiet: Boolean, t: Long) {
            if (quiet) {
                if (since < 0) since = t
                last = t
            } else if (since >= 0) {
                lastSpanMs = last - since
                since = -1
            }
        }

        /** True if the quiet stretch now running, or the one that just ended (within [withinMs]), lasted [minMs]. */
        fun fresh(t: Long, minMs: Long, withinMs: Long): Boolean =
            (since >= 0 && t - since >= minMs) || (since < 0 && last >= 0 && lastSpanMs >= minMs && t - last <= withinMs)

        fun clear() {
            since = -1
            last = -1
            lastSpanMs = 0
        }
    }

    /** The head's resting angle, following it while it is steady. */
    private class Rest {
        var value = 0f
        var known = false

        fun learn(x: Float, rate: Float) {
            if (!known) {
                value = x
                known = true
            } else {
                value += (x - value) * rate
            }
        }
    }

    private val neutralBrowLeft = Neutral(config.neutralFrames, config.neutralQuantile)
    private val neutralBrowRight = Neutral(config.neutralFrames, config.neutralQuantile)
    private val neutralEyeLeft = Neutral(config.neutralFrames, config.neutralQuantile)
    private val neutralEyeRight = Neutral(config.neutralFrames, config.neutralQuantile)
    private val neutralPucker = Neutral(config.neutralFrames, config.neutralQuantile)
    private val restYaw = Rest()
    private val restPitch = Rest()

    private val browsQuiet = Quiet()
    private val eyesQuiet = Quiet()
    private val lipsQuiet = Quiet()
    private val headQuiet = Quiet()

    private var presence = Presence.NO_FACE
    private var lastSeenMs = Long.MIN_VALUE / 2
    private var previousSeenMs = Long.MIN_VALUE / 2
    private var frameGapMs = 100f
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var cooldownUntilMs = Long.MIN_VALUE / 2

    // Eyebrows
    private var candidate: Brow? = null
    private var candidateFrames = 0
    private var raised: Brow? = null
    private var raisedSinceMs = 0L
    private var raisedFresh = false
    private var holding = false
    private var lastHoldStepMs = 0L
    private val pendingSingle = mutableMapOf<Brow, Long>()
    private var browLeftUpSinceMs = -1L
    private var browRightUpSinceMs = -1L

    // Eyes and kiss
    private var eyesClosedSinceMs = -1L
    private var eyesFired = false
    private var puckerSinceMs = -1L
    private var kissFired = false

    // Head
    private var head = Head.AT_REST
    private var headStartMs = 0L
    private var headFresh = false
    private var peakYaw = 0f
    private var peakPitch = 0f
    private var peakMs = 0L

    fun process(input: FaceInput): Output {
        val t = input.timestampMs
        val f = input.face ?: return noFace(t)
        val dtMs = (t - lastSeenMs).coerceIn(1, 1_000)
        previousSeenMs = lastSeenMs
        val fresh = !restYaw.known || t - lastSeenMs > config.lostMs
        val lastYaw = smoothYaw
        val lastPitch = smoothPitch
        smoothYaw = if (fresh) f.yaw else smoothYaw + (f.yaw - smoothYaw) * config.headSmoothing
        smoothPitch = if (fresh) f.pitch else smoothPitch + (f.pitch - smoothPitch) * config.headSmoothing
        val headSpeed = if (fresh) 0f else hypot(smoothYaw - lastYaw, smoothPitch - lastPitch) * 1000f / dtMs
        lastSeenMs = t
        if (!restYaw.known || (headSpeed < config.steadyDegPerSec && head != Head.AWAY)) {
            restYaw.learn(smoothYaw, config.restRate)
            restPitch.learn(smoothPitch, config.restRate)
        }

        // Neutral levels from frames outside each part's own gesture; the first frame starts them.
        browLeftUpSinceMs = learnBrow(neutralBrowLeft, f.browUpLeft, t, browLeftUpSinceMs)
        browRightUpSinceMs = learnBrow(neutralBrowRight, f.browUpRight, t, browRightUpSinceMs)
        if (eyesClosedSinceMs < 0 || !neutralEyeLeft.known) {
            neutralEyeLeft.add(f.eyeClosedLeft)
            neutralEyeRight.add(f.eyeClosedRight)
        }
        if (puckerSinceMs < 0 || !neutralPucker.known) neutralPucker.add(f.pucker)

        val left = f.browUpLeft - neutralBrowLeft.value
        val right = f.browUpRight - neutralBrowRight.value
        val raiseLeft = raiseThreshold(neutralBrowLeft.value, config.browRaise)
        val raiseRight = raiseThreshold(neutralBrowRight.value, config.browRaise)
        val eyes = minOf(f.eyeClosedLeft - neutralEyeLeft.value, f.eyeClosedRight - neutralEyeRight.value)
        val pucker = f.pucker - neutralPucker.value
        val yaw = smoothYaw - restYaw.value
        val pitch = smoothPitch - restPitch.value
        val frontal = abs(yaw) < config.frontalDeg && abs(pitch) < config.frontalDeg
        presence = if (frontal) Presence.READY else Presence.TURNED_AWAY

        // Which eyebrow raise the scores show, if any. One side clearly higher is that side, even if the other rose
        // too: few people keep it perfectly still. Both rising about evenly but not far is no raise, and counts as
        // at rest: resting eyebrows wobble by up to +0.2 together (live).
        val shape = when {
            left >= raiseLeft && left - right >= config.browSide -> Brow.LEFT
            right >= raiseRight && right - left >= config.browSide -> Brow.RIGHT
            left >= raiseThreshold(neutralBrowLeft.value, config.browBothRaise) &&
                right >= raiseThreshold(neutralBrowRight.value, config.browBothRaise) -> Brow.BOTH
            else -> null
        }
        browsQuiet.update(shape == null, t)
        eyesQuiet.update(eyes < config.eyesClosed, t)
        lipsQuiet.update(pucker < config.kissPucker, t)
        headQuiet.update(headSpeed < config.steadyDegPerSec && head != Head.AWAY, t)
        val coolingDown = t < cooldownUntilMs
        frameGapMs += ((t - previousSeenMs).coerceIn(1, 1_000) - frameGapMs) * 0.1f
        status = String.format(
            Locale.US,
            "brows %+.2f/%+.2f eyes %+.2f kiss %+.2f jaw %.2f head %+.0f°/%+.0f° speed %.0f°/s neutral brows %.2f/%.2f · %.0f fps",
            left, right, eyes, pucker, f.jawOpen, yaw, pitch, headSpeed, neutralBrowLeft.value, neutralBrowRight.value, 1000f / frameGapMs,
        )

        var event: GestureEvent? = null
        var rejected: String? = null
        fun emit(type: GestureType, cooldown: Boolean = true) {
            if (event != null) return
            if (paused) {
                rejected = "paused"
                return
            }
            event = GestureEvent(type, t, confidence = 1f, anchorX = 0.5f, anchorY = 0.5f)
            if (cooldown) cooldownUntilMs = t + config.cooldownMs
            trace?.invoke("face $type at $t: $status")
        }

        // Head nods and turns: away and back, quickly, from a steady head, with no eyebrow raise on the way.
        val turn = hypot(yaw, pitch)
        when (head) {
            Head.AT_REST -> if (turn >= config.headStartDeg && raised == null) {
                headFresh = headQuiet.fresh(t, config.quietMs, QUIET_JUST_ENDED_MS) && !coolingDown
                head = Head.AWAY
                headStartMs = t
                peakYaw = yaw
                peakPitch = pitch
                peakMs = t
            }
            // Held away: a new position, not a nod. The rest follows it there (while the head is steady), and
            // meanwhile the eyebrows work: before, a head resting a few degrees off kept them blocked for good.
            Head.HELD -> if (turn < config.headReturnDeg) head = Head.AT_REST
            Head.AWAY -> {
                if (turn > hypot(peakYaw, peakPitch)) {
                    peakYaw = yaw
                    peakPitch = pitch
                    peakMs = t
                }
                when {
                    t - headStartMs > config.headMaxCycleMs -> head = Head.HELD
                    turn < config.headReturnDeg -> {
                        head = Head.AT_REST
                        val peak = hypot(peakYaw, peakPitch)
                        when {
                            peak < config.headPeakDeg -> Unit
                            peakMs - headStartMs > config.headMaxOutMs -> rejected = "head moved too slowly"
                            !headFresh -> rejected = "head was not steady first"
                            else -> emit(
                                if (abs(peakYaw) >= abs(peakPitch)) {
                                    if (peakYaw < 0) GestureType.FACE_LEFT else GestureType.FACE_RIGHT
                                } else {
                                    if (peakPitch < 0) GestureType.FACE_UP else GestureType.FACE_DOWN
                                },
                            )
                        }
                    }
                }
            }
        }

        // Eyebrows: which is up, debounced, then once / twice / held.
        val now = when {
            !frontal || head == Head.AWAY -> null
            eyes >= config.browMaxEyes -> raised // a blink: no change either way
            shape != null -> shape
            // Still up: above half its raise threshold (the release level), on each side that is raised.
            raised != null && (raised == Brow.RIGHT || left >= raiseLeft / 2) && (raised == Brow.LEFT || right >= raiseRight / 2) -> raised
            else -> null
        }
        if (now == candidate) {
            candidateFrames++
        } else {
            candidate = now
            candidateFrames = 1
        }
        val settled = if (candidateFrames >= config.browConfirmFrames) candidate else raised
        if (settled != raised) {
            raised?.let { ended -> endRaise(ended, t)?.let { emit(it) } }
            raised = settled
            if (settled != null) {
                raisedSinceMs = t
                // The second raise of a double comes too soon to rest in between: the waiting first one vouches for it.
                raisedFresh = (browsQuiet.fresh(t, config.quietMs, QUIET_JUST_ENDED_MS) || pendingSingle.containsKey(settled)) &&
                    !coolingDown && roseQuickly(settled, t)
                holding = false
            }
        }
        val up = raised
        if (up != null && up != Brow.BOTH && raisedFresh && t - raisedSinceMs >= config.browHoldMs) {
            // Held: the volume, a step now and every half second; this raise is no longer a single or a double.
            if (!holding || t - lastHoldStepMs >= config.browHoldRepeatMs) {
                holding = true
                lastHoldStepMs = t
                pendingSingle.remove(up)
                emit(if (up == Brow.LEFT) GestureType.BROW_LEFT_HOLD else GestureType.BROW_RIGHT_HOLD, cooldown = false)
            }
        }
        // A single raise is reported once a second one can no longer start.
        val expired = pendingSingle.entries.firstOrNull { (_, endedMs) -> t - endedMs > config.browDoubleGapMs }
        if (expired != null && raised != expired.key) {
            pendingSingle.remove(expired.key)
            when (expired.key) {
                Brow.LEFT -> emit(GestureType.BROW_LEFT)
                Brow.RIGHT -> emit(GestureType.BROW_RIGHT)
                Brow.BOTH -> Unit // both eyebrows once: deliberately nothing (surprise looks like it)
            }
        }

        // Both eyes closed, held, from open eyes.
        if (eyes >= config.eyesClosed && frontal) {
            if (eyesClosedSinceMs < 0) {
                eyesClosedSinceMs = t
                eyesFired = !eyesQuiet.fresh(t, config.quietMs, QUIET_JUST_ENDED_MS) || coolingDown
            }
            if (!eyesFired && t - eyesClosedSinceMs >= config.eyesHoldMs) {
                eyesFired = true
                emit(GestureType.EYES_CLOSED)
            }
        } else {
            eyesClosedSinceMs = -1
        }

        // Kiss, held, with the mouth closed, from relaxed lips.
        if (pucker >= config.kissPucker && f.jawOpen < config.kissMaxJaw && frontal) {
            if (puckerSinceMs < 0) {
                puckerSinceMs = t
                kissFired = !lipsQuiet.fresh(t, config.quietMs, QUIET_JUST_ENDED_MS) || coolingDown
            }
            if (!kissFired && t - puckerSinceMs >= config.kissHoldMs) {
                kissFired = true
                emit(GestureType.KISS)
            }
        } else {
            puckerSinceMs = -1
        }
        return Output(event, rejected, presence)
    }

    /** What an eyebrow resting at [neutral] must rise by: [base], or less if it rests too high to rise that far. */
    private fun raiseThreshold(neutral: Float, base: Float): Float =
        ((1f - neutral) * config.browHeadroomShare).coerceIn(config.browRaiseMin, base)

    /**
     * Adds an eyebrow's score to its neutral level, except while it is up and has been for less than
     * [FaceConfig.browSettleMs] (since [upSinceMs]). Returns when its current time up began, or -1 if it is not up.
     */
    private fun learnBrow(neutral: Neutral, score: Float, t: Long, upSinceMs: Long): Long {
        if (!neutral.known) {
            neutral.add(score)
            return -1
        }
        val up = score - neutral.value >= raiseThreshold(neutral.value, config.browRaise) / 2
        val since = if (!up) -1 else if (upSinceMs < 0) t else upSinceMs
        if (!up || t - since >= config.browSettleMs) neutral.add(score)
        return since
    }

    private fun roseQuickly(brow: Brow, t: Long): Boolean {
        fun quick(upSinceMs: Long) = upSinceMs >= 0 && t - upSinceMs <= config.browRiseMs
        return when (brow) {
            Brow.LEFT -> quick(browLeftUpSinceMs)
            Brow.RIGHT -> quick(browRightUpSinceMs)
            Brow.BOTH -> quick(browLeftUpSinceMs) && quick(browRightUpSinceMs)
        }
    }

    /** Forgets everything in progress (the screen turned, say): each part must be quiet again before its next gesture. */
    fun reset() {
        lastSeenMs = Long.MIN_VALUE / 2
        noFace(0)
        restYaw.known = false
        restPitch.known = false
    }

    /** A raise ended: a double if a short one ended just before, otherwise a single waits for a possible second. */
    private fun endRaise(brow: Brow, t: Long): GestureType? {
        val freshRaise = raisedFresh
        val wasHold = holding
        holding = false
        if (!freshRaise || wasHold || t - raisedSinceMs > config.browShortMaxMs) {
            pendingSingle.remove(brow)
            return null
        }
        val first = pendingSingle.remove(brow)
        if (first != null && raisedSinceMs - first <= config.browDoubleGapMs) {
            return when (brow) {
                Brow.LEFT -> GestureType.BROW_LEFT_TWICE
                Brow.RIGHT -> GestureType.BROW_RIGHT_TWICE
                Brow.BOTH -> GestureType.BROWS_TWICE
            }
        }
        pendingSingle[brow] = t
        return null
    }

    private fun noFace(t: Long): Output {
        if (t - lastSeenMs > config.lostMs) {
            // Lost: nothing half done survives, and each part must be quiet again before its next gesture.
            raised = null
            candidate = null
            candidateFrames = 0
            holding = false
            pendingSingle.clear()
            eyesClosedSinceMs = -1
            puckerSinceMs = -1
            head = Head.AT_REST
            browLeftUpSinceMs = -1
            browRightUpSinceMs = -1
            presence = Presence.NO_FACE
            browsQuiet.clear()
            eyesQuiet.clear()
            lipsQuiet.clear()
            headQuiet.clear()
        }
        return Output(null, null, presence)
    }

    private companion object {
        /** A quiet stretch still counts if it ended this recently: the gesture itself ended it. */
        const val QUIET_JUST_ENDED_MS = 600L
    }
}
