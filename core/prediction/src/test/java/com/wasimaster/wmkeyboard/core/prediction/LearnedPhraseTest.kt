package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.LearnedPhrase.Follower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1291: the phrase chip offers what this user writes, not only the model's guess. */
class LearnedPhraseTest {

    private val pairs = mapOf(
        "main" to listOf(Follower("ghar", 6), Follower("bhi", 1)),
        "ghar" to listOf(Follower("ja", 5)),
        "ja" to listOf(Follower("raha", 4), Follower("rahi", 1)),
        "am" to listOf(Follower("going", 20), Follower("not", 4)),
        "going" to listOf(Follower("to", 9)),
    )
    private val triples = mapOf(
        ("i" to "am") to listOf(Follower("going", 4), Follower("not", 4)),
        ("we" to "am") to listOf(Follower("going", 1)),
    )

    private fun chain(back1: String, back2: String? = null, accepts: (String) -> Boolean = { true }) =
        LearnedPhrase.chain(
            back1, back2, maxWords = 3,
            triples = { b2, b1 -> triples[b2 to b1].orEmpty() },
            pairs = { pairs[it].orEmpty() },
            accepts = accepts,
        )

    @Test
    fun `a habit the user keeps chains into a phrase`() {
        assertEquals(listOf("ghar", "ja", "raha"), chain("main"))
    }

    @Test
    fun `a two-word context with evidence decides alone`() {
        // "I am" has gone both ways equally: nothing, whatever "am" alone says.
        assertTrue(chain("am", "i").isEmpty())
    }

    @Test
    fun `a thin two-word context falls back to the word before`() {
        assertEquals(listOf("going", "to"), chain("am", "we"))
    }

    @Test
    fun `one word is no phrase`() {
        assertTrue(chain("going").isEmpty())
    }

    @Test
    fun `a word that may not be offered ends the chain`() {
        assertEquals(listOf("ghar", "ja"), chain("main") { it != "raha" })
        assertTrue(chain("main") { it != "ja" }.isEmpty())
    }

    @Test
    fun `a follower needs sightings and a majority`() {
        assertNull(LearnedPhrase.dominant(listOf(Follower("a", 2))))
        assertNull(LearnedPhrase.dominant(listOf(Follower("a", 3), Follower("b", 2), Follower("c", 2))))
        assertNull(LearnedPhrase.dominant(listOf(Follower("a", 3), Follower("b", 3))))
        assertEquals("a", LearnedPhrase.dominant(listOf(Follower("a", 4), Follower("b", 3))))
        assertNull(LearnedPhrase.dominant(emptyList()))
    }
}
