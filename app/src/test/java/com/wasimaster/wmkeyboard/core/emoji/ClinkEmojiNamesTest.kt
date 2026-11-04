package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Clink language pack's `<code>.emoji.json`. */
class ClinkEmojiNamesTest {

    private val file = """{"version": 1,
        "aliases": {"⚖": ["Balans", "skaal", "skaal", " "]},
        "names": {"⚖": "skaal"},
        "stopwords": ["die"]}"""

    @Test
    fun `aliases are keywords and names are names`() {
        val pack = ClinkEmojiNames.decode(file)!!
        assertEquals(listOf("balans", "skaal"), pack.keywords["⚖"])
        assertEquals("skaal", pack.names["⚖"])
    }

    @Test
    fun `the keyword pack loader reads the file as it is`() {
        val pack = EmojiKeywordPack.load(file.byteInputStream(), "af")
        assertEquals(listOf("balans", "skaal"), pack.keywords["⚖"])
        assertEquals("af", pack.langId)
    }

    @Test
    fun `other JSON is not this format`() {
        assertNull(ClinkEmojiNames.decode("""{"emoji": "x"}"""))
    }
}
