package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.SequenceEdit

/**
 * How a typing rule's edit ([SequenceEdit], #1493) reaches the field, pulled out
 * of [WMKeyboardService] the way [KeymanSeam] is, so the choice can be checked
 * without an `InputConnection`.
 *
 * The word being typed is a composing buffer that the field mirrors, so an edit
 * that falls inside it is made to the buffer: a then 1 written as ā stays one
 * word with its suggestions, rather than being committed and started again. An
 * edit that reaches past the buffer's start commits the buffer first and then
 * edits the field, the way [MyanmarVisualOrder]'s reorders do.
 */
internal object SequenceRuleSeam {

    sealed interface Plan

    /** Keep the buffer's first [keep] characters and append [text] to them. */
    data class RewriteBuffer(val keep: Int, val text: String) : Plan

    /** Commit the buffer first when [commitFirst], then take [delete] UTF-16 units from before the caret and type [text]. */
    data class EditField(val commitFirst: Boolean, val delete: Int, val text: String) : Plan

    fun plan(edit: SequenceEdit, composingLength: Int): Plan =
        if (composingLength > 0 && edit.delete <= composingLength) {
            RewriteBuffer(composingLength - edit.delete, edit.text)
        } else {
            EditField(commitFirst = composingLength > 0, delete = edit.delete, text = edit.text)
        }

    /**
     * Whether a buffer can be what the rules read. A transliterating board's
     * buffer is a spelling (Avro's `k`, a Telex `aa`), not the text the field
     * shows, so its rules would read the wrong thing; a caret moved inside the
     * buffer means its end is not where the key lands.
     */
    fun bufferReadable(composingLength: Int, transliterating: Boolean, readingCaret: Int): Boolean =
        composingLength == 0 || (!transliterating && readingCaret !in 0 until composingLength)

    /**
     * What the Backspace right after an `undo` rule does: the edit that puts
     * [restore] back in place of [inserted], when [before] (the text before the
     * caret) still ends in what the rule wrote. Null otherwise, and the press
     * deletes as usual.
     */
    fun undo(before: CharSequence?, inserted: String, restore: String): Pair<Int, String>? {
        if (before == null || inserted.isEmpty() || !before.endsWith(inserted)) return null
        return inserted.length to restore
    }
}

/**
 * The undo an `undo` rule left (#1493): [restore] in place of [inserted], on the
 * Backspace at [caret]. A caret of -1 is learned from the edit's own selection
 * echo, as [WMKeyboardService]'s chip restore learns its own.
 */
internal data class SequenceUndo(val inserted: String, val restore: String, val caret: Int)
