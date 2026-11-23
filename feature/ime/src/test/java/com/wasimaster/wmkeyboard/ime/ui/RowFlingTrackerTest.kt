package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a swipe across a row with more pages flips it. The row is 1000 px wide
 * and spans y 100 to 200.
 */
class RowFlingTrackerTest {

    private fun tracker(strict: Boolean) = RowFlingTracker(
        strict = strict,
        rowTop = 100f,
        rowBottom = 200f,
        rowWidth = 1000f,
        claimPx = 100f,
        windowMs = if (strict) RowSwipeStrictWindowMs else RowSwipeLooseWindowMs,
    )

    /** Feeds [points] (x, y, ms) as one finger, then lifts it at the last one. */
    private fun RowFlingTracker.swipe(vararg points: Triple<Float, Float, Long>): Int? {
        for ((x, y, t) in points) track(1L, x, y, pressed = true, uptimeMs = t)
        val (x, y, t) = points.last()
        track(1L, x, y, pressed = false, uptimeMs = t)
        return result()
    }

    @Test
    fun `glide on, a fast straight fling across the row flips it`() {
        val fling = tracker(strict = true)
        assertEquals(1, fling.swipe(Triple(900f, 150f, 0L), Triple(600f, 152f, 80L), Triple(250f, 155f, 160L)))
        assertTrue(fling.claimed)
        assertEquals(-1, tracker(strict = true).swipe(Triple(100f, 150f, 0L), Triple(800f, 150f, 150L)))
    }

    @Test
    fun `glide on, a slow swipe is a word`() {
        val slow = tracker(strict = true)
        assertNull(slow.swipe(Triple(900f, 150f, 0L), Triple(600f, 150f, 200L), Triple(200f, 150f, 400L)))
        assertFalse(slow.claimed)
    }

    @Test
    fun `glide on, a stroke that leaves the row or turns back is a word`() {
        assertNull(tracker(strict = true).swipe(Triple(900f, 150f, 0L), Triple(600f, 260f, 80L), Triple(200f, 150f, 160L)))
        assertNull(tracker(strict = true).swipe(Triple(900f, 150f, 0L), Triple(500f, 150f, 60L), Triple(700f, 150f, 120L), Triple(100f, 150f, 200L)))
    }

    @Test
    fun `glide on, a short fast swipe is a word`() {
        assertNull(tracker(strict = true).swipe(Triple(900f, 150f, 0L), Triple(500f, 150f, 80L)))
    }

    @Test
    fun `glide off, any sideways swipe past the claim distance flips the row`() {
        val loose = tracker(strict = false)
        assertEquals(1, loose.swipe(Triple(500f, 150f, 0L), Triple(380f, 160f, 200L)))
        assertTrue(loose.claimed)
    }

    @Test
    fun `glide off, a steep swipe or a late one is not a row swipe`() {
        assertNull(tracker(strict = false).swipe(Triple(500f, 150f, 0L), Triple(400f, 260f, 100L)))
        assertNull(tracker(strict = false).swipe(Triple(500f, 150f, 0L), Triple(480f, 150f, 600L), Triple(300f, 150f, 700L)))
    }

    @Test
    fun `a second finger gives the touch up`() {
        val two = tracker(strict = false)
        two.track(1L, 500f, 150f, pressed = true, uptimeMs = 0L)
        two.track(2L, 100f, 150f, pressed = true, uptimeMs = 10L)
        two.track(1L, 300f, 150f, pressed = true, uptimeMs = 100L)
        assertTrue(two.abandoned)
        assertFalse(two.claimed)
    }
}
