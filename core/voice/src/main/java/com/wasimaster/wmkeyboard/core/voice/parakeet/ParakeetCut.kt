package com.wasimaster.wmkeyboard.core.voice.parakeet

/**
 * Where a dictation too long for Parakeet's 50 seconds is cut (#1173), from
 * Parakeet Ultra's own voice-activity head: one logit for each 80 ms encoder
 * frame, above zero for speech. The cut goes in the middle of the longest
 * pause in the last [ParakeetEngine.CUT_SEARCH_SECONDS] before the limit, so
 * no word is split, and the audio after it starts the next part.
 *
 * Pure arithmetic, so a test can drive it.
 */
object ParakeetCut {

    /** One encoder frame: 8 mel frames of 10 ms. */
    const val FRAME_SAMPLES = 1_280

    /**
     * The head reads two frames each side; the last two have none on their
     * right, so their logits are guesses and are not cut in.
     */
    private const val EDGE_FRAMES = 2

    /**
     * The sample to cut [count] samples at, given the head's [logits] over
     * them: the middle of the longest run of frames below [threshold] that
     * starts in the last [searchSamples]; with no pause there, the frame most
     * like one. Null when the head said nothing.
     */
    fun pick(logits: FloatArray, count: Int, searchSamples: Int, threshold: Float = 0f): Int? {
        val last = minOf(logits.size, count / FRAME_SAMPLES) - 1 - EDGE_FRAMES
        if (last < 1) return null
        val first = ((count - searchSamples) / FRAME_SAMPLES).coerceIn(1, last)
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        for (f in first..last) {
            if (logits[f] < threshold) {
                if (runStart < 0) runStart = f
                val length = f - runStart + 1
                // A later run of the same length wins: the more said before the cut, the fewer cuts.
                if (length >= bestLength) {
                    bestLength = length
                    bestStart = runStart
                }
            } else {
                runStart = -1
            }
        }
        val frame = if (bestStart >= 0) {
            bestStart + bestLength / 2
        } else {
            (first..last).minBy { logits[it] }
        }
        return (frame * FRAME_SAMPLES + FRAME_SAMPLES / 2).coerceIn(1, count)
    }
}
