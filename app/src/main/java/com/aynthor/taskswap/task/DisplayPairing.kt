package com.aynthor.taskswap.task

/**
 * Pure helpers for choosing which two displays participate in swap/transfer.
 * Thor often exposes extra virtual display IDs — prefer display 0 + the peer
 * that currently hosts an app (fallback: next sorted id).
 */
object DisplayPairing {

    fun pickSwapDisplays(
        displayIds: List<Int>,
        preferredAppDisplays: Set<Int> = emptySet()
    ): List<Int> {
        val sorted = displayIds.distinct().sorted()
        if (sorted.size <= 1) return sorted
        val primary = if (0 in sorted) 0 else sorted.first()
        val withApp = preferredAppDisplays.filter { it != primary && it in sorted }
        val secondary = withApp.minOrNull() ?: sorted.first { it != primary }
        return listOf(primary, secondary)
    }

    fun otherDisplay(activeDisplayId: Int, displayIds: List<Int>, preferredAppDisplays: Set<Int> = emptySet()): Int? {
        val pair = pickSwapDisplays(displayIds, preferredAppDisplays + activeDisplayId)
        if (pair.size >= 2) {
            return pair.firstOrNull { it != activeDisplayId } ?: pair[1]
        }
        return displayIds.firstOrNull { it != activeDisplayId }
    }

    /**
     * Merge dumpsys + accessibility maps for the given swap displays.
     * Accessibility wins on conflicts — dumpsys underlays on an "empty" screen must not
     * override what the user actually sees (especially the bottom display on Thor).
     */
    fun mergeDisplayApps(
        fromDumpsys: Map<Int, String>,
        fromAccessibility: Map<Int, String>,
        displayIds: List<Int>,
        ignoredPackages: Set<String>
    ): Map<Int, String> {
        val ids = displayIds.toSet()
        val merged = LinkedHashMap<Int, String>()
        for ((id, pkg) in fromAccessibility) {
            if (id in ids && pkg !in ignoredPackages) merged[id] = pkg
        }
        for ((id, pkg) in fromDumpsys) {
            if (id !in ids || pkg in ignoredPackages) continue
            if (!merged.containsKey(id)) merged[id] = pkg
        }
        return merged
    }

    fun occupiedOnDisplays(
        apps: Map<Int, String>,
        displayIds: List<Int>
    ): List<Pair<Int, String>> =
        displayIds.mapNotNull { id -> apps[id]?.let { id to it } }
}
