package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stacked strip's sums: reading words, finding them again, and where Back goes. */
class RecentStackTest {

    @Test
    fun `words are read with their places in the field`() {
        assertEquals(
            listOf(10 to "Isn't", 16 to "that", 21 to "great"),
            RecentStack.wordsIn("Isn't that great?", 10, cut = false),
        )
    }

    @Test
    fun `a word running into the end of a cut read is left out`() {
        assertEquals(listOf(0 to "see", 4 to "you"), RecentStack.wordsIn("see you tom", 0, cut = true))
        assertEquals(listOf(0 to "see", 4 to "you", 8 to "tom"), RecentStack.wordsIn("see you tom", 0, cut = false))
    }

    @Test
    fun `numbers and marks are not words`() {
        assertEquals(listOf(4 to "pm"), RecentStack.wordsIn("6 - pm 42", 0, cut = false))
    }

    @Test
    fun `words after an insertion move with it`() {
        // "in Gboard. Isn't that great?" with "your favourite keyboard" typed
        // over "Gboard": the words after it are found further along.
        val found = RecentStack.wordsIn("in your favourite keyboard. Isn't that great?", 0, cut = false)
        assertEquals(
            listOf(0, null, 28, 34, 39),
            RecentStack.relocate(listOf("in", "Gboard", "Isn't", "that", "great"), found),
        )
    }

    @Test
    fun `a deleted word does not take a later one of the same spelling`() {
        val found = listOf(10 to "is", 13 to "that")
        assertEquals(listOf(null, 10, 13), RecentStack.relocate(listOf("that", "is", "that"), found))
    }

    @Test
    fun `nothing read means every word has gone`() {
        assertEquals(listOf(null, null), RecentStack.relocate(listOf("a", "b"), emptyList()))
        assertEquals(emptyList<Int?>(), RecentStack.relocate(emptyList(), listOf(0 to "a")))
    }

    @Test
    fun `back goes past the word, its closing marks and one space`() {
        assertEquals("great? ".length, RecentStack.returnPoint("great? more", 5))
        assertEquals("great?".length, RecentStack.returnPoint("great?", 5))
        assertEquals("word ".length, RecentStack.returnPoint("word  two", 4))
        assertEquals("word".length, RecentStack.returnPoint("word\nnext", 4))
    }

    @Test
    fun `back never leaves the text it read`() {
        assertEquals(3, RecentStack.returnPoint("abc", 9))
        assertEquals(0, RecentStack.returnPoint("", 0))
    }
}
