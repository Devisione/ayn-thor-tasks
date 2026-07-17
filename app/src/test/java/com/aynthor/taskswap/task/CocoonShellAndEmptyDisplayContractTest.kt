package com.aynthor.taskswap.task

import com.aynthor.taskswap.adb.ShellExecutor
import com.aynthor.taskswap.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts for:
 * 1) rip.moth.cocoonshell is a shell — never a swap partner
 * 2) after single-app transfer, source display must get Home (not a random underlay app)
 * 3) never wipe both displays with Home during transfer
 */
class CocoonShellAndEmptyDisplayContractTest {

    private class FakeShell(
        private var dumpsys: String
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()
        override suspend fun dumpsysActivities(): Result<String> = Result.success(dumpsys)
        override suspend fun stackList(): Result<String> = Result.success("")
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

    private fun dumpsysAppPlusCocoon(): String = """
        Display #0
          RootTask #10
          * Task{a #10 type=standard A=1:com.browser U=0 visible=true}
            Hist #0: ActivityRecord{a1 u0 com.browser/.Main t10}
          mResumedActivity: ActivityRecord{a1 u0 com.browser/.Main t10}
        Display #1
          RootTask #20
          * Task{b #20 type=standard A=2:rip.moth.cocoonshell U=0 visible=true}
            Hist #0: ActivityRecord{b1 u0 rip.moth.cocoonshell/.Main t20}
          mResumedActivity: ActivityRecord{b1 u0 rip.moth.cocoonshell/.Main t20}
    """.trimIndent()

    private fun dumpsysOneAppOnBottomWithBackgroundOnTop(): String = """
        Display #0
          RootTask #7
          * Task{bg #7 type=standard A=9:com.random.underlay U=0 visible=true}
            Hist #0: ActivityRecord{r u0 com.random.underlay/.Main t7}
          mResumedActivity: ActivityRecord{r u0 com.random.underlay/.Main t7}
        Display #1
          RootTask #42
          * Task{t #42 type=standard A=1:com.browser U=0 visible=true}
            Hist #0: ActivityRecord{z u0 com.browser/.Main t42}
          mResumedActivity: ActivityRecord{z u0 com.browser/.Main t42}
    """.trimIndent()

    private fun swapper(shell: FakeShell) = DisplaySwapper(
        context = null,
        shellExecutor = shell,
        ensureAdb = { Result.success(Unit) }
    )

    @Test
    fun cocoonshell_isAlwaysIgnoredShell() {
        assertTrue(ShellPackages.isShellOrIgnored("rip.moth.cocoonshell"))
    }

    @Test
    fun planPush_doesNotTreatCocoonAsRealAppToBury() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 0,
            otherDisplayId = 1,
            apps = mapOf(
                0 to "com.browser",
                1 to "rip.moth.cocoonshell"
            ).filterValues { !ShellPackages.isShellOrIgnored(it) }
        )
        assertNotNull(plan)
        assertEquals("com.browser", plan!!.packageToMove)
        assertNull("Must not bury Cocoon shell", plan.packageToBury)
    }

    @Test
    fun doubleBack_oneAppPlusCocoon_movesOnlyRealApp_notSwap() = runBlocking {
        val shell = FakeShell(dumpsysAppPlusCocoon())
        // Production-like: caller may not list Cocoon; DisplaySwapper must still ignore it.
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(0 to "com.browser", 1 to "rip.moth.cocoonshell"),
            displayIds = listOf(0, 1),
            ignoredPackages = setOf("com.android.systemui")
        )
        assertTrue(result.message, result.success)
        val moves = shell.commands.filter { it.startsWith("moveStack:") || it.startsWith("moveTask:") }
        assertEquals("Expected single move of browser, got $moves", 1, moves.size)
        assertTrue(moves.single().contains("10->1") || moves.single().contains("->1"))
        assertFalse(
            "Must never move Cocoon shell task",
            shell.commands.any { it.contains("20->") }
        )
    }

    @Test
    fun singleApp_transfer_doesNotLaunchHome() = runBlocking {
        val shell = FakeShell(dumpsysOneAppOnBottomWithBackgroundOnTop())
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(1 to "com.browser"),
            displayIds = listOf(0, 1),
            ignoredPackages = ShellPackages.ALWAYS_IGNORED + "com.android.launcher3"
        )
        assertTrue(result.message, result.success)
        assertFalse(
            "Must not launchHome after single-app move — closes the app on Thor; got ${shell.commands}",
            shell.commands.any { it.startsWith("launchHome:") }
        )
    }

    @Test
    fun longBack_push_launchesHomeOnSourceOnly() = runBlocking {
        val shell = FakeShell(
            """
            Display #0
              mResumedActivity: ActivityRecord{x u0 com.android.launcher3/.Launcher t1}
            Display #1
              RootTask #42
              * Task{t #42 type=standard A=1:com.browser U=0 visible=true}
                Hist #0: ActivityRecord{z u0 com.browser/.Main t42}
              mResumedActivity: ActivityRecord{z u0 com.browser/.Main t42}
            """.trimIndent()
        )
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 1,
            otherDisplayId = 0,
            displayApps = mapOf(1 to "com.browser"),
            ignoredPackages = ShellPackages.ALWAYS_IGNORED + "com.android.launcher3"
        )
        assertTrue(result.message, result.success)
        val homes = shell.commands.filter { it.startsWith("launchHome:") }
        assertEquals(listOf("launchHome:1"), homes)
        val homeIdx = shell.commands.indexOf("launchHome:1")
        val moveIdx = shell.commands.indexOfFirst {
            it == "moveStack:42->0" || it == "moveTask:42->0"
        }
        assertTrue(homeIdx >= 0 && moveIdx > homeIdx)
        assertEquals(1, shell.commands.count { it == "moveStack:42->0" || it == "moveTask:42->0" })
    }

    @Test
    fun bottomTaskFocus_movesTaskToFocusedDisplay_insteadOfFailing() = runBlocking {
        // Task lives on display 1; user picks it from Recents/task list on display 0.
        val dumpsys = """
            Display #0
              mResumedActivity: ActivityRecord{x u0 com.android.launcher3/.Launcher t1}
            Display #1
              RootTask #55
              * Task{t #55 type=standard A=9:com.bottom.app U=0 visible=true}
                Hist #0: ActivityRecord{z u0 com.bottom.app/.Main t55}
              mResumedActivity: ActivityRecord{z u0 com.bottom.app/.Main t55}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).ensureAppOnDisplay(
            packageName = "com.bottom.app",
            targetDisplayId = 0,
            ignoredPackages = ShellPackages.ALWAYS_IGNORED + "com.android.launcher3"
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must move existing task to focused display, got ${shell.commands}",
            shell.commands.any { it == "moveStack:55->0" || it == "moveTask:55->0" }
        )
        assertFalse(
            "Must not wipe all displays with Home when focusing a bottom-screen task",
            shell.commands.count { it.startsWith("launchHome:") } >= 2
        )
    }
}
