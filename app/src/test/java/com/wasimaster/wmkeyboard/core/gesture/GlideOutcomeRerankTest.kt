package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.GlideOutcomes
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a glide's strip picks and undos do to the next decode of the same
 * stroke (issue #52): [GlideOutcomes] applied through [SuggestionEngine.glide].
 *
 * "the" against "thee": the one stroke, since a doubled letter is one key
 * visited once, and with the frequencies close the doubling's flat charge is
 * the whole gap — a close call the user might correct.
 */
class GlideOutcomeRerankTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private val lexicon = listOf(
        "the" to 800, "thee" to 780, "there" to 900, "three" to 700, "these" to 600,
    )

    private fun engine(entries: List<Pair<String, Int>> = lexicon): SuggestionEngine {
        val dictionary = Trie().apply {
            entries.forEach { (word, frequency) -> insert(word, frequency) }
        }
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply { englishSources = true }
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

    private fun decode(engine: SuggestionEngine, deep: Boolean = false): List<GlideBeam.Candidate> =
        engine.glide(gestureFor("the"), grid, keyWidth, limit = 8, deep = deep)

    @Test
    fun `a few picks the same way turn a close call`() {
        val engine = engine()
        val base = decode(engine)
        val leader = base[0].word
        val runnerUp = base[1].word
        assertEquals(listOf("the", "thee"), listOf(leader, runnerUp))
        val gap = base[0].score - base[1].score
        assertTrue("gap $gap is not a close call", gap < GlideOutcomes.MAX_LIFT_NATS + GlideOutcomes.MAX_DROP_NATS)
        var flippedAt = -1
        for (pick in 1..4) {
            engine.glideOutcomes.observeAlternative(rejected = leader, chosen = runnerUp)
            if (decode(engine).first().word == runnerUp) {
                flippedAt = pick
                break
            }
        }
        assertTrue("$runnerUp never took the lead from $leader", flippedAt in 1..4)
    }

    @Test
    fun `the deep search is nudged the same way`() {
        val engine = engine()
        val base = decode(engine, deep = true)
        repeat(4) { engine.glideOutcomes.observeAlternative(rejected = base[0].word, chosen = base[1].word) }
        assertEquals(base[1].word, decode(engine, deep = true).first().word)
    }

    @Test
    fun `a clear reading is never overturned`() {
        // The runner-up is a thousand times rarer: several nats of gap, more
        // than the outcomes can ever swing.
        val engine = engine(listOf("the" to 800, "thee" to 1))
        val base = decode(engine)
        assertEquals("the", base[0].word)
        val rival = base[1].word
        assertTrue("gap ${base[0].score - base[1].score}", base[0].score - base[1].score > GlideOutcomes.MAX_LIFT_NATS + GlideOutcomes.MAX_DROP_NATS)
        repeat(12) { engine.glideOutcomes.observeAlternative(rejected = "the", chosen = rival) }
        assertEquals("the", decode(engine).first().word)
    }

    @Test
    fun `a rank adjustment by hand outweighs a habit`() {
        val engine = engine()
        val base = decode(engine)
        val leader = base[0].word
        val runnerUp = base[1].word
        repeat(8) { engine.glideOutcomes.observeAlternative(rejected = leader, chosen = runnerUp) }
        assertEquals(runnerUp, decode(engine).first().word)
        engine.rankOffsets = mapOf(runnerUp to -2)
        assertEquals(leader, decode(engine).first().word)
    }

    @Test
    fun `switched off the store leaves the decoder's own order`() {
        val engine = engine()
        val base = decode(engine).map { it.word }
        repeat(8) { engine.glideOutcomes.observeAlternative(rejected = base[0], chosen = base[1]) }
        engine.glideOutcomes.applied = false
        assertEquals(base, decode(engine).map { it.word })
    }
}
