package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SelectionStatsTest {
    @Test fun countsLikeAWordProcessor() {
        val text = "The cat sat. It purred!\nA dog barked.\n\nSecond paragraph here."
        val stats = SelectionStats.of(text, Locale.ENGLISH)
        assertEquals(11, stats.words)
        assertEquals(text.length, stats.characters)
        assertEquals(8, stats.spaces)
        assertEquals(text.length - 8 - 3, stats.charactersNoSpaces)
        assertEquals(4, stats.lines)
        assertEquals(2, stats.paragraphs)
        assertEquals(4, stats.sentences)
        assertEquals("paragraph", stats.longestWord)
        assertEquals("A", stats.shortestWord)
        assertEquals("The cat sat. It purred!".length, stats.longestLine)
        assertEquals("A dog barked.".length, stats.shortestLine)
    }

    @Test fun anEmojiIsOneCharacter() {
        val stats = SelectionStats.of("hi 👍🏽", Locale.ENGLISH)
        assertEquals(4, stats.characters)
        assertEquals(1, stats.spaces)
        assertEquals(1, stats.words)
    }

    @Test fun emptyIsAllZero() {
        val stats = SelectionStats.of("", Locale.ENGLISH)
        assertEquals(0, stats.words)
        assertEquals(0, stats.lines)
        assertEquals(null, stats.longestWord)
    }
}
