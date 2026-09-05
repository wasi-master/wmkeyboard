package com.wasimaster.wmkeyboard.core.prediction

/**
 * Node-level read access to a trie, for algorithms that need to steer their
 * own traversal (fuzzy beam search) instead of consuming whole completions.
 *
 * Node handles are plain Ints, valid for the walker's lifetime; the root is
 * always handle 0. Child enumeration order is implementation-defined —
 * consumers must not depend on it.
 */
interface TrieWalker {

    val root: Int get() = 0

    /** Handle of the child reached from [node] via [label], or -1 if absent. */
    fun child(node: Int, label: Char): Int

    /**
     * Copies all of [node]'s child edges into [out] and returns the count.
     * Zero-alloc when [out] is reused across calls.
     */
    fun childrenInto(node: Int, out: ChildBuffer): Int

    fun isWord(node: Int): Boolean

    /** Word frequency at [node]; meaningful only where [isWord]. */
    fun frequency(node: Int): Int

    /** Highest word frequency anywhere in [node]'s subtree, incl. itself —
     * the admissible upper bound for best-first search. */
    fun maxSubtree(node: Int): Int

    /**
     * The frequency of the [rank]-th most frequent word here: the exact cutoff
     * for "consider only the commonest [rank] words". 0 when the walker holds
     * no more than [rank] words, and 0 for a non-positive [rank], both meaning
     * no cutoff at all.
     *
     * A walker whose frequencies are not a ranking of a vocabulary — the
     * personal lexicon, where everything the user typed once is a 1, or a
     * romanization index standing in front of a real list — keeps the default
     * and is therefore never capped. That is deliberate: capping a vocabulary
     * is about a downloaded corpus's long tail, and the words a user taught the
     * keyboard themselves are the last ones to throw away.
     */
    fun frequencyAtRank(rank: Int): Int = 0
}

/** Caller-owned growable parallel buffers for [TrieWalker.childrenInto]. */
class ChildBuffer(initial: Int = 32) {
    var labels: CharArray = CharArray(initial)
        private set
    var nodes: IntArray = IntArray(initial)
        private set

    fun ensure(n: Int) {
        if (labels.size >= n) return
        var capacity = labels.size
        while (capacity < n) capacity *= 2
        labels = labels.copyOf(capacity)
        nodes = nodes.copyOf(capacity)
    }
}
