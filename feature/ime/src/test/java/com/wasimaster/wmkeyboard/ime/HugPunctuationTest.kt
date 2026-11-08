package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HugPunctuationTest {

    private val marks = ".,?!;:\u0964\u060C\u061B\u061F\u06D4"

    @Test
    fun `a mark after a digit belongs to the number`() {
        assertTrue(markContinuesNumber("5"))
        assertTrue(markContinuesNumber("costs 3"))
        assertTrue(markContinuesNumber("৫"))
        assertFalse(markContinuesNumber("hello"))
        assertFalse(markContinuesNumber("5 "))
        assertFalse(markContinuesNumber(""))
    }

    @Test
    fun `a full stop inside an email address belongs to the address`() {
        assertTrue(markContinuesAddress("test@pm", '.'))
        assertTrue(markContinuesAddress("write to jo.doe@mail", '.'))
        assertTrue(markContinuesAddress("jo@mail.co", '.'))
    }

    @Test
    fun `a mention, prose around an address, or another mark keeps its space`() {
        assertFalse(markContinuesAddress("thanks @john", '.'))
        assertFalse(markContinuesAddress("test@", '.'))
        assertFalse(markContinuesAddress("a@b.com and then", '.'))
        assertFalse(markContinuesAddress("mail a@b.com", ','))
        assertFalse(markContinuesAddress("hello", '.'))
        assertFalse(markContinuesAddress("", '.'))
    }

    @Test
    fun `the Arabic marks hug their words like every other mark`() {
        assertEquals(1, straySpacesBefore("كيف ", '؟', marks))
        assertEquals(1, straySpacesBefore("نعم ", '،', marks))
        assertEquals(1, straySpacesBefore("هذا ", '؛', marks))
        assertEquals(1, straySpacesBefore("ختم ", '۔', marks))
    }

    @Test
    fun `a space between a word and a mark is taken back`() {
        assertEquals(1, straySpacesBefore("Hey ", '.', marks))
        assertEquals(1, straySpacesBefore("yes ", ',', marks))
        assertEquals(2, straySpacesBefore("Hey  ", '!', marks))
    }

    @Test
    fun `a mark outside the list leaves the space alone`() {
        assertEquals(0, straySpacesBefore("Hey ", ')', marks))
        assertEquals(0, straySpacesBefore("Hey ", '.', ",;"))
    }

    @Test
    fun `no space means nothing to take`() {
        assertEquals(0, straySpacesBefore("Hey", '.', marks))
        assertEquals(0, straySpacesBefore("", '.', marks))
    }

    @Test
    fun `a line break or a run that fills the read is not a slip`() {
        assertEquals(0, straySpacesBefore("Hey\n ", '.', marks))
        assertEquals(0, straySpacesBefore("   ", '.', marks))
        assertEquals(0, straySpacesBefore(" ", '.', marks))
    }

    @Test
    fun `only a word or something that closes one may come before`() {
        assertEquals(0, straySpacesBefore("... ", '.', marks))
        assertEquals(0, straySpacesBefore("hi, ", ',', marks))
        assertEquals(1, straySpacesBefore("(hi) ", '.', marks))
        assertEquals(1, straySpacesBefore("\"hi\" ", ',', marks))
        assertEquals(1, straySpacesBefore("42 ", '.', marks))
    }

    @Test
    fun `a domain ending or a file extension closes up onto the full stop`() {
        assertEquals("com", dotSuffixJoin("google. ", "com"))
        assertEquals("txt", dotSuffixJoin("see notes. ", "txt"))
        assertEquals("uk", dotSuffixJoin("bbc.co. ", "uk"))
        assertEquals("mp4", dotSuffixJoin("clip2. ", "mp4"))
    }

    @Test
    fun `the auto capital is dropped but typed capitals stay`() {
        assertEquals("com", dotSuffixJoin("google. ", "Com"))
        assertEquals("PDF", dotSuffixJoin("scan. ", "PDF"))
    }

    @Test
    fun `sentence words and loose dots stay apart`() {
        assertNull(dotSuffixJoin("I love it. ", "It"))
        assertNull(dotSuffixJoin("Done. ", "Go"))
        assertNull(dotSuffixJoin("Done. ", "hello"))
        assertNull(dotSuffixJoin("Wait... ", "com"))
        assertNull(dotSuffixJoin("(see). ", "com"))
        assertNull(dotSuffixJoin("google.", "com"))
        assertNull(dotSuffixJoin("google, ", "com"))
        assertNull(dotSuffixJoin(". ", "com"))
    }

    // #1166: a space typed before the eyes of an emoticon was meant.

    @Test
    fun `a mouth after the eyes makes an emoticon`() {
        for (mouth in listOf(")", "(", "P", "p", "D", "-", "3", "/", "|", "*")) {
            assertTrue(mouth, hugTurnsEmoticon(';', mouth, afterSpace = false))
            assertTrue(mouth, hugTurnsEmoticon(':', mouth, afterSpace = false))
        }
    }

    @Test
    fun `other marks and other keys are no emoticon`() {
        assertFalse(hugTurnsEmoticon('.', ")", afterSpace = false))
        assertFalse(hugTurnsEmoticon(',', "D", afterSpace = false))
        assertFalse(hugTurnsEmoticon(';', "a", afterSpace = false))
        assertFalse(hugTurnsEmoticon(':', " ", afterSpace = false))
        assertFalse(hugTurnsEmoticon(':', ":)", afterSpace = false))
    }

    @Test
    fun `across the keyboard's own space only a closing mouth counts`() {
        assertTrue(hugTurnsEmoticon(';', ")", afterSpace = true))
        // `word : Do this`, `word: (see below)`: the next word, not a face.
        assertFalse(hugTurnsEmoticon(':', "D", afterSpace = true))
        assertFalse(hugTurnsEmoticon(':', "(", afterSpace = true))
        assertFalse(hugTurnsEmoticon(':', "-", afterSpace = true))
    }
}
