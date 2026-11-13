package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable

/**
 * Everything a sideways drag on a delete key can ask of the service
 * (issue #36), and what a held one asks when it clears words (issue #216).
 *
 * One bundle rather than four callbacks because `ServiceKeyboardContent` sits
 * against the JVM's 64K method ceiling and cannot afford another
 * [KeyboardScreen] parameter — see `ConverterCallbacks` for the same trick.
 * It replaces the old single `onDeleteWord` lambda, which was this gesture's
 * whole API back when the swipe deleted as it went.
 *
 * `forward` runs through the two calls that touch text rather than splitting
 * the bundle in two: backspace and ⌦ are one gesture pointed two ways
 * (issue #226), and the service walks the same swipe state either way.
 */
@Immutable
class DeleteSwipeCallbacks(
    /**
     * Delete one unit right now, with no preview: a word when [byWord],
     * otherwise one character, taken after the cursor when [forward] and
     * before it when not. The path used when the preview is turned off, the
     * fallback when the editor cannot hold a selection, and what one tick of a
     * word-clearing hold does.
     */
    val onDeleteUnit: (byWord: Boolean, forward: Boolean) -> Unit = { _, _ -> },
    /**
     * Select the [units] units past where the swipe started, so the user sees
     * what is about to go. Shrinking the number gives text back, and 0 puts
     * the field back the way the swipe found it.
     *
     * Returns how many units the selection actually covers — fewer than asked
     * once the end of the text is reached, so the gesture can stop buzzing
     * for steps that delete nothing — or -1 when this editor (or an open panel
     * search) cannot show a preview at all, which switches the rest of the
     * gesture to [onDeleteUnit].
     */
    val onSelect: (units: Int, byWord: Boolean, forward: Boolean) -> Int = { _, _, _ -> -1 },
    /**
     * Select the whole field, as the preview of an upward swipe on ⌫ that
     * clears it. [onCommit] and [onCancel] end it as they end any preview.
     * False when the field cannot be selected whole, and the swipe does nothing.
     */
    val onSelectAll: () -> Boolean = { false },
    /**
     * Select the [lines] lines a delete-line arm would take (#1248), going
     * forward or back. Returns the lines covered, 0 when there is nothing to
     * take, or -1 when no preview can be shown and the arm should delete as it
     * always did. [onCommit] and [onCancel] end it as they end any preview.
     */
    val onSelectLines: (lines: Int, forward: Boolean) -> Int = { _, _ -> -1 },
    /** The finger lifted with a preview up: delete what it selected. */
    val onCommit: () -> Unit = {},
    /** The gesture ended with nothing selected: drop the preview, delete nothing. */
    val onCancel: () -> Unit = {},
)

/**
 * Which way a delete-line key goes (#1162): true forward, false back, null
 * for any other key. What tells the delete key's gesture that an arm it
 * reached can show its lines first (#1248).
 */
internal fun com.wasimaster.wmkeyboard.core.layout.Key.deletesLinesForward(): Boolean? =
    when ((action as? com.wasimaster.wmkeyboard.core.layout.KeyAction.Edit)?.op) {
        com.wasimaster.wmkeyboard.core.settings.TextEditAction.DELETE_LINE -> false
        com.wasimaster.wmkeyboard.core.settings.TextEditAction.DELETE_LINE_FORWARD -> true
        else -> null
    }
