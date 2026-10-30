package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.File

/**
 * Word unigram and bigram counts for the conversion decoder — how likely 事情 is
 * at all, and how much likelier it becomes right after 这件.
 *
 * Mandarin has one: the `pinyin_ngrams` pack (#580), fetched with the pinyin
 * table. Without it — or for a language with no pack — [EMPTY] is a working
 * state rather than a stub: [logProbability] then falls back to the conversion
 * pack's own frequency column, which makes the decoder a unigram Viterbi.
 *
 * The format is the plain TSV every other pack here uses,
 * with the section decided by field count:
 * ```
 * # wmkeyboard cjk ngram v1
 * 你好\t1234567          <- 2 fields: unigram
 * 这件\t事情\t12345      <- 3 fields: bigram
 * ```
 * The unigram section is not redundant with the conversion pack's frequencies:
 * backoff needs the count of a *word* across all its readings, and that pack is
 * keyed by reading with no way to index the other way. Keeping both sections in
 * one file also keeps them from drifting to different corpora or tokenizers.
 *
 * The table is in Rust (`native/wm-engine/src/cjk/ngrams.rs`): sorted columns of
 * 64-bit hashes with parallel counts, as flat bytes, and this class holds a
 * handle. Hashing costs collisions, but at 64 bits over 300k keys that is a ~1e-7
 * chance of one wrong score, which is not worth a fallback path to fix. A pack
 * opened from its file ([open]) is parsed once and written beside it as
 * `<pack>.wmcd`; after that it is mapped.
 */
class CjkNgrams private constructor(internal val handle: Long) {

    val isEmpty: Boolean = handle == 0L || CjkNative.ngramIsEmpty(handle) != 0

    init {
        if (handle != 0L) NativeHandles.track(this, handle, CjkNative::ngramClose)
    }

    /** Corpus count of [word], or 0 when the model has never seen it. */
    fun unigram(word: String): Int = if (handle == 0L) 0 else CjkNative.ngramUnigram(handle, word)

    /** Corpus count of [previous] immediately followed by [word]. */
    fun bigram(previous: String, word: String): Int =
        if (handle == 0L) 0 else CjkNative.ngramBigram(handle, previous, word)

    /**
     * The words seen most often straight after [word], commonest first, each
     * with the pair's count: what the strip offers once [word] is written
     * (#580 follow-up). Only the [SUCCESSORS] commonest per word are kept.
     */
    fun successors(word: String, limit: Int = SUCCESSORS): List<Pair<String, Int>> {
        if (handle == 0L || limit <= 0) return emptyList()
        val counts = IntArray(limit)
        return counted(CjkNative.ngramSuccessors(handle, word, limit, counts), counts)
    }

    /**
     * Words longer than [prefix] that start with it, commonest first, each with
     * its count: 无聊 for 无, so the strip can offer 聊 the moment 无 is written.
     */
    fun completions(prefix: String, limit: Int): List<Pair<String, Int>> {
        if (handle == 0L || limit <= 0) return emptyList()
        val counts = IntArray(limit)
        return counted(CjkNative.ngramCompletions(handle, prefix, limit, counts), counts)
    }

    /**
     * `log P(word | previous)` by stupid backoff (Brants et al. 2007): the
     * conditional when the pair was seen, otherwise [BACKOFF] times the word's
     * own probability.
     *
     * Stupid backoff rather than Kneser-Ney deliberately. KN wants continuation
     * counts, which is a third table, and at corpus scale the two are hard to
     * tell apart: this is exactly the lots-of-data regime it was published for.
     *
     * [packTotal] and [packFreq] are the conversion pack's own numbers, used when
     * this model is [EMPTY] so the decoder still has a real unigram to rank by.
     * Always finite: an unseen word floors at one notional occurrence rather than
     * `ln(0)`, so a path can never be scored at negative infinity and drop out.
     */
    fun logProbability(previous: String?, word: String, packFreq: Int, packTotal: Long): Double =
        CjkNative.ngramLogProbability(handle, previous, word, packFreq, packTotal, bigramWeight)

    companion object {
        val EMPTY = CjkNgrams(0L)

        /** The published stupid-backoff weight. */
        const val BACKOFF = 0.4

        /**
         * The pair's share when interpolating (libpinyin's form), or 0 for
         * stupid backoff. 0: on the held-out benchmark (`PinyinBenchmarkTest`)
         * backoff beat every weight from 0.3 to 0.85, by up to four points of
         * whole clauses. A variable so that benchmark can compare them again.
         */
        @Volatile
        var bigramWeight: Double = 0.0

        /** Successors kept per word for [successors]. */
        const val SUCCESSORS = 8

        private const val SEP = '\u0001'

        /**
         * The pack in [file], or [EMPTY] if nothing in it parses. Fast after the
         * first time; see the class. Throws `IOException` when it cannot be read.
         */
        fun open(file: File): CjkNgrams = ofHandle(CjkNative.ngramOpen(file.path))

        /**
         * Parses the TSV described on the class. Two fields is a unigram, three a
         * bigram; anything else — blank lines, comments, malformed rows — is
         * skipped, matching [ConversionDictionary.parse]'s tolerance of a pack
         * that picked up stray lines somewhere between the corpus and the device.
         */
        fun parse(lines: Sequence<String>): CjkNgrams =
            ofHandle(CjkNative.ngramParse(lines.joinToString("\n")))

        private fun ofHandle(handle: Long): CjkNgrams {
            val table = CjkNgrams(handle)
            return if (table.isEmpty) EMPTY else table
        }

        private fun counted(joined: String, counts: IntArray): List<Pair<String, Int>> {
            if (joined.isEmpty()) return emptyList()
            return joined.split(SEP).mapIndexed { i, word -> word to counts[i] }
        }
    }
}
