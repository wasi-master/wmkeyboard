package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlideStroke.openRun], the Kotlin twin of `wm-engine`'s `glide::open_run`
 * (#968): the straight walk from a word's last letter down to the spacebar,
 * or from the bar up into the next word, read off a chained stroke's
 * segment so the decoder can treat it as optional rather than as letters.
 */
class GlideStrokeOpenRunTest {

    private val keyWidth = 60f
    private val keyHeight = 84f

    /** QWERTY key centres on a 60 × 84 grid; the bar's top edge is right under the bottom row. */
    private fun centre(c: Char): Pair<Float, Float> {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        rows.forEachIndexed { row, (letters, offset) ->
            val i = letters.indexOf(c)
            if (i >= 0) return (offset + i + 0.5f) * keyWidth to (row + 0.5f) * keyHeight
        }
        error("no key $c")
    }

    private val barTop = 3f * keyHeight

    private fun line(out: MutableList<GesturePoint>, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val len = kotlin.math.hypot(to.first - from.first, to.second - from.second)
        val steps = kotlin.math.ceil(len / keyWidth * 8).toInt().coerceAtLeast(1)
        for (s in 1..steps) {
            val f = s.toFloat() / steps
            out.add(GesturePoint(from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f))
        }
    }

    private fun swipe(word: String): MutableList<GesturePoint> {
        val out = mutableListOf(GesturePoint(centre(word[0]).first, centre(word[0]).second))
        word.zipWithNext().forEach { (a, b) -> line(out, centre(a), centre(b)) }
        return out
    }

    @Test
    fun aWalkDownToTheBarIsAnOpenTailAndAPlainWordHasNone() {
        val walked = swipe("the")
        val last = walked.last()
        line(walked, last.x to last.y, last.x to barTop)
        assertTrue(GlideStroke.openRun(walked, keyWidth, atEnd = true) >= 20)
        // "the" ends with a shallow climb from h to e: no walk, however level it runs.
        assertEquals(0, GlideStroke.openRun(swipe("the"), keyWidth, atEnd = true))
    }

    @Test
    fun aDiagonalWalkIsARunAndItStopsAtTheCorner() {
        // "top" ends far right of the bar; the walk from p makes for its end.
        val walked = swipe("top")
        val drawn = walked.size
        val last = walked.last()
        line(walked, last.x to last.y, last.x - 2f * keyWidth to barTop)
        // The whole walk, and at most the sample the corner is drawn from.
        val run = GlideStroke.openRun(walked, keyWidth, atEnd = true)
        assertTrue("$run", run in walked.size - drawn..walked.size - drawn + 1)
    }

    @Test
    fun aWalkUpFromTheBarIsAnOpenHead() {
        val first = centre('c')
        val begun = mutableListOf(GesturePoint(first.first, barTop))
        line(begun, first.first to barTop, first)
        begun.addAll(swipe("cat").drop(1))
        assertTrue(GlideStroke.openRun(begun, keyWidth, atEnd = false) >= 4)
        // Never so long that fewer than three samples stay closed.
        assertTrue(GlideStroke.openRun(begun, keyWidth, atEnd = false) <= begun.size - 3)
    }

    @Test
    fun aSegmentThatIsOnlyItsWalksTurnsAtItsLetter() {
        val a = centre('a')
        val walked = mutableListOf(GesturePoint(a.first + keyWidth, barTop))
        line(walked, a.first + keyWidth to barTop, a)
        line(walked, a, a.first + keyWidth to barTop)
        val head = GlideStroke.openRun(walked, keyWidth, atEnd = false)
        val tail = GlideStroke.openRun(walked, keyWidth, atEnd = true)
        val turn = GlideStroke.walkTurn(walked, head, tail, keyWidth)
        assertEquals(a.second, turn!!.y, 1f)
    }

    @Test
    fun aWordBetweenItsWalksHasNoTurn() {
        val walked = swipe("as")
        val last = walked.last()
        line(walked, last.x to last.y, last.x to barTop)
        val tail = GlideStroke.openRun(walked, keyWidth, atEnd = true)
        assertEquals(null, GlideStroke.walkTurn(walked, 0, tail, keyWidth))
    }

    @Test
    fun aBottomRowLetterOnTheWayDownIsInsideTheRun() {
        // "then": n sits straight above the bar, so the walk from e through n
        // to the bar is one run and n is on it, where the decoder may still
        // place it.
        val walked = swipe("then")
        val last = walked.last()
        line(walked, last.x to last.y, last.x to barTop)
        val run = GlideStroke.openRun(walked, keyWidth, atEnd = true)
        val innermost = walked[walked.size - 1 - run]
        assertTrue(innermost.y < centre('n').second)
    }
}
