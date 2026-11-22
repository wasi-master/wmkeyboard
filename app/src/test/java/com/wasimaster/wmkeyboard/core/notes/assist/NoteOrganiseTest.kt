package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOrganiseTest {

    private val body = "buy milk and eggs. call the plumber on friday about the sink. pay rent 12000 by the 5th. ask Anna about the trip"

    @Test
    fun aRewriteThatKeepsTheNoteIsShown() {
        val answer = """
            ## To do
            - Pay rent 12000 by the 5th
            - Call the plumber on Friday about the sink
            - Ask Anna about the trip

            ## Shopping
            - Buy milk and eggs
        """.trimIndent()
        assertEquals(answer, NoteAssistPrompts.cleanOrganised(answer, body))
    }

    @Test
    fun aFenceAndThinkingAreTakenOff() {
        val inner = "## Shopping\n- buy milk and eggs\n\n## Calls\n- call the plumber on friday about the sink\n- ask Anna about the trip\n- pay rent 12000 by the 5th"
        val answer = "<think>sorting the lines</think>\n```markdown\n$inner\n```"
        assertEquals(inner, NoteAssistPrompts.cleanOrganised(answer, body))
    }

    @Test
    fun aRewriteThatDroppedPartOfTheNoteIsNotShown() {
        assertNull(NoteAssistPrompts.cleanOrganised("## Shopping\n- buy milk", body))
    }

    @Test
    fun theSameTextOrNothingIsNotShown() {
        assertNull(NoteAssistPrompts.cleanOrganised(body, body))
        assertNull(NoteAssistPrompts.cleanOrganised("   ", body))
    }

    @Test
    fun blankLineRunsCloseUp() {
        val answer = "## A\n- buy milk and eggs\n\n\n\n- call the plumber on friday about the sink, pay rent 12000 by the 5th, ask Anna about the trip"
        assertFalse(NoteAssistPrompts.cleanOrganised(answer, body)!!.contains("\n\n\n"))
    }

    @Test
    fun organiseIsOfferedForAReadableNoteOfSomeLength() {
        assertTrue(NoteAssistRules.canOrganise(Note(id = 1, body = body)))
        assertFalse(NoteAssistRules.canOrganise(Note(id = 2, body = "buy milk")))
        assertFalse(NoteAssistRules.canOrganise(Note(id = 3, body = body, locked = true)))
        assertFalse(NoteAssistRules.canOrganise(Note(id = 4, body = "word ".repeat(NoteAssistPrompts.MAX_INPUT_CHARS))))
    }
}
