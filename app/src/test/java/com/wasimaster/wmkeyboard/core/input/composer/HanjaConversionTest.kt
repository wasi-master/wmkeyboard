package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HanjaConversionTest {

    @Before
    fun setUp() {
        CjkDictionaries.hanja = ConversionDictionary.parse(
            sequenceOf(
                "학교\t學校\t1000",
                "학교\t學敎\t999",
                "학\t學\t1000",
                "에\t埃\t1000",
            ),
        )
    }

    @After
    fun tearDown() {
        CjkDictionaries.hanja = ConversionDictionary.EMPTY
    }

    @Test
    fun `a listed word offers its own Hanja, commonest first`() {
        assertEquals(listOf("學校", "學敎"), HanjaConversion.candidates("학교", 12))
    }

    @Test
    fun `an ending stays in Hangul behind the longest listed prefix`() {
        assertEquals(listOf("學校에", "學敎에"), HanjaConversion.candidates("학교에", 12))
    }

    @Test
    fun `one syllable never answers as a prefix`() {
        assertEquals(emptyList<String>(), HanjaConversion.candidates("학생", 12))
    }

    @Test
    fun `a single syllable word still converts`() {
        assertEquals(listOf("學"), HanjaConversion.candidates("학", 12))
    }

    @Test
    fun `half a syllable and an empty table offer nothing`() {
        assertEquals(emptyList<String>(), HanjaConversion.candidates("학ㄱ", 12))
        CjkDictionaries.hanja = ConversionDictionary.EMPTY
        assertEquals(emptyList<String>(), HanjaConversion.candidates("학교", 12))
    }

    @Test
    fun `the limit cuts the list`() {
        assertEquals(listOf("學校"), HanjaConversion.candidates("학교", 1))
    }

    @Test
    fun `Han marks a chip as Hanja and Hangul does not`() {
        assertTrue(HanjaConversion.isHanja("學校에"))
        assertFalse(HanjaConversion.isHanja("학교에"))
    }
}
