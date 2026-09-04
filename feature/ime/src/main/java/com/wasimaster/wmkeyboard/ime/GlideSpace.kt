package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import kotlin.math.hypot

/**
 * The text and shape decisions behind a glided word's trailing space and the
 * possessive flick that can extend it, pulled out of `WMKeyboardService` so they
 * can be checked without an `InputConnection` or a live keyboard. The service
 * does the field reads and the edits; these only say what a read means.
 */

/**
 * Marks that take back the space a glide typed, so they hug the word they
 * belong to: "hello." and "(hello)", never "hello .".
 *
 * The auto-space marks plus the closers, which is the one place the two sets
 * differ. A closing bracket or quote comes *after* the space in the
 * auto-space-after-punctuation rule's job (spacing what follows a mark) and
 * *before* it in this one (removing what precedes a mark). Openers are absent
 * on purpose — "hello (world)" wants its space kept. So are the CJK wide forms,
 * which no glide produces: the decoder is Latin-only.
 */
private val GLIDE_SPACE_SWALLOWERS =
    charArrayOf('.', '!', '?', '।', ',', ';', ':', ')', ']', '}', '"', '…', '%')

/**
 * The marks that join the list in a URL field, where a glided word is a host
 * or a path segment rather than a word in a sentence: "example" then "/" is
 * "example/", never "example /". The dash is here and not in the prose list
 * because "my-site" is a hostname while "hello - world" is a sentence.
 */
private val URI_GLIDE_SPACE_SWALLOWERS =
    charArrayOf('/', '#', '&', '=', '@', '-', '_', '+', '~')

/**
 * Whether typing [text] right after a glided word should take its space back.
 * [fieldKind] widens the set of marks in a URL field — see
 * [URI_GLIDE_SPACE_SWALLOWERS].
 */
internal fun swallowsGlideSpace(text: String, fieldKind: FieldKind = FieldKind.TEXT): Boolean {
    if (text.length != 1) return false
    val c = text[0]
    if (c in GLIDE_SPACE_SWALLOWERS) return true
    return fieldKind == FieldKind.URI && c in URI_GLIDE_SPACE_SWALLOWERS
}

/**
 * How many characters the glided [word] occupies at the end of [textBefore] —
 * the word, plus the space the commit typed after it when one is there. 0 when
 * the text does not end in that word at all, which is the signal to leave the
 * field alone.
 *
 * Backspace-undo and the strip's tap-to-replace both have to take the space
 * along with the word: without it an undo leaves a stray gap, and a replacement
 * lands on the far side of it ("hello world" out of one swipe). Measured off
 * the text rather than off a flag, so a handwritten word — which arms the same
 * undo but types no trailing space — comes out right too.
 *
 * [textBefore] is the last `word.length + 1` characters before the caret, which
 * is all either caller needs to read.
 */
internal fun glideCommitLength(textBefore: String, word: String): Int = when {
    word.isEmpty() -> 0
    textBefore.endsWith("$word ") -> word.length + 1
    textBefore.endsWith(word) -> word.length
    else -> 0
}

/** How near its two keys the possessive flick starts and ends, in key widths. */
private const val POSSESSIVE_REACH_WIDTHS = 0.7f

/** How much longer than the straight line between those keys the flick may be. */
private const val POSSESSIVE_MAX_DETOUR = 1.6f

/**
 * Whether [points] is the short straight flick that appends `'s`: [from] (the key
 * standing in for the apostrophe) to [to] (the `s` key), in the same pixel space,
 * on a keyboard whose keys are [keyWidthPx] wide.
 *
 * Three tests, and the third is the one that carries the feature. A stroke can
 * start on the punctuation key and end on `s` while spelling a real word on the
 * way, so the travelled length has to agree with the direct distance between the
 * two keys: a word-shaped detour is longer than that and goes on to decode as the
 * word it is. Without it every swipe that happened to begin near the comma and end
 * near `s` would be eaten.
 */
internal fun possessiveFlick(
    points: List<GesturePoint>,
    from: KeyCenter,
    to: KeyCenter,
    keyWidthPx: Float,
): Boolean {
    if (keyWidthPx <= 0f || points.size < 2) return false
    val reach = keyWidthPx * POSSESSIVE_REACH_WIDTHS
    val start = points.first()
    val end = points.last()
    if (!within(start.x, start.y, from.x, from.y, reach)) return false
    if (!within(end.x, end.y, to.x, to.y, reach)) return false

    val direct = hypot(to.x - from.x, to.y - from.y)
    if (direct <= 0f) return false
    var travelled = 0f
    for (i in 1 until points.size) {
        travelled += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    return travelled <= direct * POSSESSIVE_MAX_DETOUR
}

private fun within(x: Float, y: Float, cx: Float, cy: Float, reach: Float): Boolean {
    val dx = x - cx
    val dy = y - cy
    return dx * dx + dy * dy <= reach * reach
}
