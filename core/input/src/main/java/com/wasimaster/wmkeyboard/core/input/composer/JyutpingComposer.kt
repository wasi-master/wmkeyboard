package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/**
 * Cantonese Jyutping (粵拼) input. The roman buffer is Jyutping with optional
 * tone digits, and the strip offers Hanzi from the Cantonese pack — a table of
 * its own, since Cantonese readings are nothing like Mandarin ones (`nei5hou2`
 * where Mandarin has `nihao`).
 *
 * The same segmenting, prefix-committing shape as [PinyinComposer]: a
 * multi-syllable buffer offers both the whole phrase and its leading syllable,
 * each tagged with the input length it consumed, so a commit takes that prefix
 * and re-converts the tail.
 */
object JyutpingComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingJyutping
    override val readsLatinLetters: Boolean get() = true

    /**
     * Tones are digits 1–6, so a digit typed mid-syllable feeds the buffer
     * instead of committing it and typing a number. Unlike T9 a digit cannot
     * *start* a syllable, so [digitsStartBuffer] stays false and typing a plain
     * number on an empty buffer still works.
     */
    override val bufferDigits: Boolean get() = true

    /** Reading space for learned picks: Cantonese romanisation, not Mandarin. */
    private const val NAMESPACE = "jyutping"

    /** Lazy-pronunciation variants offered per syllable, beyond the one typed. */
    private const val LAZY_VARIANTS = 2

    /**
     * What a lazy-pronunciation variant costs, per syllable that differs from
     * what was typed — the same lean [PinyinComposer] applies to Fuzzy Pinyin, so
     * the spelling the user chose still leads unless a merged reading is far
     * likelier.
     */
    private val LAZY_PENALTY = ln(0.15)

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /** The composing region shows the Jyutping as typed, tone digits and all. */
    override fun composeBuffer(buffer: String): String = buffer.lowercase()

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

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }

    override val adjustsChoices: Boolean get() = true

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.adjust(NAMESPACE, cand.reading, cand.text, cand.text, adjustment)
    }


    /** A candidate word and the number of input chars (tone digits included) it covers. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    /**
     * Candidates for [buffer], longest reading first so a whole phrase outranks
     * its leading syllable, each tagged with its consumed input length. Falls
     * back to the dictionary's own prefix matching when nothing segments yet — a
     * still-typing first syllable — mirroring [PinyinComposer].
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val dict = CjkDictionaries.jyutping
        val segs = JyutpingSyllables.segment(buffer)
        if (segs.isEmpty()) {
            return dict.candidates(buffer, LOOKUP_LIMIT)
                .flatMap { word -> HanVariant.traditionalForms(word).map { Cand(it, buffer.length, buffer) } }
                .distinctBy { it.text }
        }
        val input = latticeInput(segs)
        val decoded = Lattice.decode(
            input,
            dict,
            CjkDictionaries.ngrams,
            Lattice.Opts(limit = LOOKUP_LIMIT),
            user = CjkLearning.userData(NAMESPACE),
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading) } }
            .distinctBy { it.text }
        return CjkLearning.rank(NAMESPACE, decoded, { it.text }, { it.reading })
    }

    /**
     * The lattice input for [segs]: one unit per syllable, offering the syllable
     * as typed and — with lazy pronunciation on — a couple of merged spellings
     * behind a penalty. Options rather than pre-built readings, so a merger no
     * word starts with is pruned by one binary search.
     */
    private fun latticeInput(segs: List<Seg>): Lattice.Input {
        val n = segs.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + segs[i].inputLen
        if (!CjkConfig.lazyJyutping) {
            return Lattice.Input(
                boundaries,
                Array(n) { arrayOf(segs[it].syllable) },
                Array(n) { DoubleArray(1) },
            )
        }
        val options = Array(n) { i ->
            val typed = segs[i].syllable
            val variants = JyutpingFuzzy.expand(typed, JyutpingSyllables.valid)
                .filter { it != typed }
                .take(LAZY_VARIANTS)
            (listOf(typed) + variants).toTypedArray()
        }
        val penalties = Array(n) { i ->
            DoubleArray(options[i].size) { o -> if (o == 0) 0.0 else LAZY_PENALTY }
        }
        return Lattice.Input(boundaries, options, penalties)
    }

    private val cache = RankCache<List<Cand>>()
}
