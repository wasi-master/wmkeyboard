package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaCodecTest {

    private val table = mapOf(
        "学校" to FuriganaCodec.Entry("がっこう", 20000),
        "学" to FuriganaCodec.Entry("がく", 5000),
        "校" to FuriganaCodec.Entry("こう", 5000),
        "行きます" to FuriganaCodec.Entry("いきます", 15000),
        "取り扱い" to FuriganaCodec.Entry("とりあつかい", 12000),
        "私" to FuriganaCodec.Entry("わたし", 25000),
    )

    private fun annotate(text: String) = FuriganaCodec.annotate(text) { table[it] }

    @Test
    fun `the whole word wins over its kanji singly, and kana around it stay`() {
        assertEquals("私（わたし）は学校（がっこう）へ行（い）きます。", annotate("私は学校へ行きます。"))
    }

    @Test
    fun `each kanji run gets its own reading`() {
        assertEquals("取（と）り扱（あつか）い", FuriganaCodec.ruby("取り扱い", "とりあつかい"))
        assertEquals("漢字（かんじ）", FuriganaCodec.ruby("漢字", "かんじ"))
    }

    @Test
    fun `a kanji nothing knows is left alone, and text with none is returned as it is`() {
        assertEquals("龘は学校（がっこう）", annotate("龘は学校"))
        assertEquals("ひらがな abc", annotate("ひらがな abc"))
    }
}
