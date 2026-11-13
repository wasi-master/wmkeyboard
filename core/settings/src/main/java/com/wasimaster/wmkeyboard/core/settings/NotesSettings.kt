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
     * The panel opens under the toolbar instead of over it (#1282), so the
     * pinned tools stay in reach while a note is open. Off by default: the
     * panel takes the whole keyboard, the toolbar's row included.
     */
    val keepToolbar: Boolean = false,
    /** The on-device assistant over notes and pinned clips (#1293); off until turned on. */
    val assist: NotesAssistSettings = NotesAssistSettings(),
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

/**
 * The notes assistant (#1293): headings, summaries, key details, reminders,
 * related notes and pinned clips, and study decks, all worked out on the
 * phone. [enabled] is the one switch over all of it, off by default and
 * until the user turns it on (asked for on the issue); the rest choose
 * among its parts and do nothing while it is off. The parts that need a
 * model ([headings], [summaries], and the model's half of [decks]) also do
 * nothing until an on-device model is installed; the rest are rules.
 */
data class NotesAssistSettings(
    val enabled: Boolean = false,
    /** Suggest a heading for a note that has none (model). */
    val headings: Boolean = true,
    /** A summary at the top of a note of [summaryWords] words or more (model). */
    val summaries: Boolean = true,
    val summaryWords: Int = DEFAULT_SUMMARY_WORDS,
    /** Deadlines, dates, amounts, numbers and links picked out of a note (rules). */
    val highlights: Boolean = true,
    /** "Remind me" for deadlines and birthdays in notes and pinned clips (rules). */
    val reminders: Boolean = true,
    /** Notes and pinned clips that belong with the open note, wherever they are in the list. */
    val related: Boolean = true,
    /** "Make a study deck" turns a note into Vocabulary flashcards. */
    val decks: Boolean = true,
) {
    /** Whether [part] runs: the master switch and its own. */
    fun on(part: Boolean): Boolean = enabled && part

    companion object {
        const val DEFAULT_SUMMARY_WORDS = 250
        const val MIN_SUMMARY_WORDS = 50
        const val MAX_SUMMARY_WORDS = 5_000
    }
}
