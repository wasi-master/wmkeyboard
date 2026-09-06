package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection macro engine.
 *
 * Two halves, and the first is the one that matters: what a selection is read
 * as. Reading a filename as a link or an invoice total as a phone number puts
 * a chip on the keyboard that dials somebody, so the "ordinary text raises
 * nothing" cases below are the point of this file rather than filler.
 */
class SelectionMacrosTest {

    private val bd = listOf("+880 1XXX-XXXXXX")

    // ---- detection ----

    @Test
    fun `plain phone number is a phone number`() {
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("01712345678"))
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("+8801712345678"))
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("(555) 123-4567"))
    }

    @Test
    fun `address is an address`() {
        assertEquals(SelectionKind.EMAIL, SelectionMacros.detect("example@gmail.com"))
        assertEquals(SelectionKind.EMAIL, SelectionMacros.detect("first.last+tag@sub.example.co.uk"))
    }

    @Test
    fun `link is a link, with or without its scheme`() {
        assertEquals(SelectionKind.URL, SelectionMacros.detect("https://example.com"))
        assertEquals(SelectionKind.URL, SelectionMacros.detect("www.example.com/page?a=1"))
        assertEquals(SelectionKind.URL, SelectionMacros.detect("example.com/page"))
    }

    @Test
    fun `ordinary text raises nothing`() {
        val text = listOf(
            "Hello there",
            "the meeting is at 3",
            // Dates and money have the digits but not the shape.
            "12/04/2025",
            "1,234.56",
            // A filename ending that is also a top-level domain somewhere.
            "report.txt",
            "notes.zip",
            // A sentence that merely contains a number is the sentence.
            "call me on 01712345678 tomorrow",
            // An address inside prose is prose.
            "write to example@gmail.com about it",
            "",
            "   ",
        )
        for (selection in text) {
            assertEquals(selection, SelectionKind.TEXT, SelectionMacros.detect(selection))
        }
    }

    @Test
    fun `several lines are never one entity`() {
        assertEquals(SelectionKind.TEXT, SelectionMacros.detect("01712345678\n01812345678"))
    }

    @Test
    fun `a mask narrows what counts as a number`() {
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("01712345678", bd))
        // Right length, wrong country: eleven digits that no Bangladeshi
        // number starts with.
        assertEquals(SelectionKind.TEXT, SelectionMacros.detect("+15551234567", bd))
        // The same run with no mask set passes on shape alone.
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("+15551234567"))
    }

    // ---- the row ----

    @Test
    fun `each kind offers its own actions`() {
        val all = SelectionMacros.configurable.toSet()
        val phone = SelectionMacros.offer(SelectionKind.PHONE, all)
        assertTrue(SelectionMacro.CALL in phone)
        assertTrue(SelectionMacro.WHATSAPP in phone)
        assertTrue(SelectionMacro.QR !in phone)

        val url = SelectionMacros.offer(SelectionKind.URL, all)
        assertEquals(SelectionMacro.OPEN, url.first())
        assertTrue(SelectionMacro.QR in url)
        assertTrue(SelectionMacro.CALL !in url)

        val email = SelectionMacros.offer(SelectionKind.EMAIL, all)
        assertEquals(SelectionMacro.EMAIL, email.first())

        val text = SelectionMacros.offer(SelectionKind.TEXT, all)
        assertEquals(listOf(SelectionMacro.COPY, SelectionMacro.SHARE), text.take(2))
        assertTrue(SelectionMacro.CALL !in text)
    }

    @Test
    fun `a macro the user switched off is never offered`() {
        val offered = SelectionMacros.offer(
            SelectionKind.PHONE,
            SelectionMacros.defaultMacros - SelectionMacro.WHATSAPP,
        )
        assertTrue(SelectionMacro.WHATSAPP !in offered)
        assertTrue(SelectionMacro.CALL in offered)
    }

    @Test
    fun `missing apps and tools take their chips away`() {
        val all = SelectionMacros.configurable.toSet()
        val phone = SelectionMacros.offer(SelectionKind.PHONE, all, whatsAppInstalled = false)
        assertTrue(SelectionMacro.WHATSAPP !in phone)
        val url = SelectionMacros.offer(SelectionKind.URL, all, qrAvailable = false)
        assertTrue(SelectionMacro.QR !in url)
    }

    @Test
    fun `format is dropped when it would do nothing`() {
        val all = SelectionMacros.configurable.toSet()
        assertTrue(SelectionMacro.FORMAT !in SelectionMacros.offer(SelectionKind.URL, all, formattable = false))
        assertTrue(SelectionMacro.FORMAT in SelectionMacros.offer(SelectionKind.URL, all))
    }

    @Test
    fun `the case ladder is never on the bar itself`() {
        for (macro in SelectionMacros.caseMacros) {
            assertTrue(macro !in SelectionMacros.configurable)
            for (kind in SelectionKind.entries) {
                assertTrue(macro !in SelectionMacros.macrosFor(kind))
            }
        }
    }

    // ---- formatting ----

    @Test
    fun `a number is rewritten in the user's own mask`() {
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("01712345678", bd))
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("+8801712345678", bd))
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("01712 345678", bd))
    }

    @Test
    fun `with no mask a number falls back to its digits`() {
        assertEquals("+8801712345678", SelectionMacros.formatPhone("+880 1712 345678", emptyList()))
        assertEquals("01712345678", SelectionMacros.formatPhone("01712-345678", emptyList()))
    }

    @Test
    fun `format returns null when it would change nothing`() {
        assertNull(SelectionMacros.format("+880 1712-345678", SelectionKind.PHONE, bd))
        assertNull(SelectionMacros.format("https://example.com/a", SelectionKind.URL))
        assertNull(SelectionMacros.format("example@gmail.com", SelectionKind.EMAIL))
        // Plain text is the case ladder's business, never this.
        assertNull(SelectionMacros.format("Hello", SelectionKind.TEXT))
    }

    @Test
    fun `a link loses its trackers and keeps everything else`() {
        assertEquals(
            "https://example.com/a?id=7#top",
            SelectionMacros.formatUrl("https://example.com/a?utm_source=x&id=7&fbclid=abc#top"),
        )
        assertEquals(
            "https://example.com/a",
            SelectionMacros.formatUrl("https://example.com/a?utm_campaign=spring"),
        )
        // A bare domain gains the scheme it was missing and nothing else.
        assertEquals("https://example.com/a", SelectionMacros.formatUrl("example.com/a"))
    }

    @Test
    fun `an address is lower-cased`() {
        assertEquals("example@gmail.com", SelectionMacros.format("Example@Gmail.COM", SelectionKind.EMAIL))
    }

    // ---- the case ladder ----

    @Test
    fun `upper and lower are exactly that`() {
        assertEquals("HELLO THERE", SelectionMacros.applyCase("Hello there", SelectionMacro.CASE_UPPER))
        assertEquals("hello there", SelectionMacros.applyCase("Hello There", SelectionMacro.CASE_LOWER))
    }

    @Test
    fun `title case capitalises each word and sentence case only the first`() {
        assertEquals(
            "The Quick Brown Fox",
            SelectionMacros.applyCase("the quick brown fox", SelectionMacro.CASE_TITLE),
        )
        assertEquals(
            "The QUICK brown fox. It ran.",
            SelectionMacros.applyCase("the QUICK brown fox. it ran.", SelectionMacro.CASE_SENTENCE),
        )
    }

    @Test
    fun `an apostrophe stays inside its word`() {
        assertEquals("Don't Stop", SelectionMacros.applyCase("don't stop", SelectionMacro.CASE_TITLE))
    }

    @Test
    fun `an acronym in mixed text survives, and a shouted selection does not`() {
        assertEquals(
            "The NASA Launch",
            SelectionMacros.applyCase("the NASA launch", SelectionMacro.CASE_TITLE),
        )
        // Every word capitals means the user is fixing shouting, not protecting
        // acronyms, so Title case has to have somewhere to go.
        assertEquals(
            "Hello There",
            SelectionMacros.applyCase("HELLO THERE", SelectionMacro.CASE_TITLE),
        )
        assertEquals(
            "Hello there",
            SelectionMacros.applyCase("HELLO THERE", SelectionMacro.CASE_SENTENCE),
        )
    }

    @Test
    fun `a case that changes nothing reports nothing`() {
        assertNull(SelectionMacros.applyCase("HELLO", SelectionMacro.CASE_UPPER))
        assertNull(SelectionMacros.applyCase("hello", SelectionMacro.CASE_LOWER))
    }

    // ---- dialling ----

    @Test
    fun `dialling adds the country code the mask names`() {
        assertEquals("8801712345678", SelectionMacros.dialDigits("01712345678", bd))
        assertEquals("15551234567", SelectionMacros.dialDigits("+1 555-123-4567", bd))
        // No mask, no country: whatever digits were selected.
        assertEquals("01712345678", SelectionMacros.dialDigits("01712-345678", emptyList()))
    }

    @Test
    fun `a link is made openable`() {
        assertEquals("https://example.com", SelectionMacros.openableUrl("example.com"))
        assertEquals("http://example.com", SelectionMacros.openableUrl("http://example.com"))
    }
}
