package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's own Japanese words that Mozc is handed (#1254). */
class JapaneseUserWordsTest {

    private val words = JapaneseUserWords.of(
        listOf(
            "よろ" to "よろしくお願いします",
            "よろ" to "よろしく",
            "よろ" to "よろしくお願いします",
            "よろしく" to "宜しく",
            "よろこ" to "喜",
            "おつ" to "お疲れ様です",
        ),
    )

    @Test
    fun `an exact reading gives its words in list order, repeats dropped`() {
        assertEquals(listOf("よろしくお願いします", "よろしく"), words.exact("よろ"))
        assertEquals(listOf("お疲れ様です"), words.exact("おつ"))
        assertTrue(words.exact("よ").isEmpty())
        assertEquals(5, words.size)
    }

    @Test
    fun `completions carry on past the reading, shortest first`() {
        assertEquals(listOf("喜", "宜しく"), words.completions("よろ", 5))
        assertEquals(listOf("喜"), words.completions("よろ", 1))
        // One kana is too little to suggest anything from.
        assertTrue(words.completions("よ", 5).isEmpty())
        assertTrue(words.completions("おつ", 5).isEmpty())
    }

    @Test
    fun `the same rows the conversion table reads come through`() {
        val rows = ConversionWordLists.rows(
            sequenceOf("# Gboard personal dictionary", "よろ\tよろしくお願いします", "メール\tメールアドレス"),
            ConversionWordLists.Reading.KANA,
        )
        val fromList = JapaneseUserWords.of(rows.map { it.reading to it.word })
        assertEquals(listOf("よろしくお願いします"), fromList.exact("よろ"))
        // A katakana reading is read as the hiragana Mozc is given.
        assertEquals(listOf("メールアドレス"), fromList.exact("めーる"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(JapaneseUserWords.of(emptyList()).isEmpty)
        assertTrue(JapaneseUserWords.EMPTY.completions("よろ", 3).isEmpty())
    }
}
