package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Under "Every word" a learned word wins a near tie, not a stroke that fits
 * another word clearly better (#1584): "very" written often took a clean
 * "cry" stroke.
 */
class GlideLearnedLiftTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** Straight lines through the word's key centres, densely sampled. */
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

    private fun engine(lexicon: UserLexicon): SuggestionEngine {
        val dictionary = Trie().apply {
            insert("very", 300)
            insert("cry", 300)
            insert("wary", 100)
        }
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon)
            .apply { englishSources = true }
    }

    @Test
    fun `a word written often does not take a stroke drawn cleanly for another`() {
        val lexicon = UserLexicon(null).apply { learnWord("very", count = 40, langId = "en") }
        val words = engine(lexicon).glide(gestureFor("cry"), grid, keyWidth, limit = 4).map { it.word }
        assertEquals(words.toString(), "cry", words.first())
    }

    @Test
    fun `the learned word is still read off its own stroke`() {
        val lexicon = UserLexicon(null).apply { learnWord("very", count = 40, langId = "en") }
        val words = engine(lexicon).glide(gestureFor("very"), grid, keyWidth, limit = 4).map { it.word }
        assertEquals(words.toString(), "very", words.first())
    }

    @Test
    fun `a word only the user has keeps its weight`() {
        val lexicon = UserLexicon(null).apply { learnWord("vrty", count = 5, langId = "en") }
        val words = engine(lexicon).glide(gestureFor("vrty"), grid, keyWidth, limit = 4).map { it.word }
        assertTrue(words.toString(), "vrty" in words)
    }
}
