package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Symbols page setting: which page `?123` opens on a layout that borrows
 * the shipped one, and what the number pad page tells the renderer.
 */
class SymbolsPageTest {

    private fun labels(layout: KeyboardLayout) = layout.rows.map { row -> row.map { it.label } }

    @Test
    fun `a borrowed symbols page follows the setting`() {
        val standard = BuiltInLayouts.AZERTY.compile(LayoutLayer.SYMBOLS)
        val pad = BuiltInLayouts.AZERTY.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)

        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), labels(standard).first())
        assertEquals(listOf("#", "$", "&", "_", "-", "1", "2", "3", "?"), labels(pad).first())
        assertEquals("{&=", pad.rows[2].first().label)
        assertEquals(KeyAction.Symbols, pad.rows[2].first().action)
        assertTrue(pad.borrowedSymbols)
    }

    @Test
    fun `a layout's own symbols page wins over the setting`() {
        val own = listOf(listOf(Key("a"), Key("b")), listOf(Key("ABC", action = KeyAction.Letters)))
        val spec = BuiltInLayouts.QWERTY.copy(
            id = "own_symbols",
            layers = BuiltInLayouts.QWERTY.layers + (LayoutLayer.SYMBOLS.key to LayerSpec(own)),
        )

        val compiled = spec.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)

        assertEquals(listOf("a", "b"), labels(compiled).first())
        assertFalse(compiled.digitPad)
    }

    @Test
    fun `the shipped pages QWERTY and French carry count as borrowed`() {
        // QWERTY holds the shared page as a layer of its own, French a copy
        // with the euro on it; neither is a page anyone wrote.
        val qwerty = BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)
        val french = BuiltInLayouts.FRENCH.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)

        assertEquals("{&=", qwerty.rows[2].first().label)
        assertEquals("{&=", french.rows[2].first().label)
        assertEquals("€", french.rows.first()[1].label)
    }

    @Test
    fun `an edited QWERTY symbols page is the user's and stays`() {
        val shipped = BuiltInLayouts.QWERTY.layer(LayoutLayer.SYMBOLS)!!
        val edited = BuiltInLayouts.QWERTY.copy(
            layers = BuiltInLayouts.QWERTY.layers +
                (LayoutLayer.SYMBOLS.key to shipped.copy(rows = shipped.rows.drop(1))),
        )

        assertFalse(edited.borrowsSymbolsPage())
        assertEquals("@", edited.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD).rows.first().first().label)
    }

    @Test
    fun `the setting leaves every other layer alone`() {
        assertEquals(
            labels(BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS_SHIFTED)),
            labels(BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS_SHIFTED, SymbolsPage.NUMBER_PAD)),
        )
    }

    @Test
    fun `the number pad page shows the language's own currency`() {
        val hindi = BuiltInLayouts.AZERTY.copy(id = "hindi_symbols", langId = "hi")
        val pad = hindi.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)

        assertEquals("₹", pad.rows.first()[1].label)
        assertEquals("$", pad.rows.first()[1].longPress.first())
    }

    @Test
    fun `the number pad page carries its own digits and the standard page does not`() {
        assertTrue(BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD).digitPad)
        assertFalse(BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS).digitPad)
        assertFalse(BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS, SymbolsPage.NUMBER_PAD).digitPad)
    }

    @Test
    fun `digit holds come from the shipped page`() {
        val pad = BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS, SymbolsPage.NUMBER_PAD)
        val four = pad.rows.flatten().first { it.label == "4" }

        assertTrue("¼" in four.longPress)
    }
}
