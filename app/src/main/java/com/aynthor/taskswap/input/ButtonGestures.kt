package com.aynthor.taskswap.input

/**
 * Per-button gesture state (Back / Home / AYN).
 *
 * AYN is always consumed so firmware never sees a held gpio HOME (that blanks the
 * bottom screen). Short AYN schedules a gpio sendevent replay so the native AYN
 * menu still opens; long AYN opens the all-apps list.
 */
class ButtonGestures(
    private val holdThresholdMs: Long = 1000L,
    private val doubleTapWindowMs: Long = 300L
) {
    enum class Key { BACK, HOME, AYN }
    enum class KeyAction { DOWN, UP }

    sealed class Action {
        data object SystemBack : Action()
        data object OpenAllAppsList : Action()
        data object MinimizeAllDisplays : Action()
        data object SwapDisplaysOrSendSingle : Action()
        data object PushActiveToOtherDisplay : Action()
        data class Custom(val slot: GestureSettings.Slot) : Action()
    }

    data class Decision(
        val consume: Boolean,
        val actions: List<Action> = emptyList(),
        val armBackSingleTimeout: Boolean = false,
        val scheduleHomeSystemInject: Boolean = false,
        /** Replay physical AYN via gpio sendevent (short press). */
        val scheduleAynGpioInject: Boolean = false
    )

    private class HoldState {
        var down = false
        var longFired = false
        var pendingSingle = false
    }

    private val back = HoldState()
    private val home = HoldState()
    private val ayn = HoldState()

    fun isBackHeld(): Boolean = back.down
    fun isHomeHeld(): Boolean = home.down
    fun isAynHeld(): Boolean = ayn.down
    fun isBackLongFired(): Boolean = back.longFired
    fun isHomeLongFired(): Boolean = home.longFired
    fun isAynLongFired(): Boolean = ayn.longFired

    fun onKeyEvent(key: Key, action: KeyAction, eventTimeMs: Long = 0L): Decision {
        return when (key) {
            Key.BACK -> onBack(action)
            Key.HOME -> onHome(action)
            Key.AYN -> onAyn(action)
        }
    }

    fun onBackHoldTimeout(): Decision {
        if (!back.down || back.longFired) return Decision(consume = false)
        back.longFired = true
        back.pendingSingle = false
        return Decision(consume = true, actions = listOf(Action.Custom(GestureSettings.Slot.BACK_LONG)))
    }

    fun onHomeHoldTimeout(): Decision {
        if (!home.down || home.longFired) return Decision(consume = false)
        home.longFired = true
        return Decision(
            consume = true,
            actions = listOf(Action.Custom(GestureSettings.Slot.HOME_LONG))
        )
    }

    fun onAynHoldTimeout(): Decision {
        if (!ayn.down || ayn.longFired) return Decision(consume = false)
        ayn.longFired = true
        return Decision(
            consume = true,
            actions = listOf(Action.Custom(GestureSettings.Slot.AYN_LONG))
        )
    }

    fun onBackSingleTapTimeout(): Decision {
        if (!back.pendingSingle) return Decision(consume = false)
        back.pendingSingle = false
        return Decision(consume = true, actions = listOf(Action.SystemBack))
    }

    private fun onBack(action: KeyAction): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                if (!back.down) {
                    back.down = true
                    back.longFired = false
                }
                Decision(consume = true)
            }
            KeyAction.UP -> {
                val wasLong = back.longFired
                back.down = false
                back.longFired = false
                if (wasLong) {
                    back.pendingSingle = false
                    return Decision(consume = true)
                }
                if (back.pendingSingle) {
                    back.pendingSingle = false
                    return Decision(
                        consume = true,
                        actions = listOf(Action.Custom(GestureSettings.Slot.BACK_DOUBLE))
                    )
                }
                back.pendingSingle = true
                Decision(consume = true, armBackSingleTimeout = true)
            }
        }
    }

    private fun onHome(action: KeyAction): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                if (!home.down) {
                    home.down = true
                    home.longFired = false
                }
                Decision(consume = true)
            }
            KeyAction.UP -> {
                val wasLong = home.longFired
                home.down = false
                home.longFired = false
                if (wasLong) return Decision(consume = true)
                Decision(consume = true, scheduleHomeSystemInject = true)
            }
        }
    }

    /** Always consume — firmware blanks bottom screen on held gpio AYN. */
    private fun onAyn(action: KeyAction): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                if (!ayn.down) {
                    ayn.down = true
                    ayn.longFired = false
                }
                Decision(consume = true)
            }
            KeyAction.UP -> {
                val wasLong = ayn.longFired
                ayn.down = false
                ayn.longFired = false
                if (wasLong) return Decision(consume = true)
                Decision(consume = true, scheduleAynGpioInject = true)
            }
        }
    }
}
