package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class T9ZhuyinTest {

    @Test
    fun `a syllable is one digit to a symbol, on Samsung's groups`() {
        assertEquals("72", T9Zhuyin.encode("ㄋㄧ"))
        assertEquals("88", T9Zhuyin.encode("ㄏㄠ"))
        assertEquals("359", T9Zhuyin.encode("ㄓㄨㄤ"))
        assertEquals("", T9Zhuyin.encode("ni"))
    }

    @Test
    fun `the index holds every syllable a code can spell, as pinyin`() {
        val table = ZhuyinSyllables.buildTable(setOf("ni", "mi", "hao", "zhuang"))
        val index = T9Zhuyin.buildIndex(table)
        assertEquals(listOf("mi", "ni"), index["72"])
        assertEquals(listOf("hao"), index["88"])
        assertEquals(listOf("zhuang"), index["359"])
    }

    @Test
    fun `every key reaches a syllable of its own, so no keystroke is stuck`() {
        val table = ZhuyinSyllables.buildTable(
            setOf("a", "yi", "ai", "zhi", "an", "o", "wu", "ei", "chi", "en", "e", "yu", "ao", "shi", "ang", "ou", "ri"),
        )
        val index = T9Zhuyin.buildIndex(table)
        for (digit in "1234567890") assertEquals(true, index[digit.toString()]?.isNotEmpty())
    }
}
