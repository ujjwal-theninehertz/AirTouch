package com.airtouch.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.airtouch.AirTouchApp
import com.airtouch.R
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.face.FaceEngine
import com.airtouch.gesture.mapping.ActionMapper
import com.airtouch.gesture.mapping.ScreenAction
import com.airtouch.gesture.mapping.ShortcutMenu
import com.airtouch.gesture.model.GestureEvent
import com.airtouch.gesture.model.GestureType
import com.airtouch.gesture.model.Pointer
import com.airtouch.gesture.model.Touch
import com.airtouch.settings.GestureMode
import com.airtouch.pipeline.PerceptionPipeline
import com.airtouch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Host of the live pipeline (AD-1). While hands-free mode is on, it runs the camera, hand detection and
 * gesture engine, maps each gesture to an action for the app in front and performs it, draws the cursor and
 * injects the virtual finger. It is a camera foreground service for that time, with a notification that can stop it.
 */
class AirTouchAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Lazy: the package name is only available once the service is attached; events arrive after that.
    private val tracker by lazy { ForegroundAppTracker(packageName) }
    private val mapper = ActionMapper()
    private val menu = ShortcutMenu()
    private lateinit var geometry: ScreenGeometry
    private lateinit var touch: TouchInjector
    private lateinit var executor: ActionExecutor
    private lateinit var inspector: ScreenInspector
    private lateinit var hud: HudOverlay
    private lateinit var cursor: CursorOverlay
    private lateinit var menuOverlay: ShortcutMenuOverlay
    private lateinit var calls: CallMonitor
    private var pipeline: PerceptionPipeline? = null
    /** What the hand engine and the face engine last reported, for the status (written off the main thread). */
    @Volatile
    private var lastEngineState: GestureEngine.State? = null

    @Volatile
    private var lastFacePresence = FaceEngine.Presence.NO_FACE

    /** The running session's gesture mode, or null while hands-free mode is off. Main thread. */
    private var liveMode: GestureMode? = null
    private var receiverRegistered = false
    private var settingsJob: Job? = null
    private var startJob: Job? = null
    private var cursorLogged = false
    private var lastRotation = -1
    private var scrollCount = 0
    private val pageUnknownIn = mutableSetOf<String?>()

    /**
     * Turning the phone: the camera must follow at once or every gesture points a quarter turn off. A display
     * listener, because turning straight from one landscape to the other is no configuration change.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            if (rotation == lastRotation) return
            lastRotation = rotation
            val perception = pipeline ?: return
            perception.setDisplayRotation(rotation)
            perception.screenAspect = screenAspect()
            // A finger down in the old orientation would land somewhere else now.
            touch.cancel()
            Log.i(TAG, "P4 rotation ${rotation * 90}°")
        }
    }

    /** The app 🤙 opens, kept current from settings. */
    @Volatile
    private var shortcutApp: String? = null

    /** The gesture mode in settings, for the next start. */
    @Volatile
    private var gestureMode = GestureMode.HANDS

    private val menuTimeout = Runnable {
        if (menu.expire(SystemClock.uptimeMillis())) {
            menuOverlay.hide()
            hud.flash("Shortcuts closed")
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopLive()
                ACTION_TOGGLE_PAUSE -> togglePause("notification")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        geometry = ScreenGeometry(this)
        touch = TouchInjector(this, geometry).also { injector ->
            injector.onLiveRefused = { main.post { hud.flash("On this phone, drags and zooms happen when you let go") } }
        }
        executor = ActionExecutor(this, geometry, touch)
        inspector = ScreenInspector(this)
        hud = HudOverlay(this)
        cursor = CursorOverlay(this)
        menuOverlay = ShortcutMenuOverlay(this)
        calls = CallMonitor(this, ::onRingingChanged, onAnsweredCallConnected = { main.postDelayed(::putCallOnSpeaker, SPEAKER_DELAY_MS) })
        if (!receiverRegistered) {
            val actions = IntentFilter(ACTION_STOP).apply { addAction(ACTION_TOGGLE_PAUSE) }
            ContextCompat.registerReceiver(this, controlReceiver, actions, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            (application as AirTouchApp).graph.settings.settings.collect {
                shortcutApp = it.shortcutApp
                gestureMode = it.gestureMode
            }
        }
        LiveStatus.service = this
        LiveStatus.set(LiveState.OFF)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = tracker.onEvent(event)

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pipeline?.screenAspect = screenAspect()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopLive()
        // Android may connect the next instance before this one unbinds; never clear the newer one.
        if (LiveStatus.service === this) {
            LiveStatus.service = null
            LiveStatus.set(LiveState.UNAVAILABLE)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopLive()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(controlReceiver)
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    /**
     * Starts hands-free mode in [requested] mode, or the one in settings. Call from the main thread, ideally while
     * AirTouch is on screen.
     */
    fun startLive(requested: GestureMode? = null) {
        if (pipeline != null || startJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Hands-free mode needs the camera permission")
            return
        }
        // CAMERA is granted, which startForeground(camera) requires from Android 14. It must happen now, while
        // AirTouch is on screen; the camera itself opens once the models are loaded.
        val mode = requested ?: gestureMode
        liveMode = mode
        lastEngineState = null
        lastFacePresence = FaceEngine.Presence.NO_FACE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        registry.currentState = Lifecycle.State.STARTED
        hud.show()
        hud.setStatus("AirTouch · starting…")
        val startedAtMs = SystemClock.elapsedRealtime()
        startJob = scope.launch {
            // Loading a model onto the GPU takes seconds: on the main thread that froze AirTouch's screen for 8 s and
            // Android reported it as not responding (25 Oct 2026). Whatever is built is either used or closed, even
            // if Stop comes meanwhile.
            val built = withContext(NonCancellable + Dispatchers.Default) { runCatching { PerceptionPipeline(applicationContext, mode) } }
            val perception = built.getOrElse { e ->
                Log.e(TAG, "P4 live could not start", e)
                hud.flash("AirTouch could not start the camera")
                stopLive()
                return@launch
            }
            if (!isActive) {
                perception.close()
                return@launch
            }
            Log.i(TAG, "P4 ${mode.name.lowercase()} model loaded in ${SystemClock.elapsedRealtime() - startedAtMs} ms")
            finishStart(perception)
        }
    }

    private fun finishStart(perception: PerceptionPipeline) {
        perception.screenAspect = screenAspect()
        perception.decisionListener = ::onDecision
        perception.faceListener = ::onFaceDecision
        val displays = getSystemService(DisplayManager::class.java)
        lastRotation = displays.getDisplay(Display.DEFAULT_DISPLAY).rotation
        perception.start(this, lastRotation)
        displays.registerDisplayListener(displayListener, main)
        pipeline = perception
        lastEngineState = null
        refreshStatus()
        if (!calls.start()) Log.i(TAG, "P4c call gestures off: no Phone permission")
        LiveStatus.set(LiveState.ON)
        Log.i(TAG, "P4 live started")
    }

    /** The Phone permission may have just been granted on AirTouch's screen: start watching for calls. */
    fun onPermissionsChanged() {
        if (pipeline != null && calls.start()) Log.i(TAG, "P4c call gestures on")
    }

    /** Main thread. While a call rings only the call gestures act, and nothing else is left half done. */
    private fun onRingingChanged(ringing: Boolean) {
        val perception = pipeline ?: return
        perception.callRinging = ringing
        if (ringing) {
            touch.cancel()
            closeMenu()
            hud.flash("📞 Call · ${StatusText.callHint(liveMode ?: gestureMode)}")
        }
        refreshStatus()
        Log.i(TAG, "P4c call ${if (ringing) "ringing" else "no longer ringing"}")
    }

    fun stopLive() {
        // A start still loading its models closes what it built once it is done.
        val starting = startJob?.isActive == true
        startJob?.cancel()
        startJob = null
        val perception = pipeline
        if (perception == null && !starting) return
        pipeline = null
        if (perception != null) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            perception.close()
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        touch.cancel()
        cursor.remove()
        closeMenu()
        calls.stop()
        hud.hide()
        liveMode = null
        lastEngineState = null
        lastFacePresence = FaceEngine.Presence.NO_FACE
        LiveStatus.set(LiveState.OFF)
        Log.i(TAG, "P4 live stopped")
    }

    /** Detector thread: called for every frame. */
    private fun onDecision(output: GestureEngine.Output) {
        output.rejected?.let { Log.i(TAG, "P4 rejected $it") }
        val app = tracker.current
        val pointer = output.pointer
        val command = output.touch
        // The cursor and the virtual finger follow every frame, in order.
        main.post { safely("touch") { onContinuous(pointer, command, app) } }
        val event = output.event
        if (event == null) {
            if (output.state != lastEngineState) {
                lastEngineState = output.state
                main.post(::refreshStatus)
            }
            return
        }
        val action = mapper.map(event, app)
        main.post { safely("action") { act(event, action, app) } }
    }

    /**
     * An exception escaping an accessibility service makes Android disable it until it is turned on again (a
     * crash on 24 Oct 2026 removed hands-free mode from the main screen). Whatever fails is logged, any finger
     * left down is lifted, and AirTouch keeps running.
     */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e(TAG, "P4b $what failed; carrying on", e)
            runCatching { touch.cancel() }
        }
    }

    /** MediaPipe's face thread: face gestures act exactly as hand gestures do, call mode and menus included. */
    private fun onFaceDecision(output: FaceEngine.Output) {
        output.rejected?.let { Log.i(TAG, "P4c rejected $it") }
        if (output.presence != lastFacePresence) {
            lastFacePresence = output.presence
            main.post(::refreshStatus)
        }
        val event = output.event ?: return
        val app = tracker.current
        val action = mapper.map(event, app)
        main.post { safely("face action") { act(event, action, app) } }
    }

    private fun onContinuous(pointer: Pointer?, command: Touch?, app: String?) {
        if (pipeline == null) return
        // A ringing call: no cursor and no finger, whatever app is in front.
        val blocked = isBlocked(app) || calls.ringing
        val (width, height) = geometry.size()
        val shown = pointer.takeUnless { blocked }
        if ((shown != null) != cursorLogged) {
            cursorLogged = shown != null
            Log.i(TAG, if (shown != null) "P4b cursor shown in $app" else "P4b cursor hidden")
        }
        cursor.update(shown, width, height)
        if (blocked) {
            // Calls, the camera and AirTouch itself never get a finger, not even one already down.
            touch.cancel()
            return
        }
        if (command == null) return
        if (menu.isOpen) closeMenu()
        when (command) {
            is Touch.Tap -> {
                val (px, py) = aim(magnet(command.x, command.y, width, height))
                executor.tapAt(px, py) { Log.i(TAG, "P4b tap in $app: $it") }
            }
            is Touch.DoubleTap -> {
                val (px, py) = aim(magnet(command.x, command.y, width, height))
                executor.doubleTapAt(px, py) { Log.i(TAG, "P4b double tap in $app: $it") }
            }
            is Touch.Down -> if (command.hold) {
                val (px, py) = aim(magnet(command.x, command.y, width, height))
                touch.longPress(px / width, py / height)
            } else {
                touch.down(command.x, command.y)
            }
            is Touch.Move -> touch.move(command.x, command.y)
            is Touch.ZoomStart -> touch.zoomStart(command.x, command.y)
            is Touch.Zoom -> touch.zoom(command.factor)
            Touch.Up, Touch.ZoomEnd -> touch.up()
        }
        if (command !is Touch.Move && command !is Touch.Zoom) Log.i(TAG, "P4b touch $command in $app")
    }

    /** Calls, the camera and AirTouch itself get no actions, except AirTouch's cursor tap test. */
    private fun isBlocked(app: String?): Boolean =
        app != null && app in ActionMapper.DEFAULT_BLOCKED && !(app == packageName && TapProbe.testScreenShown)

    /** Notes where a cursor tap is aimed, for the tap test to compare with where it lands. */
    private fun aim(point: Pair<Float, Float>): Pair<Float, Float> {
        TapProbe.lastAim = TapProbe.Aim(point.first, point.second, SystemClock.uptimeMillis())
        return point
    }

    /** The cursor's magnet: a tap just off a button lands on it. Returns pixels. */
    private fun magnet(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> {
        val (px, py) = TouchGeometry.toPixels(x, y, width, height)
        val radius = MAGNET_RADIUS_DP * resources.displayMetrics.density
        val snapped = ScreenTargets.snap(px, py, inspector.clickablesAt(px, py), radius)
        if (snapped != px to py) Log.i(TAG, "P4b magnet moved the tap ${hypot(snapped.first - px, snapped.second - py).toInt()} px")
        return snapped
    }

    private fun act(event: GestureEvent, action: ScreenAction?, app: String?) {
        if (pipeline == null) return
        if (calls.ringing) {
            actDuringCall(event)
            return
        }
        if (menu.isOpen) {
            main.removeCallbacks(menuTimeout)
            when (val outcome = menu.onGesture(event.type, SystemClock.uptimeMillis())) {
                is ShortcutMenu.Outcome.Chosen -> {
                    menuOverlay.hide()
                    perform(outcome.item.action, event, app)
                    return
                }
                is ShortcutMenu.Outcome.Dismissed -> {
                    menuOverlay.hide()
                    if (!outcome.passThrough) {
                        hud.flash("Shortcuts closed")
                        return
                    }
                }
                ShortcutMenu.Outcome.NotOpen -> menuOverlay.hide()
            }
        }
        when (action) {
            null -> {
                // Seen but not used here (sideways in Reels, say): say so, rather than leave the user guessing.
                hud.flash("${gestureName(event.type)} · not used in this app")
                Log.i(TAG, "P4 action none for ${event.type} in $app (not mapped or blocked)")
            }
            ScreenAction.TogglePause -> togglePause("👎 in $app")
            else -> perform(action, event, app)
        }
    }

    /** The call gestures; anything else is ignored until the call stops ringing. */
    private fun actDuringCall(event: GestureEvent) {
        when (mapper.mapDuringCall(event)) {
            ScreenAction.AnswerCall -> {
                hud.flash("📞 Answering on speaker")
                Log.i(TAG, "P4c call answer by ${event.type}: ${calls.answer()}")
            }
            ScreenAction.RejectCall -> {
                hud.flash("📞 Rejected")
                Log.i(TAG, "P4c call reject by ${event.type}: ${calls.reject()}")
            }
            ScreenAction.TogglePause -> togglePause("👎 during a call")
            else -> Log.i(TAG, "P4c ${event.type} ignored while a call rings")
        }
    }

    /**
     * A call answered by gesture goes on speaker, since the phone is out of reach. The audio route first; if the
     * phone refuses it (it owns the call), the call screen's own Speaker button.
     */
    private fun putCallOnSpeaker() {
        val routed = calls.speakerOn()
        if (calls.speakerIsOn()) {
            Log.i(TAG, "P4c call on speaker: $routed")
            return
        }
        val pressed = inspector.clickLabelled("speaker")
        Log.i(TAG, "P4c call speaker: $routed; call screen button ${if (pressed) "pressed" else "not found"}")
        if (!pressed) hud.flash("Could not turn on the speaker")
    }

    /** 👎 and the notification's Pause / Resume button. The notification follows, so its button always says what it will do. */
    private fun togglePause(source: String) {
        val perception = pipeline ?: return
        perception.paused = !perception.paused
        LiveStatus.set(if (perception.paused) LiveState.PAUSED else LiveState.ON)
        hud.flash(if (perception.paused) "AirTouch paused · ${StatusText.resumeHint(perception.mode)}" else "AirTouch resumed")
        refreshStatus()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(perception.paused))
        Log.i(TAG, "P4 action ${if (perception.paused) "pause" else "resume"} from $source")
    }

    private fun perform(action: ScreenAction, event: GestureEvent, app: String?) {
        val log: (String) -> Unit = { result -> Log.i(TAG, "P4 action $action for ${event.type} in $app: $result") }
        when (action) {
            ScreenAction.ShortcutMenu -> openMenu()
            ScreenAction.OpenChosenApp -> openChosenApp(log)
            ScreenAction.Screenshot -> screenshot(log)
            is ScreenAction.Seek -> seek(action.forward, log)
            is ScreenAction.Volume -> {
                val result = executor.volume(action.up)
                hud.flash("${if (action.up) "🔊" else "🔉"} Volume $result")
                log(result)
            }
            is ScreenAction.Scroll -> {
                hud.flash(label(action))
                executor.perform(action, log)
                logPageChange(event, app)
            }
            else -> {
                hud.flash(label(action))
                executor.perform(action, log)
            }
        }
    }

    /**
     * The P4 exit test: one swipe must move exactly one Reel. The page on screen is read as the scroll starts and
     * again once it has settled; a second scroll in between makes the reading meaningless, so it is marked as such.
     */
    private fun logPageChange(event: GestureEvent, app: String?) {
        val before = inspector.visiblePage()
        if (before == null) {
            // Said once per app: its list does not tell which item is showing, so swipes there cannot be counted.
            if (pageUnknownIn.add(app)) Log.i(TAG, "P4 page unknown in $app: its list does not say which item is showing")
            return
        }
        val count = ++scrollCount
        main.postDelayed({
            if (pipeline == null) return@postDelayed
            if (scrollCount != count) {
                Log.i(TAG, "P4 page ${before.index}→? for ${event.type} in $app (next scroll came too soon)")
                return@postDelayed
            }
            val after = inspector.visiblePage()
            Log.i(TAG, "P4 page ${before.index}→${after?.index ?: "?"} of ${before.count} for ${event.type} in $app")
        }, PAGE_SETTLE_MS)
    }

    private fun openMenu() {
        menu.open(SystemClock.uptimeMillis())
        menuOverlay.show()
        main.removeCallbacks(menuTimeout)
        main.postDelayed(menuTimeout, MENU_TIMEOUT_MS)
        Log.i(TAG, "P4b shortcut menu open")
    }

    private fun closeMenu() {
        main.removeCallbacks(menuTimeout)
        menu.close()
        menuOverlay.hide()
    }

    /** YouTube's skip: a double-tap on that side of the player, except in Shorts, where a double-tap likes. */
    private fun seek(forward: Boolean, log: (String) -> Unit) {
        val (width, height) = geometry.size()
        when (val surface = inspector.youtube(width.toInt(), height.toInt())) {
            VideoSurface.Shorts -> {
                hud.flash("Skipping is off in Shorts")
                log("skipped: Shorts")
            }
            VideoSurface.NoVideo -> {
                hud.flash("No video playing")
                log("no player found")
            }
            is VideoSurface.Player -> {
                hud.flash(if (forward) "⏩ Forward 10 s" else "⏪ Back 10 s")
                val (px, py) = ScreenTargets.seekPoint(surface.box, forward)
                executor.doubleTapAt(px, py, log)
            }
        }
    }

    private fun openChosenApp(log: (String) -> Unit) {
        val pkg = shortcutApp
        if (pkg == null) {
            hud.flash("🤙 Choose an app in AirTouch first")
            log("no app chosen")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            hud.flash("🤙 That app is no longer installed")
            log("not installed: $pkg")
            return
        }
        val name = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: RuntimeException) {
            hud.flash("🤙 Could not open $name")
            log("failed: ${e.message}")
            return
        }
        hud.flash("🤙 Opening $name")
        // MIUI can stop apps opening others from the background without saying so: check, and say how to allow it.
        main.postDelayed({
            if (pipeline == null) return@postDelayed
            if (tracker.current == pkg) {
                log("opened")
            } else {
                hud.flash("MIUI blocked it: allow AirTouch to show pop-ups while in the background")
                log("did not open; in front: ${tracker.current}")
            }
        }, OPEN_CHECK_MS)
    }

    /** AirTouch's own overlays are hidden first, so they are never in the picture. */
    private fun screenshot(log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hud.flash("Screenshots need Android 9 or later")
            log("needs Android 9")
            return
        }
        hud.setSuppressed(true)
        cursor.setSuppressed(true)
        main.postDelayed({
            executor.perform(ScreenAction.Screenshot, log)
            main.postDelayed({
                hud.setSuppressed(false)
                cursor.setSuppressed(false)
                hud.flash("📸 Screenshot")
            }, SCREENSHOT_RESTORE_MS)
        }, SCREENSHOT_DELAY_MS)
    }

    private fun screenAspect(): Float {
        val (width, height) = geometry.size()
        return width / height
    }

    /** Main thread. The resting text on screen, from what the running mode's engine last reported. */
    private fun refreshStatus() {
        val perception = pipeline ?: return
        hud.setStatus(StatusText.of(perception.mode, perception.paused, calls.ringing, lastEngineState, lastFacePresence))
    }

    /** What the action did, since one gesture acts differently per app (right is Back in apps, a page on home screens). */
    private fun label(action: ScreenAction): String = when (action) {
        is ScreenAction.Scroll -> when (action.direction) {
            ScreenAction.Direction.FORWARD -> "↑ Next"
            ScreenAction.Direction.BACK -> "↓ Previous"
            ScreenAction.Direction.NEXT_PAGE -> "← Next page"
            ScreenAction.Direction.PREVIOUS_PAGE -> "→ Previous page"
        }
        is ScreenAction.Tap -> "Tap"
        is ScreenAction.DoubleTap -> "❤ Double tap"
        ScreenAction.PlayPause -> "⏯ Play / pause"
        is ScreenAction.Seek -> if (action.forward) "⏩ Forward 10 s" else "⏪ Back 10 s"
        is ScreenAction.Track -> if (action.next) "⏭ Next song" else "⏮ Previous song"
        is ScreenAction.Volume -> if (action.up) "🔊 Volume up" else "🔉 Volume down"
        ScreenAction.Back -> "Back"
        ScreenAction.Home -> "Home"
        ScreenAction.Recents -> "Recent apps"
        ScreenAction.Notifications -> "Notifications"
        ScreenAction.QuickSettings -> "Quick settings"
        ScreenAction.Screenshot -> "📸 Screenshot"
        ScreenAction.LockScreen -> "🔒 Lock screen"
        ScreenAction.OpenChosenApp -> "🤙 Open app"
        ScreenAction.ShortcutMenu -> "🤘 Shortcuts"
        ScreenAction.AnswerCall -> "📞 Answer"
        ScreenAction.RejectCall -> "📞 Reject"
        ScreenAction.TogglePause -> "👎"
    }

    private fun gestureName(type: GestureType): String = when (type) {
        GestureType.SWIPE_UP -> "Swipe up"
        GestureType.SWIPE_DOWN -> "Swipe down"
        GestureType.SWIPE_LEFT -> "Swipe left"
        GestureType.SWIPE_RIGHT -> "Swipe right"
        GestureType.FLICK_UP -> "Flick up"
        GestureType.FLICK_DOWN -> "Flick down"
        GestureType.FLICK_LEFT -> "Flick left"
        GestureType.FLICK_RIGHT -> "Flick right"
        GestureType.PINCH -> "Pinch"
        GestureType.FIST -> "Fist"
        GestureType.THUMBS_DOWN -> "👎"
        GestureType.V_SIGN -> "✌"
        GestureType.THUMBS_UP -> "👍"
        GestureType.THREE_FINGERS -> "Three fingers"
        GestureType.ROCK -> "🤘"
        GestureType.CALL -> "🤙"
        GestureType.DOUBLE_PINCH -> "Double pinch"
        GestureType.FIST_RAISE, GestureType.FIST_LOWER -> "Volume"
        GestureType.OPEN_PALM -> "✋ Open hand"
        GestureType.FACE_UP -> "Face up"
        GestureType.FACE_DOWN -> "Face down"
        GestureType.FACE_LEFT -> "Face left"
        GestureType.FACE_RIGHT -> "Face right"
        GestureType.EYES_CLOSED -> "Eyes closed"
        GestureType.BROW_LEFT -> "Left eyebrow"
        GestureType.BROW_RIGHT -> "Right eyebrow"
        GestureType.BROW_LEFT_TWICE -> "Left eyebrow twice"
        GestureType.BROW_RIGHT_TWICE -> "Right eyebrow twice"
        GestureType.BROWS_TWICE -> "Both eyebrows twice"
        GestureType.BROW_LEFT_HOLD, GestureType.BROW_RIGHT_HOLD -> "Eyebrow volume"
        GestureType.KISS -> "😗 Kiss"
    }

    private fun notification(paused: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.live_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(ACTION_STOP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(this, 2, Intent(ACTION_TOGGLE_PAUSE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (paused) R.string.live_notification_title_paused else R.string.live_notification_title))
            .setContentText(getString(if (liveMode == GestureMode.FACE) R.string.live_notification_text_face else R.string.live_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(if (paused) R.string.live_notification_resume else R.string.live_notification_pause), pause)
            .addAction(0, getString(R.string.live_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val TAG = "AirTouch"
        const val CHANNEL_ID = "live"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.airtouch.action.STOP_LIVE"
        const val ACTION_TOGGLE_PAUSE = "com.airtouch.action.TOGGLE_PAUSE"

        /** About a fingertip's width: a tap this close to a button lands on it. */
        const val MAGNET_RADIUS_DP = 32

        /** A little longer than [ShortcutMenu]'s own timeout, so the menu has expired when this runs. */
        const val MENU_TIMEOUT_MS = 5_050L
        const val OPEN_CHECK_MS = 2_000L

        /** The call screen and its audio route settle first; set earlier, the phone resets the route to the earpiece. */
        const val SPEAKER_DELAY_MS = 800L

        /** A 250 ms scroll and the pager's snap animation are over by then. */
        const val PAGE_SETTLE_MS = 900L

        /** Long enough for the overlays to leave the screen before the capture, and for MIUI's capture animation after. */
        const val SCREENSHOT_DELAY_MS = 250L
        const val SCREENSHOT_RESTORE_MS = 1_500L
    }
}
