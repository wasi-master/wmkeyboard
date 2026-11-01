package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the long-press alternates popup fits the display (issue #298).
 *
 * The numbers are the reporter's: a 1080 px display, eleven columns, emoji about
 * 70 px wide and 42 px of padding a side. Each entry wanted 154 px and the grid
 * gave it 92, so every emoji drew from its left inset and spilled out of its cell
 * to the right, and the popup sat flush against the right edge.
 */
class AlternatesFitTest {

    @Test
    fun `a column count the glyphs fit is kept`() {
        assertEquals(11, fittedAlternateColumns(columns = 11, floor = 70, limit = 1016))
    }

    @Test
    fun `a column count too wide for the glyphs drops to one that fits`() {
        // A raised font size: 151 px glyphs, six of them across.
        assertEquals(6, fittedAlternateColumns(columns = 11, floor = 151, limit = 1016))
    }

    @Test
    fun `a glyph wider than the popup still gets a column`() {
        assertEquals(1, fittedAlternateColumns(columns = 5, floor = 2000, limit = 1016))
    }

    @Test
    fun `the automatic wrap is left alone`() {
        assertEquals(0, fittedAlternateColumns(columns = 0, floor = 151, limit = 1016))
    }

    @Test
    fun `padding keeps both sides while there is room`() {
        assertEquals(42, alternateSidePadding(maxWidth = 1016, content = 70, padding = 42))
    }

    @Test
    fun `padding gives way evenly to the glyph`() {
        // 92 px cell, 70 px emoji: 11 a side, the emoji centred and whole.
        assertEquals(11, alternateSidePadding(maxWidth = 92, content = 70, padding = 42))
    }

    @Test
    fun `padding goes to nothing before the glyph is squeezed`() {
        assertEquals(0, alternateSidePadding(maxWidth = 60, content = 70, padding = 42))
    }

    @Test
    fun `a popup at the right edge moves in to the margin`() {
        // 1037 px popup on 1080: the anchor's provider put it at 43, flush right.
        assertEquals(22, alternatesPopupX(x = 43, room = 43, margin = 21))
    }

    @Test
    fun `a popup at the left edge moves in to the margin`() {
        assertEquals(21, alternatesPopupX(x = 0, room = 600, margin = 21))
    }

    @Test
    fun `a popup clear of both edges stays over its key`() {
        assertEquals(300, alternatesPopupX(x = 300, room = 600, margin = 21))
    }

    @Test
    fun `a popup too wide for both margins is centred`() {
        assertEquals(15, alternatesPopupX(x = 30, room = 30, margin = 21))
        assertEquals(0, alternatesPopupX(x = 0, room = -10, margin = 21))
    }

    @Test
    fun `the popup grows at full length from the default delay up`() {
        assertEquals(140, alternatesGrowMs(300))
        assertEquals(140, alternatesGrowMs(3000))
    }

    @Test
    fun `a shorter delay shortens the grow down to its floor`() {
        assertEquals(70, alternatesGrowMs(150))
        assertEquals(50, alternatesGrowMs(100))
    }
}
