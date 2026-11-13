package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceSymbolsTest {

    @Test
    fun `words and sentence punctuation pass untouched`() {
        val plain = "Well, it's \"fine\" (mostly) - isn't it? Yes; 3 times: ok… ¡Hola! ¿Qué?"
        assertSame(plain, VoiceSymbols.strip(plain))
        // Other scripts' own marks are punctuation too.
        assertEquals("আমি ভালো আছি।", VoiceSymbols.strip("আমি ভালো আছি।"))
        assertEquals("هل أنت بخير؟", VoiceSymbols.strip("هل أنت بخير؟"))
        assertEquals("你好，世界。", VoiceSymbols.strip("你好，世界。"))
    }

    @Test
    fun `a recognizer's symbols are taken out`() {
        assertEquals("johnexample.com", VoiceSymbols.strip("john@example.com"))
        assertEquals("it costs 50", VoiceSymbols.strip("it costs $50"))
        assertEquals("down 5", VoiceSymbols.strip("down 5%"))
        assertEquals("salt pepper", VoiceSymbols.strip("salt & pepper"))
        assertEquals("ab", VoiceSymbols.strip("a/b"))
        assertEquals("tag this", VoiceSymbols.strip("#tag this"))
        assertEquals("2 2 4", VoiceSymbols.strip("2 + 2 = 4"))
        assertEquals("snakecase", VoiceSymbols.strip("snake_case"))
    }

    @Test
    fun `the space a symbol stood between is not doubled`() {
        assertEquals("pay 5 now", VoiceSymbols.strip("pay $ 5 now"))
        assertEquals("up 5", VoiceSymbols.strip("up 5 %"))
        assertEquals("5 now", VoiceSymbols.strip("$ 5 now"))
        // The text's own spacing elsewhere is kept as it was.
        assertEquals("one  two 3", VoiceSymbols.strip("one  two 3€"))
    }

    @Test
    fun `an emoji goes whole, glue and skin tone with it`() {
        assertEquals("I you", VoiceSymbols.strip("I ❤️ you"))
        assertEquals("nice", VoiceSymbols.strip("nice 👍🏽"))
        assertEquals("family", VoiceSymbols.strip("family 👨‍👩‍👧"))
    }

    @Test
    fun `full-width symbols go as the ASCII ones do`() {
        assertEquals("ab", VoiceSymbols.strip("a＠b"))
        assertEquals("50", VoiceSymbols.strip("50％"))
    }
}
