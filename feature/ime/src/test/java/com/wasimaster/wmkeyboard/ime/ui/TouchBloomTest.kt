package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The touch bloom's spring and light, which are closed-form so every key can
 * read one clock. What matters is the shape: in while held, back past rest
 * only when the theme asks for a wobble, and nothing left moving once the
 * frame loop is allowed to sleep.
 */
class TouchBloomTest {

    @Test
    fun `a held key sinks to a full dip and stays there`() {
        assertEquals(0f, dipEnvelope(0, STILL_DOWN, 0.5f), 1e-6f)
        assertEquals(1f, dipEnvelope(400, STILL_DOWN, 0.5f), 0.02f)
        assertEquals(1f, dipEnvelope(3000, STILL_DOWN, 1f), 0.01f)
    }

    @Test
    fun `a wobbly key springs past rest and a still one does not`() {
        var wobblyMin = 0f
        var stillMin = 0f
        for (ms in 300L..1700L step 5) {
            wobblyMin = minOf(wobblyMin, dipEnvelope(ms, 300, 1f))
            stillMin = minOf(stillMin, dipEnvelope(ms, 300, 0f))
        }
        assertTrue("no overshoot at full wobble: $wobblyMin", wobblyMin < -0.1f)
        assertTrue("overshoot with no wobble: $stillMin", stillMin > -0.001f)
    }

    @Test
    fun `nothing moves once a lifted touch has settled`() {
        for (wobble in listOf(0f, 0.5f, 1f)) {
            val settle = settleMs(wobble)
            assertTrue(settle in LIGHT_FADE_MS..MAX_SETTLE_MS)
            assertEquals(0f, dipEnvelope(200 + settle, 200, wobble), 0.02f)
            assertEquals(0f, lightEnvelope(200 + settle, 200), 1e-6f)
        }
    }

    @Test
    fun `the light comes up quickly and is gone after its fade`() {
        assertTrue(lightEnvelope(150, STILL_DOWN) > 0.8f)
        assertTrue(lightEnvelope(100 + LIGHT_FADE_MS / 2, 100) > 0f)
        assertEquals(0f, lightEnvelope(100 + LIGHT_FADE_MS, 100), 1e-6f)
    }

    @Test
    fun `only keys within reach of the finger dip, and a glide carries the dip along`() {
        TouchBloom.liftAll(0)
        val t0 = 1_000_000L
        TouchBloom.down(id = 7, x = 100f, y = 100f, now = t0)
        val now = t0 + 500
        assertEquals(1f, TouchBloom.dipAt(100f, 100f, 80f, 0.5f, now), 0.02f)
        assertTrue(TouchBloom.dipAt(150f, 100f, 80f, 0.5f, now) in 0.05f..0.9f)
        assertEquals(0f, TouchBloom.dipAt(200f, 100f, 80f, 0.5f, now), 0f)
        TouchBloom.move(id = 7, x = 200f, y = 100f)
        assertEquals(1f, TouchBloom.dipAt(200f, 100f, 80f, 0.5f, now), 0.02f)
        assertEquals(0f, TouchBloom.dipAt(100f, 100f, 80f, 0.5f, now), 0f)
        assertFalse(TouchBloom.settled(now, 0.5f))
        TouchBloom.up(id = 7, now = now)
        assertTrue(TouchBloom.settled(now + MAX_SETTLE_MS, 0.5f))
    }
}
