package com.wasimaster.wmkeyboard.core.voice.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroTextTest {

    @Test
    fun cardinals() {
        assertEquals("zero", KokoroText.cardinal(0))
        assertEquals("twelve", KokoroText.cardinal(12))
        assertEquals("forty-two", KokoroText.cardinal(42))
        assertEquals("one hundred five", KokoroText.cardinal(105))
        assertEquals("one thousand two hundred fifty", KokoroText.cardinal(1250))
        assertEquals("three million one", KokoroText.cardinal(3_000_001))
    }

    @Test
    fun ordinalsAndYears() {
        assertEquals("third", KokoroText.ordinal(3))
        assertEquals("twenty-first", KokoroText.ordinal(21))
        assertEquals("twelfth", KokoroText.ordinal(12))
        assertEquals("ninetieth", KokoroText.ordinal(90))
        assertEquals("one hundredth", KokoroText.ordinal(100))
        assertEquals("nineteen ninety-nine", KokoroText.year(1999))
        assertEquals("two thousand five", KokoroText.year(2005))
        assertEquals("nineteen oh five", KokoroText.year(1905))
        assertEquals("nineteen hundred", KokoroText.year(1900))
    }

    @Test
    fun sentences() {
        assertEquals(
            "It costs twelve dollars fifty cents on March third.",
            KokoroText.normalize("It costs $12.50 on March 3rd."),
        )
        assertEquals("Up three point five percent in nineteen ninety-nine", KokoroText.normalize("Up 3.5% in 1999"))
        assertEquals("one thousand two hundred fifty people", KokoroText.normalize("1,250 people"))
        assertEquals("call zero one seven one one", KokoroText.normalize("call 01711"))
    }

    @Test
    fun runTogetherWordsSplit() {
        assertEquals("W M Keyboard", KokoroText.normalize("WMKeyboard"))
        assertEquals("open Settings Screen", KokoroText.normalize("open SettingsScreen"))
        assertEquals("NASA said", KokoroText.normalize("NASA said"))
    }

    @Test
    fun chineseUntouched() {
        assertEquals("你好世界", KokoroText.normalize("你好世界"))
    }
}
