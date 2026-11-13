package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When one finger on the letters is a board swipe (gap list item 44). */
class OneFingerSwipeTrackerTest {

    private fun tracker() = OneFingerSwipeTracker(claimPx = 100f, windowMs = 300L)

    @Test
    fun `a quick straight swipe is claimed and read at the lift`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        assertFalse(t.track(1, -40f, 5f, pressed = true, uptimeMs = 60))
        assertTrue(t.track(1, -120f, 10f, pressed = true, uptimeMs = 120))
        assertNull("not until the finger is up", t.result())
        t.track(1, -150f, 12f, pressed = false, uptimeMs = 160)
        assertEquals(SwipeDirection.LEFT, t.result())
    }

    @Test
    fun `each direction reads as itself`() {
        for ((dx, dy, want) in listOf(
            Triple(0f, -150f, SwipeDirection.UP),
            Triple(0f, 150f, SwipeDirection.DOWN),
            Triple(150f, 0f, SwipeDirection.RIGHT),
        )) {
            val t = tracker()
            t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
            t.track(1, dx, dy, pressed = true, uptimeMs = 100)
            t.track(1, dx, dy, pressed = false, uptimeMs = 120)
            assertEquals(want, t.result())
        }
    }

    @Test
    fun `a tap that rolls onto the next key is never claimed`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        t.track(1, 40f, 0f, pressed = true, uptimeMs = 50)
        t.track(1, 40f, 0f, pressed = false, uptimeMs = 80)
        assertTrue(t.abandoned)
        assertNull(t.result())
    }

    @Test
    fun `a hold that later drifts is not a swipe`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        t.track(1, 5f, 0f, pressed = true, uptimeMs = 400)
        assertTrue(t.abandoned)
        assertFalse(t.track(1, 200f, 0f, pressed = true, uptimeMs = 450))
    }

    @Test
    fun `a slow drag that gets there late is not a swipe`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        t.track(1, 60f, 0f, pressed = true, uptimeMs = 200)
        assertFalse(t.track(1, 150f, 0f, pressed = true, uptimeMs = 500))
        assertTrue(t.abandoned)
    }

    @Test
    fun `a diagonal is no direction`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        assertFalse(t.track(1, 100f, 100f, pressed = true, uptimeMs = 100))
        assertTrue(t.abandoned)
    }

    @Test
    fun `a second finger before the claim gives the touch up`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        t.track(2, 300f, 0f, pressed = true, uptimeMs = 30)
        assertTrue(t.abandoned)
    }

    @Test
    fun `abandoning after the claim changes nothing`() {
        val t = tracker()
        t.track(1, 0f, 0f, pressed = true, uptimeMs = 0)
        t.track(1, 0f, 150f, pressed = true, uptimeMs = 100)
        t.abandon()
        assertFalse(t.abandoned)
        t.track(1, 0f, 160f, pressed = false, uptimeMs = 120)
        assertEquals(SwipeDirection.DOWN, t.result())
    }

    @Test
    fun `only a plain letter key starts one`() {
        assertTrue(startsOneFingerSwipe(Key("a")))
        assertFalse(startsOneFingerSwipe(Key("1")))
        assertFalse(startsOneFingerSwipe(Key(",")))
        assertFalse(startsOneFingerSwipe(Key("", action = KeyAction.Space)))
        assertFalse(startsOneFingerSwipe(Key("⇧", action = KeyAction.Shift)))
        assertFalse(
            "a key with flick arms owns its own swipes",
            startsOneFingerSwipe(Key(label = "a", flick = mapOf(FlickDirection.UP to "A"))),
        )
    }
}
