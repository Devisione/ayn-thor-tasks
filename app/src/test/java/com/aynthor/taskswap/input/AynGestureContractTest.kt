package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: long AYN opens the system ALL-APPS list ("список приложений").
 */
class AynGestureContractTest {

    @Test
    fun ayn_longPress_opensAllAppsList() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0)
        val timeout = g.onAynHoldTimeout()
        assertEquals(
            listOf(ButtonGestures.Action.OpenAllAppsList),
            timeout.actions
        )
        assertFalse(timeout.actions.any { it is ButtonGestures.Action.MinimizeAllDisplays })
    }

    @Test
    fun ayn_down_isConsumed_soFirmwareCannotKillBottomScreen() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0).consume)
    }
}
