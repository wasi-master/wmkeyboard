package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.at
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistTestClock.find
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteHighlightsTest {

    @Test
    fun `a date after due is a deadline, and the number on the next line is a phone`() {
        val found = find("Rent due 3 Dec\nCall the landlord on +880 1712-345678")
        assertEquals(listOf(NoteHighlightKind.DEADLINE, NoteHighlightKind.PHONE), found.map { it.kind })
        val deadline = found[0]
        assertEquals("3 Dec", deadline.text)
        assertEquals(at(2026, 12, 3), deadline.whenMillis)
        assertFalse(deadline.hasTime)
        assertEquals("Rent due 3 Dec", deadline.line)
        assertEquals("+880 1712-345678", found[1].text)
    }

    @Test
    fun `a relative day with a time reads against the anchor`() {
        // The anchor is a Friday: "next friday" is a week on.
        val found = find("Submit the form by next friday at 5pm").single()
        assertEquals(NoteHighlightKind.DEADLINE, found.kind)
        assertEquals("next friday at 5pm", found.text)
        assertEquals(at(2026, 11, 20, 17), found.whenMillis)
        assertTrue(found.hasTime)
    }

    @Test
    fun `a line naming a birthday makes its date a birthday`() {
        val found = find("Mum's birthday: 12 May").single()
        assertEquals(NoteHighlightKind.BIRTHDAY, found.kind)
        // No year written, and May is behind the anchor: next May.
        assertEquals(at(2027, 5, 12), found.whenMillis)
    }

    @Test
    fun `a date with no deadline word is a plain date, read day first in a day-first locale`() {
        val found = find("Exam 05/12/2026").single()
        assertEquals(NoteHighlightKind.DATE, found.kind)
        assertEquals(at(2026, 12, 5), found.whenMillis)
    }

    @Test
    fun `amounts come with a sign or a currency, and a bare number is not one`() {
        val found = find("Paid ৳500 and \$1,200.50, then 40 USD. Room 12.")
        assertEquals(listOf("৳500", "\$1,200.50", "40 USD"), found.filter { it.kind == NoteHighlightKind.AMOUNT }.map { it.text })
        assertTrue(found.none { it.text == "12" })
    }

    @Test
    fun `a date inside a link is the link's`() {
        val found = find("See https://example.com/2026/12/03/post for it")
        assertEquals(listOf(NoteHighlightKind.LINK), found.map { it.kind })
    }

    @Test
    fun `emails and links are picked out, and a code is not`() {
        val found = find("Write to ana@example.org or see example.com\nYour code is 482913")
        assertEquals(listOf(NoteHighlightKind.EMAIL, NoteHighlightKind.LINK), found.map { it.kind })
    }

    @Test
    fun `the same detail twice is listed once, and a blank note gives nothing`() {
        assertEquals(1, find("Call 01712345678\nOr 01712345678 again").count { it.kind == NoteHighlightKind.PHONE })
        assertTrue(find("   \n ").isEmpty())
    }
}
