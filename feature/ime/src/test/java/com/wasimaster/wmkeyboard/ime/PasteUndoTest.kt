package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteUndoTest {

    @Test
    fun `the paste right before the caret goes whole`() {
        val forms = PasteUndo.forms("https://example.com/a")
        assertEquals(21, PasteUndo.deleteLength(forms, "see https://example.com/a", composingEmpty = true))
    }

    @Test
    fun `text after the paste makes it an ordinary press`() {
        val forms = PasteUndo.forms("hello")
        assertEquals(0, PasteUndo.deleteLength(forms, "hello!", composingEmpty = true))
        assertEquals(0, PasteUndo.deleteLength(forms, "hell", composingEmpty = true))
        assertEquals(0, PasteUndo.deleteLength(forms, null, composingEmpty = true))
    }

    @Test
    fun `an open word is edited, not the paste`() {
        val forms = PasteUndo.forms("hello")
        assertEquals(0, PasteUndo.deleteLength(forms, "hello", composingEmpty = false))
    }

    @Test
    fun `the field's form of the paste counts`() {
        // An editor that folded Windows line breaks, and Fancy Text's styling.
        assertEquals(3, PasteUndo.deleteLength(PasteUndo.forms("a\r\nb"), "x a\nb", composingEmpty = true))
        val styled = PasteUndo.forms("ab", styled = "𝐚𝐛")
        assertEquals("𝐚𝐛".length, PasteUndo.deleteLength(styled, "x 𝐚𝐛", composingEmpty = true))
        assertEquals(2, PasteUndo.deleteLength(styled, "x ab", composingEmpty = true))
    }

    @Test
    fun `only text short enough, outside a terminal, with the switch on is recorded`() {
        assertTrue(PasteUndo.recordable("hi", enabled = true, nullField = false))
        assertFalse(PasteUndo.recordable("hi", enabled = false, nullField = false))
        assertFalse(PasteUndo.recordable("hi", enabled = true, nullField = true))
        assertFalse(PasteUndo.recordable("", enabled = true, nullField = false))
        assertFalse(PasteUndo.recordable(null, enabled = true, nullField = false))
        assertFalse(PasteUndo.recordable("x".repeat(PasteUndo.MAX_CHARS + 1), enabled = true, nullField = false))
    }
}
