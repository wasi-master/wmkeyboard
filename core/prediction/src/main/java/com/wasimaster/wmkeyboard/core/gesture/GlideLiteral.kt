package com.wasimaster.wmkeyboard.core.gesture

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The word a stroke spells straight off the keys it marks (#1291): the key
 * where the finger went down, every key where the path turns sharply or the
 * finger stopped, and the key where it lifted.
 *
 * What a glide offers when no word list knows the word — a name, a Hinglish
 * word, a word of the user's own. The decoder can only answer with words it
 * has, so a stroke for one it lacks comes back as the nearest word that does
 * fit, and the strip is a row of unrelated words. The keys a stroke marks are
 * the letters a person draws on purpose: Swype's rule, and the evidence
 * AOSP's gesture code weighs most (its `updateAlignPointProbabilities` all but
 * rules out skipping the first and last point, a deep corner or a slow
 * point). `DuetWord.literal` is the crude ancestor, ends only.
 *
 * Letters the finger passes over in a straight line cannot be told from the
 * keys it merely crosses, so a word with one ("kyaa" drawn k-y-a with y on
 * the way) loses it unless the finger pauses there; a doubled letter is never
 * read, since one key is one mark. Measured 2026-11-13 on the 2,000 real
 * strokes of `glide/futo-swipes.txt.gz` (`:app`'s test resources): the
 * reading is the word exactly on .387 of them and the word less its doubled
 * letters on .410, against .146 for the ends alone. 1,732 of the 2,000 words
 * have every letter at a turn or an end; most misses are the key next door
 * at an end or a cut corner.
 *
 * Pure geometry, in key widths. No word list is consulted, so it reads any
 * word in any script a grid draws.
 */
object GlideLiteral {

    /** Arc length between resampled points, in key widths. */
    const val STEP = 0.2f

    /**
     * How far either side of a point its turn is measured over, in key widths.
     * A turn read point to point is the finger's tremor; over a key's width
     * either side it is where the path changed direction.
     */
    const val REACH = 0.6f

    /**
     * Least turn that marks a key, in degrees. Swept 30 to 65 on the real
     * strokes: .4065 at 30, .4045 at 35, .3980 at 45 (with [PAUSE] off),
     * falling from there. 35 rather than 30 so a curved approach drawn
     * slowly does not mark the key before the one it curves into.
     */
    const val CORNER_DEGREES = 35f

    /**
     * How many times the stroke's median pace a stretch must take to mark its
     * key as a pause. The real strokes carry no deliberate pauses, so they can
     * only say what reading them costs: at 4 the hesitations of a finger
     * looking for the next key cost .007, at 8 the reading gains .0065 and
     * at 14 nothing. A pause is how a letter on a straight run (the y of
     * "kya") gets in at all.
     */
    const val PAUSE = 8f

    /**
     * How far back from the lift the last key is read, in key widths. A lift
     * runs on past the key or stops short of it about equally, so this is
     * small: 0.2 read the last letter .757 right against .750 at the lift
     * itself, and 0.4 already cost .034.
     */
    const val END_BACK = 0.2f

    /**
     * The share of the best dictionary reading's alignment cost the literal
     * must explain the stroke within for the reading to count as weak and the
     * literal to be offered beside it ([offerBeside]).
     *
     * A ratio and not a margin: the costs grow with how sloppily a stroke was
     * drawn, so a fixed margin offered on every sloppy stroke of a word the
     * list has. Measured 2026-11-13 on the same 2,000 real strokes
     * (`native/bench/futo-swipe/literal_gate.py` over `glide_beam_eval`),
     * against the bundled list and with every one of the strokes' words
     * taken out of it to stand for words a list lacks; the second figure of
     * each pair is over a 300,000-word English list:
     *
     *     share   offered beside a     right word pushed   literal right on
     *             right reading        off a 3-slot strip  a missing word
     *     0.30    .055  .050           6  6                .139  .117
     *     0.35    .073  .068           8  8                .155  .134
     *     0.40    .098  .090           9  9                .175  .151
     *     0.50    .139  .130           13 13               .201  .175
     *     always  .436  .414           31 32               .261  .238
     *
     * Of the times it is offered for a missing word it is the word 42 to 44
     * times in a hundred at 0.35. A fixed margin did worse: at the
     * missing-word rate of a 0.5 share it sat beside .219 of the right
     * readings against .131 (both measured with a 45 degree corner, before
     * pauses were read). The leader's cost alone, or whether the leader
     * passes near the literal's keys, did worse still.
     */
    const val FIT_SHARE = 0.35

    /** Longest stroke read, in resampled points: a scribble is not a word. */
    private const val MAX_POINTS = 600

    /**
     * The letters [points] marks on [keys], lowercased, or null when the
     * stroke marks fewer than two or is no word-shaped stroke at all.
     * [points] are in pixels as drawn; [keys] holds its centres in key widths
     * of [keyWidthPx]. Only keys whose character is a letter or a mark take
     * part, so a stroke ending on the apostrophe key reads the letter beside
     * it.
     */
    fun read(points: List<GesturePoint>, keys: GlideKeyMap, keyWidthPx: Float): String? {
        val marks = anchors(points, keyWidthPx) ?: return null
        val letters = (0 until keys.keyCount).filter { isLetterKey(keys.primaryCodePoint(it)) }
        if (letters.isEmpty()) return null
        val out = StringBuilder()
        var previous = -1
        for (mark in marks) {
            val key = letters.minBy {
                val dx = keys.keyX[it] - mark.x
                val dy = keys.keyY[it] - mark.y
                dx * dx + dy * dy
            }
            if (key == previous) continue
            previous = key
            out.appendCodePoint(keys.primaryCodePoint(key))
        }
        val literal = out.toString()
        if (literal.codePointCount(0, literal.length) < 2) return null
        return literal.takeIf { Character.isLetter(it.codePointAt(0)) }
    }

    /**
     * Whether [literal] adds nothing to a strip already holding [word]: the
     * word is the literal with letters doubled, or with a hyphen or an
     * apostrophe the finger could not draw. "will" covers "wil" and "don't"
     * covers "dont", so neither literal is offered beside them.
     */
    fun coveredBy(literal: String, word: String): Boolean = drawable(word) == literal

    /**
     * Whether a stroke whose best dictionary reading is explained at
     * [readingCost] should have its literal, explained at [literalCost], put
     * beside it: costs in the decoder's alignment units
     * (`GlideBeam.Candidate.shapeCost`). See [FIT_SHARE].
     */
    fun offerBeside(readingCost: Double, literalCost: Double): Boolean =
        readingCost > 0.0 && literalCost <= FIT_SHARE * readingCost

    /** A marked place on the stroke, in key widths. */
    internal class Mark(val x: Float, val y: Float)

    /**
     * Where [points] marks a key, in order: its start, each sharp turn and
     * each pause, and its end. Null for a stroke too short or too long to read.
     */
    internal fun anchors(points: List<GesturePoint>, keyWidthPx: Float): List<Mark>? {
        if (points.size < 2 || keyWidthPx <= 0f) return null
        val path = resample(points, keyWidthPx) ?: return null
        val n = path.size
        if (n < 2) return null
        val reach = maxOf(1, (REACH / STEP).roundToInt())
        val turn = FloatArray(n)
        for (i in 1 until n - 1) {
            val a = maxOf(0, i - reach)
            val c = minOf(n - 1, i + reach)
            turn[i] = angle(path[a], path[i], path[c])
        }
        val threshold = Math.toRadians(CORNER_DEGREES.toDouble()).toFloat()
        val at = ArrayList<Pair<Int, Sample>>()
        at += 0 to path[0]
        for (i in 1 until n - 1) {
            if (turn[i] < threshold) continue
            val lo = maxOf(0, i - reach)
            val hi = minOf(n - 1, i + reach)
            // The sharpest point of the turn, the first of a tie.
            if ((lo..hi).any { it != i && turn[it] > turn[i] }) continue
            if ((lo until i).any { turn[it] == turn[i] }) continue
            at += i to path[i]
        }
        pauses(path).forEach { at += it to path[it] }
        val back = (END_BACK / STEP).roundToInt()
        at += (n - 1) to path[maxOf(0, n - 1 - back)]
        return at.sortedBy { it.first }.map { Mark(it.second.x, it.second.y) }
    }

    /** The points where the finger took [PAUSE] times its median pace, each the slowest of its neighbours. */
    private fun pauses(path: List<Sample>): List<Int> {
        val n = path.size
        if (n < 3) return emptyList()
        val steps = DoubleArray(n - 1) { path[it + 1].t - path[it].t }.sorted()
        val median = steps[steps.size / 2]
        if (median <= 0.0) return emptyList()
        fun dwell(i: Int) = (path[i + 1].t - path[i - 1].t) / 2.0
        val out = ArrayList<Int>()
        for (i in 1 until n - 1) {
            val here = dwell(i)
            if (here < PAUSE * median) continue
            val lo = maxOf(1, i - 1)
            val hi = minOf(n - 2, i + 1)
            if ((lo..hi).all { dwell(it) <= here }) out += i
        }
        return out
    }

    private class Sample(val x: Float, val y: Float, val t: Double)

    /** [points] every [STEP] key widths along the path, the clock interpolated. */
    private fun resample(points: List<GesturePoint>, keyWidthPx: Float): List<Sample>? {
        val out = ArrayList<Sample>()
        var px = points[0].x / keyWidthPx
        var py = points[0].y / keyWidthPx
        var pt = points[0].t.toDouble()
        out += Sample(px, py, pt)
        var carried = 0f
        for (index in 1 until points.size) {
            val x = points[index].x / keyWidthPx
            val y = points[index].y / keyWidthPx
            val t = points[index].t.toDouble()
            var seg = hypot(x - px, y - py)
            while (seg > 0f && carried + seg >= STEP) {
                val r = (STEP - carried) / seg
                val nx = px + (x - px) * r
                val ny = py + (y - py) * r
                val nt = pt + (t - pt) * r
                out += Sample(nx, ny, nt)
                if (out.size > MAX_POINTS) return null
                px = nx
                py = ny
                pt = nt
                seg = hypot(x - px, y - py)
                carried = 0f
            }
            carried += seg
            px = x
            py = y
            pt = t
        }
        val last = points.last()
        val lx = last.x / keyWidthPx
        val ly = last.y / keyWidthPx
        if (hypot(out.last().x - lx, out.last().y - ly) > STEP * TAIL_SHARE) out += Sample(lx, ly, last.t.toDouble())
        return out
    }

    /** How much of a step the lift must lie past the last resampled point to count as a point of its own. */
    private const val TAIL_SHARE = 0.25f

    /** The turn at [b] from the direction a→b to b→c, in radians: 0 straight on, π straight back. */
    private fun angle(a: Sample, b: Sample, c: Sample): Float {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val vx = c.x - b.x
        val vy = c.y - b.y
        val nu = hypot(ux, uy)
        val nv = hypot(vx, vy)
        if (nu < EPSILON || nv < EPSILON) return 0f
        return acos(((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1f, 1f))
    }

    private const val EPSILON = 1e-6f

    private fun isLetterKey(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }

    /** [word] lowercased as a finger draws it: its letters and marks only, each run of one letter once. */
    private fun drawable(word: String): String {
        val out = StringBuilder()
        var previous = -1
        var at = 0
        while (at < word.length) {
            val raw = word.codePointAt(at)
            at += Character.charCount(raw)
            val cp = Character.toLowerCase(raw)
            if (!isLetterKey(cp) || cp == previous) continue
            previous = cp
            out.appendCodePoint(cp)
        }
        return out.toString()
    }
}
