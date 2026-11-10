package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystrokeTimingTest {

    private fun timed(vararg gapsMs: Long): KeystrokeTiming {
        val t = KeystrokeTiming()
        var now = 1_000L
        t.onKeystroke(now)
        for (gap in gapsMs) {
            now += gap
            t.onKeystroke(now)
        }
        return t
    }

    @Test
    fun zeroStrengthOrTooFewGapsIsAlwaysNeutral() {
        assertEquals(1.0, timed(100, 100, 100).multiplier(0f), 0.0)
        // One gap (two keystrokes) says nothing yet.
        assertEquals(1.0, timed(100).multiplier(1f), 0.0)
        // No keystrokes at all — hardware keys, pasted text.
        assertEquals(1.0, KeystrokeTiming().multiplier(1f), 0.0)
    }

    @Test
    fun fastBurstLowersTheGateSlowTypingRaisesIt() {
        val fast = timed(80, 90, 100).multiplier(1f)
        assertTrue("burst should ease the gate, got $fast", fast < 1.0)
        val slow = timed(600, 700, 800).multiplier(1f)
        assertTrue("deliberate typing should tighten, got $slow", slow > 1.0)
        // Full strength at the extremes reaches the +/- MAX_SHIFT bounds.
        assertEquals(1.0 - KeystrokeTiming.MAX_SHIFT, fast, 1e-9)
        assertEquals(1.0 + KeystrokeTiming.MAX_SHIFT, slow, 1e-9)
    }

    @Test
    fun strengthScalesTheShift() {
        val full = timed(80, 90, 100).multiplier(1f)
        val half = timed(80, 90, 100).multiplier(0.5f)
        assertTrue(half > full && half < 1.0)
        assertEquals(1.0 - KeystrokeTiming.MAX_SHIFT * 0.5, half, 1e-9)
    }

    @Test
    fun pausesCarryNoRhythmSignal() {
        // Fast burst, then a long think — the pause must not read as slow.
        val t = timed(80, 90, 5_000, 85)
        assertTrue(t.multiplier(1f) < 1.0)
    }

    @Test
    fun resetStartsTheNextWordFresh() {
        val t = timed(600, 700, 800)
        assertTrue(t.multiplier(1f) > 1.0)
        t.reset()
        assertEquals(1.0, t.multiplier(1f), 0.0)
    }

    @Test
    fun fastIsAPhoneTypistsPaceWhateverTheSetting() {
        assertTrue(timed(180, 220, 200).isFast())
        assertFalse(timed(400, 380).isFast())
        // One gap says nothing yet.
        assertFalse(timed(100).isFast())
    }
}
