package com.airtouch.gesture.face

import com.airtouch.gesture.model.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Builds face streams at the face tracker's rate (about 10 frames a second), with small natural wobble. */
private class SyntheticFace(neutralBrow: Float = 0.05f, private val frameMs: Long = 100) {

    private val neutral = FaceFrame(
        browUpLeft = neutralBrow,
        browUpRight = neutralBrow,
        eyeClosedLeft = 0.1f,
        eyeClosedRight = 0.1f,
        pucker = 0.05f,
        jawOpen = 0.05f,
        yaw = 0f,
        pitch = 30f,
    )
    private var t = 0L
    private val frames = mutableListOf<FaceInput>()
    private var head = neutral

    fun build(): List<FaceInput> = frames.toList()

    fun relax(ms: Long) = hold(ms) { this }

    /** Holds an expression (a change of the neutral face) for [ms], the head staying where it is. */
    fun hold(ms: Long, expression: FaceFrame.() -> FaceFrame) =
        repeat((ms / frameMs).toInt()) { emit(neutral.expression().copy(yaw = head.yaw, pitch = head.pitch)) }

    /** Turns the head to ([yaw], [pitch]) relative to rest over [ms], at an even pace. */
    fun turn(yaw: Float, pitch: Float, ms: Long) {
        val steps = (ms / frameMs).toInt()
        val from = head
        for (k in 1..steps) {
            val share = k.toFloat() / steps
            head = neutral.copy(yaw = from.yaw + (yaw - from.yaw) * share, pitch = from.pitch + (neutral.pitch + pitch - from.pitch) * share)
            emit(head)
        }
    }

    fun absent(ms: Long) = repeat((ms / frameMs).toInt()) {
        frames += FaceInput(t, null)
        t += frameMs
    }

    private fun emit(face: FaceFrame) {
        // Blendshape scores wobble by about 0.01 and head angles by a fraction of a degree.
        val w = sin(t / 53.0).toFloat()
        frames += FaceInput(
            t,
            face.copy(
                browUpLeft = face.browUpLeft + 0.01f * w,
                browUpRight = face.browUpRight - 0.01f * w,
                yaw = face.yaw + 0.3f * w,
                pitch = face.pitch + 0.3f * w,
            ),
        )
        t += frameMs
    }
}

class FaceEngineTest {

    private fun fired(engine: FaceEngine = FaceEngine(), neutralBrow: Float = 0.05f, script: SyntheticFace.() -> Unit): List<GestureType> =
        SyntheticFace(neutralBrow).apply(script).build().mapNotNull { engine.process(it).event?.type }

    private val leftUp: FaceFrame.() -> FaceFrame = { copy(browUpLeft = 0.55f, browUpRight = 0.12f) }
    private val rightUp: FaceFrame.() -> FaceFrame = { copy(browUpLeft = 0.12f, browUpRight = 0.55f) }
    private val bothUp: FaceFrame.() -> FaceFrame = { copy(browUpLeft = 0.55f, browUpRight = 0.55f) }

    // Eyebrows

    @Test
    fun oneEyebrowOnceIsBackOrPlayPause() {
        assertEquals(listOf(GestureType.BROW_LEFT), fired { relax(1_000); hold(400, leftUp); relax(1_500) })
        assertEquals(listOf(GestureType.BROW_RIGHT), fired { relax(1_000); hold(400, rightUp); relax(1_500) })
    }

    @Test
    fun oneEyebrowTwiceIsOneDoubleAndNoSingles() {
        assertEquals(listOf(GestureType.BROW_LEFT_TWICE), fired { relax(1_000); hold(400, leftUp); relax(300); hold(400, leftUp); relax(1_500) })
        assertEquals(listOf(GestureType.BROW_RIGHT_TWICE), fired { relax(1_000); hold(400, rightUp); relax(300); hold(400, rightUp); relax(1_500) })
    }

    @Test
    fun bothEyebrowsTwiceIsTheScreenshotAndOnceIsNothing() {
        assertEquals(listOf(GestureType.BROWS_TWICE), fired { relax(1_000); hold(400, bothUp); relax(300); hold(400, bothUp); relax(1_500) })
        assertTrue("surprise is not a gesture", fired { relax(1_000); hold(400, bothUp); relax(1_500) }.isEmpty())
    }

    @Test
    fun oneEyebrowHeldStepsTheVolumeAndIsNoSingleAfterwards() {
        val left = fired { relax(1_000); hold(2_500, leftUp); relax(1_500) }
        assertTrue("steps: $left", left.size in 3..5 && left.all { it == GestureType.BROW_LEFT_HOLD })
        val right = fired { relax(1_000); hold(2_500, rightUp); relax(1_500) }
        assertTrue("steps: $right", right.size in 3..5 && right.all { it == GestureType.BROW_RIGHT_HOLD })
    }

    @Test
    fun oneSidedEvenWhenTheOtherEyebrowRisesALittle() {
        // Few people can keep the other eyebrow perfectly still.
        assertEquals(listOf(GestureType.BROW_LEFT), fired { relax(1_000); hold(400) { copy(browUpLeft = 0.55f, browUpRight = 0.32f) }; relax(1_500) })
    }

    @Test
    fun raisesTooFarApartAreTwoSingles() {
        assertEquals(listOf(GestureType.BROW_LEFT, GestureType.BROW_LEFT), fired { relax(1_000); hold(400, leftUp); relax(1_500); hold(400, leftUp); relax(1_500) })
    }

    @Test
    fun aHigherNeutralEyebrowIsMeasuredFromItself() {
        assertEquals(listOf(GestureType.BROW_LEFT), fired(neutralBrow = 0.2f) { relax(2_000); hold(400) { copy(browUpLeft = 0.6f, browUpRight = 0.25f) }; relax(1_500) })
        // Its resting level alone is no raise.
        assertTrue(fired(neutralBrow = 0.2f) { relax(5_000) }.isEmpty())
    }

    // Eyes

    @Test
    fun eyesClosedForASecondIsHome() {
        assertEquals(listOf(GestureType.EYES_CLOSED), fired { relax(1_000); hold(1_300) { copy(eyeClosedLeft = 0.85f, eyeClosedRight = 0.85f) }; relax(1_000) })
    }

    @Test
    fun blinksAndShortClosingsAreNothing() {
        assertTrue(fired { relax(1_000); repeat(6) { hold(200) { copy(eyeClosedLeft = 0.9f, eyeClosedRight = 0.9f) }; relax(2_000) } }.isEmpty())
        assertTrue(fired { relax(1_000); hold(700) { copy(eyeClosedLeft = 0.85f, eyeClosedRight = 0.85f) }; relax(1_000) }.isEmpty())
        // One eye closed is a wink, not this gesture.
        assertTrue(fired { relax(1_000); hold(1_500) { copy(eyeClosedLeft = 0.85f) }; relax(1_000) }.isEmpty())
    }

    @Test
    fun aBlinkDoesNotSpoilTheNextGesture() {
        assertEquals(listOf(GestureType.BROW_LEFT), fired { relax(1_000); hold(200) { copy(eyeClosedLeft = 0.9f, eyeClosedRight = 0.9f) }; hold(400, leftUp); relax(1_500) })
    }

    // Kiss

    @Test
    fun aHeldKissLikes() {
        assertEquals(listOf(GestureType.KISS), fired { relax(1_000); hold(700) { copy(pucker = 0.7f) }; relax(1_000) })
    }

    @Test
    fun talkingIsNoKiss() {
        // "oo" while talking: brief, and the jaw moves.
        assertTrue(fired { relax(1_000); repeat(5) { hold(200) { copy(pucker = 0.6f) }; relax(300) }; relax(1_000) }.isEmpty())
        assertTrue(fired { relax(1_000); hold(800) { copy(pucker = 0.6f, jawOpen = 0.5f) }; relax(1_000) }.isEmpty())
    }

    // Head

    @Test
    fun aSmallQuickNodOrTurnAndBackGoesThatWay() {
        assertEquals(listOf(GestureType.FACE_UP), fired { relax(1_000); turn(0f, -16f, 300); turn(0f, 0f, 300); relax(1_000) })
        assertEquals(listOf(GestureType.FACE_DOWN), fired { relax(1_000); turn(0f, 16f, 300); turn(0f, 0f, 300); relax(1_000) })
        assertEquals(listOf(GestureType.FACE_LEFT), fired { relax(1_000); turn(-16f, 0f, 300); turn(0f, 0f, 300); relax(1_000) })
        assertEquals(listOf(GestureType.FACE_RIGHT), fired { relax(1_000); turn(16f, 0f, 300); turn(0f, 0f, 300); relax(1_000) })
    }

    @Test
    fun lookingAroundIsNoNod() {
        // Slowly, or turned and held, or too little.
        assertTrue(fired { relax(1_000); turn(-20f, 0f, 1_200); turn(0f, 0f, 1_200); relax(1_000) }.isEmpty())
        assertTrue(fired { relax(1_000); turn(-20f, 0f, 300); relax(2_000) }.isEmpty())
        assertTrue(fired { relax(1_000); turn(0f, -8f, 300); turn(0f, 0f, 300); relax(1_000) }.isEmpty())
    }

    // Guards

    @Test
    fun aFaceThatArrivesMidExpressionDoesNothing() {
        assertTrue(fired { absent(500); hold(400, leftUp); relax(1_500) }.isEmpty())
    }

    @Test
    fun pausedReportsNothing() {
        assertTrue(fired(FaceEngine().apply { paused = true }) { relax(1_000); hold(400, leftUp); relax(1_500); hold(700) { copy(pucker = 0.7f) }; relax(1_000) }.isEmpty())
    }

    @Test
    fun losingTheFaceStopsAHoldAndDropsAWaitingSingle() {
        val steps = fired { relax(1_000); hold(1_200, leftUp); absent(1_500); relax(1_500) }
        assertTrue("only the steps before the face went: $steps", steps.isNotEmpty() && steps.all { it == GestureType.BROW_LEFT_HOLD } && steps.size <= 2)
        assertTrue(fired { relax(1_000); hold(400, leftUp); absent(1_500) }.isEmpty())
    }

    @Test
    fun eyebrowsThatSettleHigherThanAtTheStartStillWork() {
        // Live test: the neutral level learned in the first second sat 0.1–0.2 below where the eyebrows then rested.
        val fired = fired {
            relax(1_000)
            hold(6_000) { copy(browUpLeft = 0.2f, browUpRight = 0.2f) }
            hold(400) { copy(browUpLeft = 0.6f, browUpRight = 0.3f) }
            hold(1_500) { copy(browUpLeft = 0.2f, browUpRight = 0.2f) }
        }
        assertEquals(listOf(GestureType.BROW_LEFT), fired)
    }

    @Test
    fun eyebrowsMovingWhileTalkingAreNothing() {
        assertTrue(fired { relax(1_000); repeat(20) { hold(200) { copy(browUpLeft = 0.2f, browUpRight = 0.17f) }; relax(200) } }.isEmpty())
    }

    @Test
    fun aKissCountsFromLipsThatRestAboveNeutral() {
        // Live, 25 Oct 2026: at rest the lips read up to +0.16 above neutral; the kiss itself +0.6 to +0.7.
        val fired = fired {
            relax(1_000)
            repeat(8) { hold(200) { copy(pucker = 0.21f) }; relax(200) }
            hold(800) { copy(pucker = 0.9f) }
            relax(1_000)
        }
        assertEquals(listOf(GestureType.KISS), fired)
    }

    @Test
    fun aRaiseCountsFromEyebrowsThatWobbleAtRest() {
        // Live: resting eyebrows wobbled up to +0.2; a right raise read +0.38 with the left at +0.02.
        val fired = fired {
            relax(1_000)
            repeat(6) { hold(200) { copy(browUpLeft = 0.22f, browUpRight = 0.25f) }; relax(200) }
            hold(400) { copy(browUpLeft = 0.07f, browUpRight = 0.43f) }
            relax(1_500)
        }
        assertEquals(listOf(GestureType.BROW_RIGHT), fired)
    }

    // The right eyebrow as measured live (25 Oct 2026): both resting near 0.58, one-sided raises of +0.19 to +0.23
    // with the left eyebrow at or just below neutral.
    private val liveRest: FaceFrame.() -> FaceFrame = { copy(browUpLeft = 0.58f, browUpRight = 0.58f) }
    private val liveRightUp: FaceFrame.() -> FaceFrame = { copy(browUpLeft = 0.56f, browUpRight = 0.78f) }

    @Test
    fun aRightRaiseAsSmallAsMeasuredLiveCounts() {
        assertEquals(listOf(GestureType.BROW_RIGHT), fired { hold(3_000, liveRest); hold(400, liveRightUp); hold(1_500, liveRest) })
        assertEquals(
            listOf(GestureType.BROW_RIGHT_TWICE),
            fired { hold(3_000, liveRest); hold(400, liveRightUp); hold(300, liveRest); hold(400, liveRightUp); hold(1_500, liveRest) },
        )
        val held = fired { hold(3_000, liveRest); hold(2_500, liveRightUp); hold(1_500, liveRest) }
        assertTrue("steps: $held", held.size in 3..5 && held.all { it == GestureType.BROW_RIGHT_HOLD })
    }

    @Test
    fun anEyebrowThatRestsHighNeedsLessToRaise() {
        // Scores stop at 1: an eyebrow resting at 0.76 cannot rise by much.
        val fired = fired {
            hold(3_000) { copy(browUpRight = 0.76f) }
            hold(400) { copy(browUpLeft = 0.03f, browUpRight = 0.92f) }
            hold(1_500) { copy(browUpRight = 0.76f) }
        }
        assertEquals(listOf(GestureType.BROW_RIGHT), fired)
    }

    @Test
    fun triesThatFallShortDoNotLiftTheNeutralLevel() {
        // Live: tries too small to count, one after another, lifted the right eyebrow's neutral level from 0.58 to
        // 0.79 within seconds, and then even a good raise could not reach the threshold.
        val fired = fired {
            hold(3_000, liveRest)
            repeat(8) { hold(1_000) { copy(browUpLeft = 0.58f, browUpRight = 0.70f) }; hold(300, liveRest) }
            hold(400, liveRightUp)
            hold(1_500, liveRest)
        }
        assertEquals(listOf(GestureType.BROW_RIGHT), fired)
    }

    @Test
    fun anEyebrowThatDriftsUpSlowlyIsNoRaiseAndBecomesTheNewRest() {
        val fired = fired {
            relax(1_000)
            for (k in 1..30) hold(100) { copy(browUpLeft = 0.05f + 0.25f * k / 30) }
            hold(8_000) { copy(browUpLeft = 0.3f) }
            hold(400) { copy(browUpLeft = 0.65f) }
            hold(1_500) { copy(browUpLeft = 0.3f) }
        }
        assertEquals("only the raise made from the new level", listOf(GestureType.BROW_LEFT), fired)
    }

    @Test
    fun aHeadRestingAFewDegreesOffStillAllowsEyebrows() {
        // Live, the head often sat 7–24° from where it had rested, and that blocked the eyebrows for good.
        val fired = fired { relax(1_000); turn(12f, 0f, 300); relax(3_000); hold(400, rightUp); relax(1_500) }
        assertEquals(listOf(GestureType.BROW_RIGHT), fired)
    }

    @Test
    fun aBlinkNeitherStartsNorEndsARaise() {
        // Blinking moves the eyebrow scores too.
        assertTrue(fired { relax(1_000); repeat(5) { hold(300) { copy(eyeClosedLeft = 0.9f, eyeClosedRight = 0.9f, browUpRight = 0.3f) }; relax(1_500) } }.isEmpty())
        val held = fired {
            relax(1_000)
            hold(1_000, leftUp)
            hold(200) { copy(browUpLeft = 0.1f, browUpRight = 0.1f, eyeClosedLeft = 0.9f, eyeClosedRight = 0.9f) }
            hold(1_000, leftUp)
            relax(1_500)
        }
        assertTrue("one hold, no single after the blink: $held", held.size >= 3 && held.all { it == GestureType.BROW_LEFT_HOLD })
    }

    @Test
    fun theEngineSaysWhetherFaceGesturesCanBeMade() {
        val engine = FaceEngine()
        fun presence(script: SyntheticFace.() -> Unit) = SyntheticFace().apply(script).build().map(engine::process).last().presence
        assertEquals(FaceEngine.Presence.READY, presence { relax(1_000) })
        engine.reset()
        assertEquals(FaceEngine.Presence.TURNED_AWAY, presence { relax(1_000); turn(40f, 0f, 300) })
        engine.reset()
        assertEquals(FaceEngine.Presence.NO_FACE, presence { relax(1_000); absent(1_000) })
    }

    @Test
    fun aRelaxedFaceForAMinuteDoesNothing() {
        assertTrue(fired { relax(60_000) }.isEmpty())
    }
}
