package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any character by the words of its Unicode name (#385). */
class UnicodeNamesTest {

    @Test
    fun `finds a character by its whole name, shortest name first`() {
        val dash = UnicodeNames.search("em dash")
        assertEquals("—", dash.first().emoji)
        assertEquals("Em dash", dash.first().name)
        assertEquals("©", UnicodeNames.search("copyright sign").first().emoji)
    }

    @Test
    fun `every query word must match, whole or as a start`() {
        val lambda = UnicodeNames.search("greek small lambda").map { it.emoji }
        assertTrue("λ" in lambda)
        assertTrue(UnicodeNames.search("greek small lambdq").isEmpty())
        // Too short to mean anything.
        assertTrue(UnicodeNames.search("a").isEmpty())
    }

    @Test
    fun `results carry the unicode category and respect the limit`() {
        val letters = UnicodeNames.search("latin small letter", limit = 5)
        assertEquals(5, letters.size)
        assertTrue(letters.all { it.category == UnicodeNames.CATEGORY })
    }

    @Test
    fun `reaches past the basic plane but not made-up ideograph names`() {
        assertTrue("\uD834\uDD1E" in UnicodeNames.search("musical symbol g clef").map { it.emoji })
        assertTrue(UnicodeNames.isMadeUpName(0x4E00, "CJK UNIFIED IDEOGRAPH-4E00"))
        assertTrue(!UnicodeNames.isMadeUpName(0x2014, "EM DASH"))
        // The ideographs themselves are left out; what is left is the
        // enclosed forms Unicode names after one (SQUARED CJK UNIFIED
        // IDEOGRAPH-7121 is U+1F210), which are characters of their own.
        assertTrue(UnicodeNames.search("cjk unified ideograph", limit = 200).none { Character.isIdeographic(it.emoji.codePointAt(0)) })
    }

    @Test
    fun `the strip offers a character only for its exact name (#1152)`() {
        assertEquals(UnicodeNames.StripMatch("\u2603", 0), UnicodeNames.forTyped("snowman", emptyList()).first())
        assertEquals("\u2014", UnicodeNames.forTyped("dash", listOf("em", "an")).first().text)
        assertEquals(1, UnicodeNames.forTyped("dash", listOf("em", "an")).first().wordsBefore)
        // A word that is no name on its own finds nothing.
        assertTrue(UnicodeNames.forTyped("word", listOf("a")).isEmpty())
        assertTrue(UnicodeNames.forTyped("x", emptyList()).isEmpty())
    }

    @Test
    fun `a trailing sign or symbol may be left off, and a greek letter goes by its name`() {
        assertEquals("\u00B0", UnicodeNames.forTyped("degree", emptyList()).first().text)
        assertEquals("\u20AC", UnicodeNames.forTyped("Euro", emptyList()).first().text)
        assertEquals("\u03BB", UnicodeNames.forTyped("lambda", emptyList()).first().text)
        assertEquals("\u03A9", UnicodeNames.forTyped("omega", listOf("capital")).first().text)
    }

    @Test
    fun `only the words of the name are deleted, and only when they are there`() {
        assertEquals(3, UnicodeNames.trailingWordsLength("I like em ", listOf("em")))
        assertEquals(15, UnicodeNames.trailingWordsLength("a greek  capital ", listOf("greek", "capital")))
        assertEquals(0, UnicodeNames.trailingWordsLength("I like it ", listOf("em")))
        assertEquals(0, UnicodeNames.trailingWordsLength("", listOf("em")))
    }
}
