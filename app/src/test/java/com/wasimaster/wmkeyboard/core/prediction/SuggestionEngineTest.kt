package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.UrduPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private fun engine(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine {
        val dictionary = Trie().apply {
            insert("the", 100)
            insert("they", 90)
            insert("them", 80)
            insert("hello", 70)
            insert("help", 60)
            insert("world", 50)
        }
        val bengali = BengaliPhoneticIndex(
            listOf(
                "আছি" to 6900,
                "আসি" to 2300,
                "ভালো" to 6500,
                "আমি" to 9000,
            )
        )
        return SuggestionEngine(dictionary, bengali, lexicon)
    }

    @Test fun anIndirectUndoOfANonWordIsNotBelieved() {
        // The trap this guards: autocorrect fixed "teh" to "the", the user
        // never saw it, went back to correct their own typo and typed the same
        // typo again. The settled field then looks exactly like a rejection,
        // and believing it retires the fix for a word that will now stay wrong
        // for good. "teh" is in no dictionary and no personal store, so the
        // reading that fits is a reproduced typo, not a defended spelling.
        val e = engine()
        e.rejectCorrection("teh", "the", deliberate = false)
        assertEquals(CorrectionStats.Penalty.NONE, e.correctionStats.penalty("teh", "the"))
        // A backspace on the correction itself is unambiguous and still counts.
        e.rejectCorrection("teh", "the")
        assertEquals(CorrectionStats.Penalty.BLOCKED, e.correctionStats.penalty("teh", "the"))
    }

    @Test fun anIndirectUndoOfAKnownWordIsBelieved() {
        // "them" is a real word, so the user leaving it standing where the
        // keyboard put "the" says what it looks like it says.
        val e = engine()
        e.rejectCorrection("them", "the", deliberate = false)
        assertEquals(CorrectionStats.Penalty.PENALIZED, e.correctionStats.penalty("them", "the"))
    }

    @Test fun aLearnedPersonalWordEarnsItsIndirectUndos() {
        val lexicon = UserLexicon(null)
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        // Before the lexicon knows the nickname, an indirect undo is thrown
        // away with every other unknown spelling.
        e.rejectCorrection("wasi", "was", deliberate = false)
        assertEquals(CorrectionStats.Penalty.NONE, e.correctionStats.penalty("wasi", "was"))
        // Once it has earned its place, its undos count in full.
        lexicon.learnWord("wasi", count = 10)
        e.rejectCorrection("wasi", "was", deliberate = false)
        assertEquals(CorrectionStats.Penalty.PENALIZED, e.correctionStats.penalty("wasi", "was"))
    }

    @Test fun strictBelievesEveryIndirectUndo() {
        val e = engine()
        e.correctionStats.memory = UndoMemory.STRICT
        e.rejectCorrection("teh", "the", deliberate = false)
        assertEquals(CorrectionStats.Penalty.BLOCKED, e.correctionStats.penalty("teh", "the"))
    }

    @Test fun mismatchedLanguageTagDampsLearnedWords() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("wasi", count = 10, langId = "bn_rom")
        lexicon.learnWord("wash", count = 5, langId = "en")
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        // Typing English: the bn_rom-tagged habit yields to the weaker
        // same-language word (a 2x count edge loses to the 3x damp)...
        e.primaryLanguageId = "en"
        assertEquals("wash", e.suggest("was", previousWord = null).first())
        // ...but is damped, not hidden.
        assertTrue("wasi" in e.suggest("was", previousWord = null))
        // Back under bn_rom the raw counts decide again.
        e.primaryLanguageId = "bn_rom"
        assertEquals("wasi", e.suggest("was", previousWord = null).first())
    }

    @Test fun untaggedLearnedWordsAreNeverDamped() {
        val lexicon = UserLexicon(null)
        // No langId: settings-app additions and legacy entries belong to
        // every language and must rank purely on count anywhere.
        lexicon.learnWord("wasi", count = 10)
        lexicon.learnWord("wash", count = 5, langId = "en")
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        e.primaryLanguageId = "en"
        assertEquals("wasi", e.suggest("was", previousWord = null).first())
    }

    @Test fun dictionaryWordsAreNeverLanguageDamped() {
        // A learned word the dictionary also knows is a real word of the
        // active language, whatever its tag says.
        val dictionary = Trie().apply { insert("wash", 50) }
        val lexicon = UserLexicon(null)
        lexicon.learnWord("wash", count = 10, langId = "bn_rom")
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon)
        e.primaryLanguageId = "en"
        assertEquals("wash", e.suggest("was", previousWord = null).first())
    }

    @Test fun registerPriorShiftsChatSpeak() {
        val dictionary = Trie().apply {
            insert("lol", 120)
            insert("low", 110)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // Neutral (setting off): raw frequency order.
        assertEquals("lol", e.suggest("lo", previousWord = null).first())
        // Formal field: the chat word sinks below the near-tie...
        e.register = Register.FORMAL
        val formal = e.suggest("lo", previousWord = null)
        assertEquals("low", formal.first())
        // ...but is never hidden.
        assertTrue("lol" in formal)
        // Casual field with the frequencies flipped: "lol" climbs back.
        val flipped = Trie().apply {
            insert("lol", 100)
            insert("low", 110)
        }
        val c = SuggestionEngine(flipped, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        assertEquals("low", c.suggest("lo", previousWord = null).first())
        c.register = Register.CASUAL
        assertEquals("lol", c.suggest("lo", previousWord = null).first())
    }

    @Test fun timingMultiplierScalesTheAutocorrectGate() {
        val dictionary = Trie().apply { insert("hello", 70) }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // The canonical solo correction fires at the default gate...
        assertEquals("hello", e.shouldAutocorrect("hallo"))
        // ...and at an eased (fast-burst) gate...
        assertEquals("hello", e.shouldAutocorrect("hallo", timingMultiplier = 0.5))
        // ...but deliberate typing pushes the gate past its margin.
        assertNull(e.shouldAutocorrect("hallo", timingMultiplier = 2.5))
    }

    @Test fun oovPreviousWordBackfillsNextWordsFromSkipContext() {
        val lexicon = UserLexicon(null)
        repeat(3) { lexicon.learnBigram("met", "yesterday") }
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        // "priya" was never learned: alone it predicts nothing, but with the
        // word before it known, its slot backfills from that context.
        assertTrue(e.suggest("", previousWord = "priya").isEmpty())
        val rescued = e.suggest("", previousWord = "priya", previousWord2 = "met")
        assertTrue("yesterday" in rescued)
    }

    @Test fun learnedSkipGramsFeedNextWordsBehindTheDirectFollowers() {
        // "how can someone" (#195): nothing learned follows "someone", but
        // "help" has followed "can" one word later and "how" two words later.
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("someone", "else")
        repeat(2) { lexicon.learnSkip1gram("can", "help") }
        repeat(3) { lexicon.learnSkip2gram("how", "please") }
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        val next = e.suggest("", previousWord = "someone", previousWord2 = "can", previousWord3 = "how")
        // Direct follower first, then the gappy ones, nearer first.
        assertEquals(listOf("else", "help", "please"), next)
        // Without the context words there is nothing gappy to read.
        assertEquals(listOf("else"), e.suggest("", previousWord = "someone"))
    }

    @Test fun dictionarySwapTakesEffectImmediately() {
        // The shared-walk cache must invalidate the moment a source changes.
        val e = engine()
        assertTrue("zebra" !in e.suggest("ze", previousWord = null))
        e.customDictionary = PackedTrie.of(listOf("zebra" to 50))
        assertTrue("zebra" in e.suggest("ze", previousWord = null))
    }

    @Test fun learnedWordAppearsWithoutStaleCache() {
        val lexicon = UserLexicon(null)
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        // Same composing string twice: the second call may serve from the
        // walk cache, which must have been invalidated by the learn.
        assertTrue(e.suggest("wa", previousWord = null).isEmpty())
        lexicon.learnWord("wasi", count = 5)
        assertTrue("wasi" in e.suggest("wa", previousWord = null))
    }

    @Test fun learnedBigramContextRanksCompletions() {
        // "words" wins on raw frequency; a learned "hello -> world" habit
        // restores "world" when that context is present.
        val lexicon = UserLexicon(null)
        repeat(5) { lexicon.learnBigram("hello", "world") }
        val flipped = Trie().apply {
            insert("world", 90)
            insert("words", 100)
        }
        val e = SuggestionEngine(flipped, BengaliPhoneticIndex(emptyList()), lexicon)
        assertEquals("world", e.suggest("wor", previousWord = "hello").first())
        // Without context the raw order stands.
        assertEquals("words", e.suggest("wor", previousWord = null).first())
        // The boost is capped: an enormous count cannot bury a much more
        // frequent candidate (a >4x frequency gap beats the ln(4) cap).
        val lopsided = Trie().apply {
            insert("world", 100)
            insert("words", 500)
        }
        val heavy = UserLexicon(null)
        repeat(10_000) { heavy.learnBigram("hello", "world") }
        val e2 = SuggestionEngine(lopsided, BengaliPhoneticIndex(emptyList()), heavy)
        assertEquals("words", e2.suggest("wor", previousWord = "hello").first())
    }

    @Test fun trigramContextOutranksBigramTail() {
        val lexicon = UserLexicon(null)
        // After "was" alone the user usually types "here"; after "i was"
        // specifically, "going".
        repeat(5) { lexicon.learnBigram("was", "here") }
        lexicon.learnBigram("was", "going")
        lexicon.learnTrigram("i", "was", "going")
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        assertEquals("here", e.suggest("", previousWord = "was").first())
        assertEquals(
            "going",
            e.suggest("", previousWord = "was", previousWord2 = "i").first(),
        )
    }

    @Test fun appRecencyOverlayNudgesButNeverDominates() {
        val dictionary = Trie().apply {
            insert("world", 95)
            insert("words", 100)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        assertEquals("words", e.suggest("wor", previousWord = null).first())
        // Recently typed in this app: the near-tie flips.
        e.contextWords = setOf("world")
        assertEquals("world", e.suggest("wor", previousWord = null).first())
        // A big frequency gap shrugs the nudge off.
        val lopsided = Trie().apply {
            insert("world", 50)
            insert("words", 100)
        }
        val e2 = SuggestionEngine(lopsided, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e2.contextWords = setOf("world")
        assertEquals("words", e2.suggest("wor", previousWord = null).first())
    }

    @Test fun joinCandidateGuards() {
        val dictionary = Trie().apply {
            insert("some", 5000)
            insert("thing", 3000)
            insert("something", 8000)
            insert("a", 90000)
            insert("nd", 60)
            insert("and", 50000)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // The canonical case.
        assertEquals("something", e.joinCandidate("some", "thing"))
        // "a" + "nd": the joined word must beat the RARER part by the margin,
        // and 50000 x 1.25 > 60 — it does. What must stop it is context
        // quality, so verify the guards that do apply:
        assertNull(e.joinCandidate(null, "thing"))
        assertNull(e.joinCandidate(WordContext.SENTENCE_START, "thing"))
        assertNull(e.joinCandidate("some", "t")) // too short
        assertNull(e.joinCandidate("some", "thing1")) // non-letters
        // Unknown join is never offered.
        assertNull(e.joinCandidate("thing", "some"))
        // A blacklisted join is never offered.
        e.blacklist = setOf("something")
        assertNull(e.joinCandidate("some", "thing"))
    }

    @Test fun prefixCompletion() {
        val suggestions = engine().suggest("th", previousWord = null)
        assertEquals("the", suggestions.first())
        assertTrue("they" in suggestions)
    }

    @Test fun completesContactEmails() {
        val e = engine().apply {
            contactEmails = ContactEmails.fromAddresses(listOf("john.doe@gmail.com"))
        }
        assertTrue("john.doe@gmail.com" in e.suggest("john", previousWord = null))
        // A single letter must not list the whole address book.
        assertTrue("john.doe@gmail.com" !in e.suggest("j", previousWord = null))
    }

    @Test fun capitalizationPreserved() {
        val suggestions = engine().suggest("Th", previousWord = null)
        assertEquals("The", suggestions.first())
    }

    @Test fun typoCorrection() {
        val suggestions = engine().suggest("helo", previousWord = null)
        assertTrue("hello" in suggestions || "help" in suggestions)
    }

    @Test fun nextLetterWeightsRanksLikelyContinuations() {
        // Extensions of "the": "they" → 'y' (freq 90), "them" → 'm' (freq 80).
        // "the" itself is exactly the prefix, so it contributes no next letter.
        val bias = engine().nextLetterWeights("the")
        assertEquals(setOf('y', 'm'), bias.keys)
        assertEquals(1.0f, bias.getValue('y'), 1e-4f) // top letter normalised to 1
        assertTrue(bias.getValue('m') < bias.getValue('y'))
    }

    @Test fun nextLetterWeightsEmptyForBlankOrUnknownPrefix() {
        assertTrue(engine().nextLetterWeights("").isEmpty())
        assertTrue(engine().nextLetterWeights("zzz").isEmpty())
    }

    @Test fun autocorrectFiresOnlyWhenConfident() {
        // "wprld" has exactly one plausible fix.
        assertEquals("world", engine().shouldAutocorrect("wprld"))
        assertNull(engine().shouldAutocorrect("hello")) // already correct
        assertNull(engine().shouldAutocorrect("xy")) // too short
        // "helo" is ambiguous between "hello" and "help": suggest, don't force.
        assertNull(engine().shouldAutocorrect("helo"))
    }

    @Test fun blacklistedWordNeverSuggested() {
        val e = engine().apply { blacklist = setOf("they") }
        val suggestions = e.suggest("th", previousWord = null)
        assertTrue("they" !in suggestions)
        // The rest of the completions are untouched.
        assertTrue("the" in suggestions)
        assertTrue("them" in suggestions)
    }

    @Test fun aRankOffsetLiftsAWordAboveAStrongerOne() {
        // "the" (100) outranks "them" (80) on frequency alone.
        val plain = engine().suggest("th", previousWord = null)
        assertTrue(plain.indexOf("the") < plain.indexOf("them"))
        val e = engine().apply { rankOffsets = mapOf("them" to 3) }
        val lifted = e.suggest("th", previousWord = null)
        assertTrue(lifted.indexOf("them") < lifted.indexOf("the"))
    }

    @Test fun aRankOffsetSinksAWordBelowAWeakerOne() {
        val e = engine().apply { rankOffsets = mapOf("the" to -3) }
        val sunk = e.suggest("th", previousWord = null)
        // Re-ranked, never hidden.
        assertTrue("the" in sunk)
        assertTrue(sunk.indexOf("they") < sunk.indexOf("the"))
    }

    @Test fun aRankOffsetMatchesOnKeys() {
        val e = engine().apply { rankOffsets = mapOf("them" to 3) }
        val lifted = e.suggest("Th", previousWord = null)
        assertTrue(lifted.indexOf("Them") < lifted.indexOf("The"))
    }

    @Test fun aRankOffsetNeverResurrectsABlacklistedWord() {
        val e = engine().apply {
            blacklist = setOf("they")
            rankOffsets = mapOf("they" to 10)
        }
        assertTrue("they" !in e.suggest("th", previousWord = null))
    }

    @Test fun describeReportsWhereAWordComesFrom() {
        val lexicon = UserLexicon(null).apply { addWord("Wasi") }
        val e = SuggestionEngine(
            Trie().apply { insert("hello", 70) },
            BengaliPhoneticIndex(emptyList()),
            lexicon,
        ).apply {
            blacklist = setOf("hello")
            rankOffsets = mapOf("wasi" to 2)
        }
        val hello = e.describe("Hello")
        assertEquals("hello", hello.key)
        assertEquals(70, hello.primary?.frequency)
        assertNull(hello.learned)
        assertTrue(hello.blacklisted)
        val wasi = e.describe("wasi")
        assertNull(wasi.primary)
        assertEquals(1, wasi.learned?.count)
        assertEquals("Wasi", wasi.learned?.display)
        assertTrue(wasi.learned?.casePinned == true)
        assertEquals(2, wasi.rankOffset)
        assertTrue(e.describe("zzz").unknown)
    }

    @Test fun blacklistMatchesCaseInsensitively() {
        val e = engine().apply { blacklist = setOf("they") }
        // Capitalized composing still filters the (capitalized) candidate.
        assertTrue("They" !in e.suggest("Th", previousWord = null))
    }

    @Test fun rejectedCorrectionNeverFiresAgain() {
        // Backspacing over a correction is the user saying they meant what they
        // typed; repeating it on the next space leaves them no way to type the
        // word at all.
        val e = engine()
        assertEquals("world", e.shouldAutocorrect("wprld"))
        e.rejectCorrection("wprld", "world")
        assertNull(e.shouldAutocorrect("wprld"))
        // The rejection is the pair, not the exact keystrokes: a capitalised
        // "Wprld" is the same word the user just insisted on.
        assertNull(e.shouldAutocorrect("Wprld"))
        // Only that pair — everything else still corrects.
        assertEquals("hello", e.shouldAutocorrect("hallo"))
    }

    @Test fun rejectingOnePairLeavesOtherCorrectionsOfTheSameTypoAlive() {
        // "cst" can fix to "cat" (adjacent slip) or "cut" (far). Rejecting
        // cat must not block a later cut — the old blanket-word rejection
        // left the user unable to reach any correction at all.
        val dictionary = Trie().apply {
            insert("cat", 100)
            insert("cut", 100)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        assertEquals("cat", e.shouldAutocorrect("cst"))
        e.rejectCorrection("cst", "cat")
        // cat is blocked; cut (the only remaining shape) may now fire.
        assertEquals("cut", e.shouldAutocorrect("cst"))
    }

    @Test fun adaptiveConfidenceTightensAfterManyReverts() {
        val dictionary = Trie().apply {
            insert("test", 100)
            insert("tear", 60)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // Gap test/tear on "tesr": both adjacent slips; ratio ~ln(101/61)=0.5,
        // fires only when the gate is loose.
        e.autocorrectConfidence = 1.5
        assertEquals("test", e.shouldAutocorrect("tesr"))
        // A brutal revert history: gate scales up and the same case stays quiet.
        repeat(40) { e.correctionStats.recordRevert("word$it", "fix$it") }
        assertNull(e.shouldAutocorrect("tesr"))
        // Adaptivity off: the raw slider value rules again.
        e.adaptiveConfidence = false
        assertEquals("test", e.shouldAutocorrect("tesr"))
    }

    /** test/tear on "tesr": a real but narrow gap, ratio ~ln(101/61) = 0.5. */
    private fun nearMissEngine() = SuggestionEngine(
        Trie().apply {
            insert("test", 100)
            insert("tear", 60)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    @Test fun aCandidateShortOfTheBarIsOfferedInstead() {
        val e = nearMissEngine()
        // Too tight to fire (0.5 < ln 3), loose enough to ask (0.5 > 0.35 ln 3).
        e.autocorrectConfidence = 3.0
        val decision = e.decideCorrection("tesr")
        assertNull(decision.apply)
        assertEquals("test", decision.offer)
    }

    @Test fun aCandidateThatFiresIsNotAlsoOffered() {
        val e = nearMissEngine()
        e.autocorrectConfidence = 1.5
        val decision = e.decideCorrection("tesr")
        assertEquals("test", decision.apply)
        assertNull(decision.offer)
    }

    @Test fun aCandidateFarShortOfTheBarIsNotOffered() {
        val e = nearMissEngine()
        // 0.5 is now under 0.35 ln 10 = 0.81: not even worth asking about.
        e.autocorrectConfidence = 10.0
        assertNull(e.decideCorrection("tesr").offer)
    }

    @Test fun aRejectedCorrectionIsNeverOffered() {
        val e = nearMissEngine()
        e.autocorrectConfidence = 3.0
        assertEquals("test", e.decideCorrection("tesr").offer)
        // Being told twice is worse than not being helped.
        e.rejectCorrection("tesr", "test")
        assertNull(e.decideCorrection("tesr").offer)
    }

    @Test fun aKnownWordIsNeitherCorrectedNorOffered() {
        val e = nearMissEngine()
        e.autocorrectConfidence = 3.0
        val decision = e.decideCorrection("test")
        assertNull(decision.apply)
        assertNull(decision.offer)
    }

    @Test fun theOfferCarriesTheCasingTheCommitWouldUse() {
        val e = nearMissEngine()
        e.autocorrectConfidence = 3.0
        assertEquals("Test", e.decideCorrection("Tesr").offer)
    }

    // The undo chip's gate: how remarkable a correction that fired actually
    // was, so the IME can leave the plain ones alone and offer the rest back.

    @Test fun `a word left alone is nothing to remark on`() {
        val e = nearMissEngine()
        // Offered rather than applied, so there is no correction to rate.
        e.autocorrectConfidence = 3.0
        val decision = e.decideCorrection("tesr")
        assertNull(decision.apply)
        assertEquals(0.0, decision.obviousness, 0.0)
    }

    @Test fun `a correction both sources name is as certain as it gets`() {
        val lexicon = UserLexicon(null).apply { learnWord("world", count = 5) }
        val e = SuggestionEngine(
            Trie().apply { insert("world", 50) },
            BengaliPhoneticIndex(emptyList()),
            lexicon,
        )
        val decision = e.decideCorrection("wprld")
        assertEquals("world", decision.apply)
        // The dictionary and the user's own lexicon arriving at the same word
        // independently bypasses the gate, so there is no margin to measure.
        assertEquals(1.0, decision.certainty, 0.0)
        assertTrue(decision.obviousness > 0.0)
    }

    @Test fun `a correction that scraped past the gate is the less certain one`() {
        // Same word, same slip, same gate. Only the runner-up moves: a close
        // rival leaves the winner scraping past the bar, a distant one leaves
        // it clear by a mile.
        val close = nearMissEngine().apply { autocorrectConfidence = 1.5 }
        val clear = SuggestionEngine(
            Trie().apply {
                insert("test", 100)
                insert("tear", 5)
            },
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        ).apply { autocorrectConfidence = 1.5 }
        assertEquals("test", close.decideCorrection("tesr").apply)
        assertEquals("test", clear.decideCorrection("tesr").apply)
        assertTrue(
            clear.decideCorrection("tesr").certainty >
                close.decideCorrection("tesr").certainty
        )
    }

    /** The same one-letter slip in a three-letter word and a thirteen-letter one. */
    private fun lengthEngine() = SuggestionEngine(
        Trie().apply {
            insert("cat", 100)
            insert("accommodation", 100)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    @Test fun `the same slip is less obvious buried in a long word`() {
        val e = lengthEngine()
        val short = e.decideCorrection("cst")
        val long = e.decideCorrection("accommodatiin")
        assertEquals("cat", short.apply)
        assertEquals("accommodation", long.apply)
        // You read a three-letter fix at a glance. A swapped letter eleven in
        // goes by unread, which is exactly when an undo is worth offering.
        assertTrue(long.complexity > short.complexity)
        assertTrue(long.obviousness < short.obviousness)
    }

    @Test fun `a split correction is never obvious`() {
        val e = splitEngine()
        val decision = e.decideCorrection("theworld")
        assertEquals("the world", decision.apply)
        // It changes how many words the sentence has. Nobody's finger did that.
        assertEquals(1.0, decision.complexity, 0.0)
        assertEquals(0.0, decision.obviousness, 0.0)
    }

    @Test fun blacklistedWordIsNotAnAutocorrectTarget() {
        // "wprld" would normally autocorrect to "world"; blacklisting it must
        // stop the silent replacement.
        val e = engine().apply { blacklist = setOf("world") }
        assertNull(e.shouldAutocorrect("wprld"))
    }

    @Test fun allCapsWordsAreNotCorrectedWhenSkipEnabled() {
        // "WPRLD" would correct to "world" if it were lowercase, but an
        // all-caps word is a deliberate acronym/shout by default.
        assertNull(engine().apply { skipAllCapsAutocorrect = true }.shouldAutocorrect("WPRLD"))
        // A mixed-case slip is still corrected — only all-caps is spared. The
        // leading capital carries through to the correction.
        assertEquals("World", engine().apply { skipAllCapsAutocorrect = true }.shouldAutocorrect("Wprld"))
        // Off, an all-caps word corrects like any other (case carried through).
        assertEquals("WORLD", engine().apply { skipAllCapsAutocorrect = false }.shouldAutocorrect("WPRLD"))
    }

    @Test fun autocorrectPrefersAdjacentKeySlip() {
        // Equal frequencies: "cst" fixes to "cat" because s sits next to a,
        // while u is across the keyboard.
        val dictionary = Trie().apply {
            insert("cat", 100)
            insert("cut", 100)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        assertEquals("cat", e.shouldAutocorrect("cst"))
    }

    @Test fun autocorrectStaysQuietBetweenCloseCandidates() {
        // Both candidates are one adjacent-key slip away at similar
        // frequency — no winner is confident enough to force.
        val dictionary = Trie().apply {
            insert("test", 100)
            insert("tear", 95)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        assertNull(e.shouldAutocorrect("tesr"))
    }

    @Test fun autocorrectConsensusBetweenDictionaryAndLexicon() {
        val dictionary = Trie().apply {
            insert("cat", 100)
            insert("car", 90)
        }
        val lexicon = UserLexicon(null)
        lexicon.learnWord("cat")
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon)
        assertEquals("cat", e.shouldAutocorrect("caz"))
    }

    @Test fun avroPhoneticSiblingWins() {
        val suggestions = engine().suggest("asi", previousWord = null, phoneticLanguage = "bn")
        assertEquals("আছি", suggestions.first())
        assertTrue("আসি" in suggestions)
    }

    @Test fun avroLoanwordWinsOverPhonetics() {
        val dictionary = Trie()
        val loanwords = SpellingMap.load(
            "keyboard\tকিবোর্ড\nchair\tচেয়ার\n".byteInputStream(Charsets.UTF_8)
        )
        val e = SuggestionEngine(
            dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null), loanwords,
        )
        assertEquals("কিবোর্ড", e.suggest("keyboard", null, phoneticLanguage = "bn").first())
        assertEquals("চেয়ার", e.suggest("chair", null, phoneticLanguage = "bn").first())
    }

    @Test fun avroCapitalsAreNotFoldedOntoAListedSpelling() {
        // Capital N is ণ. The map and the word list both fold case, so `moN`
        // used to come out as whatever they say for `mon`.
        val map = SpellingMap.load("mon\tমোন\n".byteInputStream(Charsets.UTF_8))
        val bengali = BengaliPhoneticIndex(listOf("মন" to 5000, "মণ" to 10))
        val e = SuggestionEngine(Trie(), bengali, UserLexicon(null), map)
        assertEquals("মোন", e.suggest("mon", null, phoneticLanguage = "bn").first())
        assertEquals("মণ", e.suggest("moN", null, phoneticLanguage = "bn").first())
        assertEquals(null, e.phoneticSpelling("bn", "moN"))
        // A capital that spells nothing of its own is the same spelling.
        assertEquals("মোন", e.suggest("Mon", null, phoneticLanguage = "bn").first())
        assertEquals("মোন", e.phoneticSpelling("bn", "Mon"))
    }

    @Test fun avroLiteralHoldsAgainstNearTieSibling() {
        // হলো is more frequent than হল, but the composing preview showed
        // হলো — when the top sibling is less than 2x more frequent, the
        // literal (what the user sees while typing) survives.
        val bengali = BengaliPhoneticIndex(
            listOf("হলো" to 1986, "হল" to 1900)
        )
        val e = SuggestionEngine(Trie(), bengali, UserLexicon(null))
        val suggestions = e.suggest("holO", null, phoneticLanguage = "bn")
        assertEquals("হলো", suggestions.first())
        assertTrue("হল" in suggestions)
    }

    @Test fun avroLiteralWinsWithoutSiblings() {
        // "wasi" has no phonetic sibling in the dictionary; the literal
        // transliteration (ওয়াসি) must survive the space commit untouched.
        val e = engine()
        val literal = AvroPhonetic.transliterate("wasi")
        assertEquals(literal, e.suggest("wasi", null, phoneticLanguage = "bn").first())
    }

    @Test fun avroSentenceWords() {
        val e = engine()
        assertEquals("আমি", e.suggest("ami", null, phoneticLanguage = "bn").first())
        assertEquals("ভালো", e.suggest("valo", null, phoneticLanguage = "bn").first())
        assertEquals("আছি", e.suggest("asi", null, phoneticLanguage = "bn").first())
    }

    private fun hindiEngine(
        words: List<Pair<String, Int>>,
        spellings: SpellingMap = SpellingMap.EMPTY,
    ): SuggestionEngine = engine().also {
        it.extraPhonetic = mapOf(
            "hi" to PhoneticBackend(PhoneticSchemes.HINDI, HindiPhoneticIndex(words), spellings),
        )
    }

    @Test fun hindiDictionarySettlesWhatTheRulesCannot() {
        // Dental against retroflex is not in the spelling: the rules write थीक.
        val e = hindiEngine(listOf("ठीक" to 900, "करना" to 800))
        assertEquals("ठीक", e.suggest("theek", null, phoneticLanguage = "hi").first())
        assertEquals("करना", e.suggest("karna", null, phoneticLanguage = "hi").first())
    }

    @Test fun hindiSpellingMapLeads() {
        val map = SpellingMap.load("school\tस्कूल\n".byteInputStream(Charsets.UTF_8))
        val e = hindiEngine(emptyList(), map)
        assertEquals("स्कूल", e.suggest("school", null, phoneticLanguage = "hi").first())
        assertEquals("स्कूल", e.phoneticSpelling("hi", "school"))
        assertNull(e.phoneticSpelling("hi", "karna"))
        // One language's map is not another's.
        assertNull(e.phoneticSpelling("bn", "school"))
    }

    @Test fun hindiWithNoWordListOffersTheRulesOtherReadings() {
        val suggestions = hindiEngine(emptyList()).suggest("pyar", null, phoneticLanguage = "hi")
        assertEquals(HindiPhonetic.transliterate("pyar"), suggestions.first())
        assertTrue("प्यार" in suggestions)
    }

    private fun urduEngine(
        lexicon: UserLexicon = UserLexicon(null),
        spellings: SpellingMap = SpellingMap.EMPTY,
    ): SuggestionEngine = engine(lexicon).also {
        it.extraPhonetic = mapOf(
            "ur" to PhoneticBackend(
                PhoneticSchemes.URDU,
                UrduPhoneticIndex(listOf("\u06A9\u0645" to 500, "\u06A9\u0627\u0645" to 400)),
                spellings,
            ),
        )
    }

    private val kam = "\u06A9\u0645" // کم, "less"
    private val kaam = "\u06A9\u0627\u0645" // کام, "work"
    private val mera = "\u0645\u06CC\u0631\u0627" // میرا

    @Test fun phoneticReadingsFollowTheWordBefore() {
        // Alone, "kam" is the commoner کم…
        val plain = urduEngine()
        assertEquals(kam, plain.suggest("kam", null, phoneticLanguage = "ur").first())
        assertEquals(kam, plain.phoneticCommit("ur", "kam")?.output)
        // …but after a "میرا کام" the user has typed before, it is کام, on the
        // strip and on the space bar alike.
        val lexicon = UserLexicon(null).also { it.learnBigram(mera, kaam) }
        val e = urduEngine(lexicon)
        assertEquals(kaam, e.suggest("kam", mera, phoneticLanguage = "ur").first())
        assertEquals(kaam, e.phoneticCommit("ur", "kam", mera)?.output)
        // A different word before leaves it alone.
        assertEquals(kam, e.suggest("kam", "\u0628\u06C1\u062A", phoneticLanguage = "ur").first())
    }

    @Test fun phoneticContextCanBeSwitchedOff() {
        val lexicon = UserLexicon(null).also { it.learnBigram(mera, kaam) }
        val e = urduEngine(lexicon).also { it.phoneticContextOff = setOf("ur") }
        assertEquals(kam, e.suggest("kam", mera, phoneticLanguage = "ur").first())
        assertEquals(kam, e.phoneticCommit("ur", "kam", mera)?.output)
    }

    @Test fun contextReordersAKnownSpellingsFormsAndThePreviewFollows() {
        val kal = "\u06A9\u0644" // کل
        val kaal = "\u06A9\u0627\u0644" // کال
        val aaj = "\u0622\u062C" // آج
        val map = SpellingMap.load("kal\t$kal\nkal\t$kaal\n".byteInputStream(Charsets.UTF_8))
        val lexicon = UserLexicon(null).also { it.learnBigram(aaj, kaal) }
        val e = urduEngine(lexicon, map)
        assertEquals(kal, e.phoneticSpelling("ur", "kal"))
        // The composing preview and the space bar agree after the word before.
        assertEquals(kaal, e.phoneticSpelling("ur", "kal", aaj))
        assertEquals(kaal, e.suggest("kal", aaj, phoneticLanguage = "ur").first())
    }

    @Test fun contextNeverLiftsADictionaryWordOverAKnownSpelling() {
        // The map says "kam" is کم; a habit of کام after میرا moves it up the
        // strip but not past the listed spelling, which the preview showed.
        val map = SpellingMap.load("kam\t$kam\n".byteInputStream(Charsets.UTF_8))
        val lexicon = UserLexicon(null).also { it.learnBigram(mera, kaam) }
        val e = urduEngine(lexicon, map)
        val strip = e.suggest("kam", mera, phoneticLanguage = "ur")
        assertEquals(kam, strip.first())
        assertTrue(kaam in strip)
    }

    @Test fun aPhoneticLayoutNeverAnswersInLatin() {
        // The backend has not been loaded yet — a layout switched to before the
        // index finished building — and the rules still answer.
        val suggestions = engine().suggest("kaise", null, phoneticLanguage = "hi")
        assertEquals("कैसे", suggestions.first())
        assertTrue(suggestions.none { word -> word.any { it in 'a'..'z' } })
    }

    @Test fun userLearningPersonalizes() {
        val lexicon = UserLexicon(null)
        val dictionary = Trie().apply { insert("test", 10) }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon)
        lexicon.learnWord("tezos")
        lexicon.learnWord("tezos")
        val suggestions = e.suggest("te", previousWord = null)
        assertEquals("tezos", suggestions.first()) // learned word outranks dictionary
    }

    @Test fun nextWordPrediction() {
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("good", "morning")
        lexicon.learnBigram("good", "morning")
        lexicon.learnBigram("good", "night")
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        val suggestions = e.suggest("", previousWord = "good")
        assertEquals(listOf("morning", "night"), suggestions.take(2))
    }

    @Test fun splitWordSuggestion() {
        val dictionary = Trie().apply {
            insert("of", 9000)
            insert("the", 10000)
            insert("a", 9500)
            insert("lot", 800)
        }
        // The strip offers splits only while "Add missing spaces" is on (#413).
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply { autocorrectSplits = true }
        assertEquals("of the", e.suggest("ofthe", previousWord = null).first())
        assertTrue("a lot" in e.suggest("alot", previousWord = null))
        // Capitalization pattern carries over the whole phrase.
        assertEquals("Of the", e.suggest("Ofthe", previousWord = null).first())
    }

    @Test fun contactNamesCompleteAndChain() {
        val contacts = ContactNames.fromNames(
            listOf("Wasi Mollik", "Wasim Akram", "Wasi Uddin")
        )
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.contacts = contacts
        val completions = e.suggest("was", previousWord = null)
        assertEquals("Wasi", completions.first()) // two contacts share it
        assertTrue("Wasim" in completions)
        // Next-word chaining through the name.
        val next = e.suggest("", previousWord = "Wasi")
        assertTrue("Mollik" in next && "Uddin" in next)
        // A known name is never autocorrected away.
        assertNull(e.shouldAutocorrect("wasim"))
    }

    @Test fun seedBigramsCoverColdStart() {
        val seeds = SeedBigrams.load(
            "good morning 100\ngood night 90\nthank you 100\n".byteInputStream()
        )
        val e = SuggestionEngine(
            Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null), seedBigrams = seeds,
        )
        assertEquals(listOf("morning", "night"), e.suggest("", previousWord = "good"))
        assertEquals(listOf("you"), e.suggest("", previousWord = "Thank"))
    }

    @Test fun `the seed file opens a sentence through its sentinel token`() {
        // `<s>` in the file is the sentence-start sentinel, so an empty field
        // — whose context is that sentinel — has something to offer before a
        // single letter is typed (#119). The seeds carry their own capitals,
        // which is how they reach the strip.
        val seeds = SeedBigrams.load("<s> I 98\n<s> Thanks 90\ngood morning 100\n".byteInputStream())
        val e = SuggestionEngine(
            Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null), seedBigrams = seeds,
        )

        assertEquals(
            listOf("I", "Thanks"),
            e.suggest("", previousWord = WordContext.SENTENCE_START),
        )
        // The token is not a word: nothing else answers to it.
        assertEquals(emptyList<String>(), e.suggest("", previousWord = "<s>"))
    }

    @Test fun `an opener the user types outranks the bundled ones`() {
        // The sentinel survives WordKey normalisation, so a sentence opener is
        // learned and read back under the same key as any other bigram.
        val seeds = SeedBigrams.load("<s> I 98\n".byteInputStream())
        val lexicon = UserLexicon(null)
        lexicon.learnBigram(WordContext.SENTENCE_START, "Morning")
        val e = SuggestionEngine(
            Trie(), BengaliPhoneticIndex(emptyList()), lexicon, seedBigrams = seeds,
        )

        // Learned first; its spelling is the case memory's business, not this
        // path's, so an opener with no remembered case comes back as its key.
        assertEquals(
            listOf("morning", "I"),
            e.suggest("", previousWord = WordContext.SENTENCE_START),
        )
    }

    @Test fun learnedBigramsOutrankSeeds() {
        val seeds = SeedBigrams.load("good morning 100\ngood night 90\n".byteInputStream())
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("good", "game")
        val e = SuggestionEngine(
            Trie(), BengaliPhoneticIndex(emptyList()), lexicon, seedBigrams = seeds,
        )
        assertEquals(listOf("game", "morning", "night"), e.suggest("", previousWord = "good"))
    }

    @Test fun `secondary dictionary words are suggested alongside the primary`() {
        val spanish = Trie().apply { insert("gato", 1); insert("gata", 1) }
        val e = engine().apply {
            englishSources = false // primary is a non-English language
            secondaryDictionaries = listOf(SecondaryDictionary("es", spanish))
        }
        assertTrue("gato" in e.suggest("gat", previousWord = null))
    }

    @Test fun `a word in a secondary dictionary is never autocorrected away`() {
        val secondary = Trie().apply { insert("worl", 1) }
        val e = engine().apply { secondaryDictionaries = listOf(SecondaryDictionary("es", secondary)) }
        assertNull("worl is a valid secondary word", e.shouldAutocorrect("worl"))
        // Without the secondary the same string corrects to the bundled word.
        assertEquals("world", engine().shouldAutocorrect("worl"))
    }

    @Test fun `english as a secondary contributes the bundled list`() {
        val e = engine().apply {
            englishSources = false // primary isn't English
            englishAsSecondary = true
        }
        val s = e.suggest("hel", previousWord = null)
        assertTrue("hello" in s || "help" in s)
    }

    @Test fun `the more-used secondary language outranks the neglected one`() {
        // Two secondaries whose completions collide on the same prefix; the one
        // the user actually types should sort ahead once the mix has adapted.
        val spanish = Trie().apply { insert("plato", 1) }
        val german = Trie().apply { insert("platz", 1) }
        val mix = LanguageMixConfidence()
        val e = SuggestionEngine(
            Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null),
            mixConfidence = mix,
        ).apply {
            englishSources = false
            primaryLanguageId = "fr"
            secondaryDictionaries = listOf(
                SecondaryDictionary("es", spanish),
                SecondaryDictionary("de", german),
            )
        }
        // Cold start: equal weight, so neither is forced ahead of the other.
        // Teach the keyboard the user leans on Spanish.
        repeat(30) { e.recordUsage("plato") }
        val ranked = e.suggest("plat", previousWord = null)
        assertTrue("plato" in ranked && "platz" in ranked)
        assertTrue(
            "the used language should sort first",
            ranked.indexOf("plato") < ranked.indexOf("platz"),
        )
    }

    @Test fun `a downloaded secondary with real counts does not bury the typed primary word`() {
        // #653: bundled English on its 0..10000 scale, Italian downloaded with
        // corpus counts in the millions. "visit" typed in full stays first.
        val dictionary = Trie().apply {
            insert("the", 10_000)
            insert("visit", 238)
        }
        val italian = Trie().apply {
            insert("di", 19_000_000)
            insert("visita", 900_000)
            insert("visitare", 400_000)
            insert("visite", 300_000)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            primaryLanguageId = "en"
            secondaryDictionaries = listOf(SecondaryDictionary("it", italian))
        }
        val s = e.suggest("visit", previousWord = null)
        assertEquals("expected visit first in $s", "visit", s.first())
        assertTrue("Italian still offered in $s", "visita" in s)
    }

    @Test fun `english as a secondary still shows beside a downloaded primary`() {
        // The reverse of #653: Italian primary downloaded with corpus counts,
        // bundled English riding as the secondary at half its 0..10000 scale.
        val italian = Trie().apply {
            insert("di", 19_000_000)
            insert("visita", 900_000)
            insert("visitare", 400_000)
        }
        val english = Trie().apply {
            insert("the", 10_000)
            insert("visit", 2_000)
        }
        val e = SuggestionEngine(english, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = false
            englishAsSecondary = true
            primaryLanguageId = "it"
            customDictionary = italian
        }
        val s = e.suggest("visit", previousWord = null)
        assertTrue("English visit offered in $s", "visit" in s)
    }

    @Test fun `a downloaded primary list does not correct every word it lacks`() {
        // #403: a Greek list with corpus counts in the millions read at its raw
        // scale cleared the lone-candidate floor by ten nats, so any unknown
        // word one slip from a rare listed one was replaced.
        val greek = Trie().apply {
            insert("kai", 19_000_000)
            insert("einai", 10_000_000)
            insert("kalimera", 5_000)
        }
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = false
            primaryLanguageId = "el"
            customDictionary = greek
        }
        assertNull("rare listed word must not fire unopposed", e.shouldAutocorrect("kalimeri"))
        assertEquals("einai", e.shouldAutocorrect("eiani"))
    }

    @Test fun `words the primary covers do not inflate a secondary`() {
        // "world" is a primary (English) word: committing it must count toward
        // the primary, leaving the untouched secondary damped below neutral —
        // never boosted as though the user had typed Spanish.
        val dictionary = Trie().apply { insert("world", 50) }
        val spanish = Trie().apply { insert("hola", 1) }
        val mix = LanguageMixConfidence()
        val e = SuggestionEngine(
            dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null),
            mixConfidence = mix,
        ).apply {
            englishSources = true
            primaryLanguageId = "en"
            secondaryDictionaries = listOf(SecondaryDictionary("es", spanish))
        }
        repeat(20) { e.recordUsage("world") }
        // Primary got the credit, so Spanish sits below neutral, not above it.
        assertTrue(mix.confidenceFor("es") < LanguageMixConfidence.NEUTRAL)
        assertTrue(mix.confidenceFor("en") > mix.confidenceFor("es"))
    }

    // ---- missing-space splits ----

    @Test fun `stray spacebar-neighbour letter offers the split in the strip`() {
        val e = engine().apply {
            touchModel = KeyTouchModel(mapOf('b' to TouchPoint(5f, 3f)))
            autocorrectSplits = true
        }
        val low = List(9) { i -> if (i == 3) TouchPoint(5f, 3.4f) else null }
        val out = e.suggest("thebworld", previousWord = null, touch = low)
        assertTrue("expected 'the world' in $out", "the world" in out)
    }

    @Test fun `boundary letter far from the spacebar never reads as a space`() {
        val e = engine().apply {
            touchModel = KeyTouchModel(mapOf('x' to TouchPoint(2f, 3f)))
            autocorrectSplits = true
        }
        val low = List(9) { i -> if (i == 3) TouchPoint(2f, 3.4f) else null }
        val out = e.suggest("thexworld", previousWord = null, touch = low)
        assertTrue("unexpected split in $out", "the world" !in out)
    }

    @Test fun `a split never ends on a single letter`() {
        // What a corpus tokenised at the apostrophe leaves behind: the
        // downloadable English list carries a bare `s` at 110,000 and `don`
        // at four million, so "thats" read as `that` + `s` and came back as
        // "that s" (#240). The left half stays open to one letter, since
        // "alot" really is *a lot*.
        val e = SuggestionEngine(
            PackedTrie.of(listOf("that" to 10_203_742, "s" to 110_199, "a" to 14_484_562, "lot" to 200_000)),
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        ).apply { autocorrectSplits = true }
        assertFalse("that s" in e.suggest("thats", previousWord = null))
        // Ordinary autocorrect still has its say — "thats" is one deletion
        // from "that" — but the split is not what it says.
        assertNotEquals("that s", e.shouldAutocorrect("thats"))
        // ...while a one-letter *first* half still reads: "alot" is "a lot".
        // On the strip rather than applied, because in a list this small a
        // plain deletion reaches "lot" and outranks any split.
        assertTrue("a lot" in e.suggest("alot", previousWord = null))
    }

    @Test fun `split autocorrect is off unless the IME enables it`() {
        assertNull(engine().shouldAutocorrect("theworld"))
    }

    /** Split autocorrect on, with "the world" typed often enough to vouch for itself. */
    private fun splitEngine(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine =
        engine(lexicon.apply { repeat(2) { learnBigram("the", "world") } })
            .apply { autocorrectSplits = true }

    @Test fun `split autocorrect inserts the missed space`() {
        val e = splitEngine()
        assertEquals("the world", e.shouldAutocorrect("theworld"))
        // Typed capitalization carries over to the split.
        assertEquals("The world", e.shouldAutocorrect("Theworld"))
    }

    @Test fun `an exact split needs a phrase the keyboard has seen`() {
        // Both halves listed is not enough: nearly any unlisted word breaks
        // into two listed fragments somewhere ("config" -> "con fig").
        val lexicon = UserLexicon(null)
        val e = engine(lexicon).apply { autocorrectSplits = true }
        assertNull(e.shouldAutocorrect("theworld"))
        assertTrue("the world" in e.suggest("theworld", previousWord = null))
        // One sighting is what an applied-and-kept split leaves behind.
        lexicon.learnBigram("the", "world")
        assertNull(e.shouldAutocorrect("theworld"))
        lexicon.learnBigram("the", "world")
        assertEquals("the world", e.shouldAutocorrect("theworld"))
    }

    @Test fun `a word in the personal dictionary is never split`() {
        val lexicon = UserLexicon(null)
        val e = splitEngine(lexicon)
        lexicon.learnWord("theworld")
        assertNull(e.shouldAutocorrect("theworld"))
    }

    // ---- the fat-fingered spacebar ----

    /** A bottom-row model: `b` sits at y = 3, the spacebar a row below it. */
    private fun bottomRowEngine(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine =
        engine(lexicon).apply {
            autocorrectSplits = true
            touchModel = KeyTouchModel(mapOf('b' to TouchPoint(5f, 3f)))
        }

    /** Taps for "thebworld" with the `b` landing [bDrop] key widths below its centre. */
    private fun thebworldTaps(bDrop: Float): List<TouchPoint?> =
        List(9) { i -> if (i == 3) TouchPoint(5f, 3f + bDrop) else null }

    @Test fun `split autocorrect drops a fat-fingered space letter`() {
        val lexicon = UserLexicon(null).apply { repeat(2) { learnBigram("the", "world") } }
        val e = bottomRowEngine(lexicon)
        val low = thebworldTaps(bDrop = 0.4f)
        assertEquals("the world", e.decideCorrection("thebworld", touch = low).apply)
        assertTrue("the world" in e.suggest("thebworld", null, touch = low))
    }

    @Test fun `a letter tapped square on its key is never a space slip`() {
        // "config" is not in the list, "co" and "fig" are, and `n` flanks the
        // spacebar: with no tap to say the finger reached for the spacebar,
        // the word the user typed stands (the "config" -> "co fig" report).
        val lexicon = UserLexicon(null).apply { learnBigram("the", "world") }
        val e = bottomRowEngine(lexicon)
        assertNull(e.decideCorrection("thebworld").apply)
        assertNull(e.decideCorrection("thebworld", touch = thebworldTaps(bDrop = 0f)).apply)
        assertNull(e.decideCorrection("thebworld", touch = thebworldTaps(bDrop = -0.3f)).apply)
        assertTrue("the world" !in e.suggest("thebworld", null))
        assertTrue("the world" !in e.suggest("thebworld", null, touch = thebworldTaps(bDrop = 0f)))
    }

    @Test fun `a dropped-letter split needs a phrase the keyboard has seen`() {
        val lexicon = UserLexicon(null)
        val e = bottomRowEngine(lexicon)
        val low = thebworldTaps(bDrop = 0.4f)
        // Never typed "the world": the strip may offer it, the field is not rewritten.
        assertNull(e.decideCorrection("thebworld", touch = low).apply)
        assertTrue("the world" in e.suggest("thebworld", null, touch = low))
        repeat(2) { lexicon.learnBigram("the", "world") }
        assertEquals("the world", e.decideCorrection("thebworld", touch = low).apply)
    }

    @Test fun `a word learned once does not anchor a split`() {
        val lexicon = UserLexicon(null).apply {
            repeat(2) { learnBigram("the", "wprld") }
            learnWord("wprld")
        }
        val e = bottomRowEngine(lexicon).apply { learnedWordMinCount = 3 }
        val low = thebworldTaps(bDrop = 0.4f)
        assertNull(e.decideCorrection("thebwprld", touch = low).apply)
        lexicon.learnWord("wprld", count = 2)
        assertEquals("the wprld", e.decideCorrection("thebwprld", touch = low).apply)
    }

    @Test fun `single-word fix always outranks a split`() {
        val e = engine().apply { autocorrectSplits = true }
        assertEquals("world", e.shouldAutocorrect("wprld"))
    }

    // ---- a known word's slips (#979) ----

    @Test fun `a known word keeps its nearest slip on the strip, below it`() {
        val dictionary = Trie().apply {
            insert("is", 9_000)
            insert("us", 4_000)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        val s = e.suggest("is", previousWord = null)
        assertEquals("is", s.first())
        assertTrue("us offered in $s", "us" in s)
        // The strip only: a known word is still never corrected.
        assertNull(e.shouldAutocorrect("is"))
    }

    @Test fun `a secondary word one slip from a primary word reaches the strip`() {
        val primary = Trie().apply { insert("kalb", 5_000) }
        val secondary = Trie().apply { insert("kelb", 5_000) }
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = false
            primaryLanguageId = "ar"
            customDictionary = primary
            secondaryDictionaries = listOf(SecondaryDictionary("arz", secondary))
        }
        val s = e.suggest("kalb", previousWord = null)
        assertEquals("kalb", s.first())
        assertTrue("secondary kelb offered in $s", "kelb" in s)
    }

    @Test fun `known words are never split`() {
        val e = engine().apply { autocorrectSplits = true }
        assertNull(e.shouldAutocorrect("hello"))
    }

    @Test fun `reverted split never fires again`() {
        val e = splitEngine()
        assertEquals("the world", e.shouldAutocorrect("theworld"))
        e.rejectCorrection("theworld", "the world")
        assertNull(e.shouldAutocorrect("theworld"))
    }

    // ---- number-row digit slips ----

    private fun digitEngine(): SuggestionEngine = engine().apply {
        digitSlipCorrections = true
        proximity = KeyProximity.forLayout(
            com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.QWERTY,
            numberRow = true,
        )
    }

    @Test fun `single digit corrects to the letter below it`() {
        assertEquals("help", digitEngine().shouldAutocorrect("h3lp"))
    }

    @Test fun `digit words are untouched when the feature is off`() {
        val e = engine().apply {
            proximity = KeyProximity.forLayout(
                com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.QWERTY,
                numberRow = true,
            )
        }
        assertNull(e.shouldAutocorrect("h3lp"))
    }

    @Test fun `digit is only ever swapped in place, never deleted`() {
        // "help3" -> "help" would be a deletion of text typed on purpose.
        assertNull(digitEngine().shouldAutocorrect("help3"))
    }

    @Test fun `two digits mean deliberate input`() {
        assertNull(digitEngine().shouldAutocorrect("h3lp4"))
    }

    // ---- word sources ----
    // The IME asks this before re-arming a word the caret landed on as the
    // composing region; a language it answers false for gets no underline.

    private fun bengaliEngine(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine =
        SuggestionEngine(
            // What Bengali really has: no bundled list of its own (the bundled
            // list is English, and englishSources is off for every other
            // language) and the empty imported trie until a download lands.
            Trie().apply { insert("the", 100) },
            BengaliPhoneticIndex(emptyList()),
            lexicon,
        ).apply {
            englishSources = false
            primaryLanguageId = "bn"
        }

    @Test fun `the bundled list is a word source for english`() {
        assertTrue(engine().hasWordSources)
    }

    @Test fun `a language with no list and nothing learned has no word source`() {
        assertFalse(bengaliEngine().hasWordSources)
    }

    @Test fun `a downloaded list is a word source`() {
        val e = bengaliEngine().apply {
            customDictionary = Trie().apply {
                insert("বাংলা", 1)
                insert("বাংলাদেশ", 1)
            }
        }
        assertTrue(e.hasWordSources)
    }

    @Test fun `a learned word alone is a word source`() {
        val lexicon = UserLexicon(null).apply { learnWord("বাংলা", count = 1, langId = "bn") }
        assertTrue(bengaliEngine(lexicon).hasWordSources)
    }

    @Test fun `english as a secondary is a word source for the primary`() {
        assertTrue(bengaliEngine().apply { englishAsSecondary = true }.hasWordSources)
    }

    @Test fun `a secondary language's list is a word source`() {
        val spanish = Trie().apply { insert("gato", 1) }
        val e = bengaliEngine().apply {
            secondaryDictionaries = listOf(SecondaryDictionary("es", spanish))
        }
        assertTrue(e.hasWordSources)
    }

    @Test fun `an empty imported list is not a word source`() {
        // Every language starts from the same empty trie, so presence of the
        // field cannot stand in for presence of words.
        assertFalse(bengaliEngine().apply { customDictionary = Trie() }.hasWordSources)
    }

    // ---- per-field language detection ----

    /**
     * English keyboard with Banglish as a secondary. "barj" is one adjacent
     * substitution from English "bark" and from Banglish "bari", so which way
     * autocorrect goes is decided purely by the field's language.
     */
    private fun banglishEngine(
        shift: Double = SuggestionEngine.FIELD_SHIFT_AGGRESSIVE,
    ): SuggestionEngine {
        // On the bundled list's 0..10000 scale: a list topping out above it is
        // read as a downloaded one and scaled down onto it (the engine's
        // SourceScales), which would take "bark" down with it.
        val dictionary = Trie().apply {
            insert("bark", 2000)
            insert("how", 9_000)
            insert("are", 8_000)
            insert("you", 10_000)
        }
        val banglish = Trie().apply {
            insert("bari", 1)
            insert("ami", 1)
            insert("tomake", 1)
            insert("bhalo", 1)
        }
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply {
                primaryLanguageId = "en"
                secondaryDictionaries = listOf(SecondaryDictionary("bn_rom", banglish))
                fieldDetectionShift = shift
            }
    }

    private val banglishWords = listOf("bhalo", "ami", "tomake")

    @Test fun `an empty field leaves the primary language in charge`() {
        val e = banglishEngine()
        val s = e.suggest("bar", previousWord = null)
        assertTrue(s.indexOf("bark") < s.indexOf("bari"))
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `a banglish field hands ranking and autocorrect to banglish`() {
        val e = banglishEngine()
        e.seedFieldContext(banglishWords)
        val s = e.suggest("bar", previousWord = null)
        assertTrue("expected bari first in $s", s.indexOf("bari") < s.indexOf("bark"))
        assertEquals("bari", e.shouldAutocorrect("barj"))
    }

    @Test fun `an english field keeps autocorrect english`() {
        val e = banglishEngine()
        e.seedFieldContext(listOf("how", "are", "you"))
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `gentle strength reorders nothing away from the primary`() {
        val e = banglishEngine(shift = SuggestionEngine.FIELD_SHIFT_GENTLE)
        e.seedFieldContext(banglishWords)
        val s = e.suggest("bar", previousWord = null)
        assertTrue("gentle must not dethrone the primary in $s", s.indexOf("bark") < s.indexOf("bari"))
    }

    @Test fun `an app habit leans an empty field without owning it`() {
        // Messenger, where this user writes Banglish: the field starts leaning
        // that way at half strength, which at Aggressive is enough to put the
        // Banglish word first...
        val habit = FieldLanguageMix.Prior(mapOf("bn_rom" to 0.9, "en" to 0.1), FieldLanguageMix.MAX_PRIOR_WEIGHT)
        val aggressive = banglishEngine()
        aggressive.seedFieldContext(emptyList(), habit)
        val s = aggressive.suggest("bar", previousWord = null)
        assertTrue("expected bari first in $s", s.indexOf("bari") < s.indexOf("bark"))
        // ...and one English word typed in that field takes it straight back.
        aggressive.recordUsage("how")
        val back = aggressive.suggest("bar", previousWord = null)
        assertTrue("expected bark first in $back", back.indexOf("bark") < back.indexOf("bari"))
        // At Balanced the habit alone never unseats a common English word.
        val balanced = banglishEngine(shift = SuggestionEngine.FIELD_SHIFT_BALANCED)
        balanced.seedFieldContext(emptyList(), habit)
        val b = balanced.suggest("bar", previousWord = null)
        assertTrue("balanced must keep bark first in $b", b.indexOf("bark") < b.indexOf("bari"))
    }

    @Test fun `owning languages classifies for the app tally`() {
        val e = banglishEngine()
        assertEquals(setOf("bn_rom"), e.owningLanguages("Ami"))
        assertEquals(setOf("en"), e.owningLanguages("how"))
        assertTrue(e.owningLanguages("zzq").isEmpty())
        // No mix configured: nothing to classify against.
        assertTrue(engine().owningLanguages("hello").isEmpty())
    }

    @Test fun `committed words swing the detection back mid-field`() {
        val e = banglishEngine()
        e.seedFieldContext(banglishWords)
        repeat(3) { e.recordUsage("you") }
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `reseeding replaces the old field's evidence`() {
        val e = banglishEngine()
        e.seedFieldContext(banglishWords)
        e.seedFieldContext(emptyList())
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `clearing the field context restores the primary`() {
        val e = banglishEngine()
        e.seedFieldContext(banglishWords)
        e.clearFieldContext()
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `detection off is inert whatever the field says`() {
        val e = banglishEngine(shift = SuggestionEngine.FIELD_SHIFT_OFF)
        e.seedFieldContext(banglishWords)
        assertEquals("bark", e.shouldAutocorrect("barj"))
    }

    @Test fun `a banglish word stays protected from autocorrect either way`() {
        // The in-dictionary gate is deliberately unbiased: however English the
        // field looks, a word a secondary list contains is never corrected.
        val e = banglishEngine()
        e.seedFieldContext(listOf("how", "are", "you"))
        assertNull(e.shouldAutocorrect("tomake"))
    }

    // ---- Android personal dictionary as a known-word source (#45) ----

    @Test fun systemDictionaryWordsAreKnownAndNeverCorrected() {
        val dictionary = Trie().apply { insert("also", 100); insert("soap", 90) }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // Without the platform list: unknown, and a correction candidate.
        assertFalse(e.isKnownWord("aosp"))
        // With it: known under any casing, and left alone by autocorrect.
        e.systemDictionary = SystemUserDictionary.index(listOf("AOSP")).source
        assertTrue(e.isKnownWord("aosp"))
        assertTrue(e.isKnownWord("AOSP"))
        assertNull(e.shouldAutocorrect("aosp"))
        // And it completes like any other known word.
        assertTrue("aosp" in e.suggest("aos", previousWord = null))
        // Clearing the source (setting turned off) forgets it again.
        e.systemDictionary = PackedTrie.EMPTY
        assertFalse(e.isKnownWord("aosp"))
    }

    @Test fun systemDictionaryIndexNormalisesAndSplitsEntries() {
        val source =
            SystemUserDictionary.index(listOf("AOSP", "aosp", "on my way", " x ", "", "plan b")).source
        assertTrue(source.contains("aosp"))
        assertEquals(1, source.frequencyOf("aosp"))
        // A multi-word entry indexes as its parts, never as one token.
        assertTrue(source.contains("way"))
        assertFalse(source.contains("on my way"))
        // A row that is one letter was added on purpose ("I", #620); one
        // letter out of a phrase was not.
        assertTrue(source.contains("x"))
        assertFalse(source.contains("b"))
    }

    @Test fun aOneLetterRowKeepsItsCapital() {
        assertEquals("I", SystemUserDictionary.index(listOf("I")).shapes["i"])
    }

    // ---- capitalization of learned and platform-dictionary words (#44) ----

    @Test fun systemDictionaryEntriesKeepTheCapitalsTheyWereWrittenWith() {
        val entries = SystemUserDictionary.index(listOf("AOSP", "Boston", "on my way"))
        // Keys are folded; the spellings ride alongside them.
        assertEquals("AOSP", entries.shapes["aosp"])
        assertEquals("Boston", entries.shapes["boston"])
        // A lower-case entry needs no spelling of its own.
        assertFalse("way" in entries.shapes)

        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.systemDictionary = entries.source
        e.systemWordCases = entries.shapes
        assertTrue("Boston" in e.suggest("bos", previousWord = null))
        assertTrue("AOSP" in e.suggest("aos", previousWord = null))
    }

    @Test fun aCapitalisedPhraseDoesNotCapitaliseItsWords() {
        val entries = SystemUserDictionary.index(listOf("User Dictionary Manager"))
        // The parts are known words, as any platform entry's parts are (#45)...
        assertTrue(entries.source.contains("user"))
        assertTrue(entries.source.contains("manager"))
        // ...but the phrase's title case is the phrase's, not each word's (#221).
        assertFalse("user" in entries.shapes)
        assertFalse("dictionary" in entries.shapes)
        assertFalse("manager" in entries.shapes)

        val dictionary = Trie().apply { insert("user", 100) }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.systemDictionary = entries.source
        e.systemWordCases = entries.shapes
        // Typing or gliding the word gets the word back, lower case.
        assertTrue("user" in e.suggest("use", previousWord = null))
        assertFalse("User" in e.suggest("use", previousWord = null))
        // And the case vote still reads it as a word that is spelled lower case.
        assertTrue(e.spellsInLowerCase("user"))
    }

    @Test fun aSingleWordRowStillCapitalisesWhenAPhraseMentionsIt() {
        // Row order must not decide the answer either way round.
        val phraseFirst = SystemUserDictionary.index(listOf("Boston Red Sox", "Boston"))
        val wordFirst = SystemUserDictionary.index(listOf("Boston", "Boston Red Sox"))
        assertEquals("Boston", phraseFirst.shapes["boston"])
        assertEquals("Boston", wordFirst.shapes["boston"])
        assertFalse("red" in phraseFirst.shapes)
        assertFalse("red" in wordFirst.shapes)
    }

    @Test fun aLearnedCapitalIsPutBackOnCompletionsAndNextWords() {
        val lexicon = UserLexicon(null)
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        lexicon.learnWord("Boston", count = 4, caseEvidence = true)
        lexicon.learnBigram("in", "boston")
        assertTrue("Boston" in e.suggest("bost", previousWord = null))
        // The next-word strip too: "in" -> "Boston", not "boston".
        assertTrue("Boston" in e.suggest("", previousWord = "in"))
    }

    @Test fun typedCapitalsStillOutrankTheRememberedSpelling() {
        val lexicon = UserLexicon(null)
        val e = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), lexicon)
        lexicon.learnWord("Boston", count = 4, caseEvidence = true)
        // Shouting is the typist's call, not the memory's.
        assertTrue("BOSTON" in e.suggest("BOST", previousWord = null))
    }

    // --- fixes the user taught by hand (CorrectionMemory) ---

    private fun teach(e: SuggestionEngine, typed: String, fixed: String, times: Int) {
        repeat(times) {
            e.correctionMemory.teach(typed, fixed, "qwerty", CorrectionMemory.Kind.PAIR_ONLY)
        }
    }

    @Test fun aFixTaughtTwiceIsApplied() {
        // "zzq" is nothing the dictionary can explain; the user has turned it
        // into "hello" twice, which is a fix they want made for them.
        val e = engine()
        assertNull(e.shouldAutocorrect("zzq"))
        teach(e, "zzq", "hello", 2)
        val decision = e.decideCorrection("zzq")
        assertEquals("hello", decision.apply)
        assertTrue(decision.certainty > 0.5)
        // Case carries through like any other correction.
        assertEquals("Hello", e.shouldAutocorrect("Zzq"))
    }

    @Test fun oneFixOnlyOffersAndOverrulesADifferentGuess() {
        val e = engine()
        assertEquals("world", e.shouldAutocorrect("wprld"))
        // Fixed once to something else: the engine's own guess is the mistake
        // the user was correcting, so it is asked about, not applied.
        teach(e, "wprld", "hello", 1)
        val decision = e.decideCorrection("wprld")
        assertNull(decision.apply)
        assertEquals("hello", decision.offer)
        teach(e, "wprld", "hello", 1)
        assertEquals("hello", e.shouldAutocorrect("wprld"))
    }

    @Test fun oneFixAgreeingWithTheEngineChangesNothing() {
        val e = engine()
        teach(e, "wprld", "world", 1)
        assertEquals("world", e.decideCorrection("wprld").apply)
    }

    @Test fun aTaughtFixOfARealWordNeedsThreeFixesAndTheContext() {
        fun build(fixes: Int): SuggestionEngine {
            val dictionary = Trie().apply {
                insert("form", 100)
                insert("from", 500)
                insert("far", 100)
                insert("the", 100)
            }
            val lexicon = UserLexicon(null)
            repeat(8) { lexicon.learnBigram("far", "from") }
            return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), lexicon).also {
                teach(it, "form", "from", fixes)
            }
        }
        val three = build(3)
        // A real word never gives way without context...
        assertNull(three.decideCorrection("form").apply)
        assertNull(three.decideCorrection("form", previousWord = "the").apply)
        // ...and does when the words before it say so.
        assertEquals("from", three.decideCorrection("form", previousWord = "far").apply)
        // Two fixes with the context: asked about, not applied.
        val two = build(2)
        val decision = two.decideCorrection("form", previousWord = "far")
        assertNull(decision.apply)
        assertEquals("from", decision.offer)
        // One fix says nothing about a real word at all.
        assertEquals(SuggestionEngine.NO_CORRECTION, build(1).decideCorrection("form", previousWord = "far"))
    }

    @Test fun anUndoneTaughtFixStopsFiringAndShrinks() {
        val e = engine()
        teach(e, "zzq", "hello", 2)
        assertEquals("hello", e.shouldAutocorrect("zzq"))
        e.rejectCorrection("zzq", "hello")
        assertNull(e.shouldAutocorrect("zzq"))
        assertEquals(1, e.correctionMemory.fixFor("zzq")?.count)
    }

    @Test fun aBlacklistedFixIsNeverTaughtBack() {
        val e = engine().apply { blacklist = setOf("hello") }
        teach(e, "zzq", "hello", 2)
        assertNull(e.shouldAutocorrect("zzq"))
    }

    @Test fun aThirdWordSurvivorLetsAnIndirectVerdictThrough() {
        val e = engine()
        // The typo standing where the fix was: a reproduced typo, not believed.
        e.rejectCorrection("teh", "the", deliberate = false)
        assertEquals(CorrectionStats.Penalty.NONE, e.correctionStats.penalty("teh", "the"))
        // The fix rewritten into a real third word: the correction was wrong.
        e.rejectCorrection("teh", "the", deliberate = false, survivor = "them")
        assertEquals(CorrectionStats.Penalty.PENALIZED, e.correctionStats.penalty("teh", "the"))
    }

    @Test fun aLearnedSlipPricesItsCandidateAhead() {
        val dictionary = Trie().apply {
            insert("hello", 100)
            insert("hullo", 100)
        }
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // x is far from both e and u: a tie, broken alphabetically.
        assertEquals("hello", e.suggest("hxllo", previousWord = null).first())
        val memory = CorrectionMemory(null)
        repeat(EditHabits.WARMUP) {
            memory.teach("hxllo", "hullo", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
        }
        e.editHabits = memory.habitsFor("qwerty")
        assertEquals("hullo", e.suggest("hxllo", previousWord = null).first())
        // Back to nothing learned restores the shipped pricing.
        e.editHabits = EditHabits.NONE
        assertEquals("hello", e.suggest("hxllo", previousWord = null).first())
    }
}
