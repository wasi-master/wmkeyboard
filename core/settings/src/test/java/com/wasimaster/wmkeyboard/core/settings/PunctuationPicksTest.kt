package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the punctuation keys remember of their holds (#1096). */
class PunctuationPicksTest {

    @Test
    fun `a mark picked from the comma is kept`() {
        assertEquals(mapOf("," to "?"), rememberedPicksAfter(emptyMap(), ",", "?"))
    }

    @Test
    fun `a key typing its pick is found by what it types as laid out`() {
        assertEquals(mapOf("," to "!"), rememberedPicksAfter(mapOf("," to "?"), "?", "!"))
    }

    @Test
    fun `picking the layout's own character forgets the key`() {
        assertEquals(emptyMap<String, String>(), rememberedPicksAfter(mapOf("," to "?"), "?", ","))
        assertNull(rememberedPicksAfter(emptyMap(), ",", ","))
    }

    @Test
    fun `letters, digits and longer text are never kept`() {
        assertNull(rememberedPicksAfter(emptyMap(), "e", "é"))
        assertNull(rememberedPicksAfter(emptyMap(), ".", ".com"))
        assertNull(rememberedPicksAfter(emptyMap(), ",", "1"))
    }

    @Test
    fun `the same pick again changes nothing`() {
        assertNull(rememberedPicksAfter(mapOf("," to "?"), "?", "?"))
    }

    @Test
    fun `the oldest key goes once the list is full`() {
        val full = (0 until PunctuationPicksMax).associate { "${'!' + it}" to "~" }
        val next = rememberedPicksAfter(full, "|", "?")!!
        assertEquals(PunctuationPicksMax, next.size)
        assertEquals("?", next["|"])
        assertNull(next["!"])
    }

    @Test
    fun `the stored form reads back`() {
        val picks = mapOf("," to "?", "।" to "!")
        assertEquals(picks, decodePunctuationPicks(encodePunctuationPicks(picks)))
        assertEquals(emptyMap<String, String>(), decodePunctuationPicks("broken\n\t\n"))
    }
}
