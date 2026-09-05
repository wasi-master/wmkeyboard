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
 *  - [edgeLabel] / [edgeChild]: parallel edge arrays, one entry per child,
 *    sorted by label within each node so a child lookup can binary-search.
 *  - [freq]: word frequency at a node (meaningful only where [isWord]).
 *  - [maxSubtree]: highest word frequency anywhere in the node's subtree,
 *    the branch-and-bound key that keeps completion cost near `limit`.
 */
class PackedTrie internal constructor(
    internal val childStart: IntArray,
    internal val edgeLabel: CharArray,
    internal val edgeChild: IntArray,
    internal val freq: IntArray,
    internal val isWord: BooleanArray,
    internal val maxSubtree: IntArray,
) : WordSource, TrieWalker {

    /** Number of distinct words stored. */
    val wordCount: Int get() = isWord.count { it }

    override fun walkers(): List<TrieWalker> = listOf(this)

    override fun child(node: Int, label: Char): Int {
        val edge = childEdge(node, label)
        return if (edge < 0) -1 else edgeChild[edge]
    }

    override fun childrenInto(node: Int, out: ChildBuffer): Int {
        val start = childStart[node]
        val count = childStart[node + 1] - start
        out.ensure(count)
        for (i in 0 until count) {
            out.labels[i] = edgeLabel[start + i]
            out.nodes[i] = edgeChild[start + i]
        }
        return count
    }

    override fun isWord(node: Int): Boolean = isWord[node]

    override fun frequency(node: Int): Int = if (isWord[node]) freq[node] else 0

    override fun maxSubtree(node: Int): Int = maxSubtree[node]

    private val rankFloors by lazy { RankFloorCache(freq.size) { frequency(it) } }

    override fun frequencyAtRank(rank: Int): Int = rankFloors.frequencyAtRank(rank)

    /** Node reached by walking [word] from the root, or -1 if absent. */
    private fun nodeFor(word: String): Int {
        var node = 0
        for (ch in word) {
            val edge = childEdge(node, ch)
            if (edge < 0) return -1
            node = edgeChild[edge]
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
        return if (node >= 0 && isWord[node]) freq[node] else 0
    }

    override fun contains(word: String): Boolean = frequencyOf(word) > 0

    override fun complete(prefix: String, limit: Int): List<Suggestion> =
        TrieCompleter.complete(this, prefix, limit)

    companion object {
        val EMPTY = of(emptyList())

        /**
         * Builds a packed trie from `word to frequency` entries. Duplicate
         * words keep their highest frequency, matching [Trie.insert]. The
         * transient node tree used to lay out the arrays is discarded, so its
         * HashMap cost is paid only during construction (off the main thread).
         */
        fun of(entries: Iterable<Pair<String, Int>>): PackedTrie {
            val root = BuildNode()
            var nodeCount = 1
            for ((word, frequency) in entries) {
                if (word.isEmpty()) continue
                var node = root
                for (ch in word) {
                    node = node.children.getOrPut(ch) {
                        nodeCount++
                        BuildNode()
                    }
                }
                node.isWord = true
                // Snapped here rather than in the codec so that this trie and
                // the .wmdict written from it hold identical numbers, and so
                // that the eval harnesses — which build straight from of(),
                // never through a file — measure the frequencies the keyboard
                // will really see.
                node.freq = maxOf(node.freq, FrequencyCodec.round(frequency))
            }

            val childStart = IntArray(nodeCount + 1)
            val edgeLabel = CharArray(nodeCount - 1)
            val edgeChild = IntArray(nodeCount - 1)
            val freq = IntArray(nodeCount)
            val isWord = BooleanArray(nodeCount)
            val maxSubtree = IntArray(nodeCount)

            // Breadth-first id assignment + CSR layout in one pass. Ids are
            // handed out in BFS order (root = 0), so a parent always has a
            // smaller id than its children — which lets maxSubtree fold up in
            // a single reverse sweep below. Children are sorted by label so
            // childEdge can binary-search their edge slice.
            val queue = ArrayDeque<BuildNode>()
            root.id = 0
            queue.add(root)
            var nextId = 1
            var edgeCursor = 0
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val id = node.id
                freq[id] = node.freq
                isWord[id] = node.isWord
                childStart[id] = edgeCursor
                for ((ch, child) in node.children.entries.sortedBy { it.key }) {
                    child.id = nextId++
                    edgeLabel[edgeCursor] = ch
                    edgeChild[edgeCursor] = child.id
                    edgeCursor++
                    queue.add(child)
                }
            }
            childStart[nodeCount] = edgeCursor

            // maxSubtree bottom-up: reverse id order visits every child before
            // its parent (BFS gives children strictly larger ids).
            for (id in nodeCount - 1 downTo 0) {
                var best = if (isWord[id]) freq[id] else 0
                var e = childStart[id]
                val end = childStart[id + 1]
                while (e < end) {
                    val childMax = maxSubtree[edgeChild[e]]
                    if (childMax > best) best = childMax
                    e++
                }
                maxSubtree[id] = best
            }

            return PackedTrie(childStart, edgeLabel, edgeChild, freq, isWord, maxSubtree)
        }
    }

    /** Transient build node; exists only while [of] lays out the arrays. */
    private class BuildNode {
        val children = HashMap<Char, BuildNode>()
        var isWord = false
        var freq = 0
        var id = 0
    }
}
