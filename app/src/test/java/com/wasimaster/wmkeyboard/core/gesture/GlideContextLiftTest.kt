package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.CandidateReranker
import com.wasimaster.wmkeyboard.core.prediction.GlidePhraseLattice
import com.wasimaster.wmkeyboard.core.prediction.NgramReranker
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import kotlin.math.ln1p
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word before a single stroke speaks in the decoder's own nats (issue
 * #1250), where it used to reorder the list by rank and take no notice of how
 * far apart the decoder had scored two readings.
 *
 * "the" against "thee", [GlideOutcomeRerankTest]'s pair: one stroke, the
 * doubling's flat charge and the two frequencies the whole gap. The measured
 * half is `glide_context_eval` in `native/wm-engine`.
 */
class GlideContextLiftTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** "to thee" is a pair the keyboard knows, 90 strong. */
    private val seeds = SeedBigrams.load("to thee 90\n".byteInputStream())
    private val pairLift = GlidePhraseLattice.BIGRAM_WEIGHT * ln1p(90.0)

    private fun engine(thee: Int, context: Boolean = true): SuggestionEngine {
        val entries = listOf("the" to 800, "thee" to thee, "to" to 900, "there" to 900, "three" to 700)
        val dictionary = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val lexicon = UserLexicon(null)
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon, seedBigrams = seeds).apply {
            englishSources = true
            reranker = if (context) {
                NgramReranker(lexicon, seeds, dictionaryFrequency = { dictionary.frequencyOf(it) })
            } else {
                CandidateReranker.NONE
            }
        }
    }

    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .mapNotNull { centers[it.code] }
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

    private fun decode(engine: SuggestionEngine, previous: String?): List<GlideBeam.Candidate> =
        engine.glide(gestureFor("the"), grid, keyWidth, limit = 8, previousWord = previous)

    private fun List<GlideBeam.Candidate>.scoreOf(word: String): Double = first { it.word == word }.score

    @Test
    fun `a known pair lifts its word by the pair's worth and no other`() {
        val engine = engine(thee = 780)
        val alone = decode(engine, previous = null)
        val after = decode(engine, previous = "to")
        assertEquals(pairLift, after.scoreOf("thee") - alone.scoreOf("thee"), 1e-9)
        assertEquals(alone.scoreOf("the"), after.scoreOf("the"), 1e-9)
    }

    @Test
    fun `it turns a close call`() {
        val engine = engine(thee = 780)
        val alone = decode(engine, previous = null)
        assertEquals("the", alone.first().word)
        assertTrue("not a close call", alone.scoreOf("the") - alone.scoreOf("thee") < pairLift)
        assertEquals("thee", decode(engine, previous = "to").first().word)
    }

    @Test
    fun `a clear reading keeps its place whatever came before`() {
        // Ten times rarer: more gap than the pair is worth. Ranked, the pair
        // was worth a place in the list and "thee" took the lead from here.
        val engine = engine(thee = 80)
        val alone = decode(engine, previous = null)
        assertTrue("not a clear reading", alone.scoreOf("the") - alone.scoreOf("thee") > pairLift)
        val after = decode(engine, previous = "to")
        assertEquals("the", after.first().word)
        // Still heard: the gap is the pair's worth narrower.
        assertEquals(
            alone.scoreOf("the") - alone.scoreOf("thee") - pairLift,
            after.scoreOf("the") - after.scoreOf("thee"),
            1e-9,
        )
    }

    @Test
    fun `nothing moves with context-aware suggestions off`() {
        val engine = engine(thee = 780, context = false)
        assertEquals(
            decode(engine, previous = null).map { it.word to it.score },
            decode(engine, previous = "to").map { it.word to it.score },
        )
    }
}
