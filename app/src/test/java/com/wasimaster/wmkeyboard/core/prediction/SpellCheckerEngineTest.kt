package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine with a [WordChecker] (Hunspell over an imported `.aff`/`.dic`):
 * a word it accepts is never corrected away, its spellings join the strip on
 * the background pass, and without one nothing changes.
 */
class SpellCheckerEngineTest {

    private val german = listOf(
        "schiff" to 20_000,
        // Far under `schiff`: read on the bundled list's log line, as the walk
        // reads a list of corpus counts, two fixes 2.5x apart are a close call
        // the list alone leaves open, and the tests here need it to answer.
        "schiffe" to 80,
        "haus" to 50_000,
        "boot" to 12_000,
        "dampfer" to 2_000,
        "donau" to 3_000,
    )

    /** What a Hunspell dictionary would say, and what it was asked. */
    private class FakeChecker(
        private val accepted: Set<String>,
        private val spellings: Map<String, List<String>> = emptyMap(),
    ) : WordChecker {
        val asked = ArrayList<String>()

        override fun accepts(word: String): Boolean = word in accepted

        override fun suggestions(word: String, limit: Int): List<String> {
            asked += word
            return spellings[word].orEmpty().take(limit)
        }
    }

    /** German as an imported list with an empty primary, the way the keyboard wires it. */
    private fun germanOnly(checker: WordChecker = WordChecker.NONE): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.of(emptyList()), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.englishSources = false
        e.customDictionary = PackedTrie.of(german)
        e.primaryLanguageId = "de"
        e.wordChecker = checker
        return e
    }

    @Test
    fun aWordTheCheckerAcceptsIsNeverCorrectedAway() {
        // "schiffs" is one letter from two listed words; the list alone has
        // something to say about it.
        val bare = germanOnly().decideCorrection("schiffs")
        assertTrue(bare.apply != null || bare.offer != null)
        val checked = germanOnly(FakeChecker(setOf("schiffs"))).decideCorrection("schiffs")
        assertNull(checked.apply)
        assertNull(checked.offer)
    }

    @Test
    fun aWordTheCheckerAcceptsGetsNoCorrectionsOnTheStrip() {
        assertTrue("schiff" in germanOnly().suggest("schiffs", previousWord = null))
        val strip = germanOnly(FakeChecker(setOf("schiffs"))).suggest("schiffs", previousWord = null)
        assertFalse("schiff" in strip)
        assertFalse("schiffe" in strip)
    }

    @Test
    fun anAcceptedWordIsKnown() {
        assertFalse(germanOnly().isKnownWord("Hausboot"))
        assertTrue(germanOnly(FakeChecker(setOf("Hausboot"))).isKnownWord("Hausboot"))
    }

    @Test
    fun itsSpellingsJoinTheStripOnTheBackgroundPassOnly() {
        val checker = FakeChecker(
            accepted = emptySet(),
            spellings = mapOf("Donaudampfschif" to listOf("Donaudampfschiff", "Donau dampfschif")),
        )
        val e = germanOnly(checker)
        // The quick pass on the main thread never waits for Hunspell.
        val quick = e.suggest("Donaudampfschif", previousWord = null)
        assertFalse(quick.any { it.equals("Donaudampfschiff", ignoreCase = true) })
        assertTrue(checker.asked.isEmpty())
        val full = e.suggest("Donaudampfschif", previousWord = null, allowRerank = true)
        assertEquals("Donaudampfschiff", full.first())
        // A split is offered only where splits are.
        assertFalse(full.any { ' ' in it })
        e.autocorrectSplits = true; e.stripSplits = true
        assertTrue(
            e.suggest("Donaudampfschif", previousWord = null, spellSuggestions = true)
                .any { it.equals("Donau dampfschif", ignoreCase = true) },
        )
    }

    @Test
    fun aWordStillOnItsWayToAListedOneIsNotAskedAbout() {
        val checker = FakeChecker(emptySet(), mapOf("dampfe" to listOf("Dampfe")))
        germanOnly(checker).suggest("dampfe", previousWord = null, spellSuggestions = true)
        assertTrue(checker.asked.isEmpty())
    }

    @Test
    fun withoutACheckerNothingChanges() {
        val none = germanOnly()
        val empty = germanOnly(FakeChecker(emptySet()))
        assertEquals(none.decideCorrection("schiffs"), empty.decideCorrection("schiffs"))
        assertEquals(
            none.suggest("schiffs", previousWord = null, allowRerank = true),
            empty.suggest("schiffs", previousWord = null, allowRerank = true),
        )
    }
}
