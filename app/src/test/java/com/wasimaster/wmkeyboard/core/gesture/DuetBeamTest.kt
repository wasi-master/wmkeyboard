package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-thumb decoding (issue #638) and typed letters finished by a swipe (#595). */
class DuetBeamTest {

    // The same synthetic QWERTY grid as GlideBeamTest: 60px keys, offset rows.
    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private val lexicon = listOf(
        "something" to 800, "some" to 900, "nothing" to 700, "thing" to 600, "same" to 500,
        "hello" to 900, "help" to 700, "hell" to 200, "held" to 300, "ho" to 400,
        "together" to 700, "gather" to 300, "tether" to 100, "the" to 1000,
        "keep" to 500, "kept" to 400, "moms" to 50, "mose" to 10,
        "movement" to 300, "moment" to 400, "probably" to 300, "probable" to 100,
        "heed" to 100, "head" to 600,
    )

    private fun sourcesOf(entries: List<Pair<String, Int>>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    private val sources = sourcesOf(lexicon)

    private val beam = DuetBeam()
    private val workspace = DuetWorkspace()

    private fun decode(vararg tokens: GlideToken): List<String> = decodeFrom(sources, *tokens)

    private fun decodeFrom(from: List<FuzzyBeamSearch.WalkSource>, vararg tokens: GlideToken): List<String> =
        beam.decode(tokens.toList(), grid, keyWidth, from, workspace, limit = 4).map { it.word }

    private fun tap(letter: Char, t: Long): GlideToken.Tap {
        val key = centers.getValue(letter.code)
        // A little off centre, the way a thumb lands.
        return GlideToken.Tap(key.x + 6f, key.y - 4f, t)
    }

    /** Straight lines through [letters]' keys, [msPerKey] per key-to-key leg, from [t0]. */
    private fun swipe(letters: String, t0: Long, msPerKey: Long = 150L): GlideToken.Swipe {
        val anchors = letters.map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val last = if (i == anchors.size - 2) STEPS else STEPS - 1
            for (step in 0..last) {
                val f = step / STEPS.toFloat()
                points.add(
                    GesturePoint(
                        a.x + f * (b.x - a.x),
                        a.y + f * (b.y - a.y),
                        t0 + i * msPerKey + (f * msPerKey).toLong(),
                    ),
                )
            }
        }
        return GlideToken.Swipe(points)
    }

    /**
     * [swipe] with a small circle drawn on [letter]'s key, built like
     * GlideBeamTest's: the finger reaches the key, goes once round through it
     * and carries on. The circle takes no time, so only its geometry speaks.
     */
    private fun loopedAt(swipe: GlideToken.Swipe, letter: Char): GlideToken.Swipe {
        val path = swipe.points
        val key = centers.getValue(letter.code)
        var nearest = 0
        var best = Float.MAX_VALUE
        path.forEachIndexed { i, p ->
            val d = (p.x - key.x) * (p.x - key.x) + (p.y - key.y) * (p.y - key.y)
            if (d < best) {
                best = d
                nearest = i
            }
        }
        val at = path[nearest]
        val before = path[(nearest - 1).coerceAtLeast(0)]
        val after = path[(nearest + 1).coerceAtMost(path.lastIndex)]
        var dx = after.x - before.x
        var dy = after.y - before.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
        dx /= len
        dy /= len
        // Centre off to one side of the line of travel, so the circle passes
        // through the key rather than around it.
        val cx = at.x - dy * LOOP_RADIUS_PX
        val cy = at.y + dx * LOOP_RADIUS_PX
        val start = atan2(at.y - cy, at.x - cx)
        val out = ArrayList<GesturePoint>(path.size + LOOP_POINTS)
        path.forEachIndexed { i, p ->
            out.add(p)
            if (i == nearest) {
                for (s in 1..LOOP_POINTS) {
                    val angle = start + 2.0 * PI * s / LOOP_POINTS
                    out.add(
                        GesturePoint(
                            cx + LOOP_RADIUS_PX * cos(angle).toFloat(),
                            cy + LOOP_RADIUS_PX * sin(angle).toFloat(),
                            p.t,
                        ),
                    )
                }
            }
        }
        return GlideToken.Swipe(out)
    }

    @Test
    fun `left taps and right swipes overlapping in time spell one word`() {
        // The example from the issue: S + OM + E + THING.
        val words = decode(
            tap('s', 0L),
            swipe("om", 40L),
            tap('e', 300L),
            swipe("thing", 330L, msPerKey = 120L),
        )
        assertEquals("something", words.firstOrNull())
    }

    @Test
    fun `a tap landing inside the other thumb's swipe goes where the swipe was at that moment`() {
        // h→e→l→o drawn by one thumb, the l reached at 300 ms; the other taps l
        // just after. The doubled l comes from the tap, between the swipe's l and o.
        val words = decode(swipe("helo", 0L), tap('l', 320L))
        assertEquals("hello", words.firstOrNull())
    }

    @Test
    fun `typed letters before a swipe are the start of the swiped word`() {
        // Issue #595: type "to", swipe the rest.
        val words = decode(tap('t', 0L), tap('o', 180L), swipe("gether", 450L))
        assertEquals("together", words.firstOrNull())
    }

    @Test
    fun `tokens without a clock are taken in list order`() {
        val words = decode(
            GlideToken.Tap(centers.getValue('t'.code).x, centers.getValue('t'.code).y, 0L),
            GlideToken.Tap(centers.getValue('o'.code).x, centers.getValue('o'.code).y, 0L),
            GlideToken.Swipe(swipe("gether", 0L).points.map { GesturePoint(it.x, it.y) }),
        )
        assertEquals("together", words.firstOrNull())
    }

    @Test
    fun `one tap may stand for a doubled letter`() {
        val words = decode(tap('k', 0L), tap('e', 200L), tap('p', 400L))
        assertEquals("keep", words.firstOrNull())
    }

    @Test
    fun `a tap that came well after a swipe never lands inside it`() {
        // o→m drawn and lifted, then s and e tapped long after: the letters
        // cannot be reordered into "some", whatever the dictionary prefers.
        val words = decode(swipe("om", 0L), tap('s', 600L), tap('e', 900L))
        assertFalse("some" in words)
        assertFalse("same" in words)
    }

    @Test
    fun `every gesture has to be used`() {
        // "thing" off the swipe alone is not an answer: the s tap must be in it.
        val words = decode(tap('s', 0L), swipe("thing", 400L))
        assertFalse("thing" in words)
    }

    @Test
    fun `gestures that finish no word yet still read as the start of one`() {
        // Issue #640: s + o→m is on its way to "something" and is no word
        // itself. The word reading is empty, and the partial one is what the
        // field shows meanwhile, so nothing the user did is dropped.
        val tokens = listOf(tap('s', 0L), swipe("om", 40L))
        assertTrue(beam.decode(tokens, grid, keyWidth, sources, workspace).isEmpty())
        val started = beam.decode(tokens, grid, keyWidth, sources, workspace, partial = true).map { it.word }
        assertEquals("som", started.firstOrNull())
    }

    // Issue #674: words the thumbs take turns on, with gaps between them.

    @Test
    fun `thumbs taking turns spell movement`() {
        // m-o right, v-e left, m right, e left, n-t right: never overlapping.
        val words = decode(
            swipe("mo", 0L),
            swipe("ve", 220L),
            tap('m', 420L),
            tap('e', 520L),
            swipe("nt", 640L),
        )
        assertEquals("movement", words.firstOrNull())
    }

    @Test
    fun `thumbs taking turns spell probably, the b from both`() {
        val words = decode(
            tap('p', 0L),
            tap('r', 120L),
            tap('o', 240L),
            swipe("ba", 360L),
            swipe("bly", 620L),
        )
        assertEquals("probably", words.firstOrNull())
    }

    @Test
    fun `a doubled letter split between the thumbs`() {
        // h-e swiped, the second e tapped by the other thumb after it lifted.
        assertEquals("heed", decode(swipe("he", 0L), tap('e', 260L), tap('d', 400L)).firstOrNull())
        // h-e-d swiped, the second e tapped as the swipe sits on its e.
        assertEquals("heed", decode(swipe("hed", 0L), tap('e', 170L)).firstOrNull())
    }

    // Issue #740: the loop that marks a doubled letter, read as one stroke reads it.

    @Test
    fun `a loop on a key reads as the doubled spelling`() {
        // GlideBeamTest's god and good, with the g tapped and o→d swiped. The
        // swipe crosses the o once either way, so unmarked the commoner god
        // wins; a loop on the o waives good's doubling and charges god for
        // ignoring it.
        val from = sourcesOf(listOf("god" to 400, "good" to 300))
        assertEquals("god", decodeFrom(from, tap('g', 0L), swipe("od", 200L)).firstOrNull())
        assertEquals("good", decodeFrom(from, tap('g', 0L), loopedAt(swipe("od", 200L), 'o')).firstOrNull())
    }

    @Test
    fun `a loop asks for the doubled letter the other thumb's swipe starts on`() {
        // m tapped dead centre, e→t swiped. Unmarked, net's whole-key miss
        // costs less than meet's doubling and meet comes last. A loop on the e
        // puts meet straight after met, whose lead in frequency is more than a
        // loop is meant to overcome.
        val from = sourcesOf(listOf("met" to 1757, "net" to 805, "meet" to 150))
        val m = centers.getValue('m'.code)
        val plain = decodeFrom(from, GlideToken.Tap(m.x, m.y, 0L), swipe("et", 200L))
        assertEquals(listOf("met", "net", "meet"), plain)
        val looped = decodeFrom(from, GlideToken.Tap(m.x, m.y, 0L), loopedAt(swipe("et", 200L), 'e'))
        assertEquals(listOf("met", "meet", "net"), looped)
    }

    @Test
    fun `routing sends a lone swipe and taps alone elsewhere`() {
        assertFalse(DuetBeam.needsDuet(listOf(swipe("helo", 0L))))
        assertFalse(DuetBeam.needsDuet(listOf(tap('a', 0L), tap('b', 100L))))
        assertTrue(DuetBeam.needsDuet(listOf(tap('t', 0L), swipe("gether", 200L))))
        assertTrue(DuetBeam.needsDuet(listOf(swipe("om", 0L), swipe("thing", 400L))))
    }

    private companion object {
        const val STEPS = 10
        const val LOOP_RADIUS_PX = 18f
        const val LOOP_POINTS = 24
    }
}
