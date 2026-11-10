package com.wasimaster.wmkeyboard.core.prediction

/**
 * The typing rhythm of the word being composed, as a signal for how hard
 * autocorrect should push: a fast burst is fat-fingered and wants eager
 * fixing, while slow, deliberate keys are usually exactly what they say —
 * an unusual name, a foreign word — and correcting them reads as fighting
 * the user.
 *
 * The IME feeds it one timestamp per composing keystroke and resets it at
 * every word boundary; [multiplier] turns the collected gaps into a factor
 * on the engine's confidence gate (see [SuggestionEngine.shouldAutocorrect]).
 * Pure and clock-agnostic — callers pass timestamps — so it is JVM-testable.
 */
class KeystrokeTiming {

    private var lastMs = 0L
    private var gapCount = 0
    private var gapTotalMs = 0L

    /** Record one composing keystroke at [nowMs] (any monotonic clock). */
    fun onKeystroke(nowMs: Long) {
        if (lastMs != 0L) {
            val gap = nowMs - lastMs
            // Pauses are thought, not typing rhythm; a mid-word stop to check
            // the spelling must not read as "slow typist". Negative gaps
            // (clock weirdness) are dropped the same way.
            if (gap in 1..MAX_GAP_MS) {
                gapCount++
                gapTotalMs += gap
            }
        }
        lastMs = nowMs
    }

    /** Word boundary: the next word's rhythm starts fresh. */
    fun reset() {
        lastMs = 0L
        gapCount = 0
        gapTotalMs = 0L
    }

    /**
     * Factor for the autocorrect confidence gate, in
     * `[1 - MAX_SHIFT*strength, 1 + MAX_SHIFT*strength]`: below 1 for fast
     * bursts (correct more eagerly), above 1 for deliberate typing (demand
     * near-certainty). Exactly 1.0 — no effect — when [strength] is 0 (the
     * setting's off position) or fewer than [MIN_GAPS] usable gaps exist
     * (short words, hardware keys, pasted text).
     */
    fun multiplier(strength: Float): Double {
        if (strength <= 0f || gapCount < MIN_GAPS) return 1.0
        val mean = gapTotalMs.toDouble() / gapCount
        // Map the mean gap onto [-1, +1]: FAST_MS and below is a full-speed
        // burst, SLOW_MS and above is fully deliberate, linear between.
        val t = ((mean - FAST_MS) / (SLOW_MS - FAST_MS)).coerceIn(0.0, 1.0) * 2.0 - 1.0
        return 1.0 + t * MAX_SHIFT * strength.toDouble().coerceAtMost(1.0)
    }

    /**
     * Whether this word is being typed at full speed: a mean gap of
     * [EAGER_DIGIT_MS] or less over at least [MIN_GAPS] gaps. Unlike
     * [multiplier] it does not follow the timing setting's strength, since
     * it only decides how readily a digit is read as a number-row slip.
     */
    fun isFast(): Boolean = gapCount >= MIN_GAPS && gapTotalMs <= EAGER_DIGIT_MS * gapCount

    companion object {
        /**
         * Mean inter-key gap at or under which a word counts as typed at
         * full speed for digit slips: a phone typist's ordinary pace, well
         * inside the hunt for a number on the top row.
         */
        const val EAGER_DIGIT_MS = 260L

        /** Mean inter-key gap of a flat-out burst, ms. */
        const val FAST_MS = 150.0

        /** Mean gap of visibly deliberate, hunt-and-peck typing, ms. */
        const val SLOW_MS = 450.0

        /** Gaps longer than this are pauses and carry no rhythm signal. */
        const val MAX_GAP_MS = 2_000L

        /** Two gaps (three keystrokes) before the signal says anything —
         * autocorrect already ignores words under three characters. */
        const val MIN_GAPS = 2

        /** At full strength the gate moves at most this fraction each way. */
        const val MAX_SHIFT = 0.5
    }
}
