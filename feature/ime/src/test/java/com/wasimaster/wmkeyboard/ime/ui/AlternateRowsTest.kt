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
}
