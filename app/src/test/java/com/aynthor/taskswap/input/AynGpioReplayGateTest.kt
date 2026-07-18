package com.aynthor.taskswap.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AynGpioReplayGateTest {

    @Test
    fun inFlight_echoesPassThrough_withoutStealingUserSlotsBeforeSend() {
        val gate = AynGpioReplayGate()
        assertTrue(gate.tryBeginInject(0L))

        // During blocking ADB sendevent: real/synthetic gpio events pass through.
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 10L))
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 20L))

        // Still in-flight — must not start another inject (re-entry storm).
        assertFalse(gate.shouldScheduleInject(30L))
        assertFalse(gate.tryBeginInject(30L))
    }

    @Test
    fun afterSuccessfulInject_lateEchoesPassThrough_thenCooldownBlocksReschedule() {
        val gate = AynGpioReplayGate(echoWindowMs = 800L, reentryCooldownMs = 150L)
        assertTrue(gate.tryBeginInject(0L))
        gate.onInjectFinished(success = true, nowElapsedMs = 200L)

        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 210L))
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 220L))
        // Counter exhausted
        assertFalse(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 230L))

        // Brief cooldown after inject
        assertFalse(gate.shouldScheduleInject(300L))
        assertTrue(gate.shouldScheduleInject(200L + 150L))
    }

    @Test
    fun failedInject_doesNotArmPassThrough_andBlocksImmediateReschedule() {
        val gate = AynGpioReplayGate(reentryCooldownMs = 150L)
        assertTrue(gate.tryBeginInject(0L))
        gate.onInjectFinished(success = false, nowElapsedMs = 100L)

        assertFalse(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 110L))
        assertFalse(gate.shouldScheduleInject(120L))
        assertTrue(gate.shouldScheduleInject(100L + 150L))
    }

    @Test
    fun whileEchoSlotsRemain_doesNotScheduleAnotherInject() {
        val gate = AynGpioReplayGate(echoWindowMs = 800L, reentryCooldownMs = 0L)
        assertTrue(gate.tryBeginInject(0L))
        gate.onInjectFinished(success = true, nowElapsedMs = 100L)
        // One echo consumed, one slot left — must not re-arm from a leaked gesture UP.
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 110L))
        assertFalse(gate.shouldScheduleInject(120L))
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 130L))
        assertTrue(gate.shouldScheduleInject(140L))
    }

    @Test
    fun odinControllerHome_neverPassThrough() {
        val gate = AynGpioReplayGate()
        assertTrue(gate.tryBeginInject(0L))
        assertFalse(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "Odin Controller", 10L))
        gate.abortInject(10L)
    }

    @Test
    fun oldBug_armingCountBeforeSend_isNotUsed_userTapDuringInFlightStillPasses() {
        // Regression: previously remaining=2 was set BEFORE sendevent; a real tap stole
        // slots, then echoes re-entered gestures. Now in-flight passes through without a
        // pre-armed count, and shouldScheduleInject stays false until finished+cooldown.
        val gate = AynGpioReplayGate()
        assertTrue(gate.tryBeginInject(0L))
        // User taps while ADB is still running:
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 50L))
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 80L))
        assertFalse(gate.shouldScheduleInject(90L))

        gate.onInjectFinished(success = true, nowElapsedMs = 300L)
        // Late echoes still get the post-send window
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 310L))
        assertTrue(gate.shouldPassThrough(ThorKeyMapper.KEYCODE_HOME, "gpio-keys", 320L))
    }
}
