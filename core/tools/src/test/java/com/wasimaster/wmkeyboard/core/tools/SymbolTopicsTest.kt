package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The topic tabs LeanType's symbol palette has (gap L16): stars, weather, music, technical. */
class SymbolTopicsTest {

    private fun topic(id: String) = SymbolCatalog.categories.single { it.id == id }

    @Test
    fun `the four topics sit before Misc`() {
        val ids = SymbolCatalog.categories.map { it.id }
        assertEquals(
            listOf("Shapes & stars", "Stars", "Weather", "Music", "Technical", "Misc"),
            ids.takeLast(6),
        )
    }

    @Test
    fun `each topic holds its signature symbols`() {
        assertTrue("★" in topic("Stars").symbols && "✿" in topic("Stars").symbols)
        assertTrue("☀" in topic("Weather").symbols && "℃" in topic("Weather").symbols)
        assertTrue("♪" in topic("Music").symbols && "𝄞" in topic("Music").symbols) // 𝄞
        assertTrue("⌘" in topic("Technical").symbols && "⏎" in topic("Technical").symbols)
    }

    @Test
    fun `symbols moved out are not left behind`() {
        assertTrue("★" !in topic("Shapes & stars").symbols)
        assertTrue("♪" !in topic("Misc").symbols && "☀" !in topic("Misc").symbols && "⌘" !in topic("Misc").symbols)
    }

    @Test
    fun `every cell is one code point`() {
        for (id in listOf("Stars", "Weather", "Music", "Technical")) {
            for (symbol in topic(id).symbols) {
                assertEquals("$id: $symbol", 1, symbol.codePointCount(0, symbol.length))
            }
        }
    }
}
