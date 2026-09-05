package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable

/**
 * Everything a sideways drag on the backspace key can ask of the service
 * (issue #36).
 *
 * One bundle rather than four callbacks because `ServiceKeyboardContent` sits
 * against the JVM's 64K method ceiling and cannot afford another
 * [KeyboardScreen] parameter — see `ConverterCallbacks` for the same trick.
 * It replaces the old single `onDeleteWord` lambda, which was this gesture's
 * whole API back when the swipe deleted as it went.
 */
@Immutable
class DeleteSwipeCallbacks(
    /**
     * Delete one unit right now, with no preview: the word before the cursor
     * when [byWord], otherwise one character. The path used when the preview
     * is turned off, and the fallback when the editor cannot hold a selection.
     */
    val onDeleteUnit: (byWord: Boolean) -> Unit = {},
    /**
     * Select the [units] units before where the swipe started, so the user
     * sees what is about to go. Shrinking the number gives text back, and 0
     * puts the field back the way the swipe found it.
     *
     * Returns how many units the selection actually covers — fewer than asked
     * once the start of the field is reached, so the gesture can stop buzzing
     * for steps that delete nothing — or -1 when this editor (or an open panel
     * search) cannot show a preview at all, which switches the rest of the
     * gesture to [onDeleteUnit].
     */
    val onSelect: (units: Int, byWord: Boolean) -> Int = { _, _ -> -1 },
    /** The finger lifted with a preview up: delete what it selected. */
    val onCommit: () -> Unit = {},
    /** The gesture ended with nothing selected: drop the preview, delete nothing. */
    val onCancel: () -> Unit = {},
)
