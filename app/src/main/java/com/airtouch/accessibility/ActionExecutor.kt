package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.airtouch.gesture.mapping.ScreenAction

/**
 * Performs actions through the accessibility service, one at a time: an action that arrives while the
 * previous gesture is still being injected is dropped, never queued, and so is one that arrives while a
 * virtual finger is down ([touch]), since sending it would cancel that finger. Call from the main thread.
 */
class ActionExecutor(private val service: AccessibilityService, private val geometry: ScreenGeometry, private val touch: TouchInjector) {

    private var busyUntilMs = 0L

    /** Reports "completed", "cancelled", "dropped", "rejected" or why nothing was done through [onResult]. */
    fun perform(action: ScreenAction, onResult: (String) -> Unit) {
        when (action) {
            is ScreenAction.Scroll -> dispatch(geometry.scroll(action.direction), SCROLL_MS, onResult)
            is ScreenAction.Tap -> dispatch(geometry.tap(action.x, action.y), TAP_MS, onResult)
            is ScreenAction.DoubleTap -> doubleTap(action.x, action.y, onResult)
            ScreenAction.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, onResult)
            is ScreenAction.Track -> mediaKey(if (action.next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS, onResult)
            is ScreenAction.Volume -> onResult(volume(action.up))
            ScreenAction.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, onResult)
            ScreenAction.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, onResult)
            ScreenAction.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, onResult)
            ScreenAction.Notifications -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, onResult)
            ScreenAction.QuickSettings -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, onResult)
            ScreenAction.Screenshot ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, onResult) else onResult(NEEDS_ANDROID_9)
            ScreenAction.LockScreen ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, onResult) else onResult(NEEDS_ANDROID_9)
            // The service handles these itself: they need its state (YouTube's screen, settings, the menu).
            is ScreenAction.Seek, ScreenAction.OpenChosenApp, ScreenAction.ShortcutMenu, ScreenAction.TogglePause,
            ScreenAction.AnswerCall, ScreenAction.RejectCall,
            -> onResult("not a screen action")
        }
    }

    /** Two taps 150 ms apart at a pixel: inside every app's double-tap window (300 ms by default). */
    fun doubleTapAt(px: Float, py: Float, onResult: (String) -> Unit) {
        val first = GestureDescription.StrokeDescription(point(px, py), 0, TAP_MS)
        val second = GestureDescription.StrokeDescription(point(px, py), TAP_MS + DOUBLE_TAP_GAP_MS, TAP_MS)
        dispatch(GestureDescription.Builder().addStroke(first).addStroke(second).build(), 2 * TAP_MS + DOUBLE_TAP_GAP_MS, onResult)
    }

    /** A single tap at a pixel (the cursor's, after the magnet). */
    fun tapAt(px: Float, py: Float, onResult: (String) -> Unit) =
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(point(px, py), 0, TAP_MS)).build(), TAP_MS, onResult)

    private fun doubleTap(x: Float, y: Float, onResult: (String) -> Unit) {
        val (width, height) = geometry.size()
        val (px, py) = TouchGeometry.toPixels(x, y, width, height)
        doubleTapAt(px, py, onResult)
    }

    private fun point(px: Float, py: Float) = Path().apply { moveTo(px, py) }

    private fun dispatch(path: Path, durationMs: Long, onResult: (String) -> Unit) =
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), durationMs, onResult)

    private fun dispatch(gesture: GestureDescription, durationMs: Long, onResult: (String) -> Unit) {
        val now = SystemClock.uptimeMillis()
        touch.releaseIfStale()
        if (touch.active) {
            onResult("dropped (a finger is down)")
            return
        }
        if (now < busyUntilMs) {
            onResult("dropped")
            return
        }
        busyUntilMs = now + durationMs + SETTLE_MS
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = onResult("completed")
                override fun onCancelled(gestureDescription: GestureDescription) = onResult("cancelled")
            },
            null,
        )
        if (!accepted) {
            busyUntilMs = 0
            onResult("rejected")
        }
    }

    /** A media key, as on headphones, delivered to whichever app holds the active media session. */
    private fun mediaKey(code: Int, onResult: (String) -> Unit) {
        val audio = service.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        onResult(if (audio.isMusicActive) "sent (media was playing)" else "sent (no media was playing)")
    }

    /**
     * One step of the media volume, showing the phone's own volume panel. Media rather than the "current" stream:
     * with nothing playing that would be the ringer, and turning it to silent needs Do Not Disturb access.
     * Returns what happened, such as "8/15".
     */
    fun volume(up: Boolean): String {
        val audio = service.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return "volume is fixed on this device"
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        } catch (e: SecurityException) {
            "refused: ${e.message}"
        }
    }

    private fun global(action: Int, onResult: (String) -> Unit) =
        onResult(if (service.performGlobalAction(action)) "completed" else "rejected")

    private companion object {
        /** Phase 0 spike B: a 250 ms stroke over 40 % of the height moved exactly one Reel, 10 times out of 10. */
        const val SCROLL_MS = 250L
        const val TAP_MS = 50L
        const val DOUBLE_TAP_GAP_MS = 100L
        const val SETTLE_MS = 100L
        const val NEEDS_ANDROID_9 = "needs Android 9 or later"
    }
}
