package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWordScanTest {

    private val enders = charArrayOf('.', '!', '?', '।')

    private fun scan(text: String) = TextWordScan.scan(text, enders)

    private fun TextWordScan.Result.word(key: String) = words.first { it.key == key }

    @Test
    fun countsEachWordOnceAndKeepsFirstOffset() {
        val text = "the cat saw the dog"
        val result = scan(text)
        assertEquals(listOf("the", "cat", "saw", "dog"), result.words.map { it.key })
        val the = result.word("the")
        assertEquals(2, the.count)
        assertEquals(0, the.firstStart)
        assertEquals(3, the.firstLength)
        assertEquals(text.indexOf("dog"), result.word("dog").firstStart)
    }

    @Test
    fun combiningMarksStayInsideTheWord() {
        // হয়েছে ends in a vowel sign; a letters-only split would cut it short.
        val result = scan("কাজ হয়েছে")
        assertTrue(result.words.any { it.key == WordKey.of("হয়েছে") })
    }

    @Test
    fun joinersInsideAWordAreKept() {
        val keys = scan("don't use e-mail - ok").words.map { it.key }
        assertTrue("don't" in keys)
        assertTrue("e-mail" in keys)
        // A hyphen standing alone is punctuation, not a word.
        assertFalse(keys.any { it == "-" })
    }

    @Test
    fun linksAddressesAndNumbersAreSkippedAndBreakPairs() {
        val result = scan("visit https://example.com today, mail me@example.com or call 5551234 now")
        val keys = result.words.map { it.key }
        assertFalse(keys.any { it.contains("example") })
        assertFalse("5551234" in keys)
        assertFalse(("visit" to "today") in result.pairs)
        assertFalse(("call" to "now") in result.pairs)
        assertTrue(("today" to "mail") in result.pairs)
    }

    @Test
    fun sentenceStartCapitalIsNotCaseEvidence() {
        val result = scan("Hello there. Then Zelda came, and zelda left with Zelda.")
        val hello = result.word("hello")
        assertEquals("hello", hello.spelling)
        assertFalse(hello.caseEvidence)
        val zelda = result.word("zelda")
        assertEquals("Zelda", zelda.spelling)
        assertTrue(zelda.caseEvidence)
        assertEquals(3, zelda.count)
    }

    @Test
    fun majorityVotingForMidSentenceCapitalization() {
        // "Zelda" seen 2 times mid-sentence, "zelda" seen 1 time mid-sentence -> "Zelda" wins (2 > 1)
        val resultCap = scan("then Zelda came, and zelda left with Zelda.")
        val zeldaCap = resultCap.word("zelda")
        assertEquals("Zelda", zeldaCap.spelling)
        assertTrue(zeldaCap.caseEvidence)

        // "Zelda" seen 1 time mid-sentence, "zelda" seen 1 time mid-sentence -> "zelda" wins tie (1 <= 1)
        val resultTie = scan("then Zelda came, and zelda left.")
        val zeldaTie = resultTie.word("zelda")
        assertEquals("zelda", zeldaTie.spelling)
        assertFalse(zeldaTie.caseEvidence)
    }

    @Test
    fun pairsStopAtEndersAndLineBreaks() {
        val result = scan("good morning. see you\nsoon friend । ভালো থাকো")
        assertTrue(("good" to "morning") in result.pairs)
        assertFalse(("morning" to "see") in result.pairs)
        assertTrue(("see" to "you") in result.pairs)
        assertFalse(("you" to "soon") in result.pairs)
        assertFalse(("friend" to WordKey.of("ভালো")) in result.pairs)
    }

    @Test
    fun triplesAndSkipsAreCollectedOnce() {
        val result = scan("have a good day, have a good day")
        assertTrue(Triple("have", "a", "good") in result.triples)
        assertTrue(("have" to "good") in result.skips)
        // And the word three back, across two (#195).
        assertTrue(("have" to "day") in result.skips2)
        assertFalse(("have" to "have") in result.skips2)
        // The comma does not end a sentence, so the run carries across it.
        assertTrue(("day" to "have") in result.pairs)
        assertEquals(result.pairs.size, result.pairs.toSet().size)
    }

    @Test
    fun spacelessScriptsAreSkipped() {
        val result = scan("我们 hello 今天")
        assertEquals(listOf("hello"), result.words.map { it.key })
    }

    @Test
    fun emptyTextIsEmpty() {
        assertEquals(TextWordScan.Result.EMPTY, scan(""))
    }
}
