package com.wasimaster.wmkeyboard.core.prediction

/**
 * Continuous touch-likelihood model for typo scoring: where the finger
 * actually landed, not just which key won the hit test.
 *
 * All coordinates are in key-width units in keyboard space (the same
 * convention the gesture decoder uses), so the model is independent of
 * density, orientation and keyboard size. The IME feeds key centers from the
 * live layout and per-keystroke tap positions; when either is missing the
 * engine falls back to the discrete [KeyProximity] adjacency weights.
 */
data class TouchPoint(
    val x: Float,
    val y: Float,
    /**
     * When the finger went down, in uptime milliseconds, or 0 where unknown.
     * The typo model never reads it; it is what places a tap among the other
     * thumb's gestures in a two-thumb word (issue #638).
     */
    val t: Long = 0L,
)

/**
 * How autocorrect treats a word read with several slips
 * ([KeyTouchModel.slipRadius]), the "Fix several missed keys" setting.
 *
 * [OFF] reads no slips at all: every off-key tap is an edit, as before.
 * [CAREFUL] applies a slips-only reading, but leaves one that also needs a
 * letter added, dropped or swapped to the strip. Measured on words held out
 * of the list and typed right, that mix is what rewrote new words, names and
 * slang into something else; held back, the false rewrites fall to where
 * they were without slips, and fixes of wide taps still rise.
 * [EAGER] applies the mixed readings too, for a dictionary that already
 * holds every word its owner types.
 */
enum class SlipFixes { OFF, CAREFUL, EAGER }

/**
 * Per-key 2D Gaussian likelihood. [centers] maps each committed character to
 * its key center in key-width units.
 */
class KeyTouchModel(
    private val centers: Map<Char, TouchPoint>,
    /**
     * Tap-scatter sigma in key widths: how far from a key's centre a tap may
     * land and still read as that key (#385, "mistype tolerance"). Wider
     * makes a tap well inside a neighbour cheaper to read as a slip.
     */
    private val sigma: Double = SIGMA,
    /**
     * How far from a tap, in key widths, a key's centre may sit for the tap
     * to be read as a slip of that key: a mistyped letter priced like the
     * substitution it is, but outside the edit budget, so a word with every
     * key a little wide is still one word. 0 turns slips off.
     */
    val slipRadius: Double = SLIP_SIGMAS * sigma,
    /**
     * Factors on the tap decoder's spread across and down, learned from how
     * widely this hand's taps scatter (`KeyOffsets.spreadFactors`); null
     * reads as 1, 1.
     */
    val decoderSpread: FloatArray? = null,
) {

    private val keysArray: CharArray = centers.keys.toCharArray()
    private val centersArray: Array<TouchPoint> = Array(keysArray.size) { centers[keysArray[it]]!! }

    /** The keys in the order [bestKey] tries them, their centres as x, y pairs, and sigma: for [NativeFuzzySearch]. */
    internal val keyOrder: CharArray get() = keysArray
    internal val centerPairs: FloatArray by lazy {
        FloatArray(keysArray.size * 2) { if (it % 2 == 0) centersArray[it / 2].x else centersArray[it / 2].y }
    }
    internal val sigmaValue: Double get() = sigma

    fun knows(ch: Char): Boolean = centers.containsKey(ch)

    /** Where [ch]'s key is drawn, in key widths, or null off the model. */
    fun center(ch: Char): TouchPoint? = centers[ch]

    /**
     * ln P(tap at [p] | intended key [ch]) up to a constant that cancels in
     * ratios: `-(d^2) / (2 * sigma^2)` with sigma in key widths.
     */
    fun logLikelihood(p: TouchPoint, ch: Char): Double {
        val center = centers[ch] ?: return Double.NEGATIVE_INFINITY
        val dx = (p.x - center.x).toDouble()
        val dy = (p.y - center.y).toDouble()
        return -(dx * dx + dy * dy) / (2.0 * sigma * sigma)
    }

    /** Whether [ch]'s key centre lies within [slipRadius] of [p]. */
    fun withinSlip(p: TouchPoint, ch: Char): Boolean {
        if (slipRadius <= 0.0) return false
        val center = centers[ch] ?: return false
        val dx = (p.x - center.x).toDouble()
        val dy = (p.y - center.y).toDouble()
        return dx * dx + dy * dy <= slipRadius * slipRadius
    }

    /** How far [p] lies from the nearest key's centre, in key widths, or null off an empty model. */
    fun nearestDistance(p: TouchPoint): Double? {
        var best = Double.POSITIVE_INFINITY
        for (center in centersArray) {
            val dx = (p.x - center.x).toDouble()
            val dy = (p.y - center.y).toDouble()
            val d = dx * dx + dy * dy
            if (d < best) best = d
        }
        return if (best.isFinite()) kotlin.math.sqrt(best) else null
    }

    /** The most likely intended key for a tap, or null off-keyboard. */
    fun bestKey(p: TouchPoint): Char? {
        var best: Char? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in keysArray.indices) {
            val center = centersArray[i]
            val dx = (p.x - center.x).toDouble()
            val dy = (p.y - center.y).toDouble()
            val score = -(dx * dx + dy * dy)
            if (score > bestScore) {
                bestScore = score
                best = keysArray[i]
            }
        }
        return best
    }

    companion object {
        /** Tap-scatter sigma in key widths. */
        const val SIGMA = 0.5

        /**
         * The default slip reach, in sigmas: 1.25 key widths at [SIGMA], the
         * keys either side of the one hit and the nearer diagonals. Measured
         * by `wm-engine`'s `noisy_tap_eval` against 1.0 and 1.5.
         */
        const val SLIP_SIGMAS = 2.5
    }
}
