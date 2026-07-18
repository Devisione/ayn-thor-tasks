package com.aynthor.taskswap.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPairingTest {

    @Test
    fun pickSwapDisplays_prefersZeroAndNext() {
        assertEquals(listOf(0, 2), DisplayPairing.pickSwapDisplays(listOf(0, 1, 2, 4), setOf(2)))
        assertEquals(listOf(0, 1), DisplayPairing.pickSwapDisplays(listOf(0, 1, 2, 4)))
        assertEquals(listOf(0, 2), DisplayPairing.pickSwapDisplays(listOf(2, 0, 5)))
        assertEquals(listOf(1, 3), DisplayPairing.pickSwapDisplays(listOf(3, 1)))
    }

    @Test
    fun displaysForMinimize_includesPairAndEveryAppDisplay() {
        // Pair alone is [0,1] (first peer), but app on 2 must also receive Home.
        assertEquals(
            listOf(0, 1, 2),
            DisplayPairing.displaysForMinimize(listOf(0, 1, 2, 4), setOf(1, 2))
        )
        assertEquals(
            listOf(0, 2),
            DisplayPairing.displaysForMinimize(listOf(0, 1, 2, 4), setOf(2))
        )
        assertEquals(
            listOf(0, 1),
            DisplayPairing.displaysForMinimize(listOf(0, 1, 2), emptySet())
        )
    }

    @Test
    fun merge_includesAccessibilityWhenDumpsysEmpty_forSingleApp() {
        val merged = DisplayPairing.mergeDisplayApps(
            fromDumpsys = emptyMap(),
            fromAccessibility = mapOf(1 to "com.example.browser"),
            displayIds = listOf(0, 1),
            ignoredPackages = emptySet()
        )
        assertEquals(mapOf(1 to "com.example.browser"), merged)
        val occupied = DisplayPairing.occupiedOnDisplays(merged, listOf(0, 1))
        assertEquals(1, occupied.size)
        assertEquals(1 to "com.example.browser", occupied.first())
    }

    @Test
    fun merge_prefersAccessibilityOverDumpsys_onConflict() {
        val merged = DisplayPairing.mergeDisplayApps(
            fromDumpsys = mapOf(0 to "com.underlay", 1 to "com.old"),
            fromAccessibility = mapOf(1 to "com.bar"),
            displayIds = listOf(0, 1),
            ignoredPackages = emptySet()
        )
        assertEquals("com.underlay", merged[0]) // dumpsys fills gap
        assertEquals("com.bar", merged[1]) // a11y wins
    }

    @Test
    fun merge_fillsMissingPrimaryFromDumpsys() {
        val merged = DisplayPairing.mergeDisplayApps(
            fromDumpsys = mapOf(0 to "com.foo"),
            fromAccessibility = mapOf(1 to "com.bar"),
            displayIds = listOf(0, 1),
            ignoredPackages = emptySet()
        )
        assertEquals("com.foo", merged[0])
        assertEquals("com.bar", merged[1])
    }

    @Test
    fun otherDisplay_returnsPeerForActive() {
        assertEquals(1, DisplayPairing.otherDisplay(0, listOf(0, 1, 4)))
        assertEquals(0, DisplayPairing.otherDisplay(1, listOf(0, 1, 4)))
    }

    @Test
    fun singleOccupied_targetIsOtherOfPair() {
        val pair = DisplayPairing.pickSwapDisplays(listOf(0, 1, 7))
        val occupied = DisplayPairing.occupiedOnDisplays(
            mapOf(1 to "com.only"),
            pair
        )
        assertEquals(1, occupied.size)
        val from = occupied.first().first
        val to = pair.first { it != from }
        assertEquals(0, to)
        assertTrue(from == 1)
    }
}
