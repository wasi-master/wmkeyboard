package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionUndoTest {

    @Test
    fun `a short field gives the deleted tail`() {
        assertEquals("o", DeletionUndo.deleted("hello", "", "hell"))
        assertEquals("world ", DeletionUndo.deleted("hello world ", "", "hello "))
    }

    @Test
    fun `nothing changed is an empty deletion`() {
        assertEquals("", DeletionUndo.deleted("hello", "", "hello"))
    }

    @Test
    fun `a selection is what was selected`() {
        assertEquals("brown fox", DeletionUndo.deleted("the quick ", "brown fox", "the quick "))
    }

    @Test
    fun `a word put back in place of another is no deletion`() {
        // Backspace taking an autocorrect back: "the " becomes "teh".
        assertNull(DeletionUndo.deleted("I saw the ", "", "I saw teh"))
        assertNull(DeletionUndo.deleted("ab", "x", "a"))
    }

    @Test
    fun `a long field lines the two windows up`() {
        val text = (0 until 3000).joinToString("") { ('a' + it % 26).toString() }
        val window = DeletionUndo.WINDOW
        val before = text.takeLast(window)
        val after = text.dropLast(5).takeLast(window)
        assertEquals(text.takeLast(5), DeletionUndo.deleted(before, "", after))
    }

    @Test
    fun `a forward delete gives what went after the caret`() {
        assertEquals("Hello, how are you doing", DeletionUndo.deletedAhead("Hello, how are you doing", ""))
        assertEquals("one ", DeletionUndo.deletedAhead("one two", "two"))
        assertEquals("", DeletionUndo.deletedAhead("same", "same"))
        assertNull(DeletionUndo.deletedAhead("one two", "one"))
    }

    @Test
    fun `a long tail lines the two forward windows up`() {
        val text = (0 until 3000).joinToString("") { ('a' + it % 26).toString() }
        val window = DeletionUndo.WINDOW
        assertEquals(text.take(7), DeletionUndo.deletedAhead(text.take(window), text.drop(7).take(window)))
    }

    @Test
    fun `the label stays on one line and short`() {
        assertEquals("one two", DeletionUndo.label("one\ntwo"))
        val long = "x".repeat(100)
        val label = DeletionUndo.label(long)
        assertEquals(40, label.length)
        assertEquals('…', label.last())
    }

    @Test
    fun `a letter or two off a word gets no chip`() {
        assertFalse(DeletionUndo.holdsWord("o", left = "I said hel", right = "", forward = false))
        assertFalse(DeletionUndo.holdsWord("lo", left = "I said hel", right = "", forward = false))
        assertFalse(DeletionUndo.holdsWord(" ", left = "I said hello", right = "", forward = false))
        // A vowel sign is part of its word, so the word is not whole yet.
        assertFalse(DeletionUndo.holdsWord("\u09A4", left = "\u0995\u09BF", right = "", forward = false))
    }

    @Test
    fun `a whole word gets the chip`() {
        assertTrue(DeletionUndo.holdsWord("hello", left = "I said ", right = "", forward = false))
        assertTrue(DeletionUndo.holdsWord("hello", left = "", right = "", forward = false))
        assertTrue(DeletionUndo.holdsWord("lo world", left = "hel", right = "", forward = false))
        assertTrue(DeletionUndo.holdsWord("a", left = "I ", right = "", forward = false))
    }

    @Test
    fun `a forward run needs a word's end`() {
        assertFalse(DeletionUndo.holdsWord("he", left = "", right = "llo", forward = true))
        assertTrue(DeletionUndo.holdsWord("hello", left = "", right = " there", forward = true))
        assertTrue(DeletionUndo.holdsWord("one t", left = "", right = "wo", forward = true))
    }

    @Test
    fun `a long run counts whatever it holds`() {
        assertTrue(DeletionUndo.holdsWord("\u4F60\u597D".repeat(5), left = "\u6211", right = "", forward = false))
        assertFalse(DeletionUndo.holdsWord("\u4F60\u597D", left = "\u6211", right = "", forward = false))
    }
}
