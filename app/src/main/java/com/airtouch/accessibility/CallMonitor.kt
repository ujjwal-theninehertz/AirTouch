package com.airtouch.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Incoming calls, for the call gestures (✊ reject, ✋ answer): reports when a call starts and stops ringing, and
 * answers or rejects it through Android's call service. Both need the Phone permission (READ_PHONE_STATE to see
 * the ringing on Android 12 and later, ANSWER_PHONE_CALLS to act); without it nothing happens and [start] says so.
 * Nothing about the call (number, contact) is read. Call from the main thread.
 */
class CallMonitor(
    private val context: Context,
    private val onRingingChanged: (Boolean) -> Unit,
    /** The call answered by gesture has connected (set the speaker now: before, the phone resets the route). */
    private val onAnsweredCallConnected: () -> Unit = {},
) {

    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var callback: Any? = null

    @Volatile
    var ringing = false
        private set

    private var answeredByGesture = false

    val permitted: Boolean get() = hasPermission(context)

    /** Starts watching; false if the Phone permission is missing. */
    fun start(): Boolean {
        if (callback != null) return true
        if (!permitted) return false
        try {
            callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = update(state)
                }.also { telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), it) }
            } else {
                @Suppress("DEPRECATION") // TelephonyCallback above replaces it from Android 12.
                object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = update(state)
                }.also { telephony.listen(it, PhoneStateListener.LISTEN_CALL_STATE) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "P4c call state refused", e)
            return false
        }
        return true
    }

    fun stop() {
        when (val registered = callback) {
            is TelephonyCallback -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) telephony.unregisterTelephonyCallback(registered)
            is PhoneStateListener -> @Suppress("DEPRECATION") telephony.listen(registered, PhoneStateListener.LISTEN_NONE)
        }
        callback = null
        update(TelephonyManager.CALL_STATE_IDLE)
    }

    /** Returns what happened, for the log. */
    @Suppress("DEPRECATION") // Deprecated for system apps (InCallService); the documented way for others, still working.
    fun answer(): String = act {
        answeredByGesture = true
        it.acceptRingingCall()
        "answered"
    }

    /**
     * Routes the call to the loudspeaker; returns what happened. Android 12+ chooses the communication device;
     * before that, the speakerphone switch. Either may be refused for a call the phone app owns, so the caller
     * falls back to pressing the call screen's Speaker button.
     */
    fun speakerOn(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null && audio.setCommunicationDevice(speaker)) "speaker on" else "speaker refused"
            } else {
                @Suppress("DEPRECATION") // setCommunicationDevice above replaces it from Android 12.
                audio.isSpeakerphoneOn = true
                @Suppress("DEPRECATION")
                if (audio.isSpeakerphoneOn) "speaker on" else "speaker refused"
            }
        } catch (e: RuntimeException) {
            "speaker refused: ${e.message}"
        }
    }

    /** Whether the call is on the loudspeaker now. */
    fun speakerIsOn(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn
        }
    }

    @Suppress("DEPRECATION")
    fun reject(): String = act { manager ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) "rejecting needs Android 9" else if (manager.endCall()) "rejected" else "not rejected"
    }

    private fun act(block: (TelecomManager) -> String): String {
        if (!permitted) return "no Phone permission"
        return try {
            block(context.getSystemService(TelecomManager::class.java))
        } catch (e: SecurityException) {
            "refused: ${e.message}"
        }
    }

    private fun update(state: Int) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && answeredByGesture) {
            answeredByGesture = false
            onAnsweredCallConnected()
        }
        if (state == TelephonyManager.CALL_STATE_IDLE) answeredByGesture = false
        val now = state == TelephonyManager.CALL_STATE_RINGING
        if (now == ringing) return
        ringing = now
        onRingingChanged(now)
    }

    companion object {
        private const val TAG = "AirTouch"
        val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

        fun hasPermission(context: Context): Boolean =
            PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
