package com.wasimaster.wmkeyboard.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingCadenceTest {

    @Test
    fun `the first key and the first after a long gap start a burst`() {
        val cadence = TypingCadence()
        assertTrue(cadence.noteKey(0L))
        assertFalse(cadence.noteKey(150L))
        assertTrue(cadence.noteKey(2_000L))
    }

    @Test
    fun `the pace is learned from the gaps inside a burst`() {
        val cadence = TypingCadence()
        var t = 0L
        cadence.noteKey(t)
        repeat(20) {
            t += 150L
            cadence.noteKey(t)
        }
        assertEquals(150L, cadence.gapMs)
        // A pause for this typist is about twice their gap.
        assertEquals(300L, cadence.pauseMs())
        assertTrue(cadence.typing(t + 100L))
        assertFalse(cadence.typing(t + 400L))
        assertEquals(200L, cadence.untilPauseMs(t + 100L))
    }

    @Test
    fun `a pause once the pace is known starts a burst and does not skew the pace`() {
        val cadence = TypingCadence()
        var t = 0L
        cadence.noteKey(t)
        repeat(20) {
            t += 150L
            cadence.noteKey(t)
        }
        t += 600L
        assertTrue(cadence.noteKey(t))
        assertEquals(150L, cadence.gapMs)
    }

    @Test
    fun `the pause is bounded for very fast and very slow typists`() {
        val fast = TypingCadence()
        var t = 0L
        fast.noteKey(t)
        repeat(20) {
            t += 50L
            fast.noteKey(t)
        }
        assertEquals(250L, fast.pauseMs())
        val slow = TypingCadence()
        t = 0L
        slow.noteKey(t)
        repeat(20) {
            t += 800L
            slow.noteKey(t)
        }
        assertEquals(900L, slow.pauseMs())
    }

    @Test
    fun `nothing typed yet is already a pause`() {
        val cadence = TypingCadence()
        assertEquals(0L, cadence.untilPauseMs(1_000L))
        cadence.noteKey(1_000L)
        cadence.reset()
        assertFalse(cadence.typing(1_010L))
    }
}
