package com.airtouch.gesture.mapping

import com.airtouch.gesture.model.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutMenuTest {

    @Test
    fun eachDirectionPicksItsItem() {
        val expected = mapOf(
            GestureType.FLICK_UP to ShortcutMenu.Item.UP,
            GestureType.SWIPE_DOWN to ShortcutMenu.Item.DOWN,
            GestureType.FLICK_LEFT to ShortcutMenu.Item.LEFT,
            GestureType.SWIPE_RIGHT to ShortcutMenu.Item.RIGHT,
        )
        for ((gesture, item) in expected) {
            val menu = ShortcutMenu().apply { open(0) }
            assertEquals(ShortcutMenu.Outcome.Chosen(item), menu.onGesture(gesture, 1_000))
            assertFalse("closes after a pick", menu.isOpen)
        }
        assertEquals(ScreenAction.Notifications, ShortcutMenu.Item.UP.action)
        assertEquals(ScreenAction.QuickSettings, ShortcutMenu.Item.DOWN.action)
        assertEquals(ScreenAction.Screenshot, ShortcutMenu.Item.LEFT.action)
        assertEquals(ScreenAction.LockScreen, ShortcutMenu.Item.RIGHT.action)
    }

    @Test
    fun anyOtherGestureClosesItUnused() {
        val menu = ShortcutMenu().apply { open(0) }
        assertEquals(ShortcutMenu.Outcome.Dismissed(passThrough = false), menu.onGesture(GestureType.V_SIGN, 500))
        assertFalse(menu.isOpen)
        assertEquals(ShortcutMenu.Outcome.NotOpen, menu.onGesture(GestureType.FLICK_UP, 600))
    }

    @Test
    fun thumbsDownStillPausesWhileTheMenuIsOpen() {
        val menu = ShortcutMenu().apply { open(0) }
        assertEquals(ShortcutMenu.Outcome.Dismissed(passThrough = true), menu.onGesture(GestureType.THUMBS_DOWN, 500))
    }

    @Test
    fun theMenuTimesOutOnce() {
        val menu = ShortcutMenu(timeoutMs = 5_000).apply { open(0) }
        assertFalse(menu.expire(4_999))
        assertTrue(menu.expire(5_000))
        assertFalse("only once", menu.expire(6_000))
        assertEquals(ShortcutMenu.Outcome.NotOpen, menu.onGesture(GestureType.FLICK_UP, 6_000))
    }

    @Test
    fun aLateGestureAfterTheTimeoutIsHandledAsUsual() {
        val menu = ShortcutMenu(timeoutMs = 5_000).apply { open(0) }
        assertEquals(ShortcutMenu.Outcome.NotOpen, menu.onGesture(GestureType.FLICK_UP, 7_000))
    }

    @Test
    fun aClosedMenuLetsEverythingThrough() {
        assertEquals(ShortcutMenu.Outcome.NotOpen, ShortcutMenu().onGesture(GestureType.FLICK_UP, 0))
    }
}
