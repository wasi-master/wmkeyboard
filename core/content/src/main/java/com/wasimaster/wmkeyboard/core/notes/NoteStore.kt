package com.wasimaster.wmkeyboard.core.notes

import com.wasimaster.wmkeyboard.core.engine.SearchStems
import com.wasimaster.wmkeyboard.core.text.StemMatch
import com.wasimaster.wmkeyboard.core.clipboard.capClipText
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * The user's notes (#560), persisted as JSON in app-private storage the way
 * [SnippetStore] and the clipboard are, and shared by the keyboard and the
 * settings app the same way: each re-reads the file when it opens its screen
 * ([reloadIfChanged]) and saves after each change.
 *
 * Two processes write one file, so a few rules hold:
 *
 * - Writes are atomic and ordered through [SnapshotFile]: a process killed
 *   mid-write leaves the previous file, not half of the new one, and two saves
 *   on two threads land in the order their snapshots were taken.
 * - Mutators never reload on their own. A reload while the keyboard's editor
 *   holds a draft would throw the draft away, so the contract is "reload on
 *   open, save after each change", and the stamp in [seenStamp] tells a reload
 *   apart from this process's own save.
 * - Ids are time-based ([SnippetStore.freshId]), so notes made on two phones
 *   merge by id when a sync brings them together.
 *
 * The trash is [Note.deletedAt]. Nothing is held outside the list for an
 * Undo; restoring is clearing the stamp, and [purgeTrash] forgets what has
 * sat there longer than [trashRetentionMillis].
 */
class NoteStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val notes: List<Note> = emptyList(),
        val tags: List<NoteTag> = emptyList(),
    )

    private val notes = ArrayList<Note>()
    private val tags = ArrayList<NoteTag>()
    // Unknown keys are ignored so a file written by a newer build still loads;
    // unknown enum values fall back to defaults for the same reason.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private var nextId = 1L
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /**
     * The file's modification time as this process last saw it, from its own
     * save or its last reload, so [reloadIfChanged] does not reload a write
     * this process made.
     */
    private var seenStamp = 0L

    /**
     * How long a trashed note waits before [purgeTrash] forgets it. 0 keeps
     * trashed notes until the user deletes them. Set from the Notes settings
     * after the first emission; until then the default applies.
     */
    @Volatile
    var trashRetentionMillis: Long = DEFAULT_TRASH_RETENTION_MILLIS

    init {
        reload()
    }

    // ---- reading ---------------------------------------------------------

    /**
     * The notes [filter] asks for, in [sort] order with pinned notes first.
     * The trash lists newest-deleted first and ignores pins, since a trashed
     * note's pin is only kept for its restore.
     */
    @Synchronized
    fun items(
        filter: NoteFilter = NoteFilter(),
        sort: NoteSort = NoteSort.UPDATED,
        now: Long = System.currentTimeMillis(),
    ): List<Note> {
        if (filter.shelf == NoteShelf.TRASH) purgeTrash(now)
        return notes.filter { matches(it, filter) }.sortedWith(comparator(filter.shelf, sort))
    }

    @Synchronized
    fun item(id: Long): Note? = notes.firstOrNull { it.id == id }

    @Synchronized
    fun count(filter: NoteFilter = NoteFilter()): Int = notes.count { matches(it, filter) }

    /**
     * Notes matching [query] in their title, tags or body, ignoring case.
     * A locked note's body is never searched unless [searchLockedBodies]: the
     * keyboard masks locked notes, and a filter that matched on their text
     * would leak it one word at a time.
     *
     * After the notes holding [query] as typed come the ones holding another
     * form of its words ("meetings" finds a note about a meeting), stemmed in
     * [languageTag] ([SearchStems]), each group in [sort] order.
     */
    @Synchronized
    fun search(
        query: String,
        filter: NoteFilter = NoteFilter(),
        sort: NoteSort = NoteSort.UPDATED,
        searchLockedBodies: Boolean = false,
        now: Long = System.currentTimeMillis(),
        languageTag: String? = null,
    ): List<Note> {
        val needle = query.trim()
        if (needle.isEmpty()) return items(filter, sort, now)
        return StemMatch.literalFirst(
            items(filter, sort, now),
            literal = { note ->
                note.displayTitle().contains(needle, ignoreCase = true) ||
                    note.tags.any { it.contains(needle, ignoreCase = true) } ||
                    ((!note.locked || searchLockedBodies) && note.body.contains(needle, ignoreCase = true))
            },
            stemmed = SearchStems.matcher(languageTag, needle) { note -> note.searchedText(searchLockedBodies) },
        )
    }

    /** The live note edited most recently, for "add to the last note" and the panel's start. */
    @Synchronized
    fun lastEdited(): Note? = notes.filter { !it.inTrash && !it.archived }.maxByOrNull { it.updatedAt }

    /** Every tag, sorted by name ignoring case. */
    @Synchronized
    fun tags(): List<NoteTag> = tags.sortedBy { it.name.lowercase(Locale.ROOT) }

    @Synchronized
    fun tag(name: String): NoteTag? = tags.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** How many notes on [shelf] carry each tag, keyed by the tag's canonical name. */
    @Synchronized
    fun tagCounts(shelf: NoteShelf = NoteShelf.ACTIVE): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (tag in tags) counts[tag.name] = 0
        for (note in notes) {
            if (!matches(note, NoteFilter(shelf))) continue
            for (name in note.tags) counts[name] = (counts[name] ?: 0) + 1
        }
        return counts
    }

    // ---- writing ---------------------------------------------------------

    @Synchronized
    fun add(
        body: String,
        title: String = "",
        tags: List<String> = emptyList(),
        locked: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Note = add(Note(id = 0, body = body, title = title, tags = tags, locked = locked), now)

    /**
     * Adds [note] under a fresh id, keeping every other field it carries.
     *
     * Imports go through here. Rebuilding a note out of a handful of named
     * values would quietly drop whichever field was added last.
     */
    @Synchronized
    fun add(note: Note, now: Long = System.currentTimeMillis()): Note {
        val id = SnippetStore.freshId(nextId, now)
        nextId = id + 1
        val created = if (note.createdAt > 0) note.createdAt else now
        val updated = if (note.updatedAt > 0) note.updatedAt else created
        val added = normalize(note, id = id, createdAt = created, updatedAt = updated, deletedAt = note.deletedAt)
        notes.add(added)
        return added
    }

    /** Adds every note, recreating [tags] with their colours first. Returns the notes as stored. */
    @Synchronized
    fun addAll(notes: List<Note>, tags: List<NoteTag> = emptyList(), now: Long = System.currentTimeMillis()): List<Note> {
        for (tag in tags) addTag(tag.name, tag.colour, now)
        return notes.map { add(it, now) }
    }

    /**
     * Rewrites the stored note with [note]'s id from every field [note] carries,
     * or returns null when no such note exists (deleted for good under the
     * editor; the caller re-adds the text rather than losing it).
     *
     * The stored id, creation time and trash stamp win. [Note.updatedAt]
     * moves only when the title, body or tags changed, so a pin or an archive
     * does not lift a note to the top of a list sorted by edit time.
     */
    @Synchronized
    fun update(note: Note, now: Long = System.currentTimeMillis()): Note? {
        val index = notes.indexOfFirst { it.id == note.id }
        if (index < 0) return null
        val stored = notes[index]
        val contentChanged = note.title.trim() != stored.title || note.body != stored.body ||
            normalizeTags(note.tags) != stored.tags
        val next = normalize(
            note,
            id = stored.id,
            createdAt = stored.createdAt,
            updatedAt = if (contentChanged) now else stored.updatedAt,
            deletedAt = stored.deletedAt,
        )
        notes[index] = next
        return next
    }

    /** [text] added to the end of the note, a blank line apart. The "add to the last note" path. */
    @Synchronized
    fun append(id: Long, text: String, now: Long = System.currentTimeMillis()): Note? {
        val stored = item(id) ?: return null
        val body = if (stored.body.isBlank()) text else stored.body.trimEnd() + "\n\n" + text
        return update(stored.copy(body = body), now)
    }

    @Synchronized
    fun setPinned(id: Long, pinned: Boolean): Note? = change(id) { it.copy(pinned = pinned) }

    @Synchronized
    fun setArchived(id: Long, archived: Boolean): Note? = change(id) { it.copy(archived = archived) }

    @Synchronized
    fun setLocked(id: Long, locked: Boolean): Note? = change(id) { it.copy(locked = locked) }

    @Synchronized
    fun setTags(id: Long, tags: List<String>, now: Long = System.currentTimeMillis()): Note? {
        val stored = item(id) ?: return null
        return update(stored.copy(tags = tags), now)
    }

    /**
     * Ticks or clears the task on [lineIndex] of the note's body. Null when the
     * note is gone or that line is no longer a task; see [NoteChecklist.setTask].
     */
    @Synchronized
    fun setTask(id: Long, lineIndex: Int, checked: Boolean, now: Long = System.currentTimeMillis()): Note? {
        val stored = item(id) ?: return null
        val body = NoteChecklist.setTask(stored.body, lineIndex, checked) ?: return null
        return update(stored.copy(body = body), now)
    }

    /** Moves the note to the trash, every flag kept so a restore puts it back as it was. */
    @Synchronized
    fun trash(id: Long, now: Long = System.currentTimeMillis()): Note? =
        change(id) { if (it.inTrash) it else it.copy(deletedAt = now) }

    @Synchronized
    fun restore(id: Long): Note? = change(id) { it.copy(deletedAt = null) }

    @Synchronized
    fun deleteForever(id: Long): Boolean = notes.removeAll { it.id == id }

    /** Deletes every trashed note for good; returns how many went. */
    @Synchronized
    fun emptyTrash(): Int {
        val before = notes.size
        notes.removeAll { it.inTrash }
        return before - notes.size
    }

    /**
     * Forgets trashed notes older than [trashRetentionMillis]. Run on every
     * load and whenever the trash is listed, so both processes agree on what
     * is left without either having to schedule anything. A purge only in
     * memory reaches the file at the next save, which is harmless: a purged
     * note is hidden either way.
     */
    @Synchronized
    fun purgeTrash(now: Long = System.currentTimeMillis()): Int {
        val retention = trashRetentionMillis
        if (retention <= 0) return 0
        val before = notes.size
        notes.removeAll { note -> note.deletedAt?.let { it + retention < now } == true }
        return before - notes.size
    }

    // ---- tags ------------------------------------------------------------

    /**
     * Adds a tag and returns it, or returns the existing tag whose name matches
     * ignoring case; null for a blank name or once [MAX_TAGS] exist.
     */
    @Synchronized
    fun addTag(name: String, colour: Int = 0, now: Long = System.currentTimeMillis()): NoteTag? {
        val clean = cleanTagName(name) ?: return null
        tag(clean)?.let { return it }
        if (tags.size >= MAX_TAGS) return null
        val added = NoteTag(clean, NoteTagPalette.clamp(colour), now)
        tags.add(added)
        return added
    }

    /**
     * Renames a tag, on every note too. A name that differs only in case is a
     * recase; one another tag already has is a merge, and the target keeps its
     * colour. False when [from] names no tag or [to] is blank.
     */
    @Synchronized
    fun renameTag(from: String, to: String): Boolean {
        val source = tag(from) ?: return false
        val clean = cleanTagName(to) ?: return false
        val target = tag(clean)
        val finalName = when {
            target == null || target.name.equals(source.name, ignoreCase = true) -> clean
            else -> target.name
        }
        if (target != null && !target.name.equals(source.name, ignoreCase = true)) {
            tags.removeAll { it.name.equals(source.name, ignoreCase = true) }
        } else {
            val index = tags.indexOfFirst { it.name.equals(source.name, ignoreCase = true) }
            tags[index] = tags[index].copy(name = finalName)
        }
        for (i in notes.indices) {
            val note = notes[i]
            if (!note.hasTag(source.name)) continue
            val renamed = note.tags.map { if (it.equals(source.name, ignoreCase = true)) finalName else it }
            notes[i] = note.copy(tags = dedupeTags(renamed))
        }
        return true
    }

    /** Drops the tag from every note, the trash included, and from the list. Returns notes touched. */
    @Synchronized
    fun removeTag(name: String): Int {
        if (!tags.removeAll { it.name.equals(name, ignoreCase = true) }) return 0
        var touched = 0
        for (i in notes.indices) {
            val note = notes[i]
            if (!note.hasTag(name)) continue
            notes[i] = note.copy(tags = note.tags.filterNot { it.equals(name, ignoreCase = true) })
            touched++
        }
        return touched
    }

    @Synchronized
    fun setTagColour(name: String, colour: Int) {
        val index = tags.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (index >= 0) tags[index] = tags[index].copy(colour = NoteTagPalette.clamp(colour))
    }

    // ---- persistence -----------------------------------------------------

    /**
     * Writes the file. The snapshot and its ticket are taken under the lock;
     * the encoding and the write run outside it, so the caller can run this
     * on any thread and saves on two threads land in snapshot order.
     */
    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) { file.ticket() to Snapshot(1, notes.toList(), tags.toList()) }
        if (file.write(ticket) { json.encodeToString(snapshot) }) {
            synchronized(this) { seenStamp = file.file.lastModified() }
        }
    }

    /** Re-reads the backing file, dropping whatever is in memory. */
    @Synchronized
    fun reload() {
        // A save drawn before this describes the list being thrown away; it
        // must not land over whatever the other process left in the file.
        snapshotFile?.supersede()
        notes.clear()
        tags.clear()
        val file = storageFile
        if (file != null && file.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                tags.addAll(snapshot.tags.map { it.copy(colour = NoteTagPalette.clamp(it.colour)) })
                // Tags first, so a note's names take the list's casing.
                notes.addAll(snapshot.notes.map { it.copy(tags = canonicalTags(it.tags)) })
            }
            seenStamp = file.lastModified()
        } else {
            seenStamp = 0L
        }
        nextId = (notes.maxOfOrNull { it.id } ?: 0L) + 1
        purgeTrash()
    }

    /** The file's modification time, or 0 with no file. */
    fun lastModified(): Long = storageFile?.lastModified() ?: 0L

    /**
     * Re-reads the file when another process has written it since this one
     * last read or wrote it. True when a reload happened. Two writes inside
     * one modification-time tick cannot be told apart, the same limit the
     * snippets have.
     */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val stamp = lastModified()
        if (stamp == seenStamp) return false
        reload()
        return true
    }

    // ---- helpers ---------------------------------------------------------

    private fun matches(note: Note, filter: NoteFilter): Boolean {
        val shelfOk = when (filter.shelf) {
            NoteShelf.ACTIVE -> !note.inTrash && !note.archived
            NoteShelf.ARCHIVED -> !note.inTrash && note.archived
            NoteShelf.TRASH -> note.inTrash
        }
        if (!shelfOk) return false
        if (!filter.includeLocked && note.locked) return false
        val tag = filter.tag ?: return true
        return note.hasTag(tag)
    }

    private fun comparator(shelf: NoteShelf, sort: NoteSort): Comparator<Note> {
        if (shelf == NoteShelf.TRASH) return compareByDescending { it.deletedAt ?: 0L }
        val pinnedFirst = compareByDescending<Note> { it.pinned }
        return when (sort) {
            NoteSort.UPDATED -> pinnedFirst.thenByDescending { it.updatedAt }
            NoteSort.CREATED -> pinnedFirst.thenByDescending { it.createdAt }
            NoteSort.TITLE -> pinnedFirst
                .thenBy { it.displayTitle().lowercase(Locale.ROOT) }
                .thenByDescending { it.updatedAt }
        }
    }

    private fun change(id: Long, transform: (Note) -> Note): Note? {
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return null
        val next = transform(notes[index])
        notes[index] = next
        return next
    }

    /**
     * [note] as it is stored: trimmed, capped, and with its tags cleaned.
     * Shared by [add] and [update] so a field is never normalised on one path
     * and stored raw on the other.
     */
    private fun normalize(note: Note, id: Long, createdAt: Long, updatedAt: Long, deletedAt: Long?): Note = note.copy(
        id = id,
        title = capClipText(note.title.replace(NEWLINES, " ").trim(), MAX_TITLE_LENGTH),
        body = capClipText(note.body.removePrefix(BOM).replace(CRLF, "\n").replace('\r', '\n'), MAX_BODY_CHARS),
        tags = normalizeTags(note.tags),
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    /**
     * Tags as a note stores them: cleaned, each once ignoring case, capped, and
     * in the casing the tag list holds. A name the list does not have yet is
     * added to it, which is how a suggested chip becomes a real tag.
     */
    private fun normalizeTags(raw: List<String>): List<String> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<String>(raw.size)
        val seen = HashSet<String>()
        for (name in raw) {
            val clean = cleanTagName(name) ?: continue
            if (!seen.add(clean.lowercase(Locale.ROOT))) continue
            val canonical = addTag(clean)?.name ?: continue
            out.add(canonical)
            if (out.size >= MAX_TAGS_PER_NOTE) break
        }
        return out
    }

    /** [names] in the tag list's casing, unknown names kept as written, repeats dropped. */
    private fun canonicalTags(names: List<String>): List<String> =
        dedupeTags(names.map { name -> tag(name)?.name ?: name })

    private fun dedupeTags(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.filter { seen.add(it.lowercase(Locale.ROOT)) }
    }

    /** A tag name as it is stored, or null when nothing is left of it. */
    private fun cleanTagName(name: String): String? =
        name.trim().trimStart('#').replace(",", "").replace(NEWLINES, " ").trim()
            .take(MAX_TAG_LENGTH).trim().takeIf { it.isNotEmpty() }

    companion object {
        const val FILE_PATH = "notes/notes.json"

        /** Most characters one note keeps; longer text is cut on the way in. */
        const val MAX_BODY_CHARS = 100_000
        const val MAX_TITLE_LENGTH = 200
        const val MAX_TAGS_PER_NOTE = 16
        const val MAX_TAG_LENGTH = 40

        /** Most tags the list holds; past it a new name is dropped from the note rather than created. */
        const val MAX_TAGS = 200

        const val DEFAULT_TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        private val NEWLINES = Regex("""[\r\n]+""")
        private val CRLF = Regex("""\r\n""")
        private const val BOM = "﻿"
    }
}

/**
 * What a search looks for another form of its words in: the title, the tags,
 * and the body unless the note is locked and [lockedBodies] is off.
 */
fun Note.searchedText(lockedBodies: Boolean): String = buildString {
    append(displayTitle())
    for (tag in tags) append(' ').append(tag)
    if (!locked || lockedBodies) append(' ').append(body)
}
