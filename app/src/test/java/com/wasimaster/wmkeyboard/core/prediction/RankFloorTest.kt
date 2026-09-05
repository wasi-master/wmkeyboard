package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrieWalker.frequencyAtRank] — the cutoff a glide vocabulary cap is expressed
 * as (issue #28).
 *
 * The two read-only tries have to agree on it, because which one a language is
 * reading is an accident of whether a list was downloaded: the bundled English
 * list arrives as a [MappedTrie] and an imported one as a [PackedTrie], and a
 * cap that meant different words in the two would be a setting that changes
 * meaning when a download finishes.
 */
class RankFloorTest {

    /** Ten words, frequencies 1000 down to 100, so the Nth is 1100 - 100N. */
    private val entries = (1..10).map { "word$it" to (1100 - it * 100) }

    private fun packed() = PackedTrie.of(entries)

    private fun mapped(): MappedTrie {
        val file = File.createTempFile("rankfloor", ".wmdict")
        file.deleteOnExit()
        file.outputStream().use { PackedTrieCodec.write(packed(), it) }
        return requireNotNull(MappedTrie.open(file))
    }

    private fun walkers(): List<Pair<String, TrieWalker>> =
        listOf("packed" to packed(), "mapped" to mapped())

    @Test
    fun `a rank names the frequency of that word`() {
        for ((name, trie) in walkers()) {
            assertEquals("$name rank 1", 1000, trie.frequencyAtRank(1))
            assertEquals("$name rank 3", 800, trie.frequencyAtRank(3))
            assertEquals("$name rank 9", 200, trie.frequencyAtRank(9))
        }
    }

    /** No cap is asked for, so no cap is given. */
    @Test
    fun `a rank of zero or less is no floor at all`() {
        for ((name, trie) in walkers()) {
            assertEquals("$name rank 0", 0, trie.frequencyAtRank(0))
            assertEquals("$name rank -1", 0, trie.frequencyAtRank(-1))
        }
    }

    /**
     * A cap wider than the list keeps every word. Answering the last word's
     * frequency instead would be a floor that drops nothing today and starts
     * dropping words the moment the list grows.
     */
    @Test
    fun `a rank past the end of the list is no floor either`() {
        for ((name, trie) in walkers()) {
            assertEquals("$name at the end", 0, trie.frequencyAtRank(10))
            assertEquals("$name past the end", 0, trie.frequencyAtRank(50))
        }
    }

    /**
     * Words that tie fill the ranks they span: with three 500s at ranks 2, 3
     * and 4, every one of those ranks is 500 and rank 5 is the next value down.
     */
    @Test
    fun `tied frequencies fill the ranks they span`() {
        val tied = PackedTrie.of(
            listOf("aaa" to 900, "bbb" to 500, "ccc" to 500, "ddd" to 500, "eee" to 100, "fff" to 50),
        )
        assertEquals(900, tied.frequencyAtRank(1))
        assertEquals(500, tied.frequencyAtRank(2))
        assertEquals(500, tied.frequencyAtRank(4))
        assertEquals(100, tied.frequencyAtRank(5))
    }

    /**
     * The answer is remembered, so a decode does not re-count the trie on every
     * stroke, and asking twice gives the same number rather than a stale pair of
     * one rank's question and another's answer.
     */
    @Test
    fun `repeated and alternating asks stay consistent`() {
        val trie = packed()
        repeat(3) {
            assertEquals(800, trie.frequencyAtRank(3))
            assertEquals(200, trie.frequencyAtRank(9))
        }
    }

    /** An empty trie has no words, so it has no rank to name. */
    @Test
    fun `an empty trie floors nothing`() {
        assertEquals(0, PackedTrie.EMPTY.frequencyAtRank(5))
    }

    /**
     * The personal lexicon and the curated romanized spellings keep the
     * interface default, which is how a source with no meaningful frequency
     * ranking says it must never be capped.
     */
    @Test
    fun `a mutable trie has no opinion`() {
        val trie = Trie().apply {
            insert("alpha", 900)
            insert("beta", 100)
        }
        for (walker in trie.walkers()) {
            assertEquals(0, walker.frequencyAtRank(1))
        }
    }
}
