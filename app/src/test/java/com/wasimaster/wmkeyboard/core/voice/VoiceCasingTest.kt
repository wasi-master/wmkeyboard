package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recognizer's per-utterance capital, kept or taken back by where the words land (#182). */
class VoiceCasingTest {

    @Test
    fun `a capital mid-sentence is lowered`() {
        assertEquals("on this device", VoiceCasing.apply("On this device", sentenceStart = false))
    }

    @Test
    fun `a capital at a sentence start stays`() {
        assertEquals("On this device", VoiceCasing.apply("On this device", sentenceStart = true))
    }

    @Test
    fun `the pronoun I and its contractions keep their capital anywhere`() {
        assertEquals("I think so", VoiceCasing.apply("I think so", sentenceStart = false))
        assertEquals("I'm here", VoiceCasing.apply("I'm here", sentenceStart = false))
        assertEquals("I’ll go", VoiceCasing.apply("I’ll go", sentenceStart = false))
        assertEquals("I", VoiceCasing.apply("I", sentenceStart = false))
    }

    @Test
    fun `a word with a second capital is spelled that way and is left alone`() {
        assertEquals("NASA launched", VoiceCasing.apply("NASA launched", sentenceStart = false))
        assertEquals("McKinsey said", VoiceCasing.apply("McKinsey said", sentenceStart = false))
    }

    @Test
    fun `only the first word is touched`() {
        assertEquals("the Boston office", VoiceCasing.apply("The Boston office", sentenceStart = false))
    }

    @Test
    fun `text that opens without a capital passes through`() {
        assertEquals("", VoiceCasing.apply("", sentenceStart = false))
        assertEquals("hello", VoiceCasing.apply("hello", sentenceStart = false))
        assertEquals("42 things", VoiceCasing.apply("42 things", sentenceStart = false))
        assertEquals("Ich", VoiceCasing.apply("Ich", sentenceStart = false).replaceFirstChar { it.uppercaseChar() })
    }

    @Test
    fun `an empty field opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence(null))
        assertTrue(VoiceCasing.startsSentence(""))
    }

    @Test
    fun `a field holding only blanks opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence(" "))
        assertTrue(VoiceCasing.startsSentence("   "))
    }

    @Test
    fun `a full stop behind the caret opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence("Done."))
        assertTrue(VoiceCasing.startsSentence("Done. "))
        assertTrue(VoiceCasing.startsSentence("Really?  "))
        assertTrue(VoiceCasing.startsSentence("Stop! "))
        assertTrue(VoiceCasing.startsSentence("and so on… "))
    }

    @Test
    fun `a script's own full stop ends a sentence too`() {
        assertTrue(VoiceCasing.startsSentence("শেষ। "))
        assertTrue(VoiceCasing.startsSentence("終わり。"))
        assertTrue(VoiceCasing.startsSentence("վերջ։ "))
    }

    @Test
    fun `a terminator behind a closing quote or bracket still counts`() {
        assertTrue(VoiceCasing.startsSentence("""he said "stop." """))
        assertTrue(VoiceCasing.startsSentence("(for now.) "))
        assertTrue(VoiceCasing.startsSentence("he said ‘go.’ "))
    }

    @Test
    fun `a new line opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence("a list\n"))
        assertTrue(VoiceCasing.startsSentence("a list\n  "))
    }

    @Test
    fun `mid-sentence does not`() {
        assertFalse(VoiceCasing.startsSentence("and then, "))
        assertFalse(VoiceCasing.startsSentence("the "))
        assertFalse(VoiceCasing.startsSentence("the"))
        assertFalse(VoiceCasing.startsSentence("it won't "))
        assertFalse(VoiceCasing.startsSentence("a quote \""))
    }

    @Test
    fun `every sentence after the first opens with a capital`() {
        assertEquals(
            "Hello. How are you",
            VoiceCasing.apply("Hello. how are you", sentenceStart = true),
        )
        assertEquals(
            "one. Two! Three? Four",
            VoiceCasing.apply("One. two! three? four", sentenceStart = false),
        )
        assertEquals(
            "He said \"go.\" Then he left",
            VoiceCasing.apply("He said \"go.\" then he left", sentenceStart = true),
        )
    }

    @Test
    fun `a stop that is not a sentence end raises nothing`() {
        // An initial, a decimal, and a stop with no gap after it.
        assertEquals("e.g. this one", VoiceCasing.apply("e.g. this one", sentenceStart = false))
        assertEquals("at 9 a.m. tomorrow", VoiceCasing.apply("at 9 a.m. tomorrow", sentenceStart = false))
        assertEquals("it was 3.5 metres", VoiceCasing.apply("it was 3.5 metres", sentenceStart = false))
        assertEquals("go to site.com now", VoiceCasing.apply("go to site.com now", sentenceStart = false))
    }

    @Test
    fun `a sentence already capitalized is left as it is`() {
        assertEquals("Hello. World", VoiceCasing.apply("Hello. World", sentenceStart = true))
        assertEquals("Hello. NASA", VoiceCasing.apply("Hello. NASA", sentenceStart = true))
    }

    @Test
    fun `a lower-case phrase that opens a sentence is raised`() {
        // A recognizer that formats nothing (Vosk, Sherpa): the phrase after a
        // dictated full stop went in as "Done. and then".
        assertEquals("And then", VoiceCasing.apply("and then", sentenceStart = true))
        assertEquals("Hello. How are you", VoiceCasing.apply("hello. how are you", sentenceStart = true))
    }

    @Test
    fun `a word spelled with an inner capital is not raised`() {
        assertEquals("iPhone is here", VoiceCasing.apply("iPhone is here", sentenceStart = true))
        assertEquals("eBay sold it", VoiceCasing.apply("eBay sold it", sentenceStart = true))
    }

    @Test
    fun `without raise the opening is left as heard`() {
        assertEquals("and then", VoiceCasing.apply("and then", sentenceStart = true, raise = false))
        assertEquals("on this device", VoiceCasing.apply("On this device", sentenceStart = false, raise = false))
    }

    @Test
    fun `the field's own caps mode has no say — an empty field keeps the capital`() {
        // The regression this pins: a plain inputType="text" field never asks
        // for sentence capitals, and Automatic capitals may be off, yet a
        // dictation into the empty field still opens a sentence (#182).
        val sentenceStart = VoiceCasing.startsSentence("")
        assertEquals("Hello world", VoiceCasing.apply("Hello world", sentenceStart))
    }

    @Test
    fun `every word opens with a capital for Capitalize every word (#1534)`() {
        assertEquals("New York City", VoiceCasing.apply("new york city", sentenceStart = false, everyWord = true))
        assertEquals(
            "\"Hello\" (World) iPhone",
            VoiceCasing.apply("\"hello\" (world) iPhone", sentenceStart = true, everyWord = true),
        )
    }
}
