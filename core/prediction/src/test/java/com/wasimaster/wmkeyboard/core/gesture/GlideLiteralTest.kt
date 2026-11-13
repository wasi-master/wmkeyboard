package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlideLiteral] reads the keys a stroke marks (#1291). The strokes here are
 * drawn through key centres on a QWERTY grid a hundred pixels to the key, at
 * an even pace unless a test pauses the finger.
 */
class GlideLiteralTest {

    private val keyWidth = 100f

    private val centres: List<KeyCenter> = buildList {
        val rows = listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1.0f, "zxcvbnm" to 2.0f)
        rows.forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, ch ->
                add(KeyCenter(ch.code, (i + offset) * keyWidth, (0.7f + row * ROW_PITCH) * keyWidth))
            }
        }
    }

    private val keys = GlideKeyMap.of(centres, keyWidth)

    private fun centreOf(ch: Char): KeyCenter = centres.first { it.codePoint == ch.code }

    /**
     * A stroke through the centres of [word]'s keys, a point every ten
     * pixels and ten milliseconds, resting [pauseMs] on the keys at [pauseAt]
     * (indices into [word]).
     */
    private fun stroke(word: String, pauseAt: Set<Int> = emptySet(), pauseMs: Long = 0L): List<GesturePoint> {
        val out = ArrayList<GesturePoint>()
        var t = 0L
        word.forEachIndexed { i, ch ->
            val to = centreOf(ch)
            if (out.isEmpty()) {
                out += GesturePoint(to.x, to.y, t)
            } else {
                val from = out.last()
                val dx = to.x - from.x
                val dy = to.y - from.y
                val steps = maxOf(1, (kotlin.math.hypot(dx, dy) / SPACING).toInt())
                for (s in 1..steps) {
                    t += STEP_MS
                    out += GesturePoint(from.x + dx * s / steps, from.y + dy * s / steps, t)
                }
            }
            if (i in pauseAt) {
                t += pauseMs
                out += GesturePoint(to.x, to.y, t)
            }
        }
        return out
    }

    @Test
    fun `a stroke reads the keys where it starts, turns and ends`() {
        assertEquals("bhai", GlideLiteral.read(stroke("bhai"), keys, keyWidth))
        assertEquals("rakesh", GlideLiteral.read(stroke("rakesh"), keys, keyWidth))
    }

    @Test
    fun `keys crossed on a straight run are not letters`() {
        // k to a crosses j h g f d s, and a to l crosses them back.
        assertEquals("kal", GlideLiteral.read(stroke("kal"), keys, keyWidth))
        assertEquals("qp", GlideLiteral.read(stroke("qp"), keys, keyWidth))
    }

    @Test
    fun `a pause on a straight run marks its key`() {
        val paused = stroke("qtp", pauseAt = setOf(1), pauseMs = 400L)
        assertEquals("qtp", GlideLiteral.read(paused, keys, keyWidth))
        // The same path at an even pace has nothing to mark t with.
        assertEquals("qp", GlideLiteral.read(stroke("qtp"), keys, keyWidth))
    }

    @Test
    fun `a doubled letter is one mark`() {
        assertEquals("wil", GlideLiteral.read(stroke("will"), keys, keyWidth))
    }

    @Test
    fun `a stroke inside one key reads nothing`() {
        val c = centreOf('g')
        val wobble = List(8) { GesturePoint(c.x + it * 3f, c.y + (it % 2) * 4f, it * 10L) }
        assertNull(GlideLiteral.read(wobble, keys, keyWidth))
        assertNull(GlideLiteral.read(emptyList(), keys, keyWidth))
    }

    @Test
    fun `a key that is not a letter is never read`() {
        // An apostrophe key just right of l: a stroke ending past l reads l.
        val l = centreOf('l')
        val withApostrophe = GlideKeyMap.of(centres + KeyCenter('\''.code, l.x + keyWidth, l.y), keyWidth)
        val path = stroke("kl").let { it + GesturePoint(l.x + keyWidth * 0.9f, l.y, it.last().t + STEP_MS) }
        assertEquals("kl", GlideLiteral.read(path, withApostrophe, keyWidth))
    }

    @Test
    fun `a word covers its literal when it only adds doubles or joiners`() {
        assertTrue(GlideLiteral.coveredBy("wil", "will"))
        assertTrue(GlideLiteral.coveredBy("dont", "don't"))
        assertTrue(GlideLiteral.coveredBy("bhai", "Bhai"))
        assertFalse(GlideLiteral.coveredBy("bhai", "bhaiya"))
        assertFalse(GlideLiteral.coveredBy("kal", "kl"))
    }

    @Test
    fun `the literal is offered only when it explains the stroke far better`() {
        assertTrue(GlideLiteral.offerBeside(readingCost = 10.0, literalCost = 3.0))
        assertTrue(GlideLiteral.offerBeside(readingCost = 10.0, literalCost = 3.5))
        assertFalse(GlideLiteral.offerBeside(readingCost = 10.0, literalCost = 4.0))
        // A reading the stroke fits perfectly is never weak, whatever the literal does.
        assertFalse(GlideLiteral.offerBeside(readingCost = 0.0, literalCost = 0.0))
    }

    private companion object {
        /** Rows are taller than keys are wide, as on a phone. */
        const val ROW_PITCH = 1.4f
        const val SPACING = 10f
        const val STEP_MS = 10L
    }
}
