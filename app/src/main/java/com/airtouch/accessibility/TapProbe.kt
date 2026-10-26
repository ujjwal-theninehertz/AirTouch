package com.airtouch.accessibility

/**
 * Links the cursor tap test (P4 exit test: taps land within ±8 dp, in every rotation) to the service. While the
 * test screen is in front, AirTouch may act inside its own app, where it is otherwise off; and every cursor tap
 * records where AirTouch aimed it, so the screen can compare that with where the touch arrived.
 */
object TapProbe {

    /** Where the service aimed a cursor tap, in screen pixels, at [atMs] (`SystemClock.uptimeMillis`). */
    data class Aim(val x: Float, val y: Float, val atMs: Long)

    @Volatile
    var testScreenShown = false

    @Volatile
    var lastAim: Aim? = null
}
