package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.at
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.locale
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.now
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteComingUpTest {

    private fun note(id: Long, body: String, updatedAt: Long = now, locked: Boolean = false) =
        Note(id = id, body = body, createdAt = updatedAt, updatedAt = updatedAt, locked = locked)

    @Test
    fun `notes with a reminder ahead are listed soonest first, and the rest are not (#1540)`() {
        val invoice = note(1, "Invoice due 20 Dec")
        val shopping = note(2, "Milk, eggs, bread")
        val car = note(3, "Car service next friday at 5pm")
        val index = NoteComingUp()
        val entries = index.entriesNow(listOf(invoice, shopping, car), now, zone, locale)
        assertEquals(listOf(3L, 1L), entries.map { it.note.id })
        assertEquals(at(2026, 11, 20, 17), entries.first().reminder.startMillis)
    }

    @Test
    fun `a note is read once per version, and nothing is listed until it has been`() {
        val first = note(1, "Rent due 3 Dec")
        val index = NoteComingUp()
        assertFalse(index.ready(listOf(first)))
        assertNull(index.entries(listOf(first), now, zone))
        index.fill(listOf(first), zone, locale)
        assertTrue(index.ready(listOf(first)))
        assertEquals(1, index.entries(listOf(first), now, zone)?.size)
        // Edited: the old dates no longer count until it is read again.
        val edited = first.copy(body = "Rent paid", updatedAt = now + 1)
        assertFalse(index.ready(listOf(edited)))
        index.fill(listOf(edited), zone, locale)
        assertEquals(emptyList<ComingUpEntry>(), index.entries(listOf(edited), now, zone))
    }

    @Test
    fun `locked notes are never read`() {
        val secret = note(1, "Visa interview deadline 1 Dec", locked = true)
        val index = NoteComingUp()
        assertTrue(index.ready(listOf(secret)))
        assertTrue(index.entriesNow(listOf(secret), now, zone, locale).isEmpty())
    }
}
