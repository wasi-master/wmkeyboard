package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteFilter
import com.wasimaster.wmkeyboard.core.notes.NotePassages
import com.wasimaster.wmkeyboard.core.notes.NoteShelf
import com.wasimaster.wmkeyboard.core.notes.NoteSort
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.notes.NoteTag
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks

/**
 * The Notes panel's state (#560) and the pure logic behind it: what the list
 * shows, what Enter does in the body, what the Markdown helper chips insert,
 * and how an edit meets a note the settings app changed meanwhile.
 *
 * All of it is plain data and plain functions, so it runs on the JVM under
 * test; the service owns the store and applies what these decide.
 */

/** Which notes the panel's list shows. The chips along the top pick one. */
sealed interface NotesFilter {
    data object All : NotesFilter
    data object Pinned : NotesFilter
    data class Tag(val name: String) : NotesFilter
    data object Archived : NotesFilter
    data object Trash : NotesFilter

    /** The store's view of this filter: which shelf, and which tag. */
    val storeFilter: NoteFilter
        get() = when (this) {
            All, Pinned -> NoteFilter(NoteShelf.ACTIVE)
            is Tag -> NoteFilter(NoteShelf.ACTIVE, tag = name)
            Archived -> NoteFilter(NoteShelf.ARCHIVED)
            Trash -> NoteFilter(NoteShelf.TRASH)
        }
}

/** Which of the editor's fields the keys type into. */
enum class NoteEditField { TITLE, BODY, TAG }

/**
 * A note being written in the keyboard's editor.
 *
 * The keys type into [titleDraft], [bodyDraft] or [tagDraft] through the
 * `NOTE_*` capture targets, and the service autosaves the drafts to the store
 * as they change. [original] is the note as it was when the editor opened:
 * what Cancel puts back, and the baseline a conflict with the settings app is
 * judged against. A note made by New is [isNew]; left blank, Done throws it
 * away rather than keeping an empty note.
 */
data class NoteEdit(
    val id: Long,
    val isNew: Boolean,
    val original: Note,
    val titleDraft: String = original.title,
    val bodyDraft: String = original.body,
    val tags: List<String> = original.tags,
    val focused: NoteEditField = NoteEditField.BODY,
    /** A new tag being typed; only meaningful while [focused] is [NoteEditField.TAG]. */
    val tagDraft: String = "",
    /** The row of suggested and existing tags is unfolded under the note's own. */
    val tagPickerOpen: Boolean = false,
    val startedAt: Long = 0L,
) {
    /** Whether anything has changed since the editor opened. */
    val dirty: Boolean
        get() = titleDraft != original.title || bodyDraft != original.body || tags != original.tags

    val blank: Boolean get() = titleDraft.isBlank() && bodyDraft.isBlank()

    companion object {
        /**
         * The editor's ceiling in the keyboard, in UTF-16 units: the clip
         * editor's. A longer note still opens to read, and its Edit chip hands
         * it to the settings app, where a real text field can hold it.
         */
        const val EDITOR_MAX_LENGTH = 20_000
    }
}

/** What the Undo bar would put back: notes just trashed, or just archived. */
enum class NotesUndoKind { TRASHED, ARCHIVED }

/**
 * The Undo bar after a delete or an archive: the notes it would restore. A
 * second action of the same kind while the bar is up joins it, so one Undo
 * reverses a quick run rather than only the last (the clipboard's rule).
 */
data class NotesUndo(val kind: NotesUndoKind, val ids: List<Long>)

/** A two-tap confirmation in the panel: the first tap arms it, the second does it. */
sealed interface NotesConfirm {
    data class DeleteForever(val id: Long) : NotesConfirm
    data object EmptyTrash : NotesConfirm
}

/**
 * A passage of a note that the search matched (#1099), ready to insert:
 * [text] is the passage as the note has it, and [matchStart] and [matchEnd]
 * are where the match sits inside [text].
 */
data class NotePassageHit(
    val noteId: Long,
    val title: String,
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/** How many notes the Pinned, Archived and Trash chips can say they hold. */
data class NotesCounts(val pinned: Int = 0, val archived: Int = 0, val trash: Int = 0)

/**
 * The Notes panel, as the service publishes it. Survives the panel closing,
 * like the vocabulary card: the filter and the open note are where the user
 * was, and come back on the next open. The editor does not survive — the
 * draft is saved and the keys handed back — nor does the search.
 */
data class NotesPanelUi(
    /** The store is attached: false before the first unlock. */
    val available: Boolean = false,
    /** What the filter and the query show, sorted, pinned first. */
    val items: List<Note> = emptyList(),
    /** The passages inside [items] that hold the query, listed above the notes (#1099). */
    val passages: List<NotePassageHit> = emptyList(),
    /** Every tag, for the chip row and the editor's picker. */
    val tags: List<NoteTag> = emptyList(),
    val counts: NotesCounts = NotesCounts(),
    val filter: NotesFilter = NotesFilter.All,
    val query: String = "",
    val searchActive: Boolean = false,
    /** The note open to read, or null for the list. */
    val open: Long? = null,
    val editing: NoteEdit? = null,
    /** A long-pressed row showing its inline action chips. */
    val held: Long? = null,
    val confirm: NotesConfirm? = null,
    val undo: NotesUndo? = null,
    /** The read view grew to the screen's share (#348's expand). */
    val readExpanded: Boolean = false,
    /**
     * Opened by the Quick note action (#864): the editor carries a row of the
     * latest notes, [recent], to switch to one and add to it. For this visit
     * only; the plain Notes tool opens without it.
     */
    val quick: Boolean = false,
    /** What the quick row offers, newest first; empty unless [quick]. */
    val recent: List<Note> = emptyList(),
) {
    /** The open note, as the list currently has it, or null when it is not in view. */
    fun openNote(): Note? = open?.let { id -> items.firstOrNull { it.id == id } }

    /** Whether the open note's body is past what the keyboard's editor takes. */
    fun openTooLong(): Boolean = (openNote()?.body?.length ?: 0) > NoteEdit.EDITOR_MAX_LENGTH
}

/** The Markdown the editor's helper row inserts around the caret. */
enum class NoteMarkdownAction { BOLD, ITALIC, BULLET, NUMBERED, TASK, HEADING, QUOTE, CODE }

object NotesLogic {

    /**
     * The notes the list shows for [filter] and [query]: the shelf the filter
     * names, [Pinned][NotesFilter.Pinned] narrowed to pinned notes, the query
     * searched over titles, tags and unlocked bodies, locked notes dropped
     * entirely while the device is locked. The trash is shown only when asked
     * for: a search never surfaces what was thrown away.
     */
    fun visible(
        store: NoteStore,
        filter: NotesFilter,
        query: String,
        sort: NoteSort,
        deviceLocked: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<Note> {
        val base = filter.storeFilter.copy(includeLocked = !deviceLocked)
        val rows = if (query.isBlank()) {
            store.items(base, sort, now)
        } else {
            store.search(query, base, sort, searchLockedBodies = false, now = now)
        }
        return if (filter == NotesFilter.Pinned) rows.filter { it.pinned } else rows
    }

    /**
     * The quick row's notes (#864): the live shelf by when each was last
     * touched, newest first, pinned or not. Only notes the keyboard's editor
     * can take: no locked ones, none past [NoteEdit.EDITOR_MAX_LENGTH], and
     * no blank ones, which are the row's own New chip or a draft about to be
     * thrown away.
     */
    fun recent(store: NoteStore, limit: Int = QUICK_ROW_MAX): List<Note> =
        store.items(NoteFilter(NoteShelf.ACTIVE, includeLocked = false))
            .filter { !it.locked && (it.title.isNotBlank() || it.body.isNotBlank()) && it.body.length <= NoteEdit.EDITOR_MAX_LENGTH }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    /** How many notes the quick row lists; older ones are a scroll of the panel's list away. */
    const val QUICK_ROW_MAX = 20

    /**
     * The passages of [items] that hold [query] (#1099), in the list's order,
     * a few per note. A locked note gives none, whatever matched it: its text
     * never leaves the store through the keyboard.
     */
    fun passages(items: List<Note>, query: String, limit: Int = PASSAGES_MAX): List<NotePassageHit> {
        if (query.trim().length < NotePassages.MIN_QUERY_LENGTH) return emptyList()
        val out = ArrayList<NotePassageHit>()
        for (note in items) {
            if (note.locked) continue
            for (passage in NotePassages.find(note.body, query, minOf(NotePassages.PER_NOTE_MAX, limit - out.size))) {
                out.add(
                    NotePassageHit(
                        noteId = note.id,
                        title = note.displayTitle(),
                        text = note.body.substring(passage.start, passage.end),
                        matchStart = passage.matchStart - passage.start,
                        matchEnd = passage.matchEnd - passage.start,
                    ),
                )
            }
            if (out.size >= limit) break
        }
        return out
    }

    /** How many passages a search lists above the notes. */
    const val PASSAGES_MAX = 24

    /** The chip counts for [store], locked notes left out while the device is locked. */
    fun counts(store: NoteStore, deviceLocked: Boolean): NotesCounts {
        val locked = !deviceLocked
        return NotesCounts(
            pinned = store.items(NoteFilter(NoteShelf.ACTIVE, includeLocked = locked)).count { it.pinned },
            archived = store.count(NoteFilter(NoteShelf.ARCHIVED, includeLocked = locked)),
            trash = store.count(NoteFilter(NoteShelf.TRASH, includeLocked = locked)),
        )
    }

    /**
     * Enter in the body. A line break, and the next line's marker when the
     * caret's line is a list item: `- `, `1. ` counted on, or a fresh `- [ ] `
     * box. Enter on an item with nothing after its marker takes the marker
     * off instead, which is how every editor ends a list.
     */
    fun enter(text: CaretText): CaretText {
        val base = text.withoutSelection()
        val caret = base.at
        val lineStart = base.text.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = base.text.indexOf('\n', caret).let { if (it < 0) base.text.length else it }
        val line = base.text.substring(lineStart, lineEnd)
        val marker = LIST_MARKER.find(line)
        if (marker == null || caret < lineStart + marker.groupValues[0].length) return base.typed("\n")
        val whole = marker.groupValues[0]
        val content = line.substring(whole.length)
        if (content.isBlank() && caret == lineEnd) {
            // An empty item: the list ends here, and the marker goes.
            val head = base.text.substring(0, lineStart)
            val tail = base.text.substring(lineEnd)
            return CaretText(head + tail, lineStart).typed("\n")
        }
        val indent = marker.groupValues[1]
        val next = when {
            marker.groupValues[3].isNotEmpty() -> "$indent${marker.groupValues[2]} [ ] "
            marker.groupValues[4].isNotEmpty() -> {
                val n = marker.groupValues[4].toIntOrNull() ?: 0
                "$indent${n + 1}${marker.groupValues[5]} "
            }
            else -> "$indent${marker.groupValues[2]} "
        }
        return base.typed("\n$next")
    }

    /**
     * [action] applied at the caret or over the selection. Inline styles wrap
     * the selection, or insert their markers with the caret between them;
     * applying one to text already wrapped takes the markers off. Line styles
     * toggle a prefix on every line the selection touches.
     */
    fun applyMarkdown(text: CaretText, action: NoteMarkdownAction): CaretText = when (action) {
        NoteMarkdownAction.BOLD -> wrap(text, "**")
        NoteMarkdownAction.ITALIC -> wrap(text, "_")
        NoteMarkdownAction.CODE -> wrap(text, "`")
        NoteMarkdownAction.BULLET -> prefixLines(text) { if (it.startsWith("- ")) it.substring(2) else "- $it" }
        NoteMarkdownAction.TASK -> prefixLines(text) {
            when {
                it.startsWith("- [ ] ") -> it.substring(6)
                it.startsWith("- [x] ") || it.startsWith("- [X] ") -> it.substring(6)
                it.startsWith("- ") -> "- [ ] " + it.substring(2)
                else -> "- [ ] $it"
            }
        }
        NoteMarkdownAction.HEADING -> prefixLines(text) { if (it.startsWith("# ")) it.substring(2) else "# $it" }
        NoteMarkdownAction.QUOTE -> prefixLines(text) { if (it.startsWith("> ")) it.substring(2) else "> $it" }
        NoteMarkdownAction.NUMBERED -> numberLines(text)
    }

    /**
     * How the keyboard's editor meets a note the settings app changed while it
     * was open: the store's version wins while the draft is untouched, and the
     * draft wins once the user has typed, because typed text is the one thing
     * this must never lose. A note that is gone from the store keeps the edit
     * as it is; the flush re-adds it.
     */
    fun resolveConflict(edit: NoteEdit, stored: Note?): NoteEdit {
        if (stored == null) return edit
        if (stored.updatedAt <= edit.original.updatedAt) return edit
        if (edit.dirty) return edit.copy(original = stored.copy(title = edit.original.title, body = edit.original.body, tags = edit.original.tags))
        return edit.copy(original = stored, titleDraft = stored.title, bodyDraft = stored.body, tags = stored.tags)
    }

    /** The UTF-16 offset where line [line] of [body] starts; the end for a line past the last. */
    fun lineStartOffset(body: String, line: Int): Int {
        if (line <= 0) return 0
        var offset = 0
        var remaining = line
        while (remaining > 0) {
            val next = body.indexOf('\n', offset)
            if (next < 0) return body.length
            offset = next + 1
            remaining--
        }
        return offset
    }

    /** The source line of the block a reader tapped, read off the block itself. */
    fun blockLine(block: AiMarkdownBlocks.Block): Int = block.line

    private fun wrap(text: CaretText, marker: String): CaretText {
        if (!text.hasSelection) {
            val typed = text.typed(marker + marker)
            return CaretText(typed.text, typed.at - marker.length)
        }
        val start = text.selectionStart
        val end = text.selectionEnd
        val selected = text.selectedText
        val before = text.text.substring(0, start)
        val after = text.text.substring(end)
        return if (selected.length >= marker.length * 2 && selected.startsWith(marker) && selected.endsWith(marker)) {
            val inner = selected.substring(marker.length, selected.length - marker.length)
            CaretText(before + inner + after, start + inner.length, start)
        } else if (before.endsWith(marker) && after.startsWith(marker)) {
            val head = before.substring(0, before.length - marker.length)
            CaretText(head + selected + after.substring(marker.length), head.length + selected.length, head.length)
        } else {
            val wrapped = marker + selected + marker
            CaretText(before + wrapped + after, start + wrapped.length, start)
        }
    }

    private fun prefixLines(text: CaretText, transform: (String) -> String): CaretText {
        val (start, end) = lineSpan(text)
        val lines = text.text.substring(start, end).split('\n').map(transform)
        val replaced = lines.joinToString("\n")
        val out = text.text.substring(0, start) + replaced + text.text.substring(end)
        return if (text.hasSelection) {
            CaretText(out, start + replaced.length, start)
        } else {
            // One line and a caret: keep it where it was in the text, moved by
            // what the prefix added or took.
            val delta = replaced.length - (end - start)
            CaretText(out, (text.at + delta).coerceIn(start, start + replaced.length))
        }
    }

    private fun numberLines(text: CaretText): CaretText {
        val (start, end) = lineSpan(text)
        val lines = text.text.substring(start, end).split('\n')
        val numbered = lines.all { ORDERED.containsMatchIn(it) }
        val replaced = lines.mapIndexed { i, line ->
            if (numbered) line.replaceFirst(ORDERED, "") else "${i + 1}. $line"
        }.joinToString("\n")
        val out = text.text.substring(0, start) + replaced + text.text.substring(end)
        return if (text.hasSelection) {
            CaretText(out, start + replaced.length, start)
        } else {
            val delta = replaced.length - (end - start)
            CaretText(out, (text.at + delta).coerceIn(start, start + replaced.length))
        }
    }

    /** The span of whole lines the caret or the selection touches. */
    private fun lineSpan(text: CaretText): Pair<Int, Int> {
        val start = text.text.lastIndexOf('\n', text.selectionStart - 1) + 1
        val endProbe = if (text.hasSelection && text.selectionEnd > 0 && text.text[text.selectionEnd - 1] == '\n') {
            text.selectionEnd - 1
        } else {
            text.selectionEnd
        }
        val end = text.text.indexOf('\n', endProbe).let { if (it < 0) text.text.length else it }
        return start to end
    }

    /** Indent, marker, optional task box, optional number, and the number's punctuation. */
    private val LIST_MARKER = Regex("""^( *)(?:([-*+]) (\[[ xX]\] )?|(\d{1,3})([.)]) )""")
    private val ORDERED = Regex("""^ *\d{1,3}[.)] """)
}
