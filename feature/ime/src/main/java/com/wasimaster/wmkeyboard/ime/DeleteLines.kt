package com.wasimaster.wmkeyboard.ime

/**
 * How far the delete-line keys and swipes reach (#1162), shared by the delete
 * itself and by its preview (#1248), so what is selected is what goes.
 *
 * Backward, one step takes the caret's line up to its start, or, with the
 * caret already at a line's start, the line above and the break that ends it.
 * Forward, the rest of the line, then the break and the line below.
 *
 * A line is a run of text between line breaks, unless [wraps] say where the
 * editor wrapped a long paragraph onto more lines (competitor gap list item
 * 16): each wrap is then a line boundary too, so one long message is taken a
 * drawn line at a time. A wrap is the offset in the text where a wrapped line
 * starts; one that falls on a hard line start changes nothing.
 */
internal object DeleteLines {

    /**
     * Characters one step takes from [text]: the text before the caret when
     * going back, after it when going forward. 0 when there is nothing.
     * [wraps] are offsets into [text] where a drawn line starts.
     */
    fun step(text: CharSequence, forward: Boolean, wraps: Collection<Int> = emptyList()): Int {
        if (text.isEmpty()) return 0
        return if (forward) {
            // The nearest line end past the caret: the next break (past the
            // one the caret sits on, which the step takes with it), the end,
            // or a wrap before either. A wrap straight after a break is that
            // line's own start, not a wrap inside it.
            val newline = if (text[0] == '\n') text.indexOf('\n', 1) else text.indexOf('\n')
            val hard = if (newline < 0) text.length else newline
            wraps.filter { it in 1 until hard && text[it - 1] != '\n' }.minOrNull() ?: hard
        } else {
            // The nearest line start before the caret's end: after a break or
            // at a wrap, and the text's own start.
            val length = text.length
            val trimmed = if (text[length - 1] == '\n') length - 1 else length
            var start = text.subSequence(0, trimmed).lastIndexOf('\n') + 1
            wraps.filter { it in (start + 1) until length }.maxOrNull()?.let { start = it }
            length - start
        }
    }

    /**
     * How far 1, 2, 3... steps reach into [text], up to [lines] of them, in
     * characters from the caret. Shorter than [lines] once the text runs out.
     */
    fun reach(text: CharSequence, lines: Int, forward: Boolean, wraps: Collection<Int> = emptyList()): List<Int> {
        val out = ArrayList<Int>(lines.coerceAtLeast(0))
        var taken = 0
        while (out.size < lines && taken < text.length) {
            val step = if (forward) {
                step(text.subSequence(taken, text.length), true, wraps.map { it - taken }.filter { it > 0 })
            } else {
                val end = text.length - taken
                step(text.subSequence(0, end), false, wraps.filter { it < end })
            }
            if (step <= 0) break
            taken += step
            out += taken
        }
        return out
    }
}
