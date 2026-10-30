// The Kotlin KotlinCjkNgrams the phone ran until the conversion decoder moved to Rust, kept
// as the reference NativeCjkParityTest compares wm-engine against. A change to
// the decoder's scoring lands here and in native/wm-engine/src/cjk, or the test fails.
package com.wasimaster.wmkeyboard.core.input.composer.reference

import kotlin.math.ln

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
 * Storage is two sorted `LongArray`s of 64-bit hashes with parallel counts, not
 * the `Map<String, Map<String, Int>>` this obviously wants to be — that shape is
 * exactly what blew a 256 MB heap in [KotlinConversionDictionary] and forced its
 * structure-of-arrays layout. At ~300k bigrams a hashed table is ~3.6 MB; the map
 * would be an order of magnitude more. Hashing costs collisions, but at 64 bits
 * over 300k keys that is a ~1e-7 chance of one wrong score, which is not worth a
 * fallback path to fix.
 */
class KotlinCjkNgrams private constructor(
    private val uniKeys: LongArray,
    private val uniCounts: IntArray,
    private val biKeys: LongArray,
    private val biCounts: IntArray,
    /** Corpus size, the denominator for an unconditioned unigram. */
    private val totalTokens: Long,
    /** The unigram words themselves, sorted, for [completions] and [successors]. */
    private val words: Array<String> = emptyArray(),
    /** Parallel to [words]. */
    private val wordCounts: IntArray = IntArray(0),
    /** Per word in [words], where its successors start in [succWords]; size `words + 1`. */
    private val succStart: IntArray = IntArray(1),
    /** Successors as indexes into [words], commonest first within each word's run. */
    private val succWords: IntArray = IntArray(0),
    /** Parallel to [succWords]: the pair's count. */
    private val succCounts: IntArray = IntArray(0),
) {

    val isEmpty: Boolean get() = uniKeys.isEmpty() && biKeys.isEmpty()

    /** Corpus count of [word], or 0 when the model has never seen it. */
    fun unigram(word: String): Int {
        val i = uniKeys.binarySearch(hash(word))
        return if (i >= 0) uniCounts[i] else 0
    }

    /** Corpus count of [previous] immediately followed by [word]. */
    fun bigram(previous: String, word: String): Int {
        val i = biKeys.binarySearch(pairHash(previous, word))
        return if (i >= 0) biCounts[i] else 0
    }

    /**
     * The words seen most often straight after [word], commonest first, each
     * with the pair's count: what the strip offers once [word] is written
     * (#580 follow-up). Only the [SUCCESSORS] commonest per word are kept.
     */
    fun successors(word: String, limit: Int = SUCCESSORS): List<Pair<String, Int>> {
        val i = words.binarySearch(word)
        if (i < 0) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        for (k in succStart[i] until minOf(succStart[i + 1], succStart[i] + limit)) {
            out.add(words[succWords[k]] to succCounts[k])
        }
        return out
    }

    /**
     * Words longer than [prefix] that start with it, commonest first, each with
     * its count: 无聊 for 无, so the strip can offer 聊 the moment 无 is written.
     */
    fun completions(prefix: String, limit: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty() || words.isEmpty()) return emptyList()
        var i = words.binarySearch(prefix).let { if (it >= 0) it + 1 else -it - 1 }
        val found = ArrayList<Pair<String, Int>>()
        while (i < words.size && words[i].startsWith(prefix)) {
            found.add(words[i] to wordCounts[i])
            i++
        }
        return found.sortedByDescending { it.second }.take(limit)
    }

    /**
     * `log P(word | previous)` by stupid backoff (Brants et al. 2007): the
     * conditional when the pair was seen, otherwise [BACKOFF] times the word's
     * own probability.
     *
     * Stupid backoff rather than Kneser-Ney deliberately. KN wants continuation
     * counts, which is a third table on a heap that has already run out once, and
     * at corpus scale the two are hard to tell apart — this is exactly the
     * lots-of-data regime it was published for. It also needs no normalizing
     * constant, so an edge costs two array lookups and a subtraction.
     *
     * [packTotal] and [packFreq] are the conversion pack's own numbers, used when
     * this model is [EMPTY] so the decoder still has a real unigram to rank by.
     * Always finite: an unseen word floors at one notional occurrence rather than
     * `ln(0)`, so a path can never be scored at negative infinity and drop out.
     */
    fun logProbability(previous: String?, word: String, packFreq: Int, packTotal: Long): Double {
        if (isEmpty) return ln((packFreq + 1).toDouble()) - ln((packTotal + 1).toDouble())
        val lambda = bigramWeight
        if (lambda > 0.0) {
            // Interpolated (Jelinek-Mercer), libpinyin's form: the pair's
            // conditional and the word's own share, mixed by a fixed weight.
            val unigram = (unigram(word) + 1).toDouble() / (totalTokens + 1).toDouble()
            var conditional = 0.0
            if (previous != null) {
                val pair = bigram(previous, word)
                val prior = if (pair > 0) unigram(previous) else 0
                if (prior > 0) conditional = pair.toDouble() / prior.toDouble()
            }
            return ln(lambda * conditional.coerceAtMost(1.0) + (1.0 - lambda) * unigram)
        }
        if (previous != null) {
            val pair = bigram(previous, word)
            if (pair > 0) {
                val prior = unigram(previous)
                if (prior > 0) return ln(pair.toDouble()) - ln(prior.toDouble())
            }
        }
        return LOG_BACKOFF + ln((unigram(word) + 1).toDouble()) - ln((totalTokens + 1).toDouble())
    }

    companion object {
        val EMPTY = KotlinCjkNgrams(LongArray(0), IntArray(0), LongArray(0), IntArray(0), 0L)

        /** The published stupid-backoff weight. */
        const val BACKOFF = 0.4
        private val LOG_BACKOFF = ln(BACKOFF)

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

        /**
         * Parses the TSV described on the class. Two fields is a unigram, three a
         * bigram; anything else — blank lines, comments, malformed rows — is
         * skipped, matching [KotlinConversionDictionary.parse]'s tolerance of a pack
         * that picked up stray lines somewhere between the corpus and the device.
         */
        fun parse(lines: Sequence<String>): KotlinCjkNgrams {
            // Straight into primitive arrays: the shipped pack is several
            // hundred thousand rows, and a HashMap<Long, Int> of that boxes
            // every key and value — tens of megabytes of garbage on a heap
            // that has already run out once under [KotlinConversionDictionary].
            val uni = Table()
            val bi = Table()
            var total = 0L
            // The words themselves, for successors and completions. The pack
            // lists every unigram before the first pair, so a pair's words
            // are already indexed when it arrives; one that is not (a pack
            // written some other way) is still scored, just never offered.
            val wordList = ArrayList<String>()
            val wordCountList = Table()
            val indexOf = HashMap<String, Int>()
            val pairs = Table()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val t1 = line.indexOf('\t')
                if (t1 <= 0) continue
                val t2 = line.indexOf('\t', t1 + 1)
                if (t2 < 0) {
                    val count = line.substring(t1 + 1).trim().toIntOrNull() ?: continue
                    if (count <= 0) continue
                    val word = line.substring(0, t1)
                    uni.add(hash(word), count)
                    total += count
                    if (indexOf.putIfAbsent(word, wordList.size) == null) {
                        wordList.add(word)
                        wordCountList.add(0L, count)
                    }
                } else {
                    if (t2 == t1 + 1 || line.indexOf('\t', t2 + 1) >= 0) continue
                    val count = line.substring(t2 + 1).trim().toIntOrNull() ?: continue
                    if (count <= 0) continue
                    val first = line.substring(0, t1)
                    val second = line.substring(t1 + 1, t2)
                    bi.add(pairHash(first, second), count)
                    val a = indexOf[first]
                    val b = indexOf[second]
                    if (a != null && b != null) pairs.add(a.toLong() shl 32 or b.toLong(), count)
                }
            }
            if (uni.size == 0 && bi.size == 0) return EMPTY
            uni.sortAndMerge()
            bi.sortAndMerge()
            return successorIndex(wordList, wordCountList.counts(), pairs) { words, counts, start, succ, succCounts ->
                KotlinCjkNgrams(uni.keys(), uni.counts(), bi.keys(), bi.counts(), total, words, counts, start, succ, succCounts)
            }
        }

        /**
         * Sorts the words and keeps each one's [SUCCESSORS] commonest
         * followers, as indexes into the sorted list.
         */
        private fun successorIndex(
            wordList: List<String>,
            counts: IntArray,
            pairs: Table,
            build: (Array<String>, IntArray, IntArray, IntArray, IntArray) -> KotlinCjkNgrams,
        ): KotlinCjkNgrams {
            val n = wordList.size
            val order = (0 until n).sortedWith { x, y -> wordList[x].compareTo(wordList[y]) }
            val rank = IntArray(n)
            for ((sorted, original) in order.withIndex()) rank[original] = sorted
            val words = Array(n) { wordList[order[it]] }
            val wordCounts = IntArray(n) { counts[order[it]] }
            // One sortable long per pair: first word, then count descending, then second word.
            val keys = pairs.keys()
            val pairCounts = pairs.counts()
            val packed = LongArray(keys.size) { k ->
                val a = rank[(keys[k] ushr 32).toInt()].toLong()
                val b = rank[(keys[k] and 0xFFFFFFFFL).toInt()].toLong()
                val inverse = (MAX_PACKED_COUNT - pairCounts[k].coerceAtMost(MAX_PACKED_COUNT)).toLong()
                (a shl 44) or (inverse shl 20) or b
            }
            packed.sort()
            val start = IntArray(n + 1)
            val succ = IntArray(minOf(packed.size, n * SUCCESSORS))
            val succCounts = IntArray(succ.size)
            var w = 0
            var current = -1
            var taken = 0
            for (p in packed) {
                val a = (p ushr 44).toInt()
                if (a != current) {
                    for (x in current + 1..a) start[x] = w
                    current = a
                    taken = 0
                }
                if (taken >= SUCCESSORS) continue
                succ[w] = (p and 0xFFFFF).toInt()
                succCounts[w] = MAX_PACKED_COUNT - ((p ushr 20) and 0xFFFFFF).toInt()
                w++
                taken++
            }
            for (x in current + 1..n) start[x] = w
            return build(words, wordCounts, start, succ.copyOf(w), succCounts.copyOf(w))
        }

        /** Largest count [successorIndex] packs; anything above sorts as this. */
        private const val MAX_PACKED_COUNT = 0xFFFFFF

        /** A growable key/count column pair, sorted by key once filled. */
        private class Table {
            private var keys = LongArray(INITIAL)
            private var counts = IntArray(INITIAL)
            var size = 0
                private set

            fun add(key: Long, count: Int) {
                if (size == keys.size) {
                    keys = keys.copyOf(size * 2)
                    counts = counts.copyOf(size * 2)
                }
                keys[size] = key
                counts[size] = count
                size++
            }

            /** Sorts by key; a key listed twice keeps its last count, as a map would. */
            fun sortAndMerge() {
                if (size < 2) return
                sort()
                var w = 0
                for (r in 0 until size) {
                    if (w > 0 && keys[w - 1] == keys[r]) {
                        counts[w - 1] = counts[r]
                    } else {
                        keys[w] = keys[r]
                        counts[w] = counts[r]
                        w++
                    }
                }
                size = w
            }

            fun keys(): LongArray = keys.copyOf(size)

            fun counts(): IntArray = counts.copyOf(size)

            /**
             * Bottom-up merge sort of both columns together. Stable, so "last
             * count wins" above means the last line.
             */
            private fun sort() {
                val n = size
                var srcK = keys
                var srcC = counts
                var dstK = LongArray(keys.size)
                var dstC = IntArray(keys.size)
                var width = 1
                while (width < n) {
                    var left = 0
                    while (left < n) {
                        val mid = minOf(left + width, n)
                        val right = minOf(left + 2 * width, n)
                        var i = left
                        var j = mid
                        var k = left
                        while (i < mid && j < right) {
                            if (srcK[j] < srcK[i]) {
                                dstK[k] = srcK[j]
                                dstC[k] = srcC[j]
                                j++
                            } else {
                                dstK[k] = srcK[i]
                                dstC[k] = srcC[i]
                                i++
                            }
                            k++
                        }
                        System.arraycopy(srcK, i, dstK, k, mid - i)
                        System.arraycopy(srcC, i, dstC, k, mid - i)
                        k += mid - i
                        System.arraycopy(srcK, j, dstK, k, right - j)
                        System.arraycopy(srcC, j, dstC, k, right - j)
                        left += 2 * width
                    }
                    val swapK = srcK
                    srcK = dstK
                    dstK = swapK
                    val swapC = srcC
                    srcC = dstC
                    dstC = swapC
                    width *= 2
                }
                keys = srcK
                counts = srcC
            }

            private companion object {
                const val INITIAL = 1024
            }
        }

        /** FNV-1a 64 over the chars — cheap, and well spread for short strings. */
        private fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /** [hash] of the pair, separated so `ab|c` and `a|bc` cannot collide. */
        private fun pairHash(a: String, b: String): Long {
            var h = hash(a)
            h = h xor 0L
            h *= 0x100000001b3L
            for (c in b) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
