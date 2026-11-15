package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links that open the same page count as one in the duplicates filter (#1312). */
class ClipSameLinkTest {

    private fun key(text: String, extra: List<String> = emptyList()) = sameLinkKey(text, extra)

    @Test
    fun `a youtube video is one page however it was shared`() {
        val shorts = key("https://youtube.com/shorts/abc123?si=XYZ")
        assertEquals(shorts, key("https://www.youtube.com/shorts/abc123"))
        assertEquals(shorts, key("https://youtu.be/abc123?si=q"))
        assertEquals(shorts, key("https://m.youtube.com/watch?v=abc123&t=42"))
        assertNotEquals(shorts, key("https://youtu.be/other"))
    }

    @Test
    fun `www, the scheme, a trailing slash and trackers do not make a new page`() {
        assertEquals(key("https://www.example.com/a/?utm_source=x"), key("http://example.com/a"))
    }

    @Test
    fun `the user's own trackers come off too`() {
        assertEquals(key("https://example.com/p?ref_id=9", listOf("ref_id")), key("https://example.com/p"))
        assertNotEquals(key("https://example.com/p?ref_id=9"), key("https://example.com/p"))
    }

    @Test
    fun `text that is not one link has no page`() {
        assertNull(key("see https://example.com"))
        assertNull(key("hello"))
    }
}
