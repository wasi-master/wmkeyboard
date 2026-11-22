package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trackpad tool's arithmetic (issue #39): finger travel into caret steps,
 * taps into a ladder, and what a release means.
 *
 * The step maths is the part worth a test. A drag is dozens of tiny deltas,
 * none of them a whole step on its own, and the caret has to end up exactly
 * where the finger's total travel says, in either direction, with a reversal
 * unwinding what was carried rather than firing a phantom step.
 */
class TrackpadTest {

    @Test
    fun `sub-step travel accumulates into a step`() {
        val axis = TrackpadAxis(stepPx = 16f)
        assertEquals(0, axis.advance(6f))
        assertEquals(0, axis.advance(6f))
        // 18 px so far: one step, 2 px carried.
        assertEquals(1, axis.advance(6f))
        // 2 + 14 = 16: exactly the next step, nothing left over.
        assertEquals(1, axis.advance(14f))
        assertEquals(0, axis.advance(15f))
    }

    @Test
    fun `a big delta yields several steps and keeps the change`() {
        val axis = TrackpadAxis(stepPx = 10f)
        assertEquals(3, axis.advance(37f))
        // 7 carried: 3 more lands a fourth.
        assertEquals(1, axis.advance(3f))
    }

    @Test
    fun `negative travel steps backward with the same carry rule`() {
        val axis = TrackpadAxis(stepPx = 10f)
        assertEquals(-2, axis.advance(-25f))
        assertEquals(0, axis.advance(-4f))
        assertEquals(-1, axis.advance(-1f))
    }

    /** Relative tracking: back where the finger started is back where the caret started. */
    @Test
    fun `a reversal unwinds the carry instead of firing`() {
        val axis = TrackpadAxis(stepPx = 16f)
        assertEquals(0, axis.advance(8f))
        assertEquals(0, axis.advance(-8f))
        assertEquals(0, axis.advance(-8f))
        assertEquals(-1, axis.advance(-8f))
    }

    @Test
    fun `reset forgets the carry`() {
        val axis = TrackpadAxis(stepPx = 16f)
        axis.advance(15f)
        axis.reset()
        assertEquals(0, axis.advance(15f))
    }

    @Test
    fun `sensitivity scales the travel per step`() {
        val fine = TrackpadAxis(stepPx = 8f)
        val coarse = TrackpadAxis(stepPx = 32f)
        assertEquals(4, fine.advance(32f))
        assertEquals(1, coarse.advance(32f))
    }

    @Test
    fun `a step under a pixel is clamped rather than dividing by nothing`() {
        val axis = TrackpadAxis(stepPx = 0f)
        assertEquals(5, axis.advance(5f))
    }

    @Test
    fun `taps in the window and in place climb the ladder`() {
        val taps = TrackpadTapCounter(windowMs = 300, slopPx = 20f)
        assertEquals(1, taps.tap(1_000, 100f, 100f))
        assertEquals(2, taps.tap(1_100, 105f, 98f))
        assertEquals(3, taps.tap(1_200, 110f, 100f))
        // A fourth starts over rather than going dead.
        assertEquals(1, taps.tap(1_300, 110f, 100f))
    }

    @Test
    fun `a slow second tap is a first tap`() {
        val taps = TrackpadTapCounter(windowMs = 300, slopPx = 20f)
        taps.tap(1_000, 100f, 100f)
        assertEquals(1, taps.tap(1_300, 100f, 100f))
    }

    /** A wide surface: a quick tap at the far side is a new tap, not a double. */
    @Test
    fun `a second tap far away is a first tap`() {
        val taps = TrackpadTapCounter(windowMs = 300, slopPx = 20f)
        taps.tap(1_000, 100f, 100f)
        assertEquals(1, taps.tap(1_100, 300f, 100f))
        assertEquals(2, taps.tap(1_200, 300f, 100f))
    }

    @Test
    fun `reset forgets the run`() {
        val taps = TrackpadTapCounter(windowMs = 300, slopPx = 20f)
        taps.tap(1_000, 100f, 100f)
        taps.reset()
        assertEquals(1, taps.tap(1_050, 100f, 100f))
    }

    @Test
    fun `a release is classified by what the gesture did`() {
        assertEquals(TrackpadRelease.TAP, classifyRelease(moved = false, longPressed = false, maxFingers = 1))
        assertEquals(TrackpadRelease.TWO_FINGER_TAP, classifyRelease(moved = false, longPressed = false, maxFingers = 2))
        // Three fingers are two: a palm, not a new gesture.
        assertEquals(TrackpadRelease.TWO_FINGER_TAP, classifyRelease(moved = false, longPressed = false, maxFingers = 3))
        // A drag already did its work, however many fingers.
        assertEquals(TrackpadRelease.NONE, classifyRelease(moved = true, longPressed = false, maxFingers = 1))
        assertEquals(TrackpadRelease.NONE, classifyRelease(moved = true, longPressed = false, maxFingers = 2))
        // A hold that never moved armed and released selection: not a tap.
        assertEquals(TrackpadRelease.NONE, classifyRelease(moved = false, longPressed = true, maxFingers = 1))
    }

    @Test
    fun `mouse mode clicks on a tap and right-clicks on a two-finger tap`() {
        assertEquals(MouseRelease.CLICK, classifyMouseRelease(moved = false, longPressed = false, maxFingers = 1))
        assertEquals(MouseRelease.RIGHT_CLICK, classifyMouseRelease(moved = false, longPressed = false, maxFingers = 2))
        // A drag moved the pointer and a hold was a held button: neither clicks.
        assertEquals(MouseRelease.NONE, classifyMouseRelease(moved = true, longPressed = false, maxFingers = 1))
        assertEquals(MouseRelease.NONE, classifyMouseRelease(moved = false, longPressed = true, maxFingers = 1))
        assertEquals(MouseRelease.NONE, classifyMouseRelease(moved = true, longPressed = false, maxFingers = 2))
    }

    @Test
    fun `a slow drag moves the pointer at the base speed`() {
        val motion = PointerMotion(speed = 1.5f, acceleration = true, density = 2f)
        // 4 px over 16 ms on a 2x screen is 0.125 dp/ms: careful aiming.
        assertEquals(1.5f, motion.gain(4f, 0f, 16), 0.0001f)
        assertEquals(1.5f, PointerMotion(1.5f, acceleration = false, density = 2f).gain(400f, 0f, 16), 0.0001f)
    }

    @Test
    fun `a flick earns more travel, up to the cap`() {
        val motion = PointerMotion(speed = 1f, acceleration = true, density = 1f)
        // 0.75 dp/ms: 0.5 over the floor, times the slope.
        assertEquals(1f + 0.5f * PointerMotion.ACCEL_SLOPE, motion.gain(12f, 0f, 16), 0.0001f)
        // Far past the floor, the gain stops at the cap.
        assertEquals(PointerMotion.MAX_BOOST, motion.gain(1_000f, 0f, 16), 0.0001f)
        // No time passed: no speed to read, the base gain.
        assertEquals(1f, motion.gain(50f, 0f, 0), 0.0001f)
    }

    @Test
    fun `the pointer stays inside the band above the keyboard`() {
        assertEquals(0f, clampToBand(-30f, 0f, 500f), 0f)
        assertEquals(499f, clampToBand(900f, 0f, 500f), 0f)
        assertEquals(120f, clampToBand(120f, 0f, 500f), 0f)
        // No band at all: the floor, not a crash.
        assertEquals(0f, clampToBand(40f, 0f, 0f), 0f)
    }
}
