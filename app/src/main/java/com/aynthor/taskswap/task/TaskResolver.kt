package com.aynthor.taskswap.task

import android.util.Log

data class TaskInfo(
    val rootTaskId: Int,
    val taskId: Int,
    val packageName: String,
    val displayId: Int
)

class TaskResolver {

    companion object {
        private const val TAG = "TaskResolver"
    }

    fun parse(dumpsys: String, ignoredPackages: Set<String> = emptySet()): List<TaskInfo> {
        val modern = parseModernFormat(dumpsys, ignoredPackages)
        if (modern.isNotEmpty()) return modern

        val taskDisplayArea = parseTaskDisplayAreaFormat(dumpsys, ignoredPackages)
        if (taskDisplayArea.isNotEmpty()) return taskDisplayArea

        val legacy = parseLegacyFormat(dumpsys, ignoredPackages)
        if (legacy.isNotEmpty()) return legacy

        return parseByActivityRecords(dumpsys, ignoredPackages)
    }

    fun parseForegroundPerDisplay(
        dumpsys: String,
        ignoredPackages: Set<String> = emptySet()
    ): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        val displaySections = Regex("""Display #(\d+)[^\n]*""")
            .findAll(dumpsys)
            .map { it.range.first to it.groupValues[1].toInt() }
            .toList()

        for (i in displaySections.indices) {
            val (start, displayId) = displaySections[i]
            val end = displaySections.getOrNull(i + 1)?.first ?: dumpsys.length
            val section = dumpsys.substring(start, end)

            val resumed = Regex("""mResumedActivity:\s*ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")
                .find(section)?.groupValues?.get(1)
            val top = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")
                .find(section)?.groupValues?.get(1)
            val focused = Regex("""mFocusedApp=ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")
                .find(section)?.groupValues?.get(1)

            val pkg = listOfNotNull(resumed, top, focused)
                .firstOrNull { it !in ignoredPackages }
                ?: findTopPackageInSection(section, ignoredPackages)

            if (pkg != null) {
                result[displayId] = pkg
            }
        }
        return result
    }

    fun findForPackage(
        dumpsys: String,
        packageName: String,
        displayId: Int?,
        ignoredPackages: Set<String> = emptySet()
    ): TaskInfo? {
        val tasks = parse(dumpsys, ignoredPackages)
        if (displayId != null) {
            tasks.firstOrNull { it.packageName == packageName && it.displayId == displayId }?.let { return it }
        }
        tasks.firstOrNull { it.packageName == packageName }?.let { return it }
        return findTaskByPackageSearch(dumpsys, packageName, displayId)
    }

    /** Only match a task that is still reported on [displayId] — no cross-display fallback. */
    fun findForPackageOnDisplayOnly(
        dumpsys: String,
        packageName: String,
        displayId: Int,
        ignoredPackages: Set<String> = emptySet()
    ): TaskInfo? {
        return parse(dumpsys, ignoredPackages)
            .firstOrNull { it.packageName == packageName && it.displayId == displayId }
    }

    fun resolveDisplayApps(
        dumpsys: String,
        ignoredPackages: Set<String> = emptySet(),
        stackList: String? = null
    ): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        result.putAll(parseForegroundPerDisplay(dumpsys, ignoredPackages))

        val tasksByDisplay = parse(dumpsys, ignoredPackages).groupBy { it.displayId }
        for ((displayId, tasks) in tasksByDisplay) {
            if (!result.containsKey(displayId)) {
                result[displayId] = tasks.last().packageName
            }
        }

        if (stackList != null) {
            result.putAll(parseStackList(stackList, ignoredPackages))
        }

        return result.filter { (displayId, packageName) ->
            findForPackage(dumpsys, packageName, displayId, ignoredPackages) != null
        }
    }

    fun parseStackList(stackList: String, ignoredPackages: Set<String> = emptySet()): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        var currentDisplay: Int? = null

        val displayPattern = Regex("""Display #(\d+)""")
        val displayIdPattern = Regex("""displayId=(\d+)""")
        val affinityPattern = Regex("""A=(?:\d+:)?([\w.]+)""")
        val activityPattern = Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")

        for (line in stackList.lines()) {
            displayPattern.find(line)?.let { currentDisplay = it.groupValues[1].toInt() }
            displayIdPattern.find(line)?.let { currentDisplay = it.groupValues[1].toInt() }

            val displayId = currentDisplay ?: continue
            val pkg = affinityPattern.find(line)?.groupValues?.get(1)
                ?: activityPattern.find(line)?.groupValues?.get(1)
                ?: continue
            if (pkg in ignoredPackages) continue
            result[displayId] = pkg
        }
        return result
    }

    fun findForPackages(
        dumpsys: String,
        displayApps: Map<Int, String>,
        ignoredPackages: Set<String>
    ): Map<Int, TaskInfo> {
        val result = mutableMapOf<Int, TaskInfo>()
        for ((displayId, packageName) in displayApps) {
            val task = findForPackage(dumpsys, packageName, displayId, ignoredPackages)
            if (task != null) {
                result[displayId] = task
            } else {
                Log.w(TAG, "No task found for $packageName on display $displayId")
            }
        }
        return result
    }

    private fun findTopPackageInSection(section: String, ignoredPackages: Set<String>): String? {
        val histPattern = Regex("""Hist #\d+: ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")
        for (match in histPattern.findAll(section)) {
            val pkg = match.groupValues[1]
            if (pkg !in ignoredPackages) return pkg
        }
        return null
    }

    private fun findTaskByPackageSearch(
        dumpsys: String,
        packageName: String,
        preferredDisplayId: Int?
    ): TaskInfo? {
        val lines = dumpsys.lines()
        var best: TaskInfo? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains(packageName)) continue

            var rootTaskId: Int? = null
            var taskId: Int? = null
            var displayId = preferredDisplayId ?: -1

            for (j in i downTo maxOf(0, i - 30)) {
                val back = lines[j]
                if (rootTaskId == null) {
                    Regex("""RootTask #(\d+)""").find(back)?.let { rootTaskId = it.groupValues[1].toInt() }
                    Regex("""\* Task\{[^#]*#(\d+)""").find(back)?.let {
                        taskId = it.groupValues[1].toInt()
                        if (rootTaskId == null) rootTaskId = taskId
                    }
                    Regex("""Task id #(\d+)""").find(back)?.let {
                        taskId = it.groupValues[1].toInt()
                        if (rootTaskId == null) rootTaskId = taskId
                    }
                }
                Regex("""Display #(\d+)""").find(back)?.let { displayId = it.groupValues[1].toInt() }
                Regex("""mDisplayId=(\d+)""").find(back)?.let { displayId = it.groupValues[1].toInt() }
            }

            Regex("""\bt(\d+)\b""").find(line)?.let {
                taskId = it.groupValues[1].toInt()
                if (rootTaskId == null) rootTaskId = taskId
            }

            val root = rootTaskId ?: continue
            val task = taskId ?: root
            val resolvedDisplay = when {
                displayId >= 0 -> displayId
                preferredDisplayId != null -> preferredDisplayId
                else -> 0
            }

            val candidate = TaskInfo(root, task, packageName, resolvedDisplay)
            if (preferredDisplayId == null || resolvedDisplay == preferredDisplayId) {
                return candidate
            }
            if (best == null) best = candidate
        }
        return best
    }

    private fun parseModernFormat(dumpsys: String, ignoredPackages: Set<String>): List<TaskInfo> {
        val results = mutableListOf<TaskInfo>()
        val displaySections = Regex("""Display #(\d+)[^\n]*""")
            .findAll(dumpsys)
            .map { it.range.first to it.groupValues[1].toInt() }
            .toList()

        if (displaySections.isEmpty()) return emptyList()

        for (i in displaySections.indices) {
            val (start, displayId) = displaySections[i]
            val end = displaySections.getOrNull(i + 1)?.first ?: dumpsys.length
            val section = dumpsys.substring(start, end)
            results += parseDisplaySection(section, displayId, ignoredPackages)
        }
        return results.distinctBy { "${it.rootTaskId}:${it.packageName}:${it.displayId}" }
    }

    private fun parseTaskDisplayAreaFormat(dumpsys: String, ignoredPackages: Set<String>): List<TaskInfo> {
        val results = mutableListOf<TaskInfo>()
        var currentDisplay = 0
        var currentRootTaskId: Int? = null

        val displayPattern = Regex("""Display #(\d+)""")
        val rootTaskPattern = Regex("""RootTask #(\d+)""")
        val taskPattern = Regex("""\* Task\{[^#]*#(\d+)[^}]*\}""")
        val affinityPattern = Regex("""A=(?:\d+:)?([\w.]+)""")
        val activityPattern = Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")

        for (line in dumpsys.lines()) {
            displayPattern.find(line)?.let { currentDisplay = it.groupValues[1].toInt() }
            rootTaskPattern.find(line)?.let { currentRootTaskId = it.groupValues[1].toInt() }

            val taskMatch = taskPattern.find(line)
            if (taskMatch != null) {
                val taskId = taskMatch.groupValues[1].toInt()
                val rootId = currentRootTaskId ?: taskId
                val pkg = affinityPattern.find(line)?.groupValues?.get(1)
                    ?: activityPattern.find(line)?.groupValues?.get(1)
                if (pkg != null && pkg !in ignoredPackages) {
                    results += TaskInfo(rootId, taskId, pkg, currentDisplay)
                }
                continue
            }

            if (line.contains("Hist #") && line.contains("ActivityRecord")) {
                val pkg = activityPattern.find(line)?.groupValues?.get(1) ?: continue
                if (pkg in ignoredPackages) continue
                val taskId = Regex("""\bt(\d+)\b""").find(line)?.groupValues?.get(1)?.toInt()
                val rootId = currentRootTaskId ?: taskId ?: continue
                results += TaskInfo(rootId, taskId ?: rootId, pkg, currentDisplay)
            }
        }
        return results.distinctBy { "${it.rootTaskId}:${it.packageName}:${it.displayId}" }
    }

    private fun parseDisplaySection(
        section: String,
        displayId: Int,
        ignoredPackages: Set<String>
    ): List<TaskInfo> {
        val results = mutableListOf<TaskInfo>()
        val rootTaskPattern = Regex("""RootTask #(\d+)""")
        val taskPattern = Regex("""\* Task\{[^#]*#(\d+)[^}]*\}""")
        val affinityPattern = Regex("""A=(?:\d+:)?([\w.]+)""")
        val activityPattern = Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")

        var currentRootTaskId: Int? = null
        for (line in section.lines()) {
            rootTaskPattern.find(line)?.let { currentRootTaskId = it.groupValues[1].toInt() }

            val taskMatch = taskPattern.find(line)
            if (taskMatch != null) {
                val taskId = taskMatch.groupValues[1].toInt()
                val rootId = currentRootTaskId ?: taskId
                val pkg = affinityPattern.find(line)?.groupValues?.get(1)
                    ?: activityPattern.find(line)?.groupValues?.get(1)
                if (pkg != null && pkg !in ignoredPackages) {
                    results += TaskInfo(rootId, taskId, pkg, displayId)
                }
                continue
            }

            if (line.contains("Hist #") && line.contains("ActivityRecord")) {
                val pkg = activityPattern.find(line)?.groupValues?.get(1) ?: continue
                if (pkg in ignoredPackages) continue
                val taskId = Regex("""\bt(\d+)\b""").find(line)?.groupValues?.get(1)?.toInt()
                val rootId = currentRootTaskId ?: taskId ?: continue
                results += TaskInfo(rootId, taskId ?: rootId, pkg, displayId)
            }
        }
        return results
    }

    private fun parseLegacyFormat(dumpsys: String, ignoredPackages: Set<String>): List<TaskInfo> {
        val results = mutableListOf<TaskInfo>()
        var currentStackId: Int? = null
        var currentDisplay = 0

        val stackPattern = Regex("""Stack #(\d+)""")
        val displayPattern = Regex("""Display #(\d+)""")
        val taskIdPattern = Regex("""Task id #(\d+)""")
        val affinityPattern = Regex("""A=(?:\d+:)?([\w.]+)""")
        val activityPattern = Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""")

        for (line in dumpsys.lines()) {
            displayPattern.find(line)?.let { currentDisplay = it.groupValues[1].toInt() }
            stackPattern.find(line)?.let { currentStackId = it.groupValues[1].toInt() }

            val taskMatch = taskIdPattern.find(line)
            if (taskMatch != null) {
                val taskId = taskMatch.groupValues[1].toInt()
                val stackId = currentStackId ?: taskId
                val packageName = affinityPattern.find(line)?.groupValues?.get(1)
                    ?: activityPattern.find(line)?.groupValues?.get(1)
                if (packageName != null && packageName !in ignoredPackages) {
                    results += TaskInfo(stackId, taskId, packageName, currentDisplay)
                }
            }
        }
        return results
    }

    private fun parseByActivityRecords(dumpsys: String, ignoredPackages: Set<String>): List<TaskInfo> {
        val results = mutableListOf<TaskInfo>()
        val pattern = Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/[^\s]+\s+t(\d+)""")
        for (match in pattern.findAll(dumpsys)) {
            val pkg = match.groupValues[1]
            if (pkg in ignoredPackages) continue
            val taskId = match.groupValues[2].toInt()
            results += TaskInfo(taskId, taskId, pkg, 0)
        }
        return results.distinctBy { "${it.taskId}:${it.packageName}" }
    }

    /**
     * Among [displayIds], pick the real app whose task has the highest lastActiveTime.
     * Used when key-focus sticks to the top display but the user was just on the bottom.
     */
    fun findMostRecentlyActiveApp(
        dumpsys: String,
        displayIds: Collection<Int>,
        ignoredPackages: Set<String> = emptySet()
    ): Pair<Int, String>? {
        val idSet = displayIds.toSet()
        if (idSet.isEmpty()) return null
        var best: Triple<Long, Int, String>? = null

        val displaySections = Regex("""Display #(\d+)[^\n]*""")
            .findAll(dumpsys)
            .map { it.range.first to it.groupValues[1].toInt() }
            .toList()

        for (i in displaySections.indices) {
            val (start, displayId) = displaySections[i]
            if (displayId !in idSet) continue
            val end = displaySections.getOrNull(i + 1)?.first ?: dumpsys.length
            val lines = dumpsys.substring(start, end).lines()
            for (idx in lines.indices) {
                val line = lines[idx]
                if (!line.contains("Task{")) continue
                val window = lines.subList(idx, minOf(idx + 6, lines.size)).joinToString("\n")
                val time = Regex("""lastActiveTime=(\d+)""").find(window)?.groupValues?.get(1)?.toLongOrNull()
                    ?: continue
                val pkg = Regex("""A=(?:\d+:)?([\w.]+)""").find(window)?.groupValues?.get(1)
                    ?: Regex("""ActivityRecord\{[^ ]+ [^ ]+ ([\w.]+)/""").find(window)?.groupValues?.get(1)
                    ?: continue
                if (pkg in ignoredPackages || ShellPackages.isShellOrIgnored(pkg)) continue
                if (best == null || time > best!!.first) {
                    best = Triple(time, displayId, pkg)
                }
            }
        }
        return best?.let { (_, displayId, pkg) -> displayId to pkg }
    }
}
