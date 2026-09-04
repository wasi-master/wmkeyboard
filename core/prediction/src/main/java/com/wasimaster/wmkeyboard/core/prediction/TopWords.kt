package com.wasimaster.wmkeyboard.core.prediction

import java.util.PriorityQueue

/**
 * The [limit] most frequent words in this source that pass [accept], most
 * frequent first.
 *
 * Best-first over the tries' subtree maxima rather than an enumeration: a
 * downloaded list can hold a few hundred thousand words, and building a
 * 250-word prompt pool must not mean materialising every one of them as a
 * String. Each node is pushed with its [TrieWalker.maxSubtree] as an upper
 * bound; a word is emitted only once it reaches the head of the queue under
 * its own frequency, which makes the output order exact. A word held by more
 * than one walker (a downloaded and an imported list) is emitted once.
 *
 * Sources with no walkers — flat or delegating ones — yield nothing. [maxPops]
 * caps the search so a filter that rejects almost everything cannot walk the
 * whole trie.
 */
fun WordSource.topWords(
    limit: Int,
    maxPops: Int = 200_000,
    accept: (String) -> Boolean = { true },
): List<String> {
    if (limit <= 0) return emptyList()
    val walkers = walkers()
    if (walkers.isEmpty()) return emptyList()

    class Frontier(val walker: TrieWalker?, val node: Int, val text: String, val bound: Int)

    val queue = PriorityQueue<Frontier>(compareByDescending { it.bound })
    for (walker in walkers) {
        queue += Frontier(walker, walker.root, "", walker.maxSubtree(walker.root))
    }
    val out = ArrayList<String>(limit)
    val seen = HashSet<String>()
    val children = ChildBuffer()
    var pops = 0
    while (queue.isNotEmpty() && out.size < limit && pops++ < maxPops) {
        val head = queue.poll()
        val walker = head.walker
        if (walker == null) {
            // A word under its own frequency: nothing above it is left in
            // the queue, so its rank is settled.
            if (seen.add(head.text) && accept(head.text)) out += head.text
            continue
        }
        if (walker.isWord(head.node)) {
            queue += Frontier(null, -1, head.text, walker.frequency(head.node))
        }
        val count = walker.childrenInto(head.node, children)
        for (i in 0 until count) {
            val child = children.nodes[i]
            queue += Frontier(walker, child, head.text + children.labels[i], walker.maxSubtree(child))
        }
    }
    return out
}
