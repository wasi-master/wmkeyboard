package com.wasimaster.wmkeyboard.core.voice.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where Parakeet Ultra's voice-activity head says a long dictation is cut (#1173). */
class ParakeetCutTest {

    private val frame = ParakeetCut.FRAME_SAMPLES

    /** Speech (+4) everywhere but the frames in [quiet] (-4). */
    private fun logits(frames: Int, vararg quiet: IntRange) =
        FloatArray(frames) { f -> if (quiet.any { f in it }) -4f else 4f }

    @Test
    fun `the cut is the middle of the longest pause near the end`() {
        // 40 frames; a short pause at 22-23, a long one at 30-35.
        val cut = ParakeetCut.pick(logits(40, 22..23, 30..35), 40 * frame, searchSamples = 20 * frame)
        assertEquals(33 * frame + frame / 2, cut)
    }

    @Test
    fun `a pause before the search window does not count`() {
        val cut = ParakeetCut.pick(logits(40, 5..15, 30..31), 40 * frame, searchSamples = 15 * frame)
        assertEquals(31 * frame + frame / 2, cut)
    }

    @Test
    fun `with no pause the quietest frame is cut`() {
        val values = FloatArray(40) { 4f }.also { it[33] = 1f }
        assertEquals(33 * frame + frame / 2, ParakeetCut.pick(values, 40 * frame, 20 * frame))
    }

    @Test
    fun `the last two frames are never cut`() {
        // A pause only in the frames with no right context.
        val values = FloatArray(40) { 4f }.also { it[38] = -4f; it[39] = -4f; it[30] = 2f }
        assertEquals(30 * frame + frame / 2, ParakeetCut.pick(values, 40 * frame, 20 * frame))
    }

    @Test
    fun `nothing to read is no cut`() {
        assertNull(ParakeetCut.pick(FloatArray(0), 40 * frame, 20 * frame))
        assertNull(ParakeetCut.pick(FloatArray(3), 3 * frame, 3 * frame))
    }
}
