package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.ln1p

/**
 * Reads a stroke that crossed the spacebar as one phrase rather than as a run
 * of separate words.
 *
 * Each segment's readings are a slot, and a phrase is one word from each. A
 * phrase scores the decoder's nats for its words plus what the n-grams say of
 * each word given the two before it, so a word is judged by the words after it
 * as well as those before: "by a car" loses to "buy a car" once the stroke has
 * drawn "a car", which reading the segments in order can never see. The first
 * words are judged against the two already in the field.
 *
 * The weights were fitted on half the sessions of FUTO's swipe.futo.org test
 * split, whose strokes were drawn for real sentences, and measured on the
 * other half (`PHRASES=1` in `glide_beam_eval`, `phrase_lm_eval`), with the
 * English pack: words .771 → .825, two-word phrases .651 → .726, four-word
 * .410 → .522. Reading the same evidence forward only gets .809, .708 and .494.
 * The context model on top: .848, .760 and .572.
 *
 * With no evidence every lift is 0 and the answer is each slot's leader.
 */
object GlidePhraseLattice {

    /** One segment's readings, its leader first, each with its score in nats. Empty when nothing was read. */
    class Slot(val words: List<String>, val scores: List<Double>)

    /** One word per slot, null for an empty slot, with the phrase's score. */
    class Phrase(val words: List<String?>, val score: Double)

    /** What the words before say of a word, in nats: `(word two back, word one back, word)`. */
    fun interface Lift {
        fun of(back2: String?, back1: String?, word: String): Double
    }

    /** The lift of a trigram's or bigram's count, per `ln(1 + count)`. */
    const val TRIGRAM_WEIGHT = 0.3
    const val BIGRAM_WEIGHT = 0.18

    /** What it costs to follow a word the pack has followers for with one it has never seen there. */
    const val UNSEEN_PAIR_PENALTY = 0.5

    /** The context model's weight on its log-probability of a whole phrase, against the leader's. */
    const val MODEL_WEIGHT = 0.2

    /** The most the context model can move a phrase, per word in it. */
    const val MODEL_CAP_PER_WORD = 1.0

    /** Phrases kept after each slot. Nothing past the last two words affects what comes next. */
    private const val BEAM_WIDTH = 64

    /** Phrases handed back. */
    const val PHRASE_LIMIT = 8

    /** The evidence of [count]s, by tier: the longest context that has seen the word wins. */
    fun lift(trigram: Int, bigram: Int, backKnown: Boolean): Double = when {
        trigram > 0 -> TRIGRAM_WEIGHT * ln1p(trigram.toDouble())
        bigram > 0 -> BIGRAM_WEIGHT * ln1p(bigram.toDouble())
        backKnown -> -UNSEEN_PAIR_PENALTY
        else -> 0.0
    }

    /**
     * The best phrases through [slots], best first, up to [limit]. Ties keep
     * the slots' own order. A candidate with no finite score (a slip offered
     * for the strip, which the decoder never read) is left out of the search,
     * and a slot's first word scores the slot's best: a rewrite such as an
     * offered contraction can lead a list without having the top score, and
     * the leader is who the slot says it is.
     */
    fun read(
        slots: List<Slot>,
        back2: String?,
        back1: String?,
        limit: Int = PHRASE_LIMIT,
        lift: Lift,
    ): List<Phrase> {
        var paths = listOf(Path(0.0, emptyList(), back2, back1))
        for (slot in slots) {
            if (slot.words.isEmpty()) {
                paths = paths.map { Path(it.score, it.words + null, it.back2, it.back1) }
                continue
            }
            val lead = slot.scores.filter { it.isFinite() }.maxOrNull() ?: 0.0
            val next = ArrayList<Path>(paths.size * slot.words.size)
            for (path in paths) {
                slot.words.forEachIndexed { i, word ->
                    val own = if (i == 0) lead else slot.scores.getOrNull(i)
                    if (own == null || !own.isFinite()) return@forEachIndexed
                    val score = path.score + own + lift.of(path.back2, path.back1, word)
                    next.add(Path(score, path.words + word, path.back1, word))
                }
            }
            paths = next.sortedByDescending { it.score }.take(BEAM_WIDTH)
        }
        return paths.take(limit).map { Phrase(it.words, it.score) }
    }

    /**
     * [phrases] with the context model's say: [logProbs] is its ln P of each
     * phrase given the text before it, NaN for one it cannot read, which then
     * keeps its score. Measured against the leader, capped by the phrase's
     * length, and stable, so ties keep the lattice's order.
     */
    fun blendModel(phrases: List<Phrase>, logProbs: List<Float>): List<Phrase> {
        val reference = logProbs.firstOrNull()?.takeIf { !it.isNaN() } ?: return phrases
        return phrases.mapIndexed { i, phrase ->
            val lp = logProbs.getOrNull(i)
            if (lp == null || lp.isNaN()) return@mapIndexed phrase
            val cap = MODEL_CAP_PER_WORD * phrase.words.count { it != null }
            val shift = (MODEL_WEIGHT * (lp - reference)).coerceIn(-cap, cap)
            Phrase(phrase.words, phrase.score + shift)
        }.sortedByDescending { it.score }
    }

    /**
     * [phrase]'s word for each slot, then the slot's other readings as they
     * stand given the words [phrase] put before them: what the strip offers,
     * and what a later fix reads from. Unscored words stay last.
     */
    fun slotOrders(slots: List<Slot>, phrase: Phrase, back2: String?, back1: String?, lift: Lift): List<List<String>> {
        var w2 = back2
        var w1 = back1
        return slots.mapIndexed { index, slot ->
            val chosen = phrase.words.getOrNull(index) ?: return@mapIndexed slot.words
            val scored = slot.words.indices.filter { slot.scores.getOrNull(it)?.isFinite() == true }
            val order = scored.sortedByDescending { slot.scores[it] + lift.of(w2, w1, slot.words[it]) }
                .map { slot.words[it] }
            val unscored = slot.words.indices.filter { it !in scored }.map { slot.words[it] }
            w2 = w1
            w1 = chosen
            listOf(chosen) + (order + unscored).filter { it != chosen }
        }
    }

    private class Path(val score: Double, val words: List<String?>, val back2: String?, val back1: String?)
}
