package com.aynthor.taskswap.input

import android.content.Context

/**
 * User preferences for custom button gestures (double / long).
 * Short presses stay system defaults (Back / Home / AYN).
 */
object GestureSettings {

    private const val PREFS = "gesture_settings"
    private const val KEY_ENABLED = "gestures_enabled"

    enum class Slot(val prefKey: String) {
        BACK_DOUBLE("back_double"),
        BACK_LONG("back_long"),
        HOME_LONG("home_long"),
        AYN_DOUBLE("ayn_double"),
        AYN_LONG("ayn_long");
    }

    /** What a configurable gesture slot may do. */
    enum class CustomAction(val storage: String, val label: String) {
        NONE("none", "Ничего"),
        OPEN_ALL_APPS("open_all_apps", "Открыть список приложений"),
        SWAP_OR_SEND("swap_or_send", "Поменять экраны местами"),
        PUSH_ACTIVE("push_active", "Активное приложение на другой экран"),
        MINIMIZE_ALL("minimize_all", "Свернуть все приложения");

        fun toButtonAction(): ButtonGestures.Action? = when (this) {
            NONE -> null
            OPEN_ALL_APPS -> ButtonGestures.Action.OpenAllAppsList
            SWAP_OR_SEND -> ButtonGestures.Action.SwapDisplaysOrSendSingle
            PUSH_ACTIVE -> ButtonGestures.Action.PushActiveToOtherDisplay
            MINIMIZE_ALL -> ButtonGestures.Action.MinimizeAllDisplays
        }

        companion object {
            fun fromStorage(value: String?): CustomAction =
                entries.firstOrNull { it.storage == value } ?: NONE
        }
    }

    fun defaultAction(slot: Slot): CustomAction = when (slot) {
        Slot.BACK_DOUBLE -> CustomAction.SWAP_OR_SEND
        Slot.BACK_LONG -> CustomAction.PUSH_ACTIVE
        Slot.HOME_LONG -> CustomAction.MINIMIZE_ALL
        Slot.AYN_DOUBLE -> CustomAction.NONE
        Slot.AYN_LONG -> CustomAction.OPEN_ALL_APPS
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getAction(context: Context, slot: Slot): CustomAction {
        val stored = prefs(context).getString(slot.prefKey, null)
        return if (stored == null) defaultAction(slot) else CustomAction.fromStorage(stored)
    }

    fun setAction(context: Context, slot: Slot, action: CustomAction) {
        prefs(context).edit().putString(slot.prefKey, action.storage).apply()
    }

    /** Resolve a gesture slot to an executable action, or null if disabled / none. */
    fun resolve(context: Context, slot: Slot): ButtonGestures.Action? {
        if (!isEnabled(context)) return null
        return getAction(context, slot).toButtonAction()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
