package com.wasimaster.wmkeyboard.core.perf

/**
 * How fast the user is typing right now, learned from the gaps between key
 * presses, so work that can wait is put where the user is not typing.
 *
 * Within a burst a typist's gaps are steady: a slow two-finger typist might
 * leave 350 ms between keys, a fast thumb typist 120. The cadence is a
 * moving average of the gaps that look like typing ([MAX_BURST_GAP_MS] or
 * less); a longer one is a pause, and ends the burst. From that:
 *
 *  * [pauseMs] is how long a silence has to last to be a real pause for
 *    *this* typist: about twice their own gap. Work that is only wanted at
 *    a pause (the smart punctuation key's guess, a warm-up) waits that long,
 *    rather than a fixed time that is a pause for one typist and the middle
 *    of a word for another.
 *  * [noteKey] says when a key starts a new burst, which is when the
 *    typing lane tells the system load is coming (see [WorkLane.loadUp]).
 *
 * Pure: time is passed in. Not thread-safe; the keys arrive on the main
 * thread and so do the questions.
 */
class TypingCadence {

    private var lastKeyAt = NEVER
    private var averageGap = DEFAULT_GAP_MS.toFloat()
    private var samples = 0

    /** The typist's usual gap between keys within a burst, in ms. */
    val gapMs: Long get() = averageGap.toLong()

    /**
     * A key was pressed at [now]. Returns true when it starts a burst: the
     * first key, or the first after a pause.
     */
    fun noteKey(now: Long): Boolean {
        val last = lastKeyAt
        lastKeyAt = now
        if (last == NEVER) return true
        val gap = now - last
        if (gap < 0L) return false
        if (gap > MAX_BURST_GAP_MS) return true
        // Once the pace is known, a gap past this typist's pause is a pause
        // too, and is kept out of the average it would drag.
        if (samples >= WARM_SAMPLES && gap > pauseMs()) return true
        // Faster at first, so a new typist is learned in a few keys; then
        // steady, so one slow key does not swing it.
        val weight = if (samples < WARM_SAMPLES) 1f / (samples + 1) else SMOOTHING
        averageGap += (gap.coerceAtLeast(MIN_GAP_MS) - averageGap) * weight
        samples++
        return false
    }

    /** How long a silence must last to count as a pause for this typist, in ms. */
    fun pauseMs(): Long = (averageGap * PAUSE_FACTOR).toLong().coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)

    /** How long until [now] would be a pause, in ms; 0 when it already is. */
    fun untilPauseMs(now: Long): Long {
        if (lastKeyAt == NEVER) return 0L
        return (pauseMs() - (now - lastKeyAt)).coerceAtLeast(0L)
    }

    /** Whether the user is between two keys of a burst at [now]. */
    fun typing(now: Long): Boolean = untilPauseMs(now) > 0L

    /** Forgets the last key, as at a new field; the learned pace stays. */
    fun reset() {
        lastKeyAt = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val DEFAULT_GAP_MS = 220L
        const val MIN_GAP_MS = 40L

        /** Longer than this between two keys and the second starts a new burst. */
        const val MAX_BURST_GAP_MS = 900L
        const val WARM_SAMPLES = 8
        const val SMOOTHING = 0.15f
        const val PAUSE_FACTOR = 2f
        const val MIN_PAUSE_MS = 250L
        const val MAX_PAUSE_MS = 900L
    }
}
