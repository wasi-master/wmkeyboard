// The Kotlin KotlinLattice the phone ran until the conversion decoder moved to Rust, kept
// as the reference NativeCjkParityTest compares wm-engine against. A change to
// the decoder's scoring lands here and in native/wm-engine/src/cjk, or the test fails.
package com.wasimaster.wmkeyboard.core.input.composer.reference

import com.wasimaster.wmkeyboard.core.input.composer.WordAdjust
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
 *    [KotlinConversionDictionary.prefixRange], so a reading no word starts with is
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
 */
object KotlinLattice {

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
        val correctionMargin: Double = CORRECTION_MARGIN,
        /**
         * What a word costs beyond its frequency, by its text: the Chinese
         * composers pass [HanLevels.penalty], which sinks characters outside
         * the common standard set (#633's 琌 and 硂) below the ones people
         * write. Null for everything else, and nothing is charged.
         */
        val wordPenalty: ((String) -> Double)? = null,
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
     * What the decoder knows about this user beyond the dictionary and the
     * picks per reading: which readings their own phrases start with, so the
     * dictionary prune does not cut a learned phrase off, and which word they
     * have followed which other word with.
     */
    class UserWords(
        /** Whether any phrase the user taught is read starting with this. */
        val hasPrefix: (String) -> Boolean = { false },
        /** Previous word → word → times written in that order. */
        val pairs: Map<String, Map<String, Int>> = emptyMap(),
    ) {
        companion object {
            val NONE = UserWords()
        }
    }

    /**
     * How much the word's own frequency within its reading counts against the
     * language model. Normalized inside the reading's run, so it is always ≤ 0 and
     * exactly 0 for the run's most frequent word. Matters little in Chinese and a
     * lot in Japanese, where 行 is a common word under several unrelated readings.
     */
    private const val BETA = 0.5

    /** Score of committing a unit as its raw reading. */
    private val UNK_LOG = ln(1e-9)

    /**
     * What a learned pick is worth to an edge, times `ln(1 + times chosen)`.
     *
     * [CjkLearning.rank] already puts a previously-chosen candidate first, but
     * that is a sort of the finished list and so can only promote a reading the
     * decoder already found. This is the same knowledge applied *inside* the
     * search, where it decides which words a path is built out of: a user who
     * keeps choosing 好 for `hao` gets 好的 out of `hd`, which no re-sort of the
     * answers could have done because 好的 was never among them (issue #405).
     *
     * One pick is worth 0.69 and ten are worth 2.4 — enough to settle the near
     * ties the pack's frequency column is full of (和 970 against 好 941), not
     * enough to beat a genuinely commoner word.
     */
    private const val LEARN_WEIGHT = 1.0

    /**
     * What one "show this lower" step from the candidate menu costs a word
     * (#580), times the steps taken. Two steps put a word behind one about
     * fifty times rarer; it never takes a word out — that is what blocking is
     * for.
     */
    private const val BIAS_WEIGHT = 2.0

    /**
     * What one time the user wrote a word straight after another is worth to
     * that pair, times `ln(1 + times)` (#580 follow-up). The same scale as
     * [LEARN_WEIGHT]: a habit settles a tie, it does not overrule the corpus.
     */
    private const val USER_PAIR_WEIGHT = 1.0

    /**
     * The language-model score a phrase the user taught gets where the word
     * pairs have never seen it: about one word in ten thousand. A learned
     * phrase is one word to the decoder, and scored as an unseen one it would
     * lose to the very path of shorter words it was learned to replace.
     */
    private val USER_WORD_LOG = ln(1e-4)

    /**
     * Frequency a phrase the user taught stands at where the reading has no
     * dictionary rows to take one from: the pack's middle (its scale is 0–1000).
     */
    private const val USER_WORD_FREQ = 500

    /** [Opts.correctionMargin]'s default: ln 10. */
    private val CORRECTION_MARGIN = ln(10.0)

    /** One dictionary word spanning nodes `[from, to)`, with its context-free score. */
    private class Edge(
        val from: Int,
        val to: Int,
        val text: String,
        val reading: String,
        /** Everything but the language model: emission, penalties, bonuses. */
        val emission: Double,
        val freq: Int,
        /**
         * A raw-reading edge rather than a dictionary word. These keep the lattice
         * connected across a syllable nothing is written with, so a path always
         * exists — but they are never offered as candidates. A strip that answered
         * `si` with "si" would be claiming a conversion it does not have; the raw
         * reading already commits through `composeBuffer` when the buffer is
         * flushed, which is where that fallback belongs.
         */
        val fallback: Boolean = false,
        /** Read through a repaired key; see [Arc.correction]. */
        val correction: Boolean = false,
        /** A phrase the user taught rather than a dictionary row; see [USER_WORD_LOG]. */
        val user: Boolean = false,
    )

    /** A live path: the word just committed, its running score, and where it came from. */
    private class Path(val word: String?, val score: Double, val prev: Path?, val edge: Edge?) {
        val words: Int = if (edge == null) 0 else (prev?.words ?: 0) + 1
    }

    /**
     * The language model for one decode: the corpus word pairs, the user's
     * own pairs on top, and the phrases the user taught scored as words.
     */
    private class Scorer(
        val dict: KotlinConversionDictionary,
        val ngrams: KotlinCjkNgrams,
        val pairs: Map<String, Map<String, Int>>,
    ) {
        fun lm(previous: String?, e: Edge): Double {
            var score = ngrams.logProbability(previous, e.text, e.freq, dict.totalFreq)
            if (e.user) score = maxOf(score, USER_WORD_LOG)
            if (previous != null && pairs.isNotEmpty()) {
                val n = pairs[previous]?.get(e.text) ?: 0
                if (n > 0) score += USER_PAIR_WEIGHT * ln(1.0 + n)
            }
            return score
        }
    }

    /**
     * Decodes [input], best first.
     *
     * Rank 0 is the whole-buffer sentence when the best path uses more than one
     * word; the rest are first words, ranked by their own score plus the best the
     * remainder can do behind them ([suffixScores]). Always returns something for
     * a non-empty input — every unit has a raw-reading fallback edge, so no buffer
     * can trap the user with an empty strip.
     */
    @Suppress("LongParameterList")
    fun decode(
        input: Input,
        dict: KotlinConversionDictionary,
        ngrams: KotlinCjkNgrams,
        opts: Opts = Opts(),
        learned: (reading: String) -> Map<String, Int>? = { null },
        adjust: WordAdjust? = null,
        context: String? = null,
        user: UserWords = UserWords.NONE,
    ): List<Cand> =
        if (input.units == 0) emptyList() else decode(graph(input), dict, ngrams, opts, learned, adjust, context, user)

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
        dict: KotlinConversionDictionary,
        ngrams: KotlinCjkNgrams,
        opts: Opts = Opts(),
        learned: (reading: String) -> Map<String, Int>? = { null },
        adjust: WordAdjust? = null,
        context: String? = null,
        user: UserWords = UserWords.NONE,
    ): List<Cand> {
        val n = graph.end
        if (n <= 0) return emptyList()
        val edges = buildEdges(graph, dict, opts, learned, adjust, user)
        val sc = Scorer(dict, ngrams, user.pairs)
        val live = viterbi(edges, sc, opts, n, context)
        return if (opts.coverageOrder) byCoverage(graph, edges, live, sc, opts, context)
        else byFirstWord(graph, edges, live, sc, opts, context)
    }

    /** The original order: the best sentence, stitched readings, then first words. See [Opts.coverageOrder]. */
    @Suppress("LongParameterList")
    private fun byFirstWord(
        graph: Graph,
        edges: Array<MutableList<Edge>>,
        live: Array<MutableList<Path>?>,
        sc: Scorer,
        opts: Opts,
        context: String?,
    ): List<Cand> {
        val n = graph.end
        val suffix = suffixScores(edges, sc, n)
        val out = LinkedHashMap<String, Cand>()

        // Rank 0: the decoded sentence — but only when every step of it is a real
        // word. A path patched together with raw readings is not a conversion.
        val best = live[n]?.maxByOrNull { it.score }?.let(::pathOf).orEmpty()
        if (best.size >= 2 && best.none { it.fallback }) {
            val text = best.joinToString("") { it.text }
            val reading = best.joinToString("") { it.reading }
            out[text] = Cand(text, graph.consumed[n], best.sumOf { it.emission }, reading, best.any { it.correction })
        }

        // Then the readings that cover the buffer with several words, which the
        // unigram model above cannot see (see [Opts.stitch]). Ahead of the first
        // words rather than after them: they cover everything typed, which is
        // what the buffer was abbreviated to ask for.
        //
        // Only when nothing in the dictionary covers it on its own. An
        // abbreviation that reaches a real phrase — `zhg` for 中国, `zgrm` for
        // 中国人民 — has already done its job, and stitched readings there are
        // three slots of 知好国 in front of the answer.
        addStitched(graph, edges, sc, opts, out, context)

        // Then every plausible first word, scored with the rest of the buffer
        // behind it rather than on its own length.
        val firsts = edges[0]
            .filterNot { it.fallback }
            .map { e -> e to sc.lm(context, e) + e.emission + suffix[e.to] }
            .sortedByDescending { it.second }
        for ((edge, score) in firsts) {
            if (out.size >= opts.limit) break
            out.getOrPut(edge.text) {
                Cand(edge.text, graph.consumed[edge.to], score, edge.reading, edge.correction)
            }
        }
        return out.values.toList()
    }

    /** Stitched whole-buffer readings into [out], unless one word already covers the buffer. */
    @Suppress("LongParameterList")
    private fun addStitched(
        graph: Graph,
        edges: Array<MutableList<Edge>>,
        sc: Scorer,
        opts: Opts,
        out: LinkedHashMap<String, Cand>,
        context: String?,
    ) {
        val n = graph.end
        // A row whose word is its own reading converts nothing. ja_kana files
        // またあした under またあした, and counting that as covering the buffer
        // kept また明日 off the strip altogether.
        val wholeBufferWord = edges[0].any { !it.fallback && it.to == n && it.text != it.reading }
        if (opts.stitch <= 0 || wholeBufferWord) return
        for (path in stitchedPaths(edges, sc, opts, n, context)) {
            if (out.size >= opts.limit) break
            val text = path.joinToString("") { it.text }
            out.getOrPut(text) {
                Cand(
                    text,
                    graph.consumed[n],
                    path.sumOf { it.emission },
                    path.joinToString("") { it.reading },
                    path.any { it.correction },
                )
            }
        }
    }

    /**
     * The order the candidate list takes under [Opts.coverageOrder] (#580).
     *
     * First every reading of the whole buffer — its single words and its best
     * multi-word paths, together, by score — then the stitched readings the
     * unigram fallback cannot rank. Then, for each shorter stretch from the
     * start, longest first, the best of what reads it: `wbxh` offers 我不喜欢,
     * then 我不想 and 我必须, then 我不, then 我. Each stretch is one prefix
     * commit, so whatever is left behind it re-converts on its own.
     */
    @Suppress("LongParameterList")
    private fun byCoverage(
        graph: Graph,
        edges: Array<MutableList<Edge>>,
        live: Array<MutableList<Path>?>,
        sc: Scorer,
        opts: Opts,
        context: String?,
    ): List<Cand> {
        val n = graph.end
        val out = LinkedHashMap<String, Cand>()
        val ends = HashMap<Int, MutableList<Edge>>()
        for (e in edges[0]) if (!e.fallback) ends.getOrPut(e.to) { mutableListOf() }.add(e)

        fun stretch(node: Int, cap: Int, afterFirst: () -> Unit = {}) {
            val found = ArrayList<Cand>()
            for (e in ends[node].orEmpty()) {
                val score = sc.lm(context, e) + e.emission
                found.add(Cand(e.text, graph.consumed[node], score, e.reading, e.correction))
            }
            val paths = live[node]
                ?.filter { it.words >= 2 }
                ?.sortedByDescending { it.score }
                ?.asSequence()
                ?.map(::pathOf)
                ?.filter { path -> path.none { it.fallback } }
                ?.take(opts.pathsPerStretch)
                ?.map { path ->
                    Cand(
                        path.joinToString("") { it.text },
                        graph.consumed[node],
                        path.sumOf { it.emission } + pathLm(path, sc, context),
                        path.joinToString("") { it.reading },
                        path.any { it.correction },
                    )
                }
                ?.toList()
                .orEmpty()
            // A path has to beat every single word for the stretch: where the
            // dictionary has the word, 你好 for `nihao`, stitching 你 + 号 is
            // only noise under it. Where it has none, the paths are the answer.
            val best = (found + paths).maxOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY
            val bestWord = found.maxOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY
            val floor = maxOf(bestWord, best - opts.pathMargin)
            paths.filterTo(found) { it.score >= floor }
            // A repaired key is a guess about the user's finger, so it has to
            // read nearly as well as what they actually typed. Where nothing
            // they typed reads this stretch, it is all there is.
            val bestClean = found.filterNot { it.corrected }.maxOfOrNull { it.score }
            if (bestClean != null) found.removeAll { it.corrected && it.score < bestClean - opts.correctionMargin }
            var added = 0
            for (c in found.sortedByDescending { it.score }) {
                if (out.size >= opts.limit || added >= cap) break
                if (out.putIfAbsent(c.text, c) == null && ++added == 1) afterFirst()
            }
            if (added == 0) afterFirst()
        }

        // The stitched readings go straight after the best one, where the
        // original order had them: without a word-pair model they are the only
        // way the everyday pairings get onto the strip at all.
        stretch(n, Int.MAX_VALUE) { addStitched(graph, edges, sc, opts, out, context) }
        val shorter = (1 until n)
            .filter { node -> ends.containsKey(node) || live[node]?.any { it.words >= 2 } == true }
            .sortedByDescending { graph.consumed[it] }
        for ((i, node) in shorter.withIndex()) {
            if (out.size >= opts.limit) break
            stretch(node, if (i == shorter.lastIndex) Int.MAX_VALUE else opts.stretchCap)
        }
        return out.values.toList()
    }

    /** The language model's share of [path]'s score, bigrams across its own words. */
    private fun pathLm(path: List<Edge>, sc: Scorer, context: String?): Double {
        var prev: String? = context
        var sum = 0.0
        for (e in path) {
            sum += sc.lm(prev, e)
            prev = e.text
        }
        return sum
    }

    /**
     * Every word that starts at each node, plus a raw-reading fallback so a
     * complete path always exists.
     */
    @Suppress("LongParameterList")
    private fun buildEdges(
        graph: Graph,
        dict: KotlinConversionDictionary,
        opts: Opts,
        learned: (String) -> Map<String, Int>?,
        adjust: WordAdjust?,
        user: UserWords,
    ): Array<MutableList<Edge>> {
        val n = graph.end
        val out = Array(n + 1) { mutableListOf<Edge>() }
        val walk = Walk(graph, dict, opts, out, learned, adjust, user)
        for (i in 0 until n) {
            val first = graph.arcs[i].firstOrNull()
            if (first == null) {
                // A unit with nothing to read: still a step, so the end stays reachable.
                out[i].add(Edge(i, i + 1, "", "", UNK_LOG, 0, fallback = true))
                continue
            }
            if (!dict.isEmpty || user.hasPrefix("")) {
                walk.extend(i, i, 0, "", 0 until dict.size, 0.0, 0, false)
            }
            out[i].add(Edge(i, first.to, first.reading, first.reading, UNK_LOG, 0, fallback = true))
        }
        return out
    }

    /** One decode's word enumeration: everything [extend] and [emitSpan] share. */
    private class Walk(
        val graph: Graph,
        val dict: KotlinConversionDictionary,
        val opts: Opts,
        val out: Array<MutableList<Edge>>,
        val learned: (String) -> Map<String, Int>?,
        val adjust: WordAdjust?,
        val user: UserWords,
    ) {
        /**
         * Walks forward from node [start], extending the reading one arc at a time
         * and narrowing the candidate rows with it. [syllables] is how many the
         * reading so far spells, [depth] how many arcs it took. Recursion depth is
         * bounded by [Opts.maxWordUnits], so it cannot outrun the stack.
         */
        @Suppress("LongParameterList")
        fun extend(
            start: Int,
            node: Int,
            depth: Int,
            prefix: String,
            range: IntRange,
            penalty: Double,
            syllables: Int,
            corrected: Boolean,
        ) {
            if (node >= graph.end || syllables >= opts.maxWordUnits) return
            val ambiguous = depth < opts.maxAmbiguousSpan
            for (arc in graph.arcs[node]) {
                if (!ambiguous && arc.guess) continue
                val reading = prefix + arc.reading
                // The prune that makes per-unit ambiguity affordable: no word starts
                // this way, so nothing longer can either — unless the user taught
                // a phrase that does.
                val sub = dict.prefixRange(reading, range)
                if (sub.isEmpty() && !user.hasPrefix(reading)) continue
                val cost = penalty + arc.penalty
                val fixed = corrected || arc.correction
                emitSpan(start, arc.to, reading, cost, syllables + 1, fixed)
                extend(start, arc.to, depth + 1, reading, sub, cost, syllables + 1, fixed)
            }
        }

        /** Adds the best [Opts.spanCandCap] words read exactly [reading] over the span. */
        @Suppress("LongParameterList")
        private fun emitSpan(start: Int, end: Int, reading: String, penalty: Double, syllables: Int, corrected: Boolean) {
            val rows = dict.rowsFor(reading)
            // Asked once per span, not once per row: this reaches a synchronized
            // store, and a long buffer enumerates thousands of rows across a
            // hundred-odd spans (see [LEARN_WEIGHT]).
            val picks = learned(reading)
            if (rows.isEmpty() && picks.isNullOrEmpty()) return
            var usable = if (opts.charPerUnit) rows.filter { dict.wordLength(it) >= syllables } else rows.toList()
            // A word the user blocked from the candidate menu is not a word here at
            // all, so no path is built through it either.
            val blocked = adjust?.blocked.orEmpty()
            if (blocked.isNotEmpty()) usable = usable.filterNot { dict.word(it) in blocked }
            val cap = maxOf(opts.spanCandCap, opts.limit)
            val kept = usable.sortedByDescending { dict.frequency(it) }.take(cap)
            val maxFreq = kept.firstOrNull()?.let { dict.frequency(it) }?.coerceAtLeast(1) ?: USER_WORD_FREQ
            val seen = HashSet<String>()
            for (row in kept) {
                val freq = dict.frequency(row).coerceAtLeast(1)
                var emission = BETA * (ln(freq.toDouble()) - ln(maxFreq.toDouble())) + penalty
                if (opts.charPerUnit) {
                    emission += opts.overLengthPenalty * (dict.wordLength(row) - syllables)
                }
                val word = dict.word(row)
                seen.add(word)
                out[start].add(
                    Edge(start, end, word, reading, emission + adjustments(word, picks), dict.frequency(row), correction = corrected),
                )
            }
            // A phrase the user taught that the dictionary does not have under
            // this reading: a word like any other from here on, so paths are
            // built through it and it can lead (#580 follow-up).
            if (picks == null) return
            for ((word, chosen) in picks.toSortedMap()) {
                if (chosen <= 0 || word in seen || word in blocked) continue
                val length = word.codePointCount(0, word.length)
                if (opts.charPerUnit && length != syllables) continue
                out[start].add(
                    Edge(start, end, word, reading, penalty + adjustments(word, picks), USER_WORD_FREQ, correction = corrected, user = true),
                )
            }
        }

        /** Picks, menu steps and the word's own cost, for [word]. */
        private fun adjustments(word: String, picks: Map<String, Int>?): Double {
            var add = 0.0
            val chosen = picks?.get(word) ?: 0
            if (chosen > 0) add += LEARN_WEIGHT * ln(1.0 + chosen)
            val bias = adjust?.bias?.get(word) ?: 0
            if (bias != 0) add += BIAS_WEIGHT * bias
            opts.wordPenalty?.let { add += it(word) }
            return add
        }
    }

    /**
     * Best score from each node to the end, scored without context.
     *
     * A deliberate approximation: it drops the bigram conditioning across the
     * boundary between a candidate first word and the remainder. Getting that
     * exactly right needs k-best backtrace over (position, last word) states, for
     * a difference nobody can perceive below rank two.
     */
    private fun suffixScores(edges: Array<MutableList<Edge>>, sc: Scorer, n: Int): DoubleArray {
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        best[n] = 0.0
        for (p in n - 1 downTo 0) {
            for (e in edges[p]) {
                val rest = best[e.to]
                if (rest == Double.NEGATIVE_INFINITY) continue
                val score = sc.lm(null, e) + e.emission + rest
                if (score > best[p]) best[p] = score
            }
        }
        return best
    }

    /**
     * Whole-buffer paths of two or more words, best first, searched with
     * [Opts.stitchBonus] added per word (see [Opts.stitch]).
     *
     * A second search rather than a k-best read-off of [viterbi], because the
     * two want different things. That one prunes to one path per distinct last
     * word, which is exactly right for finding *the* best reading — only the
     * last word conditions what follows, so anything worse behind the same word
     * can never win. Here it is the opposite: 和的 and 好的 end in the same 的
     * and differ only in the word before it, so that prune keeps one of them and
     * throws the alternative the user was looking for away. This one carries the
     * text so far in the key instead, which is what makes the list diverse.
     */
    private fun stitchedPaths(
        edges: Array<MutableList<Edge>>,
        sc: Scorer,
        opts: Opts,
        n: Int,
        context: String?,
    ): List<List<Edge>> {
        val live = arrayOfNulls<MutableList<Path>>(n + 1)
        live[0] = mutableListOf(Path(context, 0.0, null, null))
        for (p in 0..n) {
            val here = live[p] ?: continue
            val bestByText = LinkedHashMap<String, Path>()
            for (path in here) {
                val key = textOf(path)
                val cur = bestByText[key]
                if (cur == null || path.score > cur.score) bestByText[key] = path
            }
            val pruned = bestByText.values.sortedByDescending { it.score }.take(opts.beam)
            live[p] = pruned.toMutableList()
            if (p == n) break
            for (path in pruned) {
                for (e in edges[p]) {
                    if (e.fallback) continue
                    val next = live[e.to] ?: mutableListOf<Path>().also { live[e.to] = it }
                    next.add(Path(e.text, path.score + sc.lm(path.word, e) + e.emission + opts.stitchBonus, path, e))
                }
            }
        }
        val ends = live[n]?.sortedByDescending { it.score } ?: return emptyList()
        val out = ArrayList<List<Edge>>(opts.stitch)
        for (end in ends) {
            val path = pathOf(end)
            if (path.size < 2) continue
            out.add(path)
            if (out.size >= opts.stitch) break
        }
        return out
    }

    /** The text a path has committed so far — the key that keeps the list diverse. */
    private fun textOf(path: Path): String {
        val parts = ArrayList<String>()
        var cur: Path? = path
        while (cur?.edge != null) {
            parts.add(cur.word ?: break)
            cur = cur.prev
        }
        if (parts.isEmpty()) return ""
        parts.reverse()
        return parts.joinToString("")
    }

    /** The edges [end] was built from, in order. */
    private fun pathOf(end: Path): List<Edge> {
        val path = ArrayList<Edge>()
        var cur: Path? = end
        while (cur != null) {
            val edge = cur.edge ?: break
            path.add(edge)
            cur = cur.prev
        }
        path.reverse()
        return path
    }

    /**
     * Viterbi over the lattice, carrying the previous word so bigrams can apply.
     * Returns the surviving paths at every node, not only at the end: under
     * [Opts.coverageOrder] the best readings of each shorter stretch are
     * candidates too, and they are exactly what the search already kept there.
     * The start carries [context], so the first word is scored as following it.
     */
    private fun viterbi(
        edges: Array<MutableList<Edge>>,
        sc: Scorer,
        opts: Opts,
        n: Int,
        context: String?,
    ): Array<MutableList<Path>?> {
        val live = arrayOfNulls<MutableList<Path>>(n + 1)
        live[0] = mutableListOf(Path(context, 0.0, null, null))
        for (p in 0..n) {
            val here = live[p] ?: continue
            // One path per distinct previous word — anything worse with the same
            // word can never win, since only the word conditions what follows.
            val bestByWord = LinkedHashMap<String?, Path>()
            for (path in here) {
                val cur = bestByWord[path.word]
                if (cur == null || path.score > cur.score) bestByWord[path.word] = path
            }
            val pruned = bestByWord.values.sortedByDescending { it.score }.take(opts.beam)
            live[p] = pruned.toMutableList()
            if (p == n) break
            for (path in pruned) {
                for (e in edges[p]) {
                    val next = live[e.to] ?: mutableListOf<Path>().also { live[e.to] = it }
                    next.add(Path(e.text, path.score + sc.lm(path.word, e) + e.emission, path, e))
                }
            }
        }
        return live
    }
}
