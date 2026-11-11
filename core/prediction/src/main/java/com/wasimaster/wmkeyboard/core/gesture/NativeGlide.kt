package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.NativeFuzzySearch
import com.wasimaster.wmkeyboard.core.prediction.PredictionNative

/**
 * [GlideBeam], [DuetBeam] and [CtcGlideBeam] in Rust (`wm-engine`'s `glide`), when
 * every trie they decode against has a Rust twin, the same test
 * [NativeFuzzySearch] makes.
 *
 * The user's learned shapes stay in Kotlin, in [GlideShapeSource], so a
 * decode that reads them is two crossings on one thread: the walk, which
 * says which words the shape channel will score, then the finish with those
 * words' shapes and, when seeding is on, the words the store finds near the
 * stroke. Without them it is one.
 */
internal object NativeGlide {

    private val NO_SHAPES = emptyArray<ByteArray>()

    /** 0 when the loaded library predates `glideTuningVersion`, keeping the unversioned array. */
    private val tuningVersion: Int by lazy {
        runCatching { PredictionNative.glideTuningVersion() }.getOrDefault(0)
    }

    /** [tuning] in the order `glide.rs` reads it. */
    fun tuningArray(tuning: GlideBeam.Tuning): DoubleArray = with(tuning) {
        doubleArrayOf(
            sigma.toDouble(), maxPointCost.toDouble(), gapWeight.toDouble(), gapWindow.toDouble(),
            shapeWeight, repeatCost.toDouble(), unloopedRepeat.toDouble(), dwellFull.toDouble(),
            unclaimedDwell.toDouble(), loopExtent.toDouble(), loopMinArc.toDouble(), loopRadius.toDouble(),
            wiggleExtent.toDouble(), wiggleWeight.toDouble(), loopExclusion.toDouble(), unclaimedLoop.toDouble(),
            learnedShapeGain, if (shapeSeeding) 1.0 else 0.0, shapeChannel, startRadius.toDouble(),
            endRadius.toDouble(), nearRadius.toDouble(), vocabularyRank.toDouble(), lookAheadCost,
            lookAheadBudget.toDouble(), joinerCost.toDouble(), freqWeight, joinCost,
            joinMinPart.toDouble(), joinPartPenalty,
            maxPops.toDouble(), maxStates.toDouble(),
        )
    }

    /** [tuning] with the `[version, count, fields...]` envelope when the library reads it. */
    private fun tuningValues(tuning: GlideBeam.Tuning): DoubleArray {
        val fields = tuningArray(tuning)
        return if (tuningVersion >= 2) doubleArrayOf(2.0, fields.size.toDouble()) + fields else fields
    }

    private class Path(path: List<GesturePoint>) {
        val xs = FloatArray(path.size) { path[it].x }
        val ys = FloatArray(path.size) { path[it].y }
        val ts = LongArray(path.size) { path[it].t }
    }

    /**
     * [GlideBeam.decode] natively, or null when a source has no twin and the
     * caller should decode in Kotlin.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        tuning: GlideBeam.Tuning,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        shapes: GlideShapeSource?,
        lookAhead: Int,
        openHead: Int = 0,
        openTail: Int = 0,
    ): List<GlideBeam.Candidate>? {
        val s = Sources(sources, tuning.vocabularyRank)
        if (!s.complete) return null
        // A stroke with an open end has a walk to or from the spacebar in
        // it that no stored shape has (#968): the shape channel stands down,
        // in the decoder and in the seeding here.
        val open = openHead > 0 || openTail > 0
        val learned = !open && shapes != null && tuning.learnedShapeGain > 0.0 && tuning.shapeChannel > 0.0
        val seeding = !open && shapes != null && tuning.shapeSeeding && tuning.learnedShapeGain > 0.0
        val stroke = Path(path)
        val walked = PredictionNative.glideDecode(
            tuningValues(tuning), stroke.xs, stroke.ys, stroke.ts, keys.nativeHandle, keyWidth,
            s.handles.copyOf(s.count), s.kinds.copyOf(s.count), s.weights.copyOf(s.count), s.tiers.copyOf(s.count),
            s.floors.copyOf(s.count), limit, lookAhead, if (learned || seeding) 1 else 0, openHead, openTail,
        ) ?: return emptyList()
        if (!learned && !seeding) return candidates(walked)
        @Suppress("UNCHECKED_CAST")
        val words = walked[0] as Array<String>
        val ints = walked[2] as IntArray
        val readCount = ints[ints.size - 1]
        val stored = Array(words.size) { i ->
            if (learned && i < readCount) shapes!!.shapesOf(words[i]).toTypedArray() else NO_SHAPES
        }
        val seeds = if (seeding) {
            shapes!!.wordsNear(walked[3] as ByteArray, radius = SEED_RADIUS, limit = limit).toTypedArray()
        } else {
            null
        }
        return candidates(PredictionNative.glideFinish(words, stored, seeds))
    }

    /** The sources as `glide.rs` takes them, or null when one has no Rust twin. */
    private class Sources(sources: List<FuzzyBeamSearch.WalkSource>, vocabularyRank: Int) {
        val handles = LongArray(sources.size)
        val kinds = IntArray(sources.size)
        val weights = DoubleArray(sources.size)
        val tiers = IntArray(sources.size)
        val floors = IntArray(sources.size)
        var count = 0
        var complete = true

        init {
            val scratch = ChildBuffer()
            for (src in sources) {
                val walker = src.walker
                val handle = NativeFuzzySearch.handleOf(walker, scratch)
                if (handle == NativeFuzzySearch.SKIP) continue
                if (handle == 0L) {
                    complete = false
                    break
                }
                handles[count] = handle
                kinds[count] = NativeFuzzySearch.kindOf(walker)
                weights[count] = src.logWeight
                tiers[count] = if (src.tier == FuzzyBeamSearch.Tier.USER) 1 else 0
                floors[count] = walker.frequencyAtRank(vocabularyRank)
                count++
            }
        }
    }

    /**
     * [CtcGlideBeam.decode] natively, or null when a source has no twin. Two
     * crossings when [stroke] carries learned shapes, as [decode]: the walk
     * names the readings, then the finish takes their shapes.
     */
    @Suppress("LongParameterList")
    fun ctcDecode(
        scoring: CtcGlideBeam.Scoring,
        emissions: SwipeEmissions,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        width: Int,
        vocabularyRank: Int,
        stroke: CtcGlideBeam.Stroke?,
        lookAhead: Int,
    ): List<GlideBeam.Candidate>? {
        val s = Sources(sources, vocabularyRank)
        if (!s.complete) return null
        val weights = with(scoring) {
            doubleArrayOf(
                lengthNorm.toDouble(), lengthBonus.toDouble(), freqWeight.toDouble(),
                pruneNorm.toDouble(), pruneBonus.toDouble(), lookAheadCost.toDouble(),
                loopWeight.toDouble(), shapeWeight.toDouble(),
            )
        }
        val shapes = stroke?.shapes?.takeIf {
            scoring.shapeWeight > 0f && stroke.tuning.learnedShapeGain > 0.0
        }
        val path = Path(stroke?.path.orEmpty())
        val walked = PredictionNative.ctcDecode(
            emissions.logProbs, emissions.steps, s.handles.copyOf(s.count), s.kinds.copyOf(s.count),
            s.weights.copyOf(s.count), s.tiers.copyOf(s.count), s.floors.copyOf(s.count),
            limit, width, weights,
            stroke?.let { tuningArray(it.tuning) } ?: DoubleArray(0), path.xs, path.ys, path.ts,
            stroke?.keys?.nativeHandle ?: 0L, stroke?.keyWidth ?: 0f, lookAhead, if (shapes != null) 1 else 0,
        )
        if (shapes == null) return candidates(walked)
        @Suppress("UNCHECKED_CAST")
        val words = walked[0] as Array<String>
        val ints = walked[2] as IntArray
        val readCount = ints[ints.size - 1]
        val stored = Array(words.size) { i ->
            if (i < readCount) shapes.shapesOf(words[i]).toTypedArray() else NO_SHAPES
        }
        return candidates(PredictionNative.ctcFinish(words, stored))
    }

    /** [DuetBeam.decode] natively, or null when a source has no twin. */
    @Suppress("LongParameterList")
    fun duetDecode(
        tuning: GlideBeam.Tuning,
        duet: DuetBeam.Duet,
        tokens: List<GlideToken>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        partial: Boolean,
    ): List<GlideBeam.Candidate>? {
        val s = Sources(sources, tuning.vocabularyRank)
        if (!s.complete) return null
        val kinds = IntArray(tokens.size)
        val sizes = IntArray(tokens.size)
        val points = ArrayList<GesturePoint>()
        for ((i, token) in tokens.withIndex()) {
            when (token) {
                is GlideToken.Tap -> {
                    kinds[i] = 0
                    sizes[i] = 1
                    points.add(GesturePoint(token.x, token.y, token.t))
                }
                is GlideToken.Swipe -> {
                    kinds[i] = 1
                    sizes[i] = token.points.size
                    points.addAll(token.points)
                }
            }
        }
        val stroke = Path(points)
        val duetValues = with(duet) {
            doubleArrayOf(
                orderSlackMs.toDouble(), tapWindowMs.toDouble(), tapSigma.toDouble(), tapNearRadius.toDouble(),
                tapMaxCost.toDouble(), tapRepeatCost.toDouble(), samplesPerKey.toDouble(), minSamples.toDouble(),
            )
        }
        return candidates(
            PredictionNative.duetDecode(
                tuningArray(tuning), duetValues, kinds, sizes, stroke.xs, stroke.ys, stroke.ts,
                keys.nativeHandle, keyWidth, s.handles.copyOf(s.count), s.kinds.copyOf(s.count),
                s.weights.copyOf(s.count), s.tiers.copyOf(s.count), s.floors.copyOf(s.count), limit, partial,
            ),
        )
    }

    private fun candidates(result: Array<Any>): List<GlideBeam.Candidate> {
        @Suppress("UNCHECKED_CAST")
        val words = result[0] as Array<String>
        val doubles = result[1] as DoubleArray
        val ints = result[2] as IntArray
        return List(words.size) { i ->
            GlideBeam.Candidate(
                word = words[i],
                score = doubles[2 * i],
                shapeCost = doubles[2 * i + 1],
                tier = if (ints[2 * i] == 1) FuzzyBeamSearch.Tier.USER else FuzzyBeamSearch.Tier.DICTIONARY,
                ahead = ints[2 * i + 1],
            )
        }
    }

    /** [GlideBeam.align] natively. */
    @Suppress("LongParameterList")
    fun align(
        tuningArray: DoubleArray,
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
    ): GlideBeam.Alignment? {
        val stroke = Path(path)
        val floats = FloatArray(2 * GlideWorkspace.MAX_IDEAL_POINTS)
        val flat = PredictionNative.glideAlign(
            tuningArray, word, stroke.xs, stroke.ys, stroke.ts, keys.nativeHandle, keyWidth, floats,
        ) ?: return null
        val count = (flat.size - 1) / 3
        return GlideBeam.Alignment(
            keys = flat.copyOfRange(0, count),
            x = floats.copyOfRange(0, count),
            y = floats.copyOfRange(count, 2 * count),
            samples = flat.copyOfRange(count, 2 * count),
            chars = flat.copyOfRange(2 * count, 3 * count),
            sampleCount = flat[3 * count],
        )
    }

    /** [GlideBeam.sampleShape] natively. */
    fun sampleShape(path: List<GesturePoint>, keyWidth: Float): ByteArray? {
        val stroke = Path(path)
        return PredictionNative.glideSampleShape(stroke.xs, stroke.ys, stroke.ts, keyWidth)
    }

    /** The radius [GlideBeam.decode]'s seeding asks the shape store for. */
    const val SEED_RADIUS = 0.35f
}
