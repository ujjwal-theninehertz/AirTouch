package com.airtouch.gesture.mapping

import com.airtouch.gesture.model.GestureType

/**
 * The 🤘 shortcut menu: four system actions, one per direction, picked by the next swipe or flick. Any other
 * gesture closes it unused, and so does waiting [timeoutMs]. Thumbs-down still pauses AirTouch as it closes the
 * menu, so pausing always works. Not thread-safe; the app drives it from one thread.
 */
class ShortcutMenu(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    enum class Item(val action: ScreenAction, val label: String) {
        UP(ScreenAction.Notifications, "Notifications"),
        DOWN(ScreenAction.QuickSettings, "Quick settings"),
        LEFT(ScreenAction.Screenshot, "Screenshot"),
        RIGHT(ScreenAction.LockScreen, "Lock screen"),
    }

    sealed interface Outcome {
        /** The menu was closed; handle the gesture as usual. */
        data object NotOpen : Outcome

        /** The gesture picked [item]; the menu is closed. */
        data class Chosen(val item: Item) : Outcome

        /** The gesture closed the menu without picking anything. Perform it only if [passThrough]. */
        data class Dismissed(val passThrough: Boolean) : Outcome
    }

    private var openedAtMs: Long? = null

    val isOpen: Boolean get() = openedAtMs != null

    fun open(t: Long) {
        openedAtMs = t
    }

    fun close() {
        openedAtMs = null
    }

    /** True once, when the menu times out at [t]. */
    fun expire(t: Long): Boolean {
        val opened = openedAtMs ?: return false
        if (t - opened < timeoutMs) return false
        openedAtMs = null
        return true
    }

    fun onGesture(type: GestureType, t: Long): Outcome {
        if (expire(t) || openedAtMs == null) return Outcome.NotOpen
        openedAtMs = null
        val item = when (type) {
            GestureType.SWIPE_UP, GestureType.FLICK_UP -> Item.UP
            GestureType.SWIPE_DOWN, GestureType.FLICK_DOWN -> Item.DOWN
            GestureType.SWIPE_LEFT, GestureType.FLICK_LEFT -> Item.LEFT
            GestureType.SWIPE_RIGHT, GestureType.FLICK_RIGHT -> Item.RIGHT
            else -> return Outcome.Dismissed(passThrough = type == GestureType.THUMBS_DOWN)
        }
        return Outcome.Chosen(item)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
