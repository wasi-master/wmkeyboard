package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
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
 *
 * The word-level queries ([frequencyOf], [contains], [complete], [entries])
 * run in Rust, `wm-engine`'s `wmdict::Dict` (which WMK Desktop runs too),
 * over its own mapping of the same file, through [PredictionNative]. The
 * node-level [TrieWalker] methods stay here until the walks that use them
 * move too (native/RUST_NEON_PLAN.md).
 */
class MappedTrie private constructor(
    private val buf: ByteBuffer,
    override val wordCount: Int,
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

    /** The Rust reader over the same file ([PredictionNative]). */
    private var native: Long = 0L

    /** [native], for the Rust walks that read this dictionary ([NativeFuzzySearch]). */
    internal val nativeHandle: Long get() = native

    private val cachedCount: Int = minOf(nodeCount, CACHE_NODES)
    private val childStartCache: IntArray = IntArray(cachedCount)
    private val childCountCache: IntArray = IntArray(cachedCount)
    private val maxSubtreeCache: IntArray = IntArray(cachedCount)
    private val isWordCache: BooleanArray = BooleanArray(cachedCount)

    init {
        for (i in 0 until cachedCount) {
            childStartCache[i] = computeChildStart(i)
            childCountCache[i] = computeChildCount(i)
            maxSubtreeCache[i] = computeMaxSubtree(i)
            isWordCache[i] = computeIsWord(i)
        }
    }

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
    private fun computeChildStart(node: Int): Int {
        if (checkpointOff < 0) return buf.getInt(childStartOff + node * 4)
        var total = buf.getInt(checkpointOff + (node shr PackedTrieCodec.CHECKPOINT_SHIFT) * 4)
        var counted = node and (PackedTrieCodec.CHECKPOINT_STRIDE - 1).inv()
        while (counted < node) {
            total += buf.get(childStartOff + counted).toInt() and 0xFF
            counted++
        }
        return total
    }

    private fun childStart(node: Int): Int =
        if (node < cachedCount) childStartCache[node] else computeChildStart(node)

    /** How many children [node] has. */
    private fun computeChildCount(node: Int): Int =
        if (checkpointOff < 0) {
            buf.getInt(childStartOff + (node + 1) * 4) - buf.getInt(childStartOff + node * 4)
        } else {
            buf.get(childStartOff + node).toInt() and 0xFF
        }

    private fun childCount(node: Int): Int =
        if (node < cachedCount) childCountCache[node] else computeChildCount(node)

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

    private fun computeMaxSubtree(node: Int): Int =
        FrequencyCodec.decode(buf.getShort(maxSubtreeOff + node * 2).toInt() and 0xFFFF)

    override fun maxSubtree(node: Int): Int =
        if (node < cachedCount) maxSubtreeCache[node] else computeMaxSubtree(node)

    private fun computeIsWord(node: Int): Boolean =
        buf.get(isWordOff + (node ushr 3)).toInt() shr (node and 7) and 1 == 1

    override fun isWord(node: Int): Boolean =
        if (node < cachedCount) isWordCache[node] else computeIsWord(node)

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

    override fun rankOfFrequency(frequency: Int): Int = rankFloors.rankOfFrequency(frequency)

    override fun vocabularySize(): Int = rankFloors.vocabularySize()

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

    override fun frequencyOf(word: String): Int = PredictionNative.dictFrequencyOf(native, word)

    override fun contains(word: String): Boolean = frequencyOf(word) > 0

    /**
     * Up to [limit] completions of [prefix], best first, in exactly the order
     * [TrieCompleter.complete] gives them, ties included.
     */
    override fun complete(prefix: String, limit: Int): List<Suggestion> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val frequencies = IntArray(minOf(limit, wordCount.coerceAtLeast(1)))
        val words = PredictionNative.dictComplete(native, prefix, frequencies.size, frequencies)
        return List(words.size) { Suggestion(words[it], frequencies[it]) }
    }

    /**
     * Every `(word, frequency)` entry, depth first with children in label
     * order. Used by consumers that need the flat list (gesture lexicon,
     * phonetic indexes): it materialises strings on the heap, so call it only
     * to build another index, never per keystroke.
     */
    fun entries(): List<Pair<String, Int>> {
        val pair = PredictionNative.dictEntries(native)
        @Suppress("UNCHECKED_CAST")
        val words = pair[0] as Array<String>
        val frequencies = pair[1] as IntArray
        return List(words.size) { words[it] to frequencies[it] }
    }

    companion object {

        /**
         * Fills the symbol table's unused tail. A corrupt label byte lands here
         * rather than off the end of the array, and this is a permanent
         * noncharacter — never a real label, and not [FuzzyBeamSearch]'s
         * `NO_LABEL`, so a damaged file cannot forge either.
         */
        private const val UNUSED_SYMBOL = '\uFFFF'
        private const val CACHE_NODES = 2048

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
                fromBuffer(buf, file.length())?.takeIf { it.openNative(file) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** False when the Rust reader refuses a file this one accepted. */
        private fun MappedTrie.openNative(file: File): Boolean {
            native = PredictionNative.dictOpen(file.absolutePath)
            NativeHandles.track(this, native, PredictionNative::dictClose)
            return native != 0L
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
