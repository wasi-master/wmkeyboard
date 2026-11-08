package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `the label stays on one line and short`() {
        assertEquals("one two", DeletionUndo.label("one\ntwo"))
        val long = "x".repeat(100)
        val label = DeletionUndo.label(long)
        assertEquals(40, label.length)
        assertEquals('…', label.last())
    }
}
