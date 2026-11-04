package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards against a finger skidding on glass during fast typing being read
 * as a glide: the mid-word start distance, and the slip a claimed stroke is
 * turned back into a tap for.
 */
class GlideSlipGuardTest {

    private val keyWidth = 100f

    @Test
    fun `outside a word a glide starts at the start slop`() {
        assertEquals(30f, glideStartDistancePx(slopPx = 30f, keyWidthPx = keyWidth, midWord = false), 0f)
    }

    @Test
    fun `between two tapped letters a glide has to cover a key and a half`() {
        assertEquals(150f, glideStartDistancePx(slopPx = 30f, keyWidthPx = keyWidth, midWord = true), 0f)
    }

    @Test
    fun `a cooldown slop already past the mid-word distance is kept`() {
        assertEquals(200f, glideStartDistancePx(slopPx = 200f, keyWidthPx = keyWidth, midWord = true), 0f)
    }

    @Test
    fun `an unmeasured grid gates nothing extra`() {
        assertEquals(30f, glideStartDistancePx(slopPx = 30f, keyWidthPx = 0f, midWord = true), 0f)
    }

    @Test
    fun `a quick skid onto the next key during typing is a slip`() {
        assertTrue(glideStrokeIsSlip(reachPx = 110f, durationMs = 90, sinceTapMs = 200, keyWidthPx = keyWidth))
    }

    @Test
    fun `the same skid long after the last tap is a word`() {
        assertFalse(glideStrokeIsSlip(reachPx = 110f, durationMs = 90, sinceTapMs = 2_000, keyWidthPx = keyWidth))
    }

    @Test
    fun `a stroke drawn slower than a skid is a word`() {
        assertFalse(glideStrokeIsSlip(reachPx = 110f, durationMs = 220, sinceTapMs = 200, keyWidthPx = keyWidth))
    }

    @Test
    fun `a fast stroke across several keys is a word`() {
        assertFalse(glideStrokeIsSlip(reachPx = 300f, durationMs = 120, sinceTapMs = 200, keyWidthPx = keyWidth))
    }

    @Test
    fun `letters and combining marks continue a word, the rest end it`() {
        assertTrue(typesWordLetters("e"))
        assertTrue(typesWordLetters("ক"))
        assertTrue(typesWordLetters("া")) // Bengali vowel sign aa, a spacing mark
        assertTrue(typesWordLetters("́")) // combining acute
        assertFalse(typesWordLetters(" "))
        assertFalse(typesWordLetters("."))
        assertFalse(typesWordLetters("3"))
        assertFalse(typesWordLetters(""))
    }
}
