package com.aynthor.taskswap.task

/**
 * Pure decision helpers for swap / single-app transfer.
 * Keeps DisplaySwapper behaviour covered by unit tests without ADB.
 */
object DisplayTransferRules {

    data class SingleAppMove(
        val fromDisplayId: Int,
        val toDisplayId: Int,
        val packageName: String
    )

    data class PushPlan(
        val sourceDisplayId: Int,
        val targetDisplayId: Int,
        val packageToMove: String,
        val packageToBury: String?
    )

    /**
     * If exactly one app occupies the swap pair, it must move to the other display.
     * Never return null when occupied.size == 1 and pair has 2 displays.
     */
    fun singleAppMove(
        occupied: List<Pair<Int, String>>,
        pair: List<Int>
    ): SingleAppMove? {
        if (pair.size < 2 || occupied.size != 1) return null
        val (from, pkg) = occupied.first()
        val to = pair.firstOrNull { it != from } ?: return null
        return SingleAppMove(fromDisplayId = from, toDisplayId = to, packageName = pkg)
    }

    data class PushActor(
        val displayId: Int,
        val packageName: String
    )

    /**
     * Pick which app long-Back should push.
     *
     * On Thor, accessibility/key focus often sticks to the top display while the user is
     * actually using the bottom screen — so [lastInteracted*] must beat [focused*].
     * [recentActive*] (dumpsys lastActiveTime) sits between them.
     */
    fun resolvePushActor(
        pair: List<Int>,
        apps: Map<Int, String>,
        lastInteractedDisplayId: Int? = null,
        lastInteractedPackage: String? = null,
        recentActiveDisplayId: Int? = null,
        recentActivePackage: String? = null,
        focusedDisplayId: Int? = null,
        focusedPackage: String? = null
    ): PushActor? {
        if (pair.size < 2) return null

        fun actorOn(displayId: Int?, packageName: String?, allowMapFallback: Boolean): PushActor? {
            if (displayId == null || displayId !in pair) return null
            val fromArg = packageName?.takeIf { it.isNotBlank() }
            val fromMap = if (allowMapFallback) apps[displayId] else null
            val pkg = fromArg ?: fromMap ?: return null
            return PushActor(displayId, pkg)
        }

        // lastInteract / recentActive may omit package and still resolve via apps map.
        actorOn(lastInteractedDisplayId, lastInteractedPackage, allowMapFallback = true)?.let { return it }
        actorOn(recentActiveDisplayId, recentActivePackage, allowMapFallback = true)?.let { return it }
        // Focus must carry an explicit package — otherwise apps[focusDisplay] is often a dumpsys underlay.
        actorOn(focusedDisplayId, focusedPackage, allowMapFallback = false)?.let { return it }
        return null
    }

    /**
     * Long-Back: move the active app to the other screen; bury the other app if present.
     * If the active screen is empty/launcher but exactly one app occupies the pair,
     * push that app (so bottom→top still works when focus stayed on top).
     *
     * Priority:
     * 1. [preferredPackage] when known (last user interaction / resolved actor — NOT raw
     *    key-focus, which on Thor often stays on the top YouTube while Settings is used below).
     * 2. App visible on the active display.
     * 3. Sole a11y-visible app on the pair (beats dumpsys underlay on an empty active top).
     * 4. App from merged dumpsys/a11y map on the active display.
     * 5. Sole app in [apps].
     */
    fun planPush(
        activeDisplayId: Int,
        otherDisplayId: Int,
        apps: Map<Int, String>,
        visibleApps: Map<Int, String> = emptyMap(),
        preferredPackage: String? = null,
        /** @deprecated use [preferredPackage] */
        focusedPackage: String? = null
    ): PushPlan? {
        val pairIds = listOf(activeDisplayId, otherDisplayId)
        val visibleOccupied = pairIds.mapNotNull { id -> visibleApps[id]?.let { id to it } }
        val preferred = preferredPackage ?: focusedPackage

        if (preferred != null) {
            val sourceDisplayId = when {
                visibleApps[activeDisplayId] == preferred -> activeDisplayId
                apps[activeDisplayId] == preferred -> activeDisplayId
                else -> visibleApps.entries.firstOrNull { it.value == preferred }?.key
                    ?: apps.entries.firstOrNull { it.value == preferred }?.key
                    ?: activeDisplayId
            }
            val targetDisplayId = pairIds.firstOrNull { it != sourceDisplayId } ?: otherDisplayId
            return PushPlan(
                sourceDisplayId = sourceDisplayId,
                targetDisplayId = targetDisplayId,
                packageToMove = preferred,
                packageToBury = (visibleApps[targetDisplayId] ?: apps[targetDisplayId])
                    ?.takeIf { it != preferred }
            )
        }

        visibleApps[activeDisplayId]?.let { activeVisible ->
            return PushPlan(
                sourceDisplayId = activeDisplayId,
                targetDisplayId = otherDisplayId,
                packageToMove = activeVisible,
                packageToBury = (visibleApps[otherDisplayId] ?: apps[otherDisplayId])
                    ?.takeIf { it != activeVisible }
            )
        }

        // Active has no a11y-visible app: sole visible wins over dumpsys underlay on active.
        if (visibleOccupied.size == 1) {
            val move = singleAppMove(visibleOccupied, pairIds) ?: return null
            return PushPlan(
                sourceDisplayId = move.fromDisplayId,
                targetDisplayId = move.toDisplayId,
                packageToMove = move.packageName,
                packageToBury = null
            )
        }

        val activePkg = apps[activeDisplayId]
        if (activePkg != null) {
            return PushPlan(
                sourceDisplayId = activeDisplayId,
                targetDisplayId = otherDisplayId,
                packageToMove = activePkg,
                packageToBury = apps[otherDisplayId]?.takeIf { it != activePkg }
            )
        }
        val occupied = pairIds.mapNotNull { id -> apps[id]?.let { id to it } }
        if (occupied.size == 1) {
            val (from, pkg) = occupied.first()
            val to = pairIds.first { it != from }
            return PushPlan(
                sourceDisplayId = from,
                targetDisplayId = to,
                packageToMove = pkg,
                packageToBury = null
            )
        }
        return null
    }

    /**
     * Destination for a single-app move.
     * Prefer accessibility source when known — dumpsys displayId is often wrong on Thor
     * (bottom app listed under display 0), and flipping against a11y made bottom→top worse.
     */
    fun resolveTransferDestination(
        preferredSourceDisplayId: Int,
        taskDisplayId: Int,
        pair: List<Int>
    ): Int {
        if (pair.size < 2) return pair.firstOrNull() ?: preferredSourceDisplayId
        val source = when {
            preferredSourceDisplayId in pair -> preferredSourceDisplayId
            taskDisplayId in pair -> taskDisplayId
            else -> preferredSourceDisplayId
        }
        return pair.firstOrNull { it != source } ?: pair[1]
    }

    fun shouldAvoidGlobalHomeWipe(): Boolean = true

    fun longBackIsPushNotMinimize(): Boolean = true
}
