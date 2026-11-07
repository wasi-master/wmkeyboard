package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The row packing behind the long-press alternates popup (issue #64).
 *
 * The popup itself needs a composition to test, but the part with the arithmetic
 * in it does not: [alternateRowSizes] is a function of the measured widths, and
 * the two modes it has to get right are the wrap that shipped and the fixed
 * column grid that replaced it when a count is set.
 */
class AlternateRowsTest {

    @Test
    fun `auto mode fills a row until the next entry would not fit`() {
        val widths = List(5) { 40 }
        assertEquals(listOf(3, 2), alternateRowSizes(widths, columns = 0, limit = 130))
    }

    @Test
    fun `auto mode keeps one row while everything fits`() {
        val widths = List(4) { 30 }
        assertEquals(listOf(4), alternateRowSizes(widths, columns = 0, limit = 500))
    }

    @Test
    fun `auto mode gives an entry wider than the popup a row of its own`() {
        // Not an empty row above it, which is what a naive wrap produces: the
        // first entry never fits either, so it has to be placed anyway.
        assertEquals(listOf(1, 1, 1), alternateRowSizes(listOf(80, 90, 70), columns = 0, limit = 50))
    }

    @Test
    fun `fixed columns ignore the widths`() {
        val widths = listOf(10, 200, 10, 200, 10, 200, 10)
        assertEquals(listOf(3, 3, 1), alternateRowSizes(widths, columns = 3, limit = 100))
    }

    @Test
    fun `fixed columns leave the remainder on the last row`() {
        val widths = List(14) { 20 }
        assertEquals(listOf(6, 6, 2), alternateRowSizes(widths, columns = 6, limit = 600))
    }

    @Test
    fun `fixed columns hold one row while there are fewer entries than columns`() {
        assertEquals(listOf(2), alternateRowSizes(listOf(20, 20), columns = 6, limit = 600))
    }

    @Test
    fun `no entries is no rows`() {
        assertEquals(emptyList<Int>(), alternateRowSizes(emptyList(), columns = 0, limit = 100))
        assertEquals(emptyList<Int>(), alternateRowSizes(emptyList(), columns = 5, limit = 100))
    }

    /** #1169: the count is a most, and the rows it calls for share the entries evenly. */
    @Test
    fun `balanced columns share the entries evenly between the rows`() {
        val five = List(5) { 20 }
        assertEquals(listOf(3, 2), alternateRowSizes(five, columns = 4, limit = 600, balanced = true))
        assertEquals(listOf(3, 3), alternateRowSizes(List(6) { 20 }, columns = 4, limit = 600, balanced = true))
        assertEquals(listOf(4), alternateRowSizes(List(4) { 20 }, columns = 4, limit = 600, balanced = true))
        assertEquals(listOf(3, 2, 2), alternateRowSizes(List(7) { 20 }, columns = 3, limit = 600, balanced = true))
        // Off, the shipped fill: 4 then a lone 1.
        assertEquals(listOf(4, 1), alternateRowSizes(five, columns = 4, limit = 600))
    }

    @Test
    fun `the editor preview walks the same rows`() {
        assertEquals(listOf(3, 2), fixedAlternateRowSizes(5, columns = 4, balanced = true))
        assertEquals(listOf(4, 1), fixedAlternateRowSizes(5, columns = 4, balanced = false))
        assertEquals(emptyList<Int>(), fixedAlternateRowSizes(0, columns = 4, balanced = true))
    }

    @Test
    fun `balanced auto evens out the rows the width made`() {
        // Ten across fit in 200, so eleven wrap to 10 + 1; evened, 6 + 5.
        val widths = List(11) { 20 }
        assertEquals(listOf(10, 1), alternateRowSizes(widths, columns = 0, limit = 200))
        assertEquals(listOf(6, 5), alternateRowSizes(widths, columns = 0, limit = 200, balanced = true))
    }

    @Test
    fun `balanced auto keeps the wrap when an evened row would not fit`() {
        // 60 + 60 + 60 fit in 190 but a wide entry pulled into the first row would not.
        val widths = listOf(60, 60, 60, 150)
        assertEquals(listOf(3, 1), alternateRowSizes(widths, columns = 0, limit = 190))
        assertEquals(listOf(3, 1), alternateRowSizes(widths, columns = 0, limit = 190, balanced = true))
    }

    /** Issue #585: the first in the middle, then right, left, right, outward. */
    @Test
    fun `a centred row spreads out from its first entry`() {
        // 1 2 3 4 5 6 reads 5 3 1 2 4 6.
        assertEquals(listOf(4, 2, 0, 1, 3, 5), centredAlternateOrder(6).toList())
        // 1 2 3 reads 3 1 2.
        assertEquals(listOf(2, 0, 1), centredAlternateOrder(3).toList())
        assertEquals(listOf(0, 1), centredAlternateOrder(2).toList())
        assertEquals(listOf(0), centredAlternateOrder(1).toList())
    }

    @Test
    fun `every centred slot is used exactly once`() {
        for (size in 1..12) {
            val slots = (0 until size).map { centredAlternateSlot(it, size) }
            assertEquals((0 until size).toList(), slots.sorted())
        }
    }
}
