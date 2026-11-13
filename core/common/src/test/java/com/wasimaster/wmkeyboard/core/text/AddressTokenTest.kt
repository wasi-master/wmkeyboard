package com.wasimaster.wmkeyboard.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTokenTest {

    @Test
    fun `schemes make an address`() {
        assertTrue(AddressToken.isAddress("https://example.com/path"))
        assertTrue(AddressToken.isAddress("http://x"))
        assertTrue(AddressToken.isAddress("ftp://files.example.org"))
        // The scheme alone, while the rest is still being typed.
        assertTrue(AddressToken.isAddress("https://"))
        assertTrue(AddressToken.isAddress("HTTPS://Example.COM"))
        assertTrue(AddressToken.isAddress("mailto:a"))
        // Not a scheme the rule knows.
        assertFalse(AddressToken.isAddress("note://x"))
    }

    @Test
    fun `www makes an address`() {
        assertTrue(AddressToken.isAddress("www.g"))
        assertTrue(AddressToken.isAddress("www.example"))
        assertFalse(AddressToken.isAddress("www"))
        assertFalse(AddressToken.isAddress("www."))
    }

    @Test
    fun `email shapes are addresses, mentions are not`() {
        assertTrue(AddressToken.isAddress("test@pm.me"))
        assertTrue(AddressToken.isAddress("test@pm"))
        assertTrue(AddressToken.isAddress("first.last+tag@mail.example.co.uk"))
        assertFalse(AddressToken.isAddress("@john"))
        assertFalse(AddressToken.isAddress("john@"))
        assertFalse(AddressToken.isAddress("a@b@c"))
    }

    @Test
    fun `a host with a known ending is an address`() {
        assertTrue(AddressToken.isAddress("example.com"))
        assertTrue(AddressToken.isAddress("example.com/path"))
        assertTrue(AddressToken.isAddress("example.com/a_b-c?q=1&r=2#top"))
        assertTrue(AddressToken.isAddress("wmkeyboard.pages.dev"))
        assertTrue(AddressToken.isAddress("example.co"))
        assertTrue(AddressToken.isAddress("localhost.dev:8080/x"))
        assertTrue(AddressToken.isAddress("en.wikipedia.org/wiki/~user%20page"))
    }

    @Test
    fun `endings that are words need a path`() {
        assertFalse(AddressToken.isAddress("end.it"))
        assertFalse(AddressToken.isAddress("end.me"))
        assertFalse(AddressToken.isAddress("go.to"))
        assertTrue(AddressToken.isAddress("t.me/name"))
        assertTrue(AddressToken.isAddress("example.it/"))
        assertTrue(AddressToken.isAddress("bit.it:8080"))
    }

    @Test
    fun `prose is not an address`() {
        assertFalse(AddressToken.isAddress("end.Then"))
        assertFalse(AddressToken.isAddress("end.then"))
        assertFalse(AddressToken.isAddress("e.g."))
        assertFalse(AddressToken.isAddress("e.g"))
        assertFalse(AddressToken.isAddress("i.e."))
        assertFalse(AddressToken.isAddress("U.S."))
        assertFalse(AddressToken.isAddress("U.S"))
        assertFalse(AddressToken.isAddress("U.K"))
        assertFalse(AddressToken.isAddress("3.14"))
        assertFalse(AddressToken.isAddress("10:30"))
        assertFalse(AddressToken.isAddress("v1.2.3"))
        assertFalse(AddressToken.isAddress("notes.txt"))
        assertFalse(AddressToken.isAddress("Mr.Smith"))
        assertFalse(AddressToken.isAddress("hello"))
        assertFalse(AddressToken.isAddress("and/or"))
        // The next sentence starting straight after a full stop.
        assertFalse(AddressToken.isAddress("end.Com"))
        // Spaces and brackets are never inside one.
        assertFalse(AddressToken.isAddress("example .com"))
        assertFalse(AddressToken.isAddress("(example.com)"))
        assertFalse(AddressToken.isAddress(""))
    }

    @Test
    fun `an address does not end on the full stop after it`() {
        assertFalse(AddressToken.isAddress("example.com."))
        assertFalse(AddressToken.isAddress("https://example.com?"))
        assertFalse(AddressToken.isAddress("https:"))
    }

    @Test
    fun `start of the address ending at the caret`() {
        val text = "see (https://example.com/path"
        assertEquals(text.indexOf("https"), AddressToken.startBefore(text))
        assertEquals(5, AddressToken.startBefore("mail test@pm.me"))
        assertEquals(-1, AddressToken.startBefore("Visit example.com."))
        assertEquals(6, AddressToken.startBefore("Visit example.com.", "Visit example.com".length))
        assertEquals(-1, AddressToken.startBefore("The end.Then"))
        assertEquals(-1, AddressToken.startBefore("hello "))
        assertEquals(-1, AddressToken.startBefore(""))
        assertTrue(AddressToken.endsInAddress("go to example.com/pa"))
        assertFalse(AddressToken.endsInAddress("go to example"))
    }

    @Test
    fun `a caret inside an address`() {
        assertTrue(AddressToken.caretIn("go to exam", "ple.com now"))
        assertTrue(AddressToken.caretIn("go to example.com/pa", ""))
        assertTrue(AddressToken.caretIn("https://", ""))
        assertTrue(AddressToken.caretIn("write to test@pm", ".me"))
        // In front of the address, a new word starts.
        assertFalse(AddressToken.caretIn("go to ", "example.com"))
        // Past the full stop that ends the sentence.
        assertFalse(AddressToken.caretIn("Visit example.com.", ""))
        assertFalse(AddressToken.caretIn("Visit example.com.", " Next"))
        // Prose.
        assertFalse(AddressToken.caretIn("The end.Th", "en"))
        assertFalse(AddressToken.caretIn("e.g", ". this"))
        assertFalse(AddressToken.caretIn("hello", ""))
        assertFalse(AddressToken.caretIn("", ""))
    }

    @Test
    fun `end of the address starting at the caret`() {
        assertEquals("example.com/path".length, AddressToken.endAfter("example.com/path and more"))
        // The full stop after it closes the sentence and stays out.
        assertEquals("example.com".length, AddressToken.endAfter("example.com. Next"))
        assertEquals("https://x.io".length, AddressToken.endAfter("https://x.io)"))
        assertEquals(-1, AddressToken.endAfter("end.Then more"))
        assertEquals(-1, AddressToken.endAfter("e.g. this"))
        assertEquals(-1, AddressToken.endAfter(" example.com"))
    }
}
