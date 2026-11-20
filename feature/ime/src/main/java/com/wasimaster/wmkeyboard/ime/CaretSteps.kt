package com.wasimaster.wmkeyboard.ime

/**
 * How many UTF-16 chars [steps] caret steps cover in [text], the text beside
 * the caret on the side it moves to: [backward] walks from the end of [text],
 * otherwise from its start. A surrogate pair is one step, so an emoji is never
 * split; a step past the end of [text] covers nothing.
 */
internal fun caretStepChars(text: CharSequence, steps: Int, backward: Boolean): Int {
    var chars = 0
    var left = steps
    while (left > 0 && chars < text.length) {
        val pair = if (backward) {
            val i = text.length - chars
            i >= 2 && Character.isSurrogatePair(text[i - 2], text[i - 1])
        } else {
            chars + 1 < text.length && Character.isSurrogatePair(text[chars], text[chars + 1])
        }
        chars += if (pair) 2 else 1
        left--
    }
    return chars
}
