package com.wasimaster.wmkeyboard.ime

/**
 * The text one backspace took, worked out from the field as it read before and
 * after the press, for the strip's undo chip (Telegram, Priyanshu Mishra).
 *
 * Read from the field rather than told by each delete path, because there are
 * a dozen of those (a grapheme, a conjunct, a glided word with its space, an
 * auto-closed pair, a selection, a resumed word's buffer) and every one that
 * forgot to report would be a press the chip silently could not put back.
 */
object DeletionUndo {

    /**
     * How much of the text before the caret each read takes. A run longer than
     * this still builds up press by press; only one press taking more than
     * this at once, which only a selection does, is read differently.
     */
    const val WINDOW = 2048

    /** A run longer than this is not held: it is the text twice over in memory. */
    const val MAX_RUN = 20_000

    /**
     * What the press deleted, in field order: "" when the field did not change,
     * null when it changed in some other way (a correction taken back puts a
     * word in as well as taking one out), which is no deletion to undo.
     *
     * [before] and [after] are the [WINDOW] characters before the caret either
     * side of the press, [selected] what was selected before it.
     */
    fun deleted(before: String, selected: String, after: String): String? {
        if (selected.isNotEmpty()) {
            // The selection goes and the caret lands where it began, so the
            // text in front of it is what it was.
            return if (after == before) selected else null
        }
        if (after == before) return ""
        if (before.length < WINDOW) {
            // The field's start is inside the read, so the lengths are the
            // whole story: what is left must be the start of what was there.
            return if (after.length < before.length && before.startsWith(after)) {
                before.substring(after.length)
            } else {
                null
            }
        }
        // A long field: both reads are a full window, the second one shifted
        // back by however much went. The smallest shift that lines them up is
        // the deletion; in a run of one repeated character a bigger one would
        // line up too, and the smallest is the one a single press makes.
        if (after.length != WINDOW) return null
        for (k in 1 until WINDOW) {
            if (before.regionMatches(0, after, k, WINDOW - k)) return before.substring(WINDOW - k)
        }
        return null
    }

    /**
     * [deleted] for a delete that ate forward, past the caret: a swipe down on
     * backspace taking the line after it, or ⌦. [before] and [after] are the
     * [WINDOW] characters after the caret either side of the press, which
     * stays put, so what is left must be the end of what was there.
     */
    fun deletedAhead(before: String, after: String): String? {
        if (after == before) return ""
        if (before.length < WINDOW) {
            return if (after.length < before.length && before.endsWith(after)) {
                before.substring(0, before.length - after.length)
            } else {
                null
            }
        }
        // A long tail: the second window starts however far into the first
        // the press reached. A press that took more than is left after a full
        // window cannot be lined up, and gets no chip.
        if (after.length != WINDOW) return null
        for (k in 1 until WINDOW) {
            if (before.regionMatches(k, after, 0, WINDOW - k)) return before.substring(0, k)
        }
        return null
    }

    /**
     * Whether a run is worth a chip (#1434): it took at least one whole word,
     * or [LONG_RUN] characters, which is a word's worth in a script that puts
     * no spaces between words. A letter or two taken off the end of a word
     * being fixed is not, and gets no chip, since the backspace that took it
     * is as quick as the chip would be.
     *
     * A word is whole when its start is in the run: the character in front
     * of it, in the run or, for its first character, the last one of
     * [left] (the field before the run), is no word character, or there is
     * none. For a [forward] run it is the word's end that must be in the
     * run, with [right] the field after it. The other end is wherever the
     * cursor stood, which is where the person chose to start deleting.
     */
    fun holdsWord(text: String, left: String, right: String, forward: Boolean): Boolean {
        if (text.codePointCount(0, text.length) >= LONG_RUN) return true
        val points = text.codePoints().toArray()
        for (i in points.indices) {
            if (!isWordPoint(points[i])) continue
            val neighbour = if (forward) {
                if (i + 1 < points.size) points[i + 1] else right.firstOrNull()?.let { right.codePointAt(0) }
            } else {
                if (i > 0) points[i - 1] else left.lastOrNull()?.let { left.codePointBefore(left.length) }
            }
            if (neighbour == null || !isWordPoint(neighbour)) return true
        }
        return false
    }

    /** Letters, digits and the marks that sit on them (Indic vowel signs), plus the apostrophes inside "don't". */
    private fun isWordPoint(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> cp == '\''.code || cp == '’'.code || cp == '‌'.code || cp == '‍'.code
    }

    /** Characters a run takes before it counts as a word's worth whatever it holds; see [holdsWord]. */
    const val LONG_RUN = 10

    /** The chip's line: the run on one line, cut short where it would not fit anyway. */
    fun label(text: String): String {
        val line = text.replace('\n', ' ').trim()
        return if (line.length <= LABEL_CHARS) line else line.take(LABEL_CHARS - 1).trimEnd() + "…"
    }

    private const val LABEL_CHARS = 40
}
