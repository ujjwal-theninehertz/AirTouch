package com.airtouch.gesture.model

/**
 * The on-screen cursor. Coordinates are fractions of the screen (0..1) as the user sees it, so the app needs
 * no camera geometry to draw it.
 */
data class Pointer(
    val x: Float,
    val y: Float,
    /** A virtual finger is down under the cursor: a long-press or a drag. */
    val pressed: Boolean,
    /** 0..1 while a held pinch is on its way to becoming a long-press; 0 otherwise. */
    val holdProgress: Float,
)

/**
 * A virtual finger, for the app to inject. Coordinates are screen fractions (0..1). Every [Down] is followed by
 * [Up] and every [ZoomStart] by [ZoomEnd], also when the hand is lost or AirTouch is paused.
 */
sealed interface Touch {
    data class Tap(val x: Float, val y: Float) : Touch

    data class DoubleTap(val x: Float, val y: Float) : Touch

    /**
     * A long-press ([hold]), or the start of a drag. A long-press is complete in itself: the app may press for a
     * fixed time and ignore the [Up] that follows. A drag's finger should only touch the screen once it moves, so a
     * drag that ends without moving does nothing rather than tap.
     */
    data class Down(val x: Float, val y: Float, val hold: Boolean = false) : Touch

    data class Move(val x: Float, val y: Float) : Touch

    /** The finger lifts where it is. */
    data object Up : Touch

    /** Two fingers go down either side of ([x], [y]), as for pinch-to-zoom. */
    data class ZoomStart(val x: Float, val y: Float) : Touch

    /** How far apart the two fingers are now, relative to where they went down: 2 is twice as far (zoom in). */
    data class Zoom(val factor: Float) : Touch

    data object ZoomEnd : Touch
}
