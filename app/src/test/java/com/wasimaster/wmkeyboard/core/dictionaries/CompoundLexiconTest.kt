package com.wasimaster.wmkeyboard.core.dictionaries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What of a frequency list counts as a compound spelling (#1014). */
class CompoundLexiconTest {

    private fun read(text: String) = CompoundLexiconDownloads.readCompounds(text.byteInputStream())

    @Test
    fun `keeps long letter-only spellings, folded`() {
        val trie = read("Kindergarten 850\ngaragentor 63\nhaus 900\nbad-word1 50\n")
        assertTrue(trie.contains("kindergarten"))
        assertTrue(trie.contains("garagentor"))
        assertFalse("too short to be two parts", trie.contains("haus"))
        assertFalse(trie.contains("bad-word1"))
    }

    @Test
    fun `stops at the words seen once`() {
        val trie = read("garagentor 2\nfragenroot 1\ngaragenbrot 1\n")
        assertTrue(trie.contains("garagentor"))
        assertFalse(trie.contains("fragenroot"))
        assertEquals(1, trie.wordCount)
    }

    @Test
    fun `only a language that joins has a use for one`() {
        assertTrue(CompoundLexiconDownloads.supports("de"))
        assertTrue(CompoundLexiconDownloads.supports("de-DE"))
        assertFalse(CompoundLexiconDownloads.supports("en"))
    }
}
