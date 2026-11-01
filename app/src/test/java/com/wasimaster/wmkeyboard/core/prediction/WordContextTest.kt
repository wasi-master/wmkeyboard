package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordContextTest {

    private val enders = charArrayOf('.', '!', '?', '।') // incl. Bengali danda

    private fun before(text: String?) = WordContext.completedWordBefore(text, enders)

    @Test fun aScriptChangeEndsAWordWithNoSegmenterBehindIt() {
        assertEquals("ok", before("你好ok "))
        assertEquals("你好", before("ok你好 "))
        assertEquals("ok" to "你好", WordContext.lastTwoWords("你好ok ", enders))
        assertEquals("ー入り", before("ー入り "))
    }

    @Test fun aRunThatChangesScriptIsNotLearnedOnItsOwn() {
        assertFalse(WordContext.isLearnableWord("你好ok"))
        assertFalse(WordContext.isLearnableWord("A股"))
        assertTrue(WordContext.isLearnableWord("カタカナ漢字"))
        assertTrue(WordContext.isLearnableWord("hello"))
    }

    @Test fun fullStopYieldsSentenceStart() {
        assertEquals(WordContext.SENTENCE_START, before("Hello. "))
        assertEquals(WordContext.SENTENCE_START, before("Hello! "))
        assertEquals(WordContext.SENTENCE_START, before("Hello?  "))
        assertEquals(WordContext.SENTENCE_START, before("ভালো। "))
    }

    @Test fun commaKeepsTheWord() {
        assertEquals("hello", before("Hello, "))
        assertEquals("hello", before("Hello "))
        assertEquals("hello", before("Hello; "))
    }

    @Test fun midWordIsNullAndSoIsAnEditorThatCannotAnswer() {
        assertNull(before("Hel"))
        // Null is the editor saying it does not know, which is not the same as
        // saying there is nothing there.
        assertNull(before(null))
        assertNull(before("Hello5")) // digits count as still-inside-a-token
    }

    @Test fun anEmptyFieldIsASentenceStart() {
        // The caret at the very top of a field opens the first sentence, so
        // the openers the lexicon knows are exactly what belongs in the strip.
        // This answered null before #119, and null means "no context at all".
        assertEquals(WordContext.SENTENCE_START, before(""))
        assertEquals(
            WordContext.SENTENCE_START to null,
            WordContext.lastTwoWords("", enders),
        )
    }

    @Test fun enderTailIsASentenceStartEvenWithoutAPrecedingWord() {
        // "... " with nothing before it: whatever comes next opens a
        // sentence, so the sentinel applies here too.
        assertEquals(WordContext.SENTENCE_START, before("... "))
        assertEquals(WordContext.SENTENCE_START, before("Done... "))
        // A tail of non-ender punctuation with no word stays null.
        assertNull(before(", "))
    }

    @Test fun abbreviationLimitationIsDocumentedBehavior() {
        // "Dr. " reads as a sentence start — accepted limitation.
        assertEquals(WordContext.SENTENCE_START, before("Dr. "))
    }

    @Test fun lastTwoWordsRecoversBothOrDegrades() {
        fun two(text: String?) = WordContext.lastTwoWords(text, enders)
        assertEquals("was" to "i", two("I was "))
        assertEquals("was" to "i", two("I was, "))
        // A sentence ender between the two words kills prev2.
        assertEquals("was" to null, two("Stop. Was "))
        // Sentence start: prev1 is the sentinel, prev2 always null.
        assertEquals(WordContext.SENTENCE_START to null, two("I was. "))
        // Single word: no prev2.
        assertEquals("hello" to null, two("Hello "))
        // Mid-word: nothing.
        assertEquals(null to null, two("Hel"))
    }

    @Test fun sentinelIsRecognizableAndUntypeable() {
        assertTrue(WordContext.isSentinel(WordContext.SENTENCE_START))
        assertFalse(WordContext.isSentinel("hello"))
        assertFalse(WordContext.isSentinel(null))
        assertTrue(WordContext.SENTENCE_START.first().code == 1)
    }

    @Test fun sentinelWorksAsBigramContextButIsNeverOffered() {
        val lexicon = UserLexicon(null)
        lexicon.learnBigram(WordContext.SENTENCE_START, "the")
        lexicon.learnBigram(WordContext.SENTENCE_START, "the")
        lexicon.learnBigram(WordContext.SENTENCE_START, "when")
        assertEquals(listOf("the", "when"), lexicon.nextWords(WordContext.SENTENCE_START, 5))
        // learnWord with the sentinel never lands (non-letter guard is the
        // trim in the service, but the lexicon itself must also be safe).
        lexicon.learnWord(WordContext.SENTENCE_START)
        val engine = SuggestionEngine(
            Trie(),
            com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex(emptyList()),
            lexicon,
        )
        val offers = engine.suggest("", previousWord = WordContext.SENTENCE_START)
        assertEquals(listOf("the", "when"), offers)
        assertFalse(offers.any { WordContext.isSentinel(it) })
    }

    @Test fun aCombiningMarkIsPartOfTheWordNotABoundary() {
        // হয়েছে is হ য ় ে ছ ে — it *ends* in a vowel sign, and two of its six
        // characters are combining marks. Asking Char.isLetter() where a word
        // ends therefore truncates it: this used to hand back a bare ছ, so
        // every Bengali bigram was keyed on a single consonant and the corpus
        // pack could never match anything. Devanagari, Tamil, Thai, Arabic and
        // Hebrew all spell words this way too.
        assertEquals("হয়েছে", before("হয়েছে "))
        assertEquals("করা", before("করা "))
        assertEquals("কিয়া", before("কিয়া, "))
        assertEquals("किया", before("किया "))       // Devanagari matra
        assertEquals("ก่อน", before("ก่อน "))          // Thai tone mark

        // Still inside the word while the mark is the last thing typed.
        assertNull(before("হয়েছে"))
        assertNull(before("किया"))
    }

    @Test fun aContractionIsOneContextWord() {
        // The apostrophe is a letter inside the word, not the end of it: this
        // used to hand the next suggestion "s" as the word before it, and the
        // pair the keyboard learned was `s` followed by whatever came next
        // (#240). Both apostrophes, since another keyboard may have typed the
        // typographic one into the field.
        assertEquals("that's", before("that's "))
        assertEquals("don't", before("I don't "))
        assertEquals("that\u2019s", before("that\u2019s "))
        assertEquals("l'albero", before("l'albero "))
        // Only medial. A quote around a word, and the one a possessive ends
        // on, are punctuation and still end it.
        assertEquals("hello", before("'hello' "))
        assertEquals("developers", before("the developers' "))
    }

    @Test fun everyScriptsApostropheHoldsItsWordTogether() {
        // Not an English problem, and not an ASCII one. Each of these is a
        // word in a language the keyboard ships, and each was read as its
        // last fragment before #240.
        assertEquals("c'hoar", before("c'hoar "))          // Breton, a digraph
        assertEquals("об'єкт", before("об'єкт "))          // Ukrainian
        assertEquals("l'home", before("l'home "))          // Catalan
        assertEquals("auto's", before("auto's "))          // Dutch plural
        assertEquals("d'ith", before("d'ith "))            // Irish
        assertEquals("ג׳ינס", before("ג׳ינס "))              // Hebrew geresh
        // A smart-quote keyboard writes U+2019, a careless one U+2018.
        assertEquals("that\u2019s", before("that\u2019s "))
        assertEquals("that\u2018s", before("that\u2018s "))
        // The modifier letters are letters already and need no rule:
        // Hawaiian ʻokina, and the apostrophe Kazakh and Uzbek write.
        assertEquals("hawaiʻi", before("Hawaiʻi "))
        assertEquals("oʻzbek", before("oʻzbek "))
    }

    @Test fun bothContextWordsSurviveAnApostrophe() {
        fun two(text: String?) = WordContext.lastTwoWords(text, enders)
        assertEquals("don't" to "i", two("I don't "))
        assertEquals("s" to "that's", two("that's s "))
        val (p1, p2, p3) = WordContext.lastThreeWords("I don't think ", enders)
        assertEquals(Triple("think", "don't", "i"), Triple(p1, p2, p3))
    }

    @Test fun contextIsReadInTheStoresOwnSpelling() {
        // Field text is whatever some keyboard or paste left there, so both
        // spellings of য় turn up. They must key the same word — see WordKey.
        // These two lines look identical and differ only in their bytes, and
        // tools fold the precomposed one into the decomposed one given half a
        // chance — which is exactly what happened while this test was being
        // written, and what the assertFalse caught. Keep the assertion: without
        // it a fold leaves every check below passing against itself.
        val decomposed = "হয়েছে"
        val precomposed = "হয়েছে"
        assertFalse("the two spellings must differ as strings", decomposed == precomposed)
        assertEquals(decomposed, before("$precomposed "))
        assertEquals(before("$decomposed "), before("$precomposed "))
    }

    @Test fun lastThreeWordsRecoversAllOrDegrades() {
        fun three(text: String?) = WordContext.lastThreeWords(text, enders)
        assertEquals(Triple("that", "so", "gotten"), three("It has gotten so that "))
        assertEquals(Triple("that", "so", "gotten"), three("gotten, so that, "))
        // A boundary anywhere behind prev2 kills prev3 alone; behind prev1, both.
        assertEquals(Triple("that", "so", null), three("Stop. So that "))
        assertEquals(Triple("that", null, null), three("Stop. That "))
        assertEquals(Triple("was", "i", null), three("I was "))
        assertEquals(Triple(WordContext.SENTENCE_START, null, null), three("I was. "))
        assertEquals(Triple(null, null, null), three(null))
    }

    @Test fun bothContextWordsSurviveCombiningMarks() {
        val (prev1, prev2) = WordContext.lastTwoWords("করা হয়েছে ", enders)
        assertEquals("হয়েছে", prev1)
        assertEquals("করা", prev2)
    }

    // ---- what the keyboard may learn on its own (#185) ----

    @Test fun plainWordsAreLearnable() {
        for (word in listOf("manager", "don't", "don’t", "well-known", "b2b", "mp3", "covid19", "হয়েছে", "I")) {
            assertTrue(word, WordContext.isLearnableWord(word))
        }
    }

    @Test fun indicJoinersInsideAWordAreLearnable() {
        // ZWJ and ZWNJ spell real Bengali words; each between letters, never doubled.
        assertTrue(WordContext.isLearnableWord("র\u200D্য"))
        assertTrue(WordContext.isLearnableWord("কি\u200Cছু"))
    }

    @Test fun aSymbolGluedOnIsNotAWord() {
        // The reporter's word, and the shapes one gate has to refuse together.
        for (word in listOf("manager\"", "\"manager", "man\"ager", "hello!", "(hello", "a--b", "don''t", "-hello", "hello-")) {
            assertFalse(word, WordContext.isLearnableWord(word))
        }
    }

    @Test fun aNumberIsNotAWord() {
        assertFalse(WordContext.isLearnableWord("2024"))
        assertFalse(WordContext.isLearnableWord("12-34"))
        assertFalse(WordContext.isLearnableWord(""))
    }

    // Thai puts no space after a word, so text ending in Thai letters is
    // between words, and the words are the ones the segmenter finds.
    @Test fun thaiContextIsTheSegmentedWords() {
        val three = WordContext.lastThreeWords("ทุกสองสัปดาห์", enders)
        assertEquals(Triple("สัปดาห์", "สอง", "ทุก"), three)
        assertEquals(three, WordContext.lastThreeWords("ทุกสองสัปดาห์ ", enders))
        assertEquals("สัปดาห์", before("ทุกสองสัปดาห์"))
        assertEquals("สอง" to "ทุก", WordContext.lastTwoWords("ทุกสอง", enders))
    }

    @Test fun aShortThaiRunBorrowsTheWordsBeforeIt() {
        assertEquals(Triple("สอง", "ทุก", "hello"), WordContext.lastThreeWords("Hello ทุกสอง", enders))
        assertEquals(Triple("ทุก", "hello", "say"), WordContext.lastThreeWords("say hello ทุก", enders))
        // A sentence boundary still ends the context.
        assertEquals(Triple("ทุก", null, null), WordContext.lastThreeWords("Hello. ทุก", enders))
        assertEquals(Triple("ทุก", null, null), WordContext.lastThreeWords("ทุก", enders))
        assertEquals(WordContext.SENTENCE_START, before("ทุกสอง. "))
    }
}
