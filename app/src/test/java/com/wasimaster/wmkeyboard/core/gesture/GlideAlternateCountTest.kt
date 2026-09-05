package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many alternates one stroke comes back with (issue #54).
 *
 * The strip's slot count is user-settable up to six, and the glide path used to
 * ask the engine for a hardcoded four, so the last two slots after a swipe were
 * always empty however the setting was left. The decoder searches to its own
 * rerank pool whatever the caller asks for, so the number here only decides how
 * many survive the rerank: asking for more costs nothing but the ones it
 * returns.
 */
class GlideAlternateCountTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.char }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /**
     * Words that share the t-h-e-r-e stroke: same start key, same end key, and
     * a path through the same middle. Five of the eight are reachable; the
     * other three are here to show why the family has to be built this way.
     * "tree" spells its letters in an order the stroke does not visit them in,
     * and "theme" and "throne" want keys the finger never went near. The stroke anchors its first and last
     * letters to the samples where the finger went down and lifted, so a word
     * ending anywhere else is not a candidate at all however well its middle
     * fits, which is what makes a same-start-same-end family the way to get a
     * deep list out of one swipe.
     */
    private val lexicon = listOf(
        "there" to 900, "the" to 800, "three" to 700, "these" to 600,
        "tree" to 500, "thee" to 400, "theme" to 300, "throne" to 200,
    )

    private fun engine(): SuggestionEngine {
        val dictionary = Trie().apply {
            lexicon.forEach { (word, frequency) -> insert(word, frequency) }
        }
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply { englishSources = true }
    }

    /** Straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .mapNotNull { centers[it] }
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

    private fun glide(limit: Int): List<String> =
        engine().glide(gestureFor("there"), grid, keyWidth, limit = limit).map { it.word }

    /** The contract the slot count relies on: ask for n, never get more than n. */
    @Test
    fun `a stroke never answers with more words than it was asked for`() {
        for (limit in 1..8) {
            assertTrue("limit $limit returned ${glide(limit).size}", glide(limit).size <= limit)
        }
    }

    /**
     * And the part that was broken: six slots really do fill. A hardcoded four
     * capped this at four whatever the strip was set to.
     */
    /**
     * And the part that was broken. The engine always honoured its limit; the
     * bug was on the caller's side, where a hardcoded 4 meant a six-slot strip
     * could never be filled by a swipe. So what is worth pinning here is that
     * a limit above four is not silently a four: this stroke has five words in
     * it, and asking for six returns all five rather than the first four.
     */
    @Test
    fun `a limit above four is honoured rather than clamped`() {
        assertEquals(5, glide(6).size)
        assertEquals(5, glide(5).size)
        assertEquals(4, glide(4).size)
    }

    /** The leader does not move when the tail gets longer. */
    @Test
    fun `asking for more alternates does not change the word that commits`() {
        assertEquals(glide(4).first(), glide(6).first())
    }
}
