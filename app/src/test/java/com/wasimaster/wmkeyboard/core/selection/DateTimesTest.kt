package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DateTimesTest {

    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")
    private val now = at(2026, 9, 14, 12, 0)
    private val gb = Locale.UK

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, zone: TimeZone = dhaka): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun parse(text: String, locale: Locale = gb, zone: TimeZone = dhaka) =
        DateTimes.parse(text, now, zone, locale)

    @Test
    fun `things that are not a moment`() {
        for (text in listOf(
            "1,234.56", "12/2024", "2026", "14", "01712345678", "555-12-3456", "5 items", "14/13/2026",
            "31/02/2026", "1.2.3", "version 2.1", "call at 5", "12:60", "25:00", "17.30", "#123",
            "14 sep\n5pm", "x".repeat(65), "", "free tomorrow", "last friday",
        )) {
            assertNull(text, parse(text))
        }
    }

    @Test
    fun `dates in every shape are the same all-day event`() {
        for (text in listOf(
            "2026-09-14", "14/09/2026", "14-09-2026", "14.09.2026", "14 Sep 2026", "Sep 14", "14 September",
            "14th of September", "September 14, 2026", "১৪/০৯/২০২৬", "১৪ সেপ্টেম্বর ২০২৬", "Sep 14 2026", "today",
        )) {
            val hit = parse(text) ?: error("no hit for $text")
            assertEquals(text, at(2026, 9, 14), hit.startMillis)
            assertEquals(text, at(2026, 9, 15), hit.endMillis)
            assertTrue(text, hit.hasDate && !hit.hasTime && hit.allDay)
            assertEquals(text, "Asia/Dhaka", hit.zoneId)
            assertFalse(text, hit.zoneNamed)
        }
    }

    @Test
    fun `day and month order follows the numbers, then the locale`() {
        assertEquals(at(2026, 5, 4), parse("04/05/2026", Locale.UK)!!.startMillis)
        assertEquals(at(2026, 4, 5), parse("04/05/2026", Locale.US)!!.startMillis)
        assertTrue(parse("04/05/2026", Locale.US)!!.ambiguousOrder)
        assertEquals(at(2026, 5, 13), parse("13/05/2026", Locale.US)!!.startMillis)
        assertFalse(parse("13/05/2026", Locale.US)!!.ambiguousOrder)
        assertTrue(DateTimes.dayFirst(Locale.UK))
        assertTrue(DateTimes.dayFirst(Locale("bn", "BD")))
        assertFalse(DateTimes.dayFirst(Locale.US))
    }

    @Test
    fun `a date with a time is an hour-long event`() {
        for (text in listOf("2026-09-14T17:30", "2026-09-14 17:30", "14 Sep 2026 at 5:30 pm", "14/09/2026, 17:30", "5:30 pm 14 Sep 2026")) {
            val hit = parse(text) ?: error("no hit for $text")
            assertEquals(text, at(2026, 9, 14, 17, 30), hit.startMillis)
            assertEquals(text, at(2026, 9, 14, 18, 30), hit.endMillis)
            assertTrue(text, hit.hasDate && hit.hasTime)
        }
        assertEquals(at(2026, 9, 15, 17, 0), parse("tomorrow 5pm")!!.startMillis)
        assertEquals(at(2026, 9, 15, 17, 0), parse("tomorrow at 5pm")!!.startMillis)
    }

    @Test
    fun `a time alone is today`() {
        assertEquals(at(2026, 9, 14, 17, 0), parse("5pm")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 0), parse("5 pm")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 30), parse("5:30 pm")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 30), parse("17:30")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 30), parse("5.30 pm")!!.startMillis)
        assertEquals(at(2026, 9, 14, 0, 0), parse("12 am")!!.startMillis)
        assertEquals(at(2026, 9, 14, 9, 0), parse("সকাল ৯টা")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 0), parse("বিকাল ৫টা")!!.startMillis)
        assertEquals(at(2026, 9, 14, 20, 0), parse("রাত ৮টা")!!.startMillis)
        assertEquals(at(2026, 9, 14, 5, 30), parse("৫:৩০")!!.startMillis)
        val hit = parse("5pm")!!
        assertFalse(hit.hasDate)
        assertTrue(hit.hasTime)
    }

    @Test
    fun `a zone in the text is read in that zone`() {
        val utc = parse("5pm UTC")!!
        assertEquals("UTC", utc.zoneId)
        assertTrue(utc.zoneNamed)
        assertEquals(at(2026, 9, 14, 17, 0, TimeZone.getTimeZone("UTC")), utc.startMillis)
        assertEquals("America/New_York", parse("5pm EST")!!.zoneId)
        assertEquals(at(2026, 9, 14, 17, 30, TimeZone.getTimeZone("GMT+06:00")), parse("17:30 GMT+6")!!.startMillis)
        assertEquals(at(2026, 9, 14, 17, 30, TimeZone.getTimeZone("GMT+06:00")), parse("17:30 +0600")!!.startMillis)
        assertEquals("Asia/Dhaka", parse("5pm BST")!!.zoneId)
        assertEquals("Europe/London", parse("5pm BST", zone = TimeZone.getTimeZone("Europe/London"))!!.zoneId)
    }

    @Test
    fun `now is the moment itself, in a zone when one is named`() {
        for (text in listOf("now", "Now", "time now", "right now", "এখন")) {
            val hit = parse(text)!!
            assertEquals(text, now, hit.startMillis)
            assertTrue(hit.hasTime)
            assertFalse(hit.hasDate)
        }
        assertEquals("UTC", parse("now UTC")!!.zoneId)
        assertNull(parse("nowhere"))
    }

    @Test
    fun `phrases DateSuggest knows are whole selections too`() {
        assertEquals(at(2026, 9, 15), parse("tomorrow")!!.startMillis)
        assertEquals(at(2026, 9, 15), parse("tmr")!!.startMillis)
        assertEquals(at(2026, 9, 21), parse("next monday")!!.startMillis)
        assertEquals(at(2026, 9, 15), parse("the 15th")!!.startMillis)
        // আগামীকাল comes from ICU, which is a stub here: DateTimesIcuTest.
        assertEquals(at(2026, 9, 21), parse("সোমবার")!!.startMillis)
        assertEquals(at(2026, 9, 14), parse("আজ")!!.startMillis)
    }

    @Test
    fun `rendering in another zone`() {
        val t = at(2026, 9, 14, 17, 30)
        assertEquals("11:30 UTC", DateTimes.renderInZone(t, "UTC", "Asia/Dhaka", hasDate = false, hour24 = true, nowMillis = now))
        assertEquals("17:00 IST (Kolkata)", DateTimes.renderInZone(t, "Asia/Kolkata", "Asia/Dhaka", hasDate = false, hour24 = true, nowMillis = now))
        assertEquals("07:30 EDT (New York)", DateTimes.renderInZone(t, "America/New_York", "Asia/Dhaka", hasDate = false, hour24 = true, nowMillis = now))
        assertEquals("7:30 am EDT (New York)", DateTimes.renderInZone(t, "America/New_York", "Asia/Dhaka", hasDate = false, hour24 = false, nowMillis = now))
        assertEquals("Mon 14 Sep, 11:30 UTC", DateTimes.renderInZone(t, "UTC", "Asia/Dhaka", hasDate = true, hour24 = true, nowMillis = now))
        val early = at(2026, 9, 14, 2, 0)
        assertEquals("Sun 13 Sep, 20:00 UTC", DateTimes.renderInZone(early, "UTC", "Asia/Dhaka", hasDate = false, hour24 = true, nowMillis = now))
        val nextYear = at(2027, 1, 1, 12, 0)
        assertEquals("Fri 1 Jan 2027, 06:00 UTC", DateTimes.renderInZone(nextYear, "UTC", "Asia/Dhaka", hasDate = true, hour24 = true, nowMillis = now))
        assertEquals("GMT+6", DateTimes.zoneLabel("GMT+06:00", t))
        assertEquals("GMT+5:30", DateTimes.zoneLabel("GMT+05:30", t))
    }
}
