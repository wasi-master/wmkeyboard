package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopWordsTest {

    private fun trie(vararg entries: Pair<String, Int>): Trie =
        Trie().apply { for ((word, freq) in entries) insert(word, freq) }

    @Test
    fun `returns the most frequent words first`() {
        val source = trie("the" to 100, "then" to 40, "there" to 60, "a" to 90, "apple" to 5)
        assertEquals(listOf("the", "a", "there", "then"), source.topWords(4))
    }

    @Test
    fun `a prefix outranked by its own descendants is still placed by its own frequency`() {
        // "in" is a word of its own but a rarer one than "into" beneath it; the
        // subtree bound must not promote it past what it actually scores.
        val source = trie("in" to 10, "into" to 50, "inn" to 30, "on" to 20)
        assertEquals(listOf("into", "inn", "on", "in"), source.topWords(4))
    }

    @Test
    fun `the filter is applied without disturbing the order`() {
        val source = trie("Paris" to 200, "paris" to 1, "cat" to 50, "dog" to 40, "x" to 500)
        val accepted = source.topWords(10) { it.length >= 2 && it.first().isLowerCase() }
        assertEquals(listOf("cat", "dog", "paris"), accepted)
    }

    @Test
    fun `two sources merge, with a shared word appearing once`() {
        val downloaded = trie("haus" to 80, "und" to 100)
        val imported = trie("und" to 5, "auto" to 90)
        val union = CompositeWordSource.of(listOf(downloaded, imported))
        assertEquals(listOf("und", "auto", "haus"), union.topWords(5))
    }

    @Test
    fun `an empty source yields nothing`() {
        assertTrue(PackedTrie.EMPTY.topWords(10).isEmpty())
        assertTrue(trie("a" to 1).topWords(0).isEmpty())
    }
}
