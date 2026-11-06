package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant (#1143) and the voice input components (#1142) it leans on. */
class AssistComponentsTest {

    private fun settings(systemInput: Boolean, assistant: Boolean, aiTool: Boolean = true): KeyboardSettings {
        val base = KeyboardSettings()
        return base.copy(
            voiceBar = base.voiceBar.copy(systemInput = systemInput),
            ai = base.ai.copy(assistant = assistant),
            enabledTools = if (aiTool) base.enabledTools + ToolbarTool.AI else base.enabledTools - ToolbarTool.AI,
        )
    }

    @Test
    fun `nothing is offered by default`() {
        assertEquals(
            SystemVoiceComponents.Wanted(recognizer = false, voiceInput = false, assistant = false),
            SystemVoiceComponents.wanted(KeyboardSettings()),
        )
    }

    @Test
    fun `voice input for other apps offers the recognizer and the window`() {
        assertEquals(
            SystemVoiceComponents.Wanted(recognizer = true, voiceInput = true, assistant = false),
            SystemVoiceComponents.wanted(settings(systemInput = true, assistant = false)),
        )
    }

    @Test
    fun `the assistant brings the recognizer but not the window`() {
        assertEquals(
            SystemVoiceComponents.Wanted(recognizer = true, voiceInput = false, assistant = true),
            SystemVoiceComponents.wanted(settings(systemInput = false, assistant = true)),
        )
    }

    @Test
    fun `the assistant goes with the AI tool`() {
        assertEquals(
            SystemVoiceComponents.Wanted(recognizer = false, voiceInput = false, assistant = false),
            SystemVoiceComponents.wanted(settings(systemInput = false, assistant = true, aiTool = false)),
        )
    }

    @Test
    fun `screen text drops blanks and repeated neighbours`() {
        assertEquals(
            "Inbox\nHello there\nInbox",
            AssistScreenText.join(listOf("  Inbox ", "Inbox", "", "Hello there", "   ", "Inbox")),
        )
    }

    @Test
    fun `screen text is cut on a line near the limit`() {
        val lines = List(20) { "line number $it" }
        val text = AssistScreenText.join(lines, limit = 100)
        assertTrue(text.length <= 100)
        assertTrue(text.endsWith(lines[text.lines().size - 1]))
    }

    @Test
    fun `a single long line is cut at the limit`() {
        assertEquals(50, AssistScreenText.join(listOf("x".repeat(500)), limit = 50).length)
    }

    @Test
    fun `password fields are recognised by their input type`() {
        // TYPE_CLASS_TEXT with each password variation, and a numeric PIN.
        assertTrue(AssistScreenText.isPassword(0x01 or 0x80))
        assertTrue(AssistScreenText.isPassword(0x01 or 0x90))
        assertTrue(AssistScreenText.isPassword(0x01 or 0xe0))
        assertTrue(AssistScreenText.isPassword(0x02 or 0x10))
        // Plain text, an email address, a plain number, and no input type.
        assertFalse(AssistScreenText.isPassword(0x01))
        assertFalse(AssistScreenText.isPassword(0x01 or 0x20))
        assertFalse(AssistScreenText.isPassword(0x02))
        assertFalse(AssistScreenText.isPassword(0))
    }
}
