package com.aynthor.taskswap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonGesturesTest {

    @Test
    fun ayn_down_alwaysConsumed_toPreventScreenOff() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0).consume)
    }

    @Test
    fun ayn_shortPress_schedulesSystemInjectAfterWindow() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0)
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP, 40).consume)
        val timeout = g.onAynSingleTapTimeout()
        assertTrue(timeout.scheduleAynSystemInject)
    }

    @Test
    fun ayn_longPress_opensAllAppsList() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0)
        val timeout = g.onAynHoldTimeout()
        assertTrue(timeout.consume)
        assertEquals(listOf(ButtonGestures.Action.OpenAllAppsList), timeout.actions)
        val up = g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP, 1100)
        assertTrue(up.consume)
        assertEquals(emptyList<ButtonGestures.Action>(), up.actions)
    }

    @Test
    fun ayn_doubleTap_doesNothingCustom() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0)
        g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.UP, 30)
        val secondDown = g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 120)
        assertTrue(secondDown.consume)
        assertTrue(secondDown.actions.isEmpty())
    }

    @Test
    fun home_down_alwaysConsumed() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0).consume)
    }

    @Test
    fun home_shortPress_schedulesSystemHomeInject() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0)
        assertTrue(g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.UP, 40).consume)
        val timeout = g.onHomeSingleTapTimeout()
        assertTrue(timeout.scheduleHomeSystemInject)
        assertFalse(timeout.consume)
    }

    @Test
    fun home_longPress_minimize_notOnUp() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0)
        val timeout = g.onHomeHoldTimeout()
        assertEquals(listOf(ButtonGestures.Action.MinimizeAllDisplays), timeout.actions)
        val up = g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.UP, 1200)
        assertTrue(up.actions.isEmpty())
    }

    @Test
    fun home_holdTimeout_isIdempotent() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0)
        assertEquals(
            listOf(ButtonGestures.Action.MinimizeAllDisplays),
            g.onHomeHoldTimeout().actions
        )
        assertTrue(g.isHomeLongFired())
        assertTrue(g.onHomeHoldTimeout().actions.isEmpty())
    }

    @Test
    fun home_doubleTap_hasNoCustomAction() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0)
        g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.UP, 30)
        val secondDown = g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 100)
        assertTrue(secondDown.actions.isEmpty())
        val secondUp = g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.UP, 140)
        assertTrue(secondUp.actions.isEmpty())
    }

    @Test
    fun back_doubleTap_swaps() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN, 0)
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.UP, 10)
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN, 100)
        val d = g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.UP, 120)
        assertEquals(listOf(ButtonGestures.Action.SwapDisplaysOrSendSingle), d.actions)
    }

    @Test
    fun back_singleTap_afterTimeout_triggersSystemBack() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN, 0)
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.UP, 10)
        val d = g.onBackSingleTapTimeout()
        assertEquals(listOf(ButtonGestures.Action.SystemBack), d.actions)
    }

    @Test
    fun regression_backLong_isNeverMinimizeAll() {
        val g = ButtonGestures()
        g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN, 0)
        val timeout = g.onBackHoldTimeout()
        assertEquals(listOf(ButtonGestures.Action.PushActiveToOtherDisplay), timeout.actions)
        assertTrue(timeout.actions.none { it is ButtonGestures.Action.MinimizeAllDisplays })
    }

    @Test
    fun regression_aynDownConsumed_homeDownConsumed_backDownConsumed() {
        val g = ButtonGestures()
        assertTrue(g.onKeyEvent(ButtonGestures.Key.AYN, ButtonGestures.KeyAction.DOWN, 0).consume)
        assertTrue(g.onKeyEvent(ButtonGestures.Key.HOME, ButtonGestures.KeyAction.DOWN, 0).consume)
        assertTrue(g.onKeyEvent(ButtonGestures.Key.BACK, ButtonGestures.KeyAction.DOWN, 0).consume)
    }
}
