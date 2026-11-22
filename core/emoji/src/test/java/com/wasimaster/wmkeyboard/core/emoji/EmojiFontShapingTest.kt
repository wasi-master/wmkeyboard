package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision is made from a font's coverage tables, which are ordinary data —
 * so the whole thing is testable off-device by handing it the coverage a given
 * font would have produced.
 */
class EmojiFontShapingTest {

    private val heart = "❤️"
    private val heartUnqualified = "❤"
    private val heartOnFire = "❤️‍🔥"
    private val heartOnFireUnqualified = "❤‍🔥"
    private val fog = "😶‍🌫️"
    private val fogUnqualified = "😶‍🌫"

    @Test
    fun `the system font shapes nothing`() {
        val shaper = EmojiFontShaping.forFontFile(null)
        assertSame(EmojiFontShaping.Identity, shaper)
        assertEquals(heartOnFire, shaper.shape(heartOnFire))
        assertFalse(shaper.drawsWithSystemFont(heartOnFire))
    }

    /**
     * The reported bug. Nearly every converted colour emoji font ships without
     * a format-14 subtable, and Android hands `2764 FE0F` to the *system* font
     * because of it — so the selector has to go for the chosen font to be given
     * the run at all.
     */
    @Test
    fun `the selector is dropped by a font that declares no variation sequence`() {
        val font = coverage(covered = setOf(HEART))
        assertEquals(heartUnqualified, font.shape(heart))
        assertFalse(font.drawsWithSystemFont(heart))
    }

    @Test
    fun `the selector is kept by a font that declares the sequence`() {
        val font = coverage(covered = setOf(HEART), sequences = setOf(HEART to VS16))
        assertEquals(heart, font.shape(heart))
        assertFalse(font.drawsWithSystemFont(heart))
    }

    /**
     * A declared sequence resolves through the format-14 subtable, so the font
     * needs no cmap entry for U+FE0F itself — requiring one would fail every
     * sequence such a font declares.
     */
    @Test
    fun `a declared sequence does not need the selector mapped on its own`() {
        val font = coverage(covered = setOf(HEART), sequences = setOf(HEART to VS16))
        assertEquals(heart, font.shape(heart))
        // And a base it has no sequence for still loses its selector.
        val other = coverage(covered = setOf(SKULL), sequences = setOf(HEART to VS16))
        assertEquals("☠", other.shape("☠️"))
    }

    /** A font whose ZWJ ligatures were built on the unqualified sequence. */
    @Test
    fun `a ligature keyed unqualified gets the unqualified spelling`() {
        val font = coverage(
            covered = setOf(HEART, ZWJ, FIRE),
            ligatures = setOf(listOf(HEART, ZWJ, FIRE)),
        )
        assertEquals(heartOnFireUnqualified, font.shape(heartOnFire))
        assertFalse(font.drawsWithSystemFont(heartOnFire))
    }

    /**
     * A ligature keyed on the qualified sequence still works — as long as the
     * *leading* character isn't the one carrying the selector, because that is
     * the one that decides which font gets the run.
     */
    @Test
    fun `an interior selector is left in place for a font that ligates it`() {
        val font = coverage(
            covered = setOf(FACE, ZWJ, CLOUD, VS16),
            ligatures = setOf(listOf(FACE, ZWJ, CLOUD, VS16)),
        )
        assertEquals(fog, font.shape(fog))
        assertFalse(font.drawsWithSystemFont(fog))
    }

    @Test
    fun `an interior selector is dropped for a font that ligates without it`() {
        val font = coverage(
            covered = setOf(FACE, ZWJ, CLOUD),
            ligatures = setOf(listOf(FACE, ZWJ, CLOUD)),
        )
        assertEquals(fogUnqualified, font.shape(fog))
    }

    /**
     * The split the panel actually shows: every part is in the font, none of
     * the joins are, so it draws as a face and a separate cloud. One emoji in
     * the system font beats that.
     */
    @Test
    fun `a sequence with no ligature goes to the system font`() {
        val font = coverage(
            covered = setOf(FACE, ZWJ, CLOUD, VS16, HEART, FIRE),
            // It ligates other sequences, so the absence of this one is real.
            ligatures = setOf(listOf(HEART, ZWJ, FIRE)),
        )
        assertTrue(font.drawsWithSystemFont(fog))
        // The standard spelling is what the system font wants.
        assertEquals(fog, font.shape(fog))
    }

    @Test
    fun `an emoji the font has no glyph for goes to the system font`() {
        val font = coverage(covered = setOf(HEART))
        assertTrue(font.drawsWithSystemFont("🫠"))
        assertEquals("🫠", font.shape("🫠"))
    }

    /**
     * A font that assembles sequences some other way — contextual
     * substitutions, which aren't read — must not have its whole catalog
     * declared missing.
     */
    @Test
    fun `a font with no ligatures at all is trusted with its sequences`() {
        val font = coverage(covered = setOf(FACE, ZWJ, CLOUD))
        assertFalse(font.drawsWithSystemFont(fog))
        assertEquals(fogUnqualified, font.shape(fog))
    }

    @Test
    fun `a single covered code point is never rewritten`() {
        val font = coverage(covered = setOf(FIRE))
        assertEquals("🔥", font.shape("🔥"))
        assertFalse(font.drawsWithSystemFont("🔥"))
    }

    @Test
    fun `a keycap keeps its selector position when the selector survives`() {
        // 1️⃣ = 0031 FE0F 20E3; stripped it must stay 0031 20E3, never
        // 0031 20E3 FE0F, which is a digit with two marks on it.
        val font = coverage(
            covered = setOf('1'.code, KEYCAP),
            ligatures = setOf(listOf('1'.code, KEYCAP)),
        )
        assertEquals("1⃣", font.shape("1️⃣"))
    }

    @Test
    fun `a flag is never split by a selector`() {
        val font = coverage(
            covered = setOf(0x1F1E7, 0x1F1E9),
            ligatures = setOf(listOf(0x1F1E7, 0x1F1E9)),
        )
        assertEquals("🇧🇩", font.shape("🇧🇩"))
    }

    @Test
    fun `a skin tone rides with its base`() {
        val font = coverage(
            covered = setOf(0x1F44D, 0x1F3FD),
            ligatures = setOf(listOf(0x1F44D, 0x1F3FD)),
        )
        assertEquals("👍🏽", font.shape("👍🏽"))
        assertFalse(font.drawsWithSystemFont("👍🏽"))
    }

    @Test
    fun `the answer is computed once per emoji`() {
        var reads = 0
        val shaper = EmojiShaper {
            reads++
            fakeCoverage(setOf(HEART), emptySet(), emptySet())
        }
        repeat(5) { assertEquals(heartUnqualified, shaper.shape(heart)) }
        assertEquals("the font's tables are read once", 1, reads)
    }

    @Test
    fun `an unreadable font falls back to the standard spelling`() {
        val shaper = EmojiShaper { null }
        assertEquals(heartOnFire, shaper.shape(heartOnFire))
        assertFalse(shaper.drawsWithSystemFont(heartOnFire))
    }

    // ---- helpers ----

    private fun coverage(
        covered: Set<Int>,
        sequences: Set<Pair<Int, Int>> = emptySet(),
        ligatures: Set<List<Int>> = emptySet(),
    ): EmojiShaper = EmojiFontShaping.forCoverage(fakeCoverage(covered, sequences, ligatures))

    /**
     * Coverage built directly rather than through a font file: the glyph ids
     * are arbitrary, only which code points and sequences exist matters here.
     */
    private fun fakeCoverage(
        covered: Set<Int>,
        sequences: Set<Pair<Int, Int>>,
        ligatures: Set<List<Int>>,
    ): EmojiFontCoverage {
        val glyphs = covered.withIndex().associate { (i, cp) -> cp to i + 1 }
        val keys = ligatures.mapNotNullTo(HashSet()) { sequence ->
            sequence.map { glyphs[it] ?: return@mapNotNullTo null }
                .joinToString("") { it.toChar().toString() }
        }
        val sequenceKeys = sequences.mapTo(HashSet()) { (base, selector) ->
            (base.toLong() shl 32) or selector.toLong()
        }
        return EmojiFontCoverage(glyphs, sequenceKeys, keys)
    }

    private companion object {
        const val ZWJ = 0x200D
        const val HEART = 0x2764
        const val SKULL = 0x2620
        const val VS16 = 0xFE0F
        const val KEYCAP = 0x20E3
        const val FIRE = 0x1F525
        const val FACE = 0x1F636
        const val CLOUD = 0x1F32B
    }
}
