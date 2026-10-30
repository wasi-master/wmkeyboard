package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

class TashkeelCodecTest {

    /** CATT's own ids and answer for this sentence, from its Python and the exported model. */
    @Test
    fun `a sentence reads and writes as CATT's own code does`() {
        val text = "ذهب الولد إلى المدرسة في الصباح"
        val ids = intArrayOf(7, 22, 18, 3, 10, 25, 34, 25, 19, 3, 8, 25, 16, 3, 10, 25, 26, 19, 30, 31, 28, 3, 20, 36, 3, 10, 25, 14, 18, 10, 13)
        val tokens = TashkeelCodec.tokenize(text)
        assertArrayEquals(ids, tokens.ids)
        val classes = intArrayOf(13, 13, 13, 3, 3, 15, 13, 13, 16, 3, 14, 13, 3, 3, 3, 15, 13, 15, 13, 13, 14, 3, 14, 3, 3, 3, 3, 6, 13, 3, 14)
        // Canonically equal: CATT writes the shadda first, and normalizing puts it after the vowel.
        assertEquals(
            Normalizer.normalize("ذَهَبَ الْوَلَدُ إِلَى الْمَدْرَسَةِ فِي الصَّبَاحِ", Normalizer.Form.NFC),
            Normalizer.normalize(TashkeelCodec.apply(text, tokens, classes), Normalizer.Form.NFC),
        )
    }

    @Test
    fun `old marks go and everything else keeps its place`() {
        assertEquals("كتب", TashkeelCodec.stripMarks("كَتَبَ"))
        val text = "كتب: Hi 12!"
        val tokens = TashkeelCodec.tokenize(text)
        assertArrayEquals(intArrayOf(24, 32, 18), tokens.ids)
        assertEquals("كَتَبَ: Hi 12!", TashkeelCodec.apply(text, tokens, intArrayOf(13, 13, 13)))
    }

    @Test
    fun `text with no Arabic letter asks the model nothing`() {
        assertEquals(0, TashkeelCodec.tokenize("hello, 123").ids.size)
    }

    @Test
    fun `a run of anything else is one space, and none at either end`() {
        assertArrayEquals(intArrayOf(24, 3, 32), TashkeelCodec.tokenize("«ك» - ت.").ids)
    }
}
