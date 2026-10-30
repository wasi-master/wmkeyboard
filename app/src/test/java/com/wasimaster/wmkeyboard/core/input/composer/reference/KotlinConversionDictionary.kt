// The Kotlin KotlinConversionDictionary the phone ran until the conversion decoder moved to Rust, kept
// as the reference NativeCjkParityTest compares wm-engine against. A change to
// the decoder's scoring lands here and in native/wm-engine/src/cjk, or the test fails.
package com.wasimaster.wmkeyboard.core.input.composer.reference

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
 * ### Why the rows are stored column-wise
 *
 * The packs are big: `ja_kana` is 1.08M rows over 744k distinct readings. Held
 * the obvious way — `Map<String, List<String>>` — the *text* is only ~20 MB but
 * the objects wrapped around it are not: two `String` headers per row, a map
 * node and a list per reading runs past 160 MB, and on a 256 MB heap the parse
 * dies with an `OutOfMemoryError` that a `runCatching` upstream quietly turns
 * into "no dictionary". So rows live in parallel primitive arrays indexed by row
 * number, with every reading and word concatenated into a [CharStore]: ~33 MB
 * for that same pack, no per-row objects, and `String`s are cut only for the
 * handful of candidates a query actually returns.
 *
 * Rows are kept sorted by reading, so a lookup is a binary search for the first
 * row with that reading followed by a walk over its (contiguous) run.
 */
class KotlinConversionDictionary private constructor(
    private val readings: CharStore,
    private val readingAt: IntArray,
    private val readingLen: IntArray,
    private val words: CharStore,
    private val wordAt: IntArray,
    private val wordLen: IntArray,
    private val freqs: IntArray,
) {

    internal val size: Int get() = freqs.size

    val isEmpty: Boolean get() = size == 0

    /**
     * Total of every row's frequency — the denominator that turns a raw count
     * into log P(word) for the decoder. Summed once at construction; a `Long`
     * because a million rows of corpus counts overflow an `Int`.
     */
    val totalFreq: Long = run {
        var sum = 0L
        for (f in freqs) sum += f
        sum
    }

    /** Candidates for [reading] exactly, best first; empty when unknown. */
    fun exact(reading: String): List<String> {
        val rows = rowsFor(reading)
        if (rows.isEmpty()) return emptyList()
        // One reading's run is short (a few dozen rows at most), so collecting it
        // to sort by frequency costs nothing measurable.
        return rows.sortedByDescending { freqs[it] }.map { word(it) }.distinct()
    }

    /**
     * The contiguous rows whose reading is exactly [reading], empty when unknown.
     *
     * The row-level counterpart to [exact], for callers that score rows before
     * deciding which ones are worth turning into strings. The decoder walks far
     * more rows than it keeps, and cutting a `String` out of the arena for each
     * one is the single largest allocation on that path.
     */
    fun rowsFor(reading: String): IntRange {
        if (reading.isEmpty() || size == 0) return IntRange.EMPTY
        val lo = lowerBound(reading)
        if (lo >= size || !readingEquals(lo, reading)) return IntRange.EMPTY
        var hi = lo
        while (hi < size && readingEquals(hi, reading)) hi++
        return lo until hi
    }

    /**
     * The rows within [within] whose reading starts with [prefix].
     *
     * This is what lets the decoder enumerate multi-unit words without building
     * their readings first. Extending a reading by one more unit narrows the
     * range with two bounded binary searches, and an **empty range prunes the
     * whole subtree** — so a buffer whose units are individually ambiguous (T9
     * digits, fuzzy pinyin) costs what the dictionary actually contains rather
     * than the product of the option lists.
     */
    fun prefixRange(prefix: String, within: IntRange = 0 until size): IntRange {
        if (size == 0) return IntRange.EMPTY
        val lo = within.first.coerceAtLeast(0)
        val hi = (within.last + 1).coerceAtMost(size)
        if (lo >= hi) return IntRange.EMPTY
        if (prefix.isEmpty()) return lo until hi
        val start = prefixBound(prefix, lo, hi, inclusive = false)
        val end = prefixBound(prefix, start, hi, inclusive = true)
        return start until end
    }

    /** The word at [row], materialized out of the arena. */
    fun word(row: Int): String = words.substring(wordAt[row], wordLen[row])

    /** The reading at [row], materialized out of the arena. */
    fun reading(row: Int): String = readings.substring(readingAt[row], readingLen[row])

    /**
     * Characters in the word at [row], without cutting it. One Hanzi spells one
     * syllable, so this is the syllable-count test the ranking needs, and it runs
     * on every enumerated row while [word] runs only on the survivors.
     */
    fun wordLength(row: Int): Int = wordLen[row]

    /** The corpus frequency of the row at [row]; 0 when the pack listed none. */
    fun frequency(row: Int): Int = freqs[row]

    /**
     * Candidates for a buffer being built up: the exact match first, then matches
     * for its longest key-prefixes, so `nihao` offers 你好 while `ni` already
     * offers 你/尼/…. De-duplicated and capped at [limit].
     */
    fun candidates(reading: String, limit: Int = 12): List<String> {
        if (reading.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(exact(reading))
        var p = reading
        while (p.isNotEmpty() && out.size < limit) {
            if (p != reading) out.addAll(exact(p))
            p = p.dropLast(1)
        }
        return out.take(limit)
    }

    /** First row whose reading is >= [key]. */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (readings.compare(readingAt[mid], readingLen[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * First row in `[lo, hi)` that sorts after [prefix] — after the *last* row
     * starting with it when [inclusive], before the first when not. Comparison
     * looks only at [prefix]'s own length, so a longer reading that starts with
     * it counts as equal and lands inside the range.
     */
    private fun prefixBound(prefix: String, lo: Int, hi: Int, inclusive: Boolean): Int {
        var a = lo
        var b = hi
        while (a < b) {
            val mid = (a + b) ushr 1
            val c = comparePrefix(mid, prefix)
            if (if (inclusive) c <= 0 else c < 0) a = mid + 1 else b = mid
        }
        return a
    }

    /** [row]'s reading against [prefix], over [prefix]'s length only. */
    private fun comparePrefix(row: Int, prefix: String): Int {
        val at = readingAt[row]
        val len = readingLen[row]
        val common = minOf(len, prefix.length)
        for (i in 0 until common) {
            val d = readings.charAt(at + i) - prefix[i]
            if (d != 0) return d
        }
        // A reading that ran out early sorts before the prefix; one that matched
        // every prefix char is a hit however much longer it goes on.
        return if (len < prefix.length) -1 else 0
    }

    private fun readingEquals(row: Int, key: String): Boolean =
        readingLen[row] == key.length && readings.compare(readingAt[row], readingLen[row], key) == 0

    /**
     * The frequency below which [quantile] of the rows fall (0.5 is the median),
     * or 0 for an empty table. What an imported list is scaled against: the
     * packs count on very different scales — pinyin tops out at 1000, kana at
     * 30000 — and a row from a list has to land somewhere sensible on this one.
     */
    fun frequencyQuantile(quantile: Double): Int {
        if (size == 0) return 0
        val sorted = freqs.copyOf().also { it.sort() }
        return sorted[((size - 1) * quantile.coerceIn(0.0, 1.0)).toInt()]
    }

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
     * by reading. Returns this table itself when nothing is new.
     */
    fun plus(extra: List<Row>): KotlinConversionDictionary {
        if (extra.isEmpty()) return this
        val fresh = LinkedHashMap<String, Row>()
        val raised = HashMap<Int, Int>()
        for (row in extra) {
            if (row.reading.isEmpty() || row.word.isEmpty()) continue
            val held = rowOf(row.reading, row.word)
            if (held >= 0) {
                if (row.raises && row.frequency > (raised[held] ?: freqs[held])) raised[held] = row.frequency
                continue
            }
            val key = row.reading + '\t' + row.word
            val known = fresh[key]
            if (known == null || row.frequency > known.frequency) fresh[key] = row
        }
        if (fresh.isEmpty() && raised.isEmpty()) return this
        val builder = Builder()
        for (i in 0 until size) {
            builder.add(readings.substring(readingAt[i], readingLen[i]), word(i), raised[i] ?: freqs[i])
        }
        for (row in fresh.values) builder.add(row.reading, row.word, row.frequency)
        return builder.build()
    }

    /** The row listing [word] under [reading], or -1. */
    private fun rowOf(reading: String, word: String): Int {
        for (row in rowsFor(reading)) {
            if (wordLen[row] == word.length && words.compare(wordAt[row], wordLen[row], word) == 0) return row
        }
        return -1
    }

    /**
     * Collects rows into the compact layout, in any order. Shared by [parse]
     * and [plus], so a table built either way is laid out the same.
     */
    private class Builder {
        private val readings = CharStore()
        private val words = CharStore()
        private var rAt = IntArray(1024)
        private var rLen = IntArray(1024)
        private var wAt = IntArray(1024)
        private var wLen = IntArray(1024)
        private var freq = IntArray(1024)
        private var n = 0
        private var sorted = true
        private var prev: String? = null

        fun add(reading: String, word: String, frequency: Int) {
            if (n == freq.size) {
                val cap = n * 2
                rAt = rAt.copyOf(cap)
                rLen = rLen.copyOf(cap)
                wAt = wAt.copyOf(cap)
                wLen = wLen.copyOf(cap)
                freq = freq.copyOf(cap)
            }
            val last = prev
            if (sorted && last != null && reading < last) sorted = false
            prev = reading
            rAt[n] = readings.append(reading)
            rLen[n] = reading.length
            wAt[n] = words.append(word)
            wLen[n] = word.length
            freq[n] = frequency
            n++
        }

        fun build(): KotlinConversionDictionary {
            if (n == 0) return EMPTY
            val order = IntArray(n) { it }
            if (!sorted) sortRows(order, n, readings, rAt, rLen)

            val outRAt = IntArray(n)
            val outRLen = IntArray(n)
            val outWAt = IntArray(n)
            val outWLen = IntArray(n)
            val outFreq = IntArray(n)
            for (i in 0 until n) {
                val src = order[i]
                outRAt[i] = rAt[src]
                outRLen[i] = rLen[src]
                outWAt[i] = wAt[src]
                outWLen[i] = wLen[src]
                outFreq[i] = freq[src]
            }
            return KotlinConversionDictionary(readings, outRAt, outRLen, words, outWAt, outWLen, outFreq)
        }
    }

    /**
     * Append-only character arena. Text goes into fixed-size chunks so growing it
     * never reallocates what is already stored — the point on a table this size,
     * where a doubling `StringBuilder` would spike to twice the finished table's
     * heap at exactly the wrong moment.
     */
    class CharStore {
        private val chunks = ArrayList<CharArray>()
        private var used = CHUNK // forces the first append to allocate a chunk

        /** Total chars stored; also the position the next [append] returns. */
        var length: Int = 0
            private set

        fun append(s: String): Int {
            val start = length
            var off = 0
            while (off < s.length) {
                if (used == CHUNK) {
                    chunks.add(CharArray(CHUNK))
                    used = 0
                }
                val n = minOf(CHUNK - used, s.length - off)
                s.toCharArray(chunks[chunks.size - 1], used, off, off + n)
                used += n
                off += n
            }
            length += s.length
            return start
        }

        fun charAt(pos: Int): Char = chunks[pos ushr CHUNK_BITS][pos and CHUNK_MASK]

        /** Lexicographic compare of the stored run at [start] of [len] against [key]. */
        fun compare(start: Int, len: Int, key: String): Int {
            val common = minOf(len, key.length)
            for (i in 0 until common) {
                val d = charAt(start + i) - key[i]
                if (d != 0) return d
            }
            return len - key.length
        }

        /** Lexicographic compare of two stored runs. */
        fun compare(aAt: Int, aLen: Int, bAt: Int, bLen: Int): Int {
            val common = minOf(aLen, bLen)
            for (i in 0 until common) {
                val d = charAt(aAt + i) - charAt(bAt + i)
                if (d != 0) return d
            }
            return aLen - bLen
        }

        fun substring(start: Int, len: Int): String {
            val out = CharArray(len)
            for (i in 0 until len) out[i] = charAt(start + i)
            return String(out)
        }

        private companion object {
            const val CHUNK_BITS = 20
            const val CHUNK = 1 shl CHUNK_BITS // 1M chars = 2 MB per chunk
            const val CHUNK_MASK = CHUNK - 1
        }
    }

    companion object {
        val EMPTY = KotlinConversionDictionary(
            CharStore(), IntArray(0), IntArray(0), CharStore(), IntArray(0), IntArray(0), IntArray(0),
        )

        /**
         * Parses TSV lines into a table, keeping each reading's candidates ordered
         * by the frequency column (descending). Malformed lines are skipped, and
         * an input with no usable row yields [EMPTY].
         *
         * Streams straight into the compact layout — nothing per row is retained
         * beyond the arrays themselves. The packs already arrive sorted by reading,
         * so the sort below only does real work for a pack (or a test) that isn't.
         */
        fun parse(lines: Sequence<String>): KotlinConversionDictionary {
            val builder = Builder()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val reading = line.substring(0, tab).trim()
                val rest = line.substring(tab + 1)
                val tab2 = rest.indexOf('\t')
                val word = (if (tab2 < 0) rest else rest.substring(0, tab2)).trim()
                if (reading.isEmpty() || word.isEmpty()) continue
                val f = if (tab2 < 0) 0 else rest.substring(tab2 + 1).trim().toIntOrNull() ?: 0
                builder.add(reading, word, f)
            }
            return builder.build()
        }

        /**
         * Sorts [order] (row indices) by reading. Hand-rolled because the library
         * sorts want either boxed `Integer`s — a million of them here — or a
         * comparator over per-row objects this layout deliberately doesn't have.
         */
        private fun sortRows(order: IntArray, n: Int, store: CharStore, at: IntArray, len: IntArray) {
            fun cmp(a: Int, b: Int): Int = store.compare(at[a], len[a], at[b], len[b])

            fun swap(i: Int, j: Int) {
                val t = order[i]
                order[i] = order[j]
                order[j] = t
            }

            // Explicit stack: recursing over a million rows can outrun the real one.
            val stack = ArrayDeque<Int>()
            stack.addLast(0)
            stack.addLast(n - 1)
            while (stack.isNotEmpty()) {
                val hi = stack.removeLast()
                val lo = stack.removeLast()
                if (lo >= hi) continue
                if (hi - lo < 16) {
                    for (i in lo + 1..hi) {
                        var j = i
                        while (j > lo && cmp(order[j - 1], order[j]) > 0) {
                            swap(j - 1, j)
                            j--
                        }
                    }
                    continue
                }
                val mid = (lo + hi) ushr 1
                if (cmp(order[mid], order[lo]) < 0) swap(mid, lo)
                if (cmp(order[hi], order[lo]) < 0) swap(hi, lo)
                if (cmp(order[hi], order[mid]) < 0) swap(hi, mid)
                val pivot = order[mid]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (cmp(order[i], pivot) < 0) i++
                    while (cmp(order[j], pivot) > 0) j--
                    if (i <= j) {
                        swap(i, j)
                        i++
                        j--
                    }
                }
                stack.addLast(lo)
                stack.addLast(j)
                stack.addLast(i)
                stack.addLast(hi)
            }
        }
    }
}
