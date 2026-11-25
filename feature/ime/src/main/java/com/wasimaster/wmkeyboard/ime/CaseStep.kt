package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.util.lowercaseKeepingLength
import com.wasimaster.wmkeyboard.core.util.uppercaseKeepingLength

/**
 * [s] one case form up (lower → Title → UPPER) or down (UPPER → Title →
 * lower), for `TextEditAction.CASE_UP` / `CASE_DOWN` (issue #1409): what
 * Thumb-Key's shift arms do when swiped out and back again.
 *
 * Unlike the shift key's re-case cycle this one stops at the ends rather than
 * wrapping, because each direction has an arm of its own. A word in mixed
 * case goes to Title going up and to lower going down; a one-letter word,
 * whose Title and UPPER are the same, skips straight between the two. A word
 * with no case (Bengali, digits) comes back unchanged. Every form keeps the
 * spelling and the length, as [uppercaseKeepingLength] promises, so the caret
 * can go back to the same offset.
 */
internal fun caseFormStep(s: String, up: Boolean): String {
    val lower = s.lowercaseKeepingLength()
    val upper = s.uppercaseKeepingLength()
    if (lower == upper) return s
    val title = toTitleCase(s)
    return if (up) {
        when (s) {
            upper -> upper
            lower -> title
            title -> upper
            else -> title
        }
    } else {
        when (s) {
            lower -> lower
            upper -> if (title != upper) title else lower
            else -> lower
        }
    }
}

/**
 * Title Case: the first letter of each word up, the rest of it down (#1555).
 *
 * A word is a run of letters, digits and combining marks, joined across an
 * apostrophe that has a letter or digit on both sides, so `don't` is one word
 * and comes out `Don't`, not `Don'T`. A quote that opens or closes a word is
 * not part of it: `'hello'` goes to `'Hello'`. A word that starts with a
 * digit has no first letter to raise and is left as it is (`2nd`, `4G`).
 *
 * [reference] is the text as it stood before the case was first changed, the
 * same length as [s]. A word written there in mixed case (`iPhone`,
 * `YouTube`) or in capitals (`SMS`) keeps that spelling, so taking a sentence
 * through lowercase and back to Title Case gives its names back. Capitals
 * count only while the reference is not capitals throughout: in a shouted
 * sentence an acronym cannot be told from any other word. Null keeps nothing.
 *
 * Keeps the length, as the other case forms do, so a caret can go back to the
 * same offset.
 */
internal fun toTitleCase(s: String, reference: String? = null): String {
    val ref = reference?.takeIf { it.length == s.length }
    val capitalsMeanSomething = ref != null && ref != ref.uppercaseKeepingLength()
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (!s[i].isTitleWordChar()) {
            out.append(s[i])
            i++
            continue
        }
        var end = i + 1
        while (end < s.length) {
            val c = s[end]
            if (c.isTitleWordChar()) {
                end++
            } else if (c in WORD_APOSTROPHES && end + 1 < s.length && s[end + 1].isLetterOrDigit()) {
                end += 2
            } else {
                break
            }
        }
        when {
            ref != null && keepsOwnCase(ref, i, end, capitalsMeanSomething) -> out.append(ref, i, end)
            s[i].isDigit() -> out.append(ref ?: s, i, end)
            else -> {
                out.append(s[i].titlecaseChar())
                out.append(s.substring(i + 1, end).lowercaseKeepingLength())
            }
        }
        i = end
    }
    return out.toString()
}

/** `'` and `’`, which join `don't` into one word when a letter follows. */
private const val WORD_APOSTROPHES = "'’"

private fun Char.isTitleWordChar(): Boolean = isLetterOrDigit() || when (category) {
    CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
    else -> false
}

/**
 * Whether the word at [start] until [end] of [ref] is a name to leave as it
 * was written: mixed case with a capital past its first letter, or (when
 * [capitals] says capitals tell anything) two or more capitals and no small
 * letter.
 */
private fun keepsOwnCase(ref: String, start: Int, end: Int, capitals: Boolean): Boolean {
    var cased = 0
    var lower = false
    var upperPastFirst = false
    for (k in start until end) {
        val c = ref[k]
        if (c.isLowerCase()) {
            lower = true
            cased++
        } else if (c.isUpperCase() || c.isTitleCase()) {
            if (k > start) upperPastFirst = true
            cased++
        }
    }
    if (cased < 2) return false
    return if (lower) upperPastFirst else capitals
}

/**
 * One press of shift on a selection (or the word at the caret): the next form
 * of [s] in the cycle lower → Title → UPPER → lower.
 *
 * [origin] is the text the cycle started from, which [toTitleCase] keeps its
 * names and acronyms from; [firstPress] is true when [s] is that text, untouched
 * yet. Text in none of the three forms goes to lower first, unless
 * [titleFirst] (#1555), when the first press of a cycle goes to Title Case
 * whatever the text was. A form that would change nothing is skipped, so
 * `2nd`, whose Title Case is itself, goes straight to `2ND`. Caseless text
 * comes back unchanged.
 */
internal fun recaseStep(s: String, origin: String, firstPress: Boolean, titleFirst: Boolean): String {
    val lower = s.lowercaseKeepingLength()
    val upper = s.uppercaseKeepingLength()
    if (lower == upper) return s
    val title = toTitleCase(s, origin)
    if (titleFirst && firstPress && s != title) return title
    return when {
        s == lower -> if (title != lower) title else upper
        s == title && title != upper -> upper
        s == upper -> lower
        else -> lower
    }
}
