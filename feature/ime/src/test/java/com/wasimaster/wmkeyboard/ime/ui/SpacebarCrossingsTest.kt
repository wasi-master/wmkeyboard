package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.ime.ui.SpacebarCrossings.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spacebar split that cut "entendiendo" into "entren viento" (#428). A
 * finger reaching for a bottom-row letter clips the top of the bar, and that
 * used to end the word; now only a dip to the bar's middle does, and a
 * shallow one is handed back to the word once the finger comes back up.
 */
class SpacebarCrossingsTest {

    /** A bar 100 tall with its top at 300, so the crossing line is at 350. */
    private val bar = Rect(left = 100f, top = 300f, right = 600f, bottom = 400f)

    private fun SpacebarCrossings.feed(y: Float, x: Float = 300f, bar: Rect? = this@SpacebarCrossingsTest.bar): Step =
        step(GesturePoint(x, y), Offset(x, y), bar)

    @Test
    fun aSampleOffTheBarIsTheWords() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.KEEP, crossings.feed(250f))
        assertFalse(crossings.overBar)
    }

    @Test
    fun clippingTheTopOfTheBarEndsNoWord() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.HELD, crossings.feed(320f))
        assertEquals(Step.HELD, crossings.feed(340f))
        assertTrue(crossings.overBar)
        // Back up into the letters: the dip was the tail of a bottom-row
        // letter, and its samples go back to the word, oldest first.
        assertEquals(Step.KEEP, crossings.feed(280f))
        val word = ArrayList<GesturePoint>()
        crossings.drain(word)
        assertEquals(listOf(320f, 340f), word.map { it.y })
    }

    @Test
    fun reachingTheMiddleIsACrossingOnce() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.HELD, crossings.feed(320f))
        assertEquals(Step.CROSSED, crossings.feed(350f))
        // Deeper, back to the line, and up through the band on the way out:
        // all of it transit, none of it a second crossing.
        assertEquals(Step.HELD, crossings.feed(390f))
        assertEquals(Step.HELD, crossings.feed(350f))
        assertEquals(Step.HELD, crossings.feed(320f))
        // Above the bar but not clear of it, then clear: the next word's way up.
        assertEquals(Step.HELD, crossings.feed(280f))
        assertEquals(Step.KEEP, crossings.feed(240f))
        val word = ArrayList<GesturePoint>()
        crossings.drain(word)
        assertEquals("the walk down to the bar is not the next word's", listOf(280f), word.map { it.y })
    }

    @Test
    fun slidingOffTheBarsEndIsStillTheCrossing() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.CROSSED, crossings.feed(360f))
        assertEquals(Step.HELD, crossings.feed(y = 360f, x = 650f))
        assertTrue(crossings.overBar)
        assertEquals(Step.HELD, crossings.feed(360f))
        assertEquals(Step.KEEP, crossings.feed(y = 240f, x = 650f))
        assertFalse(crossings.overBar)
    }

    @Test
    fun pastTheBottomEdgeIsOnTheBar() {
        val crossings = SpacebarCrossings()
        // Between two samples, from above the bar to below it.
        assertEquals(Step.CROSSED, crossings.feed(420f))
        assertEquals(Step.HELD, crossings.feed(450f))
        assertEquals(Step.HELD, crossings.feed(360f))
    }

    @Test
    fun aJitterAlongTheTopEdgeIsOneCrossing() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.CROSSED, crossings.feed(360f))
        assertEquals(Step.HELD, crossings.feed(295f))
        assertEquals(Step.HELD, crossings.feed(290f))
        assertEquals(Step.HELD, crossings.feed(360f))
        assertEquals(Step.KEEP, crossings.feed(240f))
        val word = ArrayList<GesturePoint>()
        crossings.drain(word)
        assertTrue("the jitter is nobody's", word.isEmpty())
    }

    @Test
    fun theHeldDipIsDroppedWhenTheCrossingComes() {
        val crossings = SpacebarCrossings()
        crossings.feed(320f)
        crossings.feed(345f)
        assertEquals(Step.CROSSED, crossings.feed(355f))
        assertTrue(crossings.withHeld(emptyList()).isEmpty())
    }

    @Test
    fun aSecondVisitCanCrossAgain() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.CROSSED, crossings.feed(360f))
        assertEquals(Step.KEEP, crossings.feed(200f))
        assertEquals(Step.CROSSED, crossings.feed(360f))
    }

    @Test
    fun thePreviewSeesTheHeldDip() {
        val crossings = SpacebarCrossings()
        val word = listOf(GesturePoint(300f, 250f))
        assertEquals(word, crossings.withHeld(word))
        crossings.feed(320f)
        assertEquals(listOf(250f, 320f), crossings.withHeld(word).map { it.y })
        // Handing it back is not the same as counting it twice.
        assertEquals(word, crossings.withHeld(word).take(1))
    }

    @Test
    fun besideTheBarIsOffIt() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.KEEP, crossings.feed(y = 360f, x = 50f))
        assertFalse(crossings.overBar)
    }

    @Test
    fun noBarMeansNoCrossing() {
        val crossings = SpacebarCrossings()
        assertEquals(Step.KEEP, crossings.feed(360f, bar = null))
        assertFalse(crossings.overBar)
    }

    @Test
    fun theLineScalesWithTheBar() {
        val tall = Rect(left = 100f, top = 300f, right = 600f, bottom = 500f)
        val crossings = SpacebarCrossings()
        assertEquals(Step.HELD, crossings.feed(390f, bar = tall))
        assertEquals(Step.CROSSED, crossings.feed(400f, bar = tall))
    }

    @Test
    fun theDepthIsTheCallersToSet() {
        val crossings = SpacebarCrossings(depth = 0.8f)
        assertEquals(Step.HELD, crossings.feed(370f))
        assertEquals(Step.CROSSED, crossings.feed(380f))
    }
}
