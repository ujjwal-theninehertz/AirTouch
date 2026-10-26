package com.airtouch.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * What controls the phone: the hand or the face, one at a time (25 Oct 2026). Turning one on turns the other off,
 * and only its model runs, so the camera's frames all go to it.
 */
enum class GestureMode { HANDS, FACE }

/** Grows with each phase: thresholds in P3, gesture maps and profiles in P4, controls in P5. */
data class AirTouchSettings(
    val setupComplete: Boolean = false,

    /** The app 🤙 opens, by package name; null until the user picks one. */
    val shortcutApp: String? = null,

    /** Hand gestures or face gestures (P4c); hands until the user turns face gestures on. */
    val gestureMode: GestureMode = GestureMode.HANDS,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AirTouchSettings> = context.dataStore.data.map { prefs ->
        AirTouchSettings(
            setupComplete = prefs[SETUP_COMPLETE] ?: false,
            shortcutApp = prefs[SHORTCUT_APP],
            gestureMode = GestureMode.entries.firstOrNull { it.name == prefs[GESTURE_MODE] } ?: GestureMode.HANDS,
        )
    }

    suspend fun setGestureMode(mode: GestureMode) {
        context.dataStore.edit {
            it[GESTURE_MODE] = mode.name
            // Before modes, face gestures were a switch on top of hand gestures.
            it.remove(OLD_FACE_GESTURES)
        }
    }

    suspend fun setShortcutApp(packageName: String?) {
        context.dataStore.edit { if (packageName == null) it.remove(SHORTCUT_APP) else it[SHORTCUT_APP] = packageName }
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[SETUP_COMPLETE] = complete }
    }

    private companion object {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val SHORTCUT_APP = stringPreferencesKey("shortcut_app")
        val GESTURE_MODE = stringPreferencesKey("gesture_mode")
        val OLD_FACE_GESTURES = booleanPreferencesKey("face_gestures")
    }
}
