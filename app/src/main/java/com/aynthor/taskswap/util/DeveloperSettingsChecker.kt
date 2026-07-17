package com.aynthor.taskswap.util

import android.content.Context
import android.provider.Settings

object DeveloperSettingsChecker {

    fun isUsbDebuggingEnabled(context: Context): Boolean {
        return readGlobalInt(context, Settings.Global.ADB_ENABLED)
    }

    fun isDeveloperSetupReady(context: Context): Boolean {
        return isUsbDebuggingEnabled(context)
    }

    private fun readGlobalInt(context: Context, key: String): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, key, 0) == 1
        } catch (_: Exception) {
            false
        }
    }
}
