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
