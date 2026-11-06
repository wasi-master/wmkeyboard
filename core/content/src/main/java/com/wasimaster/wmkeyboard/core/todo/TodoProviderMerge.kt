package com.wasimaster.wmkeyboard.core.todo

import kotlinx.serialization.Serializable

/**
 * A task as a task app's provider holds it: one row of the OpenTasks
 * contract's `tasks` table, read by [TodoProviderSync].
 *
 * [version] is the provider's own change counter for the row, which moves on
 * every edit whoever made it; it is how a sync tells "changed in the task app
 * since last time" without trusting two clocks. [modified] is only the
 * tie-break when both sides changed.
 */
data class RemoteTask(
    val rowId: Long,
    val uid: String,
    val title: String,
    val done: Boolean,
    val completedAt: Long? = null,
    val due: Long? = null,
    val dueAllDay: Boolean = false,
    val parentRowId: Long? = null,
    val version: Long = 0,
    val modified: Long = 0,
)

/**
 * What the last sync with a task app saw, kept on this device only (it names
 * rows of this device's provider). [seen] maps each UID to the row's
 * [RemoteTask.version] then; [pushed] maps it to the task's
 * [TodoTask.updatedAt] then. A UID in [seen] that is gone from the provider
 * was deleted there; one that was never seen has not reached this phone's
 * task app yet and is left alone.
 */
@Serializable
data class TodoSyncState(
    val authority: String = "",
    val listId: Long = -1,
    val seen: Map<String, Long> = emptyMap(),
    val pushed: Map<String, Long> = emptyMap(),
    val lastSyncAt: Long = 0,
) {
    /** Whether this state belongs to the list being synced now; a new list starts over. */
    fun isFor(authority: String, listId: Long): Boolean = this.authority == authority && this.listId == listId
}

/**
 * The outcome of one merge: the list as it should now be here, and what to
 * write to the task app. Applied by [TodoProviderSync].
 */
data class TodoSyncPlan(
    /** The new list. Tasks still to insert have no UID yet. */
    val local: List<TodoTask>,
    /** Local ids to insert into the task app, every top-level task before any step. */
    val inserts: List<Long>,
    /** Rows to rewrite from a local task: row id to local id. */
    val updates: Map<Long, Long>,
    /** Rows to delete, for tasks deleted here. */
    val deletes: List<Long>,
    /** Tasks pulled from or changed by the task app, and tasks the task app deleted. */
    val pulled: Int = 0,
    val removed: Int = 0,
)

/**
 * Two-way merge between the keyboard's list and one task-app list, as pure
 * data so it runs under test.
 *
 * - A row the list does not have comes in as a new task. This list never
 *   reads the absence of a task as a delete; only [TodoStore.pendingDeletes]
 *   carries a delete over, so "Delete all" here, or a fresh install, cannot
 *   empty the user's CalDAV list.
 * - A task on both sides follows whichever side changed since the last sync;
 *   when both did, the later edit wins and a tie keeps this side's.
 * - A linked task whose row is gone was deleted in the task app, and goes
 *   here too, with its steps.
 * - A task with no UID is new here and is inserted there, unless a row the
 *   list does not know has the same words, tick and level: then it is that
 *   row, put there by an earlier sync that could not read its UID back.
 */
object TodoProviderMerge {

    fun plan(
        local: List<TodoTask>,
        pendingDeletes: Set<String>,
        remote: List<RemoteTask>,
        state: TodoSyncState,
        freshId: () -> Long,
        now: Long = System.currentTimeMillis(),
    ): TodoSyncPlan {
        val deletedRows = remote.filter { it.uid in pendingDeletes }
        val rows = remote.filter { it.uid !in pendingDeletes }
        val rowByUid = rows.associateBy { it.uid }
        val uidByRow = rows.associate { it.rowId to it.uid }

        // Remote deletes first: a linked task whose row went, and its steps.
        val goneUids = local.mapNotNull { it.uid }.filter { it !in rowByUid && it in state.seen }.toSet()
        val goneIds = local.filter { it.uid in goneUids }.mapTo(HashSet()) { it.id }
        val kept = local.filter { it.id !in goneIds && it.parentId !in goneIds }
        val removed = local.size - kept.size

        val byUid = kept.filter { it.uid != null }.associateBy { it.uid!! }.toMutableMap()
        val out = LinkedHashMap<Long, TodoTask>()
        for (task in kept) out[task.id] = task
        val updates = LinkedHashMap<Long, Long>()
        // Rows whose fields (and parent) this side takes on.
        val pulledRows = ArrayList<RemoteTask>()

        // Tasks here that no row answers to yet: never inserted, or inserted
        // into a task app that kept no UID for them (#1164). A row the list
        // does not know takes one of these with the same words, tick and level
        // before it comes in as a new task, so a row this phone put there
        // never comes back as a copy.
        val orphans = kept
            .filter { it.uid == null || (it.uid !in rowByUid && it.uid !in state.seen) }
            .groupByTo(HashMap(), { orphanKey(it.text, it.done, it.parentId != null) })
            .mapValuesTo(HashMap()) { (_, tasks) -> ArrayDeque(tasks.sortedBy { it.id }) }

        for (row in rows) {
            var mine = byUid[row.uid]
            if (mine == null) {
                val orphan = orphans[orphanKey(row.title, row.done, row.parentRowId != null)]?.removeFirstOrNull()
                if (orphan != null) {
                    orphan.uid?.let { byUid.remove(it) }
                    mine = orphan.copy(uid = row.uid)
                    out[mine.id] = mine
                    byUid[row.uid] = mine
                }
            }
            if (mine == null) {
                val task = TodoTask(
                    id = freshId(),
                    text = row.title.ifBlank { "…" },
                    uid = row.uid,
                    createdAt = now,
                    updatedAt = now,
                ).withRemote(row, now)
                out[task.id] = task
                byUid[row.uid] = task
                pulledRows += row
                continue
            }
            val remoteChanged = state.seen[row.uid] != row.version
            val localChanged = state.pushed[row.uid] != mine.updatedAt
            when {
                remoteChanged && (!localChanged || row.modified > mine.updatedAt) -> {
                    val next = mine.withRemote(row, now)
                    out[mine.id] = next
                    byUid[row.uid] = next
                    pulledRows += row
                }
                localChanged -> updates[row.rowId] = mine.id
            }
        }

        // Parents for what came in, now that every row has a local task.
        for (row in pulledRows) {
            val task = byUid[row.uid] ?: continue
            val parentUid = row.parentRowId?.let { uidByRow[it] }
            var parent = parentUid?.let { byUid[it] }?.let { out[it.id] }
            // One level only: a step of a step hangs off the top task.
            val grandparent = parent?.parentId
            if (grandparent != null) parent = out[grandparent]
            val parentId = parent?.id?.takeIf { it != task.id }
            if (task.parentId != parentId) out[task.id] = task.copy(parentId = parentId)
        }
        // A task that just became a parent cannot stay a step itself.
        val parents = out.values.mapNotNullTo(HashSet()) { it.parentId }
        for (id in parents) {
            val task = out[id] ?: continue
            if (task.parentId != null) out[id] = task.copy(parentId = null)
        }

        val unlinked = out.values.filter { it.uid == null }
        val inserts = unlinked.filter { it.parentId == null }.map { it.id } + unlinked.filter { it.parentId != null }.map { it.id }
        return TodoSyncPlan(
            local = out.values.toList(),
            inserts = inserts,
            updates = updates,
            deletes = deletedRows.map { it.rowId },
            pulled = pulledRows.size,
            removed = removed,
        )
    }

    /** The state to keep after a sync: what the provider holds now, and the list as written. */
    fun stateAfter(authority: String, listId: Long, remote: List<RemoteTask>, local: List<TodoTask>, now: Long): TodoSyncState =
        TodoSyncState(
            authority = authority,
            listId = listId,
            seen = remote.associate { it.uid to it.version },
            pushed = local.mapNotNull { task -> task.uid?.let { it to task.updatedAt } }.toMap(),
            lastSyncAt = now,
        )

    private fun orphanKey(text: String, done: Boolean, step: Boolean): Triple<String, Boolean, Boolean> =
        Triple(text.trim(), done, step)

    private fun TodoTask.withRemote(row: RemoteTask, now: Long): TodoTask = copy(
        text = row.title.ifBlank { text },
        done = row.done,
        completedAt = if (row.done) row.completedAt ?: completedAt ?: now else null,
        due = row.due,
        dueAllDay = row.due != null && row.dueAllDay,
        updatedAt = now,
    )
}
