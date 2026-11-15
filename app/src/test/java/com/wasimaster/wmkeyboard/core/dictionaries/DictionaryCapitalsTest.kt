package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SecondaryDictionary
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #481: a list's capitals are folded out of its keys and kept beside it. */
class DictionaryCapitalsTest {

    private fun fold(vararg words: String): Pair<Array<String?>, PackedTrie?> {
        val array = arrayOfNulls<String>(words.size)
        words.forEachIndexed { i, word -> array[i] = word }
        return array to DictionaryCapitals.fold(array, words.size).capitals
    }

    @Test fun capitalizedWordsAreKeyedInLowerCase() {
        val (words, _) = fold("der", "Zeit", "Haus", "USA")
        assertArrayEquals(arrayOf("der", "zeit", "haus", "usa"), words)
    }

    @Test fun theCapitalComesBackFromTheKey() {
        val (_, capitals) = fold("der", "Zeit", "USA", "iPhone", "McDonald", "Österreich")
        capitals!!
        assertEquals("Zeit", DictionaryCapitals.spelling(capitals, "zeit"))
        assertEquals("USA", DictionaryCapitals.spelling(capitals, "usa"))
        assertEquals("iPhone", DictionaryCapitals.spelling(capitals, "iphone"))
        assertEquals("McDonald", DictionaryCapitals.spelling(capitals, "mcdonald"))
        assertEquals("Österreich", DictionaryCapitals.spelling(capitals, "österreich"))
        assertNull(DictionaryCapitals.spelling(capitals, "der"))
    }

    @Test fun aWordListedBothWaysKeepsNoCapital() {
        val (_, capitals) = fold("Essen", "essen", "may", "May", "Berlin")
        capitals!!
        assertNull(DictionaryCapitals.spelling(capitals, "essen"))
        assertNull(DictionaryCapitals.spelling(capitals, "may"))
        assertEquals("Berlin", DictionaryCapitals.spelling(capitals, "berlin"))
    }

    @Test fun aLowerCaseListHasNoCapitals() {
        assertNull(fold("ich", "sie", "das").second)
    }

    private fun engine(): SuggestionEngine {
        val (words, capitals) = fold("der", "Haus", "Zeit", "essen", "Essen", "haben")
        val list = PackedTrie.of(words.map { it!! to 100 })
        return SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = false
            primaryLanguageId = "de"
            customDictionary = list
            dictionaryCapitals = mapOf("de" to capitals!!)
        }
    }

    @Test fun aNounTypedInLowerCaseCommitsWithItsCapital() {
        val engine = engine()
        assertEquals("Haus", engine.shouldAutocorrect("haus"))
        assertNull(engine.shouldAutocorrect("haben"))
        assertNull(engine.shouldAutocorrect("essen"))
        // Shifted by hand, or by the start of a sentence: already as wanted.
        assertNull(engine.shouldAutocorrect("Haus"))
    }

    @Test fun theSwitchTurnsItOff() {
        val engine = engine().apply { dictionaryCapitalsEnabled = false }
        assertNull(engine.shouldAutocorrect("haus"))
    }

    @Test fun aSecondLanguageDoesNotCapitalizeThePrimarysWords() {
        val (_, german) = fold("Gift", "Haus")
        val english = PackedTrie.of(listOf("gift" to 100, "house" to 100))
        val engine = SuggestionEngine(english, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            dictionaryCapitals = mapOf("de" to german!!)
            secondaryDictionaries = listOf(
                SecondaryDictionary("de", PackedTrie.of(listOf("gift" to 100, "haus" to 100))),
            )
        }
        assertNull(engine.shouldAutocorrect("gift"))
        assertEquals("Haus", engine.shouldAutocorrect("haus"))
    }

    /** AOSP's Polish list spells `pan` only as the acronym `PAN`; the word is lower case. */
    @Test fun aCommonWordTheListOnlySpellsInCapitalsStaysLowerCase() {
        val (folded, polish) = fold("PAN", "FBI", "dom")
        val list = PackedTrie.of(folded.filterNotNull().map { it.lowercase() to 100 })
        val engine = SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            primaryLanguageId = "pl"
            customDictionary = list
            dictionaryCapitals = mapOf("pl" to polish!!)
        }
        assertNull(engine.shouldAutocorrect("pan"))
        assertEquals("FBI", engine.shouldAutocorrect("fbi"))
        assertEquals(true, DictionaryCapitals.writtenLowerCase("pl", "pan"))
        assertEquals(false, DictionaryCapitals.writtenLowerCase("en", "pan"))
    }

    /** #517: English's bundled list is lower case, so its capitals ship beside it. */
    @Test fun theBundledCapitalsNameTheDaysForTheBundledList() {
        val english = PackedTrie.of(listOf("monday" to 100, "london" to 100, "will" to 100))
        val shipped = DictionaryCapitals.ofSpellings(sequenceOf("# comment", "Monday", "London", ""))!!
        val engine = SuggestionEngine(english, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            primaryLanguageId = "en"
            bundledCapitals = mapOf("en" to shipped)
        }
        assertEquals("Monday", engine.shouldAutocorrect("monday"))
        assertEquals("London", engine.shouldAutocorrect("london"))
        assertNull(engine.shouldAutocorrect("will"))
        engine.dictionaryCapitalsEnabled = false
        assertNull(engine.shouldAutocorrect("monday"))
    }
    private fun english(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine {
        val list = PackedTrie.of(listOf("i" to 100, "will" to 100, "the" to 100))
        val shipped = DictionaryCapitals.ofSpellings(sequenceOf("I", "I'm"))!!
        return SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), lexicon).apply {
            primaryLanguageId = "en"
            bundledCapitals = mapOf("en" to shipped)
        }
    }

    /** #620: the lone "i" is in the bundled capitals, so autocorrect writes "I". */
    @Test fun aLoneIIsCapitalized() {
        assertEquals("I", english().shouldAutocorrect("i"))
        assertNull(english().shouldAutocorrect("the"))
    }

    /** #620: adding "I" by hand pinned it, and a pinned word used to be skipped. */
    @Test fun aCapitalAddedByHandStillCommits() {
        val lexicon = UserLexicon(null).apply { addWord("I") }
        assertEquals("I", english(lexicon).shouldAutocorrect("i"))
        // A name the user added does not capitalize a word the list writes in small letters.
        lexicon.addWord("Will")
        assertNull(english(lexicon).shouldAutocorrect("will"))
        // One no list knows is the user's to spell.
        lexicon.addWord("Wasi")
        assertEquals("Wasi", english(lexicon).shouldAutocorrect("wasi"))
    }

    /** A lone "i" learned in small letters is the word as typed, not a choice to keep it small. */
    @Test fun aLoneILearnedInSmallLettersIsStillCapitalized() {
        val lexicon = UserLexicon(null).apply { learnWord("i", count = 3) }
        assertEquals("I", english(lexicon).shouldAutocorrect("i"))
    }

    /**
     * An undo holds for the run of typing that earned it. Remembered past it,
     * one undo only handicaps the capital, so it fires again and can be kept.
     */
    @Test fun oneUndoOfTheCapitalHoldsForItsRunOnly() {
        val engine = english()
        engine.rejectCorrection("i", "I")
        assertNull(engine.shouldAutocorrect("i"))
        engine.correctionStats.endFieldSession()
        assertEquals("I", engine.shouldAutocorrect("i"))
    }

    @Test fun aCapitalFromAndroidsDictionaryCommitsForAnUnlistedWord() {
        val engine = english().apply {
            systemWordCases = mapOf("wasi" to "Wasi", "will" to "Will")
        }
        assertEquals("Wasi", engine.shouldAutocorrect("wasi"))
        assertNull(engine.shouldAutocorrect("will"))
    }
}
