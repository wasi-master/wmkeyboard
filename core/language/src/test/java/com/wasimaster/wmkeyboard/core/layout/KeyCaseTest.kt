package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCaseTest {
    @Test
    fun `greek iota subscript stays one capital letter`() {
        assertEquals("ᾼ", keyUppercase("ᾳ")) // ᾳ → ᾼ, not ΑΙ
        assertEquals("ῌ", keyUppercase("ῃ")) // ῃ → ῌ
        assertEquals("ῼ", keyUppercase("ῳ")) // ῳ → ῼ
    }

    @Test
    fun `letters without a one-letter capital keep the full mapping`() {
        assertEquals("SS", keyUppercase("ß"))
        assertEquals("ʼN", keyUppercase("ŉ"))
    }

    @Test
    fun `ordinary letters`() {
        assertEquals("A", keyUppercase("a"))
        assertEquals("Ɑ", keyUppercase("ɑ"))
        assertEquals("É", keyUppercase("é"))
        assertEquals("ABC", keyUppercase("abc"))
    }
}
