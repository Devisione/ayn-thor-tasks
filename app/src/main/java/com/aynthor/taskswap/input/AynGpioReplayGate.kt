package com.aynthor.taskswap.input

/**
 * Serializes short-AYN gpio replay and decides which KEYCODE_HOME/F24 events are
 * echoes of our sendevent (pass through to the vendor menu) vs real user presses.
 *
 * Bug this prevents: arming a DOWN+UP pass-through *before* the ADB sendevent returns
 * let real taps steal the slots; late echoes then re-entered [ButtonGestures] and
 * scheduled another inject forever — short AYN looked dead while long (Intent) still worked.
 */
class AynGpioReplayGate(
    private val echoWindowMs: Long = 800L,
    private val reentryCooldownMs: Long = 150L
) {
    @Volatile
    private var inFlight = false

    @Volatile
    private var remainingEchoPassThrough = 0

    @Volatile
    private var echoUntilElapsed = 0L

    @Volatile
    private var suppressRescheduleUntilElapsed = 0L

    fun isInjectInFlight(): Boolean = inFlight

    /** @return false if another inject is already running or cooldown is active. */
    fun tryBeginInject(nowElapsedMs: Long): Boolean {
        if (inFlight) return false
        if (nowElapsedMs < suppressRescheduleUntilElapsed) return false
        if (remainingEchoPassThrough > 0 && nowElapsedMs < echoUntilElapsed) return false
        inFlight = true
        return true
    }

    /**
     * Call after sendevent finishes. On success, arm echo pass-through for late events
     * (events that arrived *during* the blocking ADB call are covered by [inFlight]).
     */
    fun onInjectFinished(success: Boolean, nowElapsedMs: Long) {
        if (success) {
            remainingEchoPassThrough = 2
            echoUntilElapsed = nowElapsedMs + echoWindowMs
            suppressRescheduleUntilElapsed = nowElapsedMs + reentryCooldownMs
        } else {
            remainingEchoPassThrough = 0
            echoUntilElapsed = 0L
            // Still cool down briefly so a failed pulse's partial echoes cannot loop.
            suppressRescheduleUntilElapsed = nowElapsedMs + reentryCooldownMs
        }
        inFlight = false
    }

    fun abortInject(nowElapsedMs: Long) {
        remainingEchoPassThrough = 0
        echoUntilElapsed = 0L
        suppressRescheduleUntilElapsed = nowElapsedMs + reentryCooldownMs
        inFlight = false
    }

    /**
     * True when this gpio AYN-like event should reach the system unchanged
     * (return false from AccessibilityService.onKeyEvent).
     */
    fun shouldPassThrough(
        keyCode: Int,
        deviceName: String?,
        nowElapsedMs: Long
    ): Boolean {
        if (!isAynGpioCandidate(keyCode, deviceName)) return false

        // Echoes that arrive while ADB sendevent is still blocked on IO.
        if (inFlight) return true

        if (remainingEchoPassThrough <= 0) return false
        if (nowElapsedMs >= echoUntilElapsed) {
            remainingEchoPassThrough = 0
            echoUntilElapsed = 0L
            return false
        }
        remainingEchoPassThrough = (remainingEchoPassThrough - 1).coerceAtLeast(0)
        if (remainingEchoPassThrough == 0) echoUntilElapsed = 0L
        return true
    }

    fun shouldScheduleInject(nowElapsedMs: Long): Boolean {
        if (inFlight) return false
        if (remainingEchoPassThrough > 0 && nowElapsedMs < echoUntilElapsed) return false
        if (nowElapsedMs < suppressRescheduleUntilElapsed) return false
        return true
    }

    companion object {
        fun isAynGpioCandidate(keyCode: Int, deviceName: String?): Boolean {
            if (keyCode != ThorKeyMapper.KEYCODE_HOME && keyCode != ThorKeyMapper.KEYCODE_F24) {
                return false
            }
            val name = deviceName?.lowercase().orEmpty()
            if (name.contains("odin") ||
                name.contains("controller") ||
                name.contains("gamepad") ||
                name.contains("joypad")
            ) {
                return false
            }
            return true
        }
    }
}
