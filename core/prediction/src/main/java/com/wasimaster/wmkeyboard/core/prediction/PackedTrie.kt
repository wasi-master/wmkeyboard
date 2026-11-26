package com.wasimaster.wmkeyboard.core.prediction

/**
 * Immutable, frequency-weighted prefix trie stored as flat primitive arrays
 * (a CSR-style layout) rather than a HashMap-per-node object graph.
 *
 * For the ~17K-word bundled English list this cuts retained heap from ~6.5 MiB
 * to well under 1 MiB: every node's [HashMap], boxed `Char` key and object
 * header is gone, replaced by one shared set of arrays. Query semantics match
 * [Trie] exactly (see [PackedTrieTest], which cross-checks the two against the
 * real dictionary), so it drops in wherever a [WordSource] is read-only.
 *
 * Build-once only. Anything mutated at runtime — the user lexicon — keeps the
 * node-based [Trie]; there is no `insert` here.
 *
 * Layout (indices are node ids, 0 = root):
 *  - [childStart]: CSR offsets; node `i`'s edges occupy
 *    `[childStart[i], childStart[i + 1])` in the edge arrays.
 *  - [edgeLabel]: one entry per child, sorted by label within each node so
 *    a child lookup can binary-search. Edge `e` leads to node `e + 1`: nodes
 *    are numbered breadth-first in the same sweep that lays out the edges,
 *    so the child ids the `.wmdict` format never stored are not stored here
 *    either ([PackedTrieCodec], [MappedTrie]).
 *  - [freqCode]: word frequency at a node as its [FrequencyCodec] code
 *    (meaningful only where [isWord]).
 *  - [maxSubtreeCode]: highest word frequency anywhere in the node's subtree,
 *    likewise coded: the branch-and-bound key that keeps completion cost
 *    near `limit`.
 *
 * Frequencies are held as 16-bit codes because they were snapped to the
 * codec's values at build time anyway, so a code decodes back to exactly the
 * number an `Int` held. With the child ids gone that is 11 bytes a node
 * instead of 19. The heap tries built on the phone (a phonetic language's
 * romanized glide spellings, the pinyin and kana glide readings, imports
 * that could not be cached) are each also copied whole into Rust for the
 * native walks ([NativeFuzzySearch]), so this is the half of the pair the
 * keyboard can shrink.
 */
class PackedTrie internal constructor(
    internal val childStart: IntArray,
    internal val edgeLabel: CharArray,
    internal val freqCode: CharArray,
    internal val isWord: BooleanArray,
    internal val maxSubtreeCode: CharArray,
) : WordSource, TrieWalker {

    /** How many nodes the trie has, the root included. */
    internal val nodeCount: Int get() = isWord.size

    /**
     * Number of distinct words stored. Counted once: the glide blend asks
     * this on every preview frame, and a full [isWord] scan per frame is far
     * more than the blend itself costs.
     */
    override val wordCount: Int = isWord.count { it }

    override fun walkers(): List<TrieWalker> = listOf(this)

    override fun child(node: Int, label: Char): Int {
        val edge = childEdge(node, label)
        return if (edge < 0) -1 else edge + 1
    }

    override fun childrenInto(node: Int, out: ChildBuffer): Int {
        val start = childStart[node]
        val count = childStart[node + 1] - start
        out.ensure(count)
        for (i in 0 until count) {
            out.labels[i] = edgeLabel[start + i]
            out.nodes[i] = start + i + 1
        }
        return count
    }

    override fun isWord(node: Int): Boolean = isWord[node]

    override fun frequency(node: Int): Int = if (isWord[node]) FrequencyCodec.decode(freqCode[node].code) else 0

    override fun maxSubtree(node: Int): Int = FrequencyCodec.decode(maxSubtreeCode[node].code)

    private val rankFloors by lazy { RankFloorCache(nodeCount) { frequency(it) } }

    override fun frequencyAtRank(rank: Int): Int = rankFloors.frequencyAtRank(rank)

    override fun rankOfFrequency(frequency: Int): Int = rankFloors.rankOfFrequency(frequency)

    override fun vocabularySize(): Int = rankFloors.vocabularySize()

    override fun countAtRank(rank: Int): Int = rankFloors.countAtRank(rank)

    /** Node reached by walking [word] from the root, or -1 if absent. */
    private fun nodeFor(word: String): Int {
        var node = 0
        for (ch in word) {
            val edge = childEdge(node, ch)
            if (edge < 0) return -1
            node = edge + 1
        }
        return node
    }

    /** Edge index of [node]'s child labelled [ch], or -1. Binary search — the
     * edges of a node are contiguous and sorted by label. */
    private fun childEdge(node: Int, ch: Char): Int {
        var lo = childStart[node]
        var hi = childStart[node + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = edgeLabel[mid]
            when {
                c < ch -> lo = mid + 1
                c > ch -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    override fun frequencyOf(word: String): Int {
        val node = nodeFor(word)
        return if (node >= 0 && isWord[node]) FrequencyCodec.decode(freqCode[node].code) else 0
    }

    override fun contains(word: String): Boolean = frequencyOf(word) > 0

    override fun complete(prefix: String, limit: Int): List<Suggestion> =
        TrieCompleter.complete(this, prefix, limit)

    companion object {
        val EMPTY = of(emptyList())

        /**
         * Builds a packed trie from `word to frequency` entries. Duplicate
         * words keep their highest frequency, matching [Trie.insert].
         */
        fun of(entries: Iterable<Pair<String, Int>>): PackedTrie {
            val list = entries as? Collection<Pair<String, Int>> ?: entries.toList()
            val words = arrayOfNulls<String>(list.size)
            val frequencies = IntArray(list.size)
            var count = 0
            for ((word, frequency) in list) {
                words[count] = word
                frequencies[count] = frequency
                count++
            }
            return of(words, frequencies, count)
        }

        /**
         * Builds a packed trie from the first [count] slots of two parallel
         * arrays, **sorting both in place** — the caller's arrays come back in
         * word order. Same semantics as the `Iterable` overload.
         *
         * This is the overload for big lists. A downloaded "everything"
         * wordlist is millions of words (English 1.6M, Thai 4M), and the
         * keyboard's heap is capped at a few hundred MB: a `Pair` per entry
         * is already ~60 MB of English before the trie exists, and the old
         * HashMap-per-node build tree ran out of heap outright (#203).
         *
         * Nothing here allocates per word or per node. Sorting makes every
         * trie node a contiguous run of words sharing its prefix, so the
         * breadth-first layout below only needs each node's `[lo, hi)` word
         * range — two transient int arrays — and the node count is known
         * before anything is allocated (one pass summing, per word, the
         * characters it does not share with its sorted predecessor).
         */
        fun of(words: Array<String?>, frequencies: IntArray, count: Int): PackedTrie {
            require(count in 0..minOf(words.size, frequencies.size)) {
                "count $count exceeds arrays of ${words.size} and ${frequencies.size}"
            }
            // Slots below count are non-null by contract; the ones above are
            // never read.
            @Suppress("UNCHECKED_CAST")
            val sorted = words as Array<String>
            sortByWord(sorted, frequencies, 0, count)

            // Empty strings sort first; they are not words.
            var first = 0
            while (first < count && sorted[first].isEmpty()) first++

            var nodeCount = 1
            for (i in first until count) {
                val word = sorted[i]
                val shared = if (i == first) 0 else commonPrefixLength(sorted[i - 1], word)
                nodeCount += word.length - shared
            }

            val childStart = IntArray(nodeCount + 1)
            val edgeLabel = CharArray(nodeCount - 1)
            val freqCode = CharArray(nodeCount)
            val isWord = BooleanArray(nodeCount)
            val maxSubtreeCode = CharArray(nodeCount)
            // Node id -> the run of sorted words under it. Transient.
            val rangeStart = IntArray(nodeCount)
            val rangeEnd = IntArray(nodeCount)

            // Breadth-first id assignment + CSR layout in one pass. Ids are
            // handed out in BFS order (root = 0), so a parent always has a
            // smaller id than its children — which lets maxSubtree fold up in
            // a single reverse sweep below. Children come out sorted by label
            // (the words are sorted) so childEdge can binary-search their edge
            // slice. Every word under a node at depth d is at least d long and
            // shares the node's prefix, so its character at d picks the child.
            rangeStart[0] = first
            rangeEnd[0] = count
            var nextId = 1
            var edgeCursor = 0
            var depth = 0
            var depthEnd = 1 // ids below this sit at `depth`
            for (id in 0 until nodeCount) {
                if (id == depthEnd) {
                    depth++
                    depthEnd = nextId
                }
                var i = rangeStart[id]
                val end = rangeEnd[id]
                // A word ending here sorts before every longer word in the
                // run; duplicates of it sit side by side.
                while (i < end && sorted[i].length == depth) {
                    isWord[id] = true
                    // Snapped here rather than in the codec so that this trie
                    // and the .wmdict written from it hold identical numbers,
                    // and so that the eval harnesses — which build straight
                    // from of(), never through a file — measure the
                    // frequencies the keyboard will really see. Kept as the
                    // code: the codec is monotone, so the larger code is the
                    // larger frequency, and it decodes to the snapped value.
                    val code = FrequencyCodec.encode(frequencies[i]).toChar()
                    if (code > freqCode[id]) freqCode[id] = code
                    i++
                }
                childStart[id] = edgeCursor
                while (i < end) {
                    val ch = sorted[i][depth]
                    var j = i + 1
                    while (j < end && sorted[j][depth] == ch) j++
                    // Always edgeCursor + 1: both count up from the root
                    // together, which is what lets a reader derive it.
                    val child = nextId++
                    rangeStart[child] = i
                    rangeEnd[child] = j
                    edgeLabel[edgeCursor] = ch
                    edgeCursor++
                    i = j
                }
            }
            childStart[nodeCount] = edgeCursor

            // maxSubtree bottom-up: reverse id order visits every child before
            // its parent (BFS gives children strictly larger ids).
            // Codes compare as their frequencies do, so the maximum code is
            // the code of the maximum.
            for (id in nodeCount - 1 downTo 0) {
                var best = if (isWord[id]) freqCode[id] else NO_FREQUENCY
                var e = childStart[id]
                val end = childStart[id + 1]
                while (e < end) {
                    val childMax = maxSubtreeCode[e + 1]
                    if (childMax > best) best = childMax
                    e++
                }
                maxSubtreeCode[id] = best
            }

            return PackedTrie(childStart, edgeLabel, freqCode, isWord, maxSubtreeCode)
        }

        private const val INSERTION_SORT_MAX = 12

        /** [FrequencyCodec]'s code for no frequency at all. */
        private const val NO_FREQUENCY = '\u0000'

        private fun commonPrefixLength(a: String, b: String): Int {
            val limit = minOf(a.length, b.length)
            var i = 0
            while (i < limit && a[i] == b[i]) i++
            return i
        }

        /**
         * Sorts `words[from, to)` by UTF-16 code unit — the order [childEdge]
         * searches in — carrying [frequencies] along. Three-way quicksort, so
         * duplicate words cost nothing extra; the middle pivot keeps an
         * already-alphabetical list (a user's imported dictionary) at n log n,
         * and recursing into the smaller side bounds the stack at log n.
         */
        private fun sortByWord(words: Array<String>, frequencies: IntArray, from: Int, to: Int) {
            var lo = from
            var hi = to
            while (hi - lo > INSERTION_SORT_MAX) {
                val pivot = words[(lo + hi) ushr 1]
                var lt = lo
                var gt = hi
                var i = lo
                // [lo, lt) < pivot, [lt, i) == pivot, [gt, hi) > pivot
                while (i < gt) {
                    val cmp = words[i].compareTo(pivot)
                    when {
                        cmp < 0 -> swap(words, frequencies, lt++, i++)
                        cmp > 0 -> swap(words, frequencies, i, --gt)
                        else -> i++
                    }
                }
                if (lt - lo < hi - gt) {
                    sortByWord(words, frequencies, lo, lt)
                    lo = gt
                } else {
                    sortByWord(words, frequencies, gt, hi)
                    hi = lt
                }
            }
            for (i in lo + 1 until hi) {
                val word = words[i]
                val frequency = frequencies[i]
                var j = i - 1
                while (j >= lo && words[j] > word) {
                    words[j + 1] = words[j]
                    frequencies[j + 1] = frequencies[j]
                    j--
                }
                words[j + 1] = word
                frequencies[j + 1] = frequency
            }
        }

        private fun swap(words: Array<String>, frequencies: IntArray, a: Int, b: Int) {
            val word = words[a]
            words[a] = words[b]
            words[b] = word
            val frequency = frequencies[a]
            frequencies[a] = frequencies[b]
            frequencies[b] = frequency
        }
    }
}
