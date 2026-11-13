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
        val code = FrequencyCodec.encode(frequency)
        var above = 0
        for (c in code + 1..FrequencyCodec.MAX_CODE) above += counts.byCode[c]
        return above + 1
    }

    /**
     * [frequencyAtRank] answered from the kept histogram, without touching
     * the one-rank cache the vocabulary cap relies on: the ranking scale's
     * anchor ([RankScale.anchored]) asks a rank of its own on every list.
     */
    fun countAtRank(rank: Int): Int {
        if (rank <= 0) return 0
        val counts = histogram()
        if (counts.words <= rank) return 0
        var seen = 0
        for (code in counts.byCode.indices.reversed()) {
            seen += counts.byCode[code]
            if (seen >= rank) return FrequencyCodec.decode(code)
        }
        return 0
    }

    /** Words with a frequency, i.e. the size of the vocabulary being ranked. */
    fun vocabularySize(): Int = histogram().words

    private class Histogram(val byCode: IntArray, val words: Int)

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
        for (node in 0 until nodeCount) {
            val frequency = frequencyAt(node)
            if (frequency <= 0) continue
            counts[FrequencyCodec.encode(frequency)]++
            words++
        }
        return Histogram(counts, words)
    }

    private fun compute(rank: Int): Int {
        // A retained histogram answers without another pass; otherwise one
        // pass, and the array is dropped again as it always was.
        val counts = histogramCache ?: count()
        if (counts.words <= rank) return 0
        var seen = 0
        for (code in counts.byCode.indices.reversed()) {
            seen += counts.byCode[code]
            if (seen >= rank) return FrequencyCodec.decode(code)
        }
        return 0
    }
}
