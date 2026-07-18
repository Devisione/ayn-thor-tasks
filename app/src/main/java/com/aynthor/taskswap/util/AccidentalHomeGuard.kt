package com.aynthor.taskswap.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.aynthor.taskswap.adb.AdbConnectionManager

/**
 * Thor / Odin setting "Prevent Accidental Home Button Press"
 * ([SETTINGS_KEY]). When on, the first Home is swallowed and the user
 * must press again.
 *
 * App preference defaults to off (Home on first press). Service start
 * re-applies that preference to the system flag.
 */
object AccidentalHomeGuard {

    const val SETTINGS_KEY = "prevent_press_home_accidentally"

    private const val TAG = "AccidentalHomeGuard"
    private const val PREFS = "accidental_home_guard"
    private const val PREF_ENABLED = "enabled"

    /** Preferred value in our app (default: protection off). */
    fun isPreferredEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_ENABLED, false)
    }

    /** Live Thor system flag. True = Home needs a second press. */
    fun isSystemEnabled(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, SETTINGS_KEY, 1) != 0
        } catch (_: Exception) {
            true
        }
    }

    /** UI / polling: prefer live system value when readable. */
    fun isEnabled(context: Context): Boolean = isSystemEnabled(context)

    /**
     * Saves preference and writes the Thor system flag.
     * Tries [Settings.System] first, then ADB (works without WRITE_SETTINGS).
     */
    suspend fun setEnabled(context: Context, enabled: Boolean): Result<Unit> {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
        return writeSystem(context, enabled)
    }

    /** Re-apply saved preference (e.g. on accessibility connect). */
    suspend fun applyPreferred(context: Context): Result<Unit> {
        return writeSystem(context, isPreferredEnabled(context))
    }

    private suspend fun writeSystem(context: Context, enabled: Boolean): Result<Unit> {
        val value = if (enabled) 1 else 0
        var settingsOk = false
        try {
            settingsOk = Settings.System.putInt(context.contentResolver, SETTINGS_KEY, value)
            Log.i(TAG, "Settings.System $SETTINGS_KEY → $value (put=$settingsOk)")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write $SETTINGS_KEY via Settings: ${e.message}")
        }

        val shell = AdbConnectionManager.shell("settings put system $SETTINGS_KEY $value")
        if (shell.isSuccess) {
            Log.i(TAG, "ADB set $SETTINGS_KEY=$value")
            return Result.success(Unit)
        }

        return if (settingsOk) {
            Result.success(Unit)
        } else {
            Result.failure(
                shell.exceptionOrNull()
                    ?: IllegalStateException("Не удалось записать $SETTINGS_KEY")
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
