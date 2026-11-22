package com.wasimaster.wmkeyboard.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ranking rule the stemmed searches share: what was typed, then other forms of it. */
class StemMatchTest {

    @Test fun `every query stem has to be a word of the text`() {
        assertTrue(StemMatch.containsAll("run", "i run late"))
        assertTrue(StemMatch.containsAll("run shoe", "a shoe to run in"))
        assertFalse(StemMatch.containsAll("run shoe", "a shoe to walk in"))
        // A word, not a part of one: the literal search finds those.
        assertFalse(StemMatch.containsAll("run", "brunch"))
    }

    @Test fun `no words match nothing`() {
        assertFalse(StemMatch.containsAll("", "anything"))
        assertFalse(StemMatch.containsAll("run", ""))
    }

    @Test fun `a long text is searched as well as a short one`() {
        val long = (1..40).joinToString(" ") { "w$it" } + " run"
        assertTrue(StemMatch.containsAll("run w3", long))
    }

    @Test fun `what matches as typed comes before what matches by stem`() {
        // "running" typed: "Running late" holds it, "run to the shop" only its stem.
        val items = listOf("run to the shop", "Running late", "walk home", "she runs")
        val stems = mapOf(
            "run to the shop" to "run to the shop",
            "Running late" to "run late",
            "walk home" to "walk home",
            "she runs" to "she run",
        )
        val found = StemMatch.literalFirst(
            items,
            literal = { it.contains("running", ignoreCase = true) },
            stemmed = { StemMatch.containsAll("run", stems.getValue(it)) },
        )
        assertEquals(listOf("Running late", "run to the shop", "she runs"), found)
    }

    @Test fun `each group keeps the order the search had`() {
        val items = listOf("b2", "a1", "b1", "a2")
        val found = StemMatch.literalFirst(items, literal = { it.startsWith("a") }, stemmed = { it.startsWith("b") })
        assertEquals(listOf("a1", "a2", "b2", "b1"), found)
    }

    @Test fun `an item the literal search takes is never asked about again`() {
        var asked = 0
        StemMatch.literalFirst(listOf("x", "y"), literal = { it == "x" }, stemmed = { asked++; false })
        assertEquals(1, asked)
    }

    @Test fun `without a stem matcher it is the plain filter`() {
        val found = StemMatch.literalFirst(listOf("one", "two", "three"), literal = { 'o' in it }, stemmed = null)
        assertEquals(listOf("one", "two"), found)
    }
}
