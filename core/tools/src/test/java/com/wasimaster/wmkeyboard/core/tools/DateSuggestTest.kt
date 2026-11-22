package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DateSuggestTest {

    /** Monday, 10 August 2026 — every expectation below counts from here. */
    private val today = CalendarSystems.gregorianToJdn(2026, 8, 10)

    private fun find(text: String) = DateSuggest.find(text, today)

    @Test
    fun `tomorrow resolves to the next day`() {
        val hit = find("see you tomorrow")
        assertNotNull(hit)
        assertEquals("tomorrow", hit!!.phrase)
        assertEquals(today + 1, hit.jdn)
        assertEquals("Tue, 11 Aug", hit.display)
        assertEquals("Tue 11 Aug", hit.annotation)
    }

    @Test
    fun `day after tomorrow wins over its tail`() {
        val hit = find("day after tomorrow")
        assertEquals(today + 2, hit?.jdn)
        assertEquals("day after tomorrow", hit?.phrase)
    }

    @Test
    fun `yesterday is not a tomorrow-shaped word`() {
        // "yesterday" does not end in any known phrase; more to the point, a
        // word gluing letters onto a phrase must not match it.
        assertNull(find("blahtomorrow"))
    }

    @Test
    fun `bare weekday is the soonest future one`() {
        val hit = find("see you friday")
        assertEquals(today + 4, hit?.jdn)
        assertEquals("friday", hit?.phrase)
        assertEquals("14 Aug", hit?.annotation)
        assertEquals("Fri, 14 Aug", hit?.display)
    }

    @Test
    fun `todays own weekday means next week`() {
        assertEquals(today + 7, find("monday")?.jdn)
    }

    @Test
    fun `next friday keeps the phrase and resolves forward`() {
        val hit = find("lets meet next friday")
        assertEquals(today + 4, hit?.jdn)
        assertEquals("next friday", hit?.phrase)
    }

    @Test
    fun `guarded weekdays stay quiet`() {
        assertNull(find("last friday"))
        assertNull(find("every friday"))
        assertNull(find("since monday"))
        // The plural is a habit, not a plan.
        assertNull(find("fridays"))
    }

    @Test
    fun `short weekday forms need a prefix`() {
        // "sun", "sat" and "wed" are everyday words; bare, they stay quiet.
        assertNull(find("here comes the sun"))
        assertNull(find("we sat"))
        assertNull(find("we wed"))
        assertEquals(today + 4, find("on fri")?.jdn)
        assertEquals(today + 5, find("next sat")?.jdn)
    }

    @Test
    fun `bengali weekday and relative words resolve`() {
        // Read by DatePhrases for the script, with no language asked for. The
        // weekday names come from java.text here, since android.icu is a stub
        // on the JVM; আগামীকাল needs ICU and is in DatePhrasesTest.
        assertEquals(today + 4, find("শুক্রবার")?.jdn)
        assertEquals(today + 4, find("আগামী শুক্রবার")?.jdn)
        assertEquals(today + 2, find("পরশু")?.jdn)
        assertNull(find("আমি ভালো"))
    }

    @Test
    fun `month day still ahead stays this year`() {
        val hit = find("lets do aug 14")
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 14), hit?.jdn)
        assertEquals("aug 14", hit?.phrase)
        assertEquals("Friday", hit?.annotation)
    }

    @Test
    fun `month day already past rolls to next year`() {
        val hit = find("jan 5")
        assertEquals(CalendarSystems.gregorianToJdn(2027, 1, 5), hit?.jdn)
        assertEquals("Tue, 5 Jan 2027", hit?.display)
    }

    @Test
    fun `day-first and ordinal month forms parse`() {
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 14), find("14 aug")?.jdn)
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 14), find("14th of august")?.jdn)
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 14), find("august 14th")?.jdn)
    }

    @Test
    fun `the ordinal picks this month while it is still ahead`() {
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 15), find("the 15th")?.jdn)
        // The 5th has passed, so it means September's.
        assertEquals(CalendarSystems.gregorianToJdn(2026, 9, 5), find("the 5th")?.jdn)
    }

    @Test
    fun `the 31st skips months that lack one`() {
        // August has a 31st and it is still ahead.
        assertEquals(CalendarSystems.gregorianToJdn(2026, 8, 31), find("the 31st")?.jdn)
        // From September the next 31st is October's.
        val fromSeptember = CalendarSystems.gregorianToJdn(2026, 9, 1)
        assertEquals(
            CalendarSystems.gregorianToJdn(2026, 10, 31),
            DateSuggest.find("the 31st", fromSeptember)?.jdn,
        )
    }

    @Test
    fun `next week and next month move by their unit`() {
        assertEquals(today + 7, find("next week")?.jdn)
        assertEquals(CalendarSystems.gregorianToJdn(2026, 9, 10), find("next month")?.jdn)
    }

    @Test
    fun `next month clamps the day where the month is shorter`() {
        val jan31 = CalendarSystems.gregorianToJdn(2026, 1, 31)
        assertEquals(
            CalendarSystems.gregorianToJdn(2026, 2, 28),
            DateSuggest.find("next month", jan31)?.jdn,
        )
    }

    @Test
    fun `start offset covers only the phrase`() {
        val text = "are you free next friday"
        val hit = find(text)
        assertEquals(text.indexOf("next friday"), hit?.start)
    }

    @Test
    fun `plain prose stays quiet`() {
        assertNull(find("hello there"))
        assertNull(find("i may go"))
        // "may" the month needs a day after it to mean a date.
        assertNull(find("may"))
    }
}
