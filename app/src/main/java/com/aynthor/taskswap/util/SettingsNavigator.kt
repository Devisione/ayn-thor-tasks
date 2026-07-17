package com.aynthor.taskswap.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object SettingsNavigator {

    fun openDeveloperSettings(context: Context): Result<Unit> = runCatching {
        start(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }.recoverCatching {
        start(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun openWirelessDebuggingSettings(context: Context): Result<Unit> {
        val intents = listOfNotNull(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            } else null
        )
        for (intent in intents) {
            val result = runCatching { start(context, intent) }
            if (result.isSuccess) return result
        }
        return openDeveloperSettings(context)
    }

    fun openAccessibilitySettings(context: Context): Result<Unit> = runCatching {
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openBatteryOptimization(context: Context): Result<Unit> = runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        start(context, intent)
    }

    private fun start(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
