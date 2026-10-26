package com.airtouch.gesture.mapping

/** What AirTouch does on screen, in semantic terms. The app turns these into pixels and strokes. */
sealed interface ScreenAction {

    enum class Direction { FORWARD, BACK, NEXT_PAGE, PREVIOUS_PAGE }

    /** FORWARD shows what is below (the next Reel); NEXT_PAGE shows what is to the right. */
    data class Scroll(val direction: Direction) : ScreenAction

    /** A tap at a point of the screen, normalised 0..1. */
    data class Tap(val x: Float, val y: Float) : ScreenAction

    /** Two quick taps at a point: likes a Reel or a Short, zooms into a photo or a map. */
    data class DoubleTap(val x: Float, val y: Float) : ScreenAction

    /** The media play/pause key, as on headphones: works in any app that plays through a media session. */
    data object PlayPause : ScreenAction

    /** Skips the video in front back or forward; in YouTube a double-tap on that side of the player (10 s by default). */
    data class Seek(val forward: Boolean) : ScreenAction

    /** The media next / previous track key, for music apps. */
    data class Track(val next: Boolean) : ScreenAction

    /** One step of the media volume, as the phone's own volume keys do. */
    data class Volume(val up: Boolean) : ScreenAction

    data object Back : ScreenAction
    data object Home : ScreenAction
    data object Recents : ScreenAction
    data object Notifications : ScreenAction
    data object QuickSettings : ScreenAction

    /** AirTouch's own overlays are hidden first, so they never appear in the picture. */
    data object Screenshot : ScreenAction

    /** Turns the screen off and locks the phone. AirTouch cannot unlock it again. */
    data object LockScreen : ScreenAction

    /** Opens the app chosen in AirTouch. */
    data object OpenChosenApp : ScreenAction

    /** Shows the shortcut menu: the next swipe or flick picks one of its four actions. */
    data object ShortcutMenu : ScreenAction

    /** Answers or rejects the ringing call through Android's call service (needs the Phone permission). */
    data object AnswerCall : ScreenAction
    data object RejectCall : ScreenAction

    /** Pauses or resumes AirTouch itself. Nothing happens on screen. */
    data object TogglePause : ScreenAction
}
