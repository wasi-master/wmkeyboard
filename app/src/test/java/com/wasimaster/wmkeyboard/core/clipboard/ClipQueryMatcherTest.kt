package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipQueryMatcherTest {

    private val link = ClipItem(id = 1, text = "https://example.com/a", timestamp = 1_000, kind = ClipKind.LINK)
    private val code = ClipItem(id = 2, text = "Your code is 482913", timestamp = 1_000)
    private val note = ClipItem(id = 3, text = "Invoice for March", timestamp = 1_000)
    private val all = listOf(link, code, note)

    private fun ids(query: String, regex: Boolean) = all.filter(clipQueryMatcher(query, regex)).map { it.id }

    @Test fun `plain search is a case-insensitive substring and blank matches all`() {
        assertEquals(listOf(3L), ids("invoice", regex = false))
        assertEquals(listOf(1L, 2L, 3L), ids("  ", regex = false))
        // A pattern means nothing with the setting off.
        assertEquals(emptyList<Long>(), ids("\\d{6}", regex = false))
    }

    @Test fun `regex search finds patterns case-insensitively`() {
        assertEquals(listOf(2L), ids("\\d{6}", regex = true))
        assertEquals(listOf(2L, 3L), ids("invoice|CODE", regex = true))
    }

    @Test fun `anchors hold on to the clip's own text`() {
        assertEquals(listOf(1L), ids("^https", regex = true))
        // Joined, the link's text would be followed by its kind words, and
        // $ would never land right after it.
        assertEquals(listOf(1L), ids("/a$", regex = true))
    }

    @Test fun `an unfinished pattern searches as plain text`() {
        val paren = ClipItem(id = 4, text = "f(x", timestamp = 1_000)
        assertTrue(clipQueryMatcher("(x", regex = true)(paren))
        assertFalse(clipQueryMatcher("[0-9", regex = true)(code))
    }

    @Test fun `a pattern is not matched against the words for a clip's kind`() {
        // Every text clip used to carry "text" and "plain", so these matched all of them.
        assertEquals(emptyList<Long>(), ids("^\\w+$", regex = true))
        assertEquals(emptyList<Long>(), ids("^\\w{1,5}$", regex = true))
    }

    @Test fun `whole clip matching`() {
        val two = ClipItem(id = 5, text = "hello world", timestamp = 1_000)
        val three = ClipItem(id = 6, text = "hello big world", timestamp = 1_000)
        val clips = listOf(two, three, note)
        fun whole(query: String) = clips.filter(clipQueryMatcher(query, regex = true, whole = true)).map { it.id }
        assertEquals(listOf(5L), whole("\\w+ \\w+"))
        assertEquals(listOf(5L, 6L, 3L), clips.filter(clipQueryMatcher("\\w+ \\w+", regex = true)).map { it.id })
        assertEquals(listOf(5L), whole(".{1,11}"))
    }

    @Test fun `a count written with a space is read as one`() {
        assertEquals(listOf(2L), ids("\\d{6, 6}", regex = true))
    }

    @Test fun `combine keeps only whole matches when asked`() {
        val texts = listOf("hello world", "hello big world")
        assertEquals("hello world", ClipCombine.combine(texts, "; ", append = "\\w+ \\w+", whole = true))
        assertEquals("hello world; hello big", ClipCombine.combine(texts, "; ", append = "\\w+ \\w+"))
    }
}
