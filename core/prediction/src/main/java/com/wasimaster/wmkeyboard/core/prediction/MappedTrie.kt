package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Zero-copy [WordSource] over a memory-mapped `.wmdict` file (see
 * [PackedTrieCodec] for the layout).
 *
 * The trie is never loaded into the Java heap: every lookup reads the mapped
 * pages directly, so "load time" is one `mmap` call, resident memory is OS
 * page cache the kernel can evict under pressure, and pages a user's typing
 * never touches are never read at all. Query semantics match [PackedTrie]
 * exactly — same CSR layout, same binary-searched edges, same
 * branch-and-bound completion.
 *
 * All reads use absolute [ByteBuffer] gets, which do not touch the buffer's
 * position, so a single instance is safe to share across threads.
 */
class MappedTrie private constructor(
    private val buf: ByteBuffer,
    val wordCount: Int,
    /** Nodes in the file, so a whole-trie pass knows where to stop. */
    private val nodeCount: Int,
    /**
     * The file's edge-label alphabet, or null when it stores labels as plain
     * u16 code units. Copied onto the heap at open — it is at most 512 bytes,
     * and reading it from the mapped buffer on every binary-search probe would
     * double the reads that `childEdge` makes.
     */
    private val symbols: CharArray?,
    private val childStartOff: Int,
    /**
     * Offset of the running totals that back [childStart], or -1 when the file
     * stores `childStart` as a plain `i32` array instead of per-node counts.
     */
    private val checkpointOff: Int,
    private val edgeLabelOff: Int,
    private val freqOff: Int,
    private val maxSubtreeOff: Int,
    private val isWordOff: Int,
) : WordSource, TrieWalker {

    /**
     * Where [node]'s edges begin. In a counted file this is the nearest stored
     * running total plus the child counts of the nodes between — at most
     * [PackedTrieCodec.CHECKPOINT_STRIDE] bytes, contiguous, so the loop is
     * arithmetic over a line the first read already pulled in.
     *
     * Callers that also want the end of the range should add [childCount]
     * rather than asking for `childStart(node + 1)`, which would walk the
     * counts a second time.
     */
    private fun childStart(node: Int): Int {
        if (checkpointOff < 0) return buf.getInt(childStartOff + node * 4)
        var total = buf.getInt(checkpointOff + (node shr PackedTrieCodec.CHECKPOINT_SHIFT) * 4)
        var counted = node and (PackedTrieCodec.CHECKPOINT_STRIDE - 1).inv()
        while (counted < node) {
            total += buf.get(childStartOff + counted).toInt() and 0xFF
            counted++
        }
        return total
    }

    /** How many children [node] has. */
    private fun childCount(node: Int): Int =
        if (checkpointOff < 0) {
            buf.getInt(childStartOff + (node + 1) * 4) - buf.getInt(childStartOff + node * 4)
        } else {
            buf.get(childStartOff + node).toInt() and 0xFF
        }

    private fun edgeLabel(edge: Int): Char = when (symbols) {
        null -> buf.getChar(edgeLabelOff + edge * 2)
        else -> symbols[buf.get(edgeLabelOff + edge).toInt() and 0xFF]
    }

    /**
     * The node edge [edge] leads to. Nodes are numbered breadth-first and edges
     * in the same sweep, so this is an identity the file no longer stores — see
     * [PackedTrieCodec].
     */
    private fun edgeChild(edge: Int): Int = edge + 1

    private fun freq(node: Int): Int =
        FrequencyCodec.decode(buf.getShort(freqOff + node * 2).toInt() and 0xFFFF)

    override fun maxSubtree(node: Int): Int =
        FrequencyCodec.decode(buf.getShort(maxSubtreeOff + node * 2).toInt() and 0xFFFF)

    override fun isWord(node: Int): Boolean =
        buf.get(isWordOff + (node ushr 3)).toInt() shr (node and 7) and 1 == 1

    override fun walkers(): List<TrieWalker> = listOf(this)

    override fun child(node: Int, label: Char): Int {
        val edge = childEdge(node, label)
        return if (edge < 0) -1 else edgeChild(edge)
    }

    override fun childrenInto(node: Int, out: ChildBuffer): Int {
        val start = childStart(node)
        val count = childCount(node)
        out.ensure(count)
        for (i in 0 until count) {
            out.labels[i] = edgeLabel(start + i)
            out.nodes[i] = edgeChild(start + i)
        }
        return count
    }

    override fun frequency(node: Int): Int = if (isWord(node)) freq(node) else 0

    private val rankFloors by lazy { RankFloorCache(nodeCount) { frequency(it) } }

    override fun frequencyAtRank(rank: Int): Int = rankFloors.frequencyAtRank(rank)

    /** Node reached by walking [word] from the root, or -1 if absent. */
    private fun nodeFor(word: String): Int {
        var node = 0
        for (ch in word) {
            val edge = childEdge(node, ch)
            if (edge < 0) return -1
            node = edgeChild(edge)
        }
        return node
    }

    /** Edge index of [node]'s child labelled [ch], or -1. Binary search — the
     * edges of a node are contiguous and sorted by label. */
    private fun childEdge(node: Int, ch: Char): Int {
        var lo = childStart(node)
        var hi = lo + childCount(node) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = edgeLabel(mid)
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
        return if (node >= 0 && isWord(node)) freq(node) else 0
    }

    override fun contains(word: String): Boolean = frequencyOf(word) > 0

    override fun complete(prefix: String, limit: Int): List<Suggestion> =
        TrieCompleter.complete(this, prefix, limit)

    /**
     * Every `(word, frequency)` entry, by iterative depth-first walk. Used by
     * consumers that need the flat list (gesture lexicon, phonetic indexes) —
     * this materialises strings on the heap, so call it only to build another
     * index, never per keystroke.
     */
    fun entries(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(wordCount)
        val text = StringBuilder()
        // Stack of edge cursors: edgeStack[depth] is the next edge to try at
        // that depth, endStack[depth] the one past its last. Both are resolved
        // once on the way in, so the walk never asks for a childStart twice.
        val endStack = ArrayList<Int>()
        val edgeStack = ArrayList<Int>()
        edgeStack.add(childStart(0))
        endStack.add(edgeStack[0] + childCount(0))
        while (endStack.isNotEmpty()) {
            val depth = endStack.size - 1
            val edge = edgeStack[depth]
            if (edge < endStack[depth]) {
                edgeStack[depth] = edge + 1
                val child = edgeChild(edge)
                text.append(edgeLabel(edge))
                if (isWord(child)) out.add(text.toString() to freq(child))
                val start = childStart(child)
                edgeStack.add(start)
                endStack.add(start + childCount(child))
            } else {
                endStack.removeAt(depth)
                edgeStack.removeAt(depth)
                if (text.isNotEmpty()) text.setLength(text.length - 1)
            }
        }
        return out
    }

    companion object {

        /**
         * Fills the symbol table's unused tail. A corrupt label byte lands here
         * rather than off the end of the array, and this is a permanent
         * noncharacter — never a real label, and not [FuzzyBeamSearch]'s
         * `NO_LABEL`, so a damaged file cannot forge either.
         */
        private const val UNUSED_SYMBOL = '\uFFFF'

        /**
         * Maps [file] read-only and validates its header. Returns null (and
         * logs nothing — callers fall back to bundled data) when the file is
         * missing, truncated, or not a `.wmdict` this build can read.
         */
        fun open(file: File): MappedTrie? {
            if (!file.isFile) return null
            return try {
                val buf = RandomAccessFile(file, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
                fromBuffer(buf, file.length())
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun fromBuffer(buf: ByteBuffer, length: Long): MappedTrie? {
            if (length < PackedTrieCodec.HEADER_BYTES) return null
            if (buf.getInt(0) != PackedTrieCodec.MAGIC) return null
            if (buf.getShort(4).toInt() != PackedTrieCodec.VERSION) return null
            val flags = buf.getShort(6).toInt()
            val wordCount = buf.getInt(8)
            val nodeCount = buf.getInt(12)
            val edgeCount = buf.getInt(16)
            val symbolCount = buf.getInt(20)
            if (nodeCount <= 0 || edgeCount != nodeCount - 1) return null
            val labelsAreBytes = flags and PackedTrieCodec.FLAG_LABELS_U8 != 0
            if (symbolCount !in 0..PackedTrieCodec.MAX_SYMBOLS) return null
            if (!labelsAreBytes && symbolCount != 0) return null
            val offsets = IntArray(7) { buf.getInt(24 + it * 4) }
            // The isWord bitset is the last section; check the file holds it all.
            val end = offsets[6] + PackedTrieCodec.pad4((nodeCount + 7) / 8)
            if (end > length) return null
            // A counted childStart reads one byte per node and one running
            // total per stride; both sections have to be there in full, because
            // a short read would silently return the wrong edge range.
            val countedChildStart = flags and PackedTrieCodec.FLAG_DEGREE_U8 != 0
            if (countedChildStart) {
                val checkpointBytes = ((nodeCount shr PackedTrieCodec.CHECKPOINT_SHIFT) + 1) * 4
                if (offsets[1] + nodeCount > offsets[2]) return null
                if (offsets[2] + checkpointBytes > offsets[3]) return null
            } else if (offsets[1] + (nodeCount + 1) * 4 > offsets[2]) {
                return null
            }
            // Always the full 256 entries, so a corrupt label byte reads a
            // wrong character instead of running off the end of the array.
            val symbols = if (!labelsAreBytes) null else {
                CharArray(PackedTrieCodec.MAX_SYMBOLS) {
                    if (it < symbolCount) buf.getChar(offsets[0] + it * 2) else UNUSED_SYMBOL
                }
            }
            return MappedTrie(
                buf = buf,
                wordCount = wordCount,
                nodeCount = nodeCount,
                symbols = symbols,
                childStartOff = offsets[1],
                checkpointOff = if (countedChildStart) offsets[2] else -1,
                edgeLabelOff = offsets[3],
                freqOff = offsets[4],
                maxSubtreeOff = offsets[5],
                isWordOff = offsets[6],
            )
        }
    }
}
