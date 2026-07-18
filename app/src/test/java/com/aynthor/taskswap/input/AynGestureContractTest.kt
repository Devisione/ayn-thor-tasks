package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: long AYN opens the system ALL-APPS list ("список приложений") by default.
 */
class AynGestureContractTest {

    @Test
    fun ayn_longPress_emitsAynLongSlot_defaultResolvesToAllApps() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0)
        val timeout = g.onAynHoldTimeout()
        assertEquals(
            listOf(ButtonGestures.Action.Custom(GestureSettings.Slot.AYN_LONG)),
            timeout.actions
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
    fun ayn_down_isConsumed_soFirmwareCannotKillBottomScreen() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0).consume)
    }
}
