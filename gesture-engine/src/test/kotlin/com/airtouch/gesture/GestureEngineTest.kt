package com.airtouch.gesture

import com.airtouch.gesture.SyntheticHands.Shape
import com.airtouch.gesture.features.HandFeatures
import com.airtouch.gesture.features.Pose
import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private fun events(engine: GestureEngine = GestureEngine(), script: SyntheticHands.() -> Unit): List<GestureEvent> {
        val frames = SyntheticHands().apply(script).build()
        return frames.mapNotNull { engine.process(it).event }
    }

    private fun List<GestureEvent>.types() = map { it.type }

    @Test
    fun swipeUpFiresOnceAndItsReturnStrokeDoesNot() {
        val fired = events {
            rest(600)
            move(0f, -0.35f, 250)
            rest(400)
            move(0f, 0.35f, 400)
            rest(1000)
        }
        assertEquals(listOf(GestureType.SWIPE_UP), fired.types())
    }

    @Test
    fun eachDirectionIsNamedFromTheUsersView() {
        assertEquals(listOf(GestureType.SWIPE_DOWN), events { rest(600); move(0f, 0.35f, 250); rest(600) }.types())
        assertEquals(listOf(GestureType.SWIPE_LEFT), events { rest(600); move(-0.35f, 0f, 250); rest(600) }.types())
        assertEquals(listOf(GestureType.SWIPE_RIGHT), events { rest(600); move(0.35f, 0f, 250); rest(600) }.types())
    }

    @Test
    fun tenSwipesWithReturnsGiveExactlyTen() {
        val fired = events {
            rest(600)
            repeat(10) {
                move(0f, -0.35f, 250)
                rest(300)
                move(0f, 0.35f, 450)
                rest(900)
            }
        }
        assertEquals(List(10) { GestureType.SWIPE_UP }, fired.types())
    }

    @Test
    fun aQuickRhythmOfSwipesKeepsWorking() {
        // A swipe every 1.2 s with only a short pause at the bottom: faster than the first live Reels test (about 3 s).
        val fired = events {
            rest(600)
            repeat(8) {
                move(0f, -0.35f, 250)
                rest(150)
                move(0f, 0.35f, 300)
                rest(500)
            }
        }
        assertEquals(List(8) { GestureType.SWIPE_UP }, fired.types())
    }

    @Test
    fun noGestureUntilTheHandIsArmed() {
        // The hand appears already moving: nothing may fire.
        assertTrue(events { move(0f, -0.35f, 250); rest(200) }.isEmpty())
    }

    @Test
    fun slowDriftIsNotASwipe() {
        assertTrue(events { rest(600); move(0f, -0.35f, 2_000); rest(600) }.isEmpty())
    }

    @Test
    fun diagonalIsNotASwipe() {
        assertTrue(events { rest(600); move(0.25f, -0.25f, 250); rest(600) }.isEmpty())
    }

    @Test
    fun tooSmallAMotionIsNotASwipe() {
        assertTrue(events { rest(600); move(0f, -0.08f, 100); rest(600) }.isEmpty())
    }

    @Test
    fun pinchFiresOnceAndTapsWhereTheFingerWasBefore() {
        val hands = SyntheticHands()
        val fired = events {
            rest(600)
            rest(1_200, Shape.PINCH)
            rest(600)
        }
        assertEquals(listOf(GestureType.PINCH), fired.types())
        // Before the pinch the index tip sat 2 hand scales above the wrist, left of the palm centre.
        val openTip = SyntheticHands.local(Shape.OPEN)[8]
        assertEquals((0.375f + openTip.first * 0.13f) / hands.aspect / (1f / hands.aspect), fired[0].anchorX * hands.aspect, 0.01f)
        assertEquals(0.60f + openTip.second * 0.13f, fired[0].anchorY, 0.01f)
    }

    @Test
    fun aBriefPinchDoesNotTap() {
        assertTrue(events { rest(600); rest(500, Shape.PINCH); rest(600) }.isEmpty())
    }

    @Test
    fun fingersFoldingAndSpringingUpAreAFlick() {
        // Recorded flicks up fold in about 0.5 s and snap back in about 0.2 s, palm nearly still.
        assertEquals(listOf(GestureType.FLICK_UP), events { rest(600); morph(Shape.OPEN, Shape.FOLD, 450); morph(Shape.FOLD, Shape.OPEN, 150); rest(600) }.types())
    }

    @Test
    fun repeatedFlicksEachCount() {
        val fired = events {
            rest(600)
            repeat(4) {
                morph(Shape.OPEN, Shape.FOLD, 450)
                morph(Shape.FOLD, Shape.OPEN, 150)
                rest(800)
            }
        }
        assertEquals(List(4) { GestureType.FLICK_UP }, fired.types())
    }

    @Test
    fun aFastFoldWithASlowWayBackIsAFlickDown() {
        // Recorded flicks down fold in 50–200 ms and straighten 1.5–4 times slower.
        assertEquals(listOf(GestureType.FLICK_DOWN), events { rest(600); morph(Shape.OPEN, Shape.FIST, 100); morph(Shape.FIST, Shape.OPEN, 400); rest(600) }.types())
    }

    @Test
    fun aFoldWithAFastSnapBackIsAFlickUp() {
        assertEquals(listOf(GestureType.FLICK_UP), events { rest(600); morph(Shape.OPEN, Shape.FOLD, 300); morph(Shape.FOLD, Shape.OPEN, 100); rest(600) }.types())
    }

    @Test
    fun aFoldAndOpeningAtOnePaceIsNotAFlick() {
        // From a "no gesture" recording: fingers folded and opened at about the same speed.
        assertTrue(events { rest(600); morph(Shape.OPEN, Shape.FOLD, 250); morph(Shape.FOLD, Shape.OPEN, 250); rest(600) }.isEmpty())
    }

    @Test
    fun flicksCanChangeDirectionStraightAway() {
        // Live test, 24 Oct 2026: a flick right soon after a flick left was refused as its return stroke.
        val fired = events {
            rest(600)
            morph(Shape.OPEN, Shape.OPEN, 150, toTilt = -40f); morph(Shape.OPEN, Shape.OPEN, 500, fromTilt = -40f); rest(400)
            morph(Shape.OPEN, Shape.OPEN, 150, toTilt = 40f); morph(Shape.OPEN, Shape.OPEN, 500, fromTilt = 40f); rest(400)
            morph(Shape.OPEN, Shape.FOLD, 450); morph(Shape.FOLD, Shape.OPEN, 150); rest(400)
            morph(Shape.OPEN, Shape.FIST, 100); morph(Shape.FIST, Shape.OPEN, 400); rest(600)
        }
        assertEquals(listOf(GestureType.FLICK_LEFT, GestureType.FLICK_RIGHT, GestureType.FLICK_UP, GestureType.FLICK_DOWN), fired.types())
    }

    @Test
    fun rapidFlicksNeedOnlyAMomentAtRest() {
        // Live test: flicks about a second apart were refused because the whole hand never came to a stop.
        val fired = events {
            rest(600)
            repeat(5) {
                morph(Shape.OPEN, Shape.FIST, 100)
                morph(Shape.FIST, Shape.OPEN, 400)
                rest(250)
            }
            rest(600)
        }
        assertEquals(List(5) { GestureType.FLICK_DOWN }, fired.types())
    }

    @Test
    fun sidewaysFlicksFollowTheFastSwing() {
        val left = events { rest(600); morph(Shape.OPEN, Shape.OPEN, 150, toTilt = -40f); morph(Shape.OPEN, Shape.OPEN, 500, fromTilt = -40f); rest(600) }
        assertEquals(listOf(GestureType.FLICK_LEFT), left.types())
        val right = events { rest(600); morph(Shape.OPEN, Shape.OPEN, 150, toTilt = 40f); morph(Shape.OPEN, Shape.OPEN, 500, fromTilt = 40f); rest(600) }
        assertEquals(listOf(GestureType.FLICK_RIGHT), right.types())
    }

    @Test
    fun flicksTwiceASecondAllCount() {
        // Third live test: flicking about twice a second, the hand never comes to a stop between flicks.
        val fired = events {
            rest(600)
            repeat(6) {
                morph(Shape.OPEN, Shape.FIST, 100)
                morph(Shape.FIST, Shape.OPEN, 300)
                rest(100)
            }
            rest(600)
        }
        assertEquals(List(6) { GestureType.FLICK_DOWN }, fired.types())
    }

    @Test
    fun aSmallWobbleIsNotAFlick() {
        assertTrue(events { rest(600); morph(Shape.OPEN, Shape.OPEN, 150, toTilt = 12f); morph(Shape.OPEN, Shape.OPEN, 300, fromTilt = 12f); rest(600) }.isEmpty())
    }

    @Test
    fun aBriefThumbsUpIsNotAFlick() {
        assertTrue(events { rest(600); rest(500, Shape.THUMBS_UP); rest(600) }.isEmpty())
    }

    @Test
    fun fistMustBeHeldAndFiresOnlyOnce() {
        // Too long for a flick, too short for the fist.
        assertTrue(events { rest(600); rest(900, Shape.FIST); rest(600) }.isEmpty())
        assertEquals(listOf(GestureType.FIST), events { rest(600); rest(3_000, Shape.FIST); rest(600) }.types())
    }

    @Test
    fun heldShapesFireOnceEach() {
        assertEquals(listOf(GestureType.V_SIGN), events { rest(600); rest(2_000, Shape.V); rest(600) }.types())
        assertEquals(listOf(GestureType.THREE_FINGERS), events { rest(600); rest(2_000, Shape.THREE); rest(600) }.types())
        assertEquals(listOf(GestureType.THUMBS_UP), events { rest(600); rest(2_000, Shape.THUMBS_UP); rest(600) }.types())
        assertEquals(listOf(GestureType.THUMBS_DOWN), events { rest(600); rest(2_000, Shape.THUMBS_DOWN); rest(600) }.types())
    }

    @Test
    fun aShapeShownStraightAwayWorksWhenHeldStill() {
        // Recorded V, thumbs-up and three-finger takes were held from the moment the hand appeared.
        assertEquals(listOf(GestureType.V_SIGN), events { rest(2_000, Shape.V); rest(600) }.types())
        assertEquals(listOf(GestureType.THUMBS_UP), events { rest(2_000, Shape.THUMBS_UP); rest(600) }.types())
        // Unarmed it takes 1.3 s, so one second is not enough.
        assertTrue(events { rest(1_100, Shape.V); rest(600) }.isEmpty())
    }

    @Test
    fun shapesHeldTooBrieflyDoNothing() {
        assertTrue(events { rest(600); rest(600, Shape.V); rest(600); rest(600, Shape.THUMBS_UP); rest(600) }.isEmpty())
    }

    @Test
    fun everyShapeIsClassifiedCorrectly() {
        val expected = mapOf(
            Shape.OPEN to Pose.OPEN,
            Shape.FIST to Pose.FIST,
            Shape.PINCH to Pose.PINCH,
            Shape.V to Pose.V,
            Shape.THREE to Pose.THREE,
            Shape.THUMBS_UP to Pose.THUMBS_UP,
            Shape.THUMBS_DOWN to Pose.THUMBS_DOWN,
            Shape.POINT to Pose.POINT,
            Shape.ROCK to Pose.ROCK,
            Shape.CALL to Pose.CALL,
            // The recorded pinch: from pointing, other fingers folded, the index tip still above its knuckle.
            Shape.POINT_PINCH to Pose.PINCH,
        )
        for ((shape, pose) in expected) {
            val frame = SyntheticHands().apply { rest(50, shape) }.build().single()
            assertEquals("$shape", pose, HandFeatures.of(frame.hand!!, frame.imageAspect, GestureConfig()).pose(GestureConfig(), wasPinching = false))
        }
    }

    @Test
    fun pausedEngineOnlyReportsThumbsDown() {
        val engine = GestureEngine().apply { paused = true }
        val fired = events(engine) {
            rest(600)
            move(0f, -0.35f, 250)
            rest(1_500)
            rest(1_500, Shape.V)
            rest(600)
            rest(1_500, Shape.FIST)
            rest(600)
            rest(1_500, Shape.THUMBS_DOWN)
            rest(600)
        }
        assertEquals(listOf(GestureType.THUMBS_DOWN), fired.types())
    }

    @Test
    fun nothingFiresWithoutAHand() {
        assertTrue(events { absent(30_000) }.isEmpty())
    }

    @Test
    fun aFarAwayHandIsIgnored() {
        val frames = SyntheticHands(scale = 0.03f).apply { rest(600); move(0f, -0.1f, 250); rest(600) }.build()
        val engine = GestureEngine()
        assertTrue(frames.mapNotNull { engine.process(it).event }.isEmpty())
    }

    @Test
    fun handLostMidSwipeStillCounts() {
        val fired = events {
            rest(600)
            move(0f, -0.25f, 150)
            absent(200)
            rest(600)
        }
        assertEquals(listOf(GestureType.SWIPE_UP), fired.types())
    }

    @Test
    fun processingIsCheap() {
        val frames = SyntheticHands().apply { repeat(50) { rest(600); move(0f, -0.35f, 250); rest(600) } }.build()
        val engine = GestureEngine()
        val start = System.nanoTime()
        frames.forEach { engine.process(it) }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames.size
        assertTrue("engine took $perFrameMs ms per frame", perFrameMs < 2.0)
    }
}
