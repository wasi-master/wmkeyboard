package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.at
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.find
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.now
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRemindersTest {

    private fun upcoming(text: String, anchor: Long = now) = NoteReminders.upcoming(find(text, anchor), now, zone)

    @Test
    fun `a deadline ahead is offered as a one-off, all day without a time`() {
        val reminder = upcoming("Application deadline 3 Dec").single()
        assertEquals(NoteHighlightKind.DEADLINE, reminder.kind)
        assertEquals(at(2026, 12, 3), reminder.startMillis)
        assertTrue(reminder.allDay)
        assertFalse(reminder.yearly)
        assertNull(reminder.rrule)
        assertEquals("Application deadline 3 Dec", reminder.title)
        assertEquals(at(2026, 12, 4), reminder.endMillis)
    }

    @Test
    fun `a deadline already past, or past the horizon, is not offered`() {
        assertTrue(upcoming("Fee due 1 Nov 2026").isEmpty())
        assertTrue(upcoming("Visa expires 1 Jun 2027").isEmpty())
    }

    @Test
    fun `a deadline today is still offered`() {
        assertEquals(1, upcoming("Pay the bill by today").size)
    }

    @Test
    fun `a birthday repeats every year from its next day, whatever year was written`() {
        val reminder = upcoming("Rahim's birthday 14 March 1990").single()
        assertTrue(reminder.yearly)
        assertEquals("FREQ=YEARLY", reminder.rrule)
        assertEquals(at(2027, 3, 14), reminder.startMillis)
    }

    @Test
    fun `a plain date asks for nothing`() {
        assertTrue(upcoming("Met Ana on 3 Dec").isEmpty())
    }

    @Test
    fun `a leap-day birthday comes round on the 28th in a year without one`() {
        assertEquals(at(2027, 2, 28), NoteReminders.nextYearly(at(2024, 2, 29), now, zone))
        assertEquals(at(2028, 2, 29), NoteReminders.nextYearly(at(2024, 2, 29), at(2027, 3, 1), zone))
    }

    @Test
    fun `reminders are soonest first, one per day, and the clips' are read against their own copy time`() {
        val texts = listOf(
            "Invoice due 20 Dec" to now,
            "Dentist appointment deadline 2 Dec" to now,
            "Invoice due 20 Dec, again" to now,
        )
        val reminders = NoteReminders.forTexts(texts, now, zone, NoteAssistTestClock.locale)
        assertEquals(listOf(at(2026, 12, 2), at(2026, 12, 20)), reminders.map { it.startMillis })
    }
}
