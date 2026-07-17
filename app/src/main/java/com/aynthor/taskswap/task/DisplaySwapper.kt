package com.aynthor.taskswap.task

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import com.aynthor.taskswap.adb.AdbConnectionManager
import com.aynthor.taskswap.adb.ShellExecutor
import kotlinx.coroutines.delay

class DisplaySwapper(
    private val context: Context?,
    private val shellExecutor: ShellExecutor = ShellExecutor(),
    private val taskResolver: TaskResolver = TaskResolver(),
    private val ensureAdb: suspend () -> Result<Unit> = { AdbConnectionManager.ensureConnected() }
) {

    companion object {
        private const val TAG = "DisplaySwapper"
        private const val MOVE_SETTLE_MS = 500L
    }

    data class SwapResult(
        val success: Boolean,
        val message: String
    )

    suspend fun performSwapOrSend(
        displayApps: Map<Int, String>,
        displayIds: List<Int>,
        ignoredPackages: Set<String>
    ): SwapResult {
        val ignored = withShellPackages(ignoredPackages)
        val effectiveIds = (displayIds + displayApps.keys).distinct()
        if (effectiveIds.size < 2 && displayApps.size < 1) {
            return SwapResult(false, "Найден только ${effectiveIds.size} экран")
        }

        val connectResult = ensureAdb()
        if (connectResult.isFailure) {
            return SwapResult(false, "ADB не подключён: ${connectResult.exceptionOrNull()?.message}")
        }

        val dumpsys = shellExecutor.dumpsysActivities().getOrElse {
            return SwapResult(false, "Не удалось прочитать задачи: ${it.message}")
        }
        val stackList = shellExecutor.stackList().getOrNull()
        val fromDumpsys = taskResolver.resolveDisplayApps(dumpsys, ignored, stackList)

        // Prefer displays that actually host apps (a11y + dumpsys), otherwise virtual IDs steal the pair.
        val preferred = displayApps.keys + fromDumpsys.keys
        val pair = DisplayPairing.pickSwapDisplays(
            displayIds = effectiveIds.ifEmpty { preferred.toList() },
            preferredAppDisplays = preferred
        )
        if (pair.size < 2) {
            return SwapResult(false, "Найден только ${pair.size} экран")
        }

        val mergedApps = DisplayPairing.mergeDisplayApps(
            fromDumpsys = fromDumpsys,
            fromAccessibility = displayApps,
            displayIds = (pair + preferred).distinct(),
            ignoredPackages = ignored
        )
        Log.d(TAG, "Display apps for swap (pair=$pair): $mergedApps (a11y=$displayApps)")

        // Accessibility "one visible app" wins over dumpsys underlays / shells on the empty screen.
        val a11yOccupied = DisplayPairing.occupiedOnDisplays(
            displayApps.filterValues { it !in ignored },
            pair
        )
        var occupied = when {
            a11yOccupied.size == 1 -> a11yOccupied
            else -> DisplayPairing.occupiedOnDisplays(mergedApps, pair)
        }
        if (occupied.isEmpty()) {
            occupied = mergedApps.entries.map { it.key to it.value }
        }
        if (occupied.isEmpty() && a11yOccupied.isNotEmpty()) {
            occupied = a11yOccupied
        }
        if (occupied.isEmpty()) {
            return SwapResult(false, "На экранах нет приложений для обмена.")
        }

        // Exactly one real app anywhere → move it to the other screen of the pair (both directions).
        if (occupied.size == 1) {
            val (from, pkg) = occupied.first()
            val to = pair.firstOrNull { it != from }
                ?: effectiveIds.firstOrNull { it != from }
                ?: return SwapResult(false, "Второй экран не найден")
            return moveAppToDisplay(
                dumpsys = dumpsys,
                packageName = pkg,
                fromDisplayId = from,
                toDisplayId = to,
                ignoredPackages = ignored,
                successMessage = "Перенесли $pkg на экран $to"
            )
        }

        // Two+ apps on the pair → classic swap / partial send
        val pairOccupied = if (a11yOccupied.size == 1) {
            a11yOccupied
        } else {
            DisplayPairing.occupiedOnDisplays(mergedApps, pair)
        }
        if (pairOccupied.size == 1) {
            val move = DisplayTransferRules.singleAppMove(pairOccupied, pair)
                ?: return SwapResult(false, "Не удалось выбрать целевой экран")
            return moveAppToDisplay(
                dumpsys = dumpsys,
                packageName = move.packageName,
                fromDisplayId = move.fromDisplayId,
                toDisplayId = move.toDisplayId,
                ignoredPackages = ignored,
                successMessage = "Перенесли ${move.packageName} на экран ${move.toDisplayId}"
            )
        }

        val d0 = pair[0]
        val d1 = pair[1]
        val appsForSwap = if (a11yOccupied.size >= 2) {
            a11yOccupied.toMap()
        } else {
            mergedApps
        }
        val app0 = appsForSwap[d0]
        val app1 = appsForSwap[d1]

        if (app0 != null && app1 != null && app0 == app1) {
            return SwapResult(false, "Одно приложение на обоих экранах — откройте разные приложения.")
        }

        val tasksByDisplay = taskResolver.findForPackages(
            dumpsys,
            listOfNotNull(app0?.let { d0 to it }, app1?.let { d1 to it }).toMap(),
            ignored
        )

        return when {
            app0 != null && app1 != null -> {
                val task0 = tasksByDisplay[d0] ?: return taskNotFound(app0, d0, dumpsys)
                val task1 = tasksByDisplay[d1] ?: return taskNotFound(app1, d1, dumpsys)
                swapTasks(task0, task1, d0, d1, ignored)
            }
            app0 != null -> {
                val task0 = tasksByDisplay[d0] ?: return taskNotFound(app0, d0, dumpsys)
                moveSingle(task0, d1, "На втором экране нет приложения — отправили ${task0.packageName}")
            }
            app1 != null -> {
                val task1 = tasksByDisplay[d1] ?: return taskNotFound(app1, d1, dumpsys)
                moveSingle(task1, d0, "На первом экране нет приложения — отправили ${task1.packageName}")
            }
            else -> SwapResult(
                false,
                "На экранах нет приложений для обмена. Откройте приложения на обоих экранах."
            )
        }
    }

    suspend fun pushActiveAppToOther(
        activeDisplayId: Int,
        otherDisplayId: Int,
        displayApps: Map<Int, String>,
        ignoredPackages: Set<String>,
        preferredPackage: String? = null,
        lastInteractedDisplayId: Int? = null,
        lastInteractedPackage: String? = null,
        focusedDisplayId: Int? = null,
        focusedPackage: String? = null
    ): SwapResult {
        val ignored = withShellPackages(ignoredPackages)
        val connectResult = ensureAdb()
        if (connectResult.isFailure) {
            return SwapResult(false, "ADB не подключён: ${connectResult.exceptionOrNull()?.message}")
        }

        val dumpsys = shellExecutor.dumpsysActivities().getOrElse {
            return SwapResult(false, "Не удалось прочитать задачи: ${it.message}")
        }
        val stackList = shellExecutor.stackList().getOrNull()
        val pair = listOf(activeDisplayId, otherDisplayId)
        val mergedApps = resolveAppsForSwap(dumpsys, stackList, displayApps, ignored, pair)
        val visibleApps = displayApps
            .filterKeys { it in pair }
            .filterValues { it !in ignored }
        val appsForPlan = LinkedHashMap<Int, String>()
        appsForPlan.putAll(visibleApps)
        for (id in pair) {
            mergedApps[id]?.takeIf { it !in ignored }?.let { appsForPlan.putIfAbsent(id, it) }
        }

        val recent = taskResolver.findMostRecentlyActiveApp(dumpsys, pair, ignored)
        val actor = DisplayTransferRules.resolvePushActor(
            pair = pair,
            apps = appsForPlan,
            lastInteractedDisplayId = lastInteractedDisplayId,
            lastInteractedPackage = lastInteractedPackage,
            recentActiveDisplayId = recent?.first,
            recentActivePackage = recent?.second,
            focusedDisplayId = focusedDisplayId,
            focusedPackage = focusedPackage ?: preferredPackage
        )
        val sourceDisplayId = actor?.displayId ?: activeDisplayId
        val targetDisplayId = pair.firstOrNull { it != sourceDisplayId } ?: otherDisplayId
        // Prefer resolved actor; if none, let planPush use visible/sole-app rules (underlay case).
        val preferred = actor?.packageName

        Log.i(
            TAG,
            "Push actor=$sourceDisplayId/$preferred recent=$recent " +
                "lastInteract=$lastInteractedDisplayId/$lastInteractedPackage " +
                "focus=$focusedDisplayId/$focusedPackage apps=$appsForPlan"
        )

        val plan = DisplayTransferRules.planPush(
            activeDisplayId = sourceDisplayId,
            otherDisplayId = targetDisplayId,
            apps = appsForPlan,
            visibleApps = visibleApps,
            preferredPackage = preferred
        ) ?: return SwapResult(false, "На активном экране нет приложения")

        if (plan.packageToBury != null && plan.packageToBury !in ignored) {
            buryAppOnDisplay(dumpsys, plan.targetDisplayId, plan.packageToBury, ignored)
            delay(MOVE_SETTLE_MS)
        }

        // Home MUST run before the move. After move, focus follows the app to the target;
        // Thor then applies HOME to the focused (target) display and leaves it empty.
        val source = plan.sourceDisplayId
        val target = plan.targetDisplayId
        Log.i(TAG, "Long-Back push: Home on $source, then move ${plan.packageToMove} → $target")
        val home = shellExecutor.launchHomeOnDisplay(source)
        if (home.isFailure || home.getOrNull()?.success != true) {
            Log.w(
                TAG,
                "Home on source $source failed: " +
                    "${home.exceptionOrNull()?.message ?: home.getOrNull()?.output}"
            )
        }
        delay(MOVE_SETTLE_MS)

        return moveAppToDisplay(
            dumpsys = shellExecutor.dumpsysActivities().getOrElse { dumpsys },
            packageName = plan.packageToMove,
            fromDisplayId = source,
            toDisplayId = target,
            ignoredPackages = ignored,
            successMessage = "Перенесли ${plan.packageToMove} на экран $target"
        )
    }

    private suspend fun buryAppOnDisplay(
        dumpsys: String,
        displayId: Int,
        packageName: String,
        ignoredPackages: Set<String>
    ) {
        val task = taskResolver.findForPackageOnDisplayOnly(dumpsys, packageName, displayId, ignoredPackages)
            ?: return
        val moveBack = shellExecutor.moveTaskToBack(task.taskId)
        if (moveBack.isFailure || moveBack.getOrNull()?.success != true) {
            Log.w(TAG, "bury $packageName on $displayId failed: ${moveBack.exceptionOrNull()?.message ?: moveBack.getOrNull()?.output}")
        }
    }

    private suspend fun moveAppToDisplay(
        dumpsys: String,
        packageName: String,
        fromDisplayId: Int,
        toDisplayId: Int,
        ignoredPackages: Set<String>,
        successMessage: String
    ): SwapResult {
        val task = taskResolver.findForPackage(dumpsys, packageName, fromDisplayId, ignoredPackages)
            ?: taskResolver.findForPackage(dumpsys, packageName, null, ignoredPackages)
            ?: return taskNotFound(packageName, fromDisplayId, dumpsys)

        val pair = listOf(fromDisplayId, toDisplayId)
        val resolvedTo = DisplayTransferRules.resolveTransferDestination(
            preferredSourceDisplayId = fromDisplayId,
            taskDisplayId = task.displayId,
            pair = pair
        )

        if (task.displayId == resolvedTo) {
            Log.i(TAG, "Task $packageName dumpsys=@${task.displayId} but plan→$resolvedTo; forcing move")
        }

        Log.i(TAG, "Move $packageName: plan $fromDisplayId→$toDisplayId, dumpsys@${task.displayId} → $resolvedTo")

        var move = shellExecutor.moveStack(task.rootTaskId, resolvedTo)
        if (move.isFailure || move.getOrNull()?.success != true) {
            move = shellExecutor.moveTaskToDisplay(task.taskId, resolvedTo)
        }
        if (move.isFailure || move.getOrNull()?.success != true) {
            val output = move.getOrNull()?.output.orEmpty()
            return SwapResult(
                false,
                "Не удалось переместить $packageName: ${move.exceptionOrNull()?.message ?: output}"
            )
        }

        delay(200)
        maybeResize(task, resolvedTo)

        return SwapResult(true, "Перенесли $packageName на экран $resolvedTo")
    }

    private fun withShellPackages(ignoredPackages: Set<String>): Set<String> =
        ignoredPackages + ShellPackages.ALWAYS_IGNORED

    private fun resolveAppsForSwap(
        dumpsys: String,
        stackList: String?,
        displayApps: Map<Int, String>,
        ignoredPackages: Set<String>,
        displayIds: List<Int> = emptyList()
    ): Map<Int, String> {
        val fromDumpsys = taskResolver.resolveDisplayApps(dumpsys, ignoredPackages, stackList)
        return DisplayPairing.mergeDisplayApps(
            fromDumpsys = fromDumpsys,
            fromAccessibility = displayApps,
            displayIds = displayIds.ifEmpty { (fromDumpsys.keys + displayApps.keys).distinct() },
            ignoredPackages = ignoredPackages
        )
    }

    private fun taskNotFound(pkg: String, displayId: Int, dumpsys: String): SwapResult {
        val snippet = dumpsys.lines()
            .filter { it.contains(pkg) }
            .take(3)
            .joinToString("\n")
        Log.w(TAG, "Task not found for $pkg on display $displayId. Snippet:\n$snippet")
        return SwapResult(
            false,
            "Задача не найдена: $pkg (экран $displayId).\n" +
                "Попробуйте снова или откройте другое приложение."
        )
    }

    private suspend fun swapTasks(
        task0: TaskInfo,
        task1: TaskInfo,
        display0: Int,
        display1: Int,
        ignoredPackages: Set<String>
    ): SwapResult {
        Log.i(TAG, "Swap ${task0.packageName}@$display0 <-> ${task1.packageName}@$display1")

        // Сначала освобождаем целевой экран: переносим нижний/второй стек.
        val moveFirst = shellExecutor.moveStack(task1.rootTaskId, display0)
        if (moveFirst.isFailure || moveFirst.getOrNull()?.success != true) {
            val output = moveFirst.getOrNull()?.output.orEmpty()
            return SwapResult(
                false,
                "Не удалось переместить ${task1.packageName}: ${moveFirst.exceptionOrNull()?.message ?: output}"
            )
        }

        delay(MOVE_SETTLE_MS)

        val dumpsysAfterFirst = shellExecutor.dumpsysActivities().getOrElse {
            return SwapResult(false, "Первый перенос выполнен, но не удалось проверить состояние: ${it.message}")
        }
        val refreshedTask0 = taskResolver.findForPackage(
            dumpsysAfterFirst,
            task0.packageName,
            display0,
            ignoredPackages
        ) ?: taskResolver.findForPackage(
            dumpsysAfterFirst,
            task0.packageName,
            null,
            ignoredPackages
        ) ?: task0

        val moveSecond = shellExecutor.moveStack(refreshedTask0.rootTaskId, display1)
        if (moveSecond.isFailure || moveSecond.getOrNull()?.success != true) {
            val output = moveSecond.getOrNull()?.output.orEmpty()
            return SwapResult(
                false,
                "Перенесли ${task1.packageName}, но не удалось переместить ${task0.packageName}: " +
                    "${moveSecond.exceptionOrNull()?.message ?: output}"
            )
        }

        delay(200)
        maybeResize(refreshedTask0, display1)
        maybeResize(task1, display0)

        return SwapResult(true, "Поменяли местами: ${task0.packageName} ↔ ${task1.packageName}")
    }

    private suspend fun moveSingle(task: TaskInfo, targetDisplay: Int, successMessage: String): SwapResult {
        Log.i(TAG, "Send ${task.packageName} -> display $targetDisplay")
        val move = shellExecutor.moveStack(task.rootTaskId, targetDisplay)
        if (move.isFailure || move.getOrNull()?.success != true) {
            val output = move.getOrNull()?.output.orEmpty()
            return SwapResult(
                false,
                "Не удалось переместить ${task.packageName}: ${move.exceptionOrNull()?.message ?: output}"
            )
        }
        maybeResize(task, targetDisplay)
        return SwapResult(true, successMessage)
    }

    private suspend fun maybeResize(task: TaskInfo, targetDisplay: Int) {
        val size = getDisplaySize(targetDisplay) ?: return
        val result = shellExecutor.resizeTask(task.taskId, 0, 0, size.width, size.height)
        if (result.isFailure || result.getOrNull()?.success != true) {
            Log.w(TAG, "Resize skipped for ${task.packageName}: ${result.exceptionOrNull()?.message ?: result.getOrNull()?.output}")
        }
    }

    /**
     * Bring [packageName] to [targetDisplayId] by moving its existing task.
     * Used when the user picks a bottom-display app from Recents/task list on the other screen —
     * must not wipe both displays with Home.
     */
    suspend fun ensureAppOnDisplay(
        packageName: String,
        targetDisplayId: Int,
        ignoredPackages: Set<String>
    ): SwapResult {
        val ignored = withShellPackages(ignoredPackages)
        if (ShellPackages.isShellOrIgnored(packageName, ignored)) {
            return SwapResult(false, "Shell package ignored: $packageName")
        }
        val connectResult = ensureAdb()
        if (connectResult.isFailure) {
            return SwapResult(false, "ADB не подключён: ${connectResult.exceptionOrNull()?.message}")
        }
        val dumpsys = shellExecutor.dumpsysActivities().getOrElse {
            return SwapResult(false, "Не удалось прочитать задачи: ${it.message}")
        }
        val task = taskResolver.findForPackage(dumpsys, packageName, null, ignored)
            ?: return SwapResult(false, "Задача не найдена: $packageName")
        if (task.displayId == targetDisplayId) {
            return SwapResult(true, "$packageName уже на экране $targetDisplayId")
        }
        var move = shellExecutor.moveStack(task.rootTaskId, targetDisplayId)
        if (move.isFailure || move.getOrNull()?.success != true) {
            move = shellExecutor.moveTaskToDisplay(task.taskId, targetDisplayId)
        }
        if (move.isFailure || move.getOrNull()?.success != true) {
            return SwapResult(
                false,
                "Не удалось перенести $packageName на экран $targetDisplayId: " +
                    "${move.exceptionOrNull()?.message ?: move.getOrNull()?.output}"
            )
        }
        maybeResize(task, targetDisplayId)
        return SwapResult(true, "Перенесли $packageName на экран $targetDisplayId")
    }

    suspend fun fetchDisplayApps(
        fallback: Map<Int, String>,
        ignoredPackages: Set<String>
    ): Map<Int, String> {
        val connectResult = ensureAdb()
        if (connectResult.isFailure) return fallback

        val dumpsys = shellExecutor.dumpsysActivities().getOrElse { return fallback }
        val stackList = shellExecutor.stackList().getOrNull()
        val fromDumpsys = taskResolver.resolveDisplayApps(dumpsys, ignoredPackages, stackList)
        return fromDumpsys.ifEmpty { fallback }
    }

    suspend fun hideAppOnDisplay(
        displayId: Int,
        packageName: String,
        ignoredPackages: Set<String>
    ): SwapResult {
        val connectResult = ensureAdb()
        if (connectResult.isFailure) {
            return SwapResult(false, "ADB не подключён: ${connectResult.exceptionOrNull()?.message}")
        }

        val dumpsys = shellExecutor.dumpsysActivities().getOrElse {
            return SwapResult(false, "Не удалось прочитать задачи: ${it.message}")
        }

        val task = taskResolver.findForPackage(dumpsys, packageName, displayId, ignoredPackages)
            ?: return SwapResult(false, "Задача не найдена: $packageName")

        val moveBack = shellExecutor.moveTaskToBack(task.taskId)
        if (moveBack.isFailure || moveBack.getOrNull()?.success != true) {
            return SwapResult(
                false,
                "Не удалось скрыть $packageName: ${moveBack.exceptionOrNull()?.message ?: moveBack.getOrNull()?.output}"
            )
        }

        delay(100)

        val home = shellExecutor.launchHomeOnDisplay(displayId)
        if (home.isFailure || home.getOrNull()?.success != true) {
            Log.w(TAG, "Home launch on display $displayId failed: ${home.exceptionOrNull()?.message ?: home.getOrNull()?.output}")
        }

        return SwapResult(true, "Скрыли $packageName на экране $displayId")
    }

    private fun getDisplaySize(displayId: Int): Size? {
        val ctx = context ?: return null
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(displayId) ?: return null
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels)
    }
}
