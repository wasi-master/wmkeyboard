package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emoji and kaomoji search with stems: other forms of a word find what its
 * own form is filed under, and never above an exact keyword.
 */
class EmojiStemSearchTest {

    /** English plurals and -ing, enough to stand in for the engine here. */
    private fun stemOf(word: String): String = word.split(' ').joinToString(" ") {
        when {
            it.endsWith("ing") && it.length > 5 -> it.dropLast(3).let { s -> if (s.endsWith("nn")) s.dropLast(1) else s }
            it.endsWith("s") && it.length > 3 -> it.dropLast(1)
            else -> it
        }
    }

    private val fakeStem: (List<String>) -> List<String> = { words -> words.map(::stemOf) }

    private val entries = listOf(
        EmojiEntry("🏃", "people", listOf("run", "runner")),
        EmojiEntry("🐈", "animals", listOf("cat")),
        EmojiEntry("😺", "smileys", listOf("cats", "kitty")),
        EmojiEntry("🎉", "activities", listOf("party")),
    )

    @Test fun `another form of the word finds what the word is filed under`() {
        val search = EmojiSearch(entries, stem = fakeStem)
        assertTrue("🏃" in search.search("running").map { it.emoji })
    }

    @Test fun `an exact keyword ranks above a stem`() {
        val search = EmojiSearch(entries, stem = fakeStem)
        // "cats" is 😺's own keyword and only the plural of 🐈's.
        assertEquals(listOf("😺", "🐈"), search.search("cats").map { it.emoji }.take(2))
        assertEquals("🐈", search.search("cat").first().emoji)
    }

    @Test fun `without a stemmer nothing changes`() {
        val search = EmojiSearch(entries)
        assertTrue("🏃" !in search.search("running").map { it.emoji })
    }

    @Test fun `kaomoji tagged another form of the word come last`() {
        val library = KaomojiLibrary(
            items = listOf(
                Kaomoji(1, "(＾▽＾)", tags = listOf("laugh")),
                Kaomoji(2, "(≧▽≦)", tags = listOf("laughing")),
                Kaomoji(3, "(T_T)", tags = listOf("cry")),
            ),
            tags = listOf("laugh", "laughing", "cry"),
        )
        val stemmed: (Kaomoji) -> Boolean = { k -> k.tags.any { stemOf(it) == stemOf("laughs") } }
        assertEquals(listOf(1L, 2L), library.search("laughs", null, stemmed).map { it.id })
        // As typed first: "laughing" is a tag of 2, and only the stem of 1's.
        val forLaughing: (Kaomoji) -> Boolean = { k -> k.tags.any { stemOf(it) == stemOf("laughing") } }
        assertEquals(listOf(2L, 1L), library.search("laughing", null, forLaughing).map { it.id })
    }
}
