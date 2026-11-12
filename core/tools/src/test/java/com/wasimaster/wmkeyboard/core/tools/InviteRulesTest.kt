package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.invite.InviteRules
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteRulesTest {

    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long = Calendar.getInstance(zone).apply {
        clear()
        set(y, m - 1, d, h, min, 0)
    }.timeInMillis

    @Test
    fun `times carry the zone's offset`() {
        assertEquals("2026-11-20T18:30:00+06:00", InviteRules.iso(at(dhaka, 2026, 11, 20, 18, 30), dhaka))
        assertEquals("2026-11-20T12:30:00+00:00", InviteRules.iso(at(dhaka, 2026, 11, 20, 18, 30), utc))
        val ny = TimeZone.getTimeZone("America/New_York")
        assertEquals("2026-07-04T09:00:00-04:00", InviteRules.iso(at(ny, 2026, 7, 4, 9), ny))
    }

    @Test
    fun `an all-day event runs from local midnight to the end of its last day`() {
        // A two-day event: the calendar stores 21 Nov 00:00 UTC to 23 Nov 00:00 UTC.
        val s = InviteRules.span(at(utc, 2026, 11, 21), at(utc, 2026, 11, 23), allDay = true, eventZone = utc, localZone = dhaka)
        assertTrue(s.allDay)
        assertEquals("2026-11-21T00:00:00+06:00", InviteRules.iso(s.begin, s.zone))
        assertEquals("2026-11-22T23:59:00+06:00", InviteRules.iso(s.end, s.zone))
    }

    @Test
    fun `a timed event keeps its own zone`() {
        val begin = at(dhaka, 2026, 11, 20, 18)
        val s = InviteRules.span(begin, begin + 3_600_000L, allDay = false, eventZone = dhaka, localZone = utc)
        assertEquals(dhaka, s.zone)
        assertEquals(begin + 3_600_000L, s.end)
        // An end before the start is held at the start.
        assertEquals(begin, InviteRules.span(begin, begin - 1, false, dhaka, utc).end)
    }

    @Test
    fun `a place that is a link is told apart`() {
        assertTrue(InviteRules.isLink("https://meet.jit.si/our-room"))
        assertTrue(InviteRules.isLink("  http://example.org/x "))
        assertFalse(InviteRules.isLink("Café Nero, 12 Oxford Street"))
        assertFalse(InviteRules.isLink("https://"))
        assertFalse(InviteRules.isLink("https://a.b see you"))
    }

    @Test
    fun `the first link in the notes is found and its trailing punctuation dropped`() {
        assertEquals("https://meet.jit.si/abc", InviteRules.firstLink("Join at https://meet.jit.si/abc."))
        assertEquals("https://x.org/a(b", InviteRules.firstLink("(see https://x.org/a(b)"))
        assertNull(InviteRules.firstLink("no link here"))
    }

    @Test
    fun `notes become escaped paragraphs`() {
        assertEquals("<p>Bring &lt;snacks&gt; &amp; drinks<br>8pm</p><p>Gate 2</p>", InviteRules.html("Bring <snacks> & drinks\n8pm\n\n\nGate 2\n"))
        assertEquals("", InviteRules.html("   "))
    }

    @Test
    fun `slugs are short ascii with the suffix`() {
        assertEquals("cafe-night-at-zoe-s-k3x9pq", InviteRules.slug("Café night at Zoë's!", "k3x9pq"))
        assertEquals("k3x9pq", InviteRules.slug("বাংলা", "k3x9pq"))
        val long = InviteRules.slug("a".repeat(80), "k3x9pq")
        assertEquals(InviteRules.SLUG_MAX, long.length)
        assertTrue(long.endsWith("-k3x9pq"))
        assertEquals(6, InviteRules.suffix(java.util.Random(1)).length)
    }

    @Test
    fun `the message puts the link last`() {
        assertEquals("Dinner\nFri 20 Nov, 7 PM\nhttps://x.org/e/1", InviteRules.message("Dinner", "Fri 20 Nov, 7 PM", "https://x.org/e/1"))
        assertEquals("https://x.org/e/1", InviteRules.message(" ", "", "https://x.org/e/1"))
    }

    @Test
    fun `a currency is three letters or the fallback`() {
        assertEquals("BDT", InviteRules.currency(" bdt ", "EUR"))
        assertEquals("EUR", InviteRules.currency("taka", "EUR"))
        assertEquals("EUR", InviteRules.currency("", "EUR"))
    }
}
