package com.airtouch.gesture

import com.airtouch.gesture.SyntheticHands.Shape
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.HandInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** While a call rings: an open hand answers only when opened after the ringing began; a fist still fires. */
class CallModeTest {

    /** Runs [before] with no call, then [during] with the call ringing. */
    private fun fired(engine: GestureEngine = GestureEngine(), before: SyntheticHands.() -> Unit = {}, during: SyntheticHands.() -> Unit): List<GestureType> {
        val hands = SyntheticHands()
        hands.before()
        val split = hands.build().size
        hands.during()
        val frames: List<HandInput> = hands.build()
        return frames.mapIndexedNotNull { i, frame ->
            engine.callRinging = i >= split
            engine.process(frame).event?.type
        }
    }

    @Test
    fun aHandShownAfterTheCallStartsAndHeldOpenAnswers() {
        assertEquals(listOf(GestureType.OPEN_PALM), fired(during = { absent(300); rest(2_200) }))
    }

    @Test
    fun aHandAlreadyOpenWhenTheCallStartsDoesNotAnswer() {
        // The resting pose between gestures: answering from it would pick up calls by accident.
        assertTrue(fired(before = { rest(1_000) }, during = { rest(3_000) }).isEmpty())
    }

    @Test
    fun closingAndReopeningTheHandAnswers() {
        val types = fired(before = { rest(1_000) }, during = { rest(500); rest(400, Shape.FIST); rest(2_200) })
        assertEquals(listOf(GestureType.OPEN_PALM), types)
    }

    @Test
    fun aMovingOpenHandDoesNotAnswer() {
        assertTrue(fired(during = { absent(300); repeat(6) { move(0.03f, 0f, 250); move(-0.03f, 0f, 250) } }).isEmpty())
    }

    @Test
    fun aFistStillFiresDuringACall() {
        assertEquals(listOf(GestureType.FIST), fired(before = { rest(600) }, during = { rest(1_500, Shape.FIST); rest(300) }))
    }

    @Test
    fun withoutACallAnOpenHandIsJustRest() {
        assertTrue(fired(before = { absent(300); rest(4_000) }, during = {}).isEmpty())
    }

    @Test
    fun aPausedEngineDoesNotAnswer() {
        assertTrue(fired(GestureEngine().apply { paused = true }, during = { absent(300); rest(2_500) }).isEmpty())
    }
}
