package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.ui.inlineChipUncoveredSlice
import com.wasimaster.wmkeyboard.ime.ui.inlineChipVisibleSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inline-suggestion chips are remote surfaces: they are not clipped by their
 * parents and they keep their full width however little of them is on screen,
 * so the row has to hand each one the slice of itself that is actually inside
 * the viewport. Get this wrong and a chip paints over the strip's own
 * chevron, emoji key and dismiss cross (#250) — or disappears entirely.
 *
 * The geometry here is the one measured on the emulator: a 782px viewport
 * holding 712px chips laid out 724px apart.
 */
class InlineChipClipTest {

    private val viewport = 782
    private val chipWidth = 712
    private val pitch = 724

    private fun chipLeft(index: Int) = 6 + index * pitch

    @Test
    fun `chip fully inside the viewport is not clipped`() {
        val slice = inlineChipVisibleSlice(chipLeft(0), chipWidth, scrollX = 0, viewportWidth = viewport)
        assertEquals(0 until chipWidth, slice)
    }

    @Test
    fun `chip running off the right edge is clipped to the viewport`() {
        // Chip 1 starts at 730 with the row unscrolled, so only the first
        // 782 - 730 = 52px of it may paint. The rest would land on the ✕.
        val slice = inlineChipVisibleSlice(chipLeft(1), chipWidth, scrollX = 0, viewportWidth = viewport)
        assertEquals(0 until 52, slice)
    }

    @Test
    fun `chip scrolled off the left edge keeps only its tail`() {
        // Scrolled 463: chip 0 spans 6..718 in row coordinates, so everything
        // before 463 is behind the chevron and the emoji key.
        val slice = inlineChipVisibleSlice(chipLeft(0), chipWidth, scrollX = 463, viewportWidth = viewport)
        assertEquals(457 until chipWidth, slice)
    }

    @Test
    fun `chip scrolled clean out is empty rather than drawn`() {
        val slice = inlineChipVisibleSlice(chipLeft(2), chipWidth, scrollX = 0, viewportWidth = viewport)
        assertTrue(slice.isEmpty())
    }

    @Test
    fun `a chip never reports a slice outside its own bounds`() {
        for (index in 0..5) {
            for (scrollX in 0..(pitch * 6) step 37) {
                val slice = inlineChipVisibleSlice(chipLeft(index), chipWidth, scrollX, viewport)
                if (slice.isEmpty()) continue
                assertTrue("left below 0 at $index/$scrollX", slice.first >= 0)
                assertTrue("right past the chip at $index/$scrollX", slice.last < chipWidth)
                assertTrue(
                    "slice wider than the viewport at $index/$scrollX",
                    slice.last + 1 - slice.first <= viewport,
                )
            }
        }
    }

    @Test
    fun `a zero-width viewport shows nothing`() {
        val slice = inlineChipVisibleSlice(chipLeft(0), chipWidth, scrollX = 0, viewportWidth = 0)
        assertTrue(slice.isEmpty())
    }

    // A key's preview bubble over a chip (#1090): the chip draws above the
    // keyboard's window, so it has to give the bubble's columns up itself.

    @Test
    fun `a bubble beside the chip leaves it whole`() {
        assertEquals(0 until 300, inlineChipUncoveredSlice(0 until 300, 400..480))
    }

    @Test
    fun `a bubble over the middle keeps the wider side`() {
        assertEquals(0 until 200, inlineChipUncoveredSlice(0 until 300, 200..260))
        assertEquals(101 until 300, inlineChipUncoveredSlice(0 until 300, 20..100))
    }

    @Test
    fun `a bubble wider than the chip hides it`() {
        assertTrue(inlineChipUncoveredSlice(0 until 300, -10..310).isEmpty())
    }
}
