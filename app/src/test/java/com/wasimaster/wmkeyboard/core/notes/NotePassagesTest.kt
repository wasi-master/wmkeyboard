package com.wasimaster.wmkeyboard.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePassagesTest {

    private fun texts(body: String, query: String, limit: Int = NotePassages.PER_NOTE_MAX) =
        NotePassages.find(body, query, limit).map { body.substring(it.start, it.end) }

    @Test
    fun `a match gives its paragraph, blank lines apart, ignoring case`() {
        val body = "First paragraph.\n\nThe quick brown\nfox jumps.\n\nLast one."
        assertEquals(listOf("The quick brown\nfox jumps."), texts(body, "FOX"))
        val hit = NotePassages.find(body, "fox").single()
        assertEquals("fox", body.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun `two matches in one paragraph give it once, and the limit holds`() {
        val body = "milk and milk\n\nmore milk\n\nstill milk\n\nmilk again"
        assertEquals(listOf("milk and milk", "more milk", "still milk"), texts(body, "milk"))
        assertEquals(listOf("milk and milk"), texts(body, "milk", limit = 1))
    }

    @Test
    fun `a blank or one-letter query finds nothing`() {
        assertTrue(NotePassages.find("a b c", " ").isEmpty())
        assertTrue(NotePassages.find("a b c", "a").isEmpty())
        assertTrue(NotePassages.find("ab", "abc").isEmpty())
    }

    @Test
    fun `surrounding spaces are trimmed off the passage`() {
        assertEquals(listOf("one two"), texts("\n\n   one two   \n\n", "two"))
    }

    @Test
    fun `a long paragraph narrows to the match's line, a long line to its sentences`() {
        val filler = "x".repeat(NotePassages.MAX_CHARS)
        val lines = "$filler\nthe needle line\n$filler"
        assertEquals(listOf("the needle line"), texts(lines, "needle"))

        val sentence = "Words go here. "
        val long = sentence.repeat(60) + "Here is the needle. " + sentence.repeat(60)
        assertEquals(listOf("Here is the needle."), texts(long, "needle"))
    }

    @Test
    fun `a long line with no sentence ends stops at a word near the cap`() {
        val body = "word ".repeat(400) + "needle " + "word ".repeat(400)
        val passage = texts(body, "needle").single()
        assertTrue(passage.length <= NotePassages.MAX_CHARS)
        assertTrue(passage.contains("needle"))
        assertTrue(passage.startsWith("word"))
        assertTrue(passage.endsWith("word"))
    }

    @Test
    fun `a pattern finds its passages, ignoring case, with ^ at each line`() {
        val body = "Invoice 123456\n\nreceipt 42\n\nnothing here"
        val pattern = NotePassages.pattern("\\d{6}|^RECEIPT")!!
        assertEquals(
            listOf("Invoice 123456", "receipt 42"),
            NotePassages.find(body, pattern).map { body.substring(it.start, it.end) },
        )
        val hit = NotePassages.find(body, pattern).first()
        assertEquals("123456", body.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun `an empty match finds nothing, and a half-typed pattern does not compile`() {
        assertTrue(NotePassages.find("abc\n\ndef", NotePassages.pattern("x*")!!).isEmpty())
        assertNull(NotePassages.pattern("(htt"))
    }

    @Test
    fun `CJK full stops end a sentence with no space after them`() {
        val body = "前文。".repeat(150) + "这是针。" + "后文。".repeat(150)
        assertEquals(listOf("这是针。"), texts(body, "是针"))
    }
}
