package com.wasimaster.wmkeyboard.core.prediction

import java.util.TreeMap

/**
 * Read-only view over a frequency-weighted word index: prefix completion plus
 * exact frequency/membership lookup. Implemented by the mutable node-based
 * [Trie] (used where words are learned at runtime — the user lexicon) and the
 * immutable [PackedTrie] (used for build-once bundled and imported lists,
 * where its flat-array layout costs a fraction of the heap).
 */
interface WordSource {
    fun complete(prefix: String, limit: Int): List<Suggestion>
    fun frequencyOf(word: String): Int
    fun contains(word: String): Boolean

    /** Walkers over the underlying tries; empty when node-level traversal is
     * unsupported (flat or delegating sources). */
    fun walkers(): List<TrieWalker> = emptyList()

    /**
     * How many words the source holds. The glide's context blend reads it to
     * tell the small bundled lists its weight was fitted on from the large
     * downloaded ones, where the same shift can go the other way (#1240).
     * Zero when the source cannot say; a zero is treated as small.
     */
    val wordCount: Int get() = 0

    /**
     * The [walkers] that are the language's own list, bundled or downloaded,
     * and not something the user imported on top of it. Every source is its
     * own unless it knows better ([CompositeWordSource.ofLanguage]). Asked
     * where a list has to speak for the language rather than merely be
     * searched: whether a layout can glide it (#288).
     */
    fun ownWalkers(): List<TrieWalker> = walkers()
}

/**
 * Frequency-weighted prefix trie used for word completion.
 *
 * Lookup walks the prefix, then collects the highest-frequency words in the
 * subtree. The trie is built once from the bundled word list and mutated at
 * runtime as the user's personal words are learned.
 */
class Trie : WordSource {

    private class Node {
        /**
         * Sorted by label, so a walk lists children in the same order on
         * every run and in every implementation (the Rust user lexicon's
         * trie, which the parity tests hold to this one). A hash map listed
         * them in an order that hung on insertion history.
         */
        val children = TreeMap<Char, Node>()
        var frequency = 0
        var isWord = false
        /** Highest word frequency anywhere in this node's subtree (incl. self). */
        var maxSubtreeFreq = 0
        /** Walker handle; index into [nodesById]. Nodes are never removed, so
         * handles stay valid for the trie's lifetime. */
        var id = 0
    }

    private val root = Node()
    private val nodesById = ArrayList<Node>().apply { add(root) }

    /** Bumped by every [insert] and [reinforce], so a copy can tell it is stale. */
    var version = 0L
        private set

    private fun newNode(): Node = Node().also { node ->
        node.id = nodesById.size
        nodesById.add(node)
    }

    fun insert(word: String, frequency: Int) {
        if (word.isEmpty()) return
        var node = root
        for (ch in word) {
            node = node.children.getOrPut(ch) { newNode() }
        }
        node.isWord = true
        node.frequency = maxOf(node.frequency, frequency)
        bumpMaxSubtree(word, node.frequency)
        version++
    }

    /** Bumps a word's frequency, inserting it if absent. */
    fun reinforce(word: String, boost: Int = 1) {
        if (word.isEmpty()) return
        var node = root
        for (ch in word) {
            node = node.children.getOrPut(ch) { newNode() }
        }
        node.isWord = true
        node.frequency += boost
        bumpMaxSubtree(word, node.frequency)
        version++
    }

    /** Raise maxSubtreeFreq to [freq] along [word]'s path so it stays a valid upper bound. */
    private fun bumpMaxSubtree(word: String, freq: Int) {
        var node = root
        root.maxSubtreeFreq = maxOf(root.maxSubtreeFreq, freq)
        for (ch in word) {
            node = node.children[ch] ?: return
            node.maxSubtreeFreq = maxOf(node.maxSubtreeFreq, freq)
        }
    }

    override fun frequencyOf(word: String): Int {
        var node = root
        for (ch in word) {
            node = node.children[ch] ?: return 0
        }
        return if (node.isWord) node.frequency else 0
    }

    override fun contains(word: String): Boolean = frequencyOf(word) > 0

    /** Returns up to [limit] completions of [prefix], best frequency first. */
    override fun complete(prefix: String, limit: Int): List<Suggestion> =
        TrieCompleter.complete(NodeWalker(), prefix, limit)

    override fun walkers(): List<TrieWalker> = listOf(NodeWalker())

    /** Walker over the live node graph. Mutation during a walk has the same
     * (pre-existing) hazard as mutation during [complete]; callers invalidate
     * on lexicon change. */
    internal inner class NodeWalker : TrieWalker {
        /** The trie this walks, for code that keeps a copy of it elsewhere. */
        val trie: Trie get() = this@Trie

        override fun child(node: Int, label: Char): Int =
            nodesById[node].children[label]?.id ?: -1

        override fun childrenInto(node: Int, out: ChildBuffer): Int {
            val children = nodesById[node].children
            out.ensure(children.size)
            var i = 0
            for ((ch, child) in children) {
                out.labels[i] = ch
                out.nodes[i] = child.id
                i++
            }
            return i
        }

        override fun isWord(node: Int): Boolean = nodesById[node].isWord

        override fun frequency(node: Int): Int {
            val n = nodesById[node]
            return if (n.isWord) n.frequency else 0
        }

        override fun maxSubtree(node: Int): Int = nodesById[node].maxSubtreeFreq
    }
}

data class Suggestion(val word: String, val frequency: Int)
