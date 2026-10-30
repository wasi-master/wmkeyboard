package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinyinNextKeyTest {

    private val valid = setOf("ni", "hao", "shi", "jie", "an")
    private val table = DoublePinyin.Table(
        initials = mapOf('n' to "n", 'h' to "h", 'u' to "sh", 'j' to "j"),
        finals = mapOf('i' to listOf("i"), 'k' to listOf("ao"), 'x' to listOf("ie"), 'j' to listOf("an")),
    )

    @Test
    fun `an abbreviated reading continues on the next initial`() {
        val reading = listOf("ni", "hao", "shi", "jie")
        assertEquals('s', PinyinSyllables.nextInitial("nh", reading))
        assertEquals('j', PinyinSyllables.nextInitial("nihs", reading))
        assertEquals('h', PinyinSyllables.nextInitial("ni", reading))
    }

    @Test
    fun `a spent or differently spelled reading has no key`() {
        assertNull(PinyinSyllables.nextInitial("nh", listOf("ni", "hao")))
        assertNull(PinyinSyllables.nextInitial("xh", listOf("ni", "hao")))
    }

    private val fuzzy = setOf("shi", "si", "hao", "zhang", "zhan", "zang", "zan", "gou")

    private fun forms(syllable: String) = PinyinFuzzy.expand(syllable, fuzzy)

    @Test
    fun `a fuzzy spelling continues on the word's next letter`() {
        val reading = listOf("shi", "hao")
        assertEquals('h', PinyinSyllables.nextFuzzyLetter("si", reading, ::forms, abbreviated = false))
        assertEquals('a', PinyinSyllables.nextFuzzyLetter("sih", reading, ::forms, abbreviated = false))
        assertEquals('g', PinyinSyllables.nextFuzzyLetter("zan", listOf("zhang"), ::forms, abbreviated = false))
        assertNull(PinyinSyllables.nextFuzzyLetter("xih", reading, ::forms, abbreviated = false))
        assertNull(PinyinSyllables.nextFuzzyLetter("sihao", reading, ::forms, abbreviated = false))
    }

    @Test
    fun `fuzzy spelling works alongside initials`() {
        val reading = listOf("shi", "hao", "gou")
        assertNull(PinyinSyllables.nextFuzzyLetter("sg", reading, ::forms, abbreviated = false))
        assertEquals('o', PinyinSyllables.nextFuzzyLetter("shg", reading, ::forms, abbreviated = true))
    }

    @Test
    fun `double pinyin continues through a fuzzy syllable`() {
        val fuzzyTable = table.copy(initials = table.initials + ('s' to "s"))
        val inventory = valid + "si"
        val forms = { s: String -> PinyinFuzzy.expand(s, inventory) }
        assertEquals('h', DoublePinyin.nextKey("si", listOf("shi", "hao"), fuzzyTable, inventory, forms))
        assertNull(DoublePinyin.nextKey("si", listOf("shi", "hao"), fuzzyTable, inventory))
    }

    @Test
    fun `double pinyin continues on the scheme's keys`() {
        val reading = listOf("ni", "hao")
        assertEquals('h', DoublePinyin.nextKey("ni", reading, table, valid))
        assertEquals('k', DoublePinyin.nextKey("nih", reading, table, valid))
        assertNull(DoublePinyin.nextKey("nij", reading, table, valid))
        assertNull(DoublePinyin.nextKey("nihk", reading, table, valid))
    }

    @Test
    fun `a zero-initial syllable types through its lead key`() {
        assertEquals("aj", DoublePinyin.keysOf(table, valid)["an"])
    }
}
