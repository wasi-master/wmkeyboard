package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-decode backstops stay wired from the Kotlin weights to the JNI
 * array, and their defaults match the constants a preview or lift reads.
 */
class GlideTuningBudgetTest {

    @Test
    fun `the default tuning keeps the full lift budget`() {
        assertEquals(GlideBeam.MAX_POPS, GlideBeam.Tuning.DEFAULT.maxPops)
        assertEquals(GlideWorkspace.MAX_STATES, GlideBeam.Tuning.DEFAULT.maxStates)
    }

    @Test
    fun `the tuning array carries the budgets in the JNI order`() {
        val values = NativeGlide.tuningArray(GlideBeam.Tuning.DEFAULT)
        assertEquals(32, values.size)
        assertArrayEquals(
            doubleArrayOf(
                GlideBeam.MAX_POPS.toDouble(),
                GlideWorkspace.MAX_STATES.toDouble(),
            ),
            values.copyOfRange(30, 32),
            0.0,
        )
    }
}
