package com.wasimaster.wmkeyboard.core.gesture.reference

import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideJoiners
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideToken
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import kotlin.math.ceil
import kotlin.math.sqrt
// fdlibm's log, as `wm-engine`'s `strict_math::log` computes it, so the Rust
// decoder and this one score alike on every runtime.
private fun ln(x: Double): Double = StrictMath.log(x)

private typealias Duet = DuetBeam.Duet

/**
 * The Kotlin DuetBeam as it stood when its decode moved to Rust
 * (native/RUST_NEON_PLAN.md step 4), kept as the reference the parity test
 * holds the Rust decoder to and the white-box tests read the workspace of.
 */
class KotlinDuetBeam(
    private val tuning: GlideBeam.Tuning = GlideBeam.Tuning(),
    private val duet: Duet = Duet(),
) {

    /**
     * Decodes [tokens] (pixel coordinates, keys [keyWidth] wide) into up to
     * [limit] words, best first. Empty when there is nothing to decode, more
     * than [KotlinDuetWorkspace.MAX_TOKENS] tokens, or no word explains them.
     *
     * Tokens with no clock (every time 0) are taken in list order.
     *
     * [partial] reads the gestures as the *start* of a word instead: every
     * result is a spelling some dictionary word begins with, scored by the
     * commonest word under it. That is what a word still being built shows
     * while its gestures so far finish no word (issue #640) — `s` tapped and
     * `o→m` swiped are "som", on the way to "something".
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        tokens: List<GlideToken>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: KotlinDuetWorkspace,
        limit: Int = 4,
        partial: Boolean = false,
    ): List<GlideBeam.Candidate> {
        if (tokens.isEmpty() || tokens.size > KotlinDuetWorkspace.MAX_TOKENS) return emptyList()
        if (keyWidth <= 0f || keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        prepare(tokens, keys, keyWidth, ws)

        val k = maxOf(limit * 2, GlideBeam.RESULT_K)
        val results = HashMap<String, GlideBeam.Candidate>()
        var floor = Double.NEGATIVE_INFINITY
        val ordered = sources.sortedByDescending {
            it.rankWeight + tuning.freqWeight * it.scale.ln1p(it.walker.maxSubtree(it.walker.root))
        }
        for (src in ordered) {
            val rootBound = src.rankWeight + tuning.freqWeight * src.scale.ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(src, keys, ws, k, results, floor, partial)
        }
        return results.values
            .sortedWith(compareByDescending<GlideBeam.Candidate> { it.score }.thenBy { it.word })
            .take(limit)
    }

    // ---- preparation ----

    private fun prepare(tokens: List<GlideToken>, keys: GlideKeyMap, keyWidth: Float, ws: KotlinDuetWorkspace) {
        val count = tokens.size
        ws.tokenCount = count
        ws.prepareKeys(keys.keyCount)
        // Without a clock nothing can be ordered by time, so the list order is
        // the order: a token apiece, none overlapping.
        val clockless = tokens.all { it.tStart == 0L && it.tEnd == 0L }
        // Sorted by start, which is how the user sequenced them; ties keep
        // their list order.
        val sorted = if (clockless) tokens else tokens.sortedBy { it.tStart }
        for (i in 0 until count) {
            val token = sorted[i]
            val swipe = token as? GlideToken.Swipe
            val prepared = swipe != null && prepareSwipe(i, swipe.points, keys, keyWidth, ws)
            if (!prepared) {
                val (x, y) = when (token) {
                    is GlideToken.Tap -> token.x to token.y
                    is GlideToken.Swipe -> token.points.first().x to token.points.first().y
                }
                prepareTap(i, x / keyWidth, y / keyWidth, keys, ws)
            }
            if (clockless) {
                ws.tStart[i] = i * CLOCKLESS_SPACING
                ws.tEnd[i] = i * CLOCKLESS_SPACING + CLOCKLESS_SPACING / 2
            } else {
                ws.tStart[i] = token.tStart
                ws.tEnd[i] = if (ws.isSwipe[i]) token.tEnd else token.tStart
            }
        }
        if (clockless) {
            // Spread each swipe's samples over its synthetic span so a tap's
            // place inside it is computable, even though none will land there.
            for (i in 0 until count) {
                if (!ws.isSwipe[i]) continue
                val n = ws.samples[i]
                val span = ws.tEnd[i] - ws.tStart[i]
                for (j in 0 until n) {
                    ws.sampleT[i * MS + j] = ws.tStart[i] + span * j / maxOf(1, n - 1)
                }
            }
        }
        val slack = duet.orderSlackMs
        for (i in 0 until count) {
            var mustDone = 0
            var mustBegun = 0
            for (j in 0 until count) {
                if (j == i) continue
                if (ws.tEnd[j] + slack < ws.tStart[i]) mustDone = mustDone or (1 shl j)
                if (ws.tStart[j] + slack < ws.tStart[i]) mustBegun = mustBegun or (1 shl j)
            }
            ws.mustBeDone[i] = mustDone
            ws.mustBeBegun[i] = mustBegun
        }
        for (t in 0 until count) {
            if (ws.isSwipe[t]) continue
            val at = ws.tStart[t]
            for (s in 0 until count) {
                if (!ws.isSwipe[s]) continue
                val n = ws.samples[s]
                ws.tapHi[t * MT + s] = lastSampleAtOrBefore(ws, s, n, at + duet.tapWindowMs)
                ws.tapLo[t * MT + s] = firstSampleAtOrAfter(ws, s, n, at - duet.tapWindowMs)
            }
        }
    }

    private fun lastSampleAtOrBefore(ws: KotlinDuetWorkspace, s: Int, n: Int, time: Long): Int {
        var j = n - 1
        while (j >= 0 && ws.sampleT[s * MS + j] > time) j--
        return j
    }

    private fun firstSampleAtOrAfter(ws: KotlinDuetWorkspace, s: Int, n: Int, time: Long): Int {
        var j = 0
        while (j < n && ws.sampleT[s * MS + j] < time) j++
        return j
    }

    private fun prepareTap(i: Int, x: Float, y: Float, keys: GlideKeyMap, ws: KotlinDuetWorkspace) {
        ws.isSwipe[i] = false
        ws.samples[i] = 1
        ws.arcStep[i] = 0f
        ws.sampleX[i * MS] = x
        ws.sampleY[i * MS] = y
        val kc = keys.keyCount
        val base = i * MS * kc
        val nearSq = duet.tapNearRadius * duet.tapNearRadius
        // In the alignment's units, so one `settled` sum carries both kinds of
        // evidence: nats divided by the weight the walk multiplies back.
        val toUnits = (1.0 / tuning.shapeWeight).toFloat()
        for (k in 0 until kc) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            val d = dx * dx + dy * dy
            val nats = minOf(d * (1f / (2f * duet.tapSigma * duet.tapSigma)), duet.tapMaxCost)
            ws.cost[base + k] = nats * toUnits
            ws.near[i * kc + k] = d <= nearSq
        }
    }

    /** Resamples and costs swipe [i]; false when it is too short to be a swipe at all. */
    private fun prepareSwipe(
        i: Int,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: KotlinDuetWorkspace,
    ): Boolean {
        if (path.size < 2) return false
        var totalPx = 0f
        for (p in 1 until path.size) totalPx += distance(path[p - 1], path[p])
        val totalKw = totalPx / keyWidth
        if (totalKw < MIN_STROKE_LENGTH) return false
        val n = ceil(totalKw * duet.samplesPerKey).toInt().plus(1)
            .coerceIn(duet.minSamples, MS)
        ws.isSwipe[i] = true
        ws.samples[i] = n
        ws.arcStep[i] = totalKw / (n - 1)
        resample(path, keyWidth, totalPx / (n - 1), n, i * MS, ws)

        val kc = keys.keyCount
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        val nearCost = tuning.nearCost
        for (j in 0 until n) {
            val base = (i * MS + j) * kc
            val px = ws.sampleX[i * MS + j]
            val py = ws.sampleY[i * MS + j]
            for (k in 0 until kc) {
                val dx = px - keys.keyX[k]
                val dy = py - keys.keyY[k]
                val scaled = (dx * dx + dy * dy) * invTwoSigmaSq
                ws.cost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
                if (scaled <= nearCost) ws.near[i * kc + k] = true
            }
        }
        anchor(ws.sampleX[i * MS], ws.sampleY[i * MS], keys, ws.startKey, i * kc, tuning.startRadiusSq)
        anchor(
            ws.sampleX[i * MS + n - 1], ws.sampleY[i * MS + n - 1], keys, ws.endKey, i * kc,
            tuning.endRadiusSq,
        )
        anchorSlack(i, 0, tuning.startSlack, ws.startKey, keys, ws)
        anchorSlack(i, n - 1, tuning.endSlack, ws.endKey, keys, ws)
        findLoops(i, path, keys, keyWidth, ws)
        return true
    }

    /**
     * Files swipe [i]'s doubled-letter loops: [GlideBeam]'s search over this
     * swipe alone, each loop credited to the one key nearest its centre
     * within [GlideBeam.Tuning.loopRadius]. Wiggles are searched for too, so
     * a swipe splits into the same windows a stroke would, and then dropped:
     * a wiggle marks a pause, which says a letter is in the word and never
     * that it is in it twice.
     */
    @Suppress("LongParameterList")
    private fun findLoops(
        i: Int,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: KotlinDuetWorkspace,
    ) {
        val scan = ws.loopScan
        val found = scan.find(path, keyWidth, tuning)
        for (w in 0 until found) {
            if (!scan.windowIsLoop[w]) continue
            scan.measure(scan.windowFrom[w], scan.windowTo[w])
            val own = scan.nearestKey(scan.centreX, scan.centreY, keys, tuning.loopRadiusSq)
            if (own >= 0) ws.addLoop(i, own)
        }
    }

    /** [GlideBeam]'s arc-length resample, to [n] samples written from [offset]. */
    @Suppress("LongParameterList")
    private fun resample(
        path: List<GesturePoint>,
        keyWidth: Float,
        stepPx: Float,
        n: Int,
        offset: Int,
        ws: KotlinDuetWorkspace,
    ) {
        val first = path.first()
        ws.sampleX[offset] = first.x / keyWidth
        ws.sampleY[offset] = first.y / keyWidth
        ws.sampleT[offset] = first.t
        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var ct = first.t
        var accumulated = 0f
        while (out < n - 1 && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= stepPx && segment > 0f) {
                val fraction = (stepPx - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                ct += ((next.t - ct) * fraction).toLong()
                ws.sampleX[offset + out] = cx / keyWidth
                ws.sampleY[offset + out] = cy / keyWidth
                ws.sampleT[offset + out] = ct
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
            ws.sampleX[offset + out] = last.x / keyWidth
            ws.sampleY[offset + out] = last.y / keyWidth
            ws.sampleT[offset + out] = last.t
            out++
        }
    }

    /**
     * [GlideBeam]'s anchor slack on swipe [i]'s sample [j]: the location cost
     * there on the distance past [slack], and every key [mask] admits walkable
     * whether or not the swipe passes near it (issue #805). A no-op at zero.
     */
    @Suppress("LongParameterList")
    private fun anchorSlack(i: Int, j: Int, slack: Float, mask: BooleanArray, keys: GlideKeyMap, ws: KotlinDuetWorkspace) {
        if (slack <= 0f) return
        val kc = keys.keyCount
        val base = (i * MS + j) * kc
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        for (k in 0 until kc) {
            val dx = ws.sampleX[i * MS + j] - keys.keyX[k]
            val dy = ws.sampleY[i * MS + j] - keys.keyY[k]
            val past = sqrt(dx * dx + dy * dy) - slack
            val scaled = if (past > 0f) past * past * invTwoSigmaSq else 0f
            ws.cost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
            if (mask[i * kc + k]) ws.near[i * kc + k] = true
        }
    }

    /** [GlideBeam]'s anchor lead: how much travel between swipe [tok]'s sample [j] and [key] the gap term forgives. */
    @Suppress("LongParameterList")
    private fun anchorLead(tok: Int, j: Int, key: Int, slack: Float, keys: GlideKeyMap, ws: KotlinDuetWorkspace): Float {
        if (slack <= 0f || key < 0) return 0f
        val dx = ws.sampleX[tok * MS + j] - keys.keyX[key]
        val dy = ws.sampleY[tok * MS + j] - keys.keyY[key]
        val d = sqrt(dx * dx + dy * dy)
        return if (d < slack) d else slack
    }

    @Suppress("LongParameterList")
    private fun anchor(
        x: Float,
        y: Float,
        keys: GlideKeyMap,
        out: BooleanArray,
        offset: Int,
        radiusSq: Float,
    ) {
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            out[offset + k] = dx * dx + dy * dy <= radiusSq
        }
    }

    // ---- the walk ----

    @Suppress(
        "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth",
        "CognitiveComplexMethod", "LongMethod",
    )
    private fun searchOne(
        src: FuzzyBeamSearch.WalkSource,
        keys: GlideKeyMap,
        ws: KotlinDuetWorkspace,
        k: Int,
        results: HashMap<String, GlideBeam.Candidate>,
        floorIn: Double,
        partial: Boolean,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val kc = keys.keyCount
        val count = ws.tokenCount
        val allDone = if (count >= Int.SIZE_BITS) -1 else (1 shl count) - 1
        val minFrequency = walker.frequencyAtRank(tuning.vocabularyRank)
        val weight = tuning.shapeWeight
        val eligible = IntArray(count)

        ws.reset()
        for (slot in 0 until SLOTS) {
            ws.scratchTok[slot] = -1
            ws.scratchKey[slot] = -1
            ws.scratchMin[slot] = 0
            ws.scratchFloor[slot] = 0f
        }
        ws.push(
            node = walker.root, parent = -1, viaLabel = KotlinGlideWorkspace.NO_LABEL, letterCp = 0,
            lastTok = -1, lastKey = -1, length = 0, done = 0, settled = 0f,
            bound = src.rankWeight + tuning.freqWeight * src.scale.ln1p(walker.maxSubtree(walker.root)),
        )

        var pops = 0
        while (ws.heapSize > 0 && pops < GlideBeam.MAX_POPS) {
            val s = ws.popBest()
            if (ws.bound[s] < floor - EPS) break
            pops++

            val node = ws.node[s]
            val length = ws.length[s].toInt()
            val done = ws.done[s]
            val settled = ws.settled[s]
            var openMask = 0
            var freeSlot = -1
            for (slot in 0 until SLOTS) {
                val tok = ws.slotTok[s * SLOTS + slot]
                if (tok >= 0) openMask = openMask or (1 shl tok) else if (freeSlot < 0) freeSlot = slot
            }

            // A prefix counts by the commonest word under it; a word by its own
            // frequency. Either way only once every gesture has given its letters.
            val emits = openMask == 0 && done == allDone && if (partial) {
                length >= 1 && walker.maxSubtree(node) >= maxOf(1, minFrequency)
            } else {
                length >= MIN_WORD_LENGTH && walker.isWord(node) && walker.frequency(node) >= minFrequency
            }
            if (emits) {
                val frequency = if (partial) walker.maxSubtree(node) else walker.frequency(node)
                // Charged here and not on the way down: only a whole word
                // knows which loops none of its doubled letters claim. It can
                // only lower a score, so the bound stays admissible.
                val cost = settled + unclaimedLoop(s, ws)
                val score = src.rankWeight + tuning.freqWeight * src.scale.ln1p(frequency) - weight * cost
                if (score > floor - EPS || results.size < k) {
                    val word = ws.materialize(s)
                    if (partial ||
                        !(src.tier == FuzzyBeamSearch.Tier.DICTIONARY && GlideJoiners.isNonWord(word))
                    ) {
                        emit(word, score, cost.toDouble(), src.tier, results)
                        if (results.size >= k) floor = kthBest(results, k, ws)
                    }
                }
            }

            // Closing an open swipe: its last letter is on its last sample,
            // under its end anchor. No trie step; the swipe's cost settles.
            for (slot in 0 until SLOTS) {
                val tok = ws.slotTok[s * SLOTS + slot]
                if (tok < 0) continue
                val end = ws.cols[ws.columnOf(s, slot) + ws.samples[tok] - 1]
                if (end >= UNREACHABLE) continue
                if (!ws.endKey[tok * kc + ws.slotKey[s * SLOTS + slot]]) continue
                ws.loadScratch(s)
                ws.scratchTok[slot] = -1
                ws.scratchKey[slot] = -1
                ws.scratchMin[slot] = 0
                ws.scratchFloor[slot] = 0f
                val childSettled = settled + end
                val bound = src.rankWeight + tuning.freqWeight * src.scale.ln1p(walker.maxSubtree(node)) -
                    weight * (childSettled + openFloors(ws))
                if (bound < floor - EPS) continue
                val id = ws.push(
                    node = node, parent = s, viaLabel = KotlinGlideWorkspace.NO_LABEL,
                    letterCp = ws.letterCp[s], lastTok = ws.lastTok[s], lastKey = ws.lastKey[s],
                    length = length, done = done or (1 shl tok), settled = childSettled, bound = bound,
                )
                if (id < 0) break
            }
            if (length >= MAX_WORD_LENGTH) continue

            // Which tokens may give the next letter, by the clock rules.
            var eligibleCount = 0
            val begun = done or openMask
            for (t in 0 until count) {
                val bit = 1 shl t
                if (begun and bit != 0) continue
                if (ws.mustBeDone[t] and done.inv() != 0) continue
                if (ws.mustBeBegun[t] and begun.inv() != 0) continue
                if (ws.isSwipe[t] && freeSlot < 0) continue
                eligible[eligibleCount++] = t
            }
            val lastTok = ws.lastTok[s]
            val tapRepeatable = lastTok >= 0 && !ws.isSwipe[lastTok]
            if (eligibleCount == 0 && openMask == 0 && !tapRepeatable) continue

            val childCount = walker.childrenInto(node, ws.children)
            val pendingHigh = ws.viaLabel[s].takeIf { it.isHighSurrogate() }
            for (c in 0 until childCount) {
                val label = ws.children.labels[c]
                val child = ws.children.nodes[c]
                val subtree = walker.maxSubtree(child)
                if (subtree < minFrequency) continue
                val head = src.rankWeight + tuning.freqWeight * src.scale.ln1p(subtree)

                val codePoint: Int
                if (pendingHigh != null) {
                    if (!label.isLowSurrogate()) continue
                    codePoint = Character.toCodePoint(pendingHigh, label)
                } else if (label.isHighSurrogate()) {
                    if (!silent(s, child, label, settled, head, floor, ws)) break
                    continue
                } else {
                    codePoint = label.code
                }

                val key = keys.keyIndex(codePoint)
                if (key < 0 && length > 0 && GlideJoiners.isJoiner(codePoint) &&
                    !GlideJoiners.isJoiner(ws.viaLabel[s].code)
                ) {
                    if (!silent(s, child, label, settled + tuning.joinerCost, head, floor, ws)) break
                    continue
                }
                if (key < 0 || key >= kc) continue

                val repeat = length > 0 && codePoint == ws.letterCp[s]
                if (repeat && lastTok >= 0) {
                    // A doubled letter off one gesture: a swipe crosses the key
                    // once whether the word has it once or twice, and one tap
                    // may stand for both. A loop the swipe drew on the key is
                    // the mark of the second one and waives the charge.
                    val charge = if (ws.isSwipe[lastTok]) {
                        if (openMask and (1 shl lastTok) == 0) Float.NaN
                        else (tuning.repeatCost + tuning.unloopedRepeat) * (1f - ws.keyLoop[lastTok * kc + key])
                    } else {
                        duet.tapRepeatCost * (1.0 / weight).toFloat()
                    }
                    if (!charge.isNaN()) {
                        ws.loadScratch(s)
                        val childSettled = settled + charge
                        val bound = head - weight * (childSettled + openFloors(ws))
                        if (bound >= floor - EPS) {
                            val id = ws.push(
                                node = child, parent = s, viaLabel = label, letterCp = codePoint,
                                lastTok = lastTok, lastKey = key, length = length + 1, done = done,
                                settled = childSettled, bound = bound,
                            )
                            if (id < 0) break
                        }
                    }
                }

                // A tap.
                for (e in 0 until eligibleCount) {
                    val t = eligible[e]
                    if (ws.isSwipe[t] || !ws.near[t * kc + key]) continue
                    ws.loadScratch(s)
                    if (!placeTapAmongOpen(t, ws)) continue
                    val childSettled = settled + ws.cost[t * MS * kc + key]
                    val bound = head - weight * (childSettled + openFloors(ws))
                    if (bound < floor - EPS) continue
                    val id = ws.push(
                        node = child, parent = s, viaLabel = label, letterCp = codePoint,
                        lastTok = t, lastKey = key, length = length + 1, done = done or (1 shl t),
                        settled = childSettled, bound = bound,
                    )
                    if (id < 0) break
                }

                // The first letter of a swipe, on its first sample.
                if (freeSlot >= 0) {
                    for (e in 0 until eligibleCount) {
                        val t = eligible[e]
                        if (!ws.isSwipe[t]) continue
                        if (!ws.startKey[t * kc + key] || !ws.near[t * kc + key]) continue
                        ws.loadScratch(s)
                        val base = freeSlot * MS
                        val first = ws.cost[t * MS * kc + key]
                        ws.scratch[base] = first
                        for (j in 1 until MS) ws.scratch[base + j] = UNREACHABLE
                        ws.scratchTok[freeSlot] = t
                        ws.scratchKey[freeSlot] = key
                        ws.scratchMin[freeSlot] = 1
                        ws.scratchFloor[freeSlot] = first
                        val bound = head - weight * (settled + openFloors(ws))
                        if (bound < floor - EPS) continue
                        val id = ws.push(
                            node = child, parent = s, viaLabel = label, letterCp = codePoint,
                            lastTok = t, lastKey = key, length = length + 1, done = done,
                            settled = settled, bound = bound,
                        )
                        if (id < 0) break
                    }
                }

                // The next letter along an open swipe.
                for (slot in 0 until SLOTS) {
                    val tok = ws.slotTok[s * SLOTS + slot]
                    if (tok < 0 || !ws.near[tok * kc + key]) continue
                    val prevKey = ws.slotKey[s * SLOTS + slot]
                    // The same key twice running inside one swipe is a doubled
                    // letter, which the repeat above already offers. Not once
                    // another gesture has given a letter since: a swipe resting
                    // on a key while the other thumb taps carries on from that
                    // key and spells it again ("se", tap "l", "ect").
                    if (prevKey == key && ws.lastTok[s] == tok) continue
                    ws.loadScratch(s)
                    val min = advance(ws, s, slot, tok, prevKey, key, keys)
                    if (min >= UNREACHABLE) continue
                    ws.scratchKey[slot] = key
                    ws.scratchFloor[slot] = min
                    val bound = head - weight * (settled + openFloors(ws))
                    if (bound < floor - EPS) continue
                    val id = ws.push(
                        node = child, parent = s, viaLabel = label, letterCp = codePoint,
                        lastTok = tok, lastKey = key, length = length + 1, done = done,
                        settled = settled, bound = bound,
                    )
                    if (id < 0) break
                }
            }
        }
        return floor
    }

    /**
     * Fits tap [t] into the swipes open in the scratch: each one's letters so
     * far must lie before the tap's moment along it (within the window), and
     * its later letters after. False when some open swipe cannot oblige.
     */
    private fun placeTapAmongOpen(t: Int, ws: KotlinDuetWorkspace): Boolean {
        for (slot in 0 until SLOTS) {
            val tok = ws.scratchTok[slot]
            if (tok < 0) continue
            val n = ws.samples[tok]
            val hi = ws.tapHi[t * MT + tok]
            val base = slot * MS
            var best = UNREACHABLE
            for (j in 0 until n) {
                if (j > hi) ws.scratch[base + j] = UNREACHABLE
                val v = ws.scratch[base + j]
                if (v < best) best = v
            }
            if (best >= UNREACHABLE) return false
            ws.scratchFloor[slot] = best
            val lo = ws.tapLo[t * MT + tok]
            if (lo > ws.scratchMin[slot]) ws.scratchMin[slot] = lo
        }
        return true
    }

    /**
     * One letter further along the swipe open in [slot], written into the
     * scratch: [GlideBeam]'s banded DP over this swipe's own samples, never
     * placing the letter before the slot's minimum sample. Returns the new
     * column's lowest value.
     */
    @Suppress("LongParameterList")
    private fun advance(
        ws: KotlinDuetWorkspace,
        s: Int,
        slot: Int,
        tok: Int,
        prevKey: Int,
        key: Int,
        keys: GlideKeyMap,
    ): Float {
        val n = ws.samples[tok]
        val kc = keys.keyCount
        val step = ws.arcStep[tok]
        val span = keys.distance(prevKey, key)
        val expected = span / step
        val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
        val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1)
        val prev = ws.columnOf(s, slot)
        val out = slot * MS
        val first = maxOf(1, ws.scratchMin[slot])
        for (j in 0 until minOf(first, n)) ws.scratch[out + j] = UNREACHABLE
        // Sample 0 holds only a first letter and only a last finishes on n - 1,
        // so these ease the lead-in and the tail and nothing else.
        val lead = anchorLead(tok, 0, prevKey, tuning.startSlack, keys, ws)
        val tail = anchorLead(tok, n - 1, key, tuning.endSlack, keys, ws)
        var best = UNREACHABLE
        for (j in first until n) {
            val from = maxOf(0, j - hi)
            val to = j - lo
            val easeJ = if (j == n - 1) tail else 0f
            var bestPrev = UNREACHABLE
            var i = from
            while (i <= to) {
                val at = ws.cols[prev + i]
                if (at < UNREACHABLE) {
                    val gap = (j - i) * step - span
                    val over = (if (gap < 0f) -gap else gap) - (if (i == 0) lead + easeJ else easeJ)
                    val v = at + (if (over > 0f) tuning.gapWeight * over else 0f)
                    if (v < bestPrev) bestPrev = v
                }
                i++
            }
            val value = if (bestPrev >= UNREACHABLE) {
                UNREACHABLE
            } else {
                bestPrev + ws.cost[(tok * MS + j) * kc + key]
            }
            ws.scratch[out + j] = value
            if (value < best) best = value
        }
        return best
    }

    /**
     * Steps over a trie edge the gestures say nothing about — the high half of
     * a surrogate pair, or a joiner — with every slot carried over. False when
     * the pool saturated.
     */
    @Suppress("LongParameterList")
    private fun silent(
        s: Int,
        child: Int,
        label: Char,
        settled: Float,
        head: Double,
        floor: Double,
        ws: KotlinDuetWorkspace,
    ): Boolean {
        ws.loadScratch(s)
        val bound = head - tuning.shapeWeight * (settled + openFloors(ws))
        if (bound < floor - EPS) return true
        return ws.push(
            node = child, parent = s, viaLabel = label, letterCp = ws.letterCp[s],
            lastTok = ws.lastTok[s], lastKey = ws.lastKey[s], length = ws.length[s].toInt(),
            done = ws.done[s], settled = settled, bound = bound,
        ) >= 0
    }

    /**
     * The loops none of state [s]'s doubled letters claim, times
     * [GlideBeam.Tuning.unclaimedLoop]: [GlideBeam]'s charge, with each loop
     * claimed only by a doubling on its own key that the swipe it was drawn
     * in gave a copy of. A doubled letter is a state spelling the same letter
     * as its parent one character further on; closing a swipe and the silent
     * steps keep the length, which keeps them out.
     */
    private fun unclaimedLoop(s: Int, ws: KotlinDuetWorkspace): Float {
        val weight = tuning.unclaimedLoop
        if (weight <= 0f || ws.loopCount == 0) return 0f
        var charge = 0f
        for (e in 0 until ws.loopCount) {
            val tok = ws.loopTok[e]
            val own = ws.loopKey[e]
            var claimed = false
            var cur = s
            while (cur >= 0 && !claimed) {
                val parent = ws.parent[cur]
                if (parent >= 0 && ws.letterCp[cur] == ws.letterCp[parent] &&
                    ws.length[cur].toInt() == ws.length[parent] + 1
                ) {
                    claimed = ws.lastKey[cur] == own && (ws.lastTok[cur] == tok || ws.lastTok[parent] == tok)
                }
                cur = parent
            }
            if (!claimed) charge += 1f
        }
        return weight * charge
    }

    private fun openFloors(ws: KotlinDuetWorkspace): Float {
        var sum = 0f
        for (slot in 0 until SLOTS) if (ws.scratchTok[slot] >= 0) sum += ws.scratchFloor[slot]
        return sum
    }

    private fun emit(
        word: String,
        score: Double,
        cost: Double,
        tier: FuzzyBeamSearch.Tier,
        results: HashMap<String, GlideBeam.Candidate>,
    ) {
        val existing = results[word]
        if (existing == null || score > existing.score) {
            results[word] = GlideBeam.Candidate(word, score, cost, tier)
        }
    }

    private fun kthBest(results: HashMap<String, GlideBeam.Candidate>, k: Int, ws: KotlinDuetWorkspace): Double {
        val size = results.size
        if (size < k) return Double.NEGATIVE_INFINITY
        var scores = ws.scoreScratch
        if (scores.size < size) {
            scores = DoubleArray(size)
            ws.scoreScratch = scores
        }
        var i = 0
        for (c in results.values) scores[i++] = c.score
        java.util.Arrays.sort(scores, 0, size)
        return scores[size - k]
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val MS = KotlinDuetWorkspace.MAX_SAMPLES
        private const val MT = KotlinDuetWorkspace.MAX_TOKENS
        private const val SLOTS = KotlinDuetWorkspace.SLOTS
        private const val UNREACHABLE = KotlinGlideWorkspace.UNREACHABLE

        /** Strokes shorter than this are taps, in key widths: [GlideBeam]'s threshold. */
        private const val MIN_STROKE_LENGTH = 0.5f
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = 24

        /** Synthetic spacing for tokens with no clock, so list order is strict order. */
        private const val CLOCKLESS_SPACING = 1_000L
        private const val EPS = 1e-9
    }
}
