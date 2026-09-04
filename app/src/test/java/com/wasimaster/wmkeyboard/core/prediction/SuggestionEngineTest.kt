package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private fun engine(): SuggestionEngine {
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
        return SuggestionEngine(dictionary, bengali, UserLexicon(null))
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
        val suggestions = engine().suggest("asi", previousWord = null, avroMode = true)
        assertEquals("আছি", suggestions.first())
        assertTrue("আসি" in suggestions)
    }

    @Test fun avroLoanwordWinsOverPhonetics() {
        val dictionary = Trie()
        val loanwords = BengaliSpellingMap.load(
            "keyboard\tকিবোর্ড\nchair\tচেয়ার\n".byteInputStream(Charsets.UTF_8)
        )
        val e = SuggestionEngine(
            dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null), loanwords,
        )
        assertEquals("কিবোর্ড", e.suggest("keyboard", null, avroMode = true).first())
        assertEquals("চেয়ার", e.suggest("chair", null, avroMode = true).first())
    }

    @Test fun avroLiteralHoldsAgainstNearTieSibling() {
        // হলো is more frequent than হল, but the composing preview showed
        // হলো — when the top sibling is less than 2x more frequent, the
        // literal (what the user sees while typing) survives.
        val bengali = BengaliPhoneticIndex(
            listOf("হলো" to 1986, "হল" to 1900)
        )
        val e = SuggestionEngine(Trie(), bengali, UserLexicon(null))
        val suggestions = e.suggest("holO", null, avroMode = true)
        assertEquals("হলো", suggestions.first())
        assertTrue("হল" in suggestions)
    }

    @Test fun avroLiteralWinsWithoutSiblings() {
        // "wasi" has no phonetic sibling in the dictionary; the literal
        // transliteration (ওয়াসি) must survive the space commit untouched.
        val e = engine()
        val literal = AvroPhonetic.transliterate("wasi")
        assertEquals(literal, e.suggest("wasi", null, avroMode = true).first())
    }

    @Test fun avroSentenceWords() {
        val e = engine()
        assertEquals("আমি", e.suggest("ami", null, avroMode = true).first())
        assertEquals("ভালো", e.suggest("valo", null, avroMode = true).first())
        assertEquals("আছি", e.suggest("asi", null, avroMode = true).first())
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
        val e = SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
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
        val out = engine().suggest("thebworld", previousWord = null)
        assertTrue("expected 'the world' in $out", "the world" in out)
    }

    @Test fun `boundary letter far from the spacebar never reads as a space`() {
        val out = engine().suggest("thexworld", previousWord = null)
        assertTrue("unexpected split in $out", "the world" !in out)
    }

    @Test fun `split autocorrect is off unless the IME enables it`() {
        assertNull(engine().shouldAutocorrect("theworld"))
    }

    @Test fun `split autocorrect inserts the missed space`() {
        val e = engine().apply { autocorrectSplits = true }
        assertEquals("the world", e.shouldAutocorrect("theworld"))
        // Typed capitalization carries over to the split.
        assertEquals("The world", e.shouldAutocorrect("Theworld"))
    }

    @Test fun `split autocorrect drops a fat-fingered space letter`() {
        val e = engine().apply { autocorrectSplits = true }
        assertEquals("the world", e.shouldAutocorrect("thebworld"))
    }

    @Test fun `single-word fix always outranks a split`() {
        val e = engine().apply { autocorrectSplits = true }
        assertEquals("world", e.shouldAutocorrect("wprld"))
    }

    @Test fun `known words are never split`() {
        val e = engine().apply { autocorrectSplits = true }
        assertNull(e.shouldAutocorrect("hello"))
    }

    @Test fun `reverted split never fires again`() {
        val e = engine().apply { autocorrectSplits = true }
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
        val dictionary = Trie().apply {
            insert("bark", 2000)
            insert("how", 90_000)
            insert("are", 80_000)
            insert("you", 100_000)
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
        e.systemDictionary = SystemUserDictionary.index(listOf("AOSP"))
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
        val source = SystemUserDictionary.index(listOf("AOSP", "aosp", "on my way", " x ", ""))
        assertTrue(source.contains("aosp"))
        assertEquals(1, source.frequencyOf("aosp"))
        // A multi-word entry indexes as its parts, never as one token.
        assertTrue(source.contains("way"))
        assertFalse(source.contains("on my way"))
        // Single characters are not words.
        assertFalse(source.contains("x"))
    }
}
