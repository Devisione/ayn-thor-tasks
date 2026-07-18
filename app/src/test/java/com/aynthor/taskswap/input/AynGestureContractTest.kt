package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AynGestureContractTest {

    @Test
    fun longAyn_opensAllApps_notMinimizeAll() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN)
        assertEquals(
            listOf(ButtonGestures.Action.Custom(GestureSettings.Slot.AYN_LONG)),
            g.onAynHoldTimeout().actions
        )
        assertEquals(
            ButtonGestures.Action.OpenAllAppsList,
            GestureSettings.defaultAction(GestureSettings.Slot.AYN_LONG).toButtonAction()
        )
        assertFalse(
            GestureSettings.defaultAction(GestureSettings.Slot.AYN_LONG).toButtonAction()
                is ButtonGestures.Action.MinimizeAllDisplays
        )
    }

    @Test
    fun shortAyn_consumesAndSchedulesGpioReplay() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN).consume)
        val up = g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP)
        assertTrue(up.consume)
        assertTrue(up.scheduleAynGpioInject)
    }
}
