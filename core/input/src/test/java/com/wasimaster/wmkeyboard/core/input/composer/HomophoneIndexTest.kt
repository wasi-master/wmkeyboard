package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomophoneIndexTest {

    private fun entry(reading: String, word: String, weight: Double) = HomophoneIndex.Entry(reading, word, weight)

    private val pinyin = HomophoneIndex.build(
        sequenceOf(
            entry("shi", "是", 1000.0),
            entry("shi", "事", 900.0),
            entry("shi", "时", 800.0),
            entry("shi", "市", 500.0),
            entry("xing", "行", 950.0),
            entry("xing", "星", 600.0),
            entry("xing", "性", 900.0),
            entry("hang", "航", 400.0),
            entry("hang", "行", 300.0),
            // Phrases are not characters: they never list.
            entry("shi", "事实", 950.0),
            entry("hang", "行业", 999.0),
        ),
    )

    @Test
    fun `a character lists the others of its reading, commonest first, itself left out`() {
        assertEquals(listOf("事", "时", "市"), pinyin.homophones("是"))
        assertEquals(listOf("是", "时", "市"), pinyin.homophones("事"))
    }

    @Test
    fun `a character of two readings lists its usual one first`() {
        // 行 leads xing and is second under hang.
        assertEquals(listOf("xing", "hang"), pinyin.readings("行"))
        assertEquals(listOf("性", "星", "航"), pinyin.homophones("行"))
    }

    @Test
    fun `the usual reading is the one the character leads the most`() {
        val index = HomophoneIndex.build(
            sequenceOf(
                entry("de", "的", 990.0),
                entry("de", "得", 500.0),
                entry("di", "地", 900.0),
                entry("di", "的", 10.0),
            ),
        )
        assertEquals(listOf("de", "di"), index.readings("的"))
        assertEquals(listOf("得", "地"), index.homophones("的"))
    }

    @Test
    fun `the limit cuts the list and an unknown character lists nothing`() {
        assertEquals(listOf("事"), pinyin.homophones("是", limit = 1))
        assertEquals(emptyList<String>(), pinyin.homophones("龘"))
        assertTrue(HomophoneIndex.EMPTY.isEmpty)
    }

    @Test
    fun `a kana is its own reading when the table does not list it`() {
        val kana = HomophoneIndex.build(
            sequenceOf(entry("か", "化", 30.0), entry("か", "下", 50.0), entry("か", "火", 40.0)),
        )
        assertEquals(listOf("下", "火", "化"), kana.homophones("か", fallbackReading = "か"))
        assertEquals("か", Homophones.toHiragana("カ"))
    }

    @Test
    fun `the same row from two sources counts once, at its best weight`() {
        val index = HomophoneIndex.build(
            sequenceOf(entry("ma", "妈", 10.0), entry("ma", "马", 20.0), entry("ma", "妈", 30.0)),
        )
        assertEquals(listOf("妈"), index.homophones("马"))
        assertEquals(listOf("马"), index.homophones("妈"))
    }

    @Test
    fun `only one ideograph or kana can be searched`() {
        assertTrue(HomophoneIndex.isSearchable("是"))
        assertTrue(HomophoneIndex.isSearchable("か"))
        assertTrue(HomophoneIndex.isSearchable("カ"))
        // Outside the Basic Multilingual Plane: one character, two UTF-16 units.
        assertTrue(HomophoneIndex.isSearchable("𠀀"))
        assertFalse(HomophoneIndex.isSearchable("是的"))
        assertFalse(HomophoneIndex.isSearchable("a"))
        assertFalse(HomophoneIndex.isSearchable("한"))
        assertFalse(HomophoneIndex.isSearchable(""))
    }

    @Test
    fun `a RIME dictionary's character lines are read and its phrases skipped`() {
        val lines = sequenceOf(
            "# Rime dictionary",
            "---",
            "name: luna_pinyin",
            "...",
            "的\tde\t99%",
            "的\tdi\t1%",
            "得\tde",
            "地\tdi",
            "的确\tdi que",
        )
        val index = HomophoneIndex.build(Homophones.rimeEntries(lines, ConversionWordLists.Reading.PINYIN))
        assertEquals(listOf("de", "di"), index.readings("的"))
        assertEquals(setOf("得", "地"), index.homophones("的").toSet())
        assertTrue(Homophones.singleCharacterLine("得\tde"))
        assertFalse(Homophones.singleCharacterLine("的确\tdi que"))
        assertFalse(Homophones.singleCharacterLine("name: luna_pinyin"))
    }
}
