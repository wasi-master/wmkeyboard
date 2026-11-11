package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.TwoThumbBackspace

/**
 * What one Backspace press does to a two-thumb word under an explicit
 * [TwoThumbBackspace] choice (#1188). One press is one step, never two.
 */
internal enum class TwoThumbBackspaceStep {
    /** [TwoThumbBackspace.CURRENT], or two-thumb typing is off: the rules as they were. */
    LEGACY,

    /** Put back the space "Take the space back" took, and keep what followed as its own word. */
    UNDO_JOIN,

    /** Take only the space the keyboard typed after the word. */
    DELETE_AUTO_SPACE,

    /** Call off a space still owed to the next letter; nothing in the field changes. */
    CANCEL_OWED_SPACE,

    /** Take back the correction (or snippet, join, revision) the word end made. */
    UNDO_CORRECTION,

    /** Take back the word's latest tap or swipe. */
    UNDO_STEP,

    /** Cut the letters the newest gesture put at the word's end, keep the rest as typed (#1232). */
    TRIM_END,

    /** Clear the whole word for fresh entry (Retype, #791). */
    RETYPE_WORD,

    /** Delete one character; the word is ordinary text. */
    DELETE_CHARACTER,

    /** No two-thumb word at the caret: an ordinary Backspace. */
    ORDINARY,
}

/**
 * What the service knows at the moment Backspace lands, already checked
 * against the field: each one true only when the edit it allows would find
 * the text it expects.
 */
internal data class TwoThumbBackspaceFacts(
    /** A word "Take the space back" joined, with something typed after the join. */
    val joinUndoable: Boolean = false,
    /** The character behind the caret is a space the keyboard typed after a word. */
    val autoSpaceBehind: Boolean = false,
    /** A space the keyboard still owes the next letter (held back before a closing mark). */
    val spaceOwed: Boolean = false,
    /** A correction the word end made that Backspace may take back, under its own setting. */
    val correctionUndoable: Boolean = false,
    /** A two-thumb word is open: composing, or gestures waiting for their reading. */
    val wordOpen: Boolean = false,
    /** A two-thumb word was just typed and the caret has not moved off it. */
    val wordBehind: Boolean = false,
    /**
     * An ordinary one-finger glide was just typed (#1191): not a two-thumb
     * word, so it keeps the Backspace it always had.
     */
    val ordinaryGlideBehind: Boolean = false,
)

/**
 * The step for this press. The order is the one the settings page shows:
 * the automatic join, then the keyboard's space, then a correction, then
 * the chosen action on the word. Outside a two-thumb word Backspace is
 * ordinary, which is also what stops Retype walking back through older
 * words: it clears one word, and the next press deletes a character.
 */
internal fun twoThumbBackspaceStep(gesture: GestureSettings, facts: TwoThumbBackspaceFacts): TwoThumbBackspaceStep {
    if (!gesture.twoThumbGlide || gesture.twoThumbBackspace == TwoThumbBackspace.CURRENT) {
        return TwoThumbBackspaceStep.LEGACY
    }
    // Only words begun with two-thumb input get the policy (#1191).
    if (!facts.wordOpen && !facts.wordBehind && facts.ordinaryGlideBehind) return TwoThumbBackspaceStep.LEGACY
    if (gesture.twoThumbBackspaceJoinFirst && facts.joinUndoable && facts.wordOpen) {
        return TwoThumbBackspaceStep.UNDO_JOIN
    }
    if (!facts.wordOpen && gesture.twoThumbBackspaceSpaceFirst) {
        if (facts.autoSpaceBehind) return TwoThumbBackspaceStep.DELETE_AUTO_SPACE
        if (facts.spaceOwed) return TwoThumbBackspaceStep.CANCEL_OWED_SPACE
    }
    if (!facts.wordOpen && facts.correctionUndoable) return TwoThumbBackspaceStep.UNDO_CORRECTION
    if (!facts.wordOpen && !facts.wordBehind) return TwoThumbBackspaceStep.ORDINARY
    return when (gesture.twoThumbBackspace) {
        TwoThumbBackspace.UNDO_STEP -> TwoThumbBackspaceStep.UNDO_STEP
        TwoThumbBackspace.TRIM_END -> TwoThumbBackspaceStep.TRIM_END
        TwoThumbBackspace.RETYPE_WORD -> TwoThumbBackspaceStep.RETYPE_WORD
        TwoThumbBackspace.DELETE_CHARACTER -> TwoThumbBackspaceStep.DELETE_CHARACTER
        TwoThumbBackspace.CURRENT -> TwoThumbBackspaceStep.LEGACY
    }
}

/**
 * Whether the press that [step] names takes back the word's gestures, which
 * is what the shared [GestureSettings.backspaceUndoesGlide] decided before
 * #1188 and still decides under [TwoThumbBackspaceStep.LEGACY].
 */
internal fun TwoThumbBackspaceStep.undoesGestures(gesture: GestureSettings): Boolean = when (this) {
    TwoThumbBackspaceStep.LEGACY -> gesture.backspaceUndoesGlide
    TwoThumbBackspaceStep.UNDO_STEP, TwoThumbBackspaceStep.TRIM_END -> true
    else -> false
}
