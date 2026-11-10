package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words dictation listens for (#305), and the server prompt built from them. */
class VoiceBiasTest {

    @Test
    fun `a typed list splits on commas and lines, once per word`() {
        assertEquals(
            listOf("Arian", "Mollik", "WMKeyboard"),
            VoiceBias.parseList(" Arian, Mollik ,\nWMKeyboard, arian,, "),
        )
    }

    @Test
    fun `an Arabic comma separates too`() {
        assertEquals(listOf("سارة", "Wasi"), VoiceBias.parseList("سارة، Wasi"))
    }

    @Test
    fun `the typed list comes first and the limit holds`() {
        val picked = VoiceBias.select(listOf("Zed", "Qux"), listOf("qux", "Alpha", "Beta"), limit = 3)
        assertEquals(listOf("Zed", "Qux", "Alpha"), picked)
    }

    @Test
    fun `the prompt ends with the user's text and the most important word next to it`() {
        val prompt = VoiceBias.serverPrompt(listOf("First", "Second", "Third"), "Casual chat.")
        assertEquals("Third, Second, First. Casual chat.", prompt)
    }

    @Test
    fun `nothing to say sends no prompt`() {
        assertNull(VoiceBias.serverPrompt(emptyList(), "  "))
        assertEquals("Only", VoiceBias.serverPrompt(listOf("Only"), ""))
        assertEquals("Just text", VoiceBias.serverPrompt(emptyList(), "Just text"))
    }

    @Test
    fun `a long prompt loses its least important words first`() {
        val words = (1..VoiceBias.PROMPT_WORD_LIMIT).map { "word$it".padEnd(40, 'x') }
        val prompt = VoiceBias.serverPrompt(words, "Tail.")!!
        assertTrue(prompt.length <= VoiceBias.PROMPT_MAX_CHARS)
        assertTrue(prompt.endsWith("${words.first()}. Tail."))
        assertTrue(!prompt.contains(words.last()))
        assertTrue(prompt.startsWith("word"))
    }

    @Test
    fun cutsToAServersLimitKeepingThePromptText() {
        val words = (1..100).map { "word$it" }
        val prompt = VoiceBias.serverPrompt(words, "Casual chat.", maxChars = 60)!!
        assertTrue(prompt.length <= 60)
        assertTrue(prompt.endsWith("word1. Casual chat."))
        val parts = VoiceBias.breakdown(words, listOf("word1"), "Casual chat.")
        assertEquals(12, parts.prompt)
        assertEquals(7, parts.custom)
        assertTrue(parts.total > 60)
    }
}
