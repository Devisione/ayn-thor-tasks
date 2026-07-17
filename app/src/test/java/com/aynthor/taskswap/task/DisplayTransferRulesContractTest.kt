package com.aynthor.taskswap.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules for long-Back push and single-app move — both directions.
 */
class DisplayTransferRulesContractTest {

    @Test
    fun singleApp_fromTop_movesToBottom() {
        val move = DisplayTransferRules.singleAppMove(
            occupied = listOf(0 to "com.top"),
            pair = listOf(0, 1)
        )
        assertNotNull(move)
        assertEquals(0, move!!.fromDisplayId)
        assertEquals(1, move.toDisplayId)
        assertEquals("com.top", move.packageName)
    }

    @Test
    fun singleApp_fromBottom_movesToTop() {
        val move = DisplayTransferRules.singleAppMove(
            occupied = listOf(1 to "com.bottom"),
            pair = listOf(0, 1)
        )
        assertNotNull(move)
        assertEquals(1, move!!.fromDisplayId)
        assertEquals(0, move.toDisplayId)
        assertEquals("com.bottom", move.packageName)
    }

    @Test
    fun pushPlan_activeOnBottom_withTwoApps_movesBottomToTop_andBuriesTop() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 1,
            otherDisplayId = 0,
            apps = mapOf(0 to "com.top", 1 to "com.bottom")
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.sourceDisplayId)
        assertEquals(0, plan.targetDisplayId)
        assertEquals("com.bottom", plan.packageToMove)
        assertEquals("com.top", plan.packageToBury)
    }

    @Test
    fun pushPlan_activeOnTop_withTwoApps_movesTopToBottom_andBuriesBottom() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 0,
            otherDisplayId = 1,
            apps = mapOf(0 to "com.top", 1 to "com.bottom")
        )
        assertNotNull(plan)
        assertEquals(0, plan!!.sourceDisplayId)
        assertEquals(1, plan.targetDisplayId)
        assertEquals("com.top", plan.packageToMove)
        assertEquals("com.bottom", plan.packageToBury)
    }

    @Test
    fun pushPlan_singleAppOnBottom_activeTopEmpty_stillMovesBottomToTop() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 0,
            otherDisplayId = 1,
            apps = mapOf(1 to "com.only")
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.sourceDisplayId)
        assertEquals(0, plan.targetDisplayId)
        assertEquals("com.only", plan.packageToMove)
        assertNull(plan.packageToBury)
    }

    @Test
    fun pushPlan_singleAppOnTop_activeBottomEmpty_stillMovesTopToBottom() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 1,
            otherDisplayId = 0,
            apps = mapOf(0 to "com.only")
        )
        assertNotNull(plan)
        assertEquals(0, plan!!.sourceDisplayId)
        assertEquals(1, plan.targetDisplayId)
        assertEquals("com.only", plan.packageToMove)
        assertNull(plan.packageToBury)
    }

    @Test
    fun pushPlan_visibleSingleOnBottom_beatsUnderlayOnActiveTop() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 0,
            otherDisplayId = 1,
            apps = mapOf(0 to "com.underlay", 1 to "com.bottom"),
            visibleApps = mapOf(1 to "com.bottom")
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.sourceDisplayId)
        assertEquals(0, plan.targetDisplayId)
        assertEquals("com.bottom", plan.packageToMove)
        assertNull(plan.packageToBury)
    }

    @Test
    fun pushPlan_preferredSettingsOnBottom_beatsSoleVisibleYoutubeOnTop() {
        // a11y missed Settings on the active bottom; only YouTube on top is visible.
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 1,
            otherDisplayId = 0,
            apps = mapOf(0 to "com.google.android.youtube", 1 to "com.android.settings"),
            visibleApps = mapOf(0 to "com.google.android.youtube"),
            preferredPackage = "com.android.settings"
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.sourceDisplayId)
        assertEquals(0, plan.targetDisplayId)
        assertEquals("com.android.settings", plan.packageToMove)
        assertEquals("com.google.android.youtube", plan.packageToBury)
    }

    @Test
    fun resolvePushActor_lastInteractBottom_beatsFocusTop() {
        val actor = DisplayTransferRules.resolvePushActor(
            pair = listOf(0, 1),
            apps = mapOf(0 to "com.google.android.youtube", 1 to "com.android.settings"),
            lastInteractedDisplayId = 1,
            lastInteractedPackage = "com.android.settings",
            focusedDisplayId = 0,
            focusedPackage = "com.google.android.youtube"
        )
        assertNotNull(actor)
        assertEquals(1, actor!!.displayId)
        assertEquals("com.android.settings", actor.packageName)
    }

    @Test
    fun resolvePushActor_recentActiveBottom_beatsFocusTop_whenNoLastInteract() {
        val actor = DisplayTransferRules.resolvePushActor(
            pair = listOf(0, 1),
            apps = mapOf(0 to "com.google.android.youtube", 1 to "com.android.settings"),
            recentActiveDisplayId = 1,
            recentActivePackage = "com.android.settings",
            focusedDisplayId = 0,
            focusedPackage = "com.google.android.youtube"
        )
        assertNotNull(actor)
        assertEquals(1, actor!!.displayId)
        assertEquals("com.android.settings", actor.packageName)
    }

    @Test
    fun pushPlan_visibleOnActive_beatsSoleVisibleOnOther() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 1,
            otherDisplayId = 0,
            apps = mapOf(0 to "com.google.android.youtube", 1 to "com.android.settings"),
            visibleApps = mapOf(
                0 to "com.google.android.youtube",
                1 to "com.android.settings"
            )
        )
        assertNotNull(plan)
        assertEquals("com.android.settings", plan!!.packageToMove)
        assertEquals(1, plan.sourceDisplayId)
        assertEquals(0, plan.targetDisplayId)
    }

    @Test
    fun resolveTransferDestination_trustsA11ySourceOverWrongDumpsys() {
        // a11y: app on bottom (1); dumpsys wrongly says display 0
        assertEquals(
            0,
            DisplayTransferRules.resolveTransferDestination(
                preferredSourceDisplayId = 1,
                taskDisplayId = 0,
                pair = listOf(0, 1)
            )
        )
    }

    @Test
    fun resolveTransferDestination_usesTaskWhenA11ySourceNotInPair() {
        assertEquals(
            0,
            DisplayTransferRules.resolveTransferDestination(
                preferredSourceDisplayId = 9,
                taskDisplayId = 1,
                pair = listOf(0, 1)
            )
        )
    }

    @Test
    fun pushPlan_isNeverSwapOfBothTasks() {
        val plan = DisplayTransferRules.planPush(
            activeDisplayId = 1,
            otherDisplayId = 0,
            apps = mapOf(0 to "com.a", 1 to "com.b")
        )
        assertNotNull(plan)
        // Exactly one package moves; the other is only buried — not a second move.
        assertTrue(plan!!.packageToMove == "com.b")
        assertEquals("com.a", plan.packageToBury)
        assertTrue(plan.packageToMove != plan.packageToBury)
    }
}
