package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeldPressTest {

    @Test
    fun `a meld runs out, holds and draws back in`() {
        val life = 750L
        assertEquals(0f, meldPress(0, life), 0f)
        assertTrue(meldPress(45, life) in 0.4f..0.6f)
        assertEquals(1f, meldPress(120, life), 0f)
        assertTrue(meldPress(400, life) in 0.05f..0.95f)
        assertEquals(0f, meldPress(life, life), 0f)
    }

    @Test
    fun `a meld never grows on the way back`() {
        val life = 750L
        var last = meldPress(140, life)
        for (age in 150L..life step 10) {
            val p = meldPress(age, life)
            assertTrue("grew at $age ms", p <= last)
            last = p
        }
    }
}
