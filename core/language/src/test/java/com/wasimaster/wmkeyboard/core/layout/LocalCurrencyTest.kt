package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCurrencyTest {
    @Test
    fun `english goes by the first region it knows`() {
        assertEquals("£", localCurrencyFor("en", listOf("GB")))
        assertEquals("€", localCurrencyFor("en", listOf("IE")))
        assertEquals("₹", localCurrencyFor("en", listOf("IN")))
        assertEquals("£", localCurrencyFor("en", listOf("CH", "GB")))
    }

    @Test
    fun `a dollar region answers before a later one`() {
        assertNull(localCurrencyFor("en", listOf("US", "GB")))
    }

    @Test
    fun `english with nothing to go by stays on the dollar`() {
        assertNull(localCurrencyFor("en", emptyList()))
        assertNull(localCurrencyFor("en", listOf("CH")))
    }

    @Test
    fun `portuguese is the euro unless the phone says Brazil`() {
        assertEquals("€", localCurrencyFor("pt", emptyList()))
        assertEquals("€", localCurrencyFor("pt", listOf("GB")))
        assertEquals("R$", localCurrencyFor("pt", listOf("BR")))
        assertEquals("R$", localCurrencyFor("pt", listOf("US", "BR")))
    }

    @Test
    fun `spanish follows the americas and is the euro elsewhere`() {
        assertNull(localCurrencyFor("es", listOf("MX")))
        assertEquals("S/", localCurrencyFor("es", listOf("PE")))
        assertEquals("€", localCurrencyFor("es", listOf("GB")))
    }

    @Test
    fun `a language with its own currency ignores the region`() {
        assertEquals("€", localCurrencyFor("de", listOf("US")))
        assertEquals("৳", localCurrencyFor("bn", listOf("GB")))
    }

    @Test
    fun `a language with no answer keeps the dollar`() {
        assertNull(localCurrencyFor("ar", listOf("SA")))
    }

    @Test
    fun `the currency leads a hold that led with the dollar`() {
        val key = Key("d", longPress = listOf("$", "ð", "£"))
        assertEquals(listOf("£", "$", "ð"), key.holdingCurrency("£").longPress)
    }

    @Test
    fun `a hold that leads with something else is left alone`() {
        val key = Key("d", longPress = listOf("€", "$"))
        assertEquals(key, key.holdingCurrency("£"))
    }

    @Test
    fun `a dollar key shows the currency and keeps the dollar first`() {
        val key = Key("$", longPress = listOf("৳", "€", "£"))
        val shown = key.showingCurrency("£")
        assertEquals("£", shown.label)
        assertEquals(listOf("$", "৳", "€"), shown.longPress)
        assertTrue(isLocalCurrencyKey(shown))
    }

    @Test
    fun `only a borrowed symbols page is marked as borrowed`() {
        fun spec(vararg own: Pair<String, LayerSpec>) = LayoutSpec(
            id = "test_currency_${own.size}",
            name = "Test",
            langId = "en",
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))) + own,
        )
        assertTrue(spec().compile(LayoutLayer.SYMBOLS).borrowedSymbols)
        assertFalse(spec().compile(LayoutLayer.LETTERS).borrowedSymbols)
        val written = LayoutLayer.SYMBOLS.key to LayerSpec(listOf(listOf(Key("$"))))
        assertFalse(spec(written).compile(LayoutLayer.SYMBOLS).borrowedSymbols)
    }
}
