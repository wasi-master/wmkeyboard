package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketPairsTest {

    @Test
    fun `an opening bracket closes itself`() {
        assertEquals(")", autoCloseCloserFor('(', ""))
        assertEquals("]", autoCloseCloserFor('[', null))
        assertEquals("}", autoCloseCloserFor('{', ""))
    }

    @Test
    fun `the CJK and fullwidth brackets close too`() {
        assertEquals("】", autoCloseCloserFor('【', ""))
        assertEquals("』", autoCloseCloserFor('『', ""))
        assertEquals("」", autoCloseCloserFor('「', ""))
        assertEquals("）", autoCloseCloserFor('（', ""))
    }

    @Test
    fun `quotes and the less-than sign are left alone`() {
        assertNull(autoCloseCloserFor('"', ""))
        assertNull(autoCloseCloserFor('\'', ""))
        assertNull(autoCloseCloserFor('`', ""))
        assertNull(autoCloseCloserFor('«', ""))
        assertNull(autoCloseCloserFor('<', ""))
    }

    @Test
    fun `a closer is not an opener`() {
        assertNull(autoCloseCloserFor(')', ""))
        assertNull(autoCloseCloserFor('】', ""))
    }

    @Test
    fun `a bracket typed in front of a word is being put around it by hand`() {
        assertNull(autoCloseCloserFor('(', "world"))
        assertNull(autoCloseCloserFor('[', "42"))
        assertNull(autoCloseCloserFor('{', "ব"))
    }

    @Test
    fun `a bracket typed in front of any other text stands down too`() {
        assertNull(autoCloseCloserFor('(', "@name"))
        assertNull(autoCloseCloserFor('(', "#tag"))
        assertNull(autoCloseCloserFor('(', "\$5"))
        assertNull(autoCloseCloserFor('(', "\"quote\""))
        assertNull(autoCloseCloserFor('(', "\uD83D\uDE00"))
    }

    @Test
    fun `anything that is not a word leaves room for the pair`() {
        assertEquals(")", autoCloseCloserFor('(', " world"))
        assertEquals(")", autoCloseCloserFor('(', "."))
        assertEquals(")", autoCloseCloserFor('(', ")"))
        assertEquals(")", autoCloseCloserFor('(', "\n"))
        assertEquals(")", autoCloseCloserFor('(', ", and"))
        assertEquals("」", autoCloseCloserFor('「', "。"))
    }

    @Test
    fun `a closer types over the one already there`() {
        assertTrue(typesOverCloser(')', ")"))
        assertTrue(typesOverCloser('】', "】 and on"))
        assertFalse(typesOverCloser(')', "]"))
        assertFalse(typesOverCloser(')', ""))
        assertFalse(typesOverCloser(')', null))
    }

    @Test
    fun `only a closer types over anything`() {
        assertFalse(typesOverCloser('(', "("))
        assertFalse(typesOverCloser('a', "a"))
        assertFalse(typesOverCloser('"', "\""))
    }

    @Test
    fun `one backspace takes out an empty pair`() {
        assertTrue(deletesEmptyPair("say (", ")"))
        assertTrue(deletesEmptyPair("【", "】 rest"))
        assertTrue(deletesEmptyPair("(", ")"))
    }

    @Test
    fun `a pair with something in it, or a mismatched one, deletes one character`() {
        assertFalse(deletesEmptyPair("(foo", ")"))
        assertFalse(deletesEmptyPair("(", "]"))
        assertFalse(deletesEmptyPair("(", ""))
        assertFalse(deletesEmptyPair("(", null))
        assertFalse(deletesEmptyPair("", ")"))
        assertFalse(deletesEmptyPair("hello", ")"))
    }

    @Test
    fun `every pair has a closer that is itself never an opener`() {
        for ((opener, closer) in AUTO_CLOSE_PAIRS) {
            assertEquals(1, closer.length)
            assertTrue(closer[0] in AUTO_CLOSE_CLOSERS)
            assertFalse("$opener closes onto itself", closer[0] in AUTO_CLOSE_PAIRS)
        }
        assertEquals(AUTO_CLOSE_PAIRS.size, AUTO_CLOSE_CLOSERS.size)
    }

    @Test
    fun `a closing bracket at the end of what it closes takes the space after it`() {
        assertTrue(closerTakesSpace(null))
        assertTrue(closerTakesSpace(""))
        assertTrue(closerTakesSpace(" more"))
        assertTrue(closerTakesSpace("\nnext line"))
    }

    @Test
    fun `a closing bracket landing mid-text takes no space`() {
        assertFalse(closerTakesSpace(")"))
        assertFalse(closerTakesSpace("."))
        assertFalse(closerTakesSpace("word"))
    }

    @Test
    fun `the user's own pairs read as two characters each (#1361)`() {
        val own = parseBracketPairs("<> «» ¿? ab 1) \"\" <<> x")
        assertEquals(mapOf('<' to ">", '«' to "»", '¿' to "?"), own)
        val pairs = AUTO_CLOSE_PAIRS + own
        val closers = closersOf(pairs)
        assertEquals(">", autoCloseCloserFor('<', " ", pairs, closers))
        assertEquals("?", autoCloseCloserFor('¿', null, pairs, closers))
        assertEquals(null, autoCloseCloserFor('<', "x", pairs, closers))
        assertTrue(typesOverCloser('»', "» rest", closers))
        assertTrue(deletesEmptyPair("a «", "»", pairs))
    }
}
