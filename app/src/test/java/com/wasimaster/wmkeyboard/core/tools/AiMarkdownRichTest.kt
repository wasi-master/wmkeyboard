package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rich-text subset notes write (#1586), as the reader draws it. */
class AiMarkdownRichTest {

    @Test
    fun spansCarryTheirValuesAndNest() {
        val inline = AiMarkdownBlocks.inline("<span style=\"color:#e53935\">a<span style=\"font-size:1.5em\">b</span>c</span> d")
        assertEquals("abc d", inline.text)
        assertTrue(inline.spans.any { it.style == Style.COLOR && it.value == "#e53935" && it.start == 0 && it.end == 3 })
        assertTrue(inline.spans.any { it.style == Style.SIZE && it.value == "1.5em" && it.start == 1 && it.end == 2 })
    }

    @Test
    fun underlineStylesAndHighlightColours() {
        val inline = AiMarkdownBlocks.inline("<u style=\"text-decoration-style:wavy\">w</u> <mark style=\"background:#66bb6a\">g</mark> <mark>y</mark>")
        assertEquals("w g y", inline.text)
        assertTrue(inline.spans.any { it.style == Style.UNDERLINE && it.value == "wavy" })
        assertTrue(inline.spans.any { it.style == Style.BACKGROUND && it.value == "#66bb6a" })
        assertTrue(inline.spans.any { it.style == Style.HIGHLIGHT })
    }

    @Test
    fun anUnclosedTagStaysText() {
        assertEquals("<span style=\"color:red\">x", AiMarkdownBlocks.inline("<span style=\"color:red\">x").text)
    }

    @Test
    fun picturesOnOneLineShareIt() {
        val blocks = AiMarkdownBlocks.parse("![](attachments/a.jpg) ![](attachments/b.jpg)\n![](c.png)")
        val images = blocks.filterIsInstance<Block.Image>()
        assertEquals(3, images.size)
        assertEquals(listOf(0, 0, 1), images.map { it.line })
    }
}
