package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserLexiconTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "learning/user_lexicon.json")

    @Test
    fun legacySnapshotWithoutNewFieldsLoads() {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText("""{"words":{"hello":5},"bigrams":{"hello":{"world":2}}}""")
        val lexicon = UserLexicon(f)
        assertEquals(5, lexicon.frequencyOf("hello"))
        assertEquals(listOf("world"), lexicon.nextWords("hello", 3))
        assertEquals(2, lexicon.bigramCount("hello", "world"))
    }

    @Test
    fun roundTripPreservesEverything() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("hello", 3)
            learnBigram("hello", "world")
            learnBigram("hello", "world")
            learnBigram("hello", "there")
            save()
        }
        val back = UserLexicon(f)
        assertEquals(3, back.frequencyOf("hello"))
        assertEquals(2, back.bigramCount("hello", "world"))
        assertEquals(listOf("world", "there"), back.nextWords("hello", 5))
    }

    @Test
    fun languageTagsRoundTripAndFollowTheWord() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("wasi", 3, langId = "bn_rom")
            // Untagged learns (blank langId) leave no tag behind.
            learnWord("hello", 3)
            save()
        }
        val back = UserLexicon(f)
        assertEquals("bn_rom", back.languageOf("wasi"))
        assertEquals(null, back.languageOf("hello"))
        // The most recent language wins.
        back.learnWord("wasi", 1, langId = "en")
        assertEquals("en", back.languageOf("wasi"))
        // Forgetting the word drops its tag with it.
        back.forget("wasi")
        assertEquals(null, back.languageOf("wasi"))
    }

    @Test
    fun nextWordsOrderSurvivesMutation() {
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("a", "x")
        lexicon.learnBigram("a", "y")
        lexicon.learnBigram("a", "y")
        assertEquals(listOf("y", "x"), lexicon.nextWords("a", 5))
        // The cached order must invalidate when counts change.
        lexicon.learnBigram("a", "x")
        lexicon.learnBigram("a", "x")
        assertEquals(listOf("x", "y"), lexicon.nextWords("a", 5))
    }

    @Test
    fun followerListIsCapped() {
        val lexicon = UserLexicon(null)
        // "keep" gets weight so it survives; then flood with singles.
        repeat(5) { lexicon.learnBigram("prev", "keep") }
        for (i in 0 until 40) lexicon.learnBigram("prev", "w$i")
        val followers = lexicon.followerCounts("prev")
        assertTrue("cap exceeded: ${followers.size}", followers.size <= 32)
        assertTrue("keep" in followers)
    }

    @Test
    fun wordLengthAndCountGuards() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("x".repeat(33))
        assertFalse(lexicon.contains("x".repeat(33)))
        lexicon.learnWord("ok", count = Int.MAX_VALUE)
        lexicon.learnWord("ok", count = Int.MAX_VALUE)
        assertTrue(lexicon.frequencyOf("ok") in 1..1_000_000)
    }

    @Test
    fun capEvictsStaleWordsButKeepsRecentAndSticky() {
        val f = file()
        val lexicon = UserLexicon(f)
        // Old cohort learned at generation 0, then aged far past the cap's
        // half-life by saving repeatedly (each dirty save ticks a generation).
        for (i in 0 until 3000) lexicon.learnWord("old$i")
        repeat(200) {
            lexicon.learnWord("clock")
            lexicon.save()
        }
        lexicon.addWord("cherished", boost = 200) // sticky
        // Fresh higher-count cohort pushes past MAX_WORDS = 10_000; the
        // eviction quota (size - 9000) is smaller than the old cohort, so
        // every evicted word must come from it and no fresh word may die.
        for (i in 0 until 8000) lexicon.learnWord("new$i", count = 2)
        lexicon.save() // triggers compaction
        val kept = UserLexicon(f)
        assertTrue("sticky word evicted", kept.contains("cherished"))
        for (i in 0 until 8000 step 997) {
            assertTrue("fresh word new$i evicted", kept.contains("new$i"))
        }
        val oldSurvivors = kept.allWords().count { it.first.startsWith("old") }
        assertTrue("no stale words were evicted", oldSurvivors < 3000)
        assertTrue(kept.allWords().size <= 10_000)
    }

    @Test
    fun trigramsServeRoundTripAndCap() {
        val f = file()
        val lexicon = UserLexicon(f)
        lexicon.learnTrigram("i", "was", "going")
        lexicon.learnTrigram("i", "was", "going")
        lexicon.learnTrigram("i", "was", "there")
        assertEquals(listOf("going", "there"), lexicon.nextWordsAfter("i", "was", 5))
        assertEquals(2, lexicon.trigramCount("i", "was", "going"))
        // A different context is a different table.
        assertTrue(lexicon.nextWordsAfter("he", "was", 5).isEmpty())
        lexicon.save()
        val back = UserLexicon(f)
        assertEquals(listOf("going", "there"), back.nextWordsAfter("i", "was", 5))
        // Follower cap applies to trigram contexts too.
        for (i in 0 until 40) back.learnTrigram("a", "b", "w$i")
        assertTrue(back.nextWordsAfter("a", "b", 50).size <= 32)
        // forget() scrubs contexts and followers that mention the word.
        back.learnTrigram("x", "target", "y")
        back.learnTrigram("p", "q", "target")
        back.forget("target")
        assertTrue(back.nextWordsAfter("x", "target", 5).isEmpty())
        assertTrue("target" !in back.nextWordsAfter("p", "q", 5))
    }

    @Test
    fun nullFileModeLearnsInMemoryOnly() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("ghost", 3)
        assertEquals(3, lexicon.frequencyOf("ghost"))
        lexicon.save() // must be a no-op, not a crash
        lexicon.clear()
        assertFalse(lexicon.contains("ghost"))
    }

    @Test
    fun forgetCleansEveryIndex() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("target", 5)
        lexicon.learnBigram("target", "next")
        lexicon.learnBigram("other", "target")
        lexicon.forget("target")
        assertFalse(lexicon.contains("target"))
        assertTrue(lexicon.nextWords("target", 5).isEmpty())
        assertFalse("target" in lexicon.followerCounts("other"))
    }

    @Test
    fun settingsAppRewriteWithoutWordGenIsTreatedAsFresh() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("mine", 3)
            save()
        }
        // The settings app rewrites words only (no wordGen for the new entry).
        f.writeText("""{"words":{"edited":7},"bigrams":{}}""")
        val back = UserLexicon(f)
        assertEquals(7, back.frequencyOf("edited"))
        assertFalse(back.contains("mine"))
    }

    @Test
    fun renameKeepsCountTagAndPairs() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("teh", 7, langId = "en")
            learnWord("hello", 3)
            learnWord("world", 3)
            learnBigram("hello", "teh")
            learnBigram("teh", "world")
            learnTrigram("hello", "teh", "world")
            assertTrue(rename("teh", "the"))
            save()
        }
        val back = UserLexicon(f)
        assertFalse(back.contains("teh"))
        assertEquals(7, back.frequencyOf("the"))
        assertEquals("en", back.languageOf("the"))
        assertEquals(null, back.languageOf("teh"))
        // Pairs where it followed, led, and sat in the middle all moved.
        assertEquals(1, back.bigramCount("hello", "the"))
        assertEquals(0, back.bigramCount("hello", "teh"))
        assertEquals(listOf("world"), back.nextWords("the", 3))
        assertEquals(1, back.trigramCount("hello", "the", "world"))
        assertEquals(0, back.trigramCount("hello", "teh", "world"))
    }

    @Test
    fun renameOntoExistingWordMergesCounts() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("colour", 4, langId = "en_gb")
        lexicon.learnWord("color", 6, langId = "en")
        lexicon.learnBigram("nice", "colour")
        lexicon.learnBigram("nice", "color")
        assertTrue(lexicon.rename("colour", "color"))
        assertEquals(10, lexicon.frequencyOf("color"))
        assertFalse(lexicon.contains("colour"))
        // The surviving word keeps its own tag, and the follower counts add.
        assertEquals("en", lexicon.languageOf("color"))
        assertEquals(2, lexicon.bigramCount("nice", "color"))
    }

    @Test
    fun renameRefusesUnknownEmptyAndSameSpelling() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("hello", 2)
        val before = lexicon.mutationCount()
        assertFalse(lexicon.rename("nope", "yes"))
        assertFalse(lexicon.rename("hello", "   "))
        assertFalse(lexicon.rename("hello", "x".repeat(33)))
        // Case folds to the same key, and matches the spelling already stored:
        // nothing to do (a case-only respelling that *changes* the spelling is
        // a case edit, covered below).
        assertFalse(lexicon.rename("hello", "hello"))
        assertFalse(lexicon.rename("nope", "Nope"))
        assertEquals(before, lexicon.mutationCount())
        assertEquals(2, lexicon.frequencyOf("hello"))
    }

    @Test
    fun setCountClampsAndReachesTheTrie() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("hello", 50)
        assertTrue(lexicon.setCount("hello", 5))
        assertEquals(5, lexicon.frequencyOf("hello"))
        assertEquals(5, lexicon.complete("hel", 1).single().frequency)
        assertTrue(lexicon.setCount("hello", 0))
        assertEquals(1, lexicon.frequencyOf("hello"))
        assertTrue(lexicon.setCount("hello", Int.MAX_VALUE))
        assertEquals(UserLexicon.MAX_COUNT, lexicon.frequencyOf("hello"))
        assertFalse(lexicon.setCount("unknown", 3))
        assertFalse(lexicon.contains("unknown"))
    }

    // ---- case memory (#44) ----

    @Test
    fun aTrustedCapitalIsRememberedAndAnUntrustedOneIsNot() {
        val lexicon = UserLexicon(null)
        // Auto-capitalize's capital teaches the word but not the spelling.
        lexicon.learnWord("Boston", caseEvidence = false)
        assertNull(lexicon.displayOf("boston"))
        // The user's own capital does.
        lexicon.learnWord("Boston", caseEvidence = true)
        assertEquals("Boston", lexicon.displayOf("boston"))
        // Looked up under either spelling; the key is what is stored.
        assertEquals("Boston", lexicon.displayOf("BOSTON"))
        assertTrue("Boston" to 2 in lexicon.allWords())
    }

    @Test
    fun ordinaryLowerCaseTypingVotesAStaleCapitalBackOut() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("SALE", caseEvidence = true)
        assertEquals("SALE", lexicon.displayOf("sale"))
        // One sighting in, one sighting out.
        lexicon.learnWord("sale", caseEvidence = true)
        assertNull(lexicon.displayOf("sale"))
        // An untrusted sighting is not a vote either way.
        lexicon.learnWord("Sale", caseEvidence = true)
        lexicon.learnWord("sale", caseEvidence = false)
        assertEquals("Sale", lexicon.displayOf("sale"))
    }

    @Test
    fun aWordAddedByHandKeepsItsSpellingAgainstOrdinaryTyping() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("iPhone")
        assertEquals("iPhone", lexicon.displayOf("iphone"))
        // A deliberate add outweighs a stray lower-case commit or two.
        lexicon.learnWord("iphone", caseEvidence = true)
        lexicon.learnWord("iphone", caseEvidence = true)
        assertEquals("iPhone", lexicon.displayOf("iphone"))
        // Adding it in lower case takes the spelling straight back off.
        lexicon.addWord("iphone")
        assertNull(lexicon.displayOf("iphone"))
    }

    @Test
    fun aCaseOnlyRespellingIsACaseEditRatherThanARename() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("boston", 5)
        assertTrue(lexicon.rename("boston", "Boston"))
        assertEquals("Boston", lexicon.displayOf("boston"))
        // The word itself never moved: same key, same count, same bigrams.
        assertEquals(5, lexicon.frequencyOf("boston"))
        assertTrue("Boston" to 5 in lexicon.allWords())
    }

    @Test
    fun aRenameCarriesTheNewSpellingAndDropsTheOldOne() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("bostn", 3, caseEvidence = true)
        assertTrue(lexicon.rename("bostn", "Boston"))
        assertNull(lexicon.displayOf("bostn"))
        assertEquals("Boston", lexicon.displayOf("boston"))
    }

    @Test
    fun spellingsSurviveASaveAndGoWithAForgottenWord() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("Boston", 3, caseEvidence = true)
            learnWord("paris", 3)
            save()
        }
        val reloaded = UserLexicon(f)
        assertEquals("Boston", reloaded.displayOf("boston"))
        assertNull(reloaded.displayOf("paris"))
        reloaded.forget("Boston")
        assertNull(reloaded.displayOf("boston"))
        reloaded.save()
        assertNull(UserLexicon(f).displayOf("boston"))
    }
}
