package com.wasimaster.wmkeyboard.core.gesture.reference

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer
/**
 * Pooled state store, alignment columns and index heap for [GlideBeam].
 *
 * One instance per decoding thread. Everything a decode needs is allocated on
 * first use and reused forever after, so a swipe costs no garbage beyond the
 * words it emits — the same contract `BeamWorkspace` holds for the typing beam,
 * and it matters more here: a preview decodes several times per stroke.
 *
 * The one thing this workspace has that `BeamWorkspace` does not is [cols], the
 * dynamic-programming columns. Each state owns [SAMPLE_POINTS] floats recording,
 * for every position along the drawn path, the best cost of having spelled this
 * state's prefix by that position. That is what makes the search exact rather
 * than greedy, and it is also why [MAX_STATES] exists: a column is 192 bytes, so
 * an unbounded frontier would be megabytes per decode rather than a fixed
 * [CAPACITY_BYTES_NOTE].
 */
class KotlinGlideWorkspace {

    var node = IntArray(INITIAL); private set
    var parent = IntArray(INITIAL); private set
    var viaLabel = CharArray(INITIAL); private set

    /**
     * Code point of the last whole letter spelled on this path, 0 at the root.
     * [viaLabel] is one trie edge, and a letter outside the BMP is two edges —
     * the high surrogate, then the low — so a state that has consumed only the
     * first half carries its parent's letter here, and the doubled-letter test
     * compares letters rather than halves.
     */
    var letterCp = IntArray(INITIAL); private set

    /** Key index the last consumed character sits on; -1 at the root. */
    var lastKey = IntArray(INITIAL); private set

    /** Characters spelled so far, repeats included. */
    var length = ShortArray(INITIAL); private set

    /** Cost accumulated outside the alignment: doubled letters, for now. */
    var extra = FloatArray(INITIAL); private set

    /**
     * The least alignment cost any word through this state can finish on: the
     * lowest of its column plus [remain] from each sample on. What the bound
     * subtracts, so it must never overstate what a word will really pay.
     */
    var floorCost = FloatArray(INITIAL); private set

    var bound = DoubleArray(INITIAL); private set

    /**
     * Compounds (#966): the least frequency among the parts this state has
     * already finished ([NO_PART] before any join), the length at which the
     * current part began, and how many joins the chain has made. Copied
     * from the parent on every [push]; the walk sets them where it joins.
     */
    var partFreq = IntArray(INITIAL); private set
    var partFrom = IntArray(INITIAL); private set
    var joins = IntArray(INITIAL); private set

    /** The `joinPartPenalty` nats the parts this state joined past have earned; 0.0 before any join. */
    var joinPenalty = DoubleArray(INITIAL); private set

    /**
     * Samples the current stroke was resampled to: [SHORT_SAMPLES], or
     * [SAMPLE_POINTS] for a stroke of [LONG_STROKE] key widths or more. Every
     * per-sample array holds [SAMPLE_POINTS]; a decode reads the first
     * [samples] of each, and a state's column is [samples] long.
     */
    var samples = SHORT_SAMPLES

    /** [samples] alignment costs per state, laid end to end. */
    var cols = FloatArray(INITIAL * SAMPLE_POINTS); private set

    private var size = 0
    private var heap = IntArray(INITIAL)
    var heapSize = 0; private set

    /** Resampled path: key-width coordinates and the sample clock. */
    val pathX = FloatArray(SAMPLE_POINTS)
    val pathY = FloatArray(SAMPLE_POINTS)
    val pathT = LongArray(SAMPLE_POINTS)

    /**
     * Arc length between consecutive samples, in key widths. Because the
     * resampling is by arc length this single number converts a gap in sample
     * indices into a distance the finger actually travelled, which is the whole
     * basis of the decoder's second cost term.
     */
    var arcStep = 0f

    /** `pointCost[i * keyCount + k]`, filled per decode by [GlideBeam]. */
    var pointCost = FloatArray(SAMPLE_POINTS * INITIAL_KEYS); private set

    /**
     * `remain[i * keyCount + k]`: the least the rest of the stroke can cost a
     * word whose latest letter sits on key `k` at sample `i`, over every key
     * sequence the grid allows and ignoring the dictionary. Zero at the last
     * sample for a key the stroke can end on. Filled per decode by [GlideBeam].
     */
    var remain = FloatArray(SAMPLE_POINTS * INITIAL_KEYS); private set

    /** The keys the stroke passes near, as a list, for the passes that only care about those. */
    var nearList = IntArray(INITIAL_KEYS); private set
    var nearCount = 0

    /**
     * The pauses, as events: the sample each hold sits on, how still the
     * finger was there, 0 to 1, where it was in key widths, and the squared
     * reach within which a key claims it. One event per hold however many
     * samples it stretches over, and one per wiggle when wiggles are read.
     * They answer the question a finished word is charged for — "which pauses
     * did it fail to account for".
     */
    val pauseAt = IntArray(SAMPLE_POINTS)
    val pauseScore = FloatArray(SAMPLE_POINTS)
    val pauseX = FloatArray(SAMPLE_POINTS)
    val pauseY = FloatArray(SAMPLE_POINTS)
    val pauseReachSq = FloatArray(SAMPLE_POINTS)
    var pauseCount = 0

    /**
     * Effective arc at each sample, in key widths: `j * arcStep`, except that
     * inside a loop window the arc is collapsed to the window's chord. The
     * alignment reads the finger's travel between two samples off this rather
     * than off their index gap, so a loop drawn on a key costs no word any
     * travel — see [GlideBeam.Tuning.loopExtent].
     */
    val arcAt = FloatArray(SAMPLE_POINTS)

    /** Samples' worth of arc the loops removed; widens the alignment band's far edge. */
    var collapsedSamples = 0

    /**
     * The places the finger went round on itself — a loop or a back-and-forth
     * on one key — as events: the window of samples each covers, its centroid
     * in key widths, the one key it is a mark on (-1 for none), and how much
     * of a doubled-letter mark it is, 0 to 1 (0 for a wiggle, which files a
     * pause instead).
     */
    val loopFrom = IntArray(SAMPLE_POINTS)
    val loopTo = IntArray(SAMPLE_POINTS)
    val loopX = FloatArray(SAMPLE_POINTS)
    val loopY = FloatArray(SAMPLE_POINTS)
    val loopScore = FloatArray(SAMPLE_POINTS)
    val loopKey = IntArray(SAMPLE_POINTS)
    /** How much of each loop's path its chord is — what the collapse leaves of it. */
    val loopShrink = FloatArray(SAMPLE_POINTS)
    var loopCount = 0

    /** The loop search's fine resample and the windows it finds; see [KotlinLoopScan]. */
    val loopScan = KotlinLoopScan()

    /** Per key, the strongest loop event that key is the mark of. */
    var keyLoop = FloatArray(INITIAL_KEYS); private set

    /** Keys the drawn path passes close enough to be spelling. */
    var nearKey = BooleanArray(INITIAL_KEYS); private set

    /** Keys close enough to the stroke's first sample to start a word. */
    var startKey = BooleanArray(INITIAL_KEYS); private set

    /** Keys close enough to the stroke's last sample to end one. */
    var endKey = BooleanArray(INITIAL_KEYS); private set

    val children = com.wasimaster.wmkeyboard.core.prediction.ChildBuffer()

    /** The look-ahead's own child buffer, so its descent never shares [children] with the walk. */
    val lookAheadChildren = com.wasimaster.wmkeyboard.core.prediction.ChildBuffer()

    /** Where the walk sorts its results' scores to find the floor, reused across emits. */
    var scoreScratch = DoubleArray(INITIAL)

    /**
     * Where a candidate column is built before anyone knows whether it is worth
     * keeping. The bound that decides that is read off the column itself, so
     * the alternative would be allocating a state to hold a column we then
     * throw away — which at this state count is most of them.
     */
    val scratch = FloatArray(SAMPLE_POINTS)

    /**
     * The drawn stroke and a candidate's ideal path, each translated to its own
     * centroid and scaled to its own size, for the shape rescore. Held here
     * rather than allocated per candidate: the rescore runs over a handful of
     * words on every preview event.
     */
    val drawnShapeX = FloatArray(SHAPE_POINTS)
    val drawnShapeY = FloatArray(SHAPE_POINTS)

    /**
     * [drawnShapeX]/[drawnShapeY] with every doubling loop cut to its chord,
     * normalised the same way: what a word's ideal path, which never loops,
     * is compared against. The same as the drawn shape on a stroke with none.
     */
    val plainShapeX = FloatArray(SHAPE_POINTS)
    val plainShapeY = FloatArray(SHAPE_POINTS)

    /** The drawn shape as the shape store keeps one, for comparing against learned shapes. */
    val drawnShape8 = ByteArray(2 * SHAPE_POINTS)
    val idealShapeX = FloatArray(SHAPE_POINTS)
    val idealShapeY = FloatArray(SHAPE_POINTS)

    /** A learned shape of a candidate word, normalised like the drawn one. */
    val learnedShapeX = FloatArray(SHAPE_POINTS)
    val learnedShapeY = FloatArray(SHAPE_POINTS)

    /** A candidate word's key centres, before resampling. */
    val idealX = FloatArray(MAX_IDEAL_POINTS)
    val idealY = FloatArray(MAX_IDEAL_POINTS)

    private val sb = StringBuilder(32)

    /** True once the state pool is full — the decode is running on a backstop. */
    var saturated = false; private set

    fun reset() {
        size = 0
        heapSize = 0
        saturated = false
    }

    /** Grows the per-key scratch to cover a grid of [keyCount] keys. */
    fun prepareKeys(keyCount: Int) {
        if (nearKey.size < keyCount) {
            nearKey = BooleanArray(keyCount)
            startKey = BooleanArray(keyCount)
            endKey = BooleanArray(keyCount)
            keyLoop = FloatArray(keyCount)
        } else {
            nearKey.fill(false, 0, keyCount)
            startKey.fill(false, 0, keyCount)
            endKey.fill(false, 0, keyCount)
            keyLoop.fill(0f, 0, keyCount)
        }
        pauseCount = 0
        loopCount = 0
        val needed = SAMPLE_POINTS * keyCount
        if (pointCost.size < needed) pointCost = FloatArray(needed)
        if (remain.size < needed) remain = FloatArray(needed)
        if (nearList.size < keyCount) nearList = IntArray(keyCount)
        nearCount = 0
    }

    /**
     * Appends a state and schedules it for expansion, returning its id — or -1
     * when the pool is full, which the caller treats as "do not explore this".
     */
    @Suppress("LongParameterList")
    fun push(
        node: Int,
        parent: Int,
        viaLabel: Char,
        lastKey: Int,
        length: Int,
        extra: Float,
        floorCost: Float,
        bound: Double,
        letterCp: Int,
    ): Int {
        if (size >= MAX_STATES) {
            saturated = true
            return -1
        }
        ensure(size + 1)
        val id = size++
        this.node[id] = node
        this.parent[id] = parent
        this.viaLabel[id] = viaLabel
        this.letterCp[id] = letterCp
        this.lastKey[id] = lastKey
        this.length[id] = length.toShort()
        this.extra[id] = extra
        this.floorCost[id] = floorCost
        this.bound[id] = bound
        if (parent >= 0) {
            partFreq[id] = partFreq[parent]
            partFrom[id] = partFrom[parent]
            joins[id] = joins[parent]
            joinPenalty[id] = joinPenalty[parent]
        } else {
            partFreq[id] = NO_PART
            partFrom[id] = 0
            joins[id] = 0
            joinPenalty[id] = 0.0
        }
        heapPush(id)
        return id
    }

    /** Files state [id] as the start of a compound's next part: `wm-engine`'s walk writes the three fields in place. */
    fun join(id: Int, partFreq: Int, partFrom: Int, joins: Int, joinPenalty: Double) {
        this.partFreq[id] = partFreq
        this.partFrom[id] = partFrom
        this.joins[id] = joins
        this.joinPenalty[id] = joinPenalty
    }

    /** Start of state [id]'s column within [cols]. */
    fun columnOf(id: Int): Int = id * samples

    /** Commits [scratch] as state [id]'s column. */
    fun storeScratch(id: Int) {
        System.arraycopy(scratch, 0, cols, id * samples, samples)
    }

    /** Copies one state's column onto another — a repeated letter reuses it. */
    fun copyColumn(from: Int, to: Int) {
        System.arraycopy(cols, from * samples, cols, to * samples, samples)
    }

    fun popBest(): Int {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            siftDown(0)
        }
        return top
    }

    /**
     * Rebuilds the word for state [s] from its parent chain.
     *
     * The chain is walked leaf to root and reversed by hand, one UTF-16 unit at
     * a time. `StringBuilder.reverse()` will not do: it keeps any high-low pair
     * it finds together, and the leaf-first sequence of a word outside the BMP
     * is full of *low-high* pairs that it reads as the pairs of the next letter
     * along — every Osage or Warang Citi word came out with a stray surrogate
     * at each end and its letters' halves swapped.
     */
    fun materialize(s: Int): String {
        sb.setLength(0)
        var cur = s
        while (cur >= 0) {
            val label = viaLabel[cur]
            if (label != NO_LABEL) sb.append(label)
            cur = parent[cur]
        }
        val n = sb.length
        val chars = CharArray(n)
        for (i in 0 until n) chars[i] = sb[n - 1 - i]
        return String(chars)
    }

    private fun ensure(needed: Int) {
        if (node.size >= needed) return
        var capacity = node.size
        while (capacity < needed) capacity *= 2
        capacity = minOf(capacity, MAX_STATES)
        node = node.copyOf(capacity)
        parent = parent.copyOf(capacity)
        viaLabel = viaLabel.copyOf(capacity)
        letterCp = letterCp.copyOf(capacity)
        lastKey = lastKey.copyOf(capacity)
        length = length.copyOf(capacity)
        extra = extra.copyOf(capacity)
        floorCost = floorCost.copyOf(capacity)
        bound = bound.copyOf(capacity)
        partFreq = partFreq.copyOf(capacity)
        partFrom = partFrom.copyOf(capacity)
        joins = joins.copyOf(capacity)
        joinPenalty = joinPenalty.copyOf(capacity)
        cols = cols.copyOf(capacity * SAMPLE_POINTS)
    }

    private fun heapPush(id: Int) {
        if (heapSize == heap.size) heap = heap.copyOf(heap.size * 2)
        heap[heapSize] = id
        var i = heapSize++
        while (i > 0) {
            val up = (i - 1) ushr 1
            if (bound[heap[up]] >= bound[heap[i]]) break
            val tmp = heap[up]
            heap[up] = heap[i]
            heap[i] = tmp
            i = up
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = i * 2 + 1
            if (left >= heapSize) break
            val right = left + 1
            var best = left
            if (right < heapSize && bound[heap[right]] > bound[heap[left]]) best = right
            if (bound[heap[i]] >= bound[heap[best]]) break
            val tmp = heap[i]
            heap[i] = heap[best]
            heap[best] = tmp
            i = best
        }
    }

    companion object {
        /**
         * Path samples the drawn stroke is resampled to. The alignment is
         * O(SAMPLE_POINTS) per trie edge and a column is 4 bytes a sample, so
         * this is the decoder's one real size dial. This is the most a
         * stroke gets; most get [SHORT_SAMPLES].
         *
         * 48 for everything left a long word two and a half samples a key:
         * with a finger's rounded corners, "internationalization" decoded
         * from 5 of 30 noisy strokes, the walk spending its budget on short
         * readings before a twenty-letter one fit. At 64 it decodes from 26.
         * 64 for everything also cost about 40% more CPU on every stroke,
         * so only a stroke of [LONG_STROKE] key widths or more gets it.
         *
         * 64 was also the most any stroke got, and a 26-letter compound
         * crosses the board twice as far as a 13-letter one (issue #966):
         * past [LONG_STROKE] a stroke now earns [SAMPLES_PER_LETTER] more
         * samples for every [LETTER_ARC] it runs on, up to this. See
         * [samplesFor].
         */
        const val SAMPLE_POINTS = 96

        /** Samples a stroke shorter than [LONG_STROKE] is resampled to. */
        const val SHORT_SAMPLES = 48

        /** Stroke length, in key widths, from which a stroke gets [LONG_SAMPLES] samples. */
        const val LONG_STROKE = 40f

        /** Samples a stroke of exactly [LONG_STROKE] gets. */
        const val LONG_SAMPLES = 64

        /**
         * A letter's worth of stroke, in key widths: the mean key-to-key
         * step over the English list, drawn centre to centre, is 2.95.
         */
        const val LETTER_ARC = 3f

        /** Samples each [LETTER_ARC] past [LONG_STROKE] adds. */
        const val SAMPLES_PER_LETTER = 2

        /**
         * How many samples a stroke of [arc] key widths is resampled to:
         * [SHORT_SAMPLES] under [LONG_STROKE], [LONG_SAMPLES] at it, and
         * [SAMPLES_PER_LETTER] more for every [LETTER_ARC] beyond, up to
         * [SAMPLE_POINTS] (issue #966 §5.3). The letter count is read off
         * the arc because the stroke is resampled before any word is known.
         * Every stroke under [LONG_STROKE] is sampled exactly as before.
         * `wm-engine`'s `glide::workspace::samples_for`, to the bit.
         */
        fun samplesFor(arc: Float): Int =
            if (arc < LONG_STROKE) {
                SHORT_SAMPLES
            } else {
                val letters = ((arc - LONG_STROKE) / LETTER_ARC).toInt()
                minOf(SAMPLE_POINTS, LONG_SAMPLES + SAMPLES_PER_LETTER * letters)
            }

        /**
         * Samples a *shape* has, apart from the alignment's: the learned
         * shapes are saved at this size, and a stored shape of another size
         * is skipped, so changing it would forget every shape a user has
         * taught. The shape channel resamples the stroke down to it.
         */
        const val SHAPE_POINTS = 48

        /**
         * Frontier cap, worth up to ~4.5 MB of columns once the pool has
         * grown into it on the longest stroke (MAX_STATES × 96 samples ×
         * 4 bytes); the vectors grow with the pool, so only a stroke that
         * needs them pays. Best-first order means the states that matter are
         * expanded first, so hitting this is a backstop rather than a bound
         * anyone should reach; [saturated] records when it happened so the
         * harness can see it rather than reading a quietly worse number.
         *
         * It was 3072, and on a long word it was reached every time: a German
         * stroke of fifteen letters or more saturated the pool on every one of
         * 300 synthetic strokes, and the word drawn was never pushed (issue
         * #714). At 8192 none of them do. At the later 4,000-pop budget the
         * real German swipes reached 8192 again: 12,288 lifted top-1 from
         * 75.81 to 76.31 and top-64 coverage from 87.13 % to 87.75 %; 16,384
         * gained nothing more on the same set.
         */
        const val MAX_STATES = 12288

        /**
         * Longest key sequence a candidate's ideal path can have: a word of
         * `KotlinGlideBeam.MAX_WORD_LENGTH` letters visits at most that many
         * keys, plus the two anchor leads the shape channel adds.
         */
        const val MAX_IDEAL_POINTS = 34

        private const val INITIAL = 256
        private const val INITIAL_KEYS = 48

        /** Stands in for "unreachable" in a column without overflowing on add. */
        const val UNREACHABLE = Float.MAX_VALUE / 4f

        /** Sentinel for the root, which consumed no trie edge. */
        val NO_LABEL: Char = 0.toChar()

        /** [partFreq] of a state that has joined nothing: no cap on the frequency. */
        const val NO_PART = Int.MAX_VALUE
    }
}
