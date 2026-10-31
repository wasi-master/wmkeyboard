package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.ln

/**
 * Autocorrect for a phonetic layout, decided over the spellings people write.
 *
 * The fold behind a phonetic layout's readings forgives loose romanization and
 * nothing else: `vako` is not a way to write ভালো, it is `valo` with the finger
 * one key off, and no fold reaches it. The Latin keyboards answer that with a
 * fuzzy walk over the word list, and so does this, except that the list a
 * phonetic layout's keys can be wrong about is the list of romanized spellings
 * ([com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex]).
 *
 * This object holds the arithmetic and the verdict, with no engine around them.
 * A [Candidate] is ranked in nats on the romanized list's scale: how much record
 * there is of its spelling being written, less the edit that reaches it, plus
 * what the words before it say. Ranking on the spelling and not on the native
 * word is what decides this. A walk one edit from any buffer finds common words
 * by the handful, and on the native word's frequency those win whatever was
 * typed (`moen` became করেন); a spelling's own count says what people meant by
 * those letters. Measured over the bn lists, with one slip made in each of the
 * 4,000 commonest spellings: 2.7% of them corrected to the wrong word.
 */
object PhoneticCorrection {

    class Candidate(val word: String, val rank: Double, val editCost: Double, val frequency: Int)

    /** A reading's rank: see the class note. [evidence] is its spelling's count. */
    fun rank(evidence: Int, editCost: Double, context: Double): Double =
        ln(1.0 + evidence) - EDIT_WEIGHT * editCost + context

    /** Where the reading a correction would replace stands, on the same scale. */
    fun rivalRank(frequency: Int, context: Double): Double =
        ln(1.0 + frequency.toDouble() / NATIVE_PER_SPELLING) + context

    /** [candidates] with each word at its best route, best first. */
    fun merged(candidates: List<Candidate>): List<Candidate> {
        val best = LinkedHashMap<String, Candidate>()
        for (candidate in candidates) {
            val held = best[candidate.word]
            if (held == null || candidate.rank > held.rank) best[candidate.word] = candidate
        }
        return best.values.sortedWith(compareByDescending<Candidate> { it.rank }.thenBy { it.word })
    }

    /**
     * The reading a space may put in place of the typed one, or null when the
     * word goes in as the rules read it. [ranked] is [merged]'s list, [rival]
     * the [rivalRank] of what the buffer reads as today.
     *
     * Four things have to hold. The edit is a slip ([MAX_SLIP_COST]), since a far
     * key is how a different word gets typed. The word is a real one and not a
     * list's stray ([MIN_FREQUENCY]). It beats what it replaces by a wide margin
     * ([LEAD_MARGIN]), because a name no list holds also looks like nothing here
     * and has to lose to a rare sibling. And nothing else explains the keys about
     * as well ([RUNNER_UP_MARGIN]), which is where the words before it count: two
     * neighbours of one buffer are a coin toss until the context takes a side.
     */
    fun leader(ranked: List<Candidate>, rival: Double): Candidate? {
        val best = ranked.firstOrNull() ?: return null
        if (best.editCost > MAX_SLIP_COST || best.frequency < MIN_FREQUENCY) return null
        if (best.rank - rival < LEAD_MARGIN) return null
        val next = ranked.getOrNull(1) ?: return best
        return best.takeIf { it.rank - next.rank >= RUNNER_UP_MARGIN }
    }

    /** Shorter buffers sit an edit from too many words to say anything about. */
    const val MIN_LENGTH = 4

    /** What the words before count for against the edit and the margins. */
    const val CONTEXT_WEIGHT = 2.0

    /**
     * Native-list occurrences to one written romanized spelling, about what the
     * bn lists show (ভালো at 70,388, `valo` at 5,010): the one scale a curated
     * spelling, which has only its native word's count, can be read on.
     */
    const val NATIVE_PER_SPELLING = 14

    /** Prices an edit against spelling evidence, three times its walk cost. */
    private const val EDIT_WEIGHT = 3.0

    /** An adjacent key, a swap, one letter too many: every far key costs more. */
    private const val MAX_SLIP_COST = 0.4

    private const val MIN_FREQUENCY = 200

    /** About 50 to 1 once the edit is paid for. */
    private const val LEAD_MARGIN = 4.0

    /** About 7 to 1. */
    private const val RUNNER_UP_MARGIN = 2.0
}
