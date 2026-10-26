package com.airtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import kotlin.math.hypot

/**
 * Injects the engine's virtual fingers that do more than tap (P4b): a long-press, a drag, and the two fingers of a
 * zoom. A long-press is one self-contained press. Drags and zooms first try continued strokes (API 26) so the
 * screen follows the hand live: each update becomes a short segment continuing the last one, sent once that one
 * has played. Some phones refuse continued strokes (the Redmi Note 8 Pro on MIUI 12.5 cancelled every one within
 * 3–11 ms, 24 Oct 2026); after a refusal, drags and zooms are recorded and played back as one gesture when the
 * hand lets go, starting from the last point the screen had already received. A drag that never moves does
 * nothing rather than tap. Call from the main thread.
 */
class TouchInjector(private val service: AccessibilityService, private val geometry: ScreenGeometry) {

    /** Called once, the first time this phone refuses a live finger. */
    var onLiveRefused: (() -> Unit)? = null

    private class Contact(
        start: List<Pair<Float, Float>>,
        val zoom: Boolean,
        val zoomCenter: Pair<Float, Float>? = null,
        val zoomGap: Float = 0f,
    ) {
        /** Where the fingers are on screen (live), or were last seen (to be played back). */
        var points = start

        /** Every position with its time, for playing back; [delivered] is the index the screen has already reached. */
        val trail = mutableListOf(SystemClock.uptimeMillis() to start)
        var delivered = 0
        var strokes: List<GestureDescription.StrokeDescription>? = null
        var pending: List<Pair<Float, Float>>? = null
        var inFlight = false
        var lifting = false
        var live = true
        var nudged = false

        /** Stopped by AirTouch itself: a cancel that follows is ours, not the phone refusing. */
        var abandoned = false

        /** The lifting segment has been sent: its strokes can no longer be continued. */
        var finished = false
    }

    private var contact: Contact? = null
    private var busyUntilMs = 0L

    /** When the engine last moved the finger that is down. */
    private var lastNewsMs = 0L

    /** This phone refused a continued stroke: until the service restarts, drags and zooms are played back. */
    private var liveRefused = false

    /** A finger is down or a press is playing: other gestures must wait, as sending one would cancel it. */
    val active: Boolean get() = contact != null || SystemClock.uptimeMillis() < busyUntilMs

    /**
     * Lifts a finger the engine has not moved for [STALE_FINGER_MS], before another gesture would wait for it: a drag
     * whose lift never came once blocked every action after it (25 Oct 2026). A finger held still that long is
     * not lost meanwhile; this only runs when something else is waiting.
     */
    fun releaseIfStale() {
        val idleMs = SystemClock.uptimeMillis() - lastNewsMs
        if (contact == null || idleMs < STALE_FINGER_MS) return
        Log.w(TAG, "P4b finger down for $idleMs ms without a move or a lift: lifted")
        cancel()
    }

    /** Presses at a screen fraction long enough for any app's long-press (Android's default timeout is 400–500 ms). */
    fun longPress(x: Float, y: Float) {
        cancel()
        val (width, height) = geometry.size()
        val (px, py) = TouchGeometry.toPixels(x, y, width, height)
        dispatchOnce(listOf(GestureDescription.StrokeDescription(Path().apply { moveTo(px, py) }, 0, LONG_PRESS_MS)), LONG_PRESS_MS, "long-press")
    }

    /** A drag begins at a screen fraction. Nothing touches the screen until it first moves. */
    fun down(x: Float, y: Float) {
        cancel()
        val (width, height) = geometry.size()
        val start = TouchGeometry.toPixels(x, y, width, height).let { (px, py) -> TouchGeometry.dragStart(px, py, width, height) }
        contact = Contact(listOf(start), zoom = false).also { it.live = !liveRefused }
        lastNewsMs = SystemClock.uptimeMillis()
    }

    fun move(x: Float, y: Float) {
        val current = contact?.takeUnless { it.zoom } ?: return
        lastNewsMs = SystemClock.uptimeMillis()
        val (width, height) = geometry.size()
        update(current, listOf(TouchGeometry.toPixels(x, y, width, height)))
    }

    /** Two fingers go down either side of the point, a quarter of the screen's width apart. */
    fun zoomStart(x: Float, y: Float) {
        cancel()
        val (width, height) = geometry.size()
        val center = TouchGeometry.toPixels(x, y, width, height)
        val gap = TouchGeometry.zoomStartGap(width, height)
        val (a, b) = TouchGeometry.zoomFingers(center.first, center.second, gap, width, height)
        val started = Contact(listOf(a, b), zoom = true, zoomCenter = center, zoomGap = gap).also { it.live = !liveRefused }
        contact = started
        lastNewsMs = SystemClock.uptimeMillis()
        if (started.live) send(started, nudge(started), HOLD_SEGMENT_MS)
    }

    /** [factor] is the gap between the fingers relative to where they went down. */
    fun zoom(factor: Float) {
        val current = contact?.takeIf { it.zoom } ?: return
        lastNewsMs = SystemClock.uptimeMillis()
        val center = current.zoomCenter ?: return
        val (width, height) = geometry.size()
        val (a, b) = TouchGeometry.zoomFingers(center.first, center.second, current.zoomGap * factor, width, height)
        update(current, listOf(a, b))
    }

    /** Lifts the fingers where they are, or plays the whole drag or zoom back on phones that refuse live fingers. */
    fun up() {
        val current = contact ?: return
        when {
            !current.live -> {
                contact = null
                playBack(current)
            }
            current.strokes == null -> contact = null
            current.inFlight -> current.lifting = true
            else -> finish(current)
        }
    }

    /** Stops at once (pausing, stopping, an app where AirTouch is off): lifts a live finger, plays nothing back. */
    fun cancel() {
        val current = contact ?: return
        contact = null
        current.abandoned = true
        if (!current.live || current.strokes == null || current.finished) return
        if (current.inFlight) current.lifting = true else finish(current)
    }

    private fun update(current: Contact, targets: List<Pair<Float, Float>>) {
        current.trail += SystemClock.uptimeMillis() to targets
        when {
            !current.live -> current.points = targets
            // A drag's first move: the finger touches down where the drag began and slides to here.
            current.strokes == null && !current.zoom -> send(current, targets, MOVE_SEGMENT_MS)
            current.inFlight -> current.pending = targets
            else -> send(current, targets, MOVE_SEGMENT_MS)
        }
    }

    /** A pixel to one side and back: continued segments with no length at all are what this phone refused first. */
    private fun nudge(current: Contact): List<Pair<Float, Float>> {
        current.nudged = !current.nudged
        val shift = if (current.nudged) 1f else -1f
        return current.points.map { (x, y) -> x + shift to y }
    }

    private fun finish(current: Contact) {
        if (current.finished) return
        val last = current.pending ?: current.points
        current.pending = null
        current.lifting = false
        current.finished = true
        // The finger is lifting: the next drag or zoom starts fresh (continuing a finished stroke throws).
        if (contact === current) contact = null
        send(current, last, LIFT_SEGMENT_MS, willContinue = false)
    }

    private fun send(current: Contact, targets: List<Pair<Float, Float>>, durationMs: Long, willContinue: Boolean = true) {
        if (current.finished && willContinue) return
        val previous = current.strokes
        val from = current.points
        val strokes = try {
            from.indices.map { i ->
                val path = Path().apply {
                    moveTo(from[i].first, from[i].second)
                    lineTo(targets[i].first, targets[i].second)
                }
                previous?.get(i)?.continueStroke(path, 0, durationMs, willContinue)
                    ?: GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "P4b finger segment not built", e)
            if (contact === current) contact = null
            return
        }
        current.strokes = strokes
        current.points = targets
        current.inFlight = true
        val reaches = current.trail.indexOfLast { it.second == targets }
        val gesture = GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build()
        val accepted = dispatchSafely(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (reaches >= 0) current.delivered = maxOf(current.delivered, reaches)
                    onSegmentDone(current, willContinue)
                }

                override fun onCancelled(gestureDescription: GestureDescription) = onRefused(current)
            },
        )
        if (!accepted) onRefused(current)
    }

    private fun onSegmentDone(current: Contact, continued: Boolean) {
        current.inFlight = false
        if (!continued || current.finished) return
        if (current.lifting) {
            finish(current)
            return
        }
        if (contact !== current) return
        val next = current.pending
        current.pending = null
        // Holding still: a short, nearly still segment keeps the finger down until the hand moves or lets go.
        if (next != null) send(current, next, MOVE_SEGMENT_MS) else send(current, nudge(current), HOLD_SEGMENT_MS)
    }

    private fun onRefused(current: Contact) {
        current.inFlight = false
        current.strokes = null
        current.pending = null
        // Replaced or stopped by AirTouch itself (a new gesture cancels the old one): not a refusal.
        if (current.abandoned || (contact !== current && !current.lifting)) return
        if (!liveRefused) {
            liveRefused = true
            Log.w(TAG, "P4b live finger refused by the system: drags and zooms now play back when the hand lets go")
            onLiveRefused?.invoke()
        }
        current.live = false
        if (current.lifting) {
            current.lifting = false
            if (contact === current) contact = null
            playBack(current)
        }
    }

    /** One gesture tracing what the hand did since the screen last saw the fingers, at the pace it did it. */
    private fun playBack(current: Contact) {
        val trail = current.trail.subList(current.delivered.coerceAtMost(current.trail.lastIndex), current.trail.size)
        if (trail.size < 2) return
        val fingers = trail.first().second.size
        val length = (0 until fingers).maxOf { i ->
            trail.zipWithNext().sumOf { (a, b) -> hypot(b.second[i].first - a.second[i].first, b.second[i].second - a.second[i].second).toDouble() }
        }
        // A drag or zoom that went nowhere would play back as a tap with one or two fingers: skip it.
        if (length < MIN_PLAYBACK_PX) return
        val durationMs = (trail.last().first - trail.first().first).coerceIn(PLAYBACK_MIN_MS, PLAYBACK_MAX_MS)
        val strokes = (0 until fingers).map { i ->
            val path = Path().apply {
                moveTo(trail[0].second[i].first, trail[0].second[i].second)
                for (k in 1 until trail.size) lineTo(trail[k].second[i].first, trail[k].second[i].second)
            }
            GestureDescription.StrokeDescription(path, 0, durationMs)
        }
        dispatchOnce(strokes, durationMs, if (current.zoom) "zoom played back" else "drag played back")
    }

    private fun dispatchOnce(strokes: List<GestureDescription.StrokeDescription>, durationMs: Long, what: String) {
        busyUntilMs = SystemClock.uptimeMillis() + durationMs + SETTLE_MS
        val gesture = runCatching { GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build() }
            .onFailure { Log.w(TAG, "P4b $what: not built", it) }
            .getOrNull() ?: return
        val accepted = dispatchSafely(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "P4b $what: completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.i(TAG, "P4b $what: cancelled")
                }
            },
        )
        if (!accepted) {
            busyUntilMs = 0
            Log.w(TAG, "P4b $what: refused by the system")
        }
    }

    /**
     * Android throws for gestures it considers malformed. An exception escaping an accessibility service makes the
     * system disable it (as a crash did on 24 Oct 2026), so a bad gesture is logged and dropped instead.
     */
    private fun dispatchSafely(gesture: GestureDescription, callback: AccessibilityService.GestureResultCallback): Boolean =
        try {
            service.dispatchGesture(gesture, callback, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "P4b gesture not sent", e)
            false
        }

    private companion object {
        const val TAG = "AirTouch"

        /** Shorter than the 50 ms between camera frames, so each move has played before the next arrives. */
        const val MOVE_SEGMENT_MS = 40L
        const val HOLD_SEGMENT_MS = 60L
        const val LIFT_SEGMENT_MS = 20L
        const val LONG_PRESS_MS = 700L
        const val SETTLE_MS = 100L
        const val PLAYBACK_MIN_MS = 150L
        const val PLAYBACK_MAX_MS = 2_000L
        const val MIN_PLAYBACK_PX = 12f
        const val STALE_FINGER_MS = 3_000L
    }
}
