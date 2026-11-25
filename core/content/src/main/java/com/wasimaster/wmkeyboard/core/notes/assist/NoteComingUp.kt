package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import java.util.Locale
import java.util.TimeZone

/** A note on the Coming up list (#1540), with the soonest reminder it holds. */
data class ComingUpEntry(val note: Note, val reminder: NoteReminder)

/**
 * The Coming up list (#1540): every note with a reminder ahead, soonest
 * first, the way [NoteReminders.upcoming] reads a single note.
 *
 * Finding the dates means reading each note's text, up to 20,000 characters
 * of it, with the date patterns of [NoteHighlights], so that half is kept per
 * note and found again only when the note changes ([fill], off the main
 * thread). Turning the dates into reminders is cheap and depends on the
 * clock, so [entries] does it fresh each time it is asked. Locked and
 * trashed notes are never read.
 */
class NoteComingUp {

    private class Found(val updatedAt: Long, val dates: List<NoteHighlight>)

    private val found = HashMap<Long, Found>()

    /** Whether every readable note in [notes] has its dates found for its current text. */
    @Synchronized
    fun ready(notes: List<Note>): Boolean =
        notes.all { !NoteAssistRules.readable(it) || found[it.id]?.updatedAt == it.updatedAt }

    /**
     * Finds the dates of each note in [notes] that has none for its current
     * text. Slow on a first run over many long notes: call it off the main
     * thread. Notes no longer in [notes] are forgotten when [prune] is set,
     * which a caller passing every note it has should do.
     */
    fun fill(
        notes: List<Note>,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        prune: Boolean = false,
    ) {
        val todo = synchronized(this) {
            notes.filter { NoteAssistRules.readable(it) && found[it.id]?.updatedAt != it.updatedAt }
        }
        for (note in todo) {
            val anchor = note.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            val dates = NoteHighlights.find(note.body, anchor, zone, locale).filter { it.kind.dated }
            synchronized(this) { found[note.id] = Found(note.updatedAt, dates) }
        }
        if (prune) synchronized(this) { found.keys.retainAll(notes.mapTo(HashSet()) { it.id }) }
    }

    /**
     * The notes of [notes] with a reminder ahead, by the soonest one each,
     * soonest first; ties keep [notes]' order. A birthday counts only within
     * the same [NoteReminders.HORIZON_DAYS] a deadline does, so the list is
     * about the months ahead and not every birthday of the year. Null while
     * [ready] is false: some note has not been read yet.
     */
    @Synchronized
    fun entries(notes: List<Note>, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): List<ComingUpEntry>? {
        if (!ready(notes)) return null
        val horizon = NoteReminders.dayStart(nowMillis, zone) + NoteReminders.HORIZON_DAYS * NoteReminder.DAY_MS
        return notes.mapNotNull { note ->
            if (!NoteAssistRules.readable(note)) return@mapNotNull null
            val dates = found[note.id]?.dates?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            NoteReminders.upcoming(dates, nowMillis, zone, limit = 1).firstOrNull()
                ?.takeIf { it.startMillis <= horizon }
                ?.let { ComingUpEntry(note, it) }
        }.sortedBy { it.reminder.startMillis }
    }

    /** Synchronous [fill] then [entries], for a caller already off the main thread. */
    fun entriesNow(
        notes: List<Note>,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<ComingUpEntry> {
        fill(notes, zone, locale, prune = true)
        return entries(notes, nowMillis, zone).orEmpty()
    }
}
