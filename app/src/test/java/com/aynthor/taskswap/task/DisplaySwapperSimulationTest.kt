package com.aynthor.taskswap.task

import com.aynthor.taskswap.adb.ShellExecutor
import com.aynthor.taskswap.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM simulation of swap/transfer — no device, no emulator, no install.
 * Fake shell records commands; agent must keep these green via `./gradlew test`.
 */
class DisplaySwapperSimulationTest {

    private class FakeShell(
        private var dumpsys: String,
        private val stack: String = ""
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override suspend fun dumpsysActivities(): Result<String> = Result.success(dumpsys)
        override suspend fun stackList(): Result<String> = Result.success(stack)

        override suspend fun moveStack(rootTaskId: Int, displayId: Int): Result<ShellResult> {
            commands += "moveStack:$rootTaskId->$displayId"
            return Result.success(ShellResult(0, "ok"))
        }

        override suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Result<ShellResult> {
            commands += "moveTask:$taskId->$displayId"
            return Result.success(ShellResult(0, "ok"))
        }

        override suspend fun moveTaskToBack(taskId: Int): Result<ShellResult> {
            commands += "moveToBack:$taskId"
            return Result.success(ShellResult(0, "ok"))
        }

        override suspend fun launchHomeOnDisplay(displayId: Int): Result<ShellResult> {
            commands += "launchHome:$displayId"
            return Result.success(ShellResult(0, "ok"))
        }

        override suspend fun resizeTask(
            taskId: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): Result<ShellResult> {
            commands += "resize:$taskId"
            return Result.success(ShellResult(0, "ok"))
        }
    }

    private fun dumpsysOneAppOnDisplay1(): String = """
        Display #0 (activities from DisplaySuperVisor):
          mResumedActivity: ActivityRecord{aaa u0 com.android.launcher3/.Launcher t1}
        Display #1 (activities from DisplaySuperVisor):
          * Task{bbb #42 type=standard A=10123:com.browser U=0 visible=true}
            Hist #0: ActivityRecord{ccc u0 com.browser/.Main t42}
          mResumedActivity: ActivityRecord{ccc u0 com.browser/.Main t42}
    """.trimIndent()

    private fun dumpsysTwoApps(): String = """
        Display #0 (activities from DisplaySuperVisor):
          RootTask #10
          * Task{aaa #10 type=standard A=1001:com.app.a U=0 visible=true}
            Hist #0: ActivityRecord{a1 u0 com.app.a/.Main t10}
          mResumedActivity: ActivityRecord{a1 u0 com.app.a/.Main t10}
        Display #1 (activities from DisplaySuperVisor):
          RootTask #20
          * Task{bbb #20 type=standard A=1002:com.app.b U=0 visible=true}
            Hist #0: ActivityRecord{b1 u0 com.app.b/.Main t20}
          mResumedActivity: ActivityRecord{b1 u0 com.app.b/.Main t20}
    """.trimIndent()

    @Test
    fun singleApp_doubleBackPath_movesWithoutLaunchHome() = runBlocking {
        val shell = FakeShell(dumpsysOneAppOnDisplay1())
        val swapper = DisplaySwapper(
            context = null,
            shellExecutor = shell,
            ensureAdb = { Result.success(Unit) }
        )

        val result = swapper.performSwapOrSend(
            displayApps = mapOf(1 to "com.browser"),
            displayIds = listOf(0, 1),
            ignoredPackages = setOf("com.android.launcher3", "com.android.systemui")
        )

        assertTrue(result.message, result.success)
        assertTrue(shell.commands.any { it.startsWith("moveStack:") || it.startsWith("moveTask:") })
        assertFalse(
            "Must not launchHome after single-app move — closes the app on Thor",
            shell.commands.any { it.startsWith("launchHome:") }
        )
        assertFalse(
            "Must not bury the moved task via cross-display leftover fallback",
            shell.commands.any { it.startsWith("moveToBack:") }
        )
        assertTrue(result.message.contains("com.browser") || result.message.contains("Перенесли"))
    }

    @Test
    fun singleApp_fromAccessibilityMap_stillMovesWithoutHome() = runBlocking {
        val shell = FakeShell(dumpsysOneAppOnDisplay1())
        val swapper = DisplaySwapper(
            context = null,
            shellExecutor = shell,
            ensureAdb = { Result.success(Unit) }
        )
        val result = swapper.performSwapOrSend(
            displayApps = mapOf(1 to "com.browser"),
            displayIds = listOf(0, 1),
            ignoredPackages = setOf("com.android.launcher3")
        )
        assertTrue(result.message, result.success)
        assertFalse(shell.commands.any { it.startsWith("launchHome:") })
    }

    @Test
    fun twoApps_swapMovesBoth_noHomeWipe() = runBlocking {
        val shell = FakeShell(dumpsysTwoApps())
        val swapper = DisplaySwapper(
            context = null,
            shellExecutor = shell,
            ensureAdb = { Result.success(Unit) }
        )
        val result = swapper.performSwapOrSend(
            displayApps = mapOf(0 to "com.app.a", 1 to "com.app.b"),
            displayIds = listOf(0, 1),
            ignoredPackages = emptySet()
        )
        assertTrue(result.message, result.success)
        val moves = shell.commands.filter { it.startsWith("moveStack:") }
        assertEquals(2, moves.size)
        assertFalse(shell.commands.any { it.startsWith("launchHome:") })
    }

    @Test
    fun pushActive_homesSourceBeforeMove() = runBlocking {
        val shell = FakeShell(dumpsysOneAppOnDisplay1())
        val swapper = DisplaySwapper(
            context = null,
            shellExecutor = shell,
            ensureAdb = { Result.success(Unit) }
        )
        val result = swapper.pushActiveAppToOther(
            activeDisplayId = 1,
            otherDisplayId = 0,
            displayApps = mapOf(1 to "com.browser"),
            ignoredPackages = setOf("com.android.launcher3")
        )
        assertTrue(result.message, result.success)
        assertTrue(shell.commands.any { it == "launchHome:1" })
        assertFalse(shell.commands.any { it == "launchHome:0" })
        val homeIdx = shell.commands.indexOf("launchHome:1")
        val moveIdx = shell.commands.indexOfFirst {
            it == "moveStack:42->0" || it == "moveTask:42->0"
        }
        assertTrue("Home must run before move, got ${shell.commands}", homeIdx >= 0 && moveIdx > homeIdx)
        assertEquals(
            "Exactly one move to target (no post-Home repin)",
            1,
            shell.commands.count { it == "moveStack:42->0" || it == "moveTask:42->0" }
        )
    }
}
