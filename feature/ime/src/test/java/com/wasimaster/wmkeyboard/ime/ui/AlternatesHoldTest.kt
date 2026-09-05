package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which alternate a held finger is choosing.
 *
 * The popup is a window of its own, so this is the one piece of arithmetic that
 * has to reconcile two coordinate spaces, and it is the piece that decides
 * whether letting go types the right character. It needs no composition: the
 * geometry arrives as plain rects from the layout passes on either side.
 *
 * The fixture is a key 40 wide and 50 tall at (100, 400), with a popup of three
 * 40-wide entries sitting directly above it.
 */
class AlternatesHoldTest {

    private val reach = 24f

    private fun hold(): AlternatesHold = AlternatesHold().apply {
        // Opened first: opening clears the measurements, and the popup reports
        // them again as it lays itself out.
        open()
        cell = Rect(100f, 400f, 140f, 450f)
        popupOffset = IntOffset(60, 330)
        gridOffset = Offset(4f, 4f)
        rects = listOf(
            Rect(0f, 0f, 40f, 40f),
            Rect(40f, 0f, 80f, 40f),
            Rect(80f, 0f, 120f, 40f),
        )
    }

    /** The popup opened this frame and has not been measured: the first entry. */
    @Test
    fun `an unmeasured popup keeps the pre-selection`() {
        val hold = AlternatesHold().apply { cell = Rect(100f, 400f, 140f, 450f) }
        assertEquals(0, hold.indexAt(Offset(20f, 25f), reach))
    }

    /** A finger inside an entry chooses it, whatever is nearby. */
    @Test
    fun `a finger on an entry chooses it`() {
        // Entry 1 spans x 104..144 in the window; the key's own left edge is 100.
        assertEquals(1, hold().indexAt(Offset(20f, -60f), reach))
    }

    /**
     * The finger that opened the popup is still on the key, well below every
     * entry. It has to be choosing the one it pre-selected, or a plain hold and
     * release would type nothing at all.
     */
    @Test
    fun `a finger resting on the key still chooses the nearest entry`() {
        assertEquals(0, hold().indexAt(Offset(2f, 25f), reach))
    }

    /** Sliding sideways under the popup walks the entries. */
    @Test
    fun `sliding under the popup follows the columns`() {
        assertEquals(2, hold().indexAt(Offset(90f, 25f), reach))
    }

    /**
     * A finger that has not gone anywhere keeps the pre-selection, whatever is
     * above it. The popup is centred over the key, so the entry the finger
     * happens to sit under is the middle one: without this, how still your hand
     * was would decide which character a plain hold typed.
     */
    @Test
    fun `the pre-selection survives a jitter`() {
        val hold = hold()
        hold.moveTo(Offset(20f, 25f), reach, steerPx = 12f)
        assertEquals(0, hold.selected.intValue)
        // Past the steering distance it follows the finger, and keeps following
        // it back again.
        hold.moveTo(Offset(90f, 25f), reach, steerPx = 12f)
        assertEquals(2, hold.selected.intValue)
        hold.moveTo(Offset(22f, 25f), reach, steerPx = 12f)
        assertEquals(1, hold.selected.intValue)
    }

    /** Far enough away is a deliberate move off, and commits nothing. */
    @Test
    fun `sliding away from the popup chooses nothing`() {
        assertEquals(-1, hold().indexAt(Offset(20f, 200f), reach))
        assertEquals(-1, hold().indexAt(Offset(-200f, 0f), reach))
    }
}
