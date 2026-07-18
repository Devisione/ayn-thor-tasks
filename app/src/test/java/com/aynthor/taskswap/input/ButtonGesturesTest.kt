package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonGesturesTest {

    @Test
    fun ayn_short_consumesAndSchedulesGpioReplay() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN).consume)
        val up = g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP)
        assertTrue(up.consume)
        assertTrue(up.scheduleAynGpioInject)
    }

    @Test
    fun ayn_long_thenUp_consumesWithoutGpioReplay() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN)
        assertEquals(
            listOf(ButtonGestures.Action.Custom(GestureSettings.Slot.AYN_LONG)),
            g.onAynHoldTimeout().actions
        )
        val up = g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP)
        assertTrue(up.consume)
        assertFalse(up.scheduleAynGpioInject)
    }

    @Test
    fun back_and_home_areConsumed() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN).consume)
        assertTrue(g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN).consume)
    }

    @Test
    fun home_short_schedulesSystemHome() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN)
        assertTrue(
            g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.UP).scheduleHomeSystemInject
        )
    }

    @Test
    fun back_doubleTap_emitsBackDoubleSlot() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN)
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.UP)
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN)
        assertEquals(
            listOf(ButtonGestures.Action.Custom(GestureSettings.Slot.BACK_DOUBLE)),
            g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.UP).actions
        )
    }

    @Test
    fun defaultSlots_resolveToLegacyActions() {
        assertEquals(
            ButtonGestures.Action.MinimizeAllDisplays,
            GestureSettings.defaultAction(GestureSettings.Slot.HOME_LONG).toButtonAction()
        )
        assertEquals(
            ButtonGestures.Action.OpenAllAppsList,
            GestureSettings.defaultAction(GestureSettings.Slot.AYN_LONG).toButtonAction()
        )
    }
}
