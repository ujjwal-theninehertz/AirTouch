package com.airtouch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.airtouch.accessibility.LiveStatus

/**
 * An invisible screen that starts hands-free mode and closes at once. The Quick Settings tile opens it, because
 * Android only lets an app start using the camera while something of the app is on screen.
 */
class StartLiveActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        LiveStatus.start()
        finish()
    }
}
