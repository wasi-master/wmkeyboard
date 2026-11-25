package com.wasimaster.wmkeyboard.core.aicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNanoFitTest {

    private val turns = listOf(
        GeminiNano.Turn(fromUser = true, text = "first question"),
        GeminiNano.Turn(fromUser = false, text = "first answer"),
        GeminiNano.Turn(fromUser = true, text = "second question"),
    )

    @Test
    fun oneTurnIsTheInstructionsThenTheMessage() {
        assertEquals("Be brief.\n\nhello", GeminiNano.fold("Be brief.", listOf(GeminiNano.Turn(true, "hello"))))
    }

    @Test
    fun aConversationIsLaidOutAsATranscript() {
        val folded = GeminiNano.fold("", turns)
        assertTrue(folded.startsWith("Conversation so far:"))
        assertTrue("User: first question" in folded)
        assertTrue("Assistant: first answer" in folded)
        assertTrue(folded.endsWith("Reply to this latest message:\n\nsecond question"))
    }

    @Test
    fun whatFitsIsLeftAsItIs() {
        val whole = GeminiNano.fold("sys", turns)
        assertEquals(whole, GeminiNano.fit("sys", turns, whole.length))
    }

    @Test
    fun theOldestTurnsGoFirst() {
        val newestOnly = GeminiNano.fold("sys", turns.takeLast(1))
        val fitted = GeminiNano.fit("sys", turns, newestOnly.length + 5)
        assertEquals(newestOnly, fitted)
        assertFalse("first question" in fitted)
    }

    @Test
    fun aMessageTooLongKeepsItsEnd() {
        val long = GeminiNano.Turn(true, "a".repeat(500) + "the question")
        val fitted = GeminiNano.fit("sys", listOf(long), 100)
        assertTrue(fitted.length <= 100)
        assertTrue(fitted.startsWith("sys"))
        assertTrue(fitted.endsWith("the question"))
    }
}
