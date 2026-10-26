package com.airtouch.gesture.mapping

import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType

/** The actions a gesture can be mapped to. Kept as a plain enum so maps can be stored and edited (P6). */
enum class ActionKind {
    SCROLL_FORWARD,
    SCROLL_BACK,
    PAGE_NEXT,
    PAGE_PREVIOUS,
    BACK,
    HOME,
    RECENTS,
    TAP_CENTER,
    DOUBLE_TAP_CENTER,
    PLAY_PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    VOLUME_UP,
    VOLUME_DOWN,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    SCREENSHOT,
    LOCK_SCREEN,
    OPEN_APP,
    SHORTCUT_MENU,
    TOGGLE_PAUSE,
    NONE,
}

data class Profile(val name: String, val actions: Map<GestureType, ActionKind>)

/**
 * Gesture to on-screen action (P4). The foreground app's profile wins over the global one. Apps on the
 * block list get no actions at all, except thumbs-down, so AirTouch can always be paused or resumed.
 */
class ActionMapper(
    private val global: Profile = DEFAULT,
    private val perApp: Map<String, Profile> = DEFAULT_APP_PROFILES,
    private val blocked: Set<String> = DEFAULT_BLOCKED,
) {

    fun map(event: GestureEvent, foregroundPackage: String?): ScreenAction? {
        if (event.type == GestureType.THUMBS_DOWN) return ScreenAction.TogglePause
        if (foregroundPackage != null && foregroundPackage in blocked) return null
        val profile = foregroundPackage?.let(perApp::get) ?: global
        // A small nod or turn of the head does what the flick the same way does, in every app's profile.
        val type = FACE_AS_FLICK[event.type] ?: event.type
        return when (profile.actions[type] ?: ActionKind.NONE) {
            ActionKind.SCROLL_FORWARD -> ScreenAction.Scroll(ScreenAction.Direction.FORWARD)
            ActionKind.SCROLL_BACK -> ScreenAction.Scroll(ScreenAction.Direction.BACK)
            ActionKind.PAGE_NEXT -> ScreenAction.Scroll(ScreenAction.Direction.NEXT_PAGE)
            ActionKind.PAGE_PREVIOUS -> ScreenAction.Scroll(ScreenAction.Direction.PREVIOUS_PAGE)
            ActionKind.BACK -> ScreenAction.Back
            ActionKind.HOME -> ScreenAction.Home
            ActionKind.RECENTS -> ScreenAction.Recents
            ActionKind.TAP_CENTER -> ScreenAction.Tap(0.5f, 0.5f)
            ActionKind.DOUBLE_TAP_CENTER -> ScreenAction.DoubleTap(0.5f, 0.5f)
            ActionKind.PLAY_PAUSE -> ScreenAction.PlayPause
            ActionKind.SEEK_BACK -> ScreenAction.Seek(forward = false)
            ActionKind.SEEK_FORWARD -> ScreenAction.Seek(forward = true)
            ActionKind.NEXT_TRACK -> ScreenAction.Track(next = true)
            ActionKind.PREVIOUS_TRACK -> ScreenAction.Track(next = false)
            ActionKind.VOLUME_UP -> ScreenAction.Volume(up = true)
            ActionKind.VOLUME_DOWN -> ScreenAction.Volume(up = false)
            ActionKind.NOTIFICATIONS -> ScreenAction.Notifications
            ActionKind.QUICK_SETTINGS -> ScreenAction.QuickSettings
            ActionKind.SCREENSHOT -> ScreenAction.Screenshot
            ActionKind.LOCK_SCREEN -> ScreenAction.LockScreen
            ActionKind.OPEN_APP -> ScreenAction.OpenChosenApp
            ActionKind.SHORTCUT_MENU -> ScreenAction.ShortcutMenu
            ActionKind.TOGGLE_PAUSE -> ScreenAction.TogglePause
            ActionKind.NONE -> null
        }
    }

    /**
     * While a call rings only these act, wherever the call shows (full screen or as a banner over an app), so a
     * stray gesture can never do anything else. Thumbs-down still pauses AirTouch.
     */
    fun mapDuringCall(event: GestureEvent): ScreenAction? = when (event.type) {
        GestureType.FIST, GestureType.BROW_RIGHT_TWICE -> ScreenAction.RejectCall
        GestureType.OPEN_PALM, GestureType.BROW_LEFT_TWICE -> ScreenAction.AnswerCall
        GestureType.THUMBS_DOWN -> ScreenAction.TogglePause
        else -> null
    }

    companion object {
        val FACE_AS_FLICK = mapOf(
            GestureType.FACE_UP to GestureType.FLICK_UP,
            GestureType.FACE_DOWN to GestureType.FLICK_DOWN,
            GestureType.FACE_LEFT to GestureType.FLICK_LEFT,
            GestureType.FACE_RIGHT to GestureType.FLICK_RIGHT,
        )

        val DEFAULT = Profile(
            "Default",
            mapOf(
                GestureType.SWIPE_UP to ActionKind.SCROLL_FORWARD,
                GestureType.FLICK_UP to ActionKind.SCROLL_FORWARD,
                GestureType.FLICK_DOWN to ActionKind.SCROLL_BACK,
                GestureType.FLICK_LEFT to ActionKind.PAGE_NEXT,
                GestureType.FLICK_RIGHT to ActionKind.BACK,
                GestureType.SWIPE_DOWN to ActionKind.SCROLL_BACK,
                GestureType.SWIPE_LEFT to ActionKind.PAGE_NEXT,
                GestureType.SWIPE_RIGHT to ActionKind.BACK,
                GestureType.PINCH to ActionKind.TAP_CENTER,
                GestureType.FIST to ActionKind.PLAY_PAUSE,
                GestureType.THUMBS_DOWN to ActionKind.TOGGLE_PAUSE,
                GestureType.V_SIGN to ActionKind.HOME,
                GestureType.THUMBS_UP to ActionKind.BACK,
                GestureType.THREE_FINGERS to ActionKind.RECENTS,
                GestureType.DOUBLE_PINCH to ActionKind.DOUBLE_TAP_CENTER,
                GestureType.FIST_RAISE to ActionKind.VOLUME_UP,
                GestureType.FIST_LOWER to ActionKind.VOLUME_DOWN,
                GestureType.ROCK to ActionKind.SHORTCUT_MENU,
                GestureType.CALL to ActionKind.OPEN_APP,
                GestureType.EYES_CLOSED to ActionKind.HOME,
                GestureType.BROW_LEFT to ActionKind.BACK,
                GestureType.BROW_RIGHT to ActionKind.PLAY_PAUSE,
                GestureType.BROWS_TWICE to ActionKind.SCREENSHOT,
                GestureType.BROW_LEFT_HOLD to ActionKind.VOLUME_DOWN,
                GestureType.BROW_RIGHT_HOLD to ActionKind.VOLUME_UP,
                GestureType.KISS to ActionKind.DOUBLE_TAP_CENTER,
            ),
        )

        /**
         * Reels have no media session to pause, but a tap on the video pauses it, so the fist taps there. Sideways acts
         * as everywhere else (on a Reel, left opens the creator's profile and right is Back): it was off after a stray
         * swipe left Reels in the first live test, and back on at the user's request once flicks were reliable.
         */
        val INSTAGRAM = DEFAULT.copy(
            name = "Instagram",
            actions = DEFAULT.actions + mapOf(GestureType.FIST to ActionKind.TAP_CENTER, GestureType.BROW_RIGHT to ActionKind.TAP_CENTER),
        )

        /**
         * YouTube: sideways skips 10 s, on the side of the player a finger would double-tap (left back, right forward).
         * In Shorts, where a double-tap likes instead, the app does nothing for these.
         */
        val YOUTUBE = DEFAULT.copy(
            name = "YouTube",
            actions = DEFAULT.actions + mapOf(
                GestureType.SWIPE_LEFT to ActionKind.SEEK_BACK,
                GestureType.FLICK_LEFT to ActionKind.SEEK_BACK,
                GestureType.SWIPE_RIGHT to ActionKind.SEEK_FORWARD,
                GestureType.FLICK_RIGHT to ActionKind.SEEK_FORWARD,
            ),
        )

        /** Music apps: sideways changes the song. Left is next, as swiping a song's cover to the left is in these apps. */
        val MUSIC = DEFAULT.copy(
            name = "Music",
            actions = DEFAULT.actions + mapOf(
                GestureType.SWIPE_LEFT to ActionKind.NEXT_TRACK,
                GestureType.FLICK_LEFT to ActionKind.NEXT_TRACK,
                GestureType.SWIPE_RIGHT to ActionKind.PREVIOUS_TRACK,
                GestureType.FLICK_RIGHT to ActionKind.PREVIOUS_TRACK,
            ),
        )

        /** Music apps common in India and worldwide (package names as on Google Play, October 2026). */
        val MUSIC_APPS = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.jio.media.jiobeats",
            "com.gaana",
            "com.bsbportal.music",
            "com.amazon.mp3",
            "com.apple.android.music",
            "com.miui.player",
            "com.soundcloud.android",
            "deezer.android.app",
        )

        /** Back does nothing on a home screen (first live flick test), so right turns the page back there. */
        val LAUNCHER = DEFAULT.copy(
            name = "Home screen",
            actions = DEFAULT.actions + mapOf(
                GestureType.SWIPE_RIGHT to ActionKind.PAGE_PREVIOUS,
                GestureType.FLICK_RIGHT to ActionKind.PAGE_PREVIOUS,
            ),
        )

        /** Photo viewers: right shows the previous photo, as left shows the next; Back would close the photo. */
        val PHOTOS = LAUNCHER.copy(name = "Photos")

        val PHOTO_APPS = setOf(
            "com.miui.gallery",
            "com.google.android.apps.photos",
            "com.sec.android.gallery3d",
            "com.oneplus.gallery",
        )

        val DEFAULT_APP_PROFILES = MUSIC_APPS.associateWith { MUSIC } + PHOTO_APPS.associateWith { PHOTOS } + mapOf(
            "com.instagram.android" to INSTAGRAM,
            "com.google.android.youtube" to YOUTUBE,
            "com.miui.home" to LAUNCHER,
            "com.android.launcher3" to LAUNCHER,
            "com.google.android.apps.nexuslauncher" to LAUNCHER,
        )

        /** Calls, the camera and AirTouch's own screens never receive gesture actions. */
        val DEFAULT_BLOCKED = setOf(
            "com.airtouch",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.camera",
            "com.google.android.GoogleCamera",
        )
    }
}
