package com.wasimaster.wmkeyboard.ime

/**
 * How far the delete-line keys and swipes reach (#1162), shared by the delete
 * itself and by its preview (#1248), so what is selected is what goes.
 *
 * Backward, one step takes the caret's line up to its start, or, with the
 * caret already at a line's start, the line above and the break that ends it.
 * Forward, the rest of the line, then the break and the line below.
 */
internal object DeleteLines {

    /**
     * Characters one step takes from [text]: the text before the caret when
     * going back, after it when going forward. 0 when there is nothing.
     */
    fun step(text: CharSequence, forward: Boolean): Int {
        if (text.isEmpty()) return 0
        return if (forward) {
            if (text[0] == '\n') {
                val below = text.indexOf('\n', 1)
                if (below < 0) text.length else below
            } else {
                text.indexOf('\n').let { if (it < 0) text.length else it }
            }
        } else if (text[text.length - 1] == '\n') {
            text.length - (text.subSequence(0, text.length - 1).lastIndexOf('\n') + 1)
        } else {
            text.length - (text.lastIndexOf('\n') + 1)
        }
    }

    /**
     * How far 1, 2, 3... steps reach into [text], up to [lines] of them, in
     * characters from the caret. Shorter than [lines] once the text runs out.
     */
    fun reach(text: CharSequence, lines: Int, forward: Boolean): List<Int> {
        val out = ArrayList<Int>(lines.coerceAtLeast(0))
        var taken = 0
        while (out.size < lines && taken < text.length) {
            val rest = if (forward) text.subSequence(taken, text.length) else text.subSequence(0, text.length - taken)
            val step = step(rest, forward)
            if (step <= 0) break
            taken += step
            out += taken
        }
        return out
    }
}
