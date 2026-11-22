package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1175: a font whose glyphs this phone cannot draw (SVG, sbix, COLRv1 before
 * Android 13) must hand every emoji to the system font rather than leave the
 * panel blank. The draw itself needs a canvas; what is decided from it is not.
 */
class EmojiFontProbeTest {

    private fun coverage(vararg codePoints: Int): EmojiFontCoverage =
        EmojiFontCoverage(codePoints.withIndex().associate { (i, cp) -> cp to i + 1 }, emptySet(), emptySet())

    @Test
    fun `an undrawable font sends every emoji to the system font`() {
        val shaper = EmojiFontShaping.forCoverage(coverage(GRIN, HEART).undrawable())
        assertTrue(shaper.drawsWithSystemFont("😀"))
        // Even one the font maps, and the spelling is left as authored.
        assertEquals("❤️", shaper.shape("❤️"))
        assertTrue(shaper.drawsWithSystemFont("❤️"))
    }

    @Test
    fun `a drawable font keeps its emoji`() {
        val shaper = EmojiFontShaping.forCoverage(coverage(GRIN))
        assertFalse(shaper.drawsWithSystemFont("😀"))
    }

    @Test
    fun `the sample is the grinning face when the font has it`() {
        assertEquals(GRIN, coverage(0x41, 0x2764, GRIN).sampleCodePoint())
    }

    @Test
    fun `otherwise the first pictograph, then the first symbol`() {
        assertEquals(0x1F37A, coverage(0x41, 0x2764, 0x1F37A, 0x1F680).sampleCodePoint())
        assertEquals(0x2764, coverage(0x41, 0x2764).sampleCodePoint())
        assertNull(coverage(0x41, 0x42).sampleCodePoint())
    }

    @Test
    fun `ink is any pixel that is not fully transparent`() {
        assertFalse(EmojiFontProbe.hasInk(IntArray(16)))
        assertTrue(EmojiFontProbe.hasInk(IntArray(16).also { it[7] = 0x01000000 }))
    }

    private companion object {
        const val GRIN = 0x1F600
        const val HEART = 0x2764
    }
}
