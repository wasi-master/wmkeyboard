package com.wasimaster.wmkeyboard.ime.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretWordTest {

    @Test
    fun `the word the caret touches, from either side or inside`() {
        assertEquals(CaretWord.Span("this", -4, 0), CaretWord.at("look at this", ""))
        assertEquals(CaretWord.Span("this", 0, 4), CaretWord.at("look at ", "this one"))
        assertEquals(CaretWord.Span("this", -2, 2), CaretWord.at("look at th", "is one"))
    }

    @Test
    fun `the word just before the caret across spaces, but not after it`() {
        // "this ⌶" (#1085).
        assertEquals(CaretWord.Span("this", -5, -1), CaretWord.at("look at this ", ""))
        assertEquals(CaretWord.Span("this", -6, -2), CaretWord.at("look at this  ", ""))
        // The next word is not the one just typed.
        assertEquals(CaretWord.Span("one", 0, 3), CaretWord.at("", "one two"))
        assertNull(CaretWord.at("", " one"))
        // A line break ends it.
        assertNull(CaretWord.at("this\n", ""))
    }

    @Test
    fun `edges and scripts`() {
        assertEquals(CaretWord.Span("motte-and-bailey", -16, 0), CaretWord.at("a motte-and-bailey", ""))
        assertEquals(CaretWord.Span("word", -5, -1), CaretWord.at("-word-", ""))
        // Vowel signs belong to the word: কিতাব is one word, not কি and তাব.
        assertEquals("কিতাব", CaretWord.at("কিতাব", "")?.word)
        assertNull(CaretWord.at("1234", ""))
    }

    @Test
    fun `a selection is a word or a short phrase`() {
        assertEquals("faux pas", CaretWord.clean(" “faux pas” "))
        assertNull(CaretWord.clean("   "))
        assertNull(CaretWord.clean("x".repeat(CaretWord.MAX_LENGTH + 1)))
    }
}
