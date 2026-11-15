package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Test

/** Digit glyphs outside the BMP (Warang Citi) map whole, not as half surrogates. */
class NumeralDigitsTest {

    private val warang = NumeralSystem.WARANG_CITI.digits!!

    @Test
    fun `warang citi has ten glyphs of two chars each`() {
        assertEquals(10, warang.codePointCount(0, warang.length))
        assertEquals(String(Character.toChars(0x118E0)), digitGlyph(warang, 0))
        assertEquals(String(Character.toChars(0x118E9)), digitGlyph(warang, 9))
    }

    @Test
    fun `digits are rewritten by code point`() {
        val expected = String(Character.toChars(0x118E2)) + "-" + String(Character.toChars(0x118E0))
        assertEquals(expected, mapDigits("2-0", warang))
        assertEquals("২-০", mapDigits("2-0", NumeralSystem.BENGALI.digits))
    }
}
