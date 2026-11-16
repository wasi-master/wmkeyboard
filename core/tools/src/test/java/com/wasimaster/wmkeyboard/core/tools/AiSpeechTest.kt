package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSpeechTest {

    @Test
    fun `marks a voice would spell are taken out`() {
        val spoken = AiSpokenText.of("## Your alarm\n\nI set it for **7:00**, see [the clock](https://x.y/z) `now`")
        assertEquals("Your alarm. I set it for 7:00, see the clock now.", spoken)
    }

    @Test
    fun `lists and quotes read as sentences, code is skipped`() {
        val spoken = AiSpokenText.of("Steps:\n- Open the app\n2. Tap *Save*\n> A tip\n```kotlin\nval x = 1\n```\nDone!")
        assertEquals("Steps: Open the app. Tap Save. A tip. Done!", spoken)
    }

    @Test
    fun `citations, bare links and table rules are dropped`() {
        val spoken = AiSpokenText.of("It rains [1, 2] today https://example.com\n| City | Temp |\n|---|---|\n| Dhaka | 30 |")
        assertEquals("It rains today. City, Temp. Dhaka, 30.", spoken)
    }

    @Test
    fun `reasoning is never read`() {
        assertEquals("Yes.", AiSpokenText.of("<think>let me see</think>Yes."))
    }

    @Test
    fun `a long answer stops at a sentence`() {
        val long = "This is one sentence. ".repeat(400)
        val spoken = AiSpokenText.of(long)
        assertTrue(spoken.length <= AiSpokenText.MAX_CHARS)
        assertTrue(spoken.endsWith("."))
    }

    @Test
    fun `the setting reads its stored id and falls back to voice`() {
        assertEquals(AiSpeakReplies.ALWAYS, AiSpeakReplies.of("always"))
        assertEquals(AiSpeakReplies.OFF, AiSpeakReplies.of("off"))
        assertEquals(AiSpeakReplies.VOICE, AiSpeakReplies.of(null))
        assertEquals(AiSpeakReplies.VOICE, AiSpeakReplies.of("nonsense"))
    }
}
