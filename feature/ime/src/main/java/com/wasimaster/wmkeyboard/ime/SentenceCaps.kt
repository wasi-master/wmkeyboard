package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.WordContext

/**
 * The one exception the keyboard makes to the field's own sentence caps: a full
 * stop, question mark or exclamation mark that sits inside a quotation or a
 * bracket which opened in the middle of a sentence (#662).
 *
 * Android's caps mode (`TextUtils.getCapsMode`) looks back past closing quotes
 * and brackets to the mark in front of them, so `An "exclamation!" is` armed
 * shift for "is" as though a new sentence had begun. The mark ended the quoted
 * words, not the sentence around them. When the quotation is the whole
 * sentence, the next word does start a new one, and the caps stay:
 * `"Stop!" Then she left.`
 */

/** Closing quotes and brackets, each with the character that opens it. */
private val PAIR_OPENER_OF: Map<Char, Char> = mapOf(
    ')' to '(', ']' to '[', '}' to '{', '”' to '“', '»' to '«', '"' to '"',
)

/** The opening halves, which may stand in front of an opener ("(“…”)"). */
private val PAIR_OPENERS: Set<Char> = PAIR_OPENER_OF.values.toSet()

/**
 * The marks that end a sentence for the caps rule. Kept to the ones a cased
 * script uses, since only those reach this.
 */
private const val SENTENCE_END_MARKS = ".!?…‽"

/**
 * Whether the caret at the end of [before] follows a sentence-ending mark that
 * closed a quotation or a bracket opened mid-sentence, so the sentence goes on
 * and the next word takes no capital.
 *
 * False whenever the shape is not exactly that: no closer after the mark, an
 * opener that cannot be found on the line, or an opener at the start of the
 * text, the start of a line or straight after another sentence's end, where
 * the quotation is the sentence and the caps are right. The apostrophe is not
 * a closer here, since it is far more often a letter inside "it’s".
 */
internal fun sentenceEndInsidePair(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && before[i - 1] != '\n' && WordContext.isSpaceLike(before[i - 1])) i--
    val closersEnd = i
    while (i > 0 && before[i - 1] in PAIR_OPENER_OF) i--
    if (i == closersEnd || i == 0) return false
    val markAt = i - 1
    if (before[markAt] !in SENTENCE_END_MARKS) return false
    // The innermost closer, the one straight after the mark.
    val opener = PAIR_OPENER_OF[before[i]] ?: return false
    var j = markAt - 1
    while (j >= 0 && before[j] != opener && before[j] != '\n') j--
    if (j < 0 || before[j] != opener) return false
    // What stands in front of the quotation, past spaces and other openers.
    var k = j
    while (k > 0 && before[k - 1] != '\n' &&
        (WordContext.isSpaceLike(before[k - 1]) || before[k - 1] in PAIR_OPENERS)
    ) {
        k--
    }
    if (k == 0) return false
    val previous = before[k - 1]
    if (previous == '\n' || previous in SENTENCE_END_MARKS) return false
    // A closer in front can still be the end of the sentence before
    // (`He left.) "Stop!" Then`), when a mark stands right before it.
    if (previous in PAIR_OPENER_OF) {
        var m = k - 1
        while (m > 0 && before[m - 1] in PAIR_OPENER_OF) m--
        if (m == 0 || before[m - 1] in SENTENCE_END_MARKS) return false
    }
    return true
}

/**
 * Every mark that already ends a sentence for the double-space full stop:
 * the Latin ones plus the script terminators the period key can type
 * (danda, Armenian, Khmer, Myanmar, Ethiopic, Tibetan, Ol Chiki, Meetei
 * Mayek, the CJK full-width ones and the Arabic-script question mark and
 * full stop).
 */
private const val DOUBLE_SPACE_STOP_MARKS = ".!?…‽।॥։។။።།᱾꯫。！？؟۔"

/**
 * Whether [before], the text in front of the first of two spaces, already
 * ends on a sentence mark, past any closing quotes or brackets after it
 * (`test.`, `“Go!”`, `(see above.)`). A double space there must not add a
 * second stop (#1559).
 */
internal fun endsWithSentenceMark(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && before[i - 1] in PAIR_OPENER_OF) i--
    return i > 0 && before[i - 1] in DOUBLE_SPACE_STOP_MARKS
}
