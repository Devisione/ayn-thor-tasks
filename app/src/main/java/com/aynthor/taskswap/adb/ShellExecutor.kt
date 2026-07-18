package com.aynthor.taskswap.adb

import android.util.Log

/**
 * ADB shell commands. Open for JVM fakes — agent verifies swap logic via `./gradlew test`
 * without installing on a device.
 */
open class ShellExecutor {

    companion object {
        private const val TAG = "ShellExecutor"
    }

    open suspend fun moveStack(rootTaskId: Int, displayId: Int): Result<ShellResult> {
        val commands = listOf(
            "am display move-stack $rootTaskId $displayId",
            "cmd activity display move-stack $rootTaskId $displayId",
            "am stack move-task $rootTaskId $displayId true"
        )
        var last: Result<ShellResult> = Result.failure(IllegalStateException("Нет команд"))
        for (command in commands) {
            Log.d(TAG, command)
            val result = AdbConnectionManager.shell(command)
            last = result
            if (result.isSuccess && result.getOrNull()?.success == true) {
                return result
            }
        }
        return last
    }

    open suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Result<ShellResult> {
        val commands = listOf(
            "am display move-stack $taskId $displayId",
            "cmd activity display move-stack $taskId $displayId",
            "am stack move-task $taskId $displayId true"
        )
        var last: Result<ShellResult> = Result.failure(IllegalStateException("Нет команд"))
        for (command in commands) {
            Log.d(TAG, command)
            val result = AdbConnectionManager.shell(command)
            last = result
            if (result.isSuccess && result.getOrNull()?.success == true) {
                return result
            }
        }
        return last
    }

    open suspend fun stackList(): Result<String> {
        return AdbConnectionManager.shell("am stack list").map { it.output }
    }

    open suspend fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Result<ShellResult> {
        val command = "am task resize $taskId $left,$top,$right,$bottom"
        Log.d(TAG, command)
        return AdbConnectionManager.shell(command)
    }

    open suspend fun dumpsysActivities(): Result<String> {
        return AdbConnectionManager.shell("dumpsys activity activities")
            .map { it.output }
    }

    open suspend fun moveTaskToBack(taskId: Int): Result<ShellResult> {
        val command = "am stack move-task $taskId true"
        Log.d(TAG, command)
        return AdbConnectionManager.shell(command)
    }

    open suspend fun launchHomeOnDisplay(displayId: Int): Result<ShellResult> {
        val command = "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"
        Log.d(TAG, command)
        return AdbConnectionManager.shell(command)
    }

    open suspend fun inputKeyevent(keyCode: Int): Result<ShellResult> {
        val command = "input keyevent $keyCode"
        Log.d(TAG, command)
        return AdbConnectionManager.shell(command)
    }

    /**
     * One real gpio tap (DOWN+SYN+UP+SYN). Used for short-AYN after we consumed the
     * physical hold so firmware cannot blank the screen — still opens native AYN menu.
     */
    open suspend fun sendeventKeyPulse(eventNode: String, linuxKeyCode: Int): Result<ShellResult> {
        val path = when {
            eventNode.startsWith("/") -> eventNode
            eventNode.startsWith("event") -> "/dev/input/$eventNode"
            else -> "/dev/input/$eventNode"
        }
        val command =
            "sendevent $path 1 $linuxKeyCode 1 && sendevent $path 0 0 0 && " +
                "sendevent $path 1 $linuxKeyCode 0 && sendevent $path 0 0 0"
        Log.d(TAG, command)
        return AdbConnectionManager.shell(command)
    }

    open suspend fun findInputEventNode(deviceName: String): String? {
        val output = AdbConnectionManager.shell("cat /proc/bus/input/devices").getOrNull()?.output
            ?: return null
        val needle = deviceName.lowercase()
        var matching = false
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("N: Name=")) {
                matching = t.lowercase().contains(needle)
            } else if (matching && t.startsWith("H: Handlers=")) {
                val event = Regex("""event\d+""").find(t)?.value ?: continue
                return event
            }
        }
        return null
    }
}
