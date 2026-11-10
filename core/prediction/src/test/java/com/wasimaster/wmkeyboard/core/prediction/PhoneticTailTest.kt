package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BornoPhonetic
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneticTailTest {

    private fun tail(buffer: String) = PhoneticTail.of(buffer, BornoPhonetic::transliterate)

    @Test
    fun aCommaAfterAWordIsItsOwnMark() {
        assertEquals(",", tail("accha,"))
    }

    @Test
    fun aCommaTheRulesReadStaysInTheWord() {
        // `,,` is Borno's hasant, `,.` a hasant with ZWNJ.
        assertEquals("", tail("k,,"))
        assertEquals("", tail("k,."))
    }

    @Test
    fun noTailWithoutAWordInFrontOrMarksBehind() {
        assertEquals("", tail(","))
        assertEquals("", tail("accha"))
        assertEquals("", tail(""))
    }

    @Test
    fun anApostropheIsNeverATail() {
        assertEquals("", tail("ki'"))
    }
}
