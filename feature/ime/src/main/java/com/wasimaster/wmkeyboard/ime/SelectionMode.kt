package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro

/**
 * What one press of the toolbar's Selection mode tool means, pulled out of
 * `WMKeyboardService` so the tap ladder can be checked without a keyboard.
 *
 * The tool answers to three gestures on the same button, told apart by nothing
 * but their spacing, which is why the counting lives here rather than inline: a
 * tap arms or disarms the mode, two taps in quick succession select the word at
 * the cursor, and three select the line. It is the shift key's double-tap
 * ladder, on the shift key's window, with one more rung.
 *
 * The first tap always acts. Waiting out the window to find out whether a second
 * one is coming would put a third of a second between pressing the button and
 * the mode coming on, which is the price the shift key does not pay either.
 */
enum class SelectionTap {
    /** A single press: turn selection mode on, or off when it is already on. */
    TOGGLE,

    /** A second press inside the window: select the word at the cursor. */
    WORD,

    /** A third: select the whole line the cursor is on. */
    LINE,
}

/**
 * Counts presses of the Selection mode tool into [SelectionTap]s.
 *
 * One instance per keyboard, since the button it counts is one button. Not
 * thread-safe, and does not need to be: every caller is the input thread.
 */
class SelectionTapCounter(private val windowMs: Long) {

    private var lastTapMs = 0L
    private var runLength = 0

    /**
     * The meaning of a press at [nowMs]. [multiTap] off answers TOGGLE every
     * time, so the setting costs the counter nothing to obey.
     *
     * The run stops at three: a fourth quick press starts a new ladder rather
     * than doing nothing, so a drummed-on button keeps toggling instead of going
     * dead. Presses spaced further apart than [windowMs] always start over.
     */
    fun tap(nowMs: Long, multiTap: Boolean): SelectionTap {
        if (!multiTap) {
            reset()
            return SelectionTap.TOGGLE
        }
        runLength = if (nowMs - lastTapMs < windowMs && runLength in 1..2) runLength + 1 else 1
        lastTapMs = nowMs
        return when (runLength) {
            2 -> SelectionTap.WORD
            3 -> SelectionTap.LINE
            else -> SelectionTap.TOGGLE
        }
    }

    /**
     * Forgets the run so far, so the next press reads as a first tap.
     *
     * Called for anything that is not a tap on this button: a press and hold on
     * it, and a new field. Without it, a hold followed by a tap would land as a
     * double tap and select a word nobody asked for.
     */
    fun reset() {
        lastTapMs = 0L
        runLength = 0
    }
}

/**
 * The selection macros as the bar draws them: what is selected, what it turned
 * out to be, and the actions offered for it.
 *
 * Resolved by the service on each selection change rather than by the bar,
 * because deciding costs a set of regexes and a look at the installed apps, and
 * the bar recomposes far more often than the selection changes. [text] travels
 * with the offer so the action a tap runs is about the text the chips were
 * drawn for, not whatever the field holds by the time the tap lands.
 */
data class SelectionMacroOffer(
    /** The selected text, trimmed, exactly as the macros will act on it. */
    val text: String,
    val kind: SelectionKind,
    /** The chips, in the order they are drawn. Never empty; a null offer is used instead. */
    val macros: List<SelectionMacro>,
)
