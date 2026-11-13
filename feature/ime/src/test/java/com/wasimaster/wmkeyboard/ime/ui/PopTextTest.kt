package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.PopTextMode
import com.wasimaster.wmkeyboard.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pop text (gap list item 45): what shows, for how long, and never while stilled. */
class PopTextTest {

    @Test
    fun `each mode shows what it says`() {
        assertFalse(popTextWanted(PopTextMode.NEVER, still = false, word = true, corrected = true))
        assertTrue(popTextWanted(PopTextMode.CORRECTIONS, still = false, word = true, corrected = true))
        assertFalse(popTextWanted(PopTextMode.CORRECTIONS, still = false, word = true, corrected = false))
        assertFalse(popTextWanted(PopTextMode.CORRECTIONS, still = false, word = false, corrected = false))
        assertTrue(popTextWanted(PopTextMode.WORDS, still = false, word = true, corrected = false))
        assertFalse(popTextWanted(PopTextMode.WORDS, still = false, word = false, corrected = false))
        assertTrue(popTextWanted(PopTextMode.EVERY_KEY, still = false, word = false, corrected = false))
        assertTrue(popTextWanted(PopTextMode.EVERY_KEY, still = false, word = true, corrected = false))
    }

    @Test
    fun `reduce motion stills every mode`() {
        for (mode in PopTextMode.entries) {
            assertFalse(popTextWanted(mode, still = true, word = true, corrected = true))
            assertFalse(popTextWanted(mode, still = true, word = false, corrected = false))
        }
    }

    @Test
    fun `the field takes nothing while off, and a word needs somewhere to rise from`() {
        val field = PopTextField(clock = { 0L })
        field.key("a", Offset.Zero)
        field.word("hello", corrected = true)
        assertTrue(field.pops.isEmpty())
        assertFalse(field.active)

        field.mode = PopTextMode.WORDS
        field.word("hello", corrected = false)
        assertTrue("no space bar measured yet", field.pops.isEmpty())
        field.wordAnchor = { Offset(10f, 20f) }
        field.word("hello", corrected = false)
        assertEquals(1, field.pops.size)
        assertTrue(field.active)
    }

    @Test
    fun `pops are dated by their first frame and go when their time is up`() {
        val field = PopTextField(clock = { 0L })
        field.mode = PopTextMode.EVERY_KEY
        field.wordAnchor = { Offset.Zero }
        field.key("a", Offset(5f, 5f))
        field.word("ab", corrected = false)
        assertTrue(field.frame(1_000L))
        assertTrue(field.frame(1_000L + PopTextField.KEY_LIFE_MS - 1))
        assertTrue("the key's is gone, the word's lives on", field.frame(1_000L + PopTextField.KEY_LIFE_MS))
        assertEquals(listOf("ab"), field.pops.map { it.text })
        assertFalse(field.frame(1_000L + PopTextField.WORD_LIFE_MS))
        assertFalse(field.active)
    }

    @Test
    fun `fast typing keeps only the newest few`() {
        val field = PopTextField(clock = { 0L })
        field.mode = PopTextMode.EVERY_KEY
        repeat(PopTextField.MAX_POPS + 3) { field.key("$it", Offset.Zero) }
        assertEquals(PopTextField.MAX_POPS, field.pops.size)
        assertEquals("3", field.pops.first().text)
    }

    @Test
    fun `a pop rises, holds, then fades`() {
        assertEquals(0f to 1f, popTextFrame(0f))
        val (riseMid, alphaEarly) = popTextFrame(0.25f)
        assertTrue(riseMid > 0.25f)
        assertEquals(1f, alphaEarly)
        assertEquals(1f to 0f, popTextFrame(1f))
        assertEquals(popTextFrame(1f), popTextFrame(3f))
    }

    @Test
    fun `a key's pop shows the capital shift will type`() {
        assertEquals("a", popKeyText(Key("a"), ShiftState.OFF))
        assertEquals("A", popKeyText(Key("a"), ShiftState.ON))
        assertEquals("ß", popKeyText(Key("ß"), ShiftState.OFF))
        assertEquals(",", popKeyText(Key(","), ShiftState.CAPS_LOCK))
    }
}
