package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1291: typed letters a swipe finished split off when the merge reads them away. */
class TypedHeadStandsAloneTest {

    private val words = setOf("i", "a", "in", "play", "about", "upset")
    private val isWord: (String) -> Boolean = { it.lowercase() in words }

    @Test
    fun `a typed word the merge gave up stands alone`() {
        assertTrue(typedHeadStandsAlone("I", "upset", isWord))
    }

    @Test
    fun `a merge that keeps the typed letters is the swipe finishing them`() {
        assertFalse(typedHeadStandsAlone("a", "about", isWord))
        assertFalse(typedHeadStandsAlone("In", "inside", isWord))
    }

    @Test
    fun `letters that are no word on their own never split`() {
        assertFalse(typedHeadStandsAlone("pl", "upset", isWord))
    }

    @Test
    fun `nothing typed or nothing read is no split`() {
        assertFalse(typedHeadStandsAlone(null, "upset", isWord))
        assertFalse(typedHeadStandsAlone("", "upset", isWord))
        assertFalse(typedHeadStandsAlone("I", null, isWord))
    }
}
