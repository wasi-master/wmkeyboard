package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.notes.rich.NoteBlockStyle
import com.wasimaster.wmkeyboard.core.notes.rich.NoteDraft
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMark
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichEdits
import com.wasimaster.wmkeyboard.core.notes.rich.NoteTableOp

/**
 * What the rich editor's chips ask of the keyboard's note editor (#1586).
 * [Slash] wraps a pick from the slash list: the `/name` typed on the line goes
 * first, then the inner action applies.
 */
sealed interface NoteRichAction {
    data class Mark(val mark: NoteMark) : NoteRichAction
    data object ClearMarks : NoteRichAction
    data class Block(val style: NoteBlockStyle) : NoteRichAction
    data object Divider : NoteRichAction
    data object Table : NoteRichAction
    data class TableEdit(val op: NoteTableOp) : NoteRichAction
    data class Move(val up: Boolean) : NoteRichAction
    data class Slash(val inner: NoteRichAction) : NoteRichAction
}

/**
 * The rich editor's side of the keyboard's note editor (#1586): plain
 * functions from the caret text and the edit to what they become, so the
 * service only applies the answer and the tests run on the JVM. The edits
 * themselves are [NoteRichEdits]'s, shared with the app's editor.
 */
object NoteRichInput {

    /** What an action did: the new text, if it changed, and the edit with its waiting formats. */
    data class Applied(val text: CaretText?, val edit: NoteEdit)

    fun draft(text: CaretText): NoteDraft = NoteDraft(text.text, text.selectionStart, text.selectionEnd)

    /** [d] back as caret text, the caret at the selection's end. */
    fun caretText(d: NoteDraft): CaretText = CaretText(d.text, d.end, d.start)

    /** Whether this edit is drawn rich: the setting, unless the note was switched to Markdown for this visit. */
    fun rich(edit: NoteEdit?, richByDefault: Boolean): Boolean = edit != null && (richByDefault != edit.flipped)

    /**
     * [action] on [text]. A format picked with nothing selected goes onto the
     * word the caret is in, as word processors do; between words it waits
     * for the next letters typed ([NoteEdit.pendingMarks]), and a second tap
     * takes it back.
     */
    fun apply(text: CaretText, edit: NoteEdit, action: NoteRichAction): Applied {
        val d = draft(text)
        return when (action) {
            is NoteRichAction.Slash -> {
                val query = NotesLogic.slashQuery(text)
                val cleared = if (query == null) {
                    text
                } else {
                    val at = text.at - query.length - 1
                    CaretText(text.text.substring(0, at) + text.text.substring(text.at), at)
                }
                val inner = apply(cleared, edit, action.inner)
                Applied(inner.text ?: cleared.takeIf { it.text != text.text }, inner.edit)
            }
            is NoteRichAction.Mark -> {
                val pick = NoteRichEdits.pickMark(d, edit.pendingMarks, action.mark)
                val next = edit.copy(pendingMarks = pick.pending)
                pick.draft?.let { changed(text, it, next) } ?: Applied(null, next)
            }
            NoteRichAction.ClearMarks -> {
                val target = if (d.collapsed) NoteRichEdits.wordAround(d) else d
                if (target == null) return Applied(null, edit.copy(pendingMarks = emptyList()))
                val cleared = NoteRichEdits.clearMarks(target)
                changed(text, if (d.collapsed) NoteDraft(cleared.text, cleared.end) else cleared, edit.copy(pendingMarks = emptyList()))
            }
            is NoteRichAction.Block -> changed(text, NoteRichEdits.setBlock(d, action.style), edit)
            NoteRichAction.Divider -> changed(text, NoteRichEdits.insertBlock(d, NoteRichEdits.DIVIDER), edit)
            NoteRichAction.Table -> changed(text, NoteRichEdits.insertBlock(d, NoteRichEdits.TABLE_TEMPLATE), edit)
            is NoteRichAction.TableEdit -> changed(text, NoteRichEdits.tableEdit(d, action.op), edit)
            is NoteRichAction.Move -> changed(text, NoteRichEdits.moveBlock(d, action.up), edit)
        }
    }

    private fun changed(before: CaretText, after: NoteDraft, edit: NoteEdit): Applied {
        val out = caretText(after)
        return Applied(out.takeIf { it.text != before.text || it.at != before.at || it.anchorAt != before.anchorAt }, edit)
    }

    /**
     * An edit the keys made in a rich note, tidied ([NoteRichEdits.settle]):
     * the text to keep, and the edit with its waiting formats spent when they
     * were used.
     */
    fun settle(before: CaretText, after: CaretText, edit: NoteEdit): Applied {
        val settled = NoteRichEdits.settle(draft(before), draft(after).let { NoteDraft(it.text, after.at) }, edit.pendingMarks)
        val out = if (settled.draft.text == after.text && settled.draft.start == after.at) after else caretText(settled.draft)
        return Applied(out, if (settled.usedPending) edit.copy(pendingMarks = emptyList()) else edit)
    }
}
