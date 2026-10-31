package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContinuationTest {

    private val open = AiContinuation.Field(
        enabled = true,
        providerConfigured = true,
        secureField = false,
        noSuggestions = false,
        incognito = false,
    )

    @Test
    fun requestsOnlyWithTheSettingOnAndAProviderToAnswer() {
        assertTrue(AiContinuation.allowed(open))
        assertFalse(AiContinuation.allowed(open.copy(enabled = false)))
        assertFalse(AiContinuation.allowed(open.copy(providerConfigured = false)))
    }

    @Test
    fun neverRequestsInAPrivateField() {
        assertFalse(AiContinuation.allowed(open.copy(secureField = true)))
        assertFalse(AiContinuation.allowed(open.copy(noSuggestions = true)))
        assertFalse(AiContinuation.allowed(open.copy(incognito = true)))
    }

    @Test
    fun aHeldChipStaysDownInTheField() {
        assertFalse(AiContinuation.allowed(open.copy(suppressed = true)))
    }

    @Test
    fun waitsForAWordToEnd() {
        assertTrue(AiContinuation.atWordBoundary("I will be there "))
        assertTrue(AiContinuation.atWordBoundary("Thanks,"))
        assertFalse(AiContinuation.atWordBoundary("I will be the"))
        assertFalse(AiContinuation.atWordBoundary("don'"))
        assertFalse(AiContinuation.atWordBoundary(""))
    }

    @Test
    fun hanAndKanaEndAWord() {
        // #990: Chinese and Japanese type no space, so a committed conversion
        // is the end of a word; Korean spaces its words, so Hangul is not.
        assertTrue(AiContinuation.atWordBoundary("你好"))
        assertTrue(AiContinuation.atWordBoundary("ありがとう"))
        assertTrue(AiContinuation.atWordBoundary("コーヒー"))
        assertFalse(AiContinuation.atWordBoundary("안녕"))
    }

    @Test
    fun twoHanCharactersAreEnoughContext() {
        assertEquals("你好", AiContinuation.context("你好", 120, wordOnly = false))
        assertNull(AiContinuation.context("你", 120, wordOnly = false))
    }

    @Test
    fun hanTakesNoSpaceInFrontOfTheContinuation() {
        assertEquals("吗", AiContinuation.insertion("你好", "吗"))
        assertEquals("ค่ะ", AiContinuation.insertion("สวัสดี", "ค่ะ"))
        assertEquals("lo", AiContinuation.insertion("hel", "lo", midWord = true))
        assertEquals("世界", AiContinuation.insertion("hello", "世界"))
        assertEquals(" world", AiContinuation.insertion("hello", "world"))
    }

    @Test
    fun contextIsTheTailCutToAWholeWord() {
        val before = "The quick brown fox jumps over the "
        assertEquals("The quick brown fox jumps over the", AiContinuation.context(before, 120, wordOnly = false))
        // 16 characters is "x jumps over the": the half word goes.
        assertEquals("jumps over the", AiContinuation.context(before, 16, wordOnly = false))
        // 18 is "fox jumps over the", which already starts on a word.
        assertEquals("fox jumps over the", AiContinuation.context(before, 18, wordOnly = false))
    }

    @Test
    fun wordOnlySendsTheLastWord() {
        assertEquals("over", AiContinuation.context("jumps over ", 120, wordOnly = true))
    }

    @Test
    fun tooLittleTextIsNotSent() {
        assertNull(AiContinuation.context("a ", 120, wordOnly = false))
        assertNull(AiContinuation.context("   ", 120, wordOnly = false))
    }

    @Test
    fun acceptsAShortContinuation() {
        assertEquals("at the station", AiContinuation.accept("  at the station ", "I will meet you"))
    }

    @Test
    fun takesOffQuotesTheModelWasToldNotToUse() {
        assertEquals("at the station", AiContinuation.accept("\"at the station\"", "I will meet you"))
    }

    @Test
    fun rejectsAnEmptyAnswer() {
        assertNull(AiContinuation.accept("", "I will meet you"))
        assertNull(AiContinuation.accept("   ", "I will meet you"))
        assertNull(AiContinuation.accept("...", "I will meet you"))
    }

    @Test
    fun rejectsTheInputSaidBack() {
        assertNull(AiContinuation.accept("I will meet you", "I will meet you"))
        assertNull(AiContinuation.accept("meet you", "I will meet you"))
    }

    @Test
    fun keepsOnlyTheNewPartOfARestatement() {
        assertEquals("tomorrow", AiContinuation.accept("I will meet you tomorrow", "I will meet you"))
    }

    @Test
    fun rejectsAnAnswerOverTheCap() {
        val long = "a".repeat(AiContinuation.MAX_CHARS + 1)
        assertNull(AiContinuation.accept(long, "I will meet you"))
        assertEquals(
            "a".repeat(AiContinuation.MAX_CHARS),
            AiContinuation.accept("a".repeat(AiContinuation.MAX_CHARS), "I will meet you"),
        )
    }

    @Test
    fun rejectsASecondLine() {
        assertNull(AiContinuation.accept("at the station\nSee you", "I will meet you"))
        assertNull(AiContinuation.accept("at the\r\nstation", "I will meet you"))
        // A line break the model only trailed is not a second line.
        assertEquals("at the station", AiContinuation.accept("at the station\n", "I will meet you"))
    }

    @Test
    fun insertionAddsTheSpaceTheTextIsMissing() {
        assertEquals(" tomorrow", AiContinuation.insertion("see you", "tomorrow"))
        assertEquals("tomorrow", AiContinuation.insertion("see you ", "tomorrow"))
        assertEquals(", then", AiContinuation.insertion("see you", ", then"))
    }
}
