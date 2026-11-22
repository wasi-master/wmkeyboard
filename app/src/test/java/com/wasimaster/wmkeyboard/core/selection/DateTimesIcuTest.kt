package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlightKind
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The date words that come from the platform's ICU (`DatePhrases`), read
 * through the selection bar's parser and the notes assistant. ICU is a stub
 * in the plain JVM tests beside this one, so these run under Robolectric,
 * which runs `android.icu` for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DateTimesIcuTest {

    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")

    /** Monday 14 September 2026, noon. */
    private val now = at(2026, 9, 14, 12)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(dhaka).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun parse(text: String, locale: Locale) = DateTimes.parse(text, now, dhaka, locale)

    @Test
    fun `a selection in the locale's language or the text's script`() {
        assertEquals(at(2026, 9, 15), parse("আগামীকাল", Locale.UK)!!.startMillis)
        assertEquals(at(2026, 9, 15), parse("morgen", Locale.GERMANY)!!.startMillis)
        assertEquals(at(2026, 9, 18), parse("nächsten Freitag", Locale.GERMANY)!!.startMillis)
        assertEquals(at(2026, 9, 17), parse("dans 3 jours", Locale.FRANCE)!!.startMillis)
        // Backward and unknown words are still not a moment.
        assertNull(parse("letzten Freitag", Locale.GERMANY))
        assertNull(parse("morgen", Locale.UK))
    }

    @Test
    fun `a German note's deadline, with its time`() {
        val found = NoteHighlights.find("Abgabe bis nächsten Freitag 14:30", now, dhaka, Locale.GERMANY).single()
        // The deadline words are English and Bengali; "bis" is not one, so
        // the line is a plain date.
        assertEquals(NoteHighlightKind.DATE, found.kind)
        assertEquals("nächsten Freitag 14:30", found.text)
        assertEquals(at(2026, 9, 18, 14, 30), found.whenMillis)
        assertTrue(found.hasTime)
    }

    @Test
    fun `a Bengali note's next-day word, on an English phone`() {
        val found = NoteHighlights.find("ফর্ম জমা আগামীকাল", now, dhaka, Locale.UK).single()
        assertEquals("আগামীকাল", found.text)
        assertEquals(NoteHighlightKind.DEADLINE, found.kind)
        assertEquals(at(2026, 9, 15), found.whenMillis)
    }
}
