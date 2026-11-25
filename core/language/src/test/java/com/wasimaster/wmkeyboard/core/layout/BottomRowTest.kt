package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bottom row settings as one pass the keyboard and the layout editor share
 * (issue #420): what the row looks like, which settings did it, where each drawn
 * key is stored, and which keys the editor has to treat as the settings' work.
 */
class BottomRowTest {

    private val symbols = Key("?123", action = KeyAction.Symbols, width = 1.5f)
    private val comma = Key(",", role = KeyRole.Comma, longPress = listOf("!", "?"))
    private val globe = Key("🌐", action = KeyAction.LanguageSwitch)
    private val space = Key(" ", action = KeyAction.Space, width = 4f)
    private val period = Key(".", role = KeyRole.Period)
    private val enter = Key("⏎", action = KeyAction.Enter, width = 1.5f)

    /** A grid of one letter row over [bottom]. */
    private fun grid(vararg bottom: Key, laidOut: Boolean = false) = KeyboardLayout(
        name = "t",
        rows = listOf(List(10) { Key("q") }, bottom.toList()),
        bottomRowAsLaidOut = laidOut,
    )

    /** The built-in bottom row, `?123 , 🌐 ␣ . ⏎`. */
    private fun builtIn(laidOut: Boolean = false) = grid(symbols, comma, globe, space, period, enter, laidOut = laidOut)

    /** What the settings ship as. */
    private val defaults = BottomRowRules(globeInOnePlace = true, swapCommaAndGlobe = true, globeAsEmoji = true)

    private fun ArrangedGrid.bottom() = layout.rows.last()

    @Test
    fun `no rules hand the grid back as it is`() {
        val layout = builtIn()
        val arranged = layout.arrangedBy(BottomRowRules())
        assertSame(layout, arranged.layout)
        assertTrue(arranged.applied.isEmpty())
        assertFalse(arranged.rowChanged(1))
    }

    /**
     * The row the issue reported: stored as `?123 , 🌐 ␣`, drawn as
     * `?123 😊 , ␣`. On the built-in row the placement is exactly the swap, so
     * it is the swap that is named, not the placement.
     */
    @Test
    fun `the shipped settings draw the emoji key before the comma`() {
        val arranged = builtIn().arrangedBy(defaults)
        val row = arranged.bottom()
        assertEquals(listOf("?123", "🌐", ",", " ", ".", "⏎"), row.map { it.label })
        assertEquals(KeyAction.Emoji, row[1].action)
        assertEquals(listOf(1.5f, 1f, 1f, 4f, 1f, 1.5f), row.map { it.width })
        assertEquals(
            setOf(BottomRowRule.SWAP_COMMA_AND_GLOBE, BottomRowRule.GLOBE_AS_EMOJI),
            arranged.applied,
        )
    }

    @Test
    fun `each drawn key knows where it is stored`() {
        val arranged = builtIn().arrangedBy(defaults)
        assertEquals(listOf(0, 2, 1, 3, 4, 5), (0..5).map { arranged.sourceColumn(1, it) })
        // The letter row is untouched.
        assertEquals(3, arranged.sourceColumn(0, 3))
        assertTrue(arranged.rowChanged(1))
        assertFalse(arranged.rowChanged(0))
    }

    /** The keys the settings moved or changed, and only those. */
    @Test
    fun `only the keys the settings touched count as rewritten`() {
        val arranged = builtIn().arrangedBy(defaults)
        assertTrue("the emoji key", arranged.rewritten(1, 1))
        assertTrue("the comma", arranged.rewritten(1, 2))
        for (col in listOf(0, 3, 4, 5)) assertFalse("column $col", arranged.rewritten(1, col))
        assertFalse(arranged.rewritten(0, 0))
    }

    /**
     * A key that only shifted along because the 🌐 key before it went is still
     * itself, and is edited where it is stored; the spacebar that took the width
     * is the settings' work.
     */
    @Test
    fun `hiding the globe shifts the keys after it without rewriting them`() {
        val arranged = builtIn().arrangedBy(BottomRowRules(hideGlobe = true))
        val row = arranged.bottom()
        assertEquals(listOf("?123", ",", " ", ".", "⏎"), row.map { it.label })
        assertEquals(5f, row[2].width)
        assertEquals(setOf(BottomRowRule.HIDE_GLOBE), arranged.applied)
        assertEquals(4, arranged.sourceColumn(1, 3))
        assertFalse("the full stop", arranged.rewritten(1, 3))
        assertTrue("the spacebar", arranged.rewritten(1, 2))
    }

    @Test
    fun `a layer laid out by hand is left alone by every rule but the hidden globe`() {
        val layout = builtIn(laidOut = true)
        val every = BottomRowRules(
            globeInOnePlace = true,
            swapCommaAndGlobe = true,
            globeAsEmoji = true,
            commaAsEmoji = true,
        )
        val arranged = layout.arrangedBy(every)
        assertSame(layout, arranged.layout)
        assertTrue(arranged.applied.isEmpty())
    }

    /** A 12-key pad's columns are the layout, so the hidden 🌐 key's seat stays and opens emoji (Telegram 11205). */
    @Test
    fun `a laid-out row's hidden globe becomes the emoji key in its own seat`() {
        val layout = builtIn(laidOut = true)
        val arranged = layout.arrangedBy(BottomRowRules(hideGlobe = true, globeInOnePlace = true, swapCommaAndGlobe = true))
        val row = arranged.bottom()
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Text, KeyAction.Emoji, KeyAction.Space, KeyAction.Text, KeyAction.Enter),
            row.map { it.action },
        )
        assertEquals(layout.rows.last().map { it.width }, row.map { it.width })
        assertEquals(setOf(BottomRowRule.HIDE_GLOBE), arranged.applied)
        assertEquals(2, arranged.sourceColumn(1, 2))
        assertTrue("the emoji key", arranged.rewritten(1, 2))
        assertFalse("the spacebar", arranged.rewritten(1, 3))
        assertSame("the letters", layout.rows.first(), arranged.layout.rows.first())
    }

    @Test
    fun `a laid-out row with its own emoji key gets a comma in the globe's seat`() {
        val emoji = Key("☺", action = KeyAction.Emoji)
        val layout = grid(symbols, emoji, globe, space, period, enter, laidOut = true)
        val row = layout.arrangedBy(BottomRowRules(hideGlobe = true)).bottom()
        assertEquals(KeyRole.Comma, row[2].role)
        assertEquals(",", row[2].label)
        assertEquals(1, row.count { it.action == KeyAction.Emoji })
    }

    @Test
    fun `a laid-out row with no globe is left alone`() {
        val layout = grid(symbols, comma, space, period, enter, laidOut = true)
        val arranged = layout.arrangedBy(BottomRowRules(hideGlobe = true))
        assertSame(layout, arranged.layout)
        assertTrue(arranged.applied.isEmpty())
    }

    /** The comma keeps its character, first on the hold. */
    @Test
    fun `the comma as the emoji key keeps the comma on its hold`() {
        val arranged = builtIn().arrangedBy(BottomRowRules(commaAsEmoji = true))
        val key = arranged.bottom()[1]
        assertEquals(KeyAction.Emoji, key.action)
        assertEquals(listOf(",", "!", "?"), key.longPress)
        assertEquals(setOf(BottomRowRule.COMMA_AS_EMOJI), arranged.applied)
    }

    /** A Keyman-style row with the 🌐 key at the far left is what the placement is for. */
    @Test
    fun `a globe out of place is named as the placement`() {
        val arranged = grid(globe, symbols, comma, space, period, enter).arrangedBy(BottomRowRules(globeInOnePlace = true))
        assertTrue(BottomRowRule.GLOBE_IN_ONE_PLACE in arranged.applied)
        val row = arranged.bottom()
        assertEquals(listOf("?123", ",", "🌐", " ", ".", "⏎"), row.map { it.label })
        assertTrue(arranged.rewritten(1, 2))
    }

    /** The swap and the placement agree, so the order is that of the swap alone. */
    @Test
    fun `the placement under the swap is not swapped a second time`() {
        val placed = builtIn().arrangedBy(BottomRowRules(globeInOnePlace = true, swapCommaAndGlobe = true))
        val swapped = builtIn().arrangedBy(BottomRowRules(swapCommaAndGlobe = true))
        assertEquals(swapped.bottom(), placed.bottom())
    }
}
