package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language but English gets its own first word of a sentence: the
 * corpus word pairs never recorded one, so the strip stood empty there.
 */
class SentenceOpenersAssetTest {

    private val openers by lazy {
        File("src/main/assets/dictionaries/openers.tsv").inputStream().use { SentenceOpeners.load(it) }
    }

    private fun engine(language: String): SuggestionEngine {
        val trie = Trie().apply {
            insert("je", 100)
            insert("il", 90)
            insert("the", 100)
        }
        return SuggestionEngine(
            trie, BengaliPhoneticIndex(emptyList()), UserLexicon(null),
            seedBigrams = SeedBigrams.load("<s> I 98\n<s> The 94\n".byteInputStream()),
        ).apply {
            primaryLanguageId = language
            englishSources = language == "en"
            sentenceOpeners = openers
        }
    }

    @Test
    fun anEmptyFrenchFieldOffersFrenchOpeners() {
        val strip = engine("fr").suggest("", previousWord = WordContext.SENTENCE_START, limit = 3)
        assertEquals(listOf("Je", "Il", "Elle"), strip)
    }

    @Test
    fun englishKeepsItsOwnOpeners() {
        val strip = engine("en").suggest("", previousWord = WordContext.SENTENCE_START, limit = 3)
        assertEquals(listOf("I", "The"), strip)
    }

    @Test
    fun openersAreOnlyForTheSentenceStart() {
        assertTrue(engine("fr").suggest("", previousWord = "zzz", limit = 3).isEmpty())
    }

    @Test
    fun theAssetCoversTheBigLanguagesAndNoTatoebaNames() {
        for (language in listOf("fr", "de", "es", "pt", "it", "tr", "ru", "pl", "nl", "bn", "hi", "ar")) {
            assertTrue("$language has no openers", openers[language].orEmpty().size >= 6)
        }
        assertFalse("en lives in en_bigrams.txt", "en" in openers)
        val names = setOf("tom", "mary", "sami", "layla", "टॉम", "টম", "توم", "תום")
        for ((language, words) in openers) {
            for (word in words) assertFalse("$language: $word", word.lowercase() in names)
        }
    }
}
