package com.airtouch.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppTrackerTest {

    private val tracker = ForegroundAppTracker("com.airtouch")

    @Test
    fun appsInFrontAreTracked() {
        tracker.onWindowState("com.instagram.android", "com.instagram.mainactivity.MainActivity")
        assertEquals("com.instagram.android", tracker.current)
        tracker.onWindowState("com.miui.home", "com.miui.home.launcher.Launcher")
        assertEquals("com.miui.home", tracker.current)
    }

    @Test
    fun airTouchsOwnOverlaysNeverTakeOverTheApp() {
        // Live test, 24 Oct 2026: the cursor and menu overlays appearing made every gesture "not used in this app".
        tracker.onWindowState("com.instagram.android", "com.instagram.mainactivity.MainActivity")
        tracker.onWindowState("com.airtouch", "com.airtouch.accessibility.CursorView")
        tracker.onWindowState("com.airtouch", "android.widget.LinearLayout")
        tracker.onWindowState("com.airtouch", "android.widget.TextView")
        tracker.onWindowState("com.airtouch", null)
        assertEquals("com.instagram.android", tracker.current)
    }

    @Test
    fun airTouchsOwnScreensDoCount() {
        tracker.onWindowState("com.airtouch", "com.airtouch.ui.MainActivity")
        assertEquals("com.airtouch", tracker.current)
        tracker.onWindowState("com.instagram.android", "com.instagram.mainactivity.MainActivity")
        tracker.onWindowState("com.airtouch", "com.airtouch.ui.practice.PracticeActivity")
        assertEquals("com.airtouch", tracker.current)
    }

    @Test
    fun systemUiAndTheKeyboardAreIgnored() {
        tracker.onWindowState("com.whatsapp", "com.whatsapp.Conversation")
        tracker.onWindowState("com.android.systemui", "android.widget.FrameLayout")
        tracker.onWindowState("miui.systemui.plugin", "android.widget.FrameLayout")
        tracker.onWindowState("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow")
        assertEquals("com.whatsapp", tracker.current)
    }

    @Test
    fun eventsWithoutAPackageAreIgnored() {
        tracker.onWindowState(null, "anything")
        assertNull(tracker.current)
    }
}
