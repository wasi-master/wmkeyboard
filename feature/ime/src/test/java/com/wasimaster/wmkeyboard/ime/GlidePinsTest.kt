package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letters picked off keys mid-glide (#1519): what a pick puts on the grid,
 * which words still count once one is made, and the word typed when no word
 * has the pick.
 */
class GlidePinsTest {

    private fun pin(letter: Char, base: Char, segmentStart: Long = 0L, t: Long = 500L) =
        GlidePin(letter.code, base.code, 50f, 10f, segmentStart, t)

    private val grid = listOf(
        KeyCenter('g', 10f, 30f),
        KeyCenter('r', 20f, 10f),
        KeyCenter('u', 50f, 10f),
        KeyCenter('n', 60f, 30f),
        // A layout with ü on a key of its own as well.
        KeyCenter('ü', 90f, 10f),
    )

    @Test
    fun `a word must have the picked letters in the order they were picked`() {
        val ue = pin('ü', 'u')
        val sz = pin('ß', 's')
        assertTrue(GlidePins.spells("grün", listOf(ue)))
        assertFalse(GlidePins.spells("grun", listOf(ue)))
        assertTrue(GlidePins.spells("Grüße", listOf(ue, sz)))
        assertFalse(GlidePins.spells("ßü", listOf(ue, sz)))
    }

    @Test
    fun `a decomposed spelling still has the letter`() {
        assertTrue(GlidePins.spells("grün", listOf(pin('ü', 'u'))))
    }

    @Test
    fun `the pick sits at its key's centre and nowhere else`() {
        val pinned = GlidePins.pinned(grid, listOf(pin('ü', 'u')))
        val ue = pinned.filter { it.codePoint == 'ü'.code }
        assertEquals(listOf(KeyCenter('ü', 50f, 10f)), ue)
        assertEquals(grid.size, pinned.size)
        assertEquals(grid, pinned.base)
    }

    @Test
    fun `a grid with picks equals a list of the same keys`() {
        val pinned = GlidePins.pinned(grid, listOf(pin('ü', 'u')))
        val plain = ArrayList<KeyCenter>(pinned)
        assertEquals(plain, pinned)
        assertEquals(pinned, plain)
        assertEquals(plain.hashCode(), pinned.hashCode())
    }

    @Test
    fun `a pick belongs to the segment it was made in`() {
        val first = listOf(GesturePoint(0f, 0f, 100), GesturePoint(1f, 0f, 900))
        val second = listOf(GesturePoint(0f, 0f, 1_000), GesturePoint(1f, 0f, 1_800))
        val keys = GlidePins.pinned(grid, listOf(pin('ü', 'u', segmentStart = 1_000, t = 1_400)))
        assertTrue(GlidePins.on(keys, first).isEmpty())
        assertEquals(1, GlidePins.on(keys, second).size)
        // The segment read again with its walk from the spacebar cut off.
        val walkCut = listOf(GesturePoint(0.5f, 0f, 1_200), GesturePoint(1f, 0f, 1_800))
        assertEquals(1, GlidePins.on(keys, walkCut).size)
        assertTrue(GlidePins.on(grid, second).isEmpty())
    }

    @Test
    fun `the drawn letters take the pick in place of its key's letter`() {
        assertEquals("grün", GlidePins.splice("grun", listOf(pin('ü', 'u')), floatArrayOf(0.5f)))
        // The second u after the pick's: only the first one after the start is replaced.
        assertEquals("üu", GlidePins.splice("uu", listOf(pin('ü', 'u')), floatArrayOf(0.1f)))
    }

    @Test
    fun `a pick whose key was never marked goes in where the stroke was`() {
        assertEquals("grün", GlidePins.splice("grn", listOf(pin('ü', 'u')), floatArrayOf(0.6f)))
        assertEquals("ü", GlidePins.splice(null, listOf(pin('ü', 'u')), floatArrayOf(0.5f)))
    }

    @Test
    fun `only single letters can be picked`() {
        assertEquals('ü'.code, GlidePins.letterOf("ü"))
        assertEquals('ü'.code, GlidePins.letterOf("Ü"))
        assertEquals('ä'.code, GlidePins.letterOf("ä"))
        assertNull(GlidePins.letterOf("€"))
        assertNull(GlidePins.letterOf("7"))
        assertNull(GlidePins.letterOf("sch"))
        assertNull(GlidePins.letterOf(""))
    }

    @Test
    fun `fractions are the share of the stroke drawn before each pick`() {
        val points = listOf(GesturePoint(0f, 0f, 1_000), GesturePoint(1f, 0f, 2_000))
        val shares = GlidePins.fractions(points, listOf(pin('ü', 'u', t = 1_250)))
        assertEquals(0.25f, shares[0], 1e-6f)
    }
}
