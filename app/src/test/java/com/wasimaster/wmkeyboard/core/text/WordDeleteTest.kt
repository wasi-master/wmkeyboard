package com.wasimaster.wmkeyboard.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDeleteTest {

    private fun len(text: String) = WordDelete.lengthBefore(text)

    @Test
    fun `word directly before the cursor`() {
        assertEquals("world".length, len("hello world"))
    }

    @Test
    fun `trailing space goes with the word before it`() {
        assertEquals("world ".length, len("hello world "))
        assertEquals("world   ".length, len("hello world   "))
        // And the step after that takes the rest.
        assertEquals("hello".length, len("hello"))
    }

    @Test
    fun `punctuation is its own step`() {
        assertEquals("...".length, len("wait..."))
        assertEquals("wait".length, len("wait"))
        // A contraction is one word.
        assertEquals("don't".length, len("don't"))
        assertEquals("I\u2019ll".length, len("I\u2019ll"))
        // An apostrophe at a word's end is still a mark.
        assertEquals("'".length, len("dogs'"))
    }

    @Test
    fun `newlines count as whitespace`() {
        assertEquals("line\n".length, len("first line\n"))
    }

    @Test
    fun `nothing to delete`() {
        assertEquals(0, len(""))
        // Leading whitespace only: take it and stop.
        assertEquals(3, len("   "))
    }

    // ---- forward, for the forward delete key (issue #226) ----

    private fun after(text: String) = WordDelete.lengthAfter(text)

    @Test
    fun `word directly after the cursor`() {
        assertEquals("hello".length, after("hello world"))
    }

    @Test
    fun `leading space goes with the word after it`() {
        assertEquals(" world".length, after(" world"))
        assertEquals("   world".length, after("   world"))
        assertEquals("world".length, after("world"))
    }

    @Test
    fun `punctuation after the cursor is its own step`() {
        assertEquals("...".length, after("...wait"))
        assertEquals("wait".length, after("wait"))
        // A contraction is one word going forward too.
        assertEquals("don't".length, after("don't"))
    }

    @Test
    fun `newlines count as whitespace going forward`() {
        assertEquals("\nlast".length, after("\nlast line"))
    }

    @Test
    fun `nothing to delete forward`() {
        assertEquals(0, after(""))
        assertEquals(3, after("   "))
    }

    @Test
    fun `the two directions are mirrors`() {
        // The same field read from either side: a cursor at the very start
        // takes " hello" going forward, and a cursor at the very end takes
        // "world " going back. Both carry the whitespace they touch.
        assertEquals(" hello".length, after(" hello world "))
        assertEquals("world ".length, len(" hello world "))
    }

    // ---- what ends a word (#662) ----

    private val spacesOnly = WordBreaks(punctuation = false, capitals = false)
    private val capitals = WordBreaks(punctuation = true, capitals = true)

    @Test
    fun `without punctuation breaks a word runs to the last space`() {
        assertEquals("wmkeyboard.pages.dev".length, WordDelete.lengthBefore("see wmkeyboard.pages.dev", spacesOnly))
        assertEquals(" wmkeyboard.pages.dev".length, WordDelete.lengthAfter(" wmkeyboard.pages.dev x", spacesOnly))
    }

    @Test
    fun `capitals split a word`() {
        assertEquals("OS".length, WordDelete.lengthBefore("GrapheneOS", capitals))
        assertEquals("Graphene".length, WordDelete.lengthBefore("Graphene", capitals))
        assertEquals("Parser".length, WordDelete.lengthBefore("HTMLParser", capitals))
        assertEquals("HTML".length, WordDelete.lengthAfter("HTMLParser", capitals))
        assertEquals("camel".length, WordDelete.lengthAfter("camelCase", capitals))
    }

    @Test
    fun `the default still stops at punctuation and not at capitals`() {
        assertEquals("dev".length, len("wmkeyboard.pages.dev"))
        assertEquals("GrapheneOS".length, len("GrapheneOS"))
    }

    @Test
    fun `the word around the cursor follows the same breaks`() {
        val text = "go wmkeyboard.pages.dev now"
        val at = text.indexOf("pages") + 2
        assertEquals(text.indexOf("pages") until text.indexOf(".dev"), WordDelete.wordAround(text, at, WordBreaks(true, false)))
        assertEquals(text.indexOf("wm") until text.indexOf(" now"), WordDelete.wordAround(text, at, spacesOnly))
        val camel = "GrapheneOS"
        assertEquals(8 until 10, WordDelete.wordAround(camel, 9, capitals))
        assertEquals(0 until 8, WordDelete.wordAround(camel, 3, capitals))
        assertEquals(null, WordDelete.wordAround("a  b", 2, spacesOnly))
    }

    @Test
    fun `an underscore splits a word like any other mark`() {
        val url = "https://www.word1.com/wordtwo_wordthree"
        assertEquals("wordthree".length, len(url))
        assertEquals("_".length, len(url.removeSuffix("wordthree")))
        assertEquals("wordtwo".length, len(url.removeSuffix("_wordthree")))
    }

    @Test
    fun `digits split a word only when asked`() {
        val digits = WordBreaks(punctuation = true, capitals = false, digits = true)
        assertEquals("word1".length, len("word1"))
        assertEquals("1".length, WordDelete.lengthBefore("word1", digits))
        assertEquals("word".length, WordDelete.lengthBefore("word", digits))
        assertEquals("mp".length, WordDelete.lengthAfter("mp3player", digits))
        assertEquals(2 until 3, WordDelete.wordAround("mp3player", 2, digits))
        assertEquals(0 until 2, WordDelete.wordAround("mp3player", 1, digits))
        // Spaces only still honours it.
        val spaced = WordBreaks(punctuation = false, capitals = false, digits = true)
        assertEquals("1.0".length, WordDelete.lengthBefore("v1.0", spaced))
    }

    @Test
    fun `an address goes in one step when asked`() {
        val urls = WordBreaks(punctuation = true, capitals = false, addresses = true)
        assertEquals("https://example.com/path".length, WordDelete.lengthBefore("open https://example.com/path", urls))
        assertEquals("example.com/path ".length, WordDelete.lengthBefore("see example.com/path ", urls))
        assertEquals("test@pm.me".length, WordDelete.lengthBefore("mail test@pm.me", urls))
        assertEquals("https://www.word1.com/wordtwo_wordthree".length, WordDelete.lengthBefore("https://www.word1.com/wordtwo_wordthree", urls))
        // The full stop after an address is its own step, then the address.
        assertEquals(".".length, WordDelete.lengthBefore("Visit example.com.", urls))
        assertEquals("example.com".length, WordDelete.lengthBefore("Visit example.com", urls))
        // A bracket stays for the next step.
        assertEquals("https://x.io".length, WordDelete.lengthBefore("(https://x.io", urls))
        // Forward, the same.
        assertEquals(" example.com/path".length, WordDelete.lengthAfter(" example.com/path now", urls))
        assertEquals("example.com".length, WordDelete.lengthAfter("example.com. Next", urls))
    }

    @Test
    fun `prose keeps its word steps with addresses on`() {
        val urls = WordBreaks(punctuation = true, capitals = false, addresses = true)
        // A missing space after a full stop is still two words.
        assertEquals("Then".length, WordDelete.lengthBefore("The end.Then", urls))
        assertEquals(".".length, WordDelete.lengthBefore("e.g.", urls))
        assertEquals(".".length, WordDelete.lengthBefore("U.S.", urls))
        assertEquals("14".length, WordDelete.lengthBefore("pi is 3.14", urls))
        assertEquals("end".length, WordDelete.lengthAfter("end.Then", urls))
        // Off, the address splits at its marks as before.
        assertEquals("path".length, len("see example.com/path"))
    }
}
