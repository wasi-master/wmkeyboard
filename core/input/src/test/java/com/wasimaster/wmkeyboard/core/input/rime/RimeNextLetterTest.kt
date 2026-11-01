package com.wasimaster.wmkeyboard.core.input.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RimeNextLetterTest {

    @Test
    fun `the letter after what was typed carries the spelling on`() {
        assertEquals('a', commentNextLetter("xi an", "xi"))
        assertEquals('a', commentNextLetter("xi an", "xi'"))
        assertEquals('n', commentNextLetter("bing", "bi"))
        assertEquals('i', commentNextLetter("bing", "B"))
    }

    @Test
    fun `a spelling already typed in full has no next key`() {
        assertNull(commentNextLetter("bing", "bing"))
    }

    @Test
    fun `a candidate the keys do not spell floats nowhere`() {
        assertNull(commentNextLetter("ni hao", "nh"))
        assertNull(commentNextLetter("ni", "nih"))
    }

    @Test
    fun `a comment that is not a plain spelling is not read`() {
        assertNull(commentNextLetter("", "bi"))
        assertNull(commentNextLetter("bīng", "b"))
        assertNull(commentNextLetter("ice 冰", "i"))
    }
}
