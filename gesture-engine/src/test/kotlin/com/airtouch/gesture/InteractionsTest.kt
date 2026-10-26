package com.airtouch.gesture

import com.airtouch.gesture.SyntheticHands.Shape
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The cursor, the pinch as a finger, and the fist as a volume knob, end to end through the engine. */
class InteractionsTest {

    private fun run(engine: GestureEngine = GestureEngine(), script: SyntheticHands.() -> Unit): List<GestureEngine.Output> =
        SyntheticHands().apply(script).build().map(engine::process)

    private fun List<GestureEngine.Output>.touches() = mapNotNull { it.touch }

    private fun List<GestureEngine.Output>.events() = mapNotNull { it.event?.type }

    /** Points long enough for the cursor to appear, from a resting open hand. */
    private fun SyntheticHands.showCursor() {
        rest(600)
        rest(1_000, Shape.POINT)
    }

    /** The recorded way to pinch: from pointing, the index bends down to the thumb, then opens again. */
    private fun SyntheticHands.quickPinch(holdMs: Long = 150) {
        morph(Shape.POINT, Shape.POINT_PINCH, 100)
        rest(holdMs, Shape.POINT_PINCH)
        morph(Shape.POINT_PINCH, Shape.POINT, 100)
    }

    // Cursor

    @Test
    fun pointingShowsTheCursorAtTheCentre() {
        val out = run { showCursor() }
        assertNotNull(out.last().pointer)
        val pointer = out.last().pointer!!
        assertEquals(0.5f, pointer.x, 0.02f)
        assertEquals(0.5f, pointer.y, 0.02f)
        assertTrue("no taps or gestures from pointing alone", out.touches().isEmpty() && out.events().isEmpty())
    }

    @Test
    fun aBriefPointDoesNotShowTheCursor() {
        val out = run { rest(600); rest(200, Shape.POINT); rest(600) }
        assertTrue(out.all { it.pointer == null })
    }

    @Test
    fun theCursorFollowsTheKnuckleInTheUsersView() {
        val right = run { showCursor(); move(0.05f, 0f, 500, Shape.POINT); rest(200, Shape.POINT) }.last().pointer!!
        assertTrue("moved right: ${right.x}", right.x > 0.7f)
        assertEquals(0.5f, right.y, 0.03f)
        val down = run { showCursor(); move(0f, 0.05f, 500, Shape.POINT); rest(200, Shape.POINT) }.last().pointer!!
        assertTrue("moved down: ${down.y}", down.y > 0.6f)
        assertEquals(0.5f, down.x, 0.03f)
    }

    @Test
    fun aSteadyHandKeepsTheCursorStill() {
        val out = run { showCursor(); rest(2_000, Shape.POINT) }
        val positions = out.takeLast(40).mapNotNull { it.pointer }
        assertEquals(40, positions.size)
        val spreadX = positions.maxOf { it.x } - positions.minOf { it.x }
        val spreadY = positions.maxOf { it.y } - positions.minOf { it.y }
        // 0.015 of the screen is about 16 px across and 35 px down on the Note 8 Pro.
        assertTrue("cursor shook by $spreadX × $spreadY", spreadX < 0.015f && spreadY < 0.015f)
    }

    @Test
    fun theCursorStopsAtTheEdgeAndComesStraightBack() {
        val out = run { showCursor(); move(0.2f, 0f, 600, Shape.POINT); rest(100, Shape.POINT); move(-0.02f, 0f, 200, Shape.POINT) }
        val atEdge = out.dropLast(4).last().pointer!!
        assertEquals(1f, atEdge.x, 0.01f)
        assertTrue("came back from the edge at once", out.last().pointer!!.x < 0.99f)
    }

    @Test
    fun theCursorFadesAfterPointingStops() {
        val out = run { showCursor(); rest(1_500) }
        assertNotNull("still shown after 1.5 s", out.last().pointer)
        assertNull("hidden after 2 s", run { showCursor(); rest(2_500) }.last().pointer)
    }

    @Test
    fun noCursorWhilePaused() {
        val out = run(GestureEngine().apply { paused = true }) { showCursor(); quickPinch(); rest(600, Shape.POINT) }
        assertTrue(out.all { it.pointer == null && it.touch == null })
    }

    @Test
    fun usingTheCursorAndOpeningAgainIsNotAFlick() {
        // Folding three fingers fast and opening them slowly would otherwise look like a flick down.
        val out = run { rest(600); morph(Shape.OPEN, Shape.POINT, 100); rest(1_000, Shape.POINT); morph(Shape.POINT, Shape.OPEN, 400); rest(600) }
        assertTrue("fired ${out.events()}", out.events().isEmpty())
    }

    @Test
    fun aFlickDownWhoseIndexFoldsLastStillCounts() {
        // Recorded: the other fingers fold first, so the hand looks like pointing for a moment.
        val out = run { rest(600); morph(Shape.OPEN, Shape.POINT, 100); morph(Shape.POINT, Shape.FIST, 100); morph(Shape.FIST, Shape.OPEN, 500); rest(600) }
        assertEquals(listOf(GestureType.FLICK_DOWN), out.events())
        assertTrue(out.all { it.pointer == null })
    }

    // Pinch at the cursor

    @Test
    fun aQuickPinchAtTheCursorTapsWhereItPointed() {
        val out = run { showCursor(); quickPinch(); rest(800, Shape.POINT) }
        val taps = out.touches()
        assertEquals(1, taps.size)
        val tap = taps.single() as Touch.Tap
        assertEquals(0.5f, tap.x, 0.02f)
        assertEquals(0.5f, tap.y, 0.02f)
    }

    @Test
    fun theTapWaitsBrieflyForASecondPinch() {
        val out = run { showCursor(); quickPinch(); rest(800, Shape.POINT) }
        // Frames are 50 ms apart; the pinch is held until 600 + 1000 + 100 + 150 ms.
        val heldUntilFrame = (600 + 1_000 + 100 + 150) / 50
        val tapFrame = out.indexOfFirst { it.touch is Touch.Tap }
        assertTrue("tap ${tapFrame - heldUntilFrame} frames after the pinch", tapFrame - heldUntilFrame in 5..12)
    }

    @Test
    fun twoQuickPinchesAreOneDoubleTap() {
        val out = run { showCursor(); quickPinch(); rest(100, Shape.POINT); quickPinch(); rest(800, Shape.POINT) }
        val touches = out.touches()
        assertEquals(listOf(Touch.DoubleTap::class), touches.map { it::class })
    }

    @Test
    fun onlyAPinchStraightFromPointingActsAtTheCursor() {
        // The cursor is still shown, but the hand opened (to flick, say) before pinching.
        val out = run { showCursor(); rest(800); rest(200, Shape.PINCH); rest(400) }
        assertNotNull("cursor still shown", out.dropLast(10).last().pointer)
        assertTrue("no tap at the cursor: ${out.touches()}", out.touches().isEmpty())
    }

    @Test
    fun twoSlowPinchesAreTwoTaps() {
        val out = run { showCursor(); quickPinch(); rest(800, Shape.POINT); quickPinch(); rest(800, Shape.POINT) }
        assertEquals(listOf(Touch.Tap::class, Touch.Tap::class), out.touches().map { it::class })
    }

    @Test
    fun aPinchHeldForASecondStillTaps() {
        // Live test: pinches held 0.6–1.3 s were meant as taps.
        val out = run { showCursor(); quickPinch(holdMs = 1_100); rest(800, Shape.POINT) }
        assertEquals(listOf(Touch.Tap::class), out.touches().map { it::class })
    }

    @Test
    fun aHeldPinchLongPressesAndLiftsOnRelease() {
        val out = run { showCursor(); quickPinch(holdMs = 1_800); rest(800, Shape.POINT) }
        val touches = out.touches()
        assertEquals(listOf(Touch.Down::class, Touch.Up::class), touches.map { it::class })
        val down = touches.first() as Touch.Down
        assertTrue("a long-press goes down at once", down.hold)
        assertEquals(0.5f, down.x, 0.02f)
        assertTrue("the ring filled while holding", out.any { (it.pointer?.holdProgress ?: 0f) > 0.5f })
    }

    @Test
    fun pinchAndMoveDragsFromWhereItPointed() {
        val out = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            rest(200, Shape.POINT_PINCH)
            move(0f, 0.06f, 500, Shape.POINT_PINCH)
            rest(200, Shape.POINT_PINCH)
            morph(Shape.POINT_PINCH, Shape.POINT, 100)
            rest(600, Shape.POINT)
        }
        val touches = out.touches()
        assertTrue("starts with a finger down: $touches", touches.first() is Touch.Down)
        assertTrue("a drag touches down only once it moves", !(touches.first() as Touch.Down).hold)
        assertEquals(Touch.Up, touches.last())
        val moves = touches.filterIsInstance<Touch.Move>()
        assertTrue("several moves", moves.size >= 4)
        assertTrue("moves only downwards, give or take a shake", moves.zipWithNext().all { (a, b) -> b.y >= a.y - 0.01f })
        assertTrue("went well down: ${moves.last().y}", moves.last().y > 0.6f)
        assertTrue("no tap as well", touches.none { it is Touch.Tap })
    }

    @Test
    fun losingTheHandMidDragLiftsTheFingerOnce() {
        val out = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            move(0f, 0.06f, 500, Shape.POINT_PINCH)
            absent(1_000)
        }
        val touches = out.touches()
        assertEquals(1, touches.count { it == Touch.Up })
        assertEquals(Touch.Up, touches.last())
        // The hand vanishes at frame (1600 + 100 + 500) / 50 = 44; the finger lifts 300–500 ms later.
        val upFrame = out.indexOfFirst { it.touch == Touch.Up }
        assertTrue("lifted ${upFrame - 44} frames after the hand went", upFrame - 44 in 5..10)
    }

    @Test
    fun aDropoutMidDragKeepsTheFingerDownAndTheDragGoesOn() {
        // A fast hand blurs out for a frame or two. Live (25 Oct 2026), that started a second drag over the first,
        // which never lifted, and every later action waited for it.
        val touches = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            move(0f, 0.04f, 400, Shape.POINT_PINCH)
            absent(100)
            move(0f, 0.04f, 400, Shape.POINT_PINCH)
            morph(Shape.POINT_PINCH, Shape.POINT, 100)
            rest(600, Shape.POINT)
        }.touches()
        assertEquals("one finger, down once and up once: $touches", 1, touches.count { it is Touch.Down })
        assertEquals(1, touches.count { it == Touch.Up })
        assertEquals(Touch.Up, touches.last())
        assertTrue("went on moving after the dropout", touches.filterIsInstance<Touch.Move>().last().y > 0.62f)
    }

    @Test
    fun aDropoutMidDragThenAnOpenHandLiftsTheFinger() {
        val out = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            move(0f, 0.06f, 500, Shape.POINT_PINCH)
            absent(100)
            rest(600, Shape.POINT)
        }
        assertEquals(listOf(Touch.Up), out.touches().dropWhile { it !== Touch.Up })
        assertEquals(1, out.touches().count { it is Touch.Down })
    }

    @Test
    fun aResetMidDragLiftsTheFingerWithTheNextOutput() {
        val engine = GestureEngine()
        val frames = SyntheticHands().apply {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            move(0f, 0.06f, 500, Shape.POINT_PINCH)
            rest(600, Shape.POINT_PINCH)
        }.build()
        val cut = frames.size - 10
        assertTrue(frames.take(cut).map(engine::process).touches().first() is Touch.Down)
        engine.reset() // the screen turned, say
        assertEquals(Touch.Up, engine.process(frames[cut]).touch)
    }

    @Test
    fun everyFingerThatGoesDownComesUpWhateverTheHandDoes() {
        // Random hands: shapes, moves, pinches, zooms and dropouts of every length, then the hand goes away.
        val shapes = listOf(Shape.OPEN, Shape.POINT, Shape.PINCH, Shape.POINT_PINCH, Shape.FIST)
        repeat(300) { seed ->
            val random = kotlin.random.Random(seed)
            val frames = SyntheticHands().apply {
                showCursor()
                repeat(25) {
                    val shape = shapes[random.nextInt(shapes.size)]
                    val ms = 50L * random.nextInt(1, 12)
                    when (random.nextInt(5)) {
                        0 -> rest(ms, shape)
                        1 -> move(random.nextFloat() * 0.1f - 0.05f, random.nextFloat() * 0.1f - 0.05f, ms, shape)
                        2 -> morph(Shape.POINT, Shape.POINT_PINCH, 100)
                        3 -> approach(0.7f + random.nextFloat() * 0.7f, ms, shape)
                        else -> absent(50L * random.nextInt(1, 14))
                    }
                }
                absent(1_000)
            }.build()
            var down = false
            for ((i, touch) in frames.map(GestureEngine()::process).mapNotNull { it.touch }.withIndex()) {
                when (touch) {
                    is Touch.Down, is Touch.ZoomStart -> {
                        assertTrue("seed $seed, touch $i: $touch while a finger was already down", !down)
                        down = true
                    }
                    Touch.Up, Touch.ZoomEnd -> {
                        assertTrue("seed $seed, touch $i: $touch with no finger down", down)
                        down = false
                    }
                    is Touch.Move, is Touch.Zoom -> assertTrue("seed $seed, touch $i: $touch with no finger down", down)
                    is Touch.Tap, is Touch.DoubleTap -> Unit
                }
            }
            assertTrue("seed $seed: a finger was left down", !down)
        }
    }

    @Test
    fun pausingMidDragLiftsTheFinger() {
        val engine = GestureEngine()
        val frames = SyntheticHands().apply {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            move(0f, 0.06f, 500, Shape.POINT_PINCH)
            rest(600, Shape.POINT_PINCH)
        }.build()
        val cut = frames.size - 10
        val before = frames.take(cut).map(engine::process)
        assertTrue(before.touches().first() is Touch.Down)
        engine.paused = true
        val after = frames.drop(cut).map(engine::process)
        assertEquals(listOf(Touch.Up), after.touches())
        assertTrue(after.all { it.pointer == null })
    }

    @Test
    fun pullingThePinchedHandCloserZoomsInAndPushingItAwayZoomsOut() {
        val zoomIn = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            rest(150, Shape.POINT_PINCH)
            approach(1.4f, 600, Shape.POINT_PINCH)
            morph(Shape.POINT_PINCH, Shape.POINT, 100)
            rest(400, Shape.POINT)
        }.touches()
        assertTrue("starts zooming: $zoomIn", zoomIn.first() is Touch.ZoomStart)
        assertEquals(Touch.ZoomEnd, zoomIn.last())
        val factors = zoomIn.filterIsInstance<Touch.Zoom>().map { it.factor }
        assertTrue("zoomed in: $factors", factors.last() > 1.6f)
        assertTrue("no drag or tap mixed in", zoomIn.none { it is Touch.Down || it is Touch.Tap })

        val zoomOut = run {
            showCursor()
            morph(Shape.POINT, Shape.POINT_PINCH, 100)
            rest(150, Shape.POINT_PINCH)
            approach(0.7f, 600, Shape.POINT_PINCH)
            morph(Shape.POINT_PINCH, Shape.POINT, 100)
            rest(400, Shape.POINT)
        }.touches()
        assertTrue(zoomOut.first() is Touch.ZoomStart)
        assertTrue("zoomed out", zoomOut.filterIsInstance<Touch.Zoom>().last().factor < 0.6f)
    }

    // Pinch without the cursor (an open-hand pinch)

    @Test
    fun anOpenHandPinchAndMoveDragsFromTheCentre() {
        val out = run { rest(600); rest(400, Shape.PINCH); move(0f, 0.08f, 500, Shape.PINCH); rest(300, Shape.PINCH); rest(600) }
        val touches = out.touches()
        assertEquals(Touch.Down(0.5f, 0.5f), touches.first())
        assertEquals(Touch.Up, touches.last())
        assertTrue("dragged down", touches.filterIsInstance<Touch.Move>().last().y > 0.55f)
        assertTrue("moving is not also a tap", out.events().isEmpty())
    }

    @Test
    fun aCentreDragStaysInsideTheSafeZone() {
        val out = run { rest(600); rest(400, Shape.PINCH); move(0f, 0.4f, 800, Shape.PINCH); rest(300, Shape.PINCH); rest(600) }
        val moves = out.touches().filterIsInstance<Touch.Move>()
        assertTrue(moves.all { it.y <= 0.85f + 1e-4f && it.x in 0.12f..0.88f })
    }

    @Test
    fun twoQuickOpenHandPinchesDoubleTapTheCentre() {
        val out = run { rest(600); rest(200, Shape.PINCH); rest(200); rest(200, Shape.PINCH); rest(600) }
        assertEquals(listOf(GestureType.DOUBLE_PINCH), out.events())
        assertTrue(out.touches().isEmpty())
    }

    @Test
    fun pinchesTooFarApartAreNotADoublePinch() {
        assertTrue(run { rest(600); rest(200, Shape.PINCH); rest(800); rest(200, Shape.PINCH); rest(600) }.events().isEmpty())
    }

    @Test
    fun aSingleQuickPinchDoesNothingWithoutTheCursor() {
        val out = run { rest(600); rest(200, Shape.PINCH); rest(1_000) }
        assertTrue(out.events().isEmpty() && out.touches().isEmpty())
    }

    @Test
    fun flicksThatPinchOnTheWayAreStillFlicksAndNeverADoublePinch() {
        // Recorded flicks up brought thumb and index together for a frame or two on the way down.
        val out = run {
            rest(600)
            repeat(2) {
                morph(Shape.OPEN, Shape.PINCH, 100)
                morph(Shape.PINCH, Shape.FOLD, 300)
                morph(Shape.FOLD, Shape.OPEN, 150)
                rest(300)
            }
            rest(600)
        }
        assertTrue("fired ${out.events()}", GestureType.DOUBLE_PINCH !in out.events())
        assertTrue(out.touches().isEmpty())
    }

    @Test
    fun anOpenHandPinchPulledCloserZoomsAtTheCentre() {
        val touches = run { rest(600); rest(300, Shape.PINCH); approach(1.4f, 500, Shape.PINCH); rest(200, Shape.PINCH); rest(600) }.touches()
        assertEquals(Touch.ZoomStart(0.5f, 0.5f), touches.first())
        assertEquals(Touch.ZoomEnd, touches.last())
    }

    @Test
    fun anUnarmedPinchDoesNotDragOrZoom() {
        // The hand arrives already pinching and moving: nothing may happen.
        val out = run { rest(200, Shape.PINCH); move(0f, 0.08f, 500, Shape.PINCH); approach(1.4f, 400, Shape.PINCH); rest(600) }
        assertTrue(out.touches().isEmpty())
    }

    // Volume knob

    @Test
    fun raisingAFistTurnsTheVolumeUpStepByStep() {
        val out = run { rest(600); rest(700, Shape.FIST); move(0f, -0.12f, 600, Shape.FIST); rest(300, Shape.FIST); rest(600) }
        val events = out.events()
        assertEquals(List(3) { GestureType.FIST_RAISE }, events)
    }

    @Test
    fun loweringAFistTurnsTheVolumeDown() {
        val out = run { rest(600); rest(700, Shape.FIST); move(0f, 0.08f, 500, Shape.FIST); rest(300, Shape.FIST); rest(600) }
        assertEquals(List(2) { GestureType.FIST_LOWER }, out.events())
    }

    @Test
    fun aFistMovedSidewaysNeitherTurnsTheVolumeNorPlays() {
        val out = run { rest(600); rest(700, Shape.FIST); move(0.1f, 0f, 500, Shape.FIST); rest(1_500, Shape.FIST); rest(600) }
        assertTrue("fired ${out.events()}", out.events().isEmpty())
    }

    @Test
    fun aStillFistStillPlaysOrPauses() {
        assertEquals(listOf(GestureType.FIST), run { rest(600); rest(2_000, Shape.FIST); rest(600) }.events())
    }

    @Test
    fun theKnobIgnoresTheFirstMomentOfTheFist() {
        // Moving while making the fist is not turning: the reference is taken once the fist has settled.
        val out = run { rest(600); move(0f, -0.04f, 200, Shape.FIST); rest(200, Shape.FIST); rest(600) }
        assertTrue(GestureType.FIST_RAISE !in out.events())
    }

    @Test
    fun pausedEngineIgnoresTheKnob() {
        val out = run(GestureEngine().apply { paused = true }) { rest(600); rest(700, Shape.FIST); move(0f, -0.12f, 600, Shape.FIST); rest(600) }
        assertTrue(out.events().isEmpty())
    }

    // New held shapes

    @Test
    fun rockAndCallFireOnceWhenHeld() {
        assertEquals(listOf(GestureType.ROCK), run { rest(600); rest(2_000, Shape.ROCK); rest(600) }.events())
        assertEquals(listOf(GestureType.CALL), run { rest(600); rest(2_000, Shape.CALL); rest(600) }.events())
        assertTrue(run { rest(600); rest(600, Shape.ROCK); rest(600); rest(600, Shape.CALL); rest(600) }.events().isEmpty())
    }

    @Test
    fun steeringTheCursorIsNeverASwipe() {
        val out = run { showCursor(); move(0f, -0.15f, 300, Shape.POINT); rest(300, Shape.POINT); move(0f, 0.15f, 300, Shape.POINT); rest(600) }
        assertTrue("fired ${out.events()}", out.events().isEmpty())
        assertTrue(out.mapNotNull { it.rejected }.none { it.startsWith("swipe") || it.startsWith("hand not open") })
    }

    @Test
    fun theCursorDoesNotDriftWhileHoldingALongPress() {
        val out = run { showCursor(); quickPinch(holdMs = 2_200); rest(400, Shape.POINT) }
        val moves = out.touches().filterIsInstance<Touch.Move>()
        assertTrue("a still hand moved the pressed finger: $moves", moves.isEmpty())
        val first = out.first { it.pointer != null }.pointer!!
        assertTrue(abs(out.last().pointer!!.x - first.x) < 0.02f)
    }
}
