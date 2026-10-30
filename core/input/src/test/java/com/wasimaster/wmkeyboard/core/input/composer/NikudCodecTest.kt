package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NikudCodecTest {

    private val vocab = NikudCodec.parseVocab("0020\n05d0\n05d5\n05dc\n05dd\n05e9\n0065\n")

    private fun id(c: Char) = vocab.getValue(c)

    @Test
    fun `each character is a token between the special ones`() {
        val tokens = NikudCodec.tokenize("של ם", vocab)
        assertArrayEquals(intArrayOf(1, id('ש'), id('ל'), id(' '), id('ם'), 2), tokens.ids)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), tokens.tokenOf)
    }

    @Test
    fun `an unknown script is one token however long, and upper case folds`() {
        val tokens = NikudCodec.tokenize("😀😀é E", vocab)
        // é stays composed after NFKC, so it is outside the known ranges too.
        assertArrayEquals(intArrayOf(1, 0, id(' '), id('e'), 2), tokens.ids)
    }

    @Test
    fun `a known character missing from the list is unknown on its own`() {
        assertArrayEquals(intArrayOf(1, 0, 0, 2), NikudCodec.tokenize("בב", vocab).ids)
    }

    @Test
    fun `points go on Hebrew letters, a mater takes none, and the shin gets its dot first`() {
        val text = "שלום!"
        val tokens = NikudCodec.tokenize(text, vocab)
        // Per token, point then shin: CLS, ש with shva, ל with nothing, ו a mater, ם with nothing, !, SEP.
        val classes = intArrayOf(0, 0, 3, 0, 0, 0, 1, 0, 0, 0, 9, 0, 0, 0)
        assertEquals("שְׁלום!", NikudCodec.apply(text, tokens, classes))
        val sin = intArrayOf(0, 0, 3, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("שְׂלום!", NikudCodec.apply(text, tokens, sin))
    }

    @Test
    fun `old points go and the block's punctuation stays`() {
        assertEquals("שלום־עולם|", NikudCodec.stripPoints("שָׁלוֹם־עוֹלָם|"))
    }

    @Test
    fun `a long text is cut at a sentence, then a space, then anywhere`() {
        assertEquals(listOf("אב גד.", " הו\n", "זח טי"), NikudCodec.chunks("אב גד. הו\nזח טי", 8))
        assertEquals(listOf("אבג ", "דהו"), NikudCodec.chunks("אבג דהו", 5))
        val pieces = NikudCodec.chunks("א".repeat(25), 10)
        assertEquals(listOf(10, 10, 5), pieces.map { it.length })
        assertTrue(NikudCodec.chunks("", 10).single().isEmpty())
    }
}
