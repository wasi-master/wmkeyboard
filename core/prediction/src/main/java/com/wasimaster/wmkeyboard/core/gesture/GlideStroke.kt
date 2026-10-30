package com.wasimaster.wmkeyboard.core.gesture

/**
 * One finger path, kept whole so it can be decoded again later.
 *
 * The grid goes with the points on purpose: a stroke is only meaningful
 * against the keys it was drawn on, and by the time it is read back the
 * keyboard may be in another orientation, another layout or another size.
 * [shape] is the sample the shape store files under the word, when swipe-style
 * learning is on.
 */
class GlideStroke(
    val points: List<GesturePoint>,
    val keys: List<KeyCenter>,
    val keyWidthPx: Float,
    val shape: GlideShapeSample? = null,
) {

    /**
     * True when this stroke could have been an attempt at [word] on [map], the
     * grid it was drawn on: it starts no further than [END_SLACK] key widths
     * from the key of the word's first letter, and ends that close to the key
     * of its last.
     *
     * The gate a backspaced glide's stroke passes before the next word may
     * claim it (#805). Deleting a word and typing a different one is as often
     * an edit of the sentence as a retry of the swipe, and without it a
     * stroke drawn for "the" taught the swipe learners that it meant "a".
     * The ends are what a glide gets right even when its middle is sloppy,
     * and what the decoder itself leans on hardest, so a word whose ends the
     * finger never came near is not this stroke's answer.
     *
     * False when the stroke is empty or either end letter is not on the grid
     * at all (a romanized stroke's Bengali answer): nothing can be judged, so
     * nothing is taught.
     */
    fun endsFit(word: String, map: GlideKeyMap): Boolean {
        if (points.isEmpty() || word.isEmpty() || keyWidthPx <= 0f) return false
        val first = map.keyIndex(word.codePointAt(0))
        val last = map.keyIndex(word.codePointBefore(word.length))
        if (first < 0 || last < 0) return false
        return near(points.first(), first, map) && near(points.last(), last, map)
    }

    private fun near(point: GesturePoint, key: Int, map: GlideKeyMap): Boolean {
        val dx = point.x / keyWidthPx - map.keyX[key]
        val dy = point.y / keyWidthPx - map.keyY[key]
        return dx * dx + dy * dy <= END_SLACK * END_SLACK
    }

    companion object {
        /**
         * How many samples at one end of [points] are a straight walk to or
         * from the spacebar (#968): `wm-engine`'s `glide::open_run`, number
         * for number. From the bar end inward, every sample that lies below
         * the next one inward (within [OPEN_RUN_LEVEL] of level: the bar is
         * under the keys), for as long as the samples between stay within
         * [OPEN_RUN_BEND] key widths of the straight line from the bar end to
         * that sample. Straight, not vertical: the bar spans the middle five
         * keys, so a word ending on `p` walks to it diagonally. The run has
         * to drop [OPEN_RUN_DESCENT] in all. The letter the walk left may sit on the run ("then": `n`
         * is straight above the bar), which is why the decoder reads the
         * run as optional rather than cut. 0 for no run, never more than
         * leaves three samples closed.
         */
        fun openRun(points: List<GesturePoint>, keyWidthPx: Float, atEnd: Boolean): Int {
            val n = points.size
            if (n < 4 || keyWidthPx <= 0f) return 0
            val level = OPEN_RUN_LEVEL * keyWidthPx
            val bend = OPEN_RUN_BEND * keyWidthPx
            fun at(i: Int) = if (atEnd) points[n - 1 - i] else points[i]
            val barEnd = at(0)
            var run = 0
            val maxRun = n - 3
            while (run < maxRun) {
                val inner = at(run + 1)
                if (at(run).y < inner.y - level) break
                // Every sample so far on the straight line to the new one.
                val dx = inner.x - barEnd.x
                val dy = inner.y - barEnd.y
                val reach = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                val bent = (1..run).any {
                    kotlin.math.abs((at(it).x - barEnd.x) * dy - (at(it).y - barEnd.y) * dx) > bend * reach
                }
                if (bent) break
                run++
            }
            if (run < 2) return 0
            return if (barEnd.y - at(run).y < OPEN_RUN_DESCENT * keyWidthPx) 0 else run
        }

        /** [openRun]: how much a sample may sit above the next one inward, in key widths. */
        const val OPEN_RUN_LEVEL = 0.05f

        /** [openRun]: how far a sample may lie off the walk's straight line, in key widths. */
        const val OPEN_RUN_BEND = 0.2f

        /** [openRun]: the least the walk must descend toward the bar in all, in key widths. */
        const val OPEN_RUN_DESCENT = 0.5f

        /**
         * How far, in key widths, a stroke's end may land from the letter it
         * stands for. Wider than the decoder's own start and end radius (1.6
         * by default), since a stroke the decoder misread is exactly one whose
         * ends wandered: rows are taller than keys are wide, so the far edge
         * of the key above or below is already about two key widths off.
         * 1.5 refused real corrections (#805); 2.0–2.2 is where learning on
         * QWERTY behaved in the reporter's own trials, and "the" still starts
         * over three keys from "a".
         */
        const val END_SLACK = 2.2f
    }
}
