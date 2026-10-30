package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.engine.NativeEngine

/**
 * JNI surface of the conversion decoder and its tables, in libwm_engine_jni
 * (`native/wm-engine-jni/src/cjk.rs`). [Lattice], [ConversionDictionary],
 * [CjkNgrams] and [HanLevels] hold the handles and wrap them in the types the
 * composers use.
 *
 * Handles are `long`s; 0 is an empty table or nothing, and never reaches this
 * object except where a parameter says so. Lists of strings cross as one string
 * joined by `\u0001`, so a call is one crossing however many there are. A row
 * range comes back as `first shl 32 or endExclusive`.
 */
internal object CjkNative {
    init {
        NativeEngine.requireLoaded()
    }

    /** A handle on the pack at [path], through `<path>.wmcd`; throws `IOException` when unreadable. */
    external fun dictOpen(path: String): Long
    external fun dictParse(text: String): Long
    external fun dictClose(handle: Long)
    external fun dictSize(handle: Long): Int
    external fun dictTotalFreq(handle: Long): Long
    external fun dictExact(handle: Long, reading: String): String
    external fun dictCandidates(handle: Long, reading: String, limit: Int): String
    external fun dictRowsFor(handle: Long, reading: String): Long
    external fun dictPrefixRange(handle: Long, prefix: String, first: Int, endExclusive: Int): Long
    external fun dictWord(handle: Long, row: Int): String
    external fun dictReading(handle: Long, row: Int): String
    external fun dictWordLength(handle: Long, row: Int): Int
    external fun dictFrequency(handle: Long, row: Int): Int
    external fun dictBestFrequency(handle: Long, reading: String, length: Int): Int
    external fun dictQuantile(handle: Long, quantile: Double): Int

    /**
     * From row [start], the rows at [floor] or above, as many as [frequencies]
     * holds: their readings joined, their frequencies and word lengths filled
     * in, and in `next[0]` the row to go on from.
     */
    @Suppress("LongParameterList")
    external fun dictRows(handle: Long, start: Int, floor: Int, frequencies: IntArray, lengths: IntArray, next: IntArray): String

    /** A handle on [handle] (0 for empty) with the rows merged in; 0 when nothing changed. */
    @Suppress("LongParameterList")
    external fun dictPlus(handle: Long, readings: String, words: String, frequencies: IntArray, raises: IntArray): Long

    /** A handle on the pack at [path], through `<path>.wmcd`; throws `IOException` when unreadable. */
    external fun ngramOpen(path: String): Long
    external fun ngramParse(text: String): Long
    external fun ngramClose(handle: Long)
    external fun ngramIsEmpty(handle: Long): Int
    external fun ngramUnigram(handle: Long, word: String): Int
    external fun ngramBigram(handle: Long, previous: String, word: String): Int
    external fun ngramSuccessors(handle: Long, word: String, limit: Int, counts: IntArray): String
    external fun ngramCompletions(handle: Long, prefix: String, limit: Int, counts: IntArray): String

    /** [handle] may be 0 here: no pack. */
    @Suppress("LongParameterList")
    external fun ngramLogProbability(handle: Long, previous: String?, word: String, packFreq: Int, packTotal: Long, weight: Double): Double

    external fun levelsOpen(points: IntArray, levels: ByteArray): Long
    external fun levelsClose(handle: Long)

    /** [picks] is reading, word, reading, word...; [pairs] previous, word...; each with its counts. */
    @Suppress("LongParameterList")
    external fun userOpen(
        picks: String,
        pickCounts: IntArray,
        pairs: String,
        pairCounts: IntArray,
        blocked: String,
        bias: String,
        biasSteps: IntArray,
    ): Long

    external fun userClose(handle: Long)

    /**
     * One decode. [dict], [ngrams], [levels] and [user] may each be 0.
     *
     * [opts] is maxWordUnits, maxAmbiguousSpan, spanCandCap, beam, limit,
     * charPerUnit, stitch, coverageOrder, pathsPerStretch, stretchCap; [reals]
     * is overLengthPenalty, stitchBonus, correctionMargin, pathMargin, and the
     * word pairs' interpolation weight.
     *
     * Returns each candidate's text and reading, joined. Its consumed length,
     * doubled with the low bit set if repaired, lands in [consumedOut], and its
     * score in [scoreOut].
     */
    @Suppress("LongParameterList")
    external fun decode(
        dict: Long,
        ngrams: Long,
        levels: Long,
        user: Long,
        scope: Int,
        consumed: IntArray,
        arcStart: IntArray,
        arcTo: IntArray,
        arcFlags: IntArray,
        arcPenalty: DoubleArray,
        arcReading: String,
        opts: IntArray,
        reals: DoubleArray,
        context: String?,
        consumedOut: IntArray,
        scoreOut: DoubleArray,
    ): String
}
