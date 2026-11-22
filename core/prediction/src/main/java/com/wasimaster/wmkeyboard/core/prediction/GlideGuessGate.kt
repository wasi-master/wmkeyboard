package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam

/**
 * Where a stroke's *guesses* — candidates carrying letters the finger has not
 * drawn ([GlideBeam.Candidate.ahead]) — are allowed to stand beside the
 * readings that spell themselves out.
 *
 * Two halves of one seam, and they have to be read together, because the bug
 * they were pulled out of the service for (#317) lived exactly in the gap
 * between them: the sandbox picked which list survived, the gate measured a
 * guess against that list's best reading, and a list chosen *by* a guess has
 * no reading left to measure against. The gate then passed everything and the
 * user's confidence tier became decorative — "When sure" behaved identically
 * to "Eager", which is what the report described.
 *
 * Pure and here rather than in the service so both halves can be measured on
 * real word lists (`GlideFinishedStrokeGuessEvalTest`) instead of reasoned
 * about.
 */
object GlideGuessGate {

    /**
     * [GlideSandboxPolicy.PREFER_LEARNED]: the learned words' answer, when it
     * explains the stroke at least as well as the best word anywhere does,
     * and the full decode otherwise.
     *
     * **Only a learned *reading* may win that comparison.** A guess's
     * `shapeCost` is the alignment of the prefix it was grown from, so a guess
     * off a four-letter prefix is charged for four letters where the word the
     * user actually drew is charged for all of its own — the guess wins the
     * comparison by construction, and winning it threw the whole dictionary
     * decode away. That is how a fully drawn `exam` came back `example`, and
     * how `safe` came back `Safeway` with `safe` nowhere on the strip (#287).
     *
     * When no learned reading wins, the learned guesses are not lost: they are
     * spliced into the full decode in score order, where [admit] can measure
     * them against the best word the stroke really spells and the user's
     * confidence tier means something again. A guess for a word the full
     * decode already reads is dropped — the drawn spelling of a word is always
     * the better answer than a guess at the same word.
     *
     * Cost, not score, for the comparison itself: the two searches weight
     * their sources differently and only the geometry means the same thing in
     * both (see [GlideSandboxLadder.SANDBOX_MARGIN]).
     *
     * When a learned reading wins, it leads the *full* decode's list rather
     * than replacing it ([winnerFirst], #1250). Returning the learned list
     * whole left nothing but the user's own words on the strip, so "Prefer
     * my words" read like "Only my words": once a written word (`notes`) fit
     * as well, a word never written (`nodes`) could not even be picked off
     * the strip.
     */
    fun preferLearned(
        learned: List<GlideBeam.Candidate>,
        full: List<GlideBeam.Candidate>,
    ): List<GlideBeam.Candidate> {
        val reading = learned.firstOrNull { it.ahead == 0 }
        val fullBest = full.firstOrNull { it.ahead == 0 }
        val takeLearned = reading != null && (
            fullBest == null ||
                reading.shapeCost <= fullBest.shapeCost + GlideSandboxLadder.SANDBOX_MARGIN
            )
        if (!takeLearned || reading == null) return splice(full, learned)
        if (fullBest == null) return learned
        return splice(winnerFirst(reading, learned, full), learned)
    }

    /**
     * The learned [winner] first, then the full decode's other words, on the
     * learned decode's scale.
     *
     * The ambiguity picker measures the gap between the first two words of
     * this list, and that gap has to stay the one it measured when the
     * learned list was returned whole: the winner against the learned
     * decode's next reading. So the winner keeps its own score, and the full
     * decode's other words are moved as one block, their gaps to each other
     * kept, until the best of them sits exactly where that next reading did.
     * A winner with no learned reading behind it put no second word on the
     * strip, and no close call; the block then sits [LONE_WINNER_GAP] under
     * it, past every finite margin the picker can be set to. Guesses are
     * spliced in afterwards by score, as they were among the learned words.
     */
    private fun winnerFirst(
        winner: GlideBeam.Candidate,
        learned: List<GlideBeam.Candidate>,
        full: List<GlideBeam.Candidate>,
    ): List<GlideBeam.Candidate> {
        val winnerKey = WordKey.of(winner.word)
        val others = full.filter { it.ahead == 0 && WordKey.of(it.word) != winnerKey }
        if (others.isEmpty()) return listOf(winner)
        val next = learned.firstOrNull { it !== winner && it.ahead == 0 && it.score <= winner.score }
        val anchor = next?.score ?: (winner.score - LONE_WINNER_GAP)
        val shift = anchor - others.maxOf { it.score }
        val merged = ArrayList<GlideBeam.Candidate>(others.size + 1)
        merged.add(winner)
        for (c in others) merged.add(GlideBeam.Candidate(c.word, c.score + shift, c.shapeCost, c.tier, c.ahead))
        return merged
    }

    /**
     * How far under a learned winner with no learned runner-up the full
     * decode's words sit, in nats: past the widest finite picker margin
     * (2.5, "Any doubt"), so such a stroke still never asks.
     */
    private const val LONE_WINNER_GAP = 10.0

    /**
     * [decoded] with its guesses kept only where they clear [margin] — nats
     * over the best word the stroke actually spells — and dropped entirely
     * when [margin] is null, which is the feature switched off.
     *
     * The measure is the gap to the best *ordinary* reading, in the decoder's
     * own log units, which is the only comparison that answers the question
     * the user is really asking: is the keyboard surer about a word I have not
     * finished than about anything I have? A guess that merely outranks other
     * guesses has cleared nothing.
     *
     * The **best** reading, not the first one in the list: by the time a list
     * reaches here it has been through the context rerank, so the word at the
     * front is the one the sentence liked rather than the one the finger drew,
     * and measuring against it hands a guess a bar the stroke never set.
     *
     * A list holding no reading at all is left alone. That is
     * [GlideSandboxPolicy.LEARNED_ONLY] answering for a word its lexicon does
     * not hold, where the guess is the only answer there is and dropping it
     * would type nothing; under every other policy the readings are there and
     * the gate bites.
     *
     * The list keeps its order, so a guess that clears the bar and outscores
     * every reading leads — which is the whole point of the feature — and one
     * that clears it without leading sits on the strip as an alternate.
     */
    fun admit(decoded: List<GlideBeam.Candidate>, margin: Double?): List<GlideBeam.Candidate> {
        if (decoded.none { it.ahead > 0 }) return decoded
        if (margin == null) return decoded.filter { it.ahead == 0 }
        val bestRead = decoded.filter { it.ahead == 0 }.maxByOrNull { it.score } ?: return decoded
        return decoded.filter { it.ahead == 0 || it.score - bestRead.score >= margin }
    }

    /** [full] with [learned]'s guesses put where their scores belong. */
    private fun splice(
        full: List<GlideBeam.Candidate>,
        learned: List<GlideBeam.Candidate>,
    ): List<GlideBeam.Candidate> {
        val guesses = learned.filter { it.ahead > 0 }
        if (guesses.isEmpty()) return full
        val read = full.mapTo(HashSet()) { WordKey.of(it.word) }
        val merged = ArrayList(full)
        for (guess in guesses) {
            if (!read.add(WordKey.of(guess.word))) continue
            val at = merged.indexOfFirst { it.score < guess.score }
            if (at < 0) merged.add(guess) else merged.add(at, guess)
        }
        return merged
    }
}
