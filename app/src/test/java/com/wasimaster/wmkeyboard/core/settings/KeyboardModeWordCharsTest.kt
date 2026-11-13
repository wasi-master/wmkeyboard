package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A mode's extra in-word characters (competitor gap list item 34): `%` and `_` for Tasker. */
class KeyboardModeWordCharsTest {

    private val tasker = KeyboardMode(id = "tasker", name = "Tasker", apps = listOf("net.dinglisch.android.taskerm"), wordChars = "%_")

    @Test
    fun `the characters reach the composing settings and an empty field adds none`() {
        val base = KeyboardSettings()
        assertEquals("", base.autoText.wordChars)
        assertEquals("%_", base.applyMode(tasker).autoText.wordChars)
        assertEquals("", base.applyMode(tasker.copy(wordChars = "")).autoText.wordChars)
        assertEquals("", base.applyMode(null).autoText.wordChars)
    }

    @Test
    fun `nothing else about the typing settings moves`() {
        val base = KeyboardSettings()
        val applied = base.applyMode(tasker)
        assertEquals(base.autoText.copy(wordChars = "%_"), applied.autoText)
        assertEquals(base.copy(autoText = applied.autoText), applied)
    }

    @Test
    fun `the other autoText overrides still apply alongside`() {
        val applied = KeyboardSettings().applyMode(tasker.copy(autoCapitalize = false, commitAsTyped = false))
        assertEquals("%_", applied.autoText.wordChars)
        assertFalse(applied.autoText.capitalize)
    }

    @Test
    fun `what is typed in the editor is cleaned on the way in`() {
        assertEquals("%_", modeWordChars("%_"))
        assertEquals("%_", modeWordChars(" % _ %"))
        assertEquals("%_.", modeWordChars("a%1_b.%"))
        assertEquals(",", modeWordChars(","))
        assertEquals("", modeWordChars("abc 123\t"))
        assertEquals("_", KeyboardSettings().applyMode(tasker.copy(wordChars = "x _ y")).autoText.wordChars)
        assertEquals("", KeyboardSettings().applyMode(tasker.copy(wordChars = "letters")).autoText.wordChars)
    }

    @Test
    fun `the field survives the codec and an old mode decodes it empty`() {
        assertEquals(tasker, KeyboardModeCodec.decodeList(KeyboardModeCodec.encodeList(listOf(tasker))).single())
        val legacy = KeyboardModeCodec.encodeList(listOf(KeyboardMode("m", "M"))).replace(",\"wordChars\":\"\"", "")
        assertFalse(legacy.contains("wordChars"))
        assertEquals("", KeyboardModeCodec.decodeList(legacy).single().wordChars)
    }
}
