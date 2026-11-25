package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A French elision typed without its apostrophe (#215). */
class FrenchElisionTest {

    /**
     * Counts from the French word list, which was tokenised at the apostrophe:
     * the fused misspellings are words in it and the elisions hardly are.
     * Wired as [SuggestionEngine.customDictionary] with an empty primary, the
     * way every language but English and Bengali reaches the engine.
     */
    private fun french(vararg extra: Pair<String, Int>): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(
            listOf(
                "est" to 6_942_248, "ai" to 2_437_065, "il" to 4_025_241, "ami" to 116_639,
                "une" to 2_717_481, "un" to 4_360_896, "on" to 2_756_346, "et" to 4_110_855,
                "as" to 974_673, "était" to 985_841, "arrive" to 200_000,
                "cest" to 1_383, "jai" to 1_758, "quil" to 270, "lami" to 10,
                "don" to 24_313, "lune" to 19_957, "tas" to 20_038, "jet" to 3_368,
                "sami" to 356, "dune" to 374, "etait" to 900,
                "hui" to 5, "aujourdhui" to 40, "quelquun" to 30, "à" to 4_119_959, "a" to 3_679_682,
            ) + extra,
        )
        e.primaryLanguageId = "fr"
        return e
    }

    @Test fun theReportedFourAreSuggestedAndCorrected() {
        val e = french()
        for ((typed, elided) in listOf("cest" to "c'est", "jai" to "j'ai", "lami" to "l'ami", "quil" to "qu'il")) {
            assertEquals(typed, elided, e.suggest(typed, previousWord = null).first())
            assertEquals(typed, elided, e.elide(typed))
        }
    }

    @Test fun theTypedCaseIsKept() {
        val e = french()
        assertEquals("C'est", e.elide("Cest"))
        assertEquals("J'AI", e.elide("JAI"))
    }

    @Test fun theGrammarRefusesAPrefixTheWordDoesNotTake() {
        // "et" follows no prefix; "on" follows l' and qu' but not d'.
        val e = french()
        assertNull(e.elide("jet"))
        assertNull(e.elide("don"))
        assertFalse("j'et" in e.suggest("jet", previousWord = null))
        assertFalse("d'on" in e.suggest("don", previousWord = null))
    }

    @Test fun aRealWordThatSplitsLeadsItsElisionInTheStripAndIsNotCorrected() {
        // "lune" against "une" is 136 to one, "tas" against "as" 48: both are
        // words people mean, so the strip offers the elision behind them.
        val e = french()
        val lune = e.suggest("lune", previousWord = null)
        assertEquals("lune", lune.first())
        assertTrue(lune.toString(), "l'une" in lune)
        assertNull(e.elide("lune"))
        val tas = e.suggest("tas", previousWord = null)
        assertEquals("tas", tas.first())
        assertTrue(tas.toString(), "t'as" in tas)
        assertNull(e.elide("tas"))
    }

    @Test fun aRareStandInIsCorrectedLikeAnAccentlessOne() {
        // "dune" is in the list 374 times against "une" at 2.7 million.
        assertEquals("d'une", french().elide("dune"))
    }

    @Test fun aNameIsNotReadAsAPronounAndANoun() {
        // "sami" is s + ami, but a pronoun prefix takes a verb, and "ami"
        // does not end like one.
        val e = french()
        assertNull(e.elide("sami"))
        assertFalse("s'ami" in e.suggest("sami", previousWord = null))
    }

    @Test fun anUnknownFusedSpellingReadsAsTheElisionOutright() {
        val e = french()
        assertEquals("j'arrive", e.elide("jarrive"))
        assertEquals("j'arrive", e.suggest("jarrive", previousWord = null).first())
    }

    @Test fun theWordAfterThePrefixGetsItsAccentsBack() {
        // "cetait" is c + etait, and "etait" is "était" without its accent.
        assertEquals("c'était", french().elide("cetait"))
    }

    @Test fun theLongPrefixesElideToo() {
        // "aujourdhui" is in the list 40 times and "hui" only 5: the ratio
        // says nothing, because neither is ever a word on its own.
        val e = french()
        assertEquals("aujourd'hui", e.elide("aujourdhui"))
        assertEquals("quelqu'un", e.elide("quelquun"))
        assertEquals("jusqu'à", e.elide("jusqua"))
    }

    @Test fun aWordFarCommonerThanItsTailIsNotOfferedTheSplit() {
        // "quand" is a million to an English "and" at a few thousand.
        val e = french("quand" to 1_000_000, "and" to 3_000)
        assertFalse("qu'and" in e.suggest("quand", previousWord = null))
        assertNull(e.elide("quand"))
    }

    @Test fun thePossessivesAreNeverRewritten() {
        // #1520: `ta` became *t'à* — the word after the prefix took the
        // accent of the commoner *à*, and the possessive itself, far rarer
        // than *a*, read as a stand-in. Both are words people mean.
        val e = french("ta" to 1_000, "ma" to 1_200, "tes" to 800, "es" to 900_000)
        for (typed in listOf("ta", "ma", "tes")) assertNull(typed, e.elide(typed))
        val ta = e.suggest("ta", previousWord = null)
        assertFalse(ta.toString(), "t'à" in ta)
        assertTrue(ta.toString(), "t'a" in ta)
        assertTrue(ta.toString(), ta.indexOf("ta") in 0 until ta.indexOf("t'a"))
    }

    @Test fun aPronounPrefixTakesTheVerbNotThePreposition() {
        // `ta` with no list behind it: the elision is *t'a*, never *t'à*.
        // Only qu' and its kin take *à*.
        val e = french()
        assertFalse("t'à" in e.suggest("ta", previousWord = null))
        assertEquals("jusqu'à", e.elide("jusqua"))
    }

    @Test fun aBlacklistedElisionIsNotApplied() {
        val e = french()
        e.blacklist = setOf("j'ai")
        assertNull(e.elide("jai"))
    }

    @Test fun anUndoneElisionIsNotAppliedAgain() {
        val e = french()
        e.correctionStats.recordRevert("jai", "j'ai")
        assertNull(e.elide("jai"))
        assertEquals("c'est", e.elide("cest"))
    }

    @Test fun aWordAlreadyElidedIsLeftAlone() {
        assertNull(french().elide("c'est"))
    }

    @Test fun onlyALanguageThatElidesIsRead() {
        val e = french()
        e.primaryLanguageId = "en"
        assertNull(e.elide("cest"))
        assertFalse("c'est" in e.suggest("cest", previousWord = null))
    }

    @Test fun thePrefixTestsServeTheApostropheKey() {
        val rules = Elisions.rulesFor("fr")!!
        assertTrue(rules.isPrefix("l"))
        assertTrue(rules.isPrefix("Qu"))
        assertTrue(rules.isPrefix("aujourd"))
        assertFalse(rules.isPrefix("don"))
        assertFalse(rules.isPrefix(""))
        // A buffer composed after "l'" is a word of its own; after "sil'" the
        // apostrophe belongs to whatever "sil" was.
        assertTrue(rules.endsWithElidedPrefix("je l'"))
        assertTrue(rules.endsWithElidedPrefix("qu’"))
        assertTrue(rules.endsWithElidedPrefix("L'"))
        assertFalse(rules.endsWithElidedPrefix("sil'"))
        assertFalse(rules.endsWithElidedPrefix("je l"))
        assertNull(Elisions.rulesFor("en"))
    }
}
