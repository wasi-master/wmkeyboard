package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Glide decoder: a best-first lattice over the dictionary trie itself.
 *
 * The decoder it replaces scored a flat word list — build each candidate's ideal
 * polyline, resample it, compare. That is linear in the dictionary on every
 * preview event, which caps how large a word list can ship and forces the whole
 * lexicon to be materialized as strings in the IME process. Walking the trie
 * instead shares work across every word with a common prefix, never materializes
 * a word that is not emitted, and takes its candidates from whatever tries the
 * caller hands over — bundled, imported, personal, any script.
 *
 * **The alignment.** The drawn stroke is resampled to
 * [GlideWorkspace.SAMPLE_POINTS] samples spaced equally *by arc length*, which
 * is what makes the whole model work: the drawn distance between sample `i` and
 * sample `j` is exactly `(j - i)` steps, no measuring required. A word explains
 * the stroke by placing each of its letters on a sample, in increasing order,
 * with the first letter on the first sample and the last on the last. Placing
 * letter `m` on sample `j` costs two things:
 *
 *  - **where** — `d²/2σ²` for the distance from sample `j` to letter `m`'s key;
 *  - **how far** — how badly the stroke's own travel since the previous letter,
 *    `(j - i)` steps, disagrees with the distance between the two keys.
 *
 * The second term is what stops a decoder from reading `ho` off a stroke drawn
 * for `hello`: both letters sit exactly where they should, but the finger
 * travelled three times as far as `h`-to-`o` asks for. It is also what makes a
 * detour expensive without any notion of curvature — a loop between two letters
 * shows up as arc length that the key distance cannot account for.
 *
 * Because a prefix's column depends only on the letters spelled so far, every
 * word sharing that prefix shares the work. A beam state is `(trie node,
 * column)` and advancing one letter is one bounded pass over the column.
 *
 * **Why the bound is admissible.** Both terms are non-negative and the DP takes
 * a minimum over predecessors, so no extension of a prefix can finish below that
 * prefix column's lowest value. Expanding in order of
 * `logWeight + ln(1 + maxSubtree(node)) - shapeWeight * minCol` therefore never
 * discards a word that would have finished in the top K — the same argument
 * `FuzzyBeamSearch` makes for typing, with the alignment floor standing in for
 * the edit cost.
 *
 * **One thing that was tried and did not work.** Weighting each sample by how
 * deliberate the finger looked there — letters sit at velocity minima and
 * curvature maxima, so those samples ought to be better evidence — was expected
 * to be the largest single gain available and measured as a loss at every gain
 * in both directions (top1 .9050 at zero, .8958 at +0.8, .8967 at -1.0). The
 * likely reason is worth knowing before anyone tries it again: corner cutting is
 * the dominant error in a real stroke, so the sample at a pivot is exactly the
 * one systematically displaced *inside* the corner and away from the key it
 * belongs to. Leaning on those samples leans on the error. The arc-length term
 * already carries what the timing would have said about where letters fall.
 *
 * Scores are in the same log space as the typing beam
 * (`logWeight + ln(1 + frequency) - cost`), so `FuzzyBeamSearch.WalkSource`
 * weights carry over unchanged and a caller can build one source list for both.
 */
class GlideBeam(private val tuning: Tuning = Tuning()) {

    /**
     * The decoder's weights, injectable so the harness can sweep them rather
     * than the author guessing. The defaults are what the sweep settled on; see
     * `GlideTuningSweepTest` for the surface they were picked off.
     */
    data class Tuning(
        /**
         * Sample scatter around the key the finger meant, in key widths. Wider
         * than the tap model's 0.5: a stroke passes *through* a key rather than
         * stopping on it.
         */
        val sigma: Float = 0.42f,
        /**
         * Ceiling on one sample's location cost. Without it a single sample
         * flung off by a dropped touch report outweighs the whole alignment.
         */
        val maxPointCost: Float = 12.0f,
        /**
         * Nats per key width of disagreement between how far the finger
         * travelled between two letters and how far apart their keys are. This
         * is the term that reads `hello` off a `hello` stroke rather than `ho`.
         */
        val gapWeight: Float = 2.0f,
        /** How far, in samples, a placement may sit from where the arc length
         * says it should before the search stops considering it. */
        val gapWindow: Float = 14f,
        /** Nats per unit of alignment cost — the dial that trades shape against
         * the language model. */
        val shapeWeight: Double = 2.5,
        /** Flat charge for a doubled letter, which the stroke's shape cannot show. */
        val repeatCost: Float = 0.1f,
        /**
         * Extra charge for a doubled letter the finger did *not* pause on.
         *
         * A stroke draws `good` and `god` identically — the o is one key, visited
         * once — so shape has nothing to say and frequency decides. Timing has
         * one thing to say: a finger writing a letter twice tends to hesitate
         * there. This charges the doubling only when that hesitation is absent,
         * which leaves the dwelled case exactly as cheap as it was.
         */
        val dwellPenalty: Float = 0.4f,
        /**
         * How much the whole stroke's *shape* counts, once its size and position
         * are taken out of it.
         *
         * The alignment scores where a stroke went in absolute terms, so it
         * punishes a user whose swipes are systematically small or offset — and
         * the noise sweep says that is the decoder's steepest axis by a distance
         * (top-1 .964 to .508 as strokes shrink, against .940 to .820 for the
         * corner cutting everyone worries about). This term asks a different
         * question of the top few candidates: never mind where it was drawn, was
         * it drawn in that shape? Zero switches it off.
         */
        val shapeChannel: Double = 45.0,
        /** How far the stroke's first/last sample may sit from the word's
         * first/last key, in key widths. */
        val anchorRadius: Float = 1.6f,
        /** How close the stroke must pass to a key for that key's subtree to be
         * worth walking at all, in key widths. */
        val nearRadius: Float = 1.5f,
        /**
         * How many of a dictionary's commonest words a stroke may decode to,
         * or 0 for all of them.
         *
         * A swipe is a much weaker signal than a typed word: it says which keys
         * the finger went near and in what order, and any number of words fit
         * that loosely. The score is `ln(1 + frequency)` against
         * [shapeWeight] × alignment cost, and the logarithm flattens the
         * language model hard — a word a thousand times rarer trails by 6.9
         * nats, which a shape advantage of 2.8 erases. On the 17k list that
         * shipped with the app there is no tail to lose to. On a 1.6M-word
         * corpus there are hundreds of thousands of words nobody writes, each
         * needing only a slightly better-fitting shape to beat the word that
         * was meant, and users read that as the decoder getting worse the more
         * words they give it (issue #28).
         *
         * The cap answers it on the axis the problem is actually on. It bounds
         * the *vocabulary a stroke chooses from* without touching the
         * dictionary: completion, autocorrect and the suggestion strip go on
         * seeing every word, because a typed prefix is evidence enough to pick
         * a rare word out of a million and a swipe is not. On that 1.6M-word
         * list it is worth 8 points of top-1 accuracy at 300k and 14 at 50k,
         * and makes the decode up to three times faster; the measurement is
         * written out on `GlideVocabulary` in :core:settings, which is where
         * the setting picks a value.
         *
         * 0 here, so the decoder on its own is uncapped and the eval harness
         * measures what it always measured. The shipped default lives in the
         * setting.
         *
         * Applied per source, and only where the source has a frequency
         * ranking to apply it to — the personal lexicon and the curated
         * romanized spellings return 0 from
         * [com.wasimaster.wmkeyboard.core.prediction.TrieWalker.frequencyAtRank]
         * and are never capped, so a word the user taught the keyboard stays
         * glidable however rare it is.
         */
        val vocabularyRank: Int = 0,
    ) {
        val invTwoSigmaSq: Float get() = 1f / (2f * sigma * sigma)
        val anchorRadiusSq: Float get() = anchorRadius * anchorRadius
        val nearCost: Float get() = nearRadius * nearRadius * invTwoSigmaSq
    }

    class Candidate(
        val word: String,
        val score: Double,
        /** Total alignment cost: how badly the stroke had to be explained to
         * read this word off it. A confidence signal, low is good. */
        val shapeCost: Double,
        val tier: FuzzyBeamSearch.Tier,
    )

    /**
     * Decodes [path] (pixel coordinates, keys [keyWidth] wide) against [sources],
     * returning up to [limit] words best first. Empty when the stroke is too
     * short to be a gesture, or when nothing in the tries explains it.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: GlideWorkspace,
        limit: Int = 4,
    ): List<Candidate> {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return emptyList()
        if (keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        if (!resample(path, keyWidth, ws)) return emptyList()
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildDwell(keys, ws)
        if (ws.arcStep <= 0f) return emptyList()

        val k = maxOf(limit * 2, RESULT_K)
        val results = HashMap<String, Candidate>()
        var floor = Double.NEGATIVE_INFINITY

        // Heaviest source first, so its emissions raise the floor before the
        // lighter ones start — the same ordering trick the typing beam uses.
        val ordered = sources.sortedByDescending {
            it.logWeight + ln1p(it.walker.maxSubtree(it.walker.root))
        }
        for (src in ordered) {
            val rootBound = src.logWeight + ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(src, keys, ws, k, results, floor)
        }

        val ranked = results.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
        return rescoreShape(ranked, keys, ws).take(limit)
    }

    // ---- the walk ----

    // The vocabulary cap adds a subtree prune and an emit gate, which takes the
    // cognitive-complexity count one past the threshold. Splitting the loop to
    // get it back would cost more than it buys: this is one best-first walk over
    // a shared column, and every branch in it is on the hot path.
    @Suppress(
        "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth",
        "CognitiveComplexMethod",
    )
    private fun searchOne(
        src: FuzzyBeamSearch.WalkSource,
        keys: GlideKeyMap,
        ws: GlideWorkspace,
        k: Int,
        results: HashMap<String, Candidate>,
        floorIn: Double,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        // The vocabulary cap as this source's own frequency. A source with no
        // frequency ranking answers 0, which switches every test below off.
        val minFrequency = walker.frequencyAtRank(tuning.vocabularyRank)

        ws.reset()
        ws.push(
            node = walker.root, parent = -1, viaLabel = GlideWorkspace.NO_LABEL,
            lastKey = -1, length = 0, extra = 0f, floorCost = 0f,
            bound = src.logWeight + ln1p(walker.maxSubtree(walker.root)),
        )

        var pops = 0
        while (ws.heapSize > 0 && pops < MAX_POPS) {
            val s = ws.popBest()
            if (ws.bound[s] < floor - EPS) break
            pops++

            val node = ws.node[s]
            val length = ws.length[s].toInt()
            val extra = ws.extra[s]
            val lastKey = ws.lastKey[s]

            if (length >= MIN_WORD_LENGTH && walker.isWord(node) && ws.endKey[lastKey]) {
                // The last letter must land on the last sample: that is where
                // the finger lifted, and it is the end anchor the old decoder
                // had to apply as a separate filter.
                val total = ws.cols[ws.columnOf(s) + n - 1]
                if (total < GlideWorkspace.UNREACHABLE &&
                    walker.frequency(node) >= minFrequency
                ) {
                    val shape = total + extra
                    val score = src.logWeight + ln1p(walker.frequency(node)) -
                        tuning.shapeWeight * shape
                    if (score > floor - EPS || results.size < k) {
                        emit(ws.materialize(s), score, shape.toDouble(), src.tier, results)
                        if (results.size >= k) floor = kthBest(results, k)
                    }
                }
            }
            if (length >= MAX_WORD_LENGTH || length >= n) continue

            val count = walker.childrenInto(node, ws.children)
            for (i in 0 until count) {
                val label = ws.children.labels[i]
                val key = keys.keyIndex(label)
                // A character the grid cannot produce makes its whole subtree
                // unreachable; so does one the stroke never went near.
                if (key < 0 || key >= keyCount || !ws.nearKey[key]) continue
                if (length == 0 && !ws.startKey[key]) continue

                val child = ws.children.nodes[i]
                val subtree = walker.maxSubtree(child)
                // Every word under here is at most `subtree` frequent, so a
                // subtree below the cap holds nothing the emit gate would keep.
                // Pruning it makes a capped decode cheaper than an uncapped
                // one rather than merely quieter.
                if (subtree < minFrequency) continue
                val repeat = length > 0 && label == ws.viaLabel[s]

                val minCol: Float
                var childExtra = extra
                if (repeat) {
                    // A doubled letter is one key visited once, so the stroke
                    // holds no evidence of it at all: the alignment carries
                    // over untouched and the repeat pays a flat charge instead.
                    // Without the charge every doubled spelling would shadow
                    // its single one for free.
                    minCol = ws.floorCost[s]
                    childExtra += tuning.repeatCost +
                        tuning.dwellPenalty * (1f - ws.keyDwell[lastKey])
                } else {
                    minCol = advance(
                        ws,
                        prevColumn = if (length == 0) -1 else ws.columnOf(s),
                        key = key,
                        prevKey = lastKey,
                        keys = keys,
                    )
                    if (minCol >= GlideWorkspace.UNREACHABLE) continue
                }

                val bound = src.logWeight + ln1p(subtree) -
                    tuning.shapeWeight * (minCol + childExtra)
                if (bound < floor - EPS) continue

                val id = ws.push(
                    node = child, parent = s, viaLabel = label, lastKey = key,
                    length = length + 1, extra = childExtra, floorCost = minCol, bound = bound,
                )
                if (id < 0) break // pool saturated; best-first says the rest are worse
                if (repeat) ws.copyColumn(s, id) else ws.storeScratch(id)
            }
        }
        return floor
    }

    /**
     * Extends the alignment by one letter sitting on [key], writing the new
     * column into [GlideWorkspace.scratch] and returning its lowest value.
     *
     * [prevColumn] is the parent's column offset, or -1 for the word's first
     * letter, which is anchored to the stroke's first sample: that is where the
     * finger went down, so there is nothing to search over.
     *
     * For every later letter the DP asks, for each sample `j`, which earlier
     * sample `i` the previous letter sat on. Only a band of `i` is worth
     * checking. The stroke's travel between the two samples is `(j - i)` steps
     * of known length, the two keys are a known distance apart, and the two
     * should agree; [GAP_WINDOW] bounds how far they may disagree before the
     * placement is not worth scoring at all. That band is what keeps this a
     * bounded pass rather than the quadratic scan the exact formulation asks
     * for, and it costs nothing real — a placement outside it carries a gap
     * penalty no word recovers from.
     */
    private fun advance(
        ws: GlideWorkspace,
        prevColumn: Int,
        key: Int,
        prevKey: Int,
        keys: GlideKeyMap,
    ): Float {
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val out = ws.scratch

        if (prevColumn < 0) {
            out[0] = cost[key]
            for (j in 1 until n) out[j] = GlideWorkspace.UNREACHABLE
            return out[0]
        }

        val prev = ws.cols
        val step = ws.arcStep
        val span = keys.distance(prevKey, key)
        // Samples the stroke should have travelled between the two letters.
        val expected = span / step
        val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
        val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1)

        out[0] = GlideWorkspace.UNREACHABLE
        var best = GlideWorkspace.UNREACHABLE
        for (j in 1 until n) {
            val from = maxOf(0, j - hi)
            val to = j - lo
            var bestPrev = GlideWorkspace.UNREACHABLE
            var i = from
            while (i <= to) {
                val at = prev[prevColumn + i]
                if (at < GlideWorkspace.UNREACHABLE) {
                    val travelled = (j - i) * step
                    val gap = travelled - span
                    val v = at + tuning.gapWeight * (if (gap < 0f) -gap else gap)
                    if (v < bestPrev) bestPrev = v
                }
                i++
            }
            val value = if (bestPrev >= GlideWorkspace.UNREACHABLE) {
                GlideWorkspace.UNREACHABLE
            } else {
                bestPrev + cost[j * keyCount + key]
            }
            out[j] = value
            if (value < best) best = value
        }
        return best
    }

    // ---- per-decode preparation ----

    /**
     * Resamples [path] to [GlideWorkspace.SAMPLE_POINTS] equidistant samples in
     * key-width coordinates, interpolating the clock along with the position so
     * timing survives. False when the stroke is too short to be a swipe at all.
     */
    private fun resample(path: List<GesturePoint>, keyWidth: Float, ws: GlideWorkspace): Boolean {
        val n = GlideWorkspace.SAMPLE_POINTS
        var totalPx = 0f
        for (i in 1 until path.size) {
            totalPx += distance(path[i - 1], path[i])
        }
        if (totalPx / keyWidth < MIN_STROKE_LENGTH) return false

        val step = totalPx / (n - 1)
        ws.arcStep = step / keyWidth
        val first = path.first()
        ws.pathX[0] = first.x / keyWidth
        ws.pathY[0] = first.y / keyWidth
        ws.pathT[0] = first.t

        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var ct = first.t
        var accumulated = 0f
        while (out < n - 1 && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= step && segment > 0f) {
                val fraction = (step - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                ct += ((next.t - ct) * fraction).toLong()
                ws.pathX[out] = cx / keyWidth
                ws.pathY[out] = cy / keyWidth
                ws.pathT[out] = ct
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = next.x
                cy = next.y
                ct = next.t
                index++
            }
        }
        val last = path.last()
        while (out < n) {
            ws.pathX[out] = last.x / keyWidth
            ws.pathY[out] = last.y / keyWidth
            ws.pathT[out] = last.t
            out++
        }
        return true
    }

    /**
     * Fills the per-sample, per-key cost table and the three key masks the walk
     * prunes with: keys the stroke passed near at all, keys it could have
     * started on, and keys it could have ended on.
     */
    private fun buildCosts(keys: GlideKeyMap, ws: GlideWorkspace) {
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        val nearCost = tuning.nearCost
        for (i in 0 until n) {
            val base = i * keyCount
            val px = ws.pathX[i]
            val py = ws.pathY[i]
            for (k in 0 until keyCount) {
                val dx = px - keys.keyX[k]
                val dy = py - keys.keyY[k]
                val scaled = (dx * dx + dy * dy) * invTwoSigmaSq
                cost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
                if (scaled <= nearCost) ws.nearKey[k] = true
            }
        }
        anchorMask(ws.pathX[0], ws.pathY[0], keys, ws.startKey)
        anchorMask(ws.pathX[n - 1], ws.pathY[n - 1], keys, ws.endKey)
    }

    /**
     * Scores how long the finger lingered on each key.
     *
     * Because the path is resampled by arc length, a sample's timestamp gap is
     * the reciprocal of speed with no differentiating required: a step that took
     * much longer than the stroke's even pace is a step the finger was barely
     * moving through. A key's score is the slowest moment anywhere near it, so a
     * pause counts wherever in the key it happened.
     *
     * Zero throughout for a stroke with no clock, which is every synthetic path
     * in a test that does not care about timing — and zero is the right answer
     * there, since no-evidence and no-pause should charge the same.
     */
    private fun buildDwell(keys: GlideKeyMap, ws: GlideWorkspace) {
        if (tuning.dwellPenalty <= 0f) return
        val n = GlideWorkspace.SAMPLE_POINTS
        val span = (ws.pathT[n - 1] - ws.pathT[0]).toFloat()
        if (span <= 0f) return
        val evenStep = span / (n - 1)
        for (j in 1 until n) {
            val step = (ws.pathT[j] - ws.pathT[j - 1]).toFloat()
            val lingering = ((step / evenStep) - 1f) / DWELL_FULL
            if (lingering <= 0f) continue
            val score = if (lingering > 1f) 1f else lingering
            for (k in 0 until keys.keyCount) {
                val dx = ws.pathX[j] - keys.keyX[k]
                val dy = ws.pathY[j] - keys.keyY[k]
                if (dx * dx + dy * dy > DWELL_RADIUS_SQ) continue
                if (score > ws.keyDwell[k]) ws.keyDwell[k] = score
            }
        }
    }

    // ---- shape channel ----

    /**
     * Reorders the finished candidates by how well each one's *shape* matches
     * the stroke, with position and size normalised away.
     *
     * A rescore over the handful of words the search already emitted, rather
     * than a term inside it: the normalisation needs a whole candidate to
     * compute, and a per-state version would have no admissible bound and would
     * cost a resample per trie edge. Here it costs a resample per candidate.
     */
    private fun rescoreShape(
        ranked: List<Candidate>,
        keys: GlideKeyMap,
        ws: GlideWorkspace,
    ): List<Candidate> {
        val weight = tuning.shapeChannel
        if (weight <= 0.0 || ranked.size < 2) return ranked
        normalise(ws.pathX, ws.pathY, ws.drawnShapeX, ws.drawnShapeY)

        val rescored = ArrayList<Candidate>(ranked.size)
        for (candidate in ranked) {
            val distance = shapeDistance(candidate.word, keys, ws)
            rescored.add(
                if (distance == null) {
                    candidate
                } else {
                    Candidate(
                        candidate.word,
                        candidate.score - weight * distance,
                        candidate.shapeCost,
                        candidate.tier,
                    )
                }
            )
        }
        return rescored.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
    }

    /**
     * Mean distance between the normalised stroke and [word]'s normalised ideal
     * path, or null when the word cannot be drawn on this grid at all.
     */
    private fun shapeDistance(word: String, keys: GlideKeyMap, ws: GlideWorkspace): Double? {
        var count = 0
        var previous = -1
        for (ch in word) {
            val key = keys.keyIndex(ch)
            if (key < 0) return null
            // Consecutive letters on one key are one point of the path: the
            // finger visited it once, however many letters it stood for.
            if (key == previous) continue
            if (count >= GlideWorkspace.MAX_IDEAL_POINTS) return null
            ws.idealX[count] = keys.keyX[key]
            ws.idealY[count] = keys.keyY[key]
            count++
            previous = key
        }
        if (count < 2) return null
        resamplePolyline(ws.idealX, ws.idealY, count, ws.idealShapeX, ws.idealShapeY)
        normalise(ws.idealShapeX, ws.idealShapeY, ws.idealShapeX, ws.idealShapeY)

        var sum = 0.0
        for (j in 0 until GlideWorkspace.SAMPLE_POINTS) {
            val dx = ws.drawnShapeX[j] - ws.idealShapeX[j]
            val dy = ws.drawnShapeY[j] - ws.idealShapeY[j]
            sum += sqrt(dx * dx + dy * dy).toDouble()
        }
        return sum / GlideWorkspace.SAMPLE_POINTS
    }

    /**
     * Centres a path on its own centroid and scales it to unit spread, so that
     * comparing two paths compares their shapes and nothing else. In and out may
     * be the same arrays.
     */
    private fun normalise(xs: FloatArray, ys: FloatArray, outX: FloatArray, outY: FloatArray) {
        val n = GlideWorkspace.SAMPLE_POINTS
        var cx = 0f
        var cy = 0f
        for (j in 0 until n) {
            cx += xs[j]
            cy += ys[j]
        }
        cx /= n
        cy /= n
        var spread = 0f
        for (j in 0 until n) {
            val dx = xs[j] - cx
            val dy = ys[j] - cy
            spread += dx * dx + dy * dy
        }
        val scale = sqrt(spread / n).takeIf { it > MIN_SHAPE_SPREAD } ?: 1f
        for (j in 0 until n) {
            outX[j] = (xs[j] - cx) / scale
            outY[j] = (ys[j] - cy) / scale
        }
    }

    /** Resamples a [count]-point polyline to [GlideWorkspace.SAMPLE_POINTS] by arc length. */
    private fun resamplePolyline(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        outX: FloatArray,
        outY: FloatArray,
    ) {
        val n = GlideWorkspace.SAMPLE_POINTS
        var total = 0f
        for (i in 1 until count) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            total += sqrt(dx * dx + dy * dy)
        }
        if (total <= 0f) {
            for (j in 0 until n) {
                outX[j] = xs[0]
                outY[j] = ys[0]
            }
            return
        }
        val step = total / (n - 1)
        outX[0] = xs[0]
        outY[0] = ys[0]
        var out = 1
        var index = 0
        var cx = xs[0]
        var cy = ys[0]
        var accumulated = 0f
        while (out < n - 1 && index < count - 1) {
            val nx = xs[index + 1]
            val ny = ys[index + 1]
            val segment = sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy))
            if (accumulated + segment >= step && segment > 0f) {
                val fraction = (step - accumulated) / segment
                cx += fraction * (nx - cx)
                cy += fraction * (ny - cy)
                outX[out] = cx
                outY[out] = cy
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = nx
                cy = ny
                index++
            }
        }
        while (out < n) {
            outX[out] = xs[count - 1]
            outY[out] = ys[count - 1]
            out++
        }
    }

    private fun anchorMask(x: Float, y: Float, keys: GlideKeyMap, out: BooleanArray) {
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            out[k] = dx * dx + dy * dy <= tuning.anchorRadiusSq
        }
    }

    // ---- results ----

    private fun emit(
        word: String,
        score: Double,
        shapeCost: Double,
        tier: FuzzyBeamSearch.Tier,
        results: HashMap<String, Candidate>,
    ) {
        val existing = results[word]
        if (existing == null || score > existing.score) {
            results[word] = Candidate(word, score, shapeCost, tier)
        }
    }

    private fun kthBest(results: HashMap<String, Candidate>, k: Int): Double {
        if (results.size < k) return Double.NEGATIVE_INFINITY
        val scores = DoubleArray(results.size)
        var i = 0
        for (c in results.values) scores[i++] = c.score
        scores.sort()
        return scores[scores.size - k]
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {

        /** Strokes shorter than this are taps, in key widths. */
        private const val MIN_STROKE_LENGTH = 0.5f

        private const val MIN_SAMPLES = 3
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = 24

        /** Below this spread a path is a dot, and dividing by its size is noise. */
        private const val MIN_SHAPE_SPREAD = 1e-4f

        /** How many times the even pace a step must take to count as a full stop. */
        private const val DWELL_FULL = 3.0f

        /** How near a key a pause has to happen to count as a pause on it. */
        private const val DWELL_RADIUS_SQ = 0.8f * 0.8f

        /** Runaway backstop; floor pruning ends healthy walks far earlier. */
        const val MAX_POPS = 2000

        /** Rank depth kept internally, so reranking has something to reorder. */
        const val RESULT_K = 8

        private const val EPS = 1e-9

        private fun ln1p(v: Int): Double = ln(1.0 + v)
    }
}
