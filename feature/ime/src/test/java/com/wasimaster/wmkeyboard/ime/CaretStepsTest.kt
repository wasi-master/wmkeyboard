package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** One read for a drag's worth of caret steps (#1474). */
class CaretStepsTest {

    @Test
    fun `plain characters are one step each`() {
        assertEquals(3, caretStepChars("hello", 3, backward = true))
        assertEquals(3, caretStepChars("hello", 3, backward = false))
    }

    @Test
    fun `an emoji is one step either way`() {
        val text = "a\uD83D\uDE00b"
        assertEquals(1, caretStepChars(text, 1, backward = true))
        assertEquals(3, caretStepChars(text, 2, backward = true))
        assertEquals(1, caretStepChars(text, 1, backward = false))
        assertEquals(3, caretStepChars(text, 2, backward = false))
    }

    @Test
    fun `steps past the end of the text cover nothing more`() {
        assertEquals(2, caretStepChars("ab", 10, backward = true))
        assertEquals(0, caretStepChars("", 4, backward = false))
    }
}
