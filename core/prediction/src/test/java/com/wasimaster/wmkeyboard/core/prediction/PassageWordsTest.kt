package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageWordsTest {

    private val common = setOf("read", "the", "chapter", "for", "exam", "and")

    @Test
    fun aCopiedWordCompletesInItsOwnCase() {
        val words = PassageWords.of(listOf("Read Laxmikant chapter 5 for the exam"))
        assertEquals("Laxmikant", words.complete("lax", 3).first().word)
        assertTrue(words.contains("laxmikant"))
    }

    @Test
    fun twoWordsOfAPassageBringTheNext() {
        val words = PassageWords.of(listOf("Read Laxmikant chapter 5 for the exam"))
        assertEquals(listOf("chapter"), words.nextWords("read", "laxmikant") { it in common })
        assertEquals(listOf("exam"), words.nextWords("for", "the") { it in common })
    }

    @Test
    fun aCommonWordAloneChainsNothing() {
        val words = PassageWords.of(listOf("the exam is on monday and the party on friday"))
        assertTrue(words.nextWords(null, "the") { it in common }.isEmpty())
    }

    @Test
    fun aWordNoListHoldsChainsOnAlone() {
        val words = PassageWords.of(listOf("Ask Laxmikant about polity"))
        assertEquals(listOf("about"), words.nextWords(null, "laxmikant") { it in common })
    }

    @Test
    fun aSentenceEndBreaksTheChain() {
        val words = PassageWords.of(listOf("Call Rahim today. Tomorrow is busy"))
        assertTrue(words.nextWords("rahim", "today") { false }.isEmpty())
    }

    @Test
    fun theMostUsedFollowerComesFirst() {
        val words = PassageWords.of(listOf("buy milk now", "buy milk later", "buy milk later"))
        assertEquals("later", words.nextWords("buy", "milk") { false }.first())
    }

    @Test
    fun nothingToReadIsEmpty() {
        assertTrue(PassageWords.of(listOf("", "  ", "12 34")).isEmpty)
        assertFalse(PassageWords.of(listOf("hello there")).isEmpty)
    }

    @Test
    fun readingStopsAtTheBudget() {
        val filler = "alpha ".repeat(PassageWords.MAX_CHARS / 6 + 10)
        val words = PassageWords.of(listOf(filler, "Zanzibar"))
        assertFalse(words.contains("zanzibar"))
    }
}
