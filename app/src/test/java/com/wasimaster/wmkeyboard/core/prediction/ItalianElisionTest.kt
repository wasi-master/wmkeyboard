package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Italian elision typed without its apostrophe (#240).
 *
 * Counts are the real ones from the 791k `it_full` list, which was tokenised
 * *after* the apostrophe rather than at it: `l'` and `dell'` and `un'` are all
 * top-100 tokens of their own and the word after the prefix is an ordinary
 * one, so most fused spellings are either missing entirely (`lalbero`) or
 * present only as the typo they are (`lho` 170, `dessere` 36).
 */
class ItalianElisionTest {

    private fun italian(vararg extra: Pair<String, Int>): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(
            listOf(
                // The words after a prefix.
                "albero" to 13_407, "amica" to 36_883, "idea" to 130_009,
                "essere" to 582_628, "accordo" to 129_100, "arte" to 14_528,
                "inizio" to 40_562, "acqua" to 58_651, "aria" to 34_562,
                "uomo" to 216_269, "anno" to 76_410, "ora" to 559_590,
                "altro" to 292_721, "era" to 657_610, "io" to 1_147_459,
                "ho" to 1_823_350, "ha" to 1_662_783, "hai" to 1_037_387,
                "hanno" to 357_477, "è" to 2_559_257, "e" to 7_389_373,
                "una" to 2_153_925, "oro" to 19_535, "ira" to 2_957,
                "ione" to 6_142, "ire" to 97, "ono" to 446,
                // Real words that happen to split.
                "allora" to 394_873, "luna" to 17_258, "lira" to 514,
                "loro" to 278_369, "dire" to 315_462, "questione" to 23_186,
                "unione" to 4_094, "unico" to 47_910, "sono" to 2_005_178,
                "ore" to 99_000, "questore" to 282, "duomo" to 162,
                "tutto" to 704_866, "tutte" to 121_576, "tuttora" to 551,
                "niente" to 376_096, "anche" to 467_913, "dove" to 344_643,
                "come" to 1_248_657, "ce" to 159_445, "cera" to 1_793,
                // Fused misspellings the corpus caught people typing.
                "lho" to 170, "lora" to 137, "luomo" to 10, "questanno" to 86,
                "nientaltro" to 197, "tuttaltro" to 15, "anchio" to 66,
                "dessere" to 36, "daccordo" to 1_355, "mezzora" to 515,
                "dovè" to 74, "comè" to 19, "cè" to 153,
            ) + extra,
        )
        e.primaryLanguageId = "it"
        return e
    }

    @Test fun theReportedCaseAndItsFamilyAreSuggestedAndCorrected() {
        val e = italian()
        val expected = listOf(
            // The report's own example, and a list word nowhere near it.
            "lalbero" to "l'albero",
            "unamica" to "un'amica",
            "unidea" to "un'idea",
            "dellarte" to "dell'arte",
            "allinizio" to "all'inizio",
            "sullacqua" to "sull'acqua",
            "nellaria" to "nell'aria",
            "luomo" to "l'uomo",
            "lora" to "l'ora",
            // *avere* after a pronoun, which no Italian word fuses into.
            "lho" to "l'ho",
            "lhanno" to "l'hanno",
            "mha" to "m'ha",
            // The accent typed, the apostrophe left off.
            "cè" to "c'è",
            "dovè" to "dov'è",
            "comè" to "com'è",
            // Prefixes that are never words on their own.
            "questanno" to "quest'anno",
            "nientaltro" to "nient'altro",
            "tuttaltro" to "tutt'altro",
            "anchio" to "anch'io",
            "mezzora" to "mezz'ora",
            "dessere" to "d'essere",
        )
        for ((typed, elided) in expected) {
            assertEquals(typed, elided, e.elide(typed))
            assertEquals(typed, elided, e.suggest(typed, previousWord = null).first())
        }
    }

    @Test fun aRealWordThatSplitsKeepsItself() {
        val e = italian()
        // `allora` against *ora* is 1.4 to one, `luna` against *una* 125,
        // `lira` against *ira* 6, `dove` against *è* 7 — all under the 200
        // that would make the fused spelling a stand-in rather than a word.
        for (word in listOf("allora", "luna", "lira", "unione", "dove", "come", "ce")) {
            assertNull(word, e.elide(word))
            assertEquals(word, word, e.suggest(word, previousWord = null).first())
        }
    }

    @Test fun ceIsNeverRewrittenToCe() {
        // `ce` is a word of its own (`ce la faccio`): however a list counts
        // it against *è*, *c'è* is only offered behind it (#1520).
        assertTrue(Elisions.rulesFor("it")!!.isWordToo("ce"))
        val e = italian()
        assertNull(e.elide("ce"))
        assertTrue("c'è" in e.suggest("ce", previousWord = null))
    }

    @Test fun aRealWordFarCommonerThanItsRestIsNotEvenOffered() {
        val e = italian()
        // `dire` outnumbers *ire* 3,000 to one and `loro` outnumbers *oro* 14
        // to one: the split is arithmetic, not Italian.
        assertFalse("d'ire" in e.suggest("dire", previousWord = null))
        assertFalse("l'oro" in e.suggest("loro", previousWord = null))
    }

    @Test fun aKnownStandInIsCorrectedAndOneUnderTheRatioIsOnlyOffered() {
        val e = italian()
        // `cera` (wax, 1,793) against *era* (657,610) clears 200, so the
        // commoner reading wins outright — the same call French makes for
        // `cest`. `daccordo` (1,355) against *accordo* (129,100) is 95, which
        // does not, so `d'accordo` waits on the strip instead.
        assertEquals("c'era", e.elide("cera"))
        assertNull(e.elide("daccordo"))
        assertTrue("d'accordo" in e.suggest("daccordo", previousWord = null))
    }

    @Test fun theGrammarRefusesWhatItalianDoesNotElide() {
        val e = italian()
        // Italian words nearly all end in a vowel, so "does the rest look
        // like a verb" cannot be asked the way French asks it. The pronoun
        // prefixes take a listed function word and nothing else: `sono` is
        // not *s'ono* and `tuttora` is not *tutt'ora*, however the counts
        // fall.
        assertNull(e.elide("sono"))
        assertNull(e.elide("tuttora"))
        assertNull(e.elide("unico"))
        // `quest'` takes a listed collocation, not any word that follows it:
        // *questione* is a question and *questore* a police chief, not
        // `quest'ione` and `quest'ore`.
        assertNull(e.elide("questione"))
        assertNull(e.elide("questore"))
        // The one subtraction from the collocations: *duomo* is a cathedral,
        // and 1,300 times rarer than *uomo*, which is the shape the ratio
        // otherwise reads as a stand-in for `d'uomo`.
        assertNull(e.elide("duomo"))
        assertEquals("quest'uomo", e.elide("questuomo"))
        assertFalse("s'ono" in e.suggest("sono", previousWord = null))
        assertFalse("tutt'ora" in e.suggest("tuttora", previousWord = null))
    }

    @Test fun aWordTooShortToSplitIsLeftAlone() {
        val e = italian()
        for (word in listOf("una", "tutto", "niente", "anche", "tutte")) {
            assertNull(word, e.elide(word))
        }
    }

    @Test fun theTypedCaseIsKept() {
        val e = italian()
        assertEquals("L'albero", e.elide("Lalbero"))
        assertEquals("C'È", e.elide("CÈ"))
    }

    @Test fun anApostropheAlreadyTypedEndsTheQuestion() {
        val e = italian()
        assertNull(e.elide("l'albero"))
    }

    @Test fun thePrefixesEndAComposingBufferSoTheNextWordStandsAlone() {
        // The other half of #215's item 3, in Italian: an apostrophe typed
        // after "l" commits "l'" and "albero" composes as a word of its own.
        val rules = requireNotNull(Elisions.rulesFor("it"))
        for (prefix in listOf("l", "d", "un", "dell", "quest", "anch")) {
            assertTrue(prefix, rules.isPrefix(prefix))
            assertTrue(prefix, rules.endsWithElidedPrefix("$prefix'"))
        }
        assertFalse(rules.isPrefix("al"))
        assertFalse(rules.endsWithElidedPrefix("albero"))
    }
}
