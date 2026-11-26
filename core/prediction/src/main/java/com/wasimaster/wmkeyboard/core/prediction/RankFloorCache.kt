package com.wasimaster.wmkeyboard.core.prediction

/**
 * Answers "what is the frequency of the Nth most frequent word in this trie",
 * which is what a vocabulary cap — keep only the commonest N words — needs and
 * neither read-only trie stores: the words sit in prefix order, not frequency
 * order.
 *
 * Sorting every frequency to find one of them costs a 6 MB array and about a
 * tenth of a second on a 1.6M-word list. Counting them into [FrequencyCodec]'s
 * own 16-bit code space instead costs one pass and a fixed 172 KB, because
 * [FrequencyCodec.encode] is monotone — a higher frequency never encodes to a
 * lower code — so scanning the codes from the top walks the words in frequency
 * order without ever putting them in it.
 *
 * The answer is a *code*, decoded back to a frequency, so on a [PackedTrie]
 * holding exact counts it can round down by up to one minifloat step. That is
 * the safe direction: a floor a shade below the true rank keeps a few more
 * words than asked rather than dropping wanted ones, and on a [MappedTrie] —
 * whose stored frequencies are these same codes — it is exact.
 *
 * One rank is remembered, which is all the cap ever asks for: it comes from
 * a setting, so the question changes only when the user changes it. The pair
 * is published as one object so a racing reader can never see a new rank
 * against the old rank's frequency.
 *
 * [rankOfFrequency] is the same histogram read the other way — "how many
 * words are commoner than this one" — for the word card's "#4,512 of
 * 300,000" line (#99). That question is asked about arbitrary words, so its
 * histogram is kept once built; a keyboard that never opens the card never
 * builds it.
 */
internal class RankFloorCache(
    private val nodeCount: Int,
    private val frequencyAt: (Int) -> Int,
) {

    private class Entry(val rank: Int, val frequency: Int)

    @Volatile
    private var cached: Entry? = null

    /**
     * The frequency of the [rank]-th most frequent word, or 0 when the trie
     * holds no more than [rank] words — nothing to cut, so nothing is floored.
     * 0 for a non-positive [rank], which is how "no cap" is spelled.
     */
    fun frequencyAtRank(rank: Int): Int {
        if (rank <= 0) return 0
        cached?.let { if (it.rank == rank) return it.frequency }
        val frequency = compute(rank)
        cached = Entry(rank, frequency)
        return frequency
    }

    /**
     * 1-based position [frequency] would take in a most-frequent-first
     * listing: one more than the number of words strictly commoner than it.
     * 0 for a non-positive [frequency] or an empty trie. Frequencies are
     * compared by [FrequencyCodec] code, so two words the codec cannot tell
     * apart share a rank — which is also true of the floor, so the two
     * answers agree with each other.
     */
    fun rankOfFrequency(frequency: Int): Int {
        if (frequency <= 0) return 0
        val counts = histogram()
        if (counts.words == 0) return 0
        return counts.above(FrequencyCodec.encode(frequency)) + 1
    }

    /**
     * [frequencyAtRank] answered from the kept histogram, without touching
     * the one-rank cache the vocabulary cap relies on: the ranking scale's
     * anchor ([RankScale.anchored]) asks a rank of its own on every list.
     */
    fun countAtRank(rank: Int): Int {
        if (rank <= 0) return 0
        return histogram().floorAt(rank)
    }

    /** Words with a frequency, i.e. the size of the vocabulary being ranked. */
    fun vocabularySize(): Int = histogram().words

    /**
     * The words counted by code, kept as running totals from the top: entry
     * `i` is how many words have code [codeAt]`(i)` or higher. Read that way,
     * "how many are commoner" and "which code holds rank N" are one look-up
     * and one binary search, where summing the counts was a walk over all
     * 43,008 codes on every [rankOfFrequency] (the typo-shadow check asks it
     * per keystroke).
     *
     * [codes] holds only the codes some word has, ascending, when that is the
     * smaller form: a list rarely spells more than a few thousand distinct
     * frequencies (the bundled English list 4,302, its AOSP phrase list 196),
     * and 6 bytes for each of those is 26 KB against the dense array's 172 KB,
     * which every ranked list kept for the life of the process. Null means
     * dense: entry `i` is code `i`, for a list whose frequencies use so many
     * codes that the dense array is the smaller.
     */
    private class Histogram(private val codes: CharArray?, private val atOrAbove: IntArray, val words: Int) {

        private fun codeAt(i: Int): Int = codes?.get(i)?.code ?: i

        /** Words whose code is strictly above [code]. */
        fun above(code: Int): Int {
            val next = if (codes == null) {
                code + 1
            } else {
                // First index whose code is above [code].
                var lo = 0
                var hi = codes.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (codes[mid].code <= code) lo = mid + 1 else hi = mid
                }
                lo
            }
            return if (next < atOrAbove.size) atOrAbove[next] else 0
        }

        /**
         * The frequency of the [rank]-th commonest word, 0 when there are no
         * more than [rank] words: the highest code whose running total
         * reaches [rank], which is where a walk down from the top meets it.
         */
        fun floorAt(rank: Int): Int {
            if (words <= rank) return 0
            // [atOrAbove] never rises with the index, and its first entry is
            // [words], past [rank], so there is always an answer.
            var lo = 0
            var hi = atOrAbove.size - 1
            var found = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (atOrAbove[mid] >= rank) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return FrequencyCodec.decode(codeAt(found))
        }
    }

    @Volatile
    private var histogramCache: Histogram? = null

    private fun histogram(): Histogram {
        histogramCache?.let { return it }
        val built = count()
        histogramCache = built
        return built
    }

    private fun count(): Histogram {
        val counts = IntArray(FrequencyCodec.MAX_CODE + 1)
        var words = 0
        var distinct = 0
        for (node in 0 until nodeCount) {
            val frequency = frequencyAt(node)
            if (frequency <= 0) continue
            val code = FrequencyCodec.encode(frequency)
            if (counts[code]++ == 0) distinct++
            words++
        }
        if (distinct.toLong() * SPARSE_BYTES_PER_CODE < counts.size.toLong() * Int.SIZE_BYTES) {
            val codes = CharArray(distinct)
            val atOrAbove = IntArray(distinct)
            var at = distinct
            var running = 0
            for (code in counts.indices.reversed()) {
                if (counts[code] == 0) continue
                running += counts[code]
                at--
                codes[at] = code.toChar()
                atOrAbove[at] = running
            }
            return Histogram(codes, atOrAbove, words)
        }
        // Dense: the counts become their own running totals, in place.
        for (code in counts.size - 2 downTo 0) counts[code] += counts[code + 1]
        return Histogram(null, counts, words)
    }

    private fun compute(rank: Int): Int {
        // A retained histogram answers without another pass; otherwise one
        // pass, and the array is dropped again as it always was.
        val counts = histogramCache ?: count()
        return counts.floorAt(rank)
    }

    private companion object {
        /** A sparse histogram's cost per code some word has: a `Char` code and an `Int` total. */
        const val SPARSE_BYTES_PER_CODE = Char.SIZE_BYTES + Int.SIZE_BYTES
    }
}
