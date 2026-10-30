package com.wasimaster.wmkeyboard.core.text

/**
 * What ends a word for a word-at-a-time step, beyond the spaces that always do
 * (#662).
 *
 * [punctuation]: a run of letters and a run of marks are separate words, so
 * "wmkeyboard.pages.dev" is five of them. An apostrophe with a letter on both
 * sides is part of the word, so "don't" and "I'll" are one (#1032). The
 * underscore is a mark like any other, so "wordtwo_wordthree" is three. Off,
 * everything between two spaces is one word, an address included.
 *
 * [capitals]: a capital letter after a small one starts a new word, and so
 * does the last capital of a run that a small letter follows, so "GrapheneOS"
 * is "Graphene" and "OS", and "HTMLParser" is "HTML" and "Parser".
 *
 * [digits]: a run of digits and a run of letters are separate words, so
 * "word1" is "word" and "1", and "mp3player" is three.
 */
data class WordBreaks(val punctuation: Boolean, val capitals: Boolean, val digits: Boolean = false) {
    companion object {
        /** What the delete keys have always used: punctuation, not capitals. */
        val DELETE_DEFAULT = WordBreaks(punctuation = true, capitals = false)
    }
}

/**
 * Word-at-a-time steps over text, as the delete keys and the word cursor
 * moves use them.
 *
 * One step takes the whitespace immediately next to the cursor *and* the word
 * beyond it, so a cursor parked after "hello world " still eats "world " in
 * one step instead of spending a whole step on the space.
 */
object WordDelete {

    /**
     * UTF-16 units to delete for one word step, given the text before the
     * cursor. 0 when there is nothing left.
     *
     * With the default [breaks], punctuation runs count as their own word, so
     * a swipe never stalls on "..." — but a run of letters stops at the first
     * mark rather than eating the whole line.
     */
    fun lengthBefore(before: CharSequence, breaks: WordBreaks = WordBreaks.DELETE_DEFAULT): Int {
        if (before.isEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        val end = i
        while (i > 0) {
            val left = before[i - 1]
            if (left.isWhitespace()) break
            if (i < end && breaksAt(before, i, breaks)) break
            i--
        }
        return before.length - i
    }

    /**
     * The same step measured the other way: UTF-16 units a forward word delete
     * takes, given the text after the cursor (issue #226). 0 when the text ends
     * there.
     *
     * The mirror of [lengthBefore] in every detail, including taking the
     * whitespace the cursor is already sitting on before the word past it, so
     * ⌦ held in "hello | world" eats " world" in one step rather than spending
     * a step on the space.
     */
    fun lengthAfter(after: CharSequence, breaks: WordBreaks = WordBreaks.DELETE_DEFAULT): Int {
        if (after.isEmpty()) return 0
        var i = 0
        while (i < after.length && after[i].isWhitespace()) i++
        val start = i
        while (i < after.length) {
            val right = after[i]
            if (right.isWhitespace()) break
            if (i > start && breaksAt(after, i, breaks)) break
            i++
        }
        return i
    }

    /**
     * The word the cursor at [at] in [text] sits in or against, as a range of
     * offsets, or null when there is none: the cursor between two spaces, or
     * only marks around it while [WordBreaks.punctuation] keeps them out of
     * words. A cursor between a word and a mark takes the word.
     */
    fun wordAround(text: CharSequence, at: Int, breaks: WordBreaks): IntRange? {
        if (at !in 0..text.length) return null
        fun inWord(i: Int) = !text[i].isWhitespace() && (!breaks.punctuation || isWordCharAt(text, i))
        var start = at
        var end = at
        while (start > 0 && inWord(start - 1) && !(start < at && breaksAt(text, start, breaks))) {
            start--
        }
        while (end < text.length && inWord(end) && !(end > at && breaksAt(text, end, breaks))) {
            end++
        }
        // A capital or digit boundary at the cursor itself: the cursor belongs
        // to the word it starts, the same as in front of any word.
        if (start < at && at < end && breaksAt(text, at, breaks)) {
            start = at
        }
        return if (start < end) start until end else null
    }

    /** Letters, digits and the marks that spell words in many scripts. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    /** Like [isWordChar], with an apostrophe between two letters counted in: "don't", "I'll". */
    private fun isWordCharAt(text: CharSequence, i: Int): Boolean =
        isWordChar(text[i]) || (
            (text[i] == '\'' || text[i] == '\u2019') &&
                text.getOrNull(i - 1)?.isLetter() == true && text.getOrNull(i + 1)?.isLetter() == true
            )

    /**
     * Whether a word step stops between the characters at [at] - 1 and [at],
     * neither of them a space. The one after them is what the capitals rule
     * needs to tell "HTML|Parser" from "HT|ML".
     */
    private fun breaksAt(text: CharSequence, at: Int, breaks: WordBreaks): Boolean {
        val left = text[at - 1]
        val right = text[at]
        val next = text.getOrNull(at + 1)
        if (breaks.punctuation && isWordCharAt(text, at - 1) != isWordCharAt(text, at)) return true
        if (breaks.capitals) {
            if (left.isLowerCase() && right.isUpperCase()) return true
            if (left.isUpperCase() && right.isUpperCase() && next?.isLowerCase() == true) return true
        }
        if (breaks.digits && (left.isLetter() && right.isDigit() || left.isDigit() && right.isLetter())) return true
        return false
    }
}
