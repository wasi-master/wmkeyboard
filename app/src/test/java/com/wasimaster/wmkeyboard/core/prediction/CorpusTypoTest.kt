package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A typo the downloaded word list counted as a word (#244). The Medium English
 * list holds `wheee` 60 times and `thw` 33 times, both below rank 80,000, so
 * they were known words and never corrected.
 */
class CorpusTypoTest {

    /**
     * [words] behind 60,000 commoner filler words, which is what puts them
     * where a big download has its typos: past the Small list's 50,000. The
     * filler starts with `qx`, so none of it is one edit from anything here.
     * Its counts fall off as a real list's do (count proportional to 1 /
     * rank), because ranking reads a list's spacing off its count at rank
     * 2,000 ([RankScale.anchored]); a filler all at one count put that
     * anchor two decades low and squeezed `where` against `whee`.
     */
    private fun engine(vararg words: Pair<String, Int>, filler: Int = 60_000): SuggestionEngine {
        val entries = ArrayList<Pair<String, Int>>(filler + words.size)
        for (i in 0 until filler) {
            var n = i
            val sb = StringBuilder("qx")
            repeat(4) {
                sb.append('a' + n % 26)
                n /= 26
            }
            entries.add(sb.toString() to maxOf(1_000, 22_761_659 / (i + 2)))
        }
        entries.addAll(words)
        return SuggestionEngine(PackedTrie.of(entries), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
    }

    private val common = arrayOf(
        "where" to 1_322_226, "the" to 22_761_659, "whee" to 1_401, "wheel" to 17_795,
        "dress" to 60_000,
    )

    @Test fun aRareTypoInTheListIsCorrected() {
        val e = engine(*common, "wheee" to 60, "thw" to 33)
        assertEquals("Where", e.shouldAutocorrect("Wheee"))
        assertEquals("the", e.shouldAutocorrect("thw"))
    }

    @Test fun theStripLeadsWithTheFix() {
        val e = engine(*common, "wheee" to 60)
        assertEquals("Where", e.suggest("Wheee", previousWord = null).first())
    }

    @Test fun aSpellingTheSmallListWouldHoldStaysAWord() {
        // Same counts, but only a few hundred words above it: the bundled list
        // and the Small download keep it, so it is somebody's word.
        val e = engine(*common, "wheee" to 60, filler = 100)
        assertNull(e.shouldAutocorrect("wheee"))
    }

    @Test fun aRareRealWordWithACommonNeighbourStaysAWord() {
        // "cress" is 600 times rarer than "dress", under the ratio.
        val e = engine(*common, "cress" to 100)
        assertNull(e.shouldAutocorrect("cress"))
    }

    @Test fun aWordInThePersonalDictionaryIsNeverATypo() {
        val e = engine(*common, "wheee" to 60)
        e.systemDictionary = PackedTrie.of(listOf("wheee" to 1))
        assertNull(e.shouldAutocorrect("wheee"))
    }
}
