package com.wasimaster.wmkeyboard.core.gesture.reference

import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideJoiners
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSource
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeStore
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import kotlin.math.ceil
import kotlin.math.sqrt
// fdlibm's log, as `wm-engine`'s `strict_math::log` computes it, so the Rust
// decoder and this one score alike on every runtime.
private fun ln(x: Double): Double = StrictMath.log(x)

private typealias Tuning = GlideBeam.Tuning
private typealias Candidate = GlideBeam.Candidate
private typealias Alignment = GlideBeam.Alignment

/**
 * The Kotlin GlideBeam as it stood when its decode moved to Rust
 * (native/RUST_NEON_PLAN.md step 4), kept as the reference the parity test
 * holds the Rust decoder to and the white-box tests read the workspace of.
 */
class KotlinGlideBeam(private val tuning: Tuning = Tuning()) {

    /**
     * Decodes [path] (pixel coordinates, keys [keyWidth] wide) against [sources],
     * returning up to [limit] words best first. Empty when the stroke is too
     * short to be a gesture, or when nothing in the tries explains it.
     * [shapes] are the user's own learned shapes for this grid, when they
     * have any, for the shape channel to compare against beside the ideal.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: KotlinGlideWorkspace,
        limit: Int = 4,
        shapes: GlideShapeSource? = null,
        lookAhead: Int = 0,
    ): List<Candidate> {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return emptyList()
        if (keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        if (!resample(path, keyWidth, ws)) return emptyList()
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildDwell(keys, ws)
        if (ws.arcStep <= 0f) return emptyList()
        buildLoops(path, keyWidth, keys, ws)
        buildRemain(keys, ws)

        val k = maxOf(limit * 2, RESULT_K)
        val results = HashMap<String, Candidate>()
        // Completions are collected apart from the readings and never touch
        // `floor`. The floor is what makes the walk's bound admissible for
        // *words*; a completion scores on a prefix's alignment plus a guess,
        // and letting one raise the floor would prune real words that the
        // guess merely outscored.
        //
        // A stroke that ends on a loop asks for no guesses at all. The loop
        // is a deliberate mark on the last key the finger reached, and a
        // finger that drew it and lifted has said where the word ends: with
        // "finish words early" on, a circle closing "see" was otherwise
        // read as the start of "seer" (issue #337).
        val ahead = if (lookAhead > 0 && !endsOnLoop(ws)) HashMap<String, Candidate>() else null
        // One budget across every source, carried in a box so `searchOne` can
        // spend from it without threading a return value back.
        val budget = intArrayOf(tuning.lookAheadBudget)
        var floor = Double.NEGATIVE_INFINITY

        // Heaviest source first, so its emissions raise the floor before the
        // lighter ones start — the same ordering trick the typing beam uses.
        val ordered = sources.sortedByDescending {
            it.logWeight + tuning.freqWeight * ln1p(it.walker.maxSubtree(it.walker.root))
        }
        for (src in ordered) {
            val rootBound = src.logWeight + tuning.freqWeight * ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(src, keys, ws, k, results, floor, ahead, budget)
        }

        val ranked = results.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
        var read = rescoreShape(ranked, keys, ws, shapes).take(limit)

        // Inject or promote learned words whose saved shape matches the drawn stroke,
        // even if trie pruning or anchor mismatch filtered or demoted them in results.
        if (shapes != null && tuning.shapeSeeding && tuning.learnedShapeGain > 0.0) {
            drawnShape(ws)
            quantise(ws.drawnShapeX, ws.drawnShapeY, ws.drawnShape8)
            val nearWords = shapes.wordsNear(ws.drawnShape8, radius = 0.35f, limit = limit)
            if (nearWords.isNotEmpty()) {
                val maxRescoredScore = read.maxOfOrNull { it.score } ?: 0.0
                val updatedRead = read.map { candidate ->
                    if (candidate.word in nearWords) {
                        Candidate(candidate.word, maxOf(candidate.score, maxRescoredScore), candidate.shapeCost, candidate.tier, candidate.ahead)
                    } else {
                        candidate
                    }
                }.toMutableList()

                val readWords = read.mapTo(HashSet()) { it.word }
                for (word in nearWords) {
                    if (word !in readWords) {
                        updatedRead.add(Candidate(word, maxRescoredScore, 0.0, FuzzyBeamSearch.Tier.DICTIONARY))
                    }
                }
                read = updatedRead.sortedWith(
                    compareByDescending<Candidate> { it.score }.thenBy { it.word }
                ).take(limit)
            }
        }
        if (ahead.isNullOrEmpty()) return read
        // A word the stroke spelled out is never also offered as a guess at
        // where it was going: one source can read it while another's prefix
        // completes to it, and the merged list would carry it twice.
        val readWords = read.mapTo(HashSet()) { it.word }
        val guesses = ahead.values.filterNot { it.word in readWords }
        // Merged into one ranked list so a caller sees the decoder's whole
        // opinion in score order; `Candidate.ahead` is what tells the two
        // apart, and every caller that cares checks it.
        //
        // The completions go through the shape channel too (issue #167).
        // They used to skip it, and every caller compares a guess's score
        // against the best ordinary reading's — so a reading paid the
        // channel's charge, up to several nats on a real finger, and the
        // guess extending it paid nothing. "thing" drawn to its last letter
        // lost to "things" on that gap alone, and a word the stroke had
        // spelled out could be beaten by any longer word it started.
        val completions = rescoreShape(guesses, keys, ws, shapes)
            .take(lookAhead)
        return (read + completions).sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
    }

    /**
     * [path] as the shape store keeps a stroke: resampled, normalised the way
     * the shape channel normalises the drawn stroke — centred, scaled to unit
     * spread — and quantised to a byte a coordinate. Null when the stroke is
     * too short to be a glide.
     */
    /**
     * The loops [decode] would read in [path], found exactly as it finds
     * them, for the CTC decoder: `wm-engine`'s `glide::Decoder::marks`.
     */
    fun marks(path: List<GesturePoint>, keys: GlideKeyMap, keyWidth: Float, ws: KotlinGlideWorkspace): KotlinGlideMarks? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f || keys.keyCount == 0) return null
        if (!resample(path, keyWidth, ws)) return null
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildDwell(keys, ws)
        if (ws.arcStep <= 0f) return null
        buildLoops(path, keyWidth, keys, ws)
        val loops = ArrayList<Pair<Int, Float>>()
        for (e in 0 until ws.loopCount) {
            if (ws.loopScore[e] > 0f && ws.loopKey[e] >= 0) loops.add(ws.loopKey[e] to ws.loopScore[e])
        }
        return KotlinGlideMarks(loops, endsOnLoop(ws))
    }

    /** `glide::Decoder::prepare_shape`: the stroke ready for [learnedGain]. */
    fun prepareShape(path: List<GesturePoint>, keyWidth: Float, ws: KotlinGlideWorkspace): Boolean {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f || !resample(path, keyWidth, ws)) return false
        drawnShape(ws)
        normalise(ws.drawnShapeX, ws.drawnShapeY, ws.drawnShapeX, ws.drawnShapeY)
        plainShape(ws, loops = false)
        return true
    }

    /**
     * `glide::Decoder::learned_gain`: how much nearer the prepared stroke sits
     * to [word] as the user drew it before than to its ideal path.
     */
    fun learnedGain(word: String, keys: GlideKeyMap, shapes: GlideShapeSource, ws: KotlinGlideWorkspace): Double {
        if (tuning.learnedShapeGain <= 0.0 || shapes.shapesOf(word).isEmpty()) return 0.0
        val ideal = shapeDistance(word, keys, ws) ?: return 0.0
        return ideal - learnedDistance(word, ideal, shapes, ws)
    }

    fun sampleShape(path: List<GesturePoint>, keyWidth: Float, ws: KotlinGlideWorkspace): ByteArray? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return null
        if (!resample(path, keyWidth, ws)) return null
        val out = ByteArray(2 * KotlinGlideWorkspace.SHAPE_POINTS)
        drawnShape(ws)
        quantise(ws.drawnShapeX, ws.drawnShapeY, out)
        return out
    }

    /**
     * Lays [word] over [path] the way the search would have, and says where
     * along the stroke each of its keys landed — the evidence the hand model
     * learns from (issue #52). The same cost model as the walk, one word
     * instead of a lattice, with the argmin kept at every cell so the placement
     * can be read back off the finished column. Null when the word has a
     * character [keys] cannot produce, has fewer than two keys, or cannot reach
     * the stroke's last sample at all.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun align(
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: KotlinGlideWorkspace,
    ): Alignment? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f || keys.keyCount == 0) return null
        val visits = IntArray(KotlinGlideWorkspace.MAX_IDEAL_POINTS)
        val starts = IntArray(KotlinGlideWorkspace.MAX_IDEAL_POINTS)
        var count = 0
        var previous = -1
        var at = 0
        while (at < word.length) {
            val codePoint = word.codePointAt(at)
            val start = at
            at += Character.charCount(codePoint)
            val key = keys.keyIndex(codePoint)
            // A joiner is not a visit: the walk stepped over it, so the
            // alignment it is read back against has to step over it too, or
            // every hyphenated word would report "cannot be drawn" and take
            // its shape lesson and its casing with it (#230). The next letter
            // keeps its own offset, so [Alignment.chars] still points past the
            // hyphen and not at it.
            if (key < 0) {
                if (GlideJoiners.isJoiner(codePoint)) continue
                return null
            }
            if (key == previous) continue
            if (count >= KotlinGlideWorkspace.MAX_IDEAL_POINTS) return null
            visits[count] = key
            starts[count] = start
            count++
            previous = key
        }
        if (count < 2) return null
        if (!resample(path, keyWidth, ws)) return null
        if (ws.arcStep <= 0f) return null
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildLoops(path, keyWidth, keys, ws)

        val n = ws.samples
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val step = ws.arcStep
        val arc = ws.arcAt
        val back = IntArray(count * n)
        var prev = FloatArray(n)
        var cur = FloatArray(n)
        prev[0] = cost[visits[0]]
        for (j in 1 until n) prev[j] = KotlinGlideWorkspace.UNREACHABLE
        for (m in 1 until count) {
            val key = visits[m]
            val span = keys.distance(visits[m - 1], key)
            val expected = span / step
            val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
            val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1) + ws.collapsedSamples
            val lead = anchorLead(0, visits[m - 1], tuning.startSlack, keys, ws)
            val tail = anchorLead(n - 1, key, tuning.endSlack, keys, ws)
            cur[0] = KotlinGlideWorkspace.UNREACHABLE
            for (j in 1 until n) {
                var best = KotlinGlideWorkspace.UNREACHABLE
                var from = -1
                val arcJ = arc[j]
                val easeJ = if (j == n - 1) tail else 0f
                var i = maxOf(0, j - hi)
                val to = j - lo
                while (i <= to) {
                    val before = prev[i]
                    if (before < KotlinGlideWorkspace.UNREACHABLE) {
                        val gap = arcJ - arc[i] - span
                        val v = before + gapCharge(gap, if (i == 0) lead + easeJ else easeJ)
                        if (v < best) {
                            best = v
                            from = i
                        }
                    }
                    i++
                }
                cur[j] = if (from < 0) KotlinGlideWorkspace.UNREACHABLE else best + cost[j * keyCount + key]
                back[m * n + j] = from
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        if (prev[n - 1] >= KotlinGlideWorkspace.UNREACHABLE) return null

        val x = FloatArray(count)
        val y = FloatArray(count)
        val samples = IntArray(count)
        var j = n - 1
        for (m in count - 1 downTo 0) {
            x[m] = ws.pathX[j]
            y[m] = ws.pathY[j]
            samples[m] = j
            if (m > 0) j = back[m * n + j]
        }
        return Alignment(visits.copyOf(count), x, y, samples, starts.copyOf(count), n)
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
        ws: KotlinGlideWorkspace,
        k: Int,
        results: HashMap<String, Candidate>,
        floorIn: Double,
        ahead: HashMap<String, Candidate>?,
        budget: IntArray,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val n = ws.samples
        val keyCount = keys.keyCount
        // The vocabulary cap as this source's own frequency. A source with no
        // frequency ranking answers 0, which switches every test below off.
        val minFrequency = walker.frequencyAtRank(tuning.vocabularyRank)
        val joinOn = tuning.joinCost.isFinite()

        ws.reset()
        ws.push(
            node = walker.root, parent = -1, viaLabel = KotlinGlideWorkspace.NO_LABEL,
            lastKey = -1, length = 0, extra = 0f, floorCost = 0f,
            bound = src.logWeight + tuning.freqWeight * ln1p(walker.maxSubtree(walker.root)),
            letterCp = 0,
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
            val partFreq = ws.partFreq[s]
            val partFrom = ws.partFrom[s]
            val joins = ws.joins[s]
            // The join charge plus the common-part penalties this state
            // already earned; both 0.0 for a plain word.
            val charge = joinCharge(joins) + ws.joinPenalty[s]

            // The prefix's last letter must land on the last sample. For a
            // finished word that is where the finger lifted; for a look-ahead
            // it is where the finger is *now*, which is the same condition and
            // the same anchor.
            // `lastKey` is -1 at the root, which has spelled nothing and can
            // be neither a word nor a prefix worth guessing from.
            val atEnd = lastKey >= 0 && ws.endKey[lastKey] &&
                ws.cols[ws.columnOf(s) + n - 1] < KotlinGlideWorkspace.UNREACHABLE
            if (atEnd) {
                val total = ws.cols[ws.columnOf(s) + n - 1]
                // Charged here and not on the way down: only a finished
                // word knows which pauses none of its letters claim. The
                // bound stays admissible since the charge can only lower
                // a score, never raise one.
                val shape = total + extra + unclaimedDwell(s, keys, ws) +
                    unclaimedLoop(s, ws)
                // A compound's last part is held to the same length as its
                // first; `partFrom` is 0 and `charge` 0.0 for a plain word.
                if (length >= MIN_WORD_LENGTH &&
                    length - partFrom >= (if (joins > 0) tuning.joinMinPart else 0) &&
                    walker.isWord(node) &&
                    walker.frequency(node) >= minFrequency
                ) {
                    val score = src.logWeight + tuning.freqWeight * ln1p(lmCap(partFreq, walker.frequency(node))) -
                        tuning.shapeWeight * shape -
                        charge
                    if (score > floor - EPS || results.size < k) {
                        val word = ws.materialize(s)
                        if (!listedNonWord(word, src)) {
                            emit(word, score, shape.toDouble(), src.tier, results)
                            if (results.size >= k) floor = kthBest(results, k, ws)
                        }
                    }
                }
                if (ahead != null && budget[0] > 0 && length - partFrom >= MIN_LOOKAHEAD_PREFIX) {
                    lookAhead(src, walker, node, s, shape, minFrequency, ws, ahead, budget)
                }
            }
            if (length >= MAX_WORD_LENGTH || length >= n) continue

            // A compound (#966 §5.2): a finished part of three letters or
            // more may hand the rest of the stroke to a second word, read
            // from the trie root on the same alignment column, as if the
            // list held the joined spelling. The joined word scores on the
            // rarest of its parts, `joinCost` the poorer per join, and the
            // bound carries both, so a join never ranks above what the
            // single-word bound allows and the walk's pruning stays exact.
            // The part joined past pays `joinPartPenalty` on its own
            // frequency; the last part, which no join follows, pays nothing.
            if (joinOn &&
                joins < MAX_JOINS &&
                length - partFrom >= tuning.joinMinPart &&
                length + tuning.joinMinPart <= n &&
                walker.isWord(node) &&
                walker.frequency(node) >= minFrequency
            ) {
                val cap = lmCap(partFreq, walker.frequency(node))
                val floorCost = ws.floorCost[s]
                val partPenalty = tuning.joinPartPenalty * ln1p(walker.frequency(node))
                val bound = src.logWeight + tuning.freqWeight * ln1p(lmCap(cap, walker.maxSubtree(walker.root))) -
                    tuning.shapeWeight * (floorCost + extra) -
                    joinCharge(joins + 1) -
                    ws.joinPenalty[s] -
                    partPenalty
                if (bound >= floor - EPS) {
                    val id = ws.push(
                        node = walker.root, parent = s, viaLabel = KotlinGlideWorkspace.NO_LABEL, lastKey = lastKey,
                        length = length, extra = extra, floorCost = floorCost, bound = bound,
                        letterCp = ws.letterCp[s],
                    )
                    if (id >= 0) {
                        ws.join(
                            id, partFreq = cap, partFrom = length, joins = joins + 1,
                            joinPenalty = ws.joinPenalty[s] + partPenalty,
                        )
                        ws.copyColumn(s, id)
                    }
                }
            }

            val count = walker.childrenInto(node, ws.children)
            // The trie is spelled in UTF-16 units, so a letter outside the BMP
            // is two edges: the high surrogate, then the low. A state that has
            // taken the high half is waiting on the low, and only the pair
            // names a key — every Warang Citi letter shares one high half.
            val pendingHigh = ws.viaLabel[s].takeIf { it.isHighSurrogate() }
            for (i in 0 until count) {
                val label = ws.children.labels[i]
                val child = ws.children.nodes[i]
                val subtree = walker.maxSubtree(child)
                // Every word under here is at most `subtree` frequent, so a
                // subtree below the cap holds nothing the emit gate would keep.
                // Pruning it makes a capped decode cheaper than an uncapped
                // one rather than merely quieter.
                if (subtree < minFrequency) continue

                val codePoint: Int
                if (pendingHigh != null) {
                    // Anything but a low surrogate here is a malformed word,
                    // which no grid can spell.
                    if (!label.isLowSurrogate()) continue
                    codePoint = Character.toCodePoint(pendingHigh, label)
                } else if (label.isHighSurrogate()) {
                    // Half a letter: let the low half decide, and until it does
                    // spend none of the stroke on it.
                    if (!descendSilent(src, subtree, s, child, label, extra, floor, ws)) break
                    continue
                } else {
                    codePoint = label.code
                }

                val key = keys.keyIndex(codePoint)
                // A hyphen, a dot, an at sign: spelled in the word, absent from
                // the grid, and never drawn. Step over it for the price of
                // `joinerCost` rather than losing the word with it (#230). Only
                // between letters — a word cannot be *started* on a character
                // the stroke's first sample has nothing to anchor to — and
                // never twice running, which bounds the detour a malformed
                // entry can send the walk on.
                if (key < 0 && length > 0 && GlideJoiners.isJoiner(codePoint) &&
                    !GlideJoiners.isJoiner(ws.viaLabel[s].code)
                ) {
                    val cost = extra + tuning.joinerCost
                    if (!descendSilent(src, subtree, s, child, label, cost, floor, ws)) break
                    continue
                }
                // A character the grid cannot produce makes its whole subtree
                // unreachable; so does one the stroke never went near.
                if (key < 0 || key >= keyCount || !ws.nearKey[key]) continue
                if (length == 0 && !ws.startKey[key]) continue

                val repeat = length > 0 && codePoint == ws.letterCp[s]

                val minCol: Float
                var childExtra = extra
                if (repeat) {
                    // A doubled letter is one key visited once, so the stroke
                    // holds no evidence of it at all: the alignment carries
                    // over untouched and the repeat pays a flat charge instead.
                    // Without the charge every doubled spelling would shadow
                    // its single one for free. A loop on the key is the
                    // evidence, and waives it; a pause is not (issue #337).
                    minCol = ws.floorCost[s]
                    childExtra += repeatCharge(lastKey, ws)
                } else {
                    minCol = advance(
                        ws,
                        prevColumn = if (length == 0) -1 else ws.columnOf(s),
                        key = key,
                        prevKey = lastKey,
                        keys = keys,
                    )
                    if (minCol >= KotlinGlideWorkspace.UNREACHABLE) continue
                }

                val bound = src.logWeight + tuning.freqWeight * ln1p(lmCap(partFreq, subtree)) -
                    tuning.shapeWeight * (minCol + childExtra) -
                    charge
                if (bound < floor - EPS) continue

                val id = ws.push(
                    node = child, parent = s, viaLabel = label, lastKey = key,
                    length = length + 1, extra = childExtra, floorCost = minCol, bound = bound,
                    letterCp = codePoint,
                )
                if (id < 0) break // pool saturated; best-first says the rest are worse
                if (repeat) ws.copyColumn(s, id) else ws.storeScratch(id)
            }
        }
        return floor
    }

    /**
     * What [joins] joins cost: nothing for none, which keeps a plain word's
     * score the plain one even with the joining off (infinity times zero is
     * not zero).
     */
    private fun joinCharge(joins: Int): Double = if (joins > 0) tuning.joinCost * joins else 0.0

    /**
     * Whether [word] is a word list's non-word that the decoder should not
     * offer: an interrupted word, a stray full stop or a stutter (see
     * [GlideJoiners.isNonWord], issue #304). Only for word lists. The personal
     * lexicon holds what this user wrote or added, and #230 was about letting
     * them glide exactly that.
     */
    private fun listedNonWord(word: String, src: FuzzyBeamSearch.WalkSource): Boolean =
        src.tier == FuzzyBeamSearch.Tier.DICTIONARY && GlideJoiners.isNonWord(word)

    /**
     * Descends the edge [label] into [child] without spending any of the stroke:
     * the alignment column, the letter count, the last key and the last letter
     * all carry over untouched, and only [extra] may differ from the parent's.
     *
     * Two edges are walked this way and they are the same case — a character in
     * the word that the stroke has nothing to say about. The high half of a
     * surrogate pair is half a letter and waits for the low half to name a key;
     * a [GlideJoiners] character is a whole character with no key at all, and
     * pays [Tuning.joinerCost] for the privilege.
     *
     * False when the state pool saturated, which best-first says the caller
     * should take as "the rest of these children are worse" and stop.
     */
    @Suppress("LongParameterList")
    private fun descendSilent(
        src: FuzzyBeamSearch.WalkSource,
        subtree: Int,
        s: Int,
        child: Int,
        label: Char,
        extra: Float,
        floor: Double,
        ws: KotlinGlideWorkspace,
    ): Boolean {
        val bound = src.logWeight + tuning.freqWeight * ln1p(lmCap(ws.partFreq[s], subtree)) -
            tuning.shapeWeight * (ws.floorCost[s] + extra) -
            joinCharge(ws.joins[s]) -
            ws.joinPenalty[s]
        if (bound < floor - EPS) return true
        val id = ws.push(
            node = child, parent = s, viaLabel = label, lastKey = ws.lastKey[s],
            length = ws.length[s].toInt(), extra = extra, floorCost = ws.floorCost[s],
            bound = bound, letterCp = ws.letterCp[s],
        )
        if (id < 0) return false
        ws.copyColumn(s, id)
        return true
    }

    /**
     * Offers the commonest word under the prefix at state [s] as a look-ahead
     * candidate — the stroke drawn so far explains that prefix, and the rest of
     * the word is the language model's guess about where the finger is going.
     *
     * The word is found by descending from [node] always into a child whose
     * subtree bound equals this node's, which by construction arrives at a word
     * carrying that bound: the walker's `maxSubtree` is the greatest frequency
     * anywhere beneath, so the branch holding it is the branch that still
     * reports it. That makes the frequency in the score the *actual* word's
     * rather than an upper bound over a subtree, which matters because the two
     * differ by exactly the amount that would make a long shot look certain.
     *
     * A prefix that is already the commonest thing under it offers nothing —
     * the ordinary emit above has that word, drawn rather than guessed.
     */
    @Suppress("LongParameterList")
    private fun lookAhead(
        src: FuzzyBeamSearch.WalkSource,
        walker: TrieWalker,
        node: Int,
        s: Int,
        shape: Float,
        minFrequency: Int,
        ws: KotlinGlideWorkspace,
        ahead: HashMap<String, Candidate>,
        budget: IntArray,
    ) {
        val best = walker.maxSubtree(node)
        if (best < minFrequency || best <= walker.frequency(node)) return
        budget[0]--
        val prefix = ws.materialize(s)
        val word = StringBuilder(prefix)
        var at = node
        var steps = 0
        while (steps < MAX_WORD_LENGTH) {
            if (walker.isWord(at) && walker.frequency(at) == best) break
            // Its own buffer: `ws.children` belongs to the walk that called
            // this, and sharing it would make the walk correct only for as
            // long as it happens to refill the buffer after this returns.
            val children = ws.lookAheadChildren
            val count = walker.childrenInto(at, children)
            var next = -1
            var label = '\u0000'
            for (i in 0 until count) {
                if (walker.maxSubtree(children.nodes[i]) == best) {
                    next = children.nodes[i]
                    label = children.labels[i]
                    break
                }
            }
            if (next < 0) return
            word.append(label)
            at = next
            steps++
        }
        if (steps == 0 || word.length <= prefix.length) return
        val spelled = word.toString()
        if (listedNonWord(spelled, src)) return
        val extra = spelled.length - prefix.length
        val score = src.logWeight + tuning.freqWeight * ln1p(lmCap(ws.partFreq[s], best)) -
            tuning.shapeWeight * shape -
            tuning.lookAheadCost * extra -
            joinCharge(ws.joins[s]) -
            ws.joinPenalty[s]
        val existing = ahead[spelled]
        if (existing == null || score > existing.score) {
            ahead[spelled] = Candidate(spelled, score, shape.toDouble(), src.tier, extra)
        }
    }

    /**
     * Extends the alignment by one letter sitting on [key], writing the new
     * column into [KotlinGlideWorkspace.scratch] and returning its lowest value.
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
        ws: KotlinGlideWorkspace,
        prevColumn: Int,
        key: Int,
        prevKey: Int,
        keys: GlideKeyMap,
    ): Float {
        val n = ws.samples
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val out = ws.scratch

        val remain = ws.remain

        if (prevColumn < 0) {
            out[0] = cost[key]
            for (j in 1 until n) out[j] = KotlinGlideWorkspace.UNREACHABLE
            return if (remain[key] >= KotlinGlideWorkspace.UNREACHABLE) KotlinGlideWorkspace.UNREACHABLE else out[0] + remain[key]
        }

        val prev = ws.cols
        val step = ws.arcStep
        val arc = ws.arcAt
        val span = keys.distance(prevKey, key)
        // Samples the stroke should have travelled between the two letters.
        // A loop's collapsed arc can only make the finger's travel read as
        // less than its sample count says, so it widens the far edge alone.
        val expected = span / step
        val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
        val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1) + ws.collapsedSamples
        // Only the first letter sits on sample 0, so a finite value there is
        // always the lead-in; only the last letter can finish on the last.
        val lead = anchorLead(0, prevKey, tuning.startSlack, keys, ws)
        val tail = anchorLead(n - 1, key, tuning.endSlack, keys, ws)

        out[0] = KotlinGlideWorkspace.UNREACHABLE
        var best = KotlinGlideWorkspace.UNREACHABLE
        for (j in 1 until n) {
            // The stroke's two ends are open past the band (#886): the first
            // letter may stand any distance before the next and the last any
            // distance after the one before, the extra travel paying the gap
            // charge. A loop the scan missed, or collapsed only in part, used
            // to put `ill`'s second key out of reach of its first entirely.
            val from = if (j == n - 1) 0 else maxOf(0, j - hi)
            val to = j - lo
            val arcJ = arc[j]
            val easeJ = if (j == n - 1) tail else 0f
            var bestPrev = KotlinGlideWorkspace.UNREACHABLE
            if (from > 0 && to >= 0) {
                val at = prev[prevColumn]
                if (at < KotlinGlideWorkspace.UNREACHABLE) {
                    val v = at + gapCharge(arcJ - arc[0] - span, lead + easeJ)
                    if (v < bestPrev) bestPrev = v
                }
            }
            var i = from
            while (i <= to) {
                val at = prev[prevColumn + i]
                if (at < KotlinGlideWorkspace.UNREACHABLE) {
                    val travelled = arcJ - arc[i]
                    val gap = travelled - span
                    val v = at + gapCharge(gap, if (i == 0) lead + easeJ else easeJ)
                    if (v < bestPrev) bestPrev = v
                }
                i++
            }
            val value = if (bestPrev >= KotlinGlideWorkspace.UNREACHABLE) {
                KotlinGlideWorkspace.UNREACHABLE
            } else {
                bestPrev + cost[j * keyCount + key]
            }
            out[j] = value
            val rest = remain[j * keyCount + key]
            if (value < KotlinGlideWorkspace.UNREACHABLE && rest < KotlinGlideWorkspace.UNREACHABLE && value + rest < best) {
                best = value + rest
            }
        }
        return best
    }

    /**
     * Fills [KotlinGlideWorkspace.remain]: for every sample and every key the stroke
     * passes near, the cheapest way to explain the rest of the stroke from a
     * letter sitting there, over any sequence of keys at all. The same two
     * terms and the same band as [advance], run backwards from the last sample,
     * with the dictionary left out — which is what makes it a lower bound on
     * what any real word will pay.
     *
     * Without it the walk's bound was a prefix column's lowest value alone, and
     * a prefix's lowest value is almost always early in the stroke, where its
     * few letters fit well — it says nothing about the length of stroke still
     * unexplained. On a short stroke that hardly matters. On a long one every
     * two- and three-letter prefix in the dictionary looked as good as the word
     * being drawn, the walk spent its whole state pool on them, and the word
     * was never reached: a nineteen-letter German compound came back as some
     * six-letter word that fit a third of the stroke (issue #714). Charging
     * each prefix for the stroke ahead of it turns the walk from breadth-first
     * in disguise into an A* search, and since the remainder never overstates
     * it the bound stays admissible and the top K are still exact.
     */
    @Suppress("NestedBlockDepth")
    private fun buildRemain(keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        val n = ws.samples
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val remain = ws.remain
        val arc = ws.arcAt
        val step = ws.arcStep
        val near = ws.nearList
        for (k in 0 until keyCount) if (ws.nearKey[k]) near[ws.nearCount++] = k
        val count = ws.nearCount
        val last = (n - 1) * keyCount
        for (k in 0 until keyCount) {
            remain[last + k] = if (ws.endKey[k]) 0f else KotlinGlideWorkspace.UNREACHABLE
        }
        for (j in n - 2 downTo 0) {
            val base = j * keyCount
            val arcJ = arc[j]
            for (a in 0 until keyCount) remain[base + a] = KotlinGlideWorkspace.UNREACHABLE
            for (x in 0 until count) {
                val from = near[x]
                // The same anchor easing as [advance], or the bound would
                // overstate what a far first or last letter really pays.
                val lead = if (j == 0) anchorLead(0, from, tuning.startSlack, keys, ws) else 0f
                var best = KotlinGlideWorkspace.UNREACHABLE
                for (y in 0 until count) {
                    val to = near[y]
                    val span = keys.distance(from, to)
                    val expected = span / step
                    val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
                    val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1) + ws.collapsedSamples
                    // Open to the last sample from anywhere, and from the
                    // first to anywhere, as in [advance].
                    val end = if (j == 0) n - 1 else minOf(n - 1, j + hi)
                    fun reach(i: Int) {
                        val rest = remain[i * keyCount + to]
                        if (rest >= KotlinGlideWorkspace.UNREACHABLE) return
                        val gap = arc[i] - arcJ - span
                        val ease = if (i == n - 1) {
                            lead + anchorLead(n - 1, to, tuning.endSlack, keys, ws)
                        } else {
                            lead
                        }
                        val v = gapCharge(gap, ease) + cost[i * keyCount + to] + rest
                        if (v < best) best = v
                    }
                    for (i in j + lo..end) reach(i)
                    if (end < n - 1 && j + lo <= n - 1) reach(n - 1)
                }
                remain[base + from] = best
            }
        }
    }

    // ---- per-decode preparation ----

    /**
     * Resamples [path] to [KotlinGlideWorkspace.samples] equidistant samples in
     * key-width coordinates, interpolating the clock along with the position so
     * timing survives. False when the stroke is too short to be a swipe at all.
     */
    private fun resample(path: List<GesturePoint>, keyWidth: Float, ws: KotlinGlideWorkspace): Boolean {
        var totalPx = 0f
        for (i in 1 until path.size) {
            totalPx += distance(path[i - 1], path[i])
        }
        if (totalPx / keyWidth < MIN_STROKE_LENGTH) return false
        // A long stroke gets finer samples, the longer the finer; see
        // KotlinGlideWorkspace.samplesFor.
        val n = KotlinGlideWorkspace.samplesFor(totalPx / keyWidth)
        ws.samples = n

        val step = totalPx / (n - 1)
        ws.arcStep = step / keyWidth
        // Travel is read off arcAt rather than off sample indices; identity
        // until buildLoops finds something to collapse.
        val arcStep = ws.arcStep
        for (j in 0 until n) ws.arcAt[j] = j * arcStep
        ws.collapsedSamples = 0
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
    private fun buildCosts(keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        val n = ws.samples
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
        anchorMask(ws.pathX[0], ws.pathY[0], keys, ws.startKey, tuning.startRadiusSq)
        anchorMask(ws.pathX[n - 1], ws.pathY[n - 1], keys, ws.endKey, tuning.endRadiusSq)
        anchorSlack(0, tuning.startSlack, ws.startKey, keys, ws)
        anchorSlack(n - 1, tuning.endSlack, ws.endKey, keys, ws)
    }

    /**
     * Rescores sample [j], an anchor, on the distance past [slack] rather than
     * the whole distance, and lets every key [mask] admits there be walked even
     * if the stroke never passes within [Tuning.nearRadius] of it (issue #805).
     * A no-op at zero slack, which is every radius at or under the default.
     *
     * Only the first letter sits on sample 0 and only a last letter finishes
     * on the last, so this reaches the anchors and nothing else; and since
     * [buildRemain] reads the same table, the walk's bound moves with it.
     */
    private fun anchorSlack(j: Int, slack: Float, mask: BooleanArray, keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        if (slack <= 0f) return
        val keyCount = keys.keyCount
        val base = j * keyCount
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        for (k in 0 until keyCount) {
            val dx = ws.pathX[j] - keys.keyX[k]
            val dy = ws.pathY[j] - keys.keyY[k]
            val past = sqrt(dx * dx + dy * dy) - slack
            val scaled = if (past > 0f) past * past * invTwoSigmaSq else 0f
            ws.pointCost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
            if (mask[k]) ws.nearKey[k] = true
        }
    }

    /**
     * How much of the travel between anchor sample [j] and [key] the gap term
     * forgives: the distance from the one to the other, up to [slack]. A
     * touch-down off its key draws that much stroke the key span cannot
     * explain, in either direction, and charging it as gap undid the slack
     * [anchorSlack] gave the location (issue #805). Zero at zero slack.
     */
    private fun anchorLead(j: Int, key: Int, slack: Float, keys: GlideKeyMap, ws: KotlinGlideWorkspace): Float {
        if (slack <= 0f || key < 0) return 0f
        val dx = ws.pathX[j] - keys.keyX[key]
        val dy = ws.pathY[j] - keys.keyY[key]
        val d = sqrt(dx * dx + dy * dy)
        return if (d < slack) d else slack
    }

    /** The gap term for a travel-against-span disagreement of [gap], the first [ease] of it free. */
    private fun gapCharge(gap: Float, ease: Float): Float {
        val over = (if (gap < 0f) -gap else gap) - ease
        return if (over > 0f) tuning.gapWeight * over else 0f
    }

    /**
     * Finds where the finger lingered and files each hold as a pause event.
     *
     * Because the path is resampled by arc length, a sample's timestamp gap is
     * the reciprocal of speed with no differentiating required: a step that took
     * much longer than the stroke's even pace is a step the finger was barely
     * moving through.
     *
     * Nothing for a stroke with no clock, which is every synthetic path in a
     * test that does not care about timing — and nothing is the right answer
     * there, since no-evidence and no-pause should charge the same.
     */
    private fun buildDwell(keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        if (tuning.unclaimedDwell <= 0f) return
        val n = ws.samples
        val span = (ws.pathT[n - 1] - ws.pathT[0]).toFloat()
        if (span <= 0f) return
        val evenStep = span / (n - 1)
        val dwellScale = tuning.dwellScale
        var runEnd = -1
        for (j in 1 until n) {
            val step = (ws.pathT[j] - ws.pathT[j - 1]).toFloat()
            val lingering = ((step / evenStep) - 1f) / dwellScale
            if (lingering <= 0f) continue
            val score = if (lingering > 1f) 1f else lingering
            recordPause(j, score, runEnd, ws)
            runEnd = j
        }
        for (p in 0 until ws.pauseCount) {
            val j = ws.pauseAt[p]
            placePause(p, ws.pathX[j], ws.pathY[j], keys, ws)
        }
    }

    /**
     * Puts pause event [p] at ([x], [y]) and works out which keys may claim
     * it: those within `DWELL_RADIUS` of it and no more than `DWELL_TIE`
     * farther than the nearest key. A stop is on one key, so the key next door
     * does not get to answer for it; the tie is for a stop drawn on the line
     * between two, which says nothing about which of them was meant and
     * should favour neither.
     */
    private fun placePause(p: Int, x: Float, y: Float, keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        var nearestSq = Float.MAX_VALUE
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            val d = dx * dx + dy * dy
            if (d < nearestSq) nearestSq = d
        }
        val reach = minOf(DWELL_RADIUS, sqrt(nearestSq) + DWELL_TIE)
        ws.pauseX[p] = x
        ws.pauseY[p] = y
        ws.pauseReachSq[p] = reach * reach
    }

    /**
     * Files sample [j]'s slowness as a pause event. A true standstill puts no
     * arc on the path, so all of its time lands in the one step that crosses
     * it; slow *travel* smears over several adjacent steps instead, and those
     * are one hold, not several — one event, sitting on whichever sample was
     * slowest, so a crawl through a key is charged once. [runEnd] is the last
     * sample filed, for telling adjacent from separate.
     */
    private fun recordPause(j: Int, score: Float, runEnd: Int, ws: KotlinGlideWorkspace) {
        val count = ws.pauseCount
        if (count > 0 && runEnd == j - 1) {
            if (score > ws.pauseScore[count - 1]) {
                ws.pauseScore[count - 1] = score
                ws.pauseAt[count - 1] = j
            }
            return
        }
        ws.pauseAt[count] = j
        ws.pauseScore[count] = score
        ws.pauseCount = count + 1
    }

    /**
     * The pauses along the stroke that none of state [s]'s letters sit on,
     * summed by how still the finger was at each, times [Tuning.unclaimedDwell].
     *
     * A pause is claimed by a key within the reach [placePause] gave it: the
     * key it happened on, and never its neighbour. The word's keys are read
     * straight off the parent chain, so this costs a walk of the word per pause
     * and allocates nothing.
     */
    private fun unclaimedDwell(s: Int, keys: GlideKeyMap, ws: KotlinGlideWorkspace): Float {
        val weight = tuning.unclaimedDwell
        if (weight <= 0f || ws.pauseCount == 0) return 0f
        var charge = 0f
        for (p in 0 until ws.pauseCount) {
            val px = ws.pauseX[p]
            val py = ws.pauseY[p]
            val reachSq = ws.pauseReachSq[p]
            var claimed = false
            var cur = s
            while (cur >= 0 && !claimed) {
                val key = ws.lastKey[cur]
                if (key >= 0) {
                    val dx = px - keys.keyX[key]
                    val dy = py - keys.keyY[key]
                    claimed = dx * dx + dy * dy <= reachSq
                }
                cur = ws.parent[cur]
            }
            if (!claimed) charge += ws.pauseScore[p]
        }
        return weight * charge
    }

    // ---- loops ----

    /**
     * Finds the places the finger went round on itself or rubbed back and
     * forth ([KotlinLoopScan], shared with [DuetBeam]) and files each as an event:
     * the samples it covers, where it sat, the key it is on, and how much of
     * a doubled-letter mark it is — a loop all of one, a wiggle none, since a
     * wiggle files a pause instead. Then leaves each event's inside to its
     * key and collapses the events' arc out of [KotlinGlideWorkspace.arcAt]. See
     * [Tuning.loopExtent] for the reading and [Tuning.wiggleExtent] for the
     * wiggle's. Nothing happens at zero extent, and a stroke with no loops
     * leaves the workspace as it found it.
     */
    private fun buildLoops(path: List<GesturePoint>, keyWidth: Float, keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        ws.loopCount = 0
        val scan = ws.loopScan
        val found = scan.find(path, keyWidth, tuning)
        for (w in 0 until found) {
            val loop = scan.windowIsLoop[w]
            val e = recordLoop(scan.windowFrom[w], scan.windowTo[w], if (loop) 1f else 0f, keys, ws)
            if (!loop) recordWiggle(e, keys, ws)
        }
        if (ws.loopCount > 0) {
            excludeNeighbours(keys, ws)
            collapseArc(ws)
        }
    }

    /**
     * Whether the stroke's last doubling loop ends within [END_LOOP_REACH] of
     * where the finger lifted, measured along the collapsed arc: a loop drawn
     * as the stroke's last act, with at most the flick of the lift after it.
     */
    private fun endsOnLoop(ws: KotlinGlideWorkspace): Boolean {
        val last = ws.samples - 1
        for (e in ws.loopCount - 1 downTo 0) {
            if (ws.loopScore[e] <= 0f || ws.loopKey[e] < 0) continue
            return ws.arcAt[last] - ws.arcAt[ws.loopTo[e]] <= END_LOOP_REACH
        }
        return false
    }

    /**
     * Files the wiggle recorded as loop event [e] as a pause on its key,
     * worth [Tuning.wiggleWeight] of a full stop: a rub is how a finger that
     * would rather not stop says the key is in the word. The loop event it
     * also is carries no doubling weight and is kept only so its arc is
     * collapsed and its inside left to its key.
     */
    private fun recordWiggle(e: Int, keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        val p = ws.pauseCount
        if (tuning.unclaimedDwell <= 0f || tuning.wiggleWeight <= 0f) return
        if (p >= ws.samples) return
        ws.pauseAt[p] = (ws.loopFrom[e] + ws.loopTo[e]) / 2
        ws.pauseScore[p] = tuning.wiggleWeight
        placePause(p, ws.loopX[e], ws.loopY[e], keys, ws)
        ws.pauseCount = p + 1
    }

    /**
     * Leaves the samples strictly inside every mark to the mark's own key:
     * every other key pays [Tuning.loopExclusion] more there, up to
     * [Tuning.maxPointCost].
     *
     * A loop drawn on the o of `good` sweeps over the i, the p, the k and the
     * l on its way round, and before this the alignment was free to place any
     * of their letters on those samples — so the circle that meant "the o
     * twice" pulled in words that went through the keys around it (issue
     * #337). The first and last samples of the window stay open to every key:
     * that is where the stroke arrives from the letter before and leaves for
     * the one after. A mark on no key excludes nothing.
     */
    private fun excludeNeighbours(keys: GlideKeyMap, ws: KotlinGlideWorkspace) {
        val charge = tuning.loopExclusion
        if (charge <= 0f) return
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val cap = tuning.maxPointCost
        for (e in 0 until ws.loopCount) {
            val own = ws.loopKey[e]
            if (own < 0) continue
            for (j in ws.loopFrom[e] + 1 until ws.loopTo[e]) {
                val base = j * keyCount
                for (k in 0 until keyCount) {
                    if (k != own) cost[base + k] = minOf(cap, cost[base + k] + charge)
                }
            }
        }
    }

    /**
     * Files the fine window `[from, to]` of [KotlinGlideWorkspace.loopScan] as a
     * loop event of strength [score]: its centroid, the alignment samples it
     * covers, how much of its path its chord is — what the collapse leaves of
     * it — and its key, the one nearest the centroid within
     * [Tuning.loopRadius], or -1 (see [KotlinLoopScan.nearestKey]). Returns the
     * event's index.
     */
    private fun recordLoop(from: Int, to: Int, score: Float, keys: GlideKeyMap, ws: KotlinGlideWorkspace): Int {
        val scan = ws.loopScan
        scan.measure(from, to)
        val cx = scan.centreX
        val cy = scan.centreY
        val length = scan.length
        val chord = scan.chord

        // Onto the alignment's samples: the nearest to where the window
        // starts and ends, at least one step apart so there is something to
        // collapse.
        val last = ws.samples - 1
        val toSample = scan.fineStep / ws.arcStep
        var a = minOf(last, (from * toSample + 0.5f).toInt())
        var b = minOf(last, (to * toSample + 0.5f).toInt())
        if (b <= a) {
            if (a < last) b = a + 1 else a = b - 1
        }
        val e = ws.loopCount
        ws.loopFrom[e] = a
        ws.loopTo[e] = b
        ws.loopShrink[e] = if (length > 0f) chord / length else 1f
        ws.loopX[e] = cx
        ws.loopY[e] = cy
        ws.loopScore[e] = score
        ws.loopCount = e + 1
        val own = scan.nearestKey(cx, cy, keys, tuning.loopRadiusSq)
        ws.loopKey[e] = own
        if (own >= 0 && score > ws.keyLoop[own]) ws.keyLoop[own] = score
        return e
    }

    /**
     * Rebuilds [KotlinGlideWorkspace.arcAt] with every loop's arc shrunk to its
     * chord, spread evenly over its samples, and counts the samples' worth of
     * arc that removed. A closed loop's chord is near zero, so it vanishes
     * from every word's travel alike; a loop entered on one side and left on
     * the other still credits the finger with the distance it net-moved.
     */
    private fun collapseArc(ws: KotlinGlideWorkspace) {
        val n = ws.samples
        val step = ws.arcStep
        val arc = ws.arcAt
        var removed = 0f
        var e = 0
        arc[0] = 0f
        for (j in 1 until n) {
            if (e < ws.loopCount && j > ws.loopFrom[e] && j <= ws.loopTo[e]) {
                val a = ws.loopFrom[e]
                val shrink = ws.loopShrink[e]
                arc[j] = arc[a] + (j - a) * step * shrink
                if (j == ws.loopTo[e]) {
                    removed += (j - a) * step * (1f - shrink)
                    e++
                }
            } else {
                arc[j] = arc[j - 1] + step
            }
        }
        ws.collapsedSamples = ceil(removed / step).toInt()
    }

    /**
     * What a doubled letter on [key] pays: the flat [Tuning.repeatCost] plus
     * [Tuning.unloopedRepeat], the whole of it waived to the extent the finger
     * looped there. Only a loop waives it: a pause or a wiggle says the key is
     * in the word, not that it is in it twice (issue #337). A charge dropped
     * and never a credit, so `extra` only ever grows along a chain and the
     * bound stays admissible.
     */
    private fun repeatCharge(key: Int, ws: KotlinGlideWorkspace): Float =
        (tuning.repeatCost + tuning.unloopedRepeat) * (1f - ws.keyLoop[key])

    /**
     * The loops along the stroke that none of state [s]'s *doubled* letters
     * sit on, summed by strength, times [Tuning.unclaimedLoop]. A loop is
     * claimed only by a doubling on its own key (see [recordLoop]). A repeat is
     * a state spelling the same letter as its parent one character further on;
     * the half-letter state of a surrogate pair carries its parent's letter
     * at the parent's length, which keeps it out.
     */
    private fun unclaimedLoop(s: Int, ws: KotlinGlideWorkspace): Float {
        val weight = tuning.unclaimedLoop
        if (weight <= 0f || ws.loopCount == 0) return 0f
        var charge = 0f
        for (e in 0 until ws.loopCount) {
            // A wiggle's event doubles nothing, and a loop on no key is
            // ignored by every word alike, which orders nothing.
            val own = ws.loopKey[e]
            if (ws.loopScore[e] <= 0f || own < 0) continue
            var claimed = false
            var cur = s
            while (cur >= 0 && !claimed) {
                val parent = ws.parent[cur]
                if (parent >= 0 && ws.letterCp[cur] == ws.letterCp[parent] &&
                    ws.length[cur].toInt() == ws.length[parent] + 1
                ) {
                    claimed = ws.lastKey[cur] == own
                }
                cur = parent
            }
            if (!claimed) charge += ws.loopScore[e]
        }
        return weight * charge
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
        ws: KotlinGlideWorkspace,
        shapes: GlideShapeSource?,
    ): List<Candidate> {
        val weight = tuning.shapeChannel
        // A lone candidate is rescored too, not merely reordered: its score
        // still gets compared, to the completions decoded beside it, and a
        // reading that skipped the charge because it had no rival would be
        // measured against guesses that paid it.
        if (weight <= 0.0 || ranked.isEmpty()) return ranked
        drawnShape(ws)
        normalise(ws.drawnShapeX, ws.drawnShapeY, ws.drawnShapeX, ws.drawnShapeY)
        plainShape(ws, loops = true)
        val learned = shapes?.takeIf { tuning.learnedShapeGain > 0.0 }

        val rescored = ArrayList<Candidate>(ranked.size)
        for (candidate in ranked) {
            // A look-ahead candidate's ideal path runs through letters the
            // finger has not drawn, so comparing the whole shape against the
            // whole word would ask it to account for a stroke that does not
            // exist yet. What the finger *has* drawn is the prefix, and the
            // prefix's ideal path is exactly what an ordinary reading of the
            // same letters would be measured against — so a guess pays what
            // the word it extends pays, and the two compare on the letters
            // ahead alone. The learned shapes are of whole words and have
            // nothing to say about a prefix.
            val distance = if (candidate.ahead > 0) {
                shapeDistance(candidate.word.dropLast(candidate.ahead), keys, ws)
            } else {
                shapeDistance(candidate.word, keys, ws)
                    ?.let { ideal -> learnedDistance(candidate.word, ideal, learned, ws) }
            }

            rescored.add(
                if (distance == null) {
                    candidate
                } else {
                    Candidate(
                        candidate.word,
                        candidate.score - weight * distance,
                        candidate.shapeCost,
                        candidate.tier,
                        candidate.ahead,
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
    private fun shapeDistance(word: String, keys: GlideKeyMap, ws: KotlinGlideWorkspace): Double? {
        var count = 0
        var previous = -1
        var at = 0
        while (at < word.length) {
            val codePoint = word.codePointAt(at)
            at += Character.charCount(codePoint)
            val key = keys.keyIndex(codePoint)
            // A joiner has no point on the ideal path — the same skip the walk
            // made. Answering null instead would let a hyphenated reading out
            // of the shape channel entirely, so it would pay none of the charge
            // its rivals pay and win on that alone (#230).
            if (key < 0) {
                if (GlideJoiners.isJoiner(codePoint)) continue
                return null
            }
            // Consecutive letters on one key are one point of the path: the
            // finger visited it once, however many letters it stood for.
            if (key == previous) continue
            if (count >= KotlinGlideWorkspace.MAX_IDEAL_POINTS) return null
            ws.idealX[count] = keys.keyX[key]
            ws.idealY[count] = keys.keyY[key]
            count++
            previous = key
        }
        if (count < 2) return null
        count = withAnchorLeads(count, ws)
        resamplePolyline(ws.idealX, ws.idealY, count, ws.idealShapeX, ws.idealShapeY)
        normalise(ws.idealShapeX, ws.idealShapeY, ws.idealShapeX, ws.idealShapeY)

        var sum = 0.0
        for (j in 0 until KotlinGlideWorkspace.SHAPE_POINTS) {
            val dx = ws.plainShapeX[j] - ws.idealShapeX[j]
            val dy = ws.plainShapeY[j] - ws.idealShapeY[j]
            sum += sqrt(dx * dx + dy * dy).toDouble()
        }
        return sum / KotlinGlideWorkspace.SHAPE_POINTS
    }

    /**
     * The ideal path of [count] points in [KotlinGlideWorkspace.idealX] with the
     * stroke's lead-in and tail on it, each as far as [Tuning.startSlack] and
     * [Tuning.endSlack] forgive, and its new length. The alignment lets a far
     * first or last key off the stretch between it and the touch (issue #805);
     * without this the shape channel would charge that same stretch as a
     * stroke drawn the wrong shape. Unchanged at zero slack.
     */
    private fun withAnchorLeads(count: Int, ws: KotlinGlideWorkspace): Int {
        var size = count
        val start = tuning.startSlack
        if (start > 0f && size < KotlinGlideWorkspace.MAX_IDEAL_POINTS) {
            for (p in size downTo 1) {
                ws.idealX[p] = ws.idealX[p - 1]
                ws.idealY[p] = ws.idealY[p - 1]
            }
            size++
            reachToward(1, 0, ws.pathX[0], ws.pathY[0], start, ws)
        }
        val end = tuning.endSlack
        if (end > 0f && size < KotlinGlideWorkspace.MAX_IDEAL_POINTS) {
            val last = ws.samples - 1
            reachToward(size - 1, size, ws.pathX[last], ws.pathY[last], end, ws)
            size++
        }
        return size
    }

    /** Writes ideal point [into] as point [from] moved toward ([x], [y]) by at most [reach]. */
    @Suppress("LongParameterList")
    private fun reachToward(from: Int, into: Int, x: Float, y: Float, reach: Float, ws: KotlinGlideWorkspace) {
        val fx = ws.idealX[from]
        val fy = ws.idealY[from]
        val dx = x - fx
        val dy = y - fy
        val d = sqrt(dx * dx + dy * dy)
        val t = if (d <= reach) 1f else reach / d
        ws.idealX[into] = fx + dx * t
        ws.idealY[into] = fy + dy * t
    }

    /**
     * [ideal] shortened by how this user draws [word], when [shapes] know: the
     * nearer of the ideal path and the word's learned shapes, but never more
     * than [Tuning.learnedShapeGain] nearer than the ideal.
     *
     * The store keeps shapes in key widths, so where a stroke starts counts
     * for merging and seeding; here each is centred and scaled to unit spread
     * first, exactly as the drawn stroke and the ideal path are, so the
     * distance is in the shape channel's units and comparable with [ideal].
     */
    private fun learnedDistance(
        word: String,
        ideal: Double,
        shapes: GlideShapeSource?,
        ws: KotlinGlideWorkspace,
    ): Double {
        shapes ?: return ideal
        val stored = shapes.shapesOf(word)
        if (stored.isEmpty()) return ideal
        val n = KotlinGlideWorkspace.SHAPE_POINTS
        var own = Double.MAX_VALUE
        for (shape in stored) {
            for (j in 0 until n) {
                ws.learnedShapeX[j] = shape[2 * j] / GlideShapeStore.QUANT
                ws.learnedShapeY[j] = shape[2 * j + 1] / GlideShapeStore.QUANT
            }
            normalise(ws.learnedShapeX, ws.learnedShapeY, ws.learnedShapeX, ws.learnedShapeY)
            var sum = 0.0
            for (j in 0 until n) {
                val dx = ws.drawnShapeX[j] - ws.learnedShapeX[j]
                val dy = ws.drawnShapeY[j] - ws.learnedShapeY[j]
                sum += sqrt(dx * dx + dy * dy).toDouble()
            }
            own = minOf(own, sum / n)
        }
        return maxOf(minOf(ideal, own), ideal - tuning.learnedShapeGain)
    }

    /** A key-width path as the shape store keeps one: [GlideShapeStore.QUANT] to the key width, clamped to a byte. */
    private fun quantise(xs: FloatArray, ys: FloatArray, out: ByteArray) {
        for (j in 0 until KotlinGlideWorkspace.SHAPE_POINTS) {
            out[2 * j] = (xs[j] * GlideShapeStore.QUANT).toInt().coerceIn(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT).toByte()
            out[2 * j + 1] = (ys[j] * GlideShapeStore.QUANT).toInt().coerceIn(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT).toByte()
        }
    }

    /**
     * Centres a path on its own centroid and scales it to unit spread, so that
     * comparing two paths compares their shapes and nothing else. In and out may
     * be the same arrays.
     */
    private fun normalise(xs: FloatArray, ys: FloatArray, outX: FloatArray, outY: FloatArray) {
        val n = KotlinGlideWorkspace.SHAPE_POINTS
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

    /**
     * The resampled stroke at the shape channel's own size, in key widths,
     * into [KotlinGlideWorkspace.drawnShapeX]/[KotlinGlideWorkspace.drawnShapeY]. The
     * alignment's samples are finer than a shape's, and learned shapes are
     * saved at [KotlinGlideWorkspace.SHAPE_POINTS], so the two are kept apart.
     */
    private fun drawnShape(ws: KotlinGlideWorkspace) {
        // Copied when the sizes agree, which is every short stroke: resampling
        // onto the same count moves the points a little, and that alone cost
        // short words accuracy.
        if (ws.samples == KotlinGlideWorkspace.SHAPE_POINTS) {
            ws.pathX.copyInto(ws.drawnShapeX, endIndex = KotlinGlideWorkspace.SHAPE_POINTS)
            ws.pathY.copyInto(ws.drawnShapeY, endIndex = KotlinGlideWorkspace.SHAPE_POINTS)
        } else {
            resamplePolyline(ws.pathX, ws.pathY, ws.samples, ws.drawnShapeX, ws.drawnShapeY)
        }
    }

    /**
     * Fills [KotlinGlideWorkspace.plainShapeX]/[KotlinGlideWorkspace.plainShapeY]
     * from the normalised drawn shape, with the stroke's doubling loops cut to
     * their chords when [loops] says this stroke's loops have been built.
     * Only the ideal paths are compared against it: no word's ideal path has
     * a loop in it, so a circle left in the shape matched the words that
     * zigzag where it was drawn better than the word it doubled, and `need`
     * lost to `needed`, `bee` to `bede` (#886). The learned shapes keep the
     * raw stroke, since they were stored from raw strokes.
     */
    private fun plainShape(ws: KotlinGlideWorkspace, loops: Boolean) {
        if (loops && shapeWithoutLoops(ws)) {
            normalise(ws.plainShapeX, ws.plainShapeY, ws.plainShapeX, ws.plainShapeY)
        } else {
            ws.drawnShapeX.copyInto(ws.plainShapeX)
            ws.drawnShapeY.copyInto(ws.plainShapeY)
        }
    }

    /**
     * The stroke resampled into the plain shape with every doubling loop's
     * inside left out, as the arc collapses it ([collapseArc]); false, with
     * nothing written, when the stroke has none.
     */
    private fun shapeWithoutLoops(ws: KotlinGlideWorkspace): Boolean {
        val n = ws.samples
        var cut = false
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var count = 0
        var e = 0
        for (j in 0 until n) {
            while (e < ws.loopCount && j >= ws.loopTo[e]) e++
            val inside = e < ws.loopCount && ws.loopScore[e] > 0f && ws.loopKey[e] >= 0 &&
                j > ws.loopFrom[e] && j < ws.loopTo[e]
            if (inside) {
                cut = true
                continue
            }
            xs[count] = ws.pathX[j]
            ys[count] = ws.pathY[j]
            count++
        }
        if (!cut || count < 2) return false
        resamplePolyline(xs, ys, count, ws.plainShapeX, ws.plainShapeY)
        return true
    }

    /** Resamples a [count]-point polyline to [KotlinGlideWorkspace.SHAPE_POINTS] by arc length. */
    private fun resamplePolyline(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        outX: FloatArray,
        outY: FloatArray,
    ) {
        val n = KotlinGlideWorkspace.SHAPE_POINTS
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

    /** Which keys the anchor at ([x], [y]) could have meant, within [radiusSq]. */
    private fun anchorMask(
        x: Float,
        y: Float,
        keys: GlideKeyMap,
        out: BooleanArray,
        radiusSq: Float,
    ) {
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            out[k] = dx * dx + dy * dy <= radiusSq
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

    /** The [k]th best score in [results], sorted in the workspace's scratch rather than a fresh array per emit. */
    private fun kthBest(results: HashMap<String, Candidate>, k: Int, ws: KotlinGlideWorkspace): Double {
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

        /** Strokes shorter than this are taps, in key widths. */
        private const val MIN_STROKE_LENGTH = 0.5f

        private const val MIN_SAMPLES = 3
        private const val MIN_WORD_LENGTH = 2

        /** Most joins in one reading: three parts, and no further. */
        private const val MAX_JOINS = 2

        /**
         * Letters a stroke must have spelled before the decoder will guess what
         * it is going to spell next.
         *
         * Three, because two is not evidence. A stroke that has reached its
         * second letter has been through one direction change at most, and
         * every long word starting with those two letters fits it about as
         * well — guessing there is guessing from frequency alone, dressed up as
         * a reading of a gesture.
         */
        private const val MIN_LOOKAHEAD_PREFIX = 3

        /**
         * Letters the walk spells at most. 24 until issue #966, which left a
         * 26-letter compound unreadable; `wm-engine`'s `MAX_WORD_LENGTH`.
         */
        private const val MAX_WORD_LENGTH = 32

        /** Below this spread a path is a dot, and dividing by its size is noise. */
        private const val MIN_SHAPE_SPREAD = 1e-4f

        private const val SHAPE_BYTE_LIMIT = 127

        /** How near a key a pause has to happen to count as a pause on it, in key widths. */
        private const val DWELL_RADIUS = 0.8f

        /**
         * How much farther than the nearest key another key may sit from a
         * pause and still claim it, in key widths: enough for a stop drawn on
         * the line between two keys to belong to both, not enough for the key
         * next door to answer for a stop a little off centre.
         */
        private const val DWELL_TIE = 0.25f

        /**
         * Most stroke a loop may have after it, in key widths, and still be
         * the stroke's closing mark: the lift's own flick, not a move on to
         * another key, which is a key width away.
         */
        private const val END_LOOP_REACH = 0.5f

        /**
         * Runaway backstop; floor pruning ends healthy walks far earlier.
         *
         * It was 2000. On 2,588 German swipes with the full list and the
         * 8,192-state frontier, 4,000 pops read 75.81 top-1 against 75.46;
         * with the 12,288-state frontier it read 76.31. The extra pops only
         * run where the walk would otherwise have stopped.
         */
        const val MAX_POPS = 4000

        /** Rank depth kept internally, so reranking has something to reorder. */
        const val RESULT_K = 8

        private const val EPS = 1e-9

        private fun ln1p(v: Int): Double = ln(1.0 + v)

        /**
         * The frequency a compound's language-model term is read at: the
         * least of its finished parts' ([cap], [KotlinGlideWorkspace.NO_PART]
         * for none) and [f], the current part's or its subtree's. [f] itself
         * for a plain word, so the plain decode's arithmetic is untouched.
         */
        private fun lmCap(cap: Int, f: Int): Int = if (f < cap) f else cap
    }
}

/**
 * `glide::Marks`: the loops a stroke draws, each as its key and score (a
 * doubled letter there), and whether it closes on one.
 */
data class KotlinGlideMarks(val loops: List<Pair<Int, Float>>, val endsOnLoop: Boolean) {

    companion object {
        /** The keys [word] visits in order, a letter typed twice in a row visiting once; -1 for no key. */
        fun visits(word: String, keys: GlideKeyMap): List<Int> {
            val out = ArrayList<Int>()
            var at = 0
            while (at < word.length) {
                val cp = word.codePointAt(at)
                at += Character.charCount(cp)
                val key = keys.keyIndex(cp)
                if (out.lastOrNull() != key || key < 0) out.add(key)
            }
            return out
        }

        /** Whether [word] types a letter twice in a row on [key]. */
        fun doubles(word: String, key: Int, keys: GlideKeyMap): Boolean {
            var at = 0
            var previous = -1
            while (at < word.length) {
                val cp = word.codePointAt(at)
                at += Character.charCount(cp)
                if (cp == previous && keys.keyIndex(cp) == key) return true
                previous = cp
            }
            return false
        }
    }
}
