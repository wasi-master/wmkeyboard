package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSwearingTest {

    private val list = setOf("fuck", "fucking", "shit", "fucker")
    private fun mask(text: String) = VoiceSwearing.mask(text) { it in list }

    @Test
    fun masksListedWordsKeepingTheFirstLetter() {
        assertEquals("what the f*** is this", mask("what the fuck is this"))
        assertEquals("F******* great", mask("Fucking great"))
    }

    @Test
    fun leavesOtherWordsAndPunctuationAlone() {
        assertEquals("Shitake soup, please.", mask("Shitake soup, please."))
        assertEquals("oh s***!", mask("oh shit!"))
    }

    @Test
    fun judgesTheStemBeforeAnApostrophe() {
        assertEquals("s***'s broken", mask("shit's broken"))
        assertEquals("it's fine", mask("it's fine"))
    }

    @Test
    fun hyphensSplitWords() {
        assertEquals("mother-f*****", mask("mother-fucker"))
    }

    @Test
    fun alreadyMaskedTextIsUntouched() {
        assertEquals("f*** that", mask("f*** that"))
    }
}
