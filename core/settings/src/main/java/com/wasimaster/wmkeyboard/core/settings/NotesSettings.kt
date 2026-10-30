package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.notes.NoteSort

/** What the keyboard's Notes panel shows when it opens. */
enum class NotesPanelStart {
    /** The list, filtered as it was left. */
    LIST,

    /** The note edited most recently, open to read. */
    LAST_NOTE,
}

/** How the keyboard's Notes panel lays its list out. */
enum class NotesPanelLayout {
    /** Pinned notes on a shelf of wide cards, the rest in a two-column grid. */
    SHELF,

    /** One compact row per note down a rail of days. */
    TIMELINE,
}

/**
 * The Notes tool's settings (#560), grouped for the reason [TrackpadSettings]
 * gives: [KeyboardSettings] sits under the JVM's `copy$default` ceiling and
 * these all belong to one feature. Each field persists under its own DataStore
 * key through the matching setter; the enum comes from `core.notes` because
 * the store sorts by it and this module depends on that one.
 */
data class NotesSettings(
    val sort: NoteSort = NoteSort.UPDATED,
    /** Lines of the body shown under a title in the keyboard's list; 0 is the title alone. */
    val previewLines: Int = 2,
    /**
     * The reading view draws headings, lists, quotes, code and task boxes.
     * Off, every note is shown as the text it was typed as.
     */
    val renderMarkdown: Boolean = true,
    val panelOpensOn: NotesPanelStart = NotesPanelStart.LIST,
    /** The list's shape in the keyboard: the shelf of cards, or the timeline. */
    val panelLayout: NotesPanelLayout = NotesPanelLayout.SHELF,
    /**
     * What the selection bar's Save as note does: a new note each time, or
     * the text added to the end of the note edited most recently.
     */
    val saveSelectionAppends: Boolean = false,
    /** Days a trashed note waits before it is forgotten; 0 keeps it until deleted by hand. */
    val trashDays: Int = 30,
    /**
     * Bumped by a restore or a sync that rewrote `notes.json`, so the running
     * keyboard reloads instead of saving its memory over the file. Not a
     * preference anyone sets; the twin of `statsVersion`.
     */
    val version: Int = 0,
) {
    companion object {
        const val MIN_PREVIEW_LINES = 0
        const val MAX_PREVIEW_LINES = 5
        const val MAX_TRASH_DAYS = 365

        /** The retention choices the settings screen offers, in days; 0 is "until I delete them". */
        val TRASH_DAY_CHOICES: List<Int> = listOf(7, 30, 90, 0)
    }
}
