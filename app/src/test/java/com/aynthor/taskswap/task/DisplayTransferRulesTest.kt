package com.aynthor.taskswap.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTransferRulesTest {

    @Test
    fun singleApp_onBottom_mustMoveToOtherOfPair() {
        val pair = DisplayPairing.pickSwapDisplays(listOf(0, 1, 4), setOf(1))
        val occupied = listOf(1 to "com.browser")
        val move = DisplayTransferRules.singleAppMove(occupied, pair)
        assertNotNull(move)
        assertEquals(1, move!!.fromDisplayId)
        assertEquals(0, move.toDisplayId)
        assertEquals("com.browser", move.packageName)
    }

    @Test
    fun singleApp_onVirtualBottomDisplay2_stillMoves() {
        val pair = DisplayPairing.pickSwapDisplays(listOf(0, 1, 2, 4), setOf(2))
        assertEquals(listOf(0, 2), pair)
        val move = DisplayTransferRules.singleAppMove(listOf(2 to "com.only"), pair)
        assertNotNull(move)
        assertEquals(2, move!!.fromDisplayId)
        assertEquals(0, move.toDisplayId)
    }

    @Test
    fun twoApps_noSingleMove() {
        val pair = listOf(0, 1)
        val occupied = listOf(0 to "com.a", 1 to "com.b")
        assertNull(DisplayTransferRules.singleAppMove(occupied, pair))
    }

    @Test
    fun empty_noSingleMove() {
        assertNull(DisplayTransferRules.singleAppMove(emptyList(), listOf(0, 1)))
    }

    @Test
    fun invariants_longBackIsPush_andAvoidGlobalHomeWipe() {
        assertTrue(DisplayTransferRules.longBackIsPushNotMinimize())
        assertTrue(DisplayTransferRules.shouldAvoidGlobalHomeWipe())
    }
}
