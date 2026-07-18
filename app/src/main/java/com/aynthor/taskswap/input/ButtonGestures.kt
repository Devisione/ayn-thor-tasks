package com.aynthor.taskswap.input

/**
 * Pure button gesture interpreter.
 *
 * Configurable double/long slots emit [Action.Custom]; short presses stay system.
 * Home has no double-tap slot.
 */
class ButtonGestures(
    private val holdThresholdMs: Long = 1000L,
    private val doubleTapWindowMs: Long = 300L
) {
    enum class Key { BACK, HOME, AYN }
    enum class KeyAction { DOWN, UP }

    sealed class Action {
        data object SystemBack : Action()
        /** System all-apps list ("список приложений"). */
        data object OpenAllAppsList : Action()
        data object MinimizeAllDisplays : Action()
        data object SwapDisplaysOrSendSingle : Action()
        data object PushActiveToOtherDisplay : Action()
        /** User-configurable slot; resolved via [GestureSettings]. */
        data class Custom(val slot: GestureSettings.Slot) : Action()
    }

    data class Decision(
        val consume: Boolean,
        val actions: List<Action> = emptyList(),
        val armBackSingleTimeout: Boolean = false,
        val scheduleHomeSystemInject: Boolean = false,
        val scheduleAynSystemInject: Boolean = false,
        val cancelHomeSystemInject: Boolean = false,
        val cancelAynSystemInject: Boolean = false
    )

    private var backDownAt: Long = -1L
    private var backPendingSingle = false
    private var backLongFired = false

    private var homeDownAt: Long = -1L
    private var homePendingSingle = false
    private var homeLongFired = false

    private var aynDownAt: Long = -1L
    private var aynPendingSingle = false
    private var aynLongFired = false
    private var aynDoubleHandled = false

    fun isBackHeld(): Boolean = backDownAt >= 0L
    fun isHomeHeld(): Boolean = homeDownAt >= 0L
    fun isAynHeld(): Boolean = aynDownAt >= 0L

    fun onBackHoldTimeout(): Decision {
        if (backDownAt < 0L || backLongFired) return Decision(consume = false)
        backLongFired = true
        backPendingSingle = false
        return Decision(consume = true, actions = listOf(Action.Custom(GestureSettings.Slot.BACK_LONG)))
    }

    fun onHomeHoldTimeout(): Decision {
        if (homeDownAt < 0L || homeLongFired) return Decision(consume = false)
        homeLongFired = true
        homePendingSingle = false
        return Decision(
            consume = true,
            actions = listOf(Action.Custom(GestureSettings.Slot.HOME_LONG)),
            cancelHomeSystemInject = true
        )
    }

    fun onAynHoldTimeout(): Decision {
        if (aynDownAt < 0L || aynLongFired) return Decision(consume = false)
        aynLongFired = true
        aynPendingSingle = false
        return Decision(
            consume = true,
            actions = listOf(Action.Custom(GestureSettings.Slot.AYN_LONG)),
            cancelAynSystemInject = true
        )
    }

    /** True after hold timeout fired for this press (until UP). */
    fun isHomeLongFired(): Boolean = homeLongFired
    fun isBackLongFired(): Boolean = backLongFired
    fun isAynLongFired(): Boolean = aynLongFired

    fun onKeyEvent(key: Key, action: KeyAction, eventTimeMs: Long): Decision {
        return when (key) {
            Key.BACK -> onBack(action, eventTimeMs)
            Key.HOME -> onHome(action, eventTimeMs)
            Key.AYN -> onAyn(action, eventTimeMs)
        }
    }

    fun onBackSingleTapTimeout(): Decision {
        if (!backPendingSingle) return Decision(consume = false)
        backPendingSingle = false
        return Decision(consume = true, actions = listOf(Action.SystemBack))
    }

    fun onHomeSingleTapTimeout(): Decision {
        if (!homePendingSingle) return Decision(consume = false)
        homePendingSingle = false
        return Decision(consume = false, scheduleHomeSystemInject = true)
    }

    fun onAynSingleTapTimeout(): Decision {
        if (!aynPendingSingle) return Decision(consume = false)
        aynPendingSingle = false
        return Decision(consume = false, scheduleAynSystemInject = true)
    }

    private fun onBack(action: KeyAction, t: Long): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                if (backDownAt < 0L) {
                    backDownAt = t
                    backLongFired = false
                }
                Decision(consume = true)
            }
            KeyAction.UP -> {
                backDownAt = -1L
                if (backLongFired) {
                    backLongFired = false
                    backPendingSingle = false
                    return Decision(consume = true)
                }
                if (backPendingSingle) {
                    backPendingSingle = false
                    return Decision(
                        consume = true,
                        actions = listOf(Action.Custom(GestureSettings.Slot.BACK_DOUBLE))
                    )
                }
                backPendingSingle = true
                Decision(consume = true, armBackSingleTimeout = true)
            }
        }
    }

    private fun onHome(action: KeyAction, t: Long): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                // Home has no double-tap gesture — clear pending so a second tap
                // cannot be confused with AYN / open the app list.
                homePendingSingle = false
                if (homeDownAt < 0L) {
                    homeDownAt = t
                    homeLongFired = false
                }
                Decision(consume = true, cancelHomeSystemInject = true)
            }
            KeyAction.UP -> {
                homeDownAt = -1L
                if (homeLongFired) {
                    homeLongFired = false
                    homePendingSingle = false
                    return Decision(consume = true, cancelHomeSystemInject = true)
                }
                homePendingSingle = true
                Decision(consume = true)
            }
        }
    }

    private fun onAyn(action: KeyAction, t: Long): Decision {
        return when (action) {
            KeyAction.DOWN -> {
                // Always consume DOWN — otherwise Thor firmware dims/turns off the bottom screen.
                if (aynPendingSingle) {
                    aynPendingSingle = false
                    aynDoubleHandled = true
                    aynDownAt = t
                    aynLongFired = false
                    return Decision(consume = true, cancelAynSystemInject = true)
                }
                if (aynDownAt < 0L) {
                    aynDownAt = t
                    aynLongFired = false
                    aynDoubleHandled = false
                }
                Decision(consume = true, cancelAynSystemInject = true)
            }
            KeyAction.UP -> {
                aynDownAt = -1L
                if (aynLongFired) {
                    aynLongFired = false
                    aynPendingSingle = false
                    return Decision(consume = true, cancelAynSystemInject = true)
                }
                if (aynDoubleHandled) {
                    aynDoubleHandled = false
                    return Decision(
                        consume = true,
                        actions = listOf(Action.Custom(GestureSettings.Slot.AYN_DOUBLE)),
                        cancelAynSystemInject = true
                    )
                }
                aynPendingSingle = true
                Decision(consume = true)
            }
        }
    }
}
