package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RomanizedCantoneseComposerTest {

    private val yale = RomanizedCantoneseComposer.YALE
    private val pinyin = RomanizedCantoneseComposer.CANTONESE_PINYIN

    @Before
    @After
    fun reset() {
        CjkDictionaries.jyutping = ConversionDictionary.EMPTY
        JyutpingSyllables.valid = emptySet()
        CjkConfig.lazyJyutping = false
    }

    private fun load(vararg rows: String) {
        CjkDictionaries.jyutping = ConversionDictionary.parse(rows.asSequence())
        JyutpingSyllables.valid = rows.map { it.substringBefore('\t') }.filter { '+' !in it }.toSet() + SYLLABLES
    }

    @Test
    fun `yale spells jyutping's initials and finals its own way`() {
        fun y(s: String) = CantoneseSpellings.of(CantoneseScheme.YALE, s).first()
        assertEquals("ji", y("zi"))
        assertEquals("chi", y("ci"))
        assertEquals("yat", y("jat"))
        assertEquals("yu", y("jyu"))
        assertEquals("yun", y("jyun"))
        assertEquals("syu", y("syu"))
        assertEquals("jyu", y("zyu"))
        assertEquals("yuk", y("juk"))
        assertEquals("heung", y("hoeng"))
        assertEquals("seui", y("seoi"))
        assertEquals("cheun", y("ceon"))
        assertEquals("ma", y("maa"))
        assertEquals("baak", y("baak"))
        assertEquals("gwong", y("gwong"))
    }

    @Test
    fun `yale marks a low tone with h after the vowels`() {
        fun low(s: String) = CantoneseSpellings.of(CantoneseScheme.YALE, s)
        assertTrue("neih" in low("nei"))
        assertTrue("sihk" in low("sik"))
        assertTrue("yuhng" in low("jung"))
        assertTrue("mouh" in low("mou"))
        assertTrue("mah" in low("maa"))
        // Long a is what plenty of people type for an open aa.
        assertTrue("maa" in low("maa"))
        assertTrue("mh" in low("m"))
        assertTrue("ngh" in low("ng"))
    }

    @Test
    fun `cantonese pinyin spells jyutping's initials and finals its own way`() {
        fun p(s: String) = CantoneseSpellings.of(CantoneseScheme.CANTONESE_PINYIN, s).single()
        assertEquals("dzi", p("zi"))
        assertEquals("tsi", p("ci"))
        assertEquals("jy", p("jyu"))
        assertEquals("jyn", p("jyun"))
        assertEquals("jyt", p("jyut"))
        assertEquals("soey", p("seoi"))
        assertEquals("tsoen", p("ceon"))
        assertEquals("oet", p("eot"))
        // Everything else is written as in Jyutping.
        assertEquals("maa", p("maa"))
        assertEquals("hoeng", p("hoeng"))
        assertEquals("gwong", p("gwong"))
    }

    @Test
    fun `a yale buffer converts through the jyutping pack`() {
        load("nei\t你\t100", "hou\t好\t90", "neihou\t你好\t200", "ou\t奧\t10")
        assertEquals("你好", yale.candidates("neihou").first())
        assertEquals("你好", yale.candidates("neihhou").first())
        assertEquals("你好", yale.candidates("nei5hou2").first())
        assertEquals(6, yale.consumedFor("neihou", "你好"))
        assertEquals(8, yale.consumedFor("nei5hou2", "你好"))
    }

    @Test
    fun `a yale h takes both readings and the words decide`() {
        // `neihou` is nei+hou and neih+ou. Longest-first would only see the second.
        load("nei\t你\t100", "hou\t好\t90", "ou\t奧\t10")
        assertEquals("你好", yale.candidates("neihou").first())
        assertEquals(6, yale.consumedFor("neihou", "你好"))
    }

    @Test
    fun `a pick leaves the tail to convert`() {
        load("nei\t你\t100", "hou\t好\t90")
        assertEquals(listOf("好"), yale.candidates("hou"))
        val consumed = yale.consumedFor("neihhou", "你")
        assertEquals(4, consumed)
        assertEquals("好", yale.candidates("neihhou".substring(consumed)).first())
    }

    @Test
    fun `cantonese pinyin converts its own spellings and tones`() {
        load("jyut\t粵\t100", "jyu\t語\t90", "jyutjyu\t粵語\t200", "seoi\t水\t50")
        assertEquals("粵語", pinyin.candidates("jytjy").first())
        assertEquals("粵語", pinyin.candidates("jyt9jy5").first())
        assertEquals(7, pinyin.consumedFor("jyt9jy5", "粵語"))
        assertEquals("水", pinyin.candidates("soey").first())
        // Jyutping's own spelling of it is not this scheme's.
        assertFalse("水" in pinyin.candidates("seoi"))
    }

    @Test
    fun `yale tone digits stop at six`() {
        load("nei\t你\t100")
        assertEquals(4, yale.consumedFor("nei5", "你"))
        assertEquals(3, yale.consumedFor("nei7", "你"))
        assertEquals(4, pinyin.consumedFor("nei7", "你"))
    }

    @Test
    fun `a half typed syllable reads as what it starts`() {
        load("ci\t次\t100", "zi\t字\t90")
        // `ch` is no syllable, and the pack spells it `c`.
        assertEquals(listOf("次"), yale.candidates("ch"))
        assertEquals(listOf("字"), pinyin.candidates("dz"))
        assertEquals(listOf<String>(), yale.candidates("q"))
    }

    @Test
    fun `lazy pronunciation reads merged syllables but not the scheme's own keys`() {
        load("nei\t你\t100", "lei\t厘\t100", "zi\t知\t100", "ji\t二\t100")
        assertEquals(listOf("厘"), yale.candidates("lei"))
        CjkConfig.lazyJyutping = true
        assertEquals(listOf("厘", "你"), yale.candidates("lei"))
        // Yale's j is Jyutping's z and its y Jyutping's j, so the z-j merger,
        // meant for Yale typed on a Jyutping board, must stay out of this.
        assertEquals(listOf("知"), yale.candidates("ji"))
        assertEquals(listOf("二"), yale.candidates("yi"))
    }

    @Test
    fun `both schemes ask for the jyutping pack and share its history`() {
        assertEquals("jyutping", yale.missingPack)
        assertEquals("jyutping", pinyin.missingPack)
        assertTrue(yale.bufferDigits && !yale.digitsStartBuffer && yale.readsLatinLetters)
    }

    private companion object {
        val SYLLABLES = setOf("nei", "lei", "hou", "ou", "jyut", "jyu", "seoi", "ci", "zi", "ji")
    }
}
