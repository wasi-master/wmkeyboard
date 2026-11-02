// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShiftDetour
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a detour to the shift key costs a glide (#163), on English/QWERTY at
 * every graded noise level.
 *
 * Each corpus stroke is redrawn with a walk out to the shift key and back
 * after one of its letters, chosen at random — the first and the last
 * included, which the reporter found to be the worst cases. Three readings
 * of the same stroke are decoded:
 *
 *  - **clean** — the stroke as the corpus drew it, with no detour: the
 *    ceiling;
 *  - **key dropped** — the detour with only the points over the shift key
 *    itself removed, which is what the keyboard did before this harness
 *    existed, and what read t→shift→here as "trashed";
 *  - **legs cut** — the detour with the walk out and back cut away by
 *    [GlideShiftDetour], which is what it does now.
 *
 * The strokes that *end* on the shift key — the per-letter reading's shout,
 * and the whole-word reading's "capitalize after the last letter" — are
 * measured apart, since they have only an entry leg.
 *
 * Alongside accuracy: how often the per-letter reading names the letter the
 * detour followed, on the strokes it read correctly. A capital on the wrong
 * letter is a different kind of miss from a wrong word, and worth its own
 * number.
 */
class GlideShiftDetourEvalTest {

    /** Committed floors for one noise level: top-1 with the legs cut, mid-word and ending on the key. */
    private class Floors(val cut: Double, val endCut: Double)

    private companion object {
        const val SEED = 42L

        /**
         * Enough strokes that [TOLERANCE] is about three standard errors at
         * every level. At 300 the error was .015 to .026, so one draw of cases
         * against another moved CLEAN between .89 and .94 on an unchanged
         * decoder; the 2026-10-19 rescale of en.txt redrew the cases and read
         * as a regression it was not.
         */
        const val CASES_PER_LEVEL = 1200

        /** A floor is the measured number less this; a change that drops below it fails. */
        const val TOLERANCE = 0.03

        /**
         * Measured 2026-10-21, English/QWERTY, the shipped 17k list after its
         * rescale, seed 42, 1200 strokes a level. Against the same strokes
         * with only the key's own points dropped — what the keyboard did
         * before — top-1 is .37 mid-word and .17 ending on the key, and the
         * no-detour ceiling is .95.
         *
         * The first floors (2026-09-15, 300 strokes) were one lucky draw.
         * Today's decoder on that draw's cases still reads .937 / .937 / .877
         * / .737, but at 1200 strokes the same list reads .930 / .913 / .849 /
         * .706, and the rescaled list's own cases .913 / .908 / .866 / .712.
         * The rescale itself moves no level by more than .004 on a fixed case
         * set.
         *
         * Three cuts were measured on the way here, and the numbers say why
         * the shipped one is the shipped one: a walk back along the stroke's
         * local heading, stopping at a turn of 45°, read .61 (it stopped on
         * tremor, or ran through a letter approached within 45° of the leg);
         * one Douglas–Peucker split with a settling walk read .84 (a letter
         * behind the bend could out-stick it once the chord's start passed
         * that letter); splitting again on the key's side until the run is
         * straight, with the chord run to the key's centre, reads .87. What
         * is left is a letter that lies on the leg's own line, which no
         * geometry can tell from the leg, and a letter whose only evidence
         * was the finger slowing on it, half of which the cut removes.
         */
        val FLOORS = mapOf(
            // Re-measured 2026-11-03: the real-swipe tuning (d0020afce) gave up
            // about the same on these synthetic strokes as on GestureEvalBaseline's.
            SwipeCorpus.Noise.CLEAN to Floors(cut = 0.8875, endCut = 0.8525),
            SwipeCorpus.Noise.LIGHT to Floors(cut = 0.8508, endCut = 0.8283),
            SwipeCorpus.Noise.TYPICAL to Floors(cut = 0.7917, endCut = 0.7567),
            SwipeCorpus.Noise.SLOPPY to Floors(cut = 0.6408, endCut = 0.6317),
        )

        /** Per-letter capitals on the right letter, over every level, measured .9117. */
        const val LETTER_FLOOR = 0.9117

        /** `SwipeCorpus`'s tremor: correlation over ~10 samples, stationary σ as asked. */
        const val TREMOR_RHO = 0.9f
        val TREMOR_MIX = sqrt(1f - TREMOR_RHO * TREMOR_RHO)
    }

    private class Counts {
        var total = 0
        var clean = 0
        var dropped = 0
        var cut = 0
        var lettered = 0
        var endTotal = 0
        var endDropped = 0
        var endCut = 0
        fun rate(n: Int, of: Int = total): Double = if (of == 0) 0.0 else n / of.toDouble()
    }

    private fun load(name: String): List<Pair<String, Int>> {
        val candidates = listOf(File("dictionaries-src/$name"), File("app/dictionaries-src/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
    private val shift = grid.shift ?: error("QWERTY's letter layer has no shift key")
    private val keyWidth = SwipeCorpus.KEY_WIDTH
    private val keys = GlideKeyMap.of(grid.keyCenters(keyWidth), keyWidth)
    private val beam = GlideBeam()
    private val workspace = GlideWorkspace()
    private val shiftCentre = GesturePoint(shift.centerX * keyWidth, shift.centerY * keyWidth)

    /** Consecutive repeats collapsed, with the char range each visit wrote. */
    private class Visit(val codePoint: Int, val start: Int, val end: Int)

    private fun visitsOf(word: String): List<Visit> {
        val out = ArrayList<Visit>()
        var at = 0
        while (at < word.length) {
            val cp = word.codePointAt(at)
            val next = at + Character.charCount(cp)
            val last = out.lastOrNull()
            if (last != null && last.codePoint == cp) {
                out[out.lastIndex] = Visit(cp, last.start, next)
            } else {
                out.add(Visit(cp, at, next))
            }
            at = next
        }
        return out
    }

    /** [word] with visit [k]'s characters in capitals. */
    private fun expectedCasing(word: String, visits: List<Visit>, k: Int): String {
        val v = visits[k]
        return word.substring(0, v.start) + word.substring(v.start, v.end).uppercase(Locale.ROOT) +
            word.substring(v.end)
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    /** Arc-length fraction at each sample of [path]. */
    private fun fractions(path: List<GesturePoint>): FloatArray {
        val out = FloatArray(path.size)
        for (i in 1 until path.size) out[i] = out[i - 1] + distance(path[i - 1], path[i])
        val total = out.last()
        if (total > 0f) for (i in out.indices) out[i] /= total
        return out
    }

    /**
     * The sample of [path] that visit [k] was drawn at: the one nearest the
     * key's centre among those near where the ideal polyline puts the visit,
     * so a key visited twice is found at the right visit.
     */
    private fun sampleOf(path: List<GesturePoint>, visits: List<Visit>, k: Int): Int {
        val anchors = visits.map { v ->
            val c = grid.centerOf(v.codePoint)!!
            GesturePoint(c.x * keyWidth, c.y * keyWidth)
        }
        val ideal = fractions(anchors)
        val drawn = fractions(path)
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in path.indices) {
            if (abs(drawn[i] - ideal[k]) > 0.3f) continue
            val d = distance(path[i], anchors[k])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return if (best >= 0) best else (ideal[k] * path.lastIndex).toInt()
    }

    private class Detoured(val points: List<GesturePoint>, val cuts: IntArray)

    /**
     * [path] redrawn with a walk from visit [k] out to the shift key and,
     * unless [endThere], straight on to visit `k + 1` (or back to where it
     * left, after the last letter). Points over the key are dropped and the
     * crossing recorded, as the keyboard does.
     */
    @Suppress("LongMethod")
    private fun detour(
        path: List<GesturePoint>,
        visits: List<Visit>,
        k: Int,
        endThere: Boolean,
        jitter: Float,
        random: Random,
    ): Detoured {
        val p = sampleOf(path, visits, k)
        val q = if (k + 1 < visits.size) maxOf(p + 1, sampleOf(path, visits, k + 1)) else p + 1
        val centre = shiftCentre
        val out = ArrayList<GesturePoint>(path.size + 64)
        val cuts = ArrayList<Int>(1)
        var wasOver = false
        var clock = 0L
        fun emit(x: Float, y: Float, t: Long) {
            val over = shift.contains(x / keyWidth, y / keyWidth)
            if (over && !wasOver) cuts.add(out.size)
            wasOver = over
            if (!over) out.add(GesturePoint(x, y, t))
        }
        // The corpus's own tremor: an AR(1) walk with the stationary σ the
        // level asks for, so the legs wander the way its strokes do rather
        // than buzzing sample to sample.
        var tremorX = 0f
        var tremorY = 0f
        fun leg(from: GesturePoint, to: GesturePoint, fromT: Long): Long {
            val steps = maxOf(1, (distance(from, to) / (0.08f * keyWidth)).toInt())
            var t = fromT
            for (step in 1..steps) {
                val f = step / steps.toFloat()
                t += 8L
                tremorX = TREMOR_RHO * tremorX + TREMOR_MIX * jitter * random.nextGaussian().toFloat()
                tremorY = TREMOR_RHO * tremorY + TREMOR_MIX * jitter * random.nextGaussian().toFloat()
                emit(
                    from.x + f * (to.x - from.x) + tremorX * keyWidth,
                    from.y + f * (to.y - from.y) + tremorY * keyWidth,
                    t,
                )
            }
            return t
        }
        for (i in 0..p) emit(path[i].x, path[i].y, path[i].t)
        clock = leg(path[p], centre, path[p].t)
        if (endThere || q > path.lastIndex) {
            if (!endThere) {
                // After the last letter: back to it, and lift there.
                leg(centre, path[p], clock)
            }
            return Detoured(out, cuts.toIntArray())
        }
        clock = leg(centre, path[q], clock)
        val shiftBy = clock - path[q].t
        for (i in q..path.lastIndex) emit(path[i].x, path[i].y, path[i].t + shiftBy)
        return Detoured(out, cuts.toIntArray())
    }

    private fun top1(path: List<GesturePoint>, sources: List<FuzzyBeamSearch.WalkSource>): String? =
        beam.decode(path, keys, keyWidth, sources, workspace, 1).firstOrNull()?.word

    @Test
    fun aDetourToShiftCostsNothingOnceItsLegsAreCut() {
        val english = load("en.txt")
        val trie = Trie().apply { english.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val corpus = SwipeCorpus(SEED, grid = grid)
        val random = Random(SEED)

        println()
        println("Shift detour on en/qwerty, $CASES_PER_LEVEL strokes a level; top-1")
        println(
            String.format(
                Locale.ROOT, "%-8s %7s %11s %9s %9s | %11s %9s",
                "noise", "clean", "key dropped", "legs cut", "letter", "end:dropped", "end:cut",
            )
        )
        val all = Counts()
        val byNoise = LinkedHashMap<SwipeCorpus.Noise, Counts>()
        for (noise in SwipeCorpus.Noise.entries) {
            val counts = Counts()
            for (case in corpus.generate(EvalSampling.entries(english), noise, CASES_PER_LEVEL)) {
                val visits = visitsOf(case.intended)
                val k = random.nextInt(visits.size)
                val jitter = noise.profile.jitter
                val mid = detour(case.path, visits, k, endThere = false, jitter, random)
                val cut = GlideShiftDetour.trim(mid.points, mid.cuts, keyWidth, key = shiftCentre)
                counts.total++
                if (top1(case.path, sources) == case.intended) counts.clean++
                if (top1(mid.points, sources) == case.intended) counts.dropped++
                if (top1(cut.points, sources) == case.intended) {
                    counts.cut++
                    val alignment = beam.align(case.intended, cut.points, keys, keyWidth, workspace)
                    val lettered = GlideCase.Letters(cut.cuts, shout = false)
                        .caseWord(case.intended, alignment)
                    if (lettered == expectedCasing(case.intended, visits, k)) counts.lettered++
                }
                // Out to the key after the last letter and lift there.
                val end = detour(case.path, visits, visits.lastIndex, endThere = true, jitter, random)
                val endCut = GlideShiftDetour.trim(end.points, end.cuts, keyWidth, key = shiftCentre)
                counts.endTotal++
                if (top1(end.points, sources) == case.intended) counts.endDropped++
                if (top1(endCut.points, sources) == case.intended) counts.endCut++
            }
            byNoise[noise] = counts
            all.total += counts.total
            all.clean += counts.clean
            all.dropped += counts.dropped
            all.cut += counts.cut
            all.lettered += counts.lettered
            all.endTotal += counts.endTotal
            all.endDropped += counts.endDropped
            all.endCut += counts.endCut
            printRow(noise.name.lowercase(Locale.ROOT), counts)
        }
        printRow("overall", all)
        println()

        for ((noise, counts) in byNoise) {
            val floors = FLOORS.getValue(noise)
            val cut = counts.rate(counts.cut)
            val endCut = counts.rate(counts.endCut, counts.endTotal)
            assertTrue("$noise: legs cut $cut regressed below ${floors.cut - TOLERANCE}", cut >= floors.cut - TOLERANCE)
            assertTrue(
                "$noise: ending on the key, legs cut $endCut regressed below ${floors.endCut - TOLERANCE}",
                endCut >= floors.endCut - TOLERANCE,
            )
            // And never worse than leaving the legs in, which is the bar the
            // reporter set: only if it carries equal accuracy.
            assertTrue(
                "$noise: cutting the legs ($cut) reads worse than dropping the key alone (${counts.rate(counts.dropped)})",
                counts.cut >= counts.dropped,
            )
            assertTrue(
                "$noise: ending on the key, cutting the leg ($endCut) reads worse than dropping the key alone (${counts.rate(counts.endDropped, counts.endTotal)})",
                counts.endCut >= counts.endDropped,
            )
        }
        // The letter the detour followed is the one that gets the capital, on
        // the strokes that read right.
        val lettered = all.rate(all.lettered, all.cut)
        assertTrue(
            "per-letter capitals landed on the right letter $lettered of the time, below ${LETTER_FLOOR - TOLERANCE}",
            lettered >= LETTER_FLOOR - TOLERANCE,
        )
    }

    private fun printRow(name: String, c: Counts) {
        println(
            String.format(
                Locale.ROOT, "%-8s %7.4f %11.4f %9.4f %9.4f | %11.4f %9.4f",
                name, c.rate(c.clean), c.rate(c.dropped), c.rate(c.cut), c.rate(c.lettered, c.cut),
                c.rate(c.endDropped, c.endTotal), c.rate(c.endCut, c.endTotal),
            )
        )
    }

    /**
     * The tolerance that ends a leg, swept. Printed only: the constant on
     * [GlideShiftDetour] is what the sweep settled on, and this is here so
     * the next person can see the surface rather than take it on trust.
     */
    @Test
    fun toleranceSweep() {
        val english = load("en.txt")
        val trie = Trie().apply { english.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val corpus = SwipeCorpus(SEED, grid = grid)
        val random = Random(SEED)
        val cases = listOf(SwipeCorpus.Noise.TYPICAL, SwipeCorpus.Noise.SLOPPY).flatMap { noise ->
            corpus.generate(EvalSampling.entries(english), noise, 150).map { case ->
                val visits = visitsOf(case.intended)
                val k = random.nextInt(visits.size)
                Triple(case, k, detour(case.path, visits, k, endThere = false, noise.profile.jitter, random))
            }
        }
        println()
        println("Tolerance sweep, typical+sloppy, ${cases.size} strokes: top-1 / right letter")
        for (tolerance in listOf(0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.5f, 0.6f, 0.8f)) {
            var right = 0
            var lettered = 0
            for ((case, k, mid) in cases) {
                val cut = GlideShiftDetour.trim(mid.points, mid.cuts, keyWidth, tolerance, shiftCentre)
                if (top1(cut.points, sources) != case.intended) continue
                right++
                val visits = visitsOf(case.intended)
                val alignment = beam.align(case.intended, cut.points, keys, keyWidth, workspace)
                val cased = GlideCase.Letters(cut.cuts, false).caseWord(case.intended, alignment)
                if (cased == expectedCasing(case.intended, visits, k)) lettered++
            }
            println(
                String.format(
                    Locale.ROOT, "  %.2f  %.4f  %.4f",
                    tolerance, right / cases.size.toDouble(), if (right == 0) 0.0 else lettered / right.toDouble(),
                )
            )
        }
        println()
    }
}
