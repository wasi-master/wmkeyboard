package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTagSuggestionsTest {

    private fun note(id: Long, body: String, tags: List<String> = emptyList(), title: String = "", locked: Boolean = false) =
        Note(id = id, body = body, title = title, tags = tags, locked = locked)

    @Test
    fun `a tag the note names comes first (#1540)`() {
        val warranty = note(1, "Warranty on the new dishwasher runs to May 2029")
        val found = NoteTagSuggestions.suggest(warranty, emptyList(), listOf("Kitchen", "Dishwasher"))
        assertEquals("Dishwasher", found.first())
        assertFalse("Kitchen" in found)
    }

    @Test
    fun `the note's own telling words fill the rest, never a date word`() {
        val warranty = note(1, "Warranty on the new dishwasher runs to May 2029, renewal in January")
        val found = NoteTagSuggestions.suggest(warranty, emptyList(), emptyList())
        assertTrue(found.size <= 2)
        assertTrue(found.all { it == "Dishwasher" || it == "Warranty" || it == "Renewal" })
        assertFalse("January" in found)
    }

    @Test
    fun `notes about the same thing lend their tags`() {
        val older = note(2, "Landlord said the boiler repair is booked, landlord pays", tags = listOf("Flat"))
        val other = note(3, "Buy milk and eggs", tags = listOf("Shopping"))
        val fresh = note(1, "Call the landlord about the boiler repair")
        val found = NoteTagSuggestions.suggest(fresh, listOf(older, other, fresh), listOf("Flat", "Shopping"))
        assertTrue("Flat" in found)
        assertFalse("Shopping" in found)
    }

    @Test
    fun `a tag the note has is not offered, and a locked note gets nothing`() {
        val tagged = note(1, "New dishwasher manual", tags = listOf("dishwasher"))
        assertFalse(NoteTagSuggestions.suggest(tagged, emptyList(), listOf("Dishwasher")).any { it.equals("dishwasher", ignoreCase = true) })
        val locked = note(2, "Dishwasher code 4471", locked = true)
        assertTrue(NoteTagSuggestions.suggest(locked, emptyList(), listOf("Dishwasher")).isEmpty())
    }
}
