package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1541: Russian and Belarusian `е` typed for `ё` gets its dots back. */
class RussianYoTest {

    private fun engine(language: String, vararg words: Pair<String, Int>): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.englishSources = false
        e.customDictionary = PackedTrie.of(words.toList())
        e.primaryLanguageId = language
        return e
    }

    @Test fun aWordTheListOnlyWritesWithYoGetsIt() {
        val e = engine("ru", "ещё" to 5_000, "её" to 4_000, "дом" to 3_000)
        assertEquals("ещё", e.shouldAutocorrect("еще"))
        assertEquals("Ещё", e.shouldAutocorrect("Еще"))
        assertEquals("её", e.shouldAutocorrect("ее"))
        assertEquals("ещё", engine("be", "ещё" to 5_000).shouldAutocorrect("еще"))
    }

    @Test fun aWordCommonBothWaysStandsAsTyped() {
        val e = engine("ru", "все" to 9_000, "всё" to 6_000)
        assertNull(e.shouldAutocorrect("все"))
    }
}
