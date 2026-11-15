package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The tokens "Suppress symbols" keeps a whisper.cpp decoder off. */
class WhisperSymbolTokensTest {

    /** [tokens] in `nativeVocab`'s layout: two bytes of length, little-endian, then the bytes. */
    private fun vocab(vararg tokens: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (token in tokens) {
            out.write(token.size and 0xFF)
            out.write((token.size shr 8) and 0xFF)
            out.write(token)
        }
        return out.toByteArray()
    }

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `symbols go and words, digits and sentence punctuation stay`() {
        val ids = WhisperSymbolTokens.ids(
            vocab(
                utf8(" hello"), // 0
                utf8("%"), // 1
                utf8(" 50"), // 2
                utf8("@"), // 3
                utf8("."), // 4
                utf8("?"), // 5
                utf8(" $"), // 6
                utf8("'s"), // 7
                utf8("→"), // 8
                utf8("。"), // 9
                utf8("-"), // 10
                utf8("_"), // 11
            ),
        )
        assertArrayEquals(intArrayOf(1, 3, 6, 8, 11), ids)
    }

    @Test
    fun `an emoji's opening bytes go, half a Han character stays`() {
        val ids = WhisperSymbolTokens.ids(
            vocab(
                bytes(0xF0, 0x9F), // 0: the start of an emoji
                bytes(0x98, 0x80), // 1: its end, unreadable alone
                bytes(0xE4, 0xBD), // 2: half of 你
                utf8("😀"), // 3: a whole emoji
                bytes(0xF0, 0xA0, 0x80), // 4: the start of a rare Han character
                ByteArray(0), // 5: an empty token
            ),
        )
        assertArrayEquals(intArrayOf(0, 3), ids)
    }

    @Test
    fun `a truncated table stops at the last whole token`() {
        val whole = vocab(utf8("a"), utf8("#"))
        val cut = whole + bytes(5, 0, 0x41)
        assertArrayEquals(intArrayOf(1), WhisperSymbolTokens.ids(cut))
    }
}
