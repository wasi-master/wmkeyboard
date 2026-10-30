package com.wasimaster.wmkeyboard.core.todo

import com.wasimaster.wmkeyboard.core.clipboard.capClipText
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.io.File

/**
 * The user's to-do list (#920), persisted as JSON in app-private storage and
 * shared by the keyboard and the settings app the way [NoteStore] is, under
 * the same rules: atomic, ordered writes through [SnapshotFile]; mutators
 * never reload on their own, so the contract is "reload on open, save after
 * each change"; and time-based ids, so tasks made on two phones merge by id
 * when a sync brings them together.
 *
 * Subtasks are one level deep. Deleting a task deletes its steps with it, and
 * ticking a task ticks the steps still open under it.
 *
 * [pendingDeletes] remembers the [TodoTask.uid] of every deleted task that a
 * task app also holds, so the next task-app sync can delete it there too.
 * Without it the sync would read the task's absence here as "never had it"
 * and copy it straight back.
 */
class TodoStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val tasks: List<TodoTask> = emptyList(),
        val pendingDeletes: List<String> = emptyList(),
    )

    private val tasks = ArrayList<TodoTask>()
    private val pendingDeletes = LinkedHashSet<String>()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private var nextId = 1L
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** The file's modification time as this process last saw it; see [NoteStore]. */
    private var seenStamp = 0L

    init {
        reload()
    }

    // ---- reading ---------------------------------------------------------

    /** Every task, top-level and steps alike, in no promised order. */
    @Synchronized
    fun all(): List<TodoTask> = tasks.toList()

    @Synchronized
    fun item(id: Long): TodoTask? = tasks.firstOrNull { it.id == id }

    /**
     * The top-level tasks in list order: open before done, then the soonest
     * due first with undated ones after, then the newest first. A finished
     * task sinks to the bottom, the one finished last at the top of those.
     * [hideDone] leaves the finished ones out.
     */
    @Synchronized
    fun topLevel(hideDone: Boolean = false): List<TodoTask> =
        tasks.filter { it.parentId == null && (!hideDone || !it.done) }.sortedWith(LIST_ORDER)

    /** The steps under [parentId], in the order they were added, ticked or not. */
    @Synchronized
    fun subtasks(parentId: Long): List<TodoTask> =
        tasks.filter { it.parentId == parentId }.sortedWith(compareBy<TodoTask> { it.createdAt }.thenBy { it.id })

    @Synchronized
    fun progress(parentId: Long): TodoProgress {
        val steps = tasks.filter { it.parentId == parentId }
        return TodoProgress(steps.count { it.done }, steps.size)
    }

    /** Top-level tasks still open, for the panel's "3 left" and the tool page. */
    @Synchronized
    fun openCount(): Int = tasks.count { it.parentId == null && !it.done }

    @Synchronized
    fun size(): Int = tasks.size

    @Synchronized
    fun pendingDeletes(): Set<String> = pendingDeletes.toSet()

    // ---- writing ---------------------------------------------------------

    /**
     * Adds a task with [text], or a step under [parentId]. A step asked for
     * under a step goes under that step's parent. Null for blank text, for a
     * parent that is gone, or once the list holds [MAX_TASKS].
     */
    @Synchronized
    fun add(
        text: String,
        parentId: Long? = null,
        due: Long? = null,
        dueAllDay: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): TodoTask? {
        val clean = cleanText(text) ?: return null
        val parent = parentId?.let { id -> item(id) ?: return null }
        val top = parent?.let { it.parentId ?: it.id }
        return add(TodoTask(id = 0, text = clean, parentId = top, due = due, dueAllDay = due != null && dueAllDay), now)
    }

    /**
     * Adds [task] under a fresh id, keeping every other field it carries, so
     * an import or a task-app pull loses nothing. Its [TodoTask.parentId]
     * must already name a top-level task here, or it lands top-level.
     */
    @Synchronized
    fun add(task: TodoTask, now: Long = System.currentTimeMillis()): TodoTask? {
        if (tasks.size >= MAX_TASKS) return null
        val clean = cleanText(task.text) ?: return null
        val id = SnippetStore.freshId(nextId, now)
        nextId = id + 1
        val parent = task.parentId?.let { pid -> tasks.firstOrNull { it.id == pid && it.parentId == null } }
        val created = if (task.createdAt > 0) task.createdAt else now
        val added = task.copy(
            id = id,
            text = clean,
            parentId = parent?.id,
            createdAt = created,
            updatedAt = if (task.updatedAt > 0) task.updatedAt else created,
            completedAt = if (task.done) task.completedAt ?: now else null,
        )
        tasks.add(added)
        return added
    }

    /** New text for a task; null when it is gone or the text is blank. */
    @Synchronized
    fun setText(id: Long, text: String, now: Long = System.currentTimeMillis()): TodoTask? {
        val clean = cleanText(text) ?: return null
        return change(id, now) { if (it.text == clean) it else it.copy(text = clean) }
    }

    /**
     * Ticks or clears a task. Ticking a task ticks every open step under it,
     * since a finished task has no steps left to do; clearing a step clears
     * its parent, since the task is not finished after all. Returns the tasks
     * that changed, the asked-for one first.
     */
    @Synchronized
    fun setDone(id: Long, done: Boolean, now: Long = System.currentTimeMillis()): List<TodoTask> {
        val task = item(id) ?: return emptyList()
        val changed = ArrayList<TodoTask>()
        // A repeating task is never finished: this time is, and it comes round
        // again with its steps open.
        val repeat = task.repeat
        val due = task.due
        if (done && repeat != null && due != null) {
            val zone = ZoneId.of(if (task.dueAllDay) "UTC" else ZoneId.systemDefault().id)
            change(id, now) { it.copy(due = TodoDue.nextAfter(TodoDue.step(due, repeat, zone), repeat, now, zone), snoozedUntil = null) }
                ?.let(changed::add)
            for (step in tasks.filter { it.parentId == id && it.done }) tick(step, false, now)?.let(changed::add)
            return changed
        }
        tick(task, done, now)?.let(changed::add)
        if (done && task.parentId == null) {
            for (step in tasks.filter { it.parentId == task.id && !it.done }) tick(step, true, now)?.let(changed::add)
        }
        if (!done && task.parentId != null) {
            item(task.parentId)?.takeIf { it.done }?.let { parent -> tick(parent, false, now)?.let(changed::add) }
        }
        return changed
    }

    @Synchronized
    fun setDue(id: Long, due: Long?, allDay: Boolean = false, now: Long = System.currentTimeMillis()): TodoTask? =
        change(id, now) {
            it.copy(
                due = due,
                dueAllDay = due != null && allDay,
                snoozedUntil = null,
                repeat = it.repeat.takeIf { due != null },
            )
        }

    /** Sets how [id] repeats; one with no date yet is first due one repeat from now. */
    @Synchronized
    fun setRepeat(id: Long, repeat: TodoRepeat?, now: Long = System.currentTimeMillis()): TodoTask? =
        change(id, now) { task ->
            val due = task.due ?: repeat?.let { TodoDue.step(now, it, ZoneId.systemDefault()) }
            task.copy(repeat = repeat, due = due)
        }

    /** Holds [id]'s reminders off until [until]. */
    @Synchronized
    fun snooze(id: Long, until: Long, now: Long = System.currentTimeMillis()): TodoTask? =
        change(id, now) { it.copy(snoozedUntil = until) }

    /**
     * Deletes a task and its steps. Returns what went, the task first, for an
     * Undo to hand to [restore]; empty when there was no such task.
     */
    @Synchronized
    fun delete(id: Long): List<TodoTask> {
        val task = item(id) ?: return emptyList()
        val gone = listOf(task) + tasks.filter { it.parentId == task.id }
        remove(gone)
        return gone
    }

    /** Deletes every finished task (with its steps) and every finished step. Returns what went. */
    @Synchronized
    fun clearDone(): List<TodoTask> {
        val doneTops = tasks.filter { it.parentId == null && it.done }.map { it.id }.toSet()
        val gone = tasks.filter { it.done || it.parentId in doneTops }
        remove(gone)
        return gone
    }

    /** Deletes everything, the remembered task-app deletes included. */
    @Synchronized
    fun clearAll() {
        tasks.clear()
        pendingDeletes.clear()
    }

    /**
     * Puts back tasks [delete] or [clearDone] took, ids and all. A task whose
     * id came back meanwhile is skipped, and a step whose parent is not here
     * lands top-level rather than nowhere.
     */
    @Synchronized
    fun restore(removed: List<TodoTask>) {
        val ids = tasks.mapTo(HashSet()) { it.id }
        val back = removed.filter { ids.add(it.id) }
        for (task in back) {
            val parentOk = task.parentId == null || task.parentId in ids
            tasks.add(if (parentOk) task else task.copy(parentId = null))
            task.uid?.let(pendingDeletes::remove)
        }
        nextId = maxOf(nextId, (tasks.maxOfOrNull { it.id } ?: 0L) + 1)
    }

    /**
     * Swaps the whole list for [next] and the remembered deletes for
     * [deletes]: the task-app sync's way in, after it has merged both sides.
     */
    @Synchronized
    fun replaceAll(next: List<TodoTask>, deletes: Set<String>) {
        tasks.clear()
        tasks.addAll(next)
        pendingDeletes.clear()
        pendingDeletes.addAll(deletes)
        nextId = maxOf(nextId, (tasks.maxOfOrNull { it.id } ?: 0L) + 1)
    }

    /** A fresh id the sync can hand a task pulled from a task app. */
    @Synchronized
    fun freshId(now: Long = System.currentTimeMillis()): Long {
        val id = SnippetStore.freshId(nextId, now)
        nextId = id + 1
        return id
    }

    // ---- persistence -----------------------------------------------------

    /** Writes the file; safe from any thread, see [NoteStore.save]. */
    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            file.ticket() to Snapshot(1, tasks.toList(), pendingDeletes.toList())
        }
        if (file.write(ticket) { json.encodeToString(snapshot) }) {
            synchronized(this) { seenStamp = file.file.lastModified() }
        }
    }

    /** Re-reads the backing file, dropping whatever is in memory. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        tasks.clear()
        pendingDeletes.clear()
        val file = storageFile
        if (file != null && file.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                tasks.addAll(dropOrphans(snapshot.tasks))
                pendingDeletes.addAll(snapshot.pendingDeletes)
            }
            seenStamp = file.lastModified()
        } else {
            seenStamp = 0L
        }
        nextId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1
    }

    fun lastModified(): Long = storageFile?.lastModified() ?: 0L

    /** Re-reads the file when another process wrote it since this one last did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        if (lastModified() == seenStamp) return false
        reload()
        return true
    }

    // ---- helpers ---------------------------------------------------------

    private fun tick(task: TodoTask, done: Boolean, now: Long): TodoTask? {
        if (task.done == done) return null
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return null
        val next = task.copy(done = done, completedAt = if (done) now else null, updatedAt = now)
        tasks[index] = next
        return next
    }

    private fun change(id: Long, now: Long, transform: (TodoTask) -> TodoTask): TodoTask? {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val stored = tasks[index]
        val next = transform(stored)
        if (next == stored) return stored
        val stamped = next.copy(updatedAt = now)
        tasks[index] = stamped
        return stamped
    }

    private fun remove(gone: List<TodoTask>) {
        val ids = gone.mapTo(HashSet()) { it.id }
        tasks.removeAll { it.id in ids }
        for (task in gone) task.uid?.let(pendingDeletes::add)
    }

    /**
     * A file merged by sync from two phones can hold a step whose parent one
     * of them deleted, or a step under a step. Both become top-level tasks
     * rather than vanishing.
     */
    private fun dropOrphans(list: List<TodoTask>): List<TodoTask> {
        val tops = list.filter { it.parentId == null }.mapTo(HashSet()) { it.id }
        return list.map { if (it.parentId != null && it.parentId !in tops) it.copy(parentId = null) else it }
    }

    private fun cleanText(text: String): String? =
        capClipText(text.replace(NEWLINES, " ").trim(), MAX_TEXT_LENGTH).trim().takeIf { it.isNotEmpty() }

    companion object {
        const val FILE_PATH = "todo/tasks.json"

        /** Longest a task's text gets; a task is a line, not a note. */
        const val MAX_TEXT_LENGTH = 500

        /** Most tasks the list holds, steps included. */
        const val MAX_TASKS = 2000

        private val NEWLINES = Regex("""[\r\n]+""")

        /** See [topLevel]. */
        private val LIST_ORDER: Comparator<TodoTask> = compareBy<TodoTask> { it.done }
            .thenComparator { a, b ->
                when {
                    a.done -> compareValues(b.completedAt ?: 0L, a.completedAt ?: 0L)
                    a.due != null && b.due != null -> compareValues(a.due, b.due)
                    a.due != null -> -1
                    b.due != null -> 1
                    else -> 0
                }
            }
            .thenByDescending { it.createdAt }
            .thenByDescending { it.id }
    }
}
