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
