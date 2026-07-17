package com.aynthor.taskswap.task

import com.aynthor.taskswap.adb.ShellExecutor
import com.aynthor.taskswap.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failing-first contracts for double-Back single-app (both directions) and long-Back push.
 */
class TransferDirectionSimulationTest {

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
        override suspend fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Result<ShellResult> {
            commands += "resize:$taskId"
            return Result.success(ShellResult(0, "ok"))
        }
    }

    private fun dumpsysAppOn(displayId: Int, pkg: String, taskId: Int): String = """
        Display #0
          mResumedActivity: ActivityRecord{x u0 com.android.launcher3/.Launcher t1}
        Display #1
          mResumedActivity: ActivityRecord{y u0 com.android.launcher3/.Launcher t2}
        Display #$displayId
          RootTask #$taskId
          * Task{t #$taskId type=standard A=1000:$pkg U=0 visible=true}
            Hist #0: ActivityRecord{z u0 $pkg/.Main t$taskId}
          mResumedActivity: ActivityRecord{z u0 $pkg/.Main t$taskId}
    """.trimIndent()

    private fun dumpsysTwoApps(): String = """
        Display #0
          RootTask #10
          * Task{a #10 type=standard A=1:com.app.a U=0 visible=true}
            Hist #0: ActivityRecord{a1 u0 com.app.a/.Main t10}
          mResumedActivity: ActivityRecord{a1 u0 com.app.a/.Main t10}
        Display #1
          RootTask #20
          * Task{b #20 type=standard A=2:com.app.b U=0 visible=true}
            Hist #0: ActivityRecord{b1 u0 com.app.b/.Main t20}
          mResumedActivity: ActivityRecord{b1 u0 com.app.b/.Main t20}
    """.trimIndent()

    private fun swapper(shell: FakeShell) = DisplaySwapper(
        context = null,
        shellExecutor = shell,
        ensureAdb = { Result.success(Unit) }
    )

    private val ignored = setOf("com.android.launcher3", "com.android.systemui")

    @Test
    fun doubleBack_singleAppOnTop_movesToBottom() = runBlocking {
        val shell = FakeShell(dumpsysAppOn(0, "com.top", 42))
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(0 to "com.top"),
            displayIds = listOf(0, 1, 2, 4),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must move task toward display 1, got ${shell.commands}",
            shell.commands.any { it == "moveStack:42->1" || it == "moveTask:42->1" }
        )
    }

    @Test
    fun doubleBack_singleAppOnBottom_movesToTop() = runBlocking {
        val shell = FakeShell(dumpsysAppOn(1, "com.bottom", 42))
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(1 to "com.bottom"),
            displayIds = listOf(0, 1, 2, 4),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must move task toward display 0 (top), got ${shell.commands}",
            shell.commands.any { it == "moveStack:42->0" || it == "moveTask:42->0" }
        )
    }

    @Test
    fun doubleBack_singleAppOnlyInAccessibility_stillFindsTaskAndMoves() = runBlocking {
        // dumpsys lists the task globally; a11y says it's on bottom
        val dumpsys = """
            Display #0
              mResumedActivity: ActivityRecord{x u0 com.android.launcher3/.Launcher t1}
            Display #1
              mResumedActivity: ActivityRecord{y u0 com.android.launcher3/.Launcher t2}
            RootTask #55
            * Task{t #55 type=standard A=9:com.browser U=0 visible=true}
              Hist #0: ActivityRecord{z u0 com.browser/.Main t55}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(1 to "com.browser"),
            displayIds = listOf(0, 1),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(shell.commands.any { it.contains("->0") })
    }

    @Test
    fun longBack_pushFromBottomToTop() = runBlocking {
        val shell = FakeShell(dumpsysAppOn(1, "com.bottom", 42))
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 1,
            otherDisplayId = 0,
            displayApps = mapOf(1 to "com.bottom"),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(shell.commands.any { it == "moveStack:42->0" || it == "moveTask:42->0" })
    }

    @Test
    fun longBack_pushFromTopToBottom() = runBlocking {
        val shell = FakeShell(dumpsysAppOn(0, "com.top", 42))
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 0,
            otherDisplayId = 1,
            displayApps = mapOf(0 to "com.top"),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(shell.commands.any { it == "moveStack:42->1" || it == "moveTask:42->1" })
    }

    @Test
    fun longBack_twoApps_isPushNotSwap() = runBlocking {
        val shell = FakeShell(dumpsysTwoApps())
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 1,
            otherDisplayId = 0,
            displayApps = mapOf(0 to "com.app.a", 1 to "com.app.b"),
            ignoredPackages = emptySet()
        )
        assertTrue(result.message, result.success)
        val moves = shell.commands.filter { it.startsWith("moveStack:") || it.startsWith("moveTask:") }
        // Move active (b) to top, then re-pin after Home — not a two-app swap.
        assertTrue("Expected at least one move of b→0, got $moves", moves.any { it.endsWith("->0") })
        assertFalse(
            "Must not move app.a as a swap partner",
            moves.any { it.startsWith("moveStack:10->") || it.startsWith("moveTask:10->") }
        )
        assertTrue(
            "Target app must be buried, not moved as swap",
            shell.commands.any { it.startsWith("moveToBack:") }
        )
        assertTrue(
            "Long-Back must Home vacated source",
            shell.commands.any { it == "launchHome:1" }
        )
        assertFalse(shell.commands.any { it == "launchHome:0" })
    }

    @Test
    fun doubleBack_a11yOnBottom_dumpsysUnderlayOnTop_movesBottomToTop() = runBlocking {
        val dumpsys = """
            Display #0
              RootTask #7
              * Task{bg #7 type=standard A=9:com.underlay U=0 visible=true}
                Hist #0: ActivityRecord{r u0 com.underlay/.Main t7}
              mResumedActivity: ActivityRecord{r u0 com.underlay/.Main t7}
            Display #1
              RootTask #42
              * Task{t #42 type=standard A=1:com.bottom U=0 visible=true}
                Hist #0: ActivityRecord{z u0 com.bottom/.Main t42}
              mResumedActivity: ActivityRecord{z u0 com.bottom/.Main t42}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).performSwapOrSend(
            displayApps = mapOf(1 to "com.bottom"),
            displayIds = listOf(0, 1, 2, 4),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must move bottom task to top, got ${shell.commands}",
            shell.commands.any { it == "moveStack:42->0" || it == "moveTask:42->0" }
        )
        assertFalse(
            "Must not treat underlay as swap partner",
            shell.commands.any { it.contains("7->") }
        )
        assertFalse(shell.commands.any { it.startsWith("launchHome:") })
    }

    @Test
    fun longBack_twoApps_focusedBottom_a11yOnlyTop_pushesBottomNotTop() = runBlocking {
        val dumpsys = """
            Display #0
              RootTask #10
              * Task{a #10 type=standard A=1:com.google.android.youtube U=0 visible=true lastActiveTime=100}
                Hist #0: ActivityRecord{a1 u0 com.google.android.youtube/.Watch t10}
              mResumedActivity: ActivityRecord{a1 u0 com.google.android.youtube/.Watch t10}
            Display #1
              RootTask #20
              * Task{b #20 type=standard A=2:com.android.settings U=0 visible=true lastActiveTime=999}
                Hist #0: ActivityRecord{b1 u0 com.android.settings/.Settings t20}
              mResumedActivity: ActivityRecord{b1 u0 com.android.settings/.Settings t20}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 1,
            otherDisplayId = 0,
            // a11y only sees YouTube — classic Thor gap on the bottom Settings window
            displayApps = mapOf(0 to "com.google.android.youtube"),
            ignoredPackages = emptySet(),
            lastInteractedDisplayId = 1,
            lastInteractedPackage = "com.android.settings",
            focusedDisplayId = 0,
            focusedPackage = "com.google.android.youtube"
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must push Settings bottom→top, got ${shell.commands}",
            shell.commands.any { it == "moveStack:20->0" || it == "moveTask:20->0" }
        )
        assertFalse(
            "Must not move YouTube as if it were the active app",
            shell.commands.any { it.contains("10->") && !it.startsWith("moveToBack:") }
        )
        assertTrue(shell.commands.any { it == "launchHome:1" })
    }

    @Test
    fun longBack_stickyTopFocus_recentActiveBottom_pushesSettingsNotYoutube() = runBlocking {
        // No a11y last-interact; key-focus stuck on YouTube; dumpsys lastActiveTime says Settings.
        val dumpsys = """
            Display #0
              RootTask #10
              * Task{a #10 type=standard A=1:com.google.android.youtube U=0 visible=true lastActiveTime=100}
                Hist #0: ActivityRecord{a1 u0 com.google.android.youtube/.Watch t10}
              mResumedActivity: ActivityRecord{a1 u0 com.google.android.youtube/.Watch t10}
            Display #1
              RootTask #20
              * Task{b #20 type=standard A=2:com.android.settings U=0 visible=true lastActiveTime=5000}
                Hist #0: ActivityRecord{b1 u0 com.android.settings/.Settings t20}
              mResumedActivity: ActivityRecord{b1 u0 com.android.settings/.Settings t20}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 0,
            otherDisplayId = 1,
            displayApps = mapOf(
                0 to "com.google.android.youtube",
                1 to "com.android.settings"
            ),
            ignoredPackages = emptySet(),
            focusedDisplayId = 0,
            focusedPackage = "com.google.android.youtube"
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must push Settings (recent lastActiveTime) bottom→top, got ${shell.commands}",
            shell.commands.any { it == "moveStack:20->0" || it == "moveTask:20->0" }
        )
        assertFalse(
            "Must not push YouTube just because key-focus is on top",
            shell.commands.any { it.contains("10->1") || it == "moveStack:10->1" || it == "moveTask:10->1" }
        )
        assertTrue(shell.commands.any { it == "launchHome:1" })
    }

    @Test
    fun longBack_focusTopUnderlay_a11yOnlyBottom_movesBottomToTop() = runBlocking {
        val dumpsys = """
            Display #0
              RootTask #7
              * Task{bg #7 type=standard A=9:com.underlay U=0 visible=true}
                Hist #0: ActivityRecord{r u0 com.underlay/.Main t7}
              mResumedActivity: ActivityRecord{r u0 com.underlay/.Main t7}
            Display #1
              RootTask #42
              * Task{t #42 type=standard A=1:com.bottom U=0 visible=true}
                Hist #0: ActivityRecord{z u0 com.bottom/.Main t42}
              mResumedActivity: ActivityRecord{z u0 com.bottom/.Main t42}
        """.trimIndent()
        val shell = FakeShell(dumpsys)
        val result = swapper(shell).pushActiveAppToOther(
            activeDisplayId = 0,
            otherDisplayId = 1,
            displayApps = mapOf(1 to "com.bottom"),
            ignoredPackages = ignored
        )
        assertTrue(result.message, result.success)
        assertTrue(
            "Must push real bottom app to top, not underlay; got ${shell.commands}",
            shell.commands.any { it == "moveStack:42->0" || it == "moveTask:42->0" }
        )
        assertFalse(
            "Must not move underlay as if it were the active app",
            shell.commands.any { it.contains("7->") }
        )
    }
}
