package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.PinchGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinch and stretch on the key grid (gap list item 18), and the things they
 * must never be taken for: a multi-finger swipe, two thumbs gliding, two quick
 * taps, a thumb resting while the other finger moves.
 */
class PinchTrackerTest {

    private val span = 40f
    private val finger = 14f

    private fun classify(a: PinchStroke, b: PinchStroke) = classifyPinch(a, b, span, finger)

    // classifyPinch, the pure rule.

    @Test
    fun `two fingers moving apart along their line are a stretch`() {
        assertEquals(
            PinchGesture.STRETCH,
            classify(PinchStroke(150f, 200f, 100f, 200f), PinchStroke(250f, 200f, 300f, 200f)),
        )
    }

    @Test
    fun `two fingers moving together along their line are a pinch`() {
        assertEquals(
            PinchGesture.PINCH,
            classify(PinchStroke(100f, 200f, 150f, 200f), PinchStroke(300f, 200f, 250f, 200f)),
        )
    }

    @Test
    fun `a diagonal pair pinches too`() {
        assertEquals(
            PinchGesture.PINCH,
            classify(PinchStroke(100f, 100f, 140f, 140f), PinchStroke(300f, 300f, 260f, 260f)),
        )
    }

    @Test
    fun `two fingers swiped the same way are no pinch`() {
        assertNull(classify(PinchStroke(100f, 200f, 100f, 80f), PinchStroke(200f, 200f, 200f, 80f)))
        assertNull(classify(PinchStroke(100f, 200f, 220f, 200f), PinchStroke(200f, 200f, 320f, 200f)))
    }

    @Test
    fun `a still finger under a moving one is no stretch`() {
        // The gap grows by 100, but finger a never moved: a held key and a glide.
        assertNull(classify(PinchStroke(100f, 200f, 100f, 200f), PinchStroke(200f, 200f, 300f, 200f)))
    }

    @Test
    fun `a small wobble is not enough`() {
        assertNull(classify(PinchStroke(100f, 200f, 85f, 200f), PinchStroke(300f, 200f, 315f, 200f)))
    }

    @Test
    fun `a far-apart pair has to change scale, not only distance`() {
        // 600 apart, 50 further: past the span but nowhere near a quarter.
        assertNull(classify(PinchStroke(100f, 200f, 75f, 200f), PinchStroke(700f, 200f, 725f, 200f)))
    }

    @Test
    fun `fingers moving across their line are no pinch`() {
        // Each finger mostly travels up and down while the gap grows a little.
        assertNull(classify(PinchStroke(150f, 200f, 130f, 80f), PinchStroke(250f, 200f, 270f, 320f)))
    }

    @Test
    fun `a pair that drifts as it stretches is two thumbs gliding`() {
        // Each finger heads 48° off the line, inside the radial cone, so only
        // the drift rule is left to catch it: the gap grows by 80 while the
        // pair rises 45 together.
        assertNull(classify(PinchStroke(150f, 300f, 110f, 255f), PinchStroke(250f, 300f, 290f, 255f)))
        // The same spread with no rise is a stretch.
        assertEquals(
            PinchGesture.STRETCH,
            classify(PinchStroke(150f, 300f, 110f, 300f), PinchStroke(250f, 300f, 290f, 300f)),
        )
    }

    @Test
    fun `fingers on the same spot are nothing`() {
        assertNull(classify(PinchStroke(100f, 100f, 50f, 100f), PinchStroke(100f, 100f, 150f, 100f)))
    }

    // PinchTracker, the pointer rules around it.

    private fun tracker() = PinchTracker(spanPx = span, fingerPx = finger, landSlopPx = 10f, landWindowMs = 300L)

    /** Lands two fingers at [a] and [b] [gapMs] apart, then moves each by its delta in five steps. */
    private fun PinchTracker.pair(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        da: Pair<Float, Float>,
        db: Pair<Float, Float>,
        gapMs: Long = 20L,
    ) {
        track(0, a.first, a.second, pressed = true, uptimeMs = 0L)
        track(1, b.first, b.second, pressed = true, uptimeMs = gapMs)
        for (step in 1..5) {
            val t = gapMs + step * 16L
            track(0, a.first + da.first * step / 5, a.second + da.second * step / 5, pressed = true, uptimeMs = t)
            track(1, b.first + db.first * step / 5, b.second + db.second * step / 5, pressed = true, uptimeMs = t)
        }
    }

    private fun PinchTracker.liftBoth() {
        track(0, 0f, 0f, pressed = false, uptimeMs = 1000L)
        track(1, 0f, 0f, pressed = false, uptimeMs = 1000L)
    }

    @Test
    fun `a stretch is claimed while the fingers move and answered at the lift`() {
        val t = tracker()
        t.pair(150f to 200f, 250f to 200f, da = -60f to 0f, db = 60f to 0f)
        assertEquals(PinchGesture.STRETCH, t.claimed)
        assertNull(t.result())
        t.liftBoth()
        assertEquals(PinchGesture.STRETCH, t.result())
    }

    @Test
    fun `two quick taps by two thumbs are nothing`() {
        val t = tracker()
        t.track(0, 100f, 200f, pressed = true, uptimeMs = 0L)
        t.track(1, 300f, 200f, pressed = true, uptimeMs = 40L)
        t.track(0, 100f, 200f, pressed = false, uptimeMs = 80L)
        t.track(1, 300f, 200f, pressed = false, uptimeMs = 110L)
        assertNull(t.claimed)
        assertTrue(t.abandoned)
        assertNull(t.result())
    }

    @Test
    fun `a finger lifted before the claim gives the touch back`() {
        val t = tracker()
        t.track(0, 150f, 200f, pressed = true, uptimeMs = 0L)
        t.track(1, 250f, 200f, pressed = true, uptimeMs = 10L)
        t.track(0, 150f, 200f, pressed = false, uptimeMs = 60L)
        t.track(1, 400f, 200f, pressed = true, uptimeMs = 80L)
        assertNull(t.claimed)
        assertTrue(t.abandoned)
    }

    @Test
    fun `a second finger that lands late is not a pinch`() {
        val t = tracker()
        t.pair(150f to 200f, 250f to 200f, da = -60f to 0f, db = 60f to 0f, gapMs = 500L)
        assertNull(t.claimed)
        assertTrue(t.abandoned)
    }

    @Test
    fun `a second finger that lands on a glide already moving is not a pinch`() {
        val t = tracker()
        t.track(0, 150f, 200f, pressed = true, uptimeMs = 0L)
        t.track(0, 120f, 200f, pressed = true, uptimeMs = 30L)
        t.track(1, 250f, 200f, pressed = true, uptimeMs = 50L)
        t.track(0, 60f, 200f, pressed = true, uptimeMs = 80L)
        t.track(1, 320f, 200f, pressed = true, uptimeMs = 80L)
        assertNull(t.claimed)
        assertTrue(t.abandoned)
    }

    @Test
    fun `a third finger is a multi-finger swipe, not a pinch`() {
        val t = tracker()
        t.track(0, 150f, 200f, pressed = true, uptimeMs = 0L)
        t.track(1, 250f, 200f, pressed = true, uptimeMs = 10L)
        t.track(2, 350f, 200f, pressed = true, uptimeMs = 20L)
        assertTrue(t.abandoned)
    }

    @Test
    fun `a two-finger swipe never claims`() {
        val t = tracker()
        t.pair(100f to 300f, 200f to 300f, da = 0f to -150f, db = 0f to -150f)
        assertNull(t.claimed)
        assertFalse(t.abandoned)
    }

    @Test
    fun `the caller can give the touch up before a claim but not after`() {
        val before = tracker()
        before.track(0, 150f, 200f, pressed = true, uptimeMs = 0L)
        before.abandon()
        assertTrue(before.abandoned)

        val after = tracker()
        after.pair(100f to 200f, 300f to 200f, da = 50f to 0f, db = -50f to 0f)
        assertEquals(PinchGesture.PINCH, after.claimed)
        after.abandon()
        assertFalse(after.abandoned)
    }
}
