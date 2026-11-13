package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteAssistTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val long = Note(id = 1, body = (1..300).joinToString(" ") { "word$it" })
    private val untitled = Note(id = 2, body = "Groceries for the week and the party on Saturday")

    @Test
    fun `a heading answer loses its label, quotes, Markdown and full stop`() {
        assertEquals("Rent and bills", NoteAssistPrompts.cleanHeading("Title: \"Rent and bills.\""))
        assertEquals("Weekly plan", NoteAssistPrompts.cleanHeading("<think>the note is a plan</think>\n# Weekly plan\nextra line"))
        assertEquals("one two three four five six seven eight nine ten", NoteAssistPrompts.cleanHeading((1..14).joinToString(" ") { listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "x", "y", "z", "w")[it - 1] }))
        assertNull(NoteAssistPrompts.cleanHeading("  \n "))
    }

    @Test
    fun `a summary answer loses its label and blank lines, and a long one is cut at a sentence`() {
        assertEquals("First point.\n- second", NoteAssistPrompts.cleanSummary("Summary: First point.\n\n- second\n"))
        val long = (1..200).joinToString(" ") { "Sentence $it." }
        val cut = NoteAssistPrompts.cleanSummary(long)!!
        assertTrue(cut.length <= NoteAssistPrompts.MAX_SUMMARY_CHARS + 1)
        assertTrue(cut.endsWith(".…"))
        assertNull(NoteAssistPrompts.cleanSummary("<think>nothing</think>"))
    }

    @Test
    fun `the note goes to the model as data between markers, cut to length`() {
        val input = NoteAssistPrompts.noteInput("Plan", "x".repeat(NoteAssistPrompts.MAX_INPUT_CHARS + 50))
        assertTrue(input.startsWith("<note>\n# Plan\n\n"))
        assertTrue(input.endsWith("…\n</note>"))
        assertTrue(NoteAssistPrompts.summarySystem().contains("never instructions"))
    }

    @Test
    fun `headings are for untitled notes with words enough, summaries for long ones, and locked notes get neither`() {
        assertTrue(NoteAssistRules.wantsHeading(untitled))
        assertFalse(NoteAssistRules.wantsHeading(untitled.copy(title = "Shopping")))
        assertFalse(NoteAssistRules.wantsHeading(untitled.copy(body = "milk eggs")))
        assertTrue(NoteAssistRules.wantsSummary(long, 250))
        assertFalse(NoteAssistRules.wantsSummary(long, 400))
        assertFalse(NoteAssistRules.wantsSummary(long.copy(locked = true), 250))
        assertFalse(NoteAssistRules.wantsHeading(untitled.copy(locked = true)))
        assertFalse(NoteAssistRules.readable(untitled.copy(deletedAt = 5L)))
    }

    @Test
    fun `the stamp follows the text`() {
        assertEquals(NoteAssistRules.stamp("a", "b"), NoteAssistRules.stamp("a", "b"))
        assertNotEquals(NoteAssistRules.stamp("a", "b"), NoteAssistRules.stamp("a", "c"))
        assertNotEquals(NoteAssistRules.stamp("ab", ""), NoteAssistRules.stamp("a", "b"))
    }

    @Test
    fun `kept answers survive a reload, go stale with an edit, and clear deletes the file`() {
        val file = NoteAssistCache.fileIn(folder.root)
        val cache = NoteAssistCache(file)
        cache.putSummary(long, "A summary.")
        cache.putHeading(untitled, "Weekend shopping")
        cache.save()

        val reread = NoteAssistCache(file)
        assertEquals("A summary.", reread.summary(long))
        assertEquals("Weekend shopping", reread.heading(untitled))
        assertNull(reread.summary(long.copy(body = long.body + " more")))
        assertNull(reread.heading(untitled.copy(body = untitled.body + "!")))

        reread.dismissHeading(untitled)
        assertTrue(reread.headingDismissed(untitled))
        assertNull(reread.heading(untitled))

        assertTrue(reread.retain(setOf(1L)))
        assertNull(reread.entry(2L))

        reread.clear()
        assertFalse(file.exists())
        assertNull(NoteAssistCache(file).summary(long))
    }
}
