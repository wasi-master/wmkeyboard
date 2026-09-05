package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glide vocabulary cap (issue #28): how much of a dictionary one stroke may
 * answer with.
 *
 * The complaint behind it is that the decoder appears to get *worse* as the word
 * list gets bigger. It is not imaginary. A swipe says which keys the finger went
 * near and in what order, the score is `ln(1 + frequency)` against the alignment
 * cost, and the logarithm flattens the language model hard: a word a thousand
 * times rarer trails by 6.9 nats, which a slightly better-fitting shape erases.
 * Seventeen thousand words have no tail to lose to. A million and a half do.
 *
 * These tests fix the contract rather than the tuning: what a cap does, what it
 * refuses to touch, and that leaving it off changes nothing.
 */
class GlideVocabularyCapTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.char }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /**
     * Eight words, frequencies 800 down to 100, so rank N is word N. Every
     * frequency is under the point where the stored minifloat starts rounding,
     * which keeps the cutoffs exact and the arithmetic in the test readable.
     */
    private val lexicon = listOf(
        "hello" to 800, "world" to 700, "there" to 600, "power" to 500,
        "hollow" to 400, "yellow" to 300, "toilet" to 200, "hotel" to 100,
    )
    private val common = lexicon.take(4).map { it.first }
    private val rare = lexicon.drop(4).map { it.first }

    private val dictionary = PackedTrie.of(lexicon)
    private val workspace = GlideWorkspace()

    private fun sources(source: com.wasimaster.wmkeyboard.core.prediction.WordSource) =
        source.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }

    private fun beam(rank: Int) = GlideBeam(GlideBeam.Tuning(vocabularyRank = rank))

    private fun decode(
        word: String,
        rank: Int,
        from: List<FuzzyBeamSearch.WalkSource> = sources(dictionary),
        limit: Int = 8,
    ): List<String> =
        beam(rank).decode(gestureFor(word), grid, keyWidth, from, workspace, limit).map { it.word }

    /** Straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .map { centers.getValue(it) }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        return points
    }

    /** The reason the setting exists: a rare word is reachable until it is capped out. */
    @Test
    fun `a cap keeps a stroke out of the tail of the list`() {
        for (word in rare) {
            assertTrue("$word should decode with no cap", word in decode(word, rank = 0))
            assertFalse("$word is outside the top 4", word in decode(word, rank = 4))
        }
    }

    /** And it is a cap, not a purge: the words inside it still decode. */
    @Test
    fun `a cap leaves the common words alone`() {
        for (word in common) {
            assertTrue("$word is inside the top 4", word in decode(word, rank = 4))
        }
    }

    /** Whatever the stroke, nothing past the cap ever comes back. */
    @Test
    fun `no stroke answers with a word past the cap`() {
        for (word in lexicon.map { it.first }) {
            val capped = decode(word, rank = 4)
            assertTrue(
                "stroke for $word returned $capped",
                capped.none { it in rare },
            )
        }
    }

    /**
     * The default. A cap of 0 is not "cap at nothing", it is "no cap", and it
     * has to decode identically to a beam that never heard of the setting.
     */
    @Test
    fun `no cap decodes exactly as before`() {
        val plain = GlideBeam()
        for (word in lexicon.map { it.first }) {
            val path = gestureFor(word)
            assertEquals(
                plain.decode(path, grid, keyWidth, sources(dictionary), workspace, 8).map { it.word },
                decode(word, rank = 0),
            )
        }
    }

    /**
     * A cap wider than the list is the same thing again. The floor comes from
     * the source's own ranking, so a small imported list is never quietly
     * trimmed by a number meant for a downloaded corpus.
     */
    @Test
    fun `a cap wider than the list changes nothing`() {
        for (word in lexicon.map { it.first }) {
            assertEquals(decode(word, rank = 0), decode(word, rank = 5_000))
        }
    }

    /**
     * Words the user taught the keyboard are the last ones to throw away, and
     * their frequencies are a count of how often that person typed them rather
     * than a ranking of a vocabulary. The personal lexicon keeps the
     * [com.wasimaster.wmkeyboard.core.prediction.TrieWalker] default, so no cap
     * reaches it however low it is set.
     */
    @Test
    fun `a cap never reaches the personal lexicon`() {
        val learned = Trie().apply { insert("hotel", 1) }
        val both = sources(dictionary) + learned.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.USER)
        }
        assertTrue(
            "a learned word survives the tightest cap",
            "hotel" in decode("hotel", rank = 1, from = both),
        )
    }
}
