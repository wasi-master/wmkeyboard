package com.wasimaster.wmkeyboard.core.prediction

/**
 * The phrase chip built from the user's own writing (#1291): the words they
 * have typed after the last one or two, often enough and consistently enough
 * to offer them together. Before this the chip only ever offered what the
 * context model expected anyone to write next.
 *
 * Each step takes the follower seen most after the two words before it, when
 * it was seen at least [MIN_SIGHTINGS] times and more often than all the
 * others put together. A two-word context with that much evidence decides on
 * its own; only one with less falls back to the word before alone, so "I am"
 * followed by "going" and "not" alike offers neither, however often "am" alone
 * led to "going".
 */
object LearnedPhrase {

    /** Fewest times a word must have followed its context to be offered. */
    const val MIN_SIGHTINGS = 3

    /** Followers of one context weighed against each other. */
    const val POOL = 8

    /** A follower and how often it came after its context. */
    data class Follower(val word: String, val count: Int)

    /**
     * Up to [maxWords] words after [back1] (and [back2] before it), or an empty
     * list when fewer than two qualify. [triples] lists what followed a
     * two-word context, [pairs] what followed one word, best first; [accepts]
     * may stop the chain at a word (one never to be offered, a mark).
     */
    fun chain(
        back1: String,
        back2: String?,
        maxWords: Int,
        triples: (String, String) -> List<Follower>,
        pairs: (String) -> List<Follower>,
        accepts: (String) -> Boolean,
    ): List<String> {
        val words = ArrayList<String>(maxWords)
        var one = back1
        var two = back2
        while (words.size < maxWords) {
            val next = follower(one, two, triples, pairs) ?: break
            if (!accepts(next)) break
            words += next
            two = one
            one = next.lowercase()
        }
        return if (words.size >= 2) words else emptyList()
    }

    private fun follower(
        back1: String,
        back2: String?,
        triples: (String, String) -> List<Follower>,
        pairs: (String) -> List<Follower>,
    ): String? {
        if (back2 != null) {
            val after = triples(back2, back1)
            if (after.sumOf { it.count } >= MIN_SIGHTINGS) return dominant(after)
        }
        return dominant(pairs(back1))
    }

    /** The follower that both reached [MIN_SIGHTINGS] and outweighs the rest, or null. */
    fun dominant(followers: List<Follower>): String? {
        val best = followers.maxByOrNull { it.count } ?: return null
        val total = followers.sumOf { it.count }
        return best.word.takeIf { best.count >= MIN_SIGHTINGS && best.count * 2 > total }
    }
}
