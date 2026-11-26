package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.notes.NoteSort

/** What the keyboard's Notes panel shows when it opens. */
enum class NotesPanelStart {
    /** The list, filtered as it was left. */
    LIST,

    /** The note edited most recently, open to read. */
    LAST_NOTE,
}

/**
 * Which editor a note opens in (#1586), in the app and in the keyboard alike.
 * Both edit the same stored Markdown, so either can open any note, and the
 * editor's menu switches one note over for that visit.
 */
enum class NoteEditorKind {
    /** What the note looks like as it is written: bold is bold, a list has bullets, pictures show. */
    RICH,

    /** The Markdown as text, with its helper chips and a preview. */
    MARKDOWN,
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
    /**
     * A tap on a note in the list types it into the field (#1333), as its
     * Insert chip does; a hold shows the chips, and a tap on a held note
     * opens it. Off by default: a tap opens the note.
     */
    val tapInserts: Boolean = false,
    /**
     * The editor notes open in (#1586). Rich text by default: what the owner
     * asked for on the issue, and nothing about the stored text changes, so
     * Markdown stays a setting away.
     */
    val editor: NoteEditorKind = NoteEditorKind.RICH,
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
    /**
     * "Remind me" for deadlines, birthdays and timed plans in notes and pinned
     * clips, and the notes list's Coming up chip (#1540) (rules).
     */
    val reminders: Boolean = true,
    /** Notes and pinned clips that belong with the open note, wherever they are in the list. */
    val related: Boolean = true,
    /** "Make a study deck" turns a note into Vocabulary flashcards. */
    val decks: Boolean = true,
    /**
     * "Organise" has the model rewrite a note under headings and lists, shown
     * first and kept only when the user says so (#1453). On-demand summaries
     * of notes under [summaryWords] ride on [summaries].
     */
    val organise: Boolean = true,
    /**
     * Tags offered for a note (#1540): the user's tags it names, the tags of
     * notes like it, and its own telling words. Offered as chips, never put
     * on by themselves (rules).
     */
    val tags: Boolean = true,
) {
    /** Whether [part] runs: the master switch and its own. */
    fun on(part: Boolean): Boolean = enabled && part

    companion object {
        const val DEFAULT_SUMMARY_WORDS = 250
        const val MIN_SUMMARY_WORDS = 50
        const val MAX_SUMMARY_WORDS = 5_000
    }
}
