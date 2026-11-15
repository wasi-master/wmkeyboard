package com.wasimaster.wmkeyboard.core.prediction


/**
 * Trie-guided fuzzy search: corrections and completions in one best-first
 * walk. Instead of generating every edit of the typed word and probing the
 * dictionary (Norvig generate-and-test), the walk descends the trie itself,
 * so only dictionary-reachable strings are ever considered — which makes
 * two-edit corrections affordable, corrects mid-word prefixes while
 * completing them, and draws substitution candidates from the trie's actual
 * edge labels (any script, not just ASCII).
 *
 * Scoring is log-space: `score = sourceLogWeight + ln(1 + frequency) - editCost`.
 * Edit penalties are the negative logs of the legacy multiplicative weights,
 * so relative ranking matches the edits-1 engine it replaces. A word reachable
 * several ways keeps its best score (max-merge, matching the engine's
 * historical maxOf semantics).
 *
 * The expansion order is admissible best-first: a state's bound is
 * `sourceLogWeight + ln(1 + maxSubtree(node)) - cost`, which only ever
 * overestimates, so pruning against the current K-th best emitted score never
 * loses a top-K word.
 *
 * The walk runs in Rust (`wm-engine`'s `fuzzy` module, which WMK Desktop
 * shares), through [NativeFuzzySearch]; every trie the keyboard builds has a
 * Rust twin. The Kotlin walk it replaced is the test sources'
 * `reference/KotlinFuzzyBeamSearch`, which `NativeFuzzyParityTest` holds it
 * to: the same candidates, scores and edit counts, bit for bit.
 */
/**
 * The natural log as fdlibm computes it, which `StrictMath` guarantees on
 * every Java runtime and `wm-engine`'s `strict_math::log` reproduces bit for
 * bit. `Math.log` may be a CPU-specific intrinsic that rounds the odd result
 * differently, and one ulp is enough to reorder two candidates, so the
 * Kotlin and Rust walks would disagree on some machine.
 */
private fun ln(x: Double): Double = StrictMath.log(x)

class FuzzyBeamSearch {

    /**
     * One weighted trie participating in a search.
     *
     * [logWeight] is the weight for the list's counts as stored: what glide
     * reads, and what the rules measured as "N times commoner" on raw counts
     * compare (the typo, accent, hyphen and elision shadows). The typing walk
     * reads the counts through [scale] at [rankWeight] instead ([RankScale]),
     * which for a list already on the bundled list's scale is the same thing.
     */
    class WalkSource(
        val walker: TrieWalker,
        val logWeight: Double,
        val tier: Tier,
        val rankWeight: Double = logWeight,
        val scale: RankScale = RankScale.RAW,
    ) {
        /** The walk's frequency term for a stored count: `rankWeight + ln(1 + scale.read(f))`. */
        fun rankScore(frequency: Int): Double = rankWeight + scale.ln1p(frequency)

        /** The same term on the counts as stored: `logWeight + ln(1 + f)`. */
        fun rawScore(frequency: Int): Double = logWeight + ln(1.0 + frequency)
    }

    enum class Tier { DICTIONARY, USER }

    /**
     * Per-keystroke touch evidence: the layout's key-center model plus the
     * tap position for each composing character (null where unknown —
     * hardware keys, pasted text, re-armed words).
     */
    class TouchScoring(val model: KeyTouchModel, val points: List<TouchPoint?>) {
        val bestLogLikelihoods: DoubleArray = DoubleArray(points.size) { i ->
            val p = points[i]
            val best = p?.let { model.bestKey(it) }
            if (p != null && best != null) model.logLikelihood(p, best) else Double.NEGATIVE_INFINITY
        }
    }

    class ScoredCandidate(
        val word: String,
        val score: Double,
        val editCost: Double,
        val edits: Int,
        /** Characters appended beyond the typed length by completion descent. */
        val completedChars: Int,
        val tier: Tier,
        /** Best score contributed by DICTIONARY-tier sources (NEGATIVE_INFINITY if none). */
        val dictScore: Double = Double.NEGATIVE_INFINITY,
        /** Best score contributed by USER-tier sources (NEGATIVE_INFINITY if none). */
        val userScore: Double = Double.NEGATIVE_INFINITY,
        /**
         * Letters read as their accented form ([Accents]): `juz` reaching `już`
         * carries 1, and so does a half-space put back (`میکنم` reaching
         * `می‌کنم`). These are not edits, so neither [edits] nor [editCost]
         * counts them.
         */
        val accents: Int = 0,
        /**
         * Of [edits], the substitutions read as slips: a tap that landed
         * within [KeyTouchModel.slipRadius] of the key meant. Counted among
         * the edits, so every gate that distrusts an edited word still does,
         * but never against the edit budget: `rgw` with three wide taps
         * reaches `the` at three edits, three of them slips.
         */
        val slips: Int = 0,
    )

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    fun search(
        sources: List<WalkSource>,
        typed: CharSequence,
        proximity: KeyProximity,
        limit: Int,
        workspace: BeamWorkspace,
        maxEdits: Int = defaultMaxEdits(typed.length),
        touch: TouchScoring? = null,
        habits: EditHabits = EditHabits.NONE,
        keys: KeySets? = null,
    ): List<ScoredCandidate> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        return NativeFuzzySearch.search(sources, typed.toString(), proximity, limit, maxEdits, touch, habits, keys)
            ?: error("a typing source with no Rust twin: every TrieWalker the keyboard builds has one")
    }

    companion object {
        /** -ln of the legacy multiplicative edit weights (SuggestionEngine),
         * kept exact so ranking is precisely isomorphic to the edits-1 engine. */
        /** Two keys swapped across the hands: the ordinary racing-hands slip. */
        val COST_TRANSPOSITION = -ln(0.9)

        /**
         * The same swap within one hand, which is a rarer thing to do and a
         * worse explanation for the buffer. Priced with the adjacent insertion
         * and the deletion rather than with its cross-hand twin, so it loses to
         * a one-key slip of comparable frequency and wins over a far
         * substitution — which is where the evidence puts it.
         */
        val COST_TRANSPOSITION_SAME_HAND = -ln(0.7)
        val COST_SUB_ADJACENT = -ln(0.9)
        val COST_DELETION = -ln(0.7)

        /** Deleting a char that doubles its neighbour ("helllo", hold-repeat
         * noise): the cheapest edit tier, alongside transposition — a
         * double-strike explains itself. */
        val COST_DELETE_DOUBLED = -ln(0.9)
        val COST_INSERT_ADJACENT = -ln(0.7)
        val COST_INSERT_FAR = -ln(0.25)
        val COST_SUB_FAR = -ln(0.2)

        /**
         * A letter typed without its accent ([Accents]). Priced with the
         * adjacent slip, which is the cheapest edit, but outside the edit
         * budget: `zolw` for `żółw` leaves off three accents and is still one
         * word, not three mistakes.
         */
        val COST_ACCENT = -ln(0.9)

        const val COMPLETION_STEP = 0.0

        /** Per-character completion cost once the path holds an edit. As
         * expensive as a far insertion: an edited-then-completed word needs a
         * ~4x frequency advantage per extra character to outrank the direct
         * fix (otherwise "skills" buries "skill" for typed "skiml", and
         * deleted-prefix floods like "bwl" -> "bl" -> blue/black/blood push
         * the real fix out of the result set). */
        val COST_COMPLETION_AFTER_EDIT = -ln(0.25)

        /** Two far substitutions can never survive; two adjacent slips can.
         * Applies to the base edit costs, before [SECOND_EDIT_SURCHARGE]. */
        const val MAX_EDIT_COST = 2.0

        /**
         * Extra cost on the second edit: the legacy engine only ever reached
         * one edit, so a two-edit word must rank below any one-edit fix of
         * comparable frequency — it exists to catch typos nothing else
         * explains, not to outbid them. (Cheapest 2-edit total: ~2.21 nats,
         * above the most expensive single edit at 1.609.)
         */
        const val SECOND_EDIT_SURCHARGE = 2.0

        /** Autocorrect wants a deeper ranked list than the strip shows. */
        const val AUTOCORRECT_K = 8

        /** Cap on the rank cost an off-center tap adds to an exact match. */
        const val MATCH_CAP = 1.0

        /** No learned habit prices an edit under the adjacent-slip tier. */
        val HABIT_FLOOR = COST_SUB_ADJACENT

        /** Runaway-state backstop; floor pruning ends healthy walks long before. */
        const val MAX_POPS = 4096

        fun defaultMaxEdits(typedLength: Int): Int = if (typedLength >= 5) 2 else 1

        /**
         * Edits allowed on top of key ambiguity (see [KeySets]).
         *
         * One, not two, and the reason is the frontier rather than taste: a
         * three-letter key already branches every position, and a second edit
         * branches each of those again — a five-key word reaches [MAX_POPS]
         * while the walk is still exploring nonsense, and the backstop then
         * cuts it off before the reading the user meant is ever emitted. One
         * edit still catches the ordinary slip of hitting the key next door,
         * which on a keypad of eight big keys is the only slip there is.
         */
        const val AMBIGUOUS_MAX_EDITS = 1

        /**
         * Extra cost on any edit taken while decoding ambiguous keystrokes.
         *
         * The ordinary edit prices assume an edit competes against a handful of
         * readings — on a 1:1 board, the typed letters and nothing else. On a
         * board with three letters to a key it competes against every reading of
         * every key, and at those prices it wins far too often: four keys of
         * "home" would rather drop the first keystroke and answer "one", which
         * is a commoner word than "home" by more than a deletion costs.
         *
         * That is the wrong answer for a reason worth naming. A reading that
         * spends every keystroke exactly as it was pressed explains the input
         * completely; one that throws a keystroke away explains it by calling
         * the user wrong. The first should lose to the second only on
         * overwhelming evidence — which is what this is: about ln(400), so an
         * edited reading has to be some four hundred times commoner than the
         * best honest one before it leads.
         *
         * A hard rule ("never rank an edited reading above an unedited one")
         * was the alternative and does not survive the trip: the strip's own
         * context boosts re-sort by score afterwards, so an ordering imposed
         * here would simply be undone. Only a price travels.
         */
        const val AMBIGUOUS_EDIT_SURCHARGE = 6.0
    }
}

/**
 * Kept for callers that pool one per thread: the walk now runs in Rust and
 * pools its own state there (`wm-engine`'s `fuzzy::Workspace`), so an
 * instance carries nothing and [FuzzyBeamSearch.search] ignores it.
 */
@Suppress("UNUSED_PARAMETER")
class BeamWorkspace(initialCapacity: Int = 256)

/**
 * How a list's stored counts enter the typing walk's score (`wm-engine`'s
 * `fuzzy::RankScale`, which this matches bit for bit).
 *
 * Every weight the engine carries was tuned on the bundled English list,
 * whose numbers are `973.83 * ln(count / 1000) + 1` on corpus counts (#792):
 * the commonest word is 10,000 and `threat` is 2,962. A downloaded list
 * carries the counts themselves, and shrunk in proportion to the same top,
 * `threat` was 6, about six nats under where the weights expect a word that
 * common. A word typed once, the context model's lift or a two-edit reading
 * of a common word then beat it: `tdreat` offered `treat`, `great` and a
 * learned `dewar`, never `threat`. Measured on the AOSP English list (strip
 * top-1, careful / fast / sloppy): .746 / .687 / .639 as stored, .837 / .777
 * / .700 on this line, the bundled list .859 / .787 / .679.
 *
 * On the line, a count at or above [floor] reads `1 + perNat * ln(f / floor)`
 * and one below it `f / floor`, which keeps the rarest words in order rather
 * than flattening them onto 1. The line is pinned by rank ([anchored]): the
 * list's commonest word lands where the bundled list's does, and its word at
 * [ANCHOR_RANK] where the bundled list's word at that rank is; the counts set
 * the spacing. [RAW] reads counts as stored.
 */
class RankScale(val floor: Double, val perNat: Double) {

    val isRaw: Boolean get() = perNat <= 0.0

    /** A stored count read through this scale. */
    fun read(frequency: Int): Double {
        val f = frequency.toDouble()
        return when {
            isRaw -> f
            f < floor -> f / floor
            else -> 1.0 + perNat * ln(f / floor)
        }
    }

    /** `ln(1 + read(f))`. */
    fun ln1p(frequency: Int): Double = ln(1.0 + read(frequency))

    override fun equals(other: Any?): Boolean =
        other is RankScale && other.floor == floor && other.perNat == perNat

    override fun hashCode(): Int = 31 * floor.hashCode() + perNat.hashCode()

    companion object {
        /** Counts as stored. */
        val RAW = RankScale(0.0, 0.0)

        /**
         * The bundled list's ratio from its commonest word to its floor:
         * 28,787,591 / 1,000 on the OpenSubtitles counts it was rescaled from.
         */
        const val DYNAMIC_RANGE = 28_787.591

        /**
         * A list whose counts sit at or under the reference counts as corpus
         * counts, and is read [anchored], when its count at [ANCHOR_RANK] is
         * under this share of its top. Counts that are already logs keep far
         * more of their top that deep (the bundled English list 0.31, the
         * bundled Bangla one 0.12); corpus counts keep a hundredth or less
         * (the small Wikipedia lists 0.0003 to 0.01).
         */
        const val CORPUS_ANCHOR_SHARE = 0.05

        /**
         * How a list whose commonest count is [top] and whose count at
         * [ANCHOR_RANK] is [atAnchor] is read for ranking (`wm-engine`'s
         * `RankScale::for_list`).
         *
         * A list whose top lands above [reference] × [tolerance] carries
         * corpus counts and is read [anchored]. So is one whose top is at or
         * under it but whose counts fall away like a corpus's: a small
         * corpus (a Wikipedia of a few thousand articles) tops out at a few
         * thousand, and read as stored its mid words sat a few nats under
         * where the engine's fixed prices expect them, the fault the large
         * lists had. A list already on the reference's log scale, a flat
         * one, or one too short to have a word at the anchor reads as stored.
         */
        fun forList(top: Int, atAnchor: Int, reference: Double, tolerance: Double): RankScale = when {
            top > reference * tolerance -> anchored(top, atAnchor, reference)
            atAnchor > 0 && atAnchor < top * CORPUS_ANCHOR_SHARE -> anchored(top, atAnchor, reference)
            else -> RAW
        }

        /**
         * The line that puts a list whose commonest word is [top] where the
         * bundled list's is, at [reference], with its floor the same ratio
         * below it.
         */
        fun logLine(top: Int, reference: Double): RankScale =
            RankScale(top / DYNAMIC_RANGE, (reference - 1.0) / ln(DYNAMIC_RANGE))

        /** The rank [anchored]'s second point is taken at. */
        const val ANCHOR_RANK = 2_000

        /**
         * The bundled English list's number at [ANCHOR_RANK]
         * (`app/dictionaries-src/en.txt`, sorted). Moves only if that list is
         * renumbered.
         */
        const val REFERENCE_AT_ANCHOR = 3_077.0

        /**
         * The line through two points: the list's commonest count [top] at
         * [reference] and its count at [ANCHOR_RANK], [atAnchor], at
         * [REFERENCE_AT_ANCHOR] (`wm-engine`'s `RankScale::anchored`).
         *
         * [logLine] assumed every list spans the bundled list's range, which
         * the large corpus lists nearly do and a small one does not: the
         * Banglish list tops out at 31,465, and on the assumed line its rare
         * words read as common as mid-list English ones. Typing harness,
         * strip top-1 careful / fast / sloppy: Banglish .655 / .522 / .381
         * assumed, .658 / .573 / .457 anchored; AOSP English .846 / .779 /
         * .699 and .847 / .780 / .693; German .766 / .677 / .574 and .758 /
         * .677 / .579.
         *
         * [logLine] where the list has no count at the anchor (fewer words,
         * or a trie that cannot rank) or one equal to its top. The stretch
         * stays within half and twice the assumed line's, so a head that is
         * one long tie cannot blow it up.
         */
        fun anchored(top: Int, atAnchor: Int, reference: Double): RankScale {
            val line = logLine(top, reference)
            if (atAnchor <= 0 || atAnchor >= top) return line
            val perNat = ((reference - REFERENCE_AT_ANCHOR) / ln(top.toDouble() / atAnchor))
                .coerceIn(line.perNat / 2.0, line.perNat * 2.0)
            return RankScale(top * StrictMath.pow(Math.E, -(reference - 1.0) / perNat), perNat)
        }
    }
}
