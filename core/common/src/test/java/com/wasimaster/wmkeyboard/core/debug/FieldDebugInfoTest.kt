package com.wasimaster.wmkeyboard.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldDebugInfoTest {

    @Test
    fun `input types read as class, variation and flags`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertEquals("text, email address (0x21)", FieldDebugInfo.decodeInputType(0x21))
        // A chat box: text, short message, caps sentences, auto correct, multi line.
        assertEquals(
            "text, short message, caps sentences, auto correct, multi line (0x2c041)",
            FieldDebugInfo.decodeInputType(0x1 or 0x40 or 0x4000 or 0x8000 or 0x20000),
        )
        assertEquals("text, web password (0xe1)", FieldDebugInfo.decodeInputType(0xe1))
        assertEquals("number, normal, signed, decimal (0x3002)", FieldDebugInfo.decodeInputType(0x3002))
        assertEquals("phone (0x3)", FieldDebugInfo.decodeInputType(0x3))
        assertEquals("datetime, time (0x24)", FieldDebugInfo.decodeInputType(0x24))
        assertTrue(FieldDebugInfo.decodeInputType(0).startsWith("null"))
    }

    @Test
    fun `an unknown bit is shown rather than dropped`() {
        assertEquals("text, normal, flags 0x800000 (0x800001)", FieldDebugInfo.decodeInputType(0x800001))
        assertEquals("class 0x9 (0x9)", FieldDebugInfo.decodeInputType(0x9))
    }

    @Test
    fun `ime options read as the action and every flag`() {
        // IME_ACTION_SEARCH | IME_FLAG_NO_EXTRACT_UI
        assertEquals("action search, no extract UI (0x10000003)", FieldDebugInfo.decodeImeOptions(0x10000003))
        // IME_ACTION_SEND | IME_FLAG_NO_PERSONALIZED_LEARNING | IME_FLAG_NO_ENTER_ACTION
        assertEquals(
            "action send, no personalized learning, no enter action (0x41000004)",
            FieldDebugInfo.decodeImeOptions(0x41000004),
        )
        assertEquals("action unspecified, force ASCII (0x80000000)", FieldDebugInfo.decodeImeOptions(Int.MIN_VALUE))
        assertEquals("action done, flags 0x100 (0x106)", FieldDebugInfo.decodeImeOptions(0x106))
    }

    @Test
    fun `caps modes read as words`() {
        assertEquals("none", FieldDebugInfo.decodeCapsMode(0))
        assertEquals("sentences", FieldDebugInfo.decodeCapsMode(0x4000))
        assertEquals("words, sentences", FieldDebugInfo.decodeCapsMode(0x6000))
    }

    @Test
    fun `declared text is one short line`() {
        assertEquals("Search or type URL", FieldDebugInfo.oneLine("Search or\ntype URL".replace("\n", " ")))
        assertEquals("a b", FieldDebugInfo.oneLine("a\r\nb"))
        assertEquals("", FieldDebugInfo.oneLine(null))
        val long = "x".repeat(200)
        assertEquals(81, FieldDebugInfo.oneLine(long).length)
    }

    @Test
    fun `the report renders section by section, one line per value`() {
        val text = FieldDebugInfo.render(
            listOf(
                FieldDebugInfo.Section("Field", listOf("Package" to "com.example", "Hint" to "Type\na message")),
                FieldDebugInfo.Section("Screen", listOf("Density" to "2.75")),
            ),
        )
        assertEquals(
            "## Field\nPackage: com.example\nHint: Type a message\n\n## Screen\nDensity: 2.75",
            text,
        )
        assertFalse(text.endsWith("\n"))
    }
}
