package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/**
 * Chinese Pinyin input. The roman buffer is toneless pinyin (or Double Pinyin
 * key codes), shown in the composing region, and the strip offers Hanzi/word
 * candidates from the pinyin→Hanzi pack ([isConversion] + [candidates]).
 *
 * The buffer is split into syllables so a multi-syllable reading like `nihao`
 * offers both the whole-phrase 你好 and the leading-syllable 你/尼/…, each
 * remembering how many *input* chars it consumed ([consumedFor]) so the service
 * commits that prefix and re-converts the tail. Three [CjkConfig] knobs feed in
 * at call time: Double Pinyin ([DoublePinyin]) changes how the buffer segments,
 * Jianpin ([Jianpin]) lets a bare initial stand in for a whole syllable, and
 * Fuzzy Pinyin ([PinyinFuzzy]) widens each syllable's lookup. With no dictionary
 * match the raw pinyin commits, so the buffer never traps the user.
 *
 * Beyond the reading itself: the word before the caret ([CjkContext]) is
 * the context the first word is scored after; phrases the user built pick by
 * pick are learned as words, and what they write after what as word pairs;
 * `rq`, `sj`, `xq` and `v` + a number type dates and numerals
 * ([PinyinShortcuts]); a backtick and stroke letters (`` `hs ``) narrow the
 * list to characters written with those first strokes; and once a buffer is
 * committed, [predictions] offers what usually comes next.
 */
object PinyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams
    override val readsLatinLetters: Boolean get() = true

    /** Reading space for learned picks: pinyin letters, whatever scheme typed them. */
    private const val NAMESPACE = "pinyin"

    private const val LIMIT = 12

    /**
     * How deep [consumedFor] ranks. The expanded grid shows far more than the
     * strip, and a candidate the user can tap but the ranking cannot find would
     * fall through to "consumed the whole buffer" and swallow their text.
     */
    private const val LOOKUP_LIMIT = 128

    /** Fuzzy variants offered per syllable, beyond the one actually typed. */
    private const val FUZZY_VARIANTS = 2

    /**
     * What a fuzzy syllable costs, per syllable that differs from what was typed.
     * An exact match therefore loses only to a fuzzy word about seven times more
     * probable, and a one-syllable slip outranks a three-syllable one.
     */
    private val FUZZY_PENALTY = ln(0.15)

    /**
     * What an abbreviated syllable costs. Most of the time this decides nothing
     * — a bare `h` has no exact reading, so every option pays the same — and the
     * real ordering comes from the decoder's word cost: a phrase covering N
     * units always beats N stitched single characters, and a unit typed in full
     * pays nothing at all, which is what puts exact > mixed > pure jianpin. It
     * only discriminates on `a`, `e` and `o`, which are both a syllable and an
     * initial: typed alone, the exact 啊 should lead the abbreviated 爱.
     */
    private val PARTIAL_PENALTY = ln(0.15)

    /** Stitched whole-buffer readings offered beyond the best path (#405). */
    private const val STITCH_CANDIDATES = 3

    /**
     * Shortest buffer worth stitching, in units, and only where it is
     * abbreviated. A fully spelled-out buffer is already decoded correctly —
     * offering 呢好 under 你好 for `nihao` would be noise — and at two units
     * the alternatives are worth less than the strip slots they would cost:
     * `bj` means 北京 and `zg` means 中国, and stitching two single characters
     * there pushes those down for 不就 and 在个. From three units the
     * dictionary runs out of whole phrases and stitching is the only way to
     * reach 我的妈妈 or 我不喜欢你 without a word-pair model.
     */
    private const val MIN_STITCH_UNITS = 3

    /**
     * What reading a mistyped key as its neighbour costs (#580): `xiqn` is
     * `xian` with the `a` missed, and a stretch typed that way also reads as
     * initials, which is worth keeping. At ln(0.05) the repair wins only where
     * the initials make nothing likelier.
     */
    private val TYPO_PENALTY = ln(0.05)

    /**
     * Two keys typed the wrong way round (`qign`): a likelier slip than a
     * wrong key, libime's "advanced typo", so it costs half as much.
     */
    private val SWAP_PENALTY = ln(0.1)

    /** One key too many (`zhonng`) or one too few (`zhng`): the least likely repair. */
    private val EXTRA_PENALTY = ln(0.03)

    /**
     * Another spelling of a syllable ([PinyinSpellings.aliases]): `lue`,
     * `jv`, `liou`, `bagn`. Deliberate or nearly so, so it costs little; only
     * enough that where the same letters also spell real syllables, those lead.
     */
    private val ALIAS_PENALTY = ln(0.6)

    /** Marks a stroke filter in the buffer: `nihao` + `` ` `` + strokes. */
    private const val STROKE_MARK = '`'

    /**
     * Stroke letters after [STROKE_MARK], to the stroke table's digits:
     * 横 h, 竖 s, 撇 p, 点 d or n (捺), 折 z — the letters fcitx and Sogou use.
     */
    private val STROKE_LETTERS = mapOf('h' to '1', 's' to '2', 'p' to '3', 'd' to '4', 'n' to '4', 'z' to '5')

    /** How the composing region draws each stroke digit. */
    private const val STROKE_GLYPHS = "一丨丿丶乙"

    /** A phrase pieced together pick by pick is learned up to this many syllables. */
    private const val PHRASE_SYLLABLES = 4

    /** ...or up to this many, where every pick was a single character. */
    private const val PHRASE_SINGLES = 6

    /** Longest context suffix [predictions] looks a follower up for. */
    private const val PREDICT_CONTEXT = 4

    /**
     * What finishing a word costs against a word that follows one (无 → 聊
     * from 无聊 against 无 → 法), in nats: a completion is half the evidence.
     */
    private val COMPLETION_COST = ln(0.5)

    /**
     * Buffer length, in syllables, past which the lattice gives way to plain
     * prefix lookup. Nobody types two dozen syllables without committing, and the
     * bound is what keeps a pathological buffer off the frame budget.
     */
    private const val MAX_LATTICE_UNITS = 24

    /**
     * The composing region shows the pinyin — translated from key codes in
     * Double Pinyin — and a stroke filter as the strokes themselves.
     */
    override fun composeBuffer(buffer: String): String {
        val cut = buffer.indexOf(STROKE_MARK)
        if (cut >= 0) {
            val strokes = strokeDigits(buffer.substring(cut + 1)).map { STROKE_GLYPHS[it - '1'] }.joinToString("")
            return composeBuffer(buffer.substring(0, cut)) + " " + strokes
        }
        val table = doublePinyinTable()
        return if (table != null) DoublePinyin.translate(buffer.lowercase(), table, PinyinSyllables.valid)
        else buffer.lowercase()
    }

    /**
     * A backtick after a reading starts a stroke filter, and a `v` number
     * takes its digits and one decimal point.
     */
    override fun buffersChar(c: Char, composing: CharSequence): Boolean = when {
        c == STROKE_MARK -> composing.isNotEmpty() && STROKE_MARK !in composing && !PinyinShortcuts.isNumber(composing.toString())
        CjkConfig.pinyinShortcuts && PinyinShortcuts.continuesNumber(composing, c) -> true
        else -> false
    }

    /** The stroke digits a filter's letters spell; anything else is dropped. */
    private fun strokeDigits(letters: String): String =
        letters.lowercase().mapNotNull { STROKE_LETTERS[it] ?: it.takeIf { d -> d in '1'..'5' } }.joinToString("")

    /**
     * The keys as pressed. In Double Pinyin the composing region shows the
     * pinyin they spell (`nihc` as *nihao*), but Enter is "write what I typed",
     * so it is what goes in (#514). In full pinyin the two are the same.
     */
    override fun typedReading(buffer: String): String = buffer

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer.lowercase()).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int {
        val b = buffer.lowercase()
        return ranked(b).firstOrNull { it.text == chosen }?.consumed ?: b.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val b = buffer.lowercase()
        return ranked(b).getOrNull(index)?.consumed ?: b.length
    }

    override fun nextReadingLetter(buffer: String, index: Int): Char? {
        val b = buffer.lowercase()
        val cand = ranked(b).getOrNull(index)?.takeIf { !it.special && it.consumed == b.length } ?: return null
        val typed = b.replace("'", "")
        val reading = cand.reading.replace("'", "")
        doublePinyinTable()?.let { table ->
            return DoublePinyin.nextKey(typed, syllablesOf(reading), table, PinyinSyllables.valid, ::fuzzySpellings)
        }
        if (reading.length > typed.length && reading.startsWith(typed)) return reading[typed.length]
        val abbreviated = jianpin() != null
        if (CjkConfig.fuzzyPinyin) {
            return PinyinSyllables.nextFuzzyLetter(typed, syllablesOf(reading), ::fuzzySpellings, abbreviated)
        }
        return if (abbreviated) PinyinSyllables.nextInitial(typed, syllablesOf(reading)) else null
    }

    /** What [syllable] may be typed as under the Fuzzy Pinyin switch, itself first. */
    private fun fuzzySpellings(syllable: String): Set<String> =
        if (CjkConfig.fuzzyPinyin) {
            PinyinFuzzy.expand(syllable, PinyinSyllables.valid, CjkConfig.fuzzyPinyinPairs)
        } else {
            setOf(syllable)
        }

    private fun syllablesOf(reading: String): List<String> = PinyinSyllables.segment(reading).map { it.syllable }

    /** The dictionary the map was built from, and each character's commonest reading. */
    @Volatile
    private var characterReadings: Pair<ConversionDictionary, Map<Int, String>>? = null

    override fun primePredictionKeys() {
        val dict = CjkDictionaries.pinyin
        if (dict.isEmpty || characterReadings?.first === dict) return
        val readings = HashMap<Int, String>()
        val best = HashMap<Int, Int>()
        for (row in 0 until dict.size) {
            val word = dict.word(row)
            if (word.isEmpty() || word.codePointCount(0, word.length) != 1) continue
            val character = word.codePointAt(0)
            val frequency = dict.frequency(row)
            if (frequency > (best[character] ?: Int.MIN_VALUE)) {
                best[character] = frequency
                readings[character] = dict.reading(row)
            }
        }
        characterReadings = dict to readings
    }

    override fun predictionKey(word: String): Char? {
        if (word.isEmpty()) return null
        val built = characterReadings?.takeIf { it.first === CjkDictionaries.pinyin } ?: return null
        val reading = built.second[word.codePointAt(0)]?.takeIf { it.isNotEmpty() } ?: return null
        val table = doublePinyinTable() ?: return reading.first()
        return DoublePinyin.keysOf(table, PinyinSyllables.valid)[reading]?.first()
    }

    override fun learnChoice(buffer: String, index: Int) {
        val b = buffer.lowercase()
        val cand = ranked(b).getOrNull(index) ?: return
        if (cand.special) {
            run = null
            return
        }
        // Learned as the decoder found it, not as Traditional output drew it:
        // the decoder looks picks up by its own words.
        CjkLearning.learn(NAMESPACE, cand.reading, cand.source)
        val current = run?.takeIf { it.rest == b }
        val previous = current?.parts?.lastOrNull() ?: contextWord()
        if (previous != null) CjkLearning.learnPair(NAMESPACE, previous, cand.source)
        val rest = remainderAfterCommit(b.substring(cand.consumed.coerceIn(0, b.length)))
        val parts = current?.parts.orEmpty() + cand.source
        val reading = (current?.reading ?: "") + cand.reading
        if (rest.isNotEmpty()) {
            run = Run(rest, parts, reading)
            return
        }
        run = null
        learnPhrase(parts, reading)
    }

    /**
     * One buffer committed a pick at a time: what is left of it, and what the
     * picks so far wrote and read. Main-thread state, like the service's own
     * composing buffer; a pick for any other buffer starts afresh.
     */
    private class Run(val rest: String, val parts: List<String>, val reading: String)

    @Volatile
    private var run: Run? = null

    /**
     * A buffer committed in two picks or more is a phrase the decoder did not
     * offer whole: learned as one word, so it does next time — 我的妈妈 for
     * `wdmm` once it has been picked as 我的 + 妈妈. Kept short, the way
     * libime keeps it (four syllables, or single characters), and skipped
     * where the word pairs already join every pick to the next, since the
     * decoder will find that reading on its own.
     */
    private fun learnPhrase(parts: List<String>, reading: String) {
        if (parts.size < 2) return
        val text = parts.joinToString("")
        val syllables = text.codePointCount(0, text.length)
        val singles = parts.all { it.codePointCount(0, it.length) == 1 }
        if (syllables > (if (singles) PHRASE_SINGLES else PHRASE_SYLLABLES)) return
        val pairs = CjkDictionaries.pinyinNgrams
        if (!pairs.isEmpty && parts.zipWithNext().all { (a, b) -> pairs.bigram(a, b) > 0 }) return
        CjkLearning.learn(NAMESPACE, reading, text)
    }


    private fun doublePinyinTable(): DoublePinyin.Table? {
        val scheme = CjkConfig.doublePinyin
        return if (scheme == DoublePinyinScheme.OFF) null else DoublePinyin.tableFor(scheme)
    }

    /**
     * Jianpin's index while it applies: full pinyin with the switch on. Under
     * Double Pinyin a syllable is always two keys, so there is nothing to
     * abbreviate and the index is left out of the segmentation entirely. An
     * empty custom scheme types full pinyin, so it keeps Jianpin too.
     */
    private fun jianpin(): Jianpin.Index? =
        if (CjkConfig.jianpin && doublePinyinTable() == null) Jianpin.index else null

    /**
     * Syllables of [buffer] with per-syllable input spans, in the active input
     * mode. With Jianpin on the inventory also holds every initial, so `hd`
     * splits into `h` and `d`; a full syllable is always longer than its own
     * initial, so the longest-first walk keeps `nihao` as `ni` + `hao`.
     */
    private fun segments(buffer: String): List<Seg> {
        val table = doublePinyinTable()
        if (table != null) return DoublePinyin.segments(buffer, table, PinyinSyllables.valid)
        val jianpin = jianpin()
        return if (jianpin != null && jianpin.units.isNotEmpty()) PinyinSyllables.segment(buffer, jianpin.units)
        else PinyinSyllables.segment(buffer)
    }

    /**
     * The lattice input for [segs]: one unit per syllable, each offering the
     * syllable as typed, then — with Fuzzy Pinyin on — a couple of confusable
     * spellings behind a penalty, then — with Jianpin on — every syllable an
     * abbreviated unit could stand for. A bare initial has no as-typed reading,
     * so its options are abbreviations alone; `a`, `e` and `o` are both.
     *
     * The variants are *options*, not readings. Building the cartesian product
     * of readings was what forced a cap, and the cap dropped real candidates;
     * the decoder instead prunes each variant against the dictionary before
     * extending it, so an impossible spelling costs one binary search.
     */
    private fun latticeInput(segs: List<Seg>): Lattice.Input {
        val n = segs.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + segs[i].inputLen
        val fuzzy = CjkConfig.fuzzyPinyin
        val fuzzyPairs = CjkConfig.fuzzyPinyinPairs
        val valid = PinyinSyllables.valid
        val jianpin = jianpin()
        val units = List(n) { i -> unitOptions(segs[i].syllable, valid, fuzzy, fuzzyPairs, jianpin) }
        // A typed digraph reads both ways: `sh` is s + h (上海) by segmentation,
        // and the initial sh… (是) by merging the two units. An apostrophe
        // between them is the user's own boundary, so it blocks the merge.
        val merged = Array(n) { i ->
            val digraph = segs[i].syllable + segs.getOrNull(i + 1)?.syllable
            if (jianpin == null || i + 1 >= n || segs[i + 1].inputLen != 1 || digraph !in Jianpin.DIGRAPHS) emptyArray()
            else jianpin.expansions[digraph].orEmpty().toTypedArray()
        }
        return Lattice.Input(
            boundaries,
            Array(n) { units[it].readings },
            Array(n) { units[it].costs },
            IntArray(n) { units[it].partialFrom },
            merged,
            Array(n) { DoubleArray(merged[it].size) { PARTIAL_PENALTY } },
        )
    }

    /**
     * The arcs one span of the buffer contributes to the graph: [unit] as typed
     * with its fuzzy variants and abbreviations, the way [latticeInput] reads a
     * unit; a digraph's merged syllables; or a mistyped key's repair.
     */
    private fun arcsFor(unit: String, to: Int, kind: PinyinGraph.Kind): List<Lattice.Arc> = when (kind) {
        PinyinGraph.Kind.UNIT -> {
            val o = unitOptions(unit, PinyinSyllables.valid, CjkConfig.fuzzyPinyin, CjkConfig.fuzzyPinyinPairs, jianpin())
            o.readings.indices.map { i -> Lattice.Arc(to, o.readings[i], o.costs[i], guess = i > 0 && i < o.partialFrom) }
        }
        // Two letters typed, so two abbreviations' worth: charged as one, a
        // digraph syllable outscored every reading of the letters as two
        // initials, and `sh` buried 上海 under a hundred single characters.
        PinyinGraph.Kind.DIGRAPH -> jianpin()?.expansions?.get(unit).orEmpty().map { Lattice.Arc(to, it, 2 * PARTIAL_PENALTY) }
        PinyinGraph.Kind.TYPO -> listOf(Lattice.Arc(to, unit, TYPO_PENALTY, guess = true, correction = true))
        PinyinGraph.Kind.SWAP -> listOf(Lattice.Arc(to, unit, SWAP_PENALTY, guess = true, correction = true))
        PinyinGraph.Kind.EXTRA -> listOf(Lattice.Arc(to, unit, EXTRA_PENALTY, guess = true, correction = true))
        // Another spelling of the syllable [unit], read with its fuzzy
        // variants the way the syllable itself would be.
        PinyinGraph.Kind.ALIAS -> {
            val o = unitOptions(unit, PinyinSyllables.valid, CjkConfig.fuzzyPinyin, CjkConfig.fuzzyPinyinPairs, null)
            o.readings.indices.map { i -> Lattice.Arc(to, o.readings[i], o.costs[i] + ALIAS_PENALTY, guess = i > 0) }
        }
        // The rest of a syllable not typed yet: an abbreviation, like an initial.
        PinyinGraph.Kind.PARTIAL -> listOf(Lattice.Arc(to, unit, PARTIAL_PENALTY))
    }

    /** One unit's readings, what each costs, and where the abbreviations begin. */
    private class UnitOptions(val readings: Array<String>, val costs: DoubleArray, val partialFrom: Int)

    private fun unitOptions(
        typed: String,
        valid: Set<String>,
        fuzzy: Boolean,
        fuzzyPairs: Set<String>,
        jianpin: Jianpin.Index?,
    ): UnitOptions {
        // Without Jianpin every unit came out of the syllable inventory.
        val exact = jianpin == null || typed in valid
        val readings = ArrayList<String>(4)
        val costs = ArrayList<Double>(4)
        if (exact) { readings.add(typed); costs.add(0.0) }
        if (exact && fuzzy) {
            for (v in PinyinFuzzy.expand(typed, valid, fuzzyPairs).filter { it != typed }.take(FUZZY_VARIANTS)) {
                readings.add(v); costs.add(FUZZY_PENALTY)
            }
        }
        val partialFrom = readings.size
        if (jianpin != null) {
            for (s in jianpin.expansions[typed].orEmpty()) {
                if (s == typed) continue
                readings.add(s); costs.add(PARTIAL_PENALTY)
            }
        }
        return UnitOptions(readings.toTypedArray(), costs.toDoubleArray(), partialFrom)
    }

    /**
     * Candidates for [buffer], best first, each tagged with its consumed input
     * length. Falls back to the dictionary's own prefix matching when the buffer
     * has no segmentable syllable yet (a still-typing first syllable).
     *
     * Cached on the buffer, because the service ranks the same one twice per
     * commit — once to fill the strip and once to ask how much to delete.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        if (CjkConfig.pinyinShortcuts && PinyinShortcuts.isNumber(buffer)) {
            return PinyinShortcuts.numbers(buffer).map { Cand(it, buffer.length, buffer, it, special = true) }
        }
        val cut = buffer.indexOf(STROKE_MARK)
        if (cut >= 0) return filtered(buffer, cut)
        val context = contextWord()
        val base = cache.get(if (context == null) buffer else buffer + '\u0000' + context) { rank(buffer, context) }
        val extra = if (CjkConfig.pinyinShortcuts) PinyinShortcuts.expand(buffer) else emptyList()
        if (extra.isEmpty()) return base
        // Behind the reading's own best candidate: `sj` is also 时间 and 世界.
        val at = minOf(1, base.size)
        return base.take(at) + extra.map { Cand(it, buffer.length, buffer, it, special = true) } + base.drop(at)
    }

    /**
     * [buffer]'s reading before the backtick at [cut], narrowed to the
     * candidates whose first character starts with the strokes after it. A
     * candidate covering the whole reading consumes the filter too; one that
     * covers less leaves the rest of the reading, and the filter goes with
     * the pick ([remainderAfterCommit]). Without the stroke table the filter
     * narrows nothing.
     */
    private fun filtered(buffer: String, cut: Int): List<Cand> {
        val reading = buffer.substring(0, cut)
        if (reading.isEmpty()) return emptyList()
        val strokes = strokeDigits(buffer.substring(cut + 1))
        val table = CjkDictionaries.stroke
        val all = ranked(reading)
        val kept = if (strokes.isEmpty() || table.isEmpty) {
            all
        } else {
            all.filter { c ->
                // By the decoder's own text, which the stroke table is keyed
                // by, and then the form shown, for a table that has it too.
                fun first(t: String) = if (t.isEmpty()) t else t.substring(0, t.offsetByCodePoints(0, 1))
                (table.codeOf(first(c.source)) ?: table.codeOf(first(c.text)))?.startsWith(strokes) == true
            }
        }
        return kept.map { c -> if (c.consumed >= reading.length) c.copy(consumed = buffer.length) else c }
    }

    /** A stroke filter goes with the pick it narrowed the list for. */
    override fun remainderAfterCommit(rest: String): String {
        val cut = rest.indexOf(STROKE_MARK)
        return if (cut < 0) rest else rest.substring(0, cut)
    }

    /**
     * The word before the caret the decoder scores the buffer after: the
     * longest stretch of [CjkContext.before] the word pairs or the user's own
     * pairs know. Null at the start of a sentence, and with neither loaded.
     */
    private fun contextWord(): String? {
        val before = CjkContext.before
        if (before.isEmpty()) return null
        val ngrams = CjkDictionaries.pinyinNgrams
        val pairs = CjkLearning.store?.pairsFor(NAMESPACE).orEmpty()
        if (ngrams.isEmpty && pairs.isEmpty()) return null
        return CjkContext.previousWord(before) { ngrams.unigram(it) > 0 || it in pairs }
    }

    private fun rank(buffer: String, context: String?): List<Cand> {
        val dict = CjkDictionaries.pinyin
        val graph: Lattice.Graph?
        val stitch: Int
        if (doublePinyinTable() != null) {
            // Every syllable is two keys, so there is exactly one way to split
            // the buffer and nothing for a graph to choose between.
            val segs = segments(buffer)
            graph = if (segs.isEmpty() || segs.size > MAX_LATTICE_UNITS) null else Lattice.graph(latticeInput(segs))
            stitch = 0
        } else {
            val built = PinyinGraph.build(buffer, PinyinSyllables.valid, jianpin(), ::arcsFor)
            graph = built?.graph?.takeIf { built.units <= MAX_LATTICE_UNITS }
            // Stitching stands in for a word-pair model; with the pairs loaded
            // the decoder ranks those readings itself, and better.
            stitch = if (built != null && built.units >= MIN_STITCH_UNITS && built.abbreviated &&
                CjkDictionaries.pinyinNgrams.isEmpty
            ) {
                STITCH_CANDIDATES
            } else {
                0
            }
        }
        if (graph == null) {
            // Nothing segments yet, or the buffer is longer than anyone types
            // before committing: fall back to plain prefix lookup, which cannot
            // offer a prefix commit but always answers.
            return dict.candidates(buffer, LOOKUP_LIMIT)
                .flatMap { word -> HanVariant.traditionalForms(word).map { Cand(it, buffer.length, buffer, word) } }
                .distinctBy { it.text }
        }
        val opts = Lattice.Opts(limit = LOOKUP_LIMIT, stitch = stitch, coverageOrder = true, hanLevels = true)
        val decoded = Lattice.decode(
            graph,
            dict,
            CjkDictionaries.pinyinNgrams,
            opts,
            CjkLearning.userData(NAMESPACE),
            Lattice.UserScope.ALL,
            context,
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading, c.text) } }
            // Converting to Traditional can merge two Simplified words onto one
            // form, so de-duplicate after the conversion rather than before it.
            .distinctBy { it.text }
        // Grouped by how much of the buffer each covers: a single character
        // picked for `wo` last week moves up among the single characters, not
        // above every reading of the four letters typed (#580).
        // Counted by the decoder's own text, which is what a pick is learned as.
        return CjkLearning.rank(NAMESPACE, decoded, { it.source }, { it.reading }, { it.text }, { it.consumed })
    }

    override val adjustsChoices: Boolean get() = true

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        if (cand.special) return
        CjkLearning.adjust(NAMESPACE, cand.reading, cand.text, cand.source, adjustment)
    }

    /**
     * What usually follows [context] (#580 follow-up): the user's own habits
     * first, then the word pairs' commonest followers of its last word, and
     * the rest of a longer word it starts (无 → 聊, from 无聊). Looked up for
     * every ending of [context] up to four characters, so 喜欢 and 欢 both get
     * asked, and scored on one scale, `ln P(next | ending)`.
     */
    override fun predictions(context: String, limit: Int): List<String> {
        if (!CjkConfig.pinyinPrediction) return emptyList()
        val tail = CjkContext.hanTail(context)
        if (tail.isEmpty()) return emptyList()
        val ngrams = CjkDictionaries.pinyinNgrams
        val history = CjkLearning.store
        val blocked = CjkLearning.adjustFor(NAMESPACE)?.blocked.orEmpty()
        val scores = HashMap<String, Double>()
        fun offer(word: String, score: Double) {
            if (word.isEmpty() || word in blocked) return
            if (score > (scores[word] ?: Double.NEGATIVE_INFINITY)) scores[word] = score
        }
        val points = tail.codePointCount(0, tail.length)
        for (len in minOf(PREDICT_CONTEXT, points) downTo 1) {
            val ending = tail.substring(tail.offsetByCodePoints(tail.length, -len))
            // The user's own habit outranks any corpus.
            history?.followersOf(NAMESPACE, ending)?.forEach { (word, n) -> offer(word, n.toDouble()) }
            val prior = ngrams.unigram(ending)
            if (prior <= 0) continue
            for ((word, n) in ngrams.successors(ending)) offer(word, ln(n.toDouble() / prior))
            for ((word, n) in ngrams.completions(ending, limit)) {
                offer(word.substring(ending.length), ln(n.toDouble() / maxOf(prior, n)) + COMPLETION_COST)
            }
        }
        return scores.entries.sortedByDescending { it.value }
            .take(limit)
            .map { HanVariant.traditionalForms(it.key).first() }
            .distinct()
    }

    override fun learnPrediction(context: String, picked: String) {
        val tail = CjkContext.hanTail(context)
        val ngrams = CjkDictionaries.pinyinNgrams
        val pairs = CjkLearning.store?.pairsFor(NAMESPACE).orEmpty()
        val previous = CjkContext.previousWord(tail) { ngrams.unigram(it) > 0 || it in pairs }
            ?: tail.takeIf { it.isNotEmpty() }?.let { it.substring(it.offsetByCodePoints(it.length, -1)) }
            ?: return
        CjkLearning.learnPair(NAMESPACE, previous, picked)
    }


    /**
     * A candidate word, the input chars (keys) it covers, the reading behind it,
     * and the Simplified text the decoder found, which [text] may be the
     * Traditional form of. The candidate menu acts on [source]: that is what
     * the decoder can recognise next time.
     */
    private data class Cand(
        val text: String,
        val consumed: Int,
        val reading: String,
        val source: String,
        /** A [PinyinShortcuts] answer rather than a conversion: never learned or adjusted. */
        val special: Boolean = false,
    )

    private val cache = RankCache<List<Cand>>()
}
