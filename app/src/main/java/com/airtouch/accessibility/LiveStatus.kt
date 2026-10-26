package com.airtouch.accessibility

import com.airtouch.settings.GestureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hands-free mode, as the app's screens see it. The service and the activities share one process. */
enum class LiveState {
    /** The accessibility service is off, so nothing can act in other apps. */
    UNAVAILABLE,
    OFF,
    ON,
    PAUSED,
}

object LiveStatus {
    private val _state = MutableStateFlow(LiveState.UNAVAILABLE)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    @Volatile
    internal var service: AirTouchAccessibilityService? = null

    internal fun set(state: LiveState) {
        android.util.Log.i("AirTouch", "P4 state $state (service=${service?.let { System.identityHashCode(it) }})")
        _state.value = state
    }

    /** Starts hands-free mode in [mode], or in the mode saved in settings. */
    fun start(mode: GestureMode? = null) = service?.startLive(mode)

    fun stop() = service?.stopLive()

    fun onPermissionsChanged() = service?.onPermissionsChanged()
}
