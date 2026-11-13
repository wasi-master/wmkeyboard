package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.ui.LongWordFit
import com.wasimaster.wmkeyboard.core.ui.TextFitting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The width-fitting maths behind the suggestion strip's squeezed and shrunk words. */
class SuggestionTextFitTest {

    @Test
    fun `a word that already fits is left alone`() {
        val fit = fitSuggestionText(measuredWidthPx = 80f, availableWidthPx = 120f)
        assertEquals(1f, fit.fontScale, 0f)
        assertEquals(1f, fit.scaleX, 0f)
        assertFalse(fit.condensed)
    }

    @Test
    fun `a word exactly as wide as its slot is left alone`() {
        val fit = fitSuggestionText(measuredWidthPx = 120f, availableWidthPx = 120f)
        assertEquals(1f, fit.fontScale, 0f)
        assertEquals(1f, fit.scaleX, 0f)
    }

    @Test
    fun `mild overflow condenses the glyphs only`() {
        // 10% too wide: 0.91 is above the 0.7 squeeze floor, so the font keeps its size.
        val fit = fitSuggestionText(measuredWidthPx = 132f, availableWidthPx = 120f)
        assertEquals(120f / 132f, fit.scaleX, 0.001f)
        assertEquals(1f, fit.fontScale, 0f)
        assertTrue(fit.condensed)
    }

    @Test
    fun `heavy overflow pins the squeeze floor and shrinks the rest`() {
        // Needs 0.6; the glyphs stop at 0.7, so the font takes 0.6 / 0.7.
        val fit = fitSuggestionText(measuredWidthPx = 200f, availableWidthPx = 120f)
        assertEquals(0.7f, fit.scaleX, 0.001f)
        assertEquals(0.6f / 0.7f, fit.fontScale, 0.001f)
        assertTrue(fit.condensed)
    }

    @Test
    fun `an unfittable word bottoms out at both floors`() {
        val fit = fitSuggestionText(measuredWidthPx = 1000f, availableWidthPx = 120f)
        assertEquals(0.68f, fit.fontScale, 0.001f)
        assertEquals(0.7f, fit.scaleX, 0.001f)
    }

    @Test
    fun `the two floors together fit a word about twice the slot`() {
        val fit = fitSuggestionText(measuredWidthPx = 1000f, availableWidthPx = 120f)
        val widest = 1f / (fit.fontScale * fit.scaleX)
        assertEquals(2.1f, widest, 0.02f)
    }

    @Test
    fun `longer words never scale up more than shorter ones`() {
        // Monotonic: growing the word can only ever shrink the drawn result.
        var previous = 1f
        for (width in 100..400 step 10) {
            val fit = fitSuggestionText(width.toFloat(), availableWidthPx = 120f)
            val effective = fit.fontScale * fit.scaleX
            assertTrue("width=$width went back up", effective <= previous + 1e-6f)
            previous = effective
        }
    }

    @Test
    fun `shrink first takes the font down before narrowing`() {
        val fitting = TextFitting(LongWordFit.SHRINK_THEN_NARROW)
        val mild = fitSuggestionText(measuredWidthPx = 132f, availableWidthPx = 120f, fitting = fitting)
        assertEquals(120f / 132f, mild.fontScale, 0.001f)
        assertEquals(1f, mild.scaleX, 0f)
        val heavy = fitSuggestionText(measuredWidthPx = 200f, availableWidthPx = 120f, fitting = fitting)
        assertEquals(0.68f, heavy.fontScale, 0.001f)
        assertEquals(0.6f / 0.68f, heavy.scaleX, 0.001f)
    }

    @Test
    fun `one-step modes stop at their own floor`() {
        val narrow = fitSuggestionText(1000f, 120f, TextFitting(LongWordFit.NARROW_ONLY, minScaleX = 0.6f))
        assertEquals(1f, narrow.fontScale, 0f)
        assertEquals(0.6f, narrow.scaleX, 0.001f)
        val shrink = fitSuggestionText(1000f, 120f, TextFitting(LongWordFit.SHRINK_ONLY, minFontScale = 0.5f))
        assertEquals(0.5f, shrink.fontScale, 0.001f)
        assertEquals(1f, shrink.scaleX, 0f)
    }

    @Test
    fun `cut off never scales`() {
        val fit = fitSuggestionText(1000f, 120f, TextFitting(LongWordFit.CUT_OFF))
        assertEquals(1f, fit.fontScale, 0f)
        assertEquals(1f, fit.scaleX, 0f)
    }

    @Test
    fun `the user's floors replace the defaults`() {
        val fit = fitSuggestionText(1000f, 120f, TextFitting(minScaleX = 0.5f, minFontScale = 0.9f))
        assertEquals(0.5f, fit.scaleX, 0.001f)
        assertEquals(0.9f, fit.fontScale, 0.001f)
    }

    @Test
    fun `a zero-width slot or empty measurement is a no-op`() {
        assertEquals(1f, fitSuggestionText(200f, 0f).fontScale, 0f)
        assertEquals(1f, fitSuggestionText(200f, -5f).fontScale, 0f)
        assertEquals(1f, fitSuggestionText(0f, 120f).fontScale, 0f)
    }
}
