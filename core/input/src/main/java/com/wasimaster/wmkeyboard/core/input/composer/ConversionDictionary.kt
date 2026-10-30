package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.File

/**
 * A reading→candidates table for a conversion IME (Pinyin→Hanzi, kana→Kanji),
 * loaded once from a shipped TSV asset or a downloaded pack. Each line is
 * `reading<TAB>word<TAB>freq`; a reading may repeat across lines, and its
 * candidates are returned most-frequent first.
 *
 * The table makes Chinese and Japanese *typeable* (type a reading, pick a
 * character) without an ML model. An unknown reading simply yields no
 * candidates, and the composer falls back to committing the raw reading (pinyin
 * letters, or kana).
 *
 * ### Where the rows are
 *
 * The packs are big: `ja_kana` is 1.08M rows over 744k distinct readings, and the
 * Java heap this keyboard runs on is not. So the table is in Rust
 * (`native/wm-engine/src/cjk/dict.rs`), as flat columns and two text arenas, and
 * this class holds a handle. A pack opened from its file ([open]) is parsed once
 * and written beside it as `<pack>.wmcd`; after that it is mapped, which takes
 * milliseconds and puts nothing on any heap. Rows are kept sorted by reading, so a
 * lookup is a binary search for the first row with that reading followed by a
 * walk over its contiguous run.
 *
 * A row-level call here is a JNI crossing. The decoder runs on the Rust side and
 * makes none; a loop over rows goes through [forEachRow].
 */
class ConversionDictionary private constructor(internal val handle: Long) {

    internal val size: Int = if (handle == 0L) 0 else CjkNative.dictSize(handle)

    val isEmpty: Boolean get() = size == 0

    /**
     * Total of every row's frequency: the denominator that turns a raw count into
     * log P(word) for the decoder.
     */
    val totalFreq: Long = if (handle == 0L) 0L else CjkNative.dictTotalFreq(handle)

    init {
        if (handle != 0L) NativeHandles.track(this, handle, CjkNative::dictClose)
    }

    /** Candidates for [reading] exactly, best first; empty when unknown. */
    fun exact(reading: String): List<String> =
        if (handle == 0L) emptyList() else split(CjkNative.dictExact(handle, reading))

    /**
     * The contiguous rows whose reading is exactly [reading], empty when unknown.
     *
     * The row-level counterpart to [exact], for callers that score rows before
     * deciding which ones are worth turning into strings.
     */
    fun rowsFor(reading: String): IntRange =
        if (handle == 0L) IntRange.EMPTY else rangeOf(CjkNative.dictRowsFor(handle, reading))

    /**
     * The rows within [within] whose reading starts with [prefix].
     *
     * Extending a reading by one more unit narrows the range with two bounded
     * binary searches, and an **empty range prunes the whole subtree**, so a
     * buffer whose units are individually ambiguous (T9 digits, fuzzy pinyin)
     * costs what the dictionary actually contains rather than the product of the
     * option lists.
     */
    fun prefixRange(prefix: String, within: IntRange = 0 until size): IntRange {
        if (handle == 0L) return IntRange.EMPTY
        return rangeOf(CjkNative.dictPrefixRange(handle, prefix, within.first, within.last + 1))
    }

    /** The word at [row]. */
    fun word(row: Int): String = CjkNative.dictWord(handle, row)

    /** The reading at [row]. */
    fun reading(row: Int): String = CjkNative.dictReading(handle, row)

    /** UTF-16 units in the word at [row], without cutting it. */
    fun wordLength(row: Int): Int = CjkNative.dictWordLength(handle, row)

    /** The corpus frequency of the row at [row]; 0 when the pack listed none. */
    fun frequency(row: Int): Int = CjkNative.dictFrequency(handle, row)

    /** The commonest frequency among [reading]'s words of [length] UTF-16 units, or 0. */
    fun bestFrequency(reading: String, length: Int): Int =
        if (handle == 0L) 0 else CjkNative.dictBestFrequency(handle, reading, length)

    /**
     * Every row at [floor] or above, in order: its reading, its word's length and
     * its frequency. A block of rows at a time, for the glide tables, which walk
     * all of a million-row pack.
     */
    fun forEachRow(floor: Int = Int.MIN_VALUE, action: (reading: String, wordLength: Int, frequency: Int) -> Unit) {
        if (size == 0) return
        val frequencies = IntArray(ROW_BLOCK)
        val lengths = IntArray(ROW_BLOCK)
        val next = IntArray(1)
        var row = 0
        while (row < size) {
            val readings = CjkNative.dictRows(handle, row, floor, frequencies, lengths, next)
            row = next[0]
            var start = 0
            var i = 0
            while (i < ROW_BLOCK && frequencies[i] != NO_ROW) {
                var end = readings.indexOf(SEP, start)
                if (end < 0) end = readings.length
                action(readings.substring(start, end), lengths[i], frequencies[i])
                start = end + 1
                i++
            }
        }
    }

    /**
     * Candidates for a buffer being built up: the exact match first, then matches
     * for its longest key-prefixes, so `nihao` offers 你好 while `ni` already
     * offers 你/尼/…. De-duplicated and capped at [limit].
     */
    fun candidates(reading: String, limit: Int = 12): List<String> =
        if (handle == 0L) emptyList() else split(CjkNative.dictCandidates(handle, reading, limit))

    /**
     * The frequency below which [quantile] of the rows fall (0.5 is the median),
     * or 0 for an empty table. What an imported list is scaled against: the
     * packs count on very different scales — pinyin tops out at 1000, kana at
     * 30000 — and a row from a list has to land somewhere sensible on this one.
     */
    fun frequencyQuantile(quantile: Double): Int =
        if (size == 0) 0 else CjkNative.dictQuantile(handle, quantile)

    /**
     * One row to merge in through [plus]: a reading, the word it converts to
     * and that word's frequency on this table's scale. [raises] lets the row
     * lift the frequency of a word the table already has, which only a list
     * that ranks its own words should do (#633).
     */
    class Row(val reading: String, val word: String, val frequency: Int, val raises: Boolean = false)

    /**
     * This table with [extra] merged in: the words a user imported for the
     * language (#633). A word the table already lists under the same reading
     * keeps its own frequency — a list with no counts of its own would
     * otherwise flatten the pack's ranking to one value — unless the row
     * [Row.raises] it. A row listed twice keeps its higher frequency.
     *
     * Rebuilds the table rather than patching it, since rows are stored sorted
     * by reading, and in memory rather than through a file. Returns this table
     * itself when nothing is new.
     */
    fun plus(extra: List<Row>): ConversionDictionary {
        if (extra.isEmpty()) return this
        val merged = CjkNative.dictPlus(
            handle,
            extra.joinToString(SEP.toString()) { it.reading },
            extra.joinToString(SEP.toString()) { it.word },
            IntArray(extra.size) { extra[it].frequency },
            IntArray(extra.size) { if (extra[it].raises) 1 else 0 },
        )
        return if (merged == 0L) this else ConversionDictionary(merged)
    }

    companion object {
        val EMPTY = ConversionDictionary(0L)

        private const val SEP = '\u0001'
        private const val ROW_BLOCK = 8192

        /** Marks the end of a block's rows: not a frequency a pack can list. */
        private const val NO_ROW = Int.MIN_VALUE

        /**
         * The pack in [file], or [EMPTY] if nothing in it parses. Fast after the
         * first time; see the class. Throws `IOException` when it cannot be read.
         */
        fun open(file: File): ConversionDictionary = ofHandle(CjkNative.dictOpen(file.path))

        /**
         * Parses TSV lines into a table, keeping each reading's candidates ordered
         * by the frequency column (descending). Malformed lines are skipped, and
         * an input with no usable row yields [EMPTY].
         */
        fun parse(lines: Sequence<String>): ConversionDictionary =
            ofHandle(CjkNative.dictParse(lines.joinToString("\n")))

        private fun ofHandle(handle: Long): ConversionDictionary {
            val table = ConversionDictionary(handle)
            return if (table.isEmpty) EMPTY else table
        }

        private fun split(joined: String): List<String> =
            if (joined.isEmpty()) emptyList() else joined.split(SEP)

        private fun rangeOf(packed: Long): IntRange =
            (packed ushr 32).toInt() until packed.toInt()
    }
}
