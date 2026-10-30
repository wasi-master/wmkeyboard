package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class MoakeyComposerTest {

    private fun typed(buffer: String) = MoakeyComposer.composeBuffer(buffer)

    @Test
    fun `a drag is a syllable and a tap is its final`() {
        assertEquals("바", typed("바"))
        assertEquals("박", typed("바ㄱ"))
        assertEquals("닭", typed("다ㄹㄱ"))
    }

    @Test
    fun `a drag brings its own initial, so a final stays where it was typed`() {
        assertEquals("바가", typed("바가"))
        assertEquals("박아", typed("바ㄱ아"))
        assertEquals("달사", typed("다ㄹ사"))
        assertEquals("바까", typed("바까"))
    }

    @Test
    fun `the syllable a drag types is its key and its vowel`() {
        assertEquals('배', MoakeyComposer.syllable('ㅂ', 'ㅐ'))
        assertEquals('꽤', MoakeyComposer.syllable('ㄲ', 'ㅙ'))
        assertEquals(null, MoakeyComposer.syllable('a', 'ㅏ'))
    }

    @Test
    fun `the side keys fold into the vowel before them`() {
        assertEquals("배", typed("바ㅣ"))
        assertEquals("뱌", typed("바ㆍ"))
        assertEquals("바", typed("바ㆍㆍ"))
        assertEquals("뵈", typed("보ㅣ"))
        assertEquals("봐", typed("보ㅣㆍ"))
        assertEquals("봬", typed("보ㅣㆍㅣ"))
        assertEquals("붜", typed("부ㅣㆍ"))
        assertEquals("뷔", typed("부ㅣㆍㆍ"))
        assertEquals("븨", typed("브ㅣ"))
    }

    @Test
    fun `a side key after a tapped consonant opens its syllable`() {
        assertEquals("기", typed("ㄱㅣ"))
        assertEquals("가", typed("ㄱㅣㆍ"))
        assertEquals("바기", typed("바ㄱㅣ"))
    }

    @Test
    fun `Vega spells as Naratgeul does and space ends a run`() {
        assertEquals("개", VegaComposer.composeBuffer("ㄱㅏㅣ"))
        assertEquals(true, VegaComposer.spaceEndsMultitap)
    }
}
