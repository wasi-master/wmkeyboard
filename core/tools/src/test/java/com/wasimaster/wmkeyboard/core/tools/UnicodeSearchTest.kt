package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeSearchTest {

    private val points = intArrayOf(0x41, 0x61, 0x2190, 0x2192, 0x21D2, 0x4E00, 0xAC00)

    @Test
    fun `every word of the query has to be in the name`() {
        assertEquals(listOf(0x2192, 0x21D2), UnicodeSearch.search("right arrow", points))
        assertEquals(listOf(0x2190), UnicodeSearch.search("LEFT arrow", points))
    }

    @Test
    fun `a code finds its character first, in any spelling`() {
        assertEquals(0x2192, UnicodeSearch.codePoint("U+2192"))
        assertEquals(0x2192, UnicodeSearch.codePoint("0x2192"))
        assertEquals(0x2192, UnicodeSearch.codePoint("2192"))
        assertEquals(0x41, UnicodeSearch.codePoint("u+41"))
        assertEquals(listOf(0x2192), UnicodeSearch.search("U+2192", points))
    }

    @Test
    fun `an ideograph comes up by its code, not by the code in its name`() {
        assertEquals(listOf(0x4E00), UnicodeSearch.search("4E00", points))
        assertEquals(emptyList<Int>(), UnicodeSearch.search("ideographs", points))
    }

    @Test
    fun `what is not a code is left to the names`() {
        assertNull(UnicodeSearch.codePoint("arrow"))
        assertNull(UnicodeSearch.codePoint("41"))
        assertNull(UnicodeSearch.codePoint("U+110000"))
        assertEquals(emptyList<Int>(), UnicodeSearch.search("   ", points))
    }

    @Test
    fun `a long list is cut`() {
        val many = (listOf(0x2190..0x21FF, 0x2900..0x297F, 0x2B00..0x2BFF).flatten()).toIntArray()
        assertEquals(UnicodeSearch.MAX_RESULTS, UnicodeSearch.search("arrow", many).size)
    }

    @Test
    fun `holding a character names it`() {
        assertEquals("U+2192 RIGHTWARDS ARROW", UnicodeSearch.caption(0x2192))
    }

    @Test
    fun `a token spells out what holding it offers`() {
        assertEquals("→", SymbolCatalog.face("→|⇒|⟶"))
        assertEquals(listOf("⇒", "⟶"), SymbolCatalog.alternates("→|⇒|⟶"))
        assertEquals("|", SymbolCatalog.face("|"))
        assertTrue(SymbolCatalog.alternates("|").isEmpty())
    }

    @Test
    fun `a plain symbol offers its relatives and its other case`() {
        assertTrue("⇒" in SymbolCatalog.alternates("→"))
        assertEquals(listOf("Α"), SymbolCatalog.alternates("α"))
        assertEquals(listOf("α"), SymbolCatalog.alternates("Α"))
        assertFalse(SymbolCatalog.alternates("½").isNotEmpty())
    }
}
