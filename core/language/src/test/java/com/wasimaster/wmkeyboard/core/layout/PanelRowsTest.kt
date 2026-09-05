package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a panel layout's rows share the key area: fixed key rows keep their
 * height, the rows holding a component take what is left.
 */
class PanelRowsTest {

    private fun field(kind: PanelFieldKind = PanelFieldKind.EMOJI_GRID, span: Int = 1) =
        Key("", action = KeyAction.Field(kind), width = 10f, rowSpan = span)

    private val keyRow = listOf(Key("a"), Key("b"))

    @Test
    fun `a row holding a component is a flex row and a key row is not`() {
        val flex = panelFlexRows(listOf(listOf(field()), keyRow))
        assertArrayEquals(booleanArrayOf(true, false), flex)
    }

    @Test
    fun `a spanning component marks only the row it starts in`() {
        val rows = listOf(listOf(field(span = 3)), keyRow, keyRow, keyRow)
        assertArrayEquals(booleanArrayOf(true, false, false, false), panelFlexRows(rows))
    }

    @Test
    fun `fixed rows keep their pitch and the flex row gets the remainder`() {
        val tops = panelRowTops(
            fixedPx = intArrayOf(60, 60, 60),
            weights = floatArrayOf(1f, 1f, 1f),
            flex = booleanArrayOf(false, true, false),
            totalPx = 300,
        )
        assertArrayEquals(intArrayOf(0, 60, 240, 300), tops)
    }

    @Test
    fun `two flex rows split the remainder by weight`() {
        val tops = panelRowTops(
            fixedPx = intArrayOf(50, 50, 50),
            weights = floatArrayOf(1f, 3f, 1f),
            flex = booleanArrayOf(true, true, false),
            totalPx = 250,
        )
        // 200 spare, split 1:3 → 50 and 150; then the fixed 50.
        assertArrayEquals(intArrayOf(0, 50, 200, 250), tops)
    }

    @Test
    fun `with no flex row every row shares the height by weight`() {
        // The text-editing pad: all keys, no component.
        val tops = panelRowTops(
            fixedPx = intArrayOf(60, 60, 60, 60),
            weights = floatArrayOf(1f, 1f, 1f, 1f),
            flex = booleanArrayOf(false, false, false, false),
            totalPx = 200,
        )
        assertArrayEquals(intArrayOf(0, 50, 100, 150, 200), tops)
    }

    @Test
    fun `fixed rows that overrun the height are scaled together and still sum to it`() {
        val tops = panelRowTops(
            fixedPx = intArrayOf(100, 100, 100),
            weights = floatArrayOf(1f, 1f, 1f),
            flex = booleanArrayOf(false, true, false),
            totalPx = 100,
        )
        assertEquals(100, tops.last())
        // The flex row has nothing left and collapses to zero height.
        assertEquals(tops[1], tops[2])
        assertEquals(50, tops[1])
    }

    @Test
    fun `tops are monotonic and land exactly on the total for an odd height`() {
        val tops = panelRowTops(
            fixedPx = intArrayOf(33, 33, 33, 33),
            weights = floatArrayOf(1f, 1f, 1f, 1f),
            flex = booleanArrayOf(false, false, false, false),
            totalPx = 101,
        )
        assertEquals(101, tops.last())
        for (i in 1 until tops.size) assertTrue(tops[i] >= tops[i - 1])
        // No seam: every row's bottom is the next row's top, and the sum of
        // the heights is the total.
        assertEquals(101, (1 until tops.size).sumOf { tops[it] - tops[it - 1] })
    }

    @Test
    fun `an empty grid has one top at zero`() {
        assertArrayEquals(intArrayOf(0), panelRowTops(IntArray(0), FloatArray(0), BooleanArray(0), 100))
    }

    @Test
    fun `every shipped panel with a component leaves the component real height`() {
        // At a typical 48 dp key with 8 dp of gaps, four rows tall: 224.
        val total = 224
        for ((kind, spec) in BuiltInPanelLayouts.byKind) {
            val rows = spec.grid.rows
            val flex = panelFlexRows(rows)
            val weights = FloatArray(rows.size) { spec.grid.rowHeights?.getOrNull(it) ?: 1f }
            val fixed = IntArray(rows.size) { 56 }
            val tops = panelRowTops(fixed, weights, flex, total)
            assertEquals(total, tops.last())
            if (kind.requiredField != null) {
                val flexHeight = (0 until rows.size).filter { flex[it] }.sumOf { tops[it + 1] - tops[it] }
                assertTrue("$kind gives its components $flexHeight px", flexHeight >= total / 2)
            } else {
                assertFalse("$kind has no component row", flex.any { it })
            }
        }
    }
}
