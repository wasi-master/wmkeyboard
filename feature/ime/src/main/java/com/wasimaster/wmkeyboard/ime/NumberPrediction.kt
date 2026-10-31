package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.script.VERBATIM_DIGITS
import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import com.wasimaster.wmkeyboard.core.tools.NumberGroups

/**
 * The number a composing word spells through the corner hints of the keys it
 * was typed on (#181): "qwe" on a QWERTY board with the number row off is
 * also "123", and the strip may offer it beside the words.
 *
 * Pure and layout-agnostic on purpose. AZERTY puts the 1 on `a`, Dvorak on
 * the apostrophe, and the German and Turkish boards carry no digit hints at
 * all — so the hints are read off the keys that were actually pressed, one
 * per composing character, rather than assumed from the letters. A number
 * row that is showing has already had those hints stripped from the keys
 * (see `currentLayout`), which is what keeps this quiet exactly when the
 * issue asks it to be.
 */
internal object NumberPrediction {

    /**
     * Shorter than this and nothing is offered. Every single-letter word on
     * the top row spells a digit — "I" is an 8 — and a lone digit is a hold or
     * a flick away in any case.
     */
    const val MIN_LENGTH = 2

    /**
     * The digit a key holds among its [alternates], wherever in them it sits.
     * AZERTY's `a` holds à first and its 1 after, and reading only the first
     * alternate made `aze` spell nothing while `rt` spelled 45.
     */
    fun digitHint(alternates: List<String>): String? {
        for (alternate in alternates) {
            val hint = alternate.removePrefix(VERBATIM_DIGITS)
            if (hint.length == 1 && hint[0].isDigit()) return hint
        }
        return null
    }

    /**
     * The number [composing] spells, or null when any of its characters came
     * off a key with no digit hint. [hints] holds, per character, the corner
     * hint of the key that typed it, null where unknown; a character that is
     * itself a digit (a hold-typed 3 in the middle of "qw3") stands for itself.
     *
     * A run that the number chip would group — five to fifteen digits, no
     * leading zero — is grouped the same way under [grouping] when [group] is
     * on, so the two never disagree about what a long number looks like.
     * Anything else, and any run in non-ASCII digits, is offered as typed.
     */
    fun predict(
        composing: CharSequence,
        hints: List<String?>,
        group: Boolean,
        grouping: NumberGrouping,
        localeTag: String,
    ): String? {
        if (composing.length < MIN_LENGTH || hints.size != composing.length) return null
        val digits = StringBuilder(composing.length)
        for (i in composing.indices) {
            val hint = hints[i]
            val digit = when {
                hint != null && hint.length == 1 && hint[0].isDigit() -> hint[0]
                composing[i].isDigit() -> composing[i]
                else -> return null
            }
            digits.append(digit)
        }
        val run = digits.toString()
        val quantity = group &&
            run.length in MIN_GROUPED..MAX_GROUPED &&
            run[0] != '0' &&
            run.all { it in '0'..'9' }
        return if (quantity) NumberGroups.group(run, NumberGroups.styleFor(grouping, localeTag)) else run
    }

    /**
     * [strip] with [number] in its last visible slot of [slotCount], the
     * candidates behind it pushed back rather than out. Never the first slot:
     * that is the one a space commits on some boards and the one the eye
     * reads as the correction, and a number is neither. Nothing changes when
     * there is no number, or when the strip already offers it.
     */
    fun place(strip: List<String>, number: String?, slotCount: Int): List<String> {
        if (number == null || strip.any { it == number }) return strip
        val at = (slotCount - 1).coerceIn(0, strip.size)
        return strip.take(at) + number + strip.drop(at)
    }

    /** The number chip's own bounds for a run that reads as a quantity. */
    private const val MIN_GROUPED = 5
    private const val MAX_GROUPED = 15
}
