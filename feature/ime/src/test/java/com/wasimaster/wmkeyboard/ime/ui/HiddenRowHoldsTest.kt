package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.ime.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HiddenRowHoldsTest {

    private val fill = BuiltInLayouts.SYMBOLS_FILL_ROW
    private val arrows = BuiltInLayouts.defaultNumberRow(LayoutLayer.SYMBOLS_SHIFTED)

    @Test
    fun `number row off puts the fill row first on the digits' holds (#1560)`() {
        val rows = BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS).rows
        val drawn = symbolBodyRows(rows, LayoutMode.SYMBOLS, false, false, fill, arrows)
        val digits = drawn.first()
        assertEquals("1", digits[0].label)
        assertEquals(listOf("=", "!", "¹", "₁"), digits[0].longPress)
        assertEquals("<", digits[2].longPress.first())
        assertEquals(">", digits[3].longPress.first())
        assertEquals("~", digits[9].longPress.first())
        // The rest of the page is untouched.
        assertEquals(rows.drop(1), drawn.drop(1))
    }

    @Test
    fun `number row on still swaps the digit row for the fill row`() {
        val rows = BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS).rows
        val drawn = symbolBodyRows(rows, LayoutMode.SYMBOLS, true, true, fill, arrows)
        assertEquals(fill, drawn.first())
    }

    @Test
    fun `the second page holds the arrow row after its own holds`() {
        val rows = BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS_SHIFTED).rows
        val drawn = symbolBodyRows(rows, LayoutMode.SYMBOLS_SHIFTED, false, false, fill, arrows)
        val top = drawn.first()
        // ~ had no holds: ← and its own holds are all it has.
        assertEquals(listOf("←", "⟵", "↔"), top[0].longPress)
        // • keeps ◦ as its default hold; ↓ comes after.
        assertEquals("◦", top[3].longPress.first())
        assertEquals("↓", top[3].longPress.last())
        // = already held ≥, which is not repeated.
        assertEquals(1, top[9].longPress.count { it == "≥" })
        // With the number row over the page, nothing changes.
        assertSame(rows, symbolBodyRows(rows, LayoutMode.SYMBOLS_SHIFTED, true, true, fill, arrows))
    }

    @Test
    fun `only text keys take a hold`() {
        val row = listOf(Key("⌫", action = KeyAction.Delete), Key("a"))
        val out = withHiddenRowHolds(row, listOf(Key("x"), Key("y")), lead = true)
        assertEquals(emptyList<String>(), out[0].longPress)
        assertEquals(listOf("y"), out[1].longPress)
    }
}
