package com.airtouch.gesture.mapping

import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionMapperTest {

    private val mapper = ActionMapper()
    private fun gesture(type: GestureType) = GestureEvent(type, timestampMs = 0, confidence = 1f, anchorX = 0.5f, anchorY = 0.5f)

    @Test
    fun defaultProfile() {
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.FORWARD), mapper.map(gesture(GestureType.SWIPE_UP), "com.android.chrome"))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.BACK), mapper.map(gesture(GestureType.SWIPE_DOWN), "com.android.chrome"))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(GestureType.SWIPE_LEFT), "com.android.chrome"))
        assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.SWIPE_RIGHT), "com.android.chrome"))
        assertEquals(ScreenAction.Tap(0.5f, 0.5f), mapper.map(gesture(GestureType.PINCH), "com.android.chrome"))
    }

    @Test
    fun homeScreenTurnsPagesBothWays() {
        for (type in listOf(GestureType.SWIPE_LEFT, GestureType.FLICK_LEFT)) {
            assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(type), "com.miui.home"))
        }
        for (type in listOf(GestureType.SWIPE_RIGHT, GestureType.FLICK_RIGHT)) {
            assertEquals(ScreenAction.Scroll(ScreenAction.Direction.PREVIOUS_PAGE), mapper.map(gesture(type), "com.miui.home"))
        }
    }

    @Test
    fun photoAppsShowThePreviousPhotoOnRightInsteadOfClosingIt() {
        for (app in listOf("com.miui.gallery", "com.google.android.apps.photos")) {
            assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(GestureType.FLICK_LEFT), app))
            assertEquals(ScreenAction.Scroll(ScreenAction.Direction.PREVIOUS_PAGE), mapper.map(gesture(GestureType.FLICK_RIGHT), app))
            assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.THUMBS_UP), app))
        }
    }

    @Test
    fun duringACallOnlyTheCallGesturesAct() {
        assertEquals(ScreenAction.RejectCall, mapper.mapDuringCall(gesture(GestureType.FIST)))
        assertEquals(ScreenAction.AnswerCall, mapper.mapDuringCall(gesture(GestureType.OPEN_PALM)))
        assertEquals(ScreenAction.AnswerCall, mapper.mapDuringCall(gesture(GestureType.BROW_LEFT_TWICE)))
        assertEquals(ScreenAction.RejectCall, mapper.mapDuringCall(gesture(GestureType.BROW_RIGHT_TWICE)))
        assertEquals(ScreenAction.TogglePause, mapper.mapDuringCall(gesture(GestureType.THUMBS_DOWN)))
        for (type in listOf(GestureType.FLICK_UP, GestureType.V_SIGN, GestureType.PINCH, GestureType.KISS, GestureType.EYES_CLOSED)) {
            assertNull("$type during a call", mapper.mapDuringCall(gesture(type)))
        }
    }

    @Test
    fun faceGesturesHaveTheirDefaultActions() {
        val chrome = "com.android.chrome"
        assertEquals(ScreenAction.Home, mapper.map(gesture(GestureType.EYES_CLOSED), chrome))
        assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.BROW_LEFT), chrome))
        assertEquals(ScreenAction.PlayPause, mapper.map(gesture(GestureType.BROW_RIGHT), chrome))
        assertEquals(ScreenAction.Screenshot, mapper.map(gesture(GestureType.BROWS_TWICE), chrome))
        assertEquals(ScreenAction.Volume(up = false), mapper.map(gesture(GestureType.BROW_LEFT_HOLD), chrome))
        assertEquals(ScreenAction.Volume(up = true), mapper.map(gesture(GestureType.BROW_RIGHT_HOLD), chrome))
        assertEquals(ScreenAction.DoubleTap(0.5f, 0.5f), mapper.map(gesture(GestureType.KISS), chrome))
        // Answering and rejecting only mean something while a call rings.
        assertNull(mapper.map(gesture(GestureType.BROW_LEFT_TWICE), chrome))
        assertNull(mapper.map(gesture(GestureType.OPEN_PALM), chrome))
        // Instagram pauses a Reel by tapping it, as with the fist.
        assertEquals(ScreenAction.Tap(0.5f, 0.5f), mapper.map(gesture(GestureType.BROW_RIGHT), "com.instagram.android"))
    }

    @Test
    fun headNodsAndTurnsDoWhatTheFlicksDoInEachApp() {
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.FORWARD), mapper.map(gesture(GestureType.FACE_UP), "com.instagram.android"))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.BACK), mapper.map(gesture(GestureType.FACE_DOWN), "com.instagram.android"))
        assertEquals(ScreenAction.Seek(forward = false), mapper.map(gesture(GestureType.FACE_LEFT), "com.google.android.youtube"))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.PREVIOUS_PAGE), mapper.map(gesture(GestureType.FACE_RIGHT), "com.miui.home"))
        assertEquals(ScreenAction.Track(next = true), mapper.map(gesture(GestureType.FACE_LEFT), "com.spotify.music"))
        assertNull(mapper.map(gesture(GestureType.FACE_UP), "com.android.incallui"))
    }

    @Test
    fun heldShapesNavigateEverywhereIncludingReels() {
        for (app in listOf("com.android.chrome", "com.instagram.android")) {
            assertEquals(ScreenAction.Home, mapper.map(gesture(GestureType.V_SIGN), app))
            assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.THUMBS_UP), app))
            assertEquals(ScreenAction.Recents, mapper.map(gesture(GestureType.THREE_FINGERS), app))
        }
    }

    @Test
    fun unknownForegroundAppUsesTheDefault() {
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.FORWARD), mapper.map(gesture(GestureType.SWIPE_UP), null))
    }

    @Test
    fun instagramSidewaysWorksAsEverywhereElse() {
        val app = "com.instagram.android"
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(GestureType.FLICK_LEFT), app))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(GestureType.SWIPE_LEFT), app))
        assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.FLICK_RIGHT), app))
        assertEquals(ScreenAction.Back, mapper.map(gesture(GestureType.SWIPE_RIGHT), app))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.FORWARD), mapper.map(gesture(GestureType.SWIPE_UP), app))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.BACK), mapper.map(gesture(GestureType.SWIPE_DOWN), app))
        assertEquals(ScreenAction.Tap(0.5f, 0.5f), mapper.map(gesture(GestureType.PINCH), app))
    }

    @Test
    fun youTubeSkipsTenSecondsOnTheSideAFingerWouldDoubleTap() {
        val app = "com.google.android.youtube"
        assertEquals(ScreenAction.Seek(forward = false), mapper.map(gesture(GestureType.FLICK_LEFT), app))
        assertEquals(ScreenAction.Seek(forward = false), mapper.map(gesture(GestureType.SWIPE_LEFT), app))
        assertEquals(ScreenAction.Seek(forward = true), mapper.map(gesture(GestureType.FLICK_RIGHT), app))
        assertEquals(ScreenAction.Seek(forward = true), mapper.map(gesture(GestureType.SWIPE_RIGHT), app))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.FORWARD), mapper.map(gesture(GestureType.FLICK_UP), app))
        assertEquals(ScreenAction.PlayPause, mapper.map(gesture(GestureType.FIST), app))
    }

    @Test
    fun musicAppsChangeTheSongSideways() {
        for (app in listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats")) {
            assertEquals(ScreenAction.Track(next = true), mapper.map(gesture(GestureType.FLICK_LEFT), app))
            assertEquals(ScreenAction.Track(next = false), mapper.map(gesture(GestureType.FLICK_RIGHT), app))
            assertEquals(ScreenAction.Track(next = true), mapper.map(gesture(GestureType.SWIPE_LEFT), app))
            assertEquals(ScreenAction.PlayPause, mapper.map(gesture(GestureType.FIST), app))
        }
        // Elsewhere sideways still turns pages.
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE), mapper.map(gesture(GestureType.FLICK_LEFT), "com.android.chrome"))
    }

    @Test
    fun newGesturesHaveTheirDefaultActionsEverywhere() {
        for (app in listOf(null, "com.android.chrome", "com.instagram.android", "com.google.android.youtube", "com.spotify.music", "com.miui.home")) {
            assertEquals(ScreenAction.DoubleTap(0.5f, 0.5f), mapper.map(gesture(GestureType.DOUBLE_PINCH), app))
            assertEquals(ScreenAction.Volume(up = true), mapper.map(gesture(GestureType.FIST_RAISE), app))
            assertEquals(ScreenAction.Volume(up = false), mapper.map(gesture(GestureType.FIST_LOWER), app))
            assertEquals(ScreenAction.ShortcutMenu, mapper.map(gesture(GestureType.ROCK), app))
            assertEquals(ScreenAction.OpenChosenApp, mapper.map(gesture(GestureType.CALL), app))
        }
    }

    @Test
    fun blockedAppsGetNoneOfTheNewGesturesEither() {
        for (type in listOf(GestureType.DOUBLE_PINCH, GestureType.FIST_RAISE, GestureType.FIST_LOWER, GestureType.ROCK, GestureType.CALL)) {
            assertNull(mapper.map(gesture(type), "com.android.incallui"))
            assertNull(mapper.map(gesture(type), "com.airtouch"))
        }
    }

    @Test
    fun blockedAppsGetNothingButThumbsDownStillPauses() {
        assertNull(mapper.map(gesture(GestureType.SWIPE_UP), "com.google.android.dialer"))
        assertNull(mapper.map(gesture(GestureType.PINCH), "com.airtouch"))
        assertNull(mapper.map(gesture(GestureType.FIST), "com.google.android.dialer"))
        assertEquals(ScreenAction.TogglePause, mapper.map(gesture(GestureType.THUMBS_DOWN), "com.google.android.dialer"))
    }

    @Test
    fun fistPlaysOrPausesWithTheMediaKeyButTapsInInstagram() {
        assertEquals(ScreenAction.PlayPause, mapper.map(gesture(GestureType.FIST), "com.google.android.youtube"))
        assertEquals(ScreenAction.PlayPause, mapper.map(gesture(GestureType.FIST), "com.miui.videoplayer"))
        assertEquals(ScreenAction.Tap(0.5f, 0.5f), mapper.map(gesture(GestureType.FIST), "com.instagram.android"))
    }

    @Test
    fun customProfilesOverrideTheMap() {
        val custom = ActionMapper(perApp = mapOf("com.android.chrome" to Profile("Browser", mapOf(GestureType.SWIPE_RIGHT to ActionKind.PAGE_PREVIOUS))))
        assertEquals(ScreenAction.Scroll(ScreenAction.Direction.PREVIOUS_PAGE), custom.map(gesture(GestureType.SWIPE_RIGHT), "com.android.chrome"))
        assertNull(custom.map(gesture(GestureType.SWIPE_UP), "com.android.chrome"))
    }
}
