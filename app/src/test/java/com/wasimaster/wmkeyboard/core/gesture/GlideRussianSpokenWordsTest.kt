package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.AospScores
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A clean `привет` stroke on ЙЦУКЕН glided as `пример` (Play reviews of
 * 0.6.5). Not the grid and not the capital: the stroke is the word's own, and
 * the decoder folds case. It was the list. AOSP's Russian list was counted on
 * written text and rates `привет` 81 (rank 72,065) against `пример` 128 and
 * `примет` 104, which on the app's count scale ([AospScores.listCount]) is
 * 3.8 and 1.9 nats in their favour: more than the shape term charges either
 * of them for skipping the detour to `в`, since `м` lies on the way there and
 * `е`→`т` crosses `р`. The data repo's `ru_aosp_v2` lifts spoken Russian from
 * the counted list the way `de_aosp_v2` did German, and `привет` sits at 123.
 */
class GlideRussianSpokenWordsTest {

    // ЙЦУКЕН as BuiltInLayouts draws it: eleven keys on the top two rows,
    // shift and delete a key and a half each round the nine of the third, every
    // row stretched to the same 660 px. Keys are taller than wide, as on a phone.
    private val keyWidth = 60f
    private val rowHeight = 84f
    private val keys: List<KeyCenter> = buildList {
        "йцукенгшщзх".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, rowHeight / 2)) }
        // ё rides е's long-press, so it shares the key.
        add(KeyCenter('ё', 30f + 4 * 60f, rowHeight / 2))
        "фывапролджэ".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, rowHeight * 1.5f)) }
        val third = 660f / 12f
        "ячсмитьбю".forEachIndexed { i, c ->
            add(KeyCenter(c, third * 1.5f + third * (i + 0.5f), rowHeight * 2.5f))
        }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** Straight lines through the word's key centres, densely sampled: a careful stroke. */
    private fun strokeFor(word: String): List<GesturePoint> {
        val anchors = word.map { centers.getValue(it.code) }
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

    /** The words a `привет` stroke competes with, at their AOSP ratings, `привет` at [privet]. */
    private fun lexicon(privet: Int): List<FuzzyBeamSearch.WalkSource> {
        val ratings = listOf(
            "при" to 178, "пример" to 128, "приём" to 122, "приют" to 111,
            "примет" to 104, "привёз" to 102, "привет" to privet,
        )
        val trie = Trie().apply {
            ratings.forEach { (word, rating) -> insert(word, AospScores.listCount(rating)) }
        }
        return trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    private fun decode(privet: Int): List<String> =
        GlideBeam().decode(strokeFor("привет"), grid, keyWidth, lexicon(privet), GlideWorkspace(), 4)
            .map { it.word }

    @Test
    fun `a clean привет stroke reads привет once the list rates spoken Russian`() {
        val words = decode(privet = 123)
        assertEquals(words.toString(), "привет", words.firstOrNull())
    }

    @Test
    fun `the lifted rating is what puts привет within reach of пример`() {
        // On the count scale the decoder reads, the lift takes the gap to
        // `пример` from 3.8 nats to under half of one.
        fun nats(rating: Int) = kotlin.math.ln1p(AospScores.listCount(rating).toDouble())
        assertTrue(nats(128) - nats(81) > 3.5)
        assertTrue(nats(128) - nats(123) < 0.5)
    }
}
