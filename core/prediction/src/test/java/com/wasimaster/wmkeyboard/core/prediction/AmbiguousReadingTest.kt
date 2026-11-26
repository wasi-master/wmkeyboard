package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an ambiguous board's field shows and a space commits (#1572). */
class AmbiguousReadingTest {

    /** Compact QWERTY's keys for t-h-i: `ty`, `gh`, `ui`; anchors "tgu". */
    private val thi = KeySets.of(listOf("ty", "gh", "ui"))!!

    @Test
    fun `with autocorrect on the engine's order stands, a longer word included`() {
        assertEquals("this", AmbiguousReading.pick(listOf("this", "thi"), "tgu", thi, corrects = true))
    }

    @Test
    fun `with autocorrect off a longer word is cut to what the keys spell`() {
        assertEquals("thi", AmbiguousReading.pick(listOf("this", "think"), "tgu", thi, corrects = false))
        // A word of the keys' own length wins over a longer one ranked above it.
        assertEquals("yhu", AmbiguousReading.pick(listOf("this", "yhu"), "tgu", thi, corrects = false))
    }

    @Test
    fun `a letter picked by hand is kept`() {
        // The middle key's `h` was picked from its popup: the frame holds it alone.
        val picked = KeySets.of(listOf("ty", "h", "ui"))!!
        assertEquals("thu", AmbiguousReading.pick(listOf("tgi", "thu"), "thu", picked, corrects = true))
        assertEquals("thu", AmbiguousReading.pick(listOf("tgi", "thu"), "thu", picked, corrects = false))
    }

    @Test
    fun `a dropped or added key is never the reading while another word keeps them`() {
        assertEquals("thi", AmbiguousReading.pick(listOf("th", "thi"), "tgu", thi, corrects = true))
    }

    @Test
    fun `the case of the strip's word is kept`() {
        assertEquals("This", AmbiguousReading.pick(listOf("This"), "Tgu", thi, corrects = true))
        assertEquals("Thi", AmbiguousReading.pick(listOf("This"), "Tgu", thi, corrects = false))
    }

    @Test
    fun `nothing to pick from is no reading`() {
        assertNull(AmbiguousReading.pick(emptyList(), "tgu", thi, corrects = true))
    }
}
