package com.airtouch.accessibility

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airtouch.ui.MainActivity
import com.airtouch.ui.StartLiveActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The AirTouch tile in Quick Settings, beside Wi-Fi and the torch: shows whether hands-free mode is on, paused
 * or off, and switches it. Starting needs a visible surface (Android only lets an app start the camera from one),
 * so the tile opens [StartLiveActivity], an invisible screen that starts AirTouch and closes at once.
 */
class AirTouchTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch { LiveStatus.state.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        when (LiveStatus.state.value) {
            LiveState.ON, LiveState.PAUSED -> LiveStatus.stop()
            LiveState.OFF -> open(Intent(this, StartLiveActivity::class.java))
            // The accessibility service is off: AirTouch's main screen explains how to turn it on.
            LiveState.UNAVAILABLE -> open(Intent(this, MainActivity::class.java))
        }
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") // The PendingIntent form above only exists from Android 14.
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: LiveState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            LiveState.ON, LiveState.PAUSED -> Tile.STATE_ACTIVE
            LiveState.OFF -> Tile.STATE_INACTIVE
            LiveState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                LiveState.ON -> "On"
                LiveState.PAUSED -> "Paused"
                LiveState.OFF -> "Off"
                LiveState.UNAVAILABLE -> "Set up in the app"
            }
        }
        tile.updateTile()
    }
}
