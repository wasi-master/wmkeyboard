package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import kotlin.math.ln

/**
 * The shared conversion decoder: given a buffer already split into units, work
 * out which words the user most likely meant.
 *
 * Every conversion composer here used to do its own version of this — walk the
 * cumulative prefixes longest-first, look each one up, stop at twelve. That has
 * three problems the lattice exists to fix.
 *
 *  - **It cannot see past the first word.** Longest-first ranks 你好 above 你
 *    because it is longer, not because the rest of the buffer works out. Scoring
 *    whole paths lets a shorter first word win when it leaves a better remainder,
 *    which is what makes typing a sentence and committing it possible.
 *  - **It enumerates ambiguity into strings, then truncates.** Fuzzy pinyin and
 *    T9 both build the cartesian product of per-unit readings and cut it off at a
 *    cap, which drops real candidates — alphabetically-late ones first, because
 *    that is the order the product happens to come out in. Here the ambiguity
 *    stays as [Input.options] and is resolved against
 *    [ConversionDictionary.prefixRange], so a reading no word starts with is
 *    pruned before it is ever built and the cost is what the dictionary contains
 *    rather than what the option lists multiply out to.
 *  - **It fills to a limit and breaks.** Whatever the first source of candidates
 *    was, it took all twelve slots. Collect-then-rank has no such accident.
 *
 * Prefix commit shapes what this can offer. `commitConversionPrefix` deletes
 * `composing[0, consumed)`, so **every candidate must start at input position 0**
 * — there is no way to commit the second word of a sentence without committing
 * the first. So the strip gets the whole decoded sentence at rank 0, then the
 * plausible *first* words below it, each re-ranked by how well the rest of the
 * buffer comes out behind it. Per-word alternatives under a sentence would need
 * the segment-narrowing UI a desktop IME has, which this keyboard does not.
 *
 * The search itself runs in Rust (`native/wm-engine/src/cjk/lattice.rs`), one
 * crossing per decode, with the tables and what the user taught it behind
 * handles. What stays here is the composers' language for asking. The Kotlin
 * search it replaced is the reference `NativeCjkParityTest` compares against.
 */
object Lattice {

    /**
     * A buffer prepared for decoding: where the unit boundaries fall in the input,
     * what reading each unit might spell, and what each of those spellings costs.
     *
     * The composer owns this translation, which is the point — Japanese counts
     * romaji while segmenting kana, T9 counts digits, Double Pinyin counts key
     * codes, and none of that leaks in here.
     */
    class Input(
        /** Size `units + 1`; `boundaries[k]` is the input chars the first k units ate. */
        val boundaries: IntArray,
        /** Per unit, the readings it could spell. Index 0 is what the user typed. */
        val options: Array<Array<String>>,
        /** Parallel to [options]: 0.0 for the as-typed reading, negative for a guess. */
        val penalties: Array<DoubleArray>,
        /**
         * Per unit, the index in [options] from which the readings are
         * *abbreviations* — the unit spells only the start of its syllable
         * (jianpin `h` for hao/hen/hua…) rather than a complete reading that
         * might have been misspelled. Defaults to `options[i].size`: no
         * abbreviations. The distinction matters to [Opts.maxAmbiguousSpan],
         * which caps guesses but must never cap deliberate abbreviation.
         */
        val partialFrom: IntArray = IntArray(options.size) { options[it].size },
        /**
         * Per unit, readings that cover this unit *and the next one* as a single
         * syllable — jianpin's `z` + `h` read as the digraph initial `zh…`, so
         * `zhg` still reaches 中国 while `sh` also reads as `s` + `h` (上海).
         * Empty by default. These are always abbreviations, never capped.
         */
        val merged: Array<Array<String>> = Array(options.size) { emptyArray() },
        /** Parallel to [merged]. */
        val mergedPenalties: Array<DoubleArray> = Array(options.size) { DoubleArray(0) },
    ) {
        val units: Int get() = options.size
    }

    /** [Input] for units whose reading is unambiguous — Zhuyin, Jyutping, kana. */
    fun input(readings: List<String>, inputLens: List<Int>): Input =
        options(readings.map { arrayOf(it) }, inputLens)

    /**
     * [Input] for units that could spell several readings, each equally intended —
     * a T9 digit code, where ambiguity is the keypad's doing and not the user's,
     * so nothing is penalized and the language model decides.
     */
    fun options(options: List<Array<String>>, inputLens: List<Int>): Input {
        val n = options.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + inputLens[i]
        return Input(
            boundaries,
            options.toTypedArray(),
            Array(n) { DoubleArray(options[it].size) },
        )
    }

    /**
     * One reading a stretch of the buffer could spell, from one node of a
     * [Graph] to a later one.
     */
    class Arc(
        val to: Int,
        val reading: String,
        /** 0.0 for what was typed, negative for a guess or an abbreviation. */
        val penalty: Double = 0.0,
        /**
         * A spelling *guess* (a fuzzy variant, a mistyped key), held to
         * [Opts.maxAmbiguousSpan]. Never an abbreviation: see that option for
         * why those must stay uncapped.
         */
        val guess: Boolean = false,
        /**
         * A repair of what was typed (a mistyped, swapped, missing or extra
         * key) rather than a spelling the user chose. Fuzzy Pinyin is not one:
         * the user switched it on. A reading built through a repair is offered
         * only while it scores near the best one built without any
         * ([Opts.correctionMargin]), the way libime holds its corrections to
         * within ten times the clean answer.
         */
        val correction: Boolean = false,
    )

    /**
     * A buffer as a graph rather than one row of units: [Input] fixes where
     * every boundary falls, and a graph does not have to.
     *
     * That is the whole difference, and it is what Pinyin needs (#580). `xian`
     * is one syllable (先) *and* two (西安); `nine` is `nin`+`e` (您饿) *and*
     * `ni`+`ne` (你呢). A single segmentation picks one of those before the
     * decoder has seen a word, and whatever it picked is the only reading the
     * strip can offer. A graph carries every split worth considering as arcs
     * between shared nodes, so the language model chooses between them.
     *
     * Nodes are positions; [consumed] says how many input chars each one is
     * past, which is what a candidate ending there commits.
     */
    class Graph(
        /** Per node, input chars consumed by reaching it. Node 0 is the start, the last node the end. */
        val consumed: IntArray,
        /** Per node, the arcs leaving it. The first arc of a node is its as-typed reading. */
        val arcs: Array<List<Arc>>,
    ) {
        val end: Int get() = consumed.size - 1
    }

    /** [input] as a [Graph] whose nodes are its unit boundaries. */
    fun graph(input: Input): Graph {
        val n = input.units
        val arcs = Array(n + 1) { i ->
            if (i == n) return@Array emptyList<Arc>()
            val out = ArrayList<Arc>(input.options[i].size + input.merged[i].size)
            for ((oi, reading) in input.options[i].withIndex()) {
                // Past the ambiguity cap only the as-typed reading and the
                // abbreviations are tried; a spelling guess is not worth the fan-out.
                val guess = oi > 0 && oi < input.partialFrom[i]
                out.add(Arc(i + 1, reading, input.penalties[i].getOrElse(oi) { 0.0 }, guess))
            }
            // Two units read as one syllable (`z` + `h` → `zh…`): the arc lands
            // past both, but spells one syllable towards the word-length test.
            if (i + 1 < n) {
                for ((mi, reading) in input.merged[i].withIndex()) {
                    out.add(Arc(i + 2, reading, input.mergedPenalties[i].getOrElse(mi) { 0.0 }))
                }
            }
            out
        }
        return Graph(input.boundaries, arcs)
    }

    /** Decoder bounds and weights. Defaults suit Chinese; Japanese overrides one. */
    class Opts(
        /** Longest word, in syllables. Beyond ~6 a "word" is really a phrase. */
        val maxWordUnits: Int = 6,
        /**
         * Spans longer than this use only each unit's first reading.
         *
         * This is for *guesses*. Fuzzy pinyin is per-syllable spelling correction,
         * and someone who typed a five-syllable phrase with three fuzzy errors is
         * not worth a combinatorial price — the short edges the decoder stitches
         * together recover most of it anyway.
         *
         * It must be left wide open where the ambiguity is intrinsic rather than
         * the user's doing. On a T9 keypad *every* unit is ambiguous, so capping
         * this pins later units to whichever syllable sorted first and rebuilds
         * the exact alphabetical bias the lattice replaced: `726726726` would
         * offer nothing beginning `san`. Cost there is bounded by the dictionary
         * prune instead, which is what it is for.
         *
         * Abbreviations ([Input.partialFrom]) are exempt for the same reason: a
         * jianpin `zgrm` is four deliberately ambiguous units, and pinning the
         * third to `ra` would make 中国人民 untypeable.
         */
        val maxAmbiguousSpan: Int = 2,
        /**
         * Words kept per span, best-frequency first — raised to [limit] where
         * that is larger, since a single reading routinely has more characters
         * behind it than the strip shows and the expanded grid exists to show
         * them. Interior spans only matter for pathfinding, but the first span
         * *is* the candidate list.
         */
        val spanCandCap: Int = 16,
        /** Live paths carried between positions. */
        val beam: Int = 8,
        val limit: Int = 12,
        /**
         * Whether one character spells exactly one unit.
         *
         * True across the Chinese family: one Hanzi is one syllable, always. The
         * dictionary keys a *joined* reading, so a two-syllable key is shared with
         * every single character read the same way — 西安 and 现 both sit under
         * `xian`. When the user spells out two syllables (`xi'an`), 现 is not a
         * lower-ranked answer, it is not an answer at all: one character cannot
         * cover two syllables. Dropping those rows is what makes the apostrophe
         * worth typing.
         *
         * The reverse is only over-segmentation, not an error: typing `xian` as
         * one unit still legitimately means 西安, so a longer word is kept and
         * merely demoted by [overLengthPenalty].
         *
         * **False for Japanese**, where the rule is simply untrue — a kanji takes
         * however many mora it takes.
         */
        val charPerUnit: Boolean = true,
        /** Charged per character by which a word overruns its span's unit count. */
        val overLengthPenalty: Double = -1.2,
        /**
         * How many extra whole-buffer readings made of *several* words to offer
         * beyond the single best path, best first (issue #405). 0 offers none,
         * which is right for every buffer the user spelled out in full.
         *
         * These exist because the language model here is a unigram over words,
         * and a unigram cannot compare readings that split the buffer
         * differently: each extra word costs another `ln(totalFreq)`, so a
         * two-word reading loses to any single dictionary word covering the same
         * span by about fifteen nats whatever the words are. That is an artifact
         * of the normaliser, not a judgement — and it is exactly the readings
         * jianpin is for. `wdmm` is 我的妈妈, two of whose three words
         * (我, 的) are not a dictionary entry together, so the decoder answered
         * 味道妈妈 and the right reading appeared nowhere at all.
         *
         * So they are *offered*, not promoted: the unbiased best path keeps rank
         * 0 and these follow it, found with [stitchBonus] standing in for the
         * missing word-insertion term. Picking one teaches [CjkLearning] the
         * whole reading, after which it leads on its own.
         */
        val stitch: Int = 0,
        /**
         * The per-word bonus the [stitch] search runs with — the word-insertion
         * term a unigram model lacks. Measured against the shipped CC-CEDICT
         * pack: at 8.0 `wdmm` finds 我的妈妈 and `wbxhni` finds 我不喜欢你,
         * which is what the issue asked for; much above it the decoder stops
         * preferring phrases at all (`wdmm` → 我的们们) and much below it finds
         * nothing new. It never touches the ranking the strip leads with.
         */
        val stitchBonus: Double = 8.0,
        /**
         * Order the list by how much of the buffer each candidate covers (#580):
         * every reading of the whole buffer first, then the best readings of
         * each shorter stretch from the start, longest first, down to the
         * single first words.
         *
         * Off, the list is the best sentence and then *first words*, each
         * ranked by how well the rest of the buffer works out behind it. That
         * cannot offer `我不想` for `wbxh`: it is neither the whole buffer nor
         * one word. Nor can it keep a single word that happens to have been
         * picked before from jumping above every reading of the whole buffer,
         * since a first word is what that order is made of.
         */
        val coverageOrder: Boolean = false,
        /** Under [coverageOrder], multi-word readings of one stretch, beyond its single words. */
        val pathsPerStretch: Int = 4,
        /**
         * Under [coverageOrder], how far behind the best clean reading of a
         * stretch a reading built through a repaired key ([Arc.correction])
         * may score and still be offered, in nats: ln 10, so a repair has to
         * be at least a tenth as likely as what was typed. Where nothing clean
         * reads the stretch at all, every repair is offered.
         */
        val correctionMargin: Double = ln(10.0),
        /**
         * Charge each word for its characters by [HanLevels.penalty], which
         * sinks characters outside the common standard set (#633's 琌 and 硂)
         * below the ones people write. The Chinese composers turn this on.
         */
        val hanLevels: Boolean = false,
        /**
         * Under [coverageOrder], how far behind the best reading of a stretch a
         * multi-word one may score and still be offered, in nats. Its single
         * words are always all offered — they are the characters to pick from
         * — but a path is a guess at a sentence, and one that loses to a
         * dictionary word for the same letters is never offered at all.
         */
        val pathMargin: Double = 5.0,
        /**
         * Under [coverageOrder], how many candidates each stretch shorter than
         * the buffer adds, except the shortest, which adds all of its words:
         * that one is the list of characters to pick one at a time from.
         */
        val stretchCap: Int = 4,
    )

    /** A decoded candidate: what to commit, how much of the buffer it eats. */
    data class Cand(
        val text: String,
        val consumed: Int,
        val score: Double,
        val reading: String,
        /** Built through a repaired key ([Arc.correction]). */
        val corrected: Boolean = false,
    )

    /**
     * One reading space's history, as the decoder reads it: what was picked for
     * each reading, which words were written after which, and what the
     * candidate menu said. A snapshot in Rust; [CjkUserHistory] makes a new one
     * after a change rather than the decoder asking it per word.
     */
    class UserData private constructor(internal val handle: Long) {
        init {
            NativeHandles.track(this, handle, CjkNative::userClose)
        }

        companion object {
            private const val SEP = '\u0001'

            /** Null when there is nothing to say. */
            fun of(
                picks: Map<String, Map<String, Int>>?,
                adjust: WordAdjust?,
                pairs: Map<String, Map<String, Int>>?,
            ): UserData? {
                if (picks.isNullOrEmpty() && pairs.isNullOrEmpty() && adjust == null) return null
                val (pickText, pickCounts) = flatten(picks.orEmpty())
                val (pairText, pairCounts) = flatten(pairs.orEmpty())
                val bias = adjust?.bias.orEmpty().entries.toList()
                val handle = CjkNative.userOpen(
                    pickText,
                    pickCounts,
                    pairText,
                    pairCounts,
                    adjust?.blocked.orEmpty().joinToString(SEP.toString()),
                    bias.joinToString(SEP.toString()) { it.key },
                    IntArray(bias.size) { bias[it].value },
                )
                return UserData(handle)
            }

            private fun flatten(table: Map<String, Map<String, Int>>): Pair<String, IntArray> {
                val text = StringBuilder()
                val counts = ArrayList<Int>()
                for ((key, words) in table) {
                    for ((word, count) in words) {
                        if (counts.isNotEmpty()) text.append(SEP)
                        text.append(key).append(SEP).append(word)
                        counts.add(count)
                    }
                }
                return text.toString() to counts.toIntArray()
            }
        }
    }

    /** How much of a [UserData] a composer's decode reads; the menu's answers always count. */
    enum class UserScope(internal val bits: Int) {
        MENU(0),

        /** Picks too, which steer the words paths are built out of. */
        PICKS(1),

        /** Picks, phrases the user taught, and their word pairs. */
        ALL(3),
    }

    /**
     * Decodes [input], best first.
     *
     * Rank 0 is the whole-buffer sentence when the best path uses more than one
     * word; the rest are first words, ranked by their own score plus the best the
     * remainder can do behind them. Always returns something for a non-empty
     * input with words in it: no buffer can trap the user with an empty strip.
     */
    @Suppress("LongParameterList")
    fun decode(
        input: Input,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        opts: Opts = Opts(),
        user: UserData? = null,
        scope: UserScope = UserScope.MENU,
        context: String? = null,
    ): List<Cand> =
        if (input.units == 0) emptyList() else decode(graph(input), dict, ngrams, opts, user, scope, context)

    /**
     * [decode] over a [Graph], for a buffer whose boundaries are themselves ambiguous.
     *
     * [context] is the word written just before the buffer, when there is one:
     * the first word of every reading is scored as following it, which is how
     * 他 after 我爱 and 她 after 她说 can come out differently for the same `ta`.
     */
    @Suppress("LongParameterList")
    fun decode(
        graph: Graph,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        opts: Opts = Opts(),
        user: UserData? = null,
        scope: UserScope = UserScope.MENU,
        context: String? = null,
    ): List<Cand> {
        if (graph.end <= 0) return emptyList()
        val total = graph.arcs.sumOf { it.size }
        val arcStart = IntArray(graph.arcs.size + 1)
        val arcTo = IntArray(total)
        val arcFlags = IntArray(total)
        val arcPenalty = DoubleArray(total)
        val readings = StringBuilder()
        var a = 0
        for ((node, arcs) in graph.arcs.withIndex()) {
            arcStart[node] = a
            for (arc in arcs) {
                if (a > 0) readings.append(SEP)
                readings.append(arc.reading)
                arcTo[a] = arc.to
                arcFlags[a] = (if (arc.guess) ARC_GUESS else 0) or (if (arc.correction) ARC_CORRECTION else 0)
                arcPenalty[a] = arc.penalty
                a++
            }
        }
        arcStart[graph.arcs.size] = a
        val consumed = IntArray(opts.limit)
        val scores = DoubleArray(opts.limit)
        val text = CjkNative.decode(
            dict.handle,
            ngrams.handle,
            if (opts.hanLevels) HanLevels.table.handle else 0L,
            user?.handle ?: 0L,
            scope.bits,
            graph.consumed,
            arcStart,
            arcTo,
            arcFlags,
            arcPenalty,
            readings.toString(),
            intArrayOf(
                opts.maxWordUnits,
                opts.maxAmbiguousSpan,
                opts.spanCandCap,
                opts.beam,
                opts.limit,
                if (opts.charPerUnit) 1 else 0,
                opts.stitch,
                if (opts.coverageOrder) 1 else 0,
                opts.pathsPerStretch,
                opts.stretchCap,
            ),
            doubleArrayOf(
                opts.overLengthPenalty,
                opts.stitchBonus,
                opts.correctionMargin,
                opts.pathMargin,
                CjkNgrams.bigramWeight,
            ),
            context,
            consumed,
            scores,
        )
        if (text.isEmpty()) return emptyList()
        val parts = text.split(SEP)
        return List(parts.size / 2) { i ->
            Cand(parts[2 * i], consumed[i] ushr 1, scores[i], parts[2 * i + 1], consumed[i] and 1 != 0)
        }
    }

    private const val SEP = '\u0001'
    private const val ARC_GUESS = 1
    private const val ARC_CORRECTION = 2
}

/**
 * What the user said about words from the candidate menu (#580), for one reading
 * space: words never to offer, and how many steps up or down each other one was
 * moved. A snapshot, read without a lock for a whole decode.
 */
class WordAdjust(
    val blocked: Set<String> = emptySet(),
    /** Word → steps; negative is lower. */
    val bias: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = blocked.isEmpty() && bias.isEmpty()
}
