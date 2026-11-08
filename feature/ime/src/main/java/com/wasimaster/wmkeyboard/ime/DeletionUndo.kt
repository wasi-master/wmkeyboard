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

    /** The chip's line: the run on one line, cut short where it would not fit anyway. */
    fun label(text: String): String {
        val line = text.replace('\n', ' ').trim()
        return if (line.length <= LABEL_CHARS) line else line.take(LABEL_CHARS - 1).trimEnd() + "…"
    }

    private const val LABEL_CHARS = 40
}
