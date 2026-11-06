package com.wasimaster.wmkeyboard.core.todo

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.TimeZone
import java.util.UUID

/**
 * A task app whose content provider speaks the OpenTasks contract, which is
 * what DAVx5 syncs CalDAV task lists into (#920). Tasks.org ships a copy of
 * the same provider under its own authority and permissions.
 *
 * jtx Board is the third app DAVx5 offers, and is not here on purpose: its
 * provider answers only callers that say they are a sync adapter, and a
 * write made that way is marked as already synced, so DAVx5 would never send
 * it to the server.
 */
enum class TaskApp(
    val authority: String,
    val packageName: String,
    val readPermission: String,
    val writePermission: String,
) {
    OPENTASKS("org.dmfs.tasks", "org.dmfs.tasks", "org.dmfs.permission.READ_TASKS", "org.dmfs.permission.WRITE_TASKS"),
    TASKS_ORG("org.tasks.opentasks", "org.tasks", "org.tasks.permission.READ_TASKS", "org.tasks.permission.WRITE_TASKS"),
    ;

    val permissions: Array<String> get() = arrayOf(readPermission, writePermission)

    companion object {
        fun of(authority: String): TaskApp? = entries.firstOrNull { it.authority == authority }
    }
}

/** A task list a task app holds: the CalDAV list DAVx5 made, or one of the app's own. */
data class TaskListInfo(
    val app: TaskApp,
    val id: Long,
    val name: String,
    val account: String,
    val color: Int? = null,
)

/** What one sync did, for the settings page's status line. */
sealed interface TodoSyncResult {
    data class Done(val pulled: Int, val pushed: Int, val removed: Int, val at: Long) : TodoSyncResult

    /** The task app is not installed, or the list is gone. */
    data object NoList : TodoSyncResult

    /** The task app's permission is not granted. */
    data object NoPermission : TodoSyncResult

    data class Failed(val message: String) : TodoSyncResult
}

/**
 * Two-way sync between the [TodoStore] and one task-app list, through the
 * task app's content provider. DAVx5 takes it from there to the CalDAV
 * server; this never talks to a network itself.
 *
 * Writes go in as an ordinary app's, never as a sync adapter's, so the task
 * app marks them dirty and DAVx5 uploads them on its next run. The merge is
 * [TodoProviderMerge]; this reads the rows, applies the plan, and keeps the
 * device-local [TodoSyncState] in [STATE_PATH], outside every backup, since
 * row ids mean nothing on another phone.
 *
 * Both processes may sync. A file lock on the state file keeps two runs from
 * inserting the same new task twice.
 */
object TodoProviderSync {

    const val STATE_PATH = "todo/provider-sync.json"

    private const val TAG = "TodoProviderSync"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // The OpenTasks contract's names, copied rather than depended on.
    private const val ID = "_id"
    private const val UID = "_uid"
    private const val DELETED = "_deleted"
    private const val LIST_ID = "list_id"
    private const val TITLE = "title"
    private const val STATUS = "status"
    private const val COMPLETED = "completed"
    private const val DUE = "due"
    private const val TZ = "tz"
    private const val IS_ALLDAY = "is_allday"
    private const val PARENT_ID = "parent_id"
    private const val VERSION = "version"
    private const val LAST_MODIFIED = "last_modified"
    private const val LIST_NAME = "list_name"
    private const val LIST_COLOR = "list_color"
    private const val ACCOUNT_NAME = "account_name"
    private const val STATUS_NEEDS_ACTION = 0
    private const val STATUS_COMPLETED = 2
    private const val STATUS_CANCELLED = 3

    private fun tasksUri(app: TaskApp): Uri = Uri.parse("content://${app.authority}/tasks")

    private fun listsUri(app: TaskApp): Uri = Uri.parse("content://${app.authority}/tasklists")

    /** The task apps installed and visible to this app. */
    fun installed(context: Context): List<TaskApp> = TaskApp.entries.filter { app ->
        context.packageManager.resolveContentProvider(app.authority, 0) != null
    }

    fun hasPermission(context: Context, app: TaskApp): Boolean =
        app.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Every list of every installed task app this app may read. */
    fun lists(context: Context): List<TaskListInfo> = installed(context)
        .filter { hasPermission(context, it) }
        .flatMap { app ->
            runCatching {
                context.contentResolver.query(
                    listsUri(app),
                    arrayOf(ID, LIST_NAME, ACCOUNT_NAME, LIST_COLOR),
                    null,
                    null,
                    null,
                )?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                TaskListInfo(
                                    app = app,
                                    id = c.getLong(0),
                                    name = c.getString(1).orEmpty(),
                                    account = c.getString(2).orEmpty(),
                                    color = if (c.isNull(3)) null else c.getInt(3),
                                ),
                            )
                        }
                    }
                }.orEmpty()
            }.onFailure { Log.w(TAG, "listing ${app.authority} failed", it) }.getOrDefault(emptyList())
        }

    /**
     * One sync of [store] with list [listId] of [app]. Reloads the store
     * first and saves it after, so call it off the main thread, and only
     * while nothing holds an unsaved change in memory.
     */
    fun sync(context: Context, store: TodoStore, app: TaskApp, listId: Long, now: Long = System.currentTimeMillis()): TodoSyncResult {
        if (context.packageManager.resolveContentProvider(app.authority, 0) == null) return TodoSyncResult.NoList
        if (!hasPermission(context, app)) return TodoSyncResult.NoPermission
        val stateFile = File(context.filesDir, STATE_PATH).also { it.parentFile?.mkdirs() }
        return runCatching {
            RandomAccessFile(File(stateFile.parentFile, stateFile.name + ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    syncLocked(context.contentResolver, store, app, listId, stateFile, now)
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "sync with ${app.authority} failed", error)
            TodoSyncResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Forgets what the last sync saw, for a list switched off or changed. */
    fun forget(context: Context) {
        File(context.filesDir, STATE_PATH).delete()
    }

    /** When the last sync finished, or 0. */
    fun lastSyncAt(context: Context): Long = readState(File(context.filesDir, STATE_PATH)).lastSyncAt

    private fun syncLocked(
        resolver: ContentResolver,
        store: TodoStore,
        app: TaskApp,
        listId: Long,
        stateFile: File,
        now: Long,
    ): TodoSyncResult {
        if (!listExists(resolver, app, listId)) return TodoSyncResult.NoList
        store.reloadIfChanged()
        val state = readState(stateFile).takeIf { it.isFor(app.authority, listId) } ?: TodoSyncState(app.authority, listId)
        val remote = readRows(resolver, app, listId)
        val plan = TodoProviderMerge.plan(store.all(), store.pendingDeletes(), remote, state, { store.freshId(now) }, now)
        val uri = tasksUri(app)

        for (row in plan.deletes) resolver.delete(Uri.withAppendedPath(uri, row.toString()), null, null)

        val byId = plan.local.associateBy { it.id }.toMutableMap()
        val rowByUid = remote.associate { it.uid to it.rowId }.toMutableMap()
        val remoteParent = remote.associate { it.rowId to it.parentRowId }
        fun parentRow(task: TodoTask): Long? = task.parentId?.let { byId[it]?.uid }?.let { rowByUid[it] }

        for ((row, id) in plan.updates) {
            val task = byId[id] ?: continue
            val values = values(task)
            val parent = parentRow(task)
            if (parent != remoteParent[row]) values.put(PARENT_ID, parent)
            resolver.update(Uri.withAppendedPath(uri, row.toString()), values, null, null)
        }
        var pushed = plan.updates.size
        for (id in plan.inserts) {
            val task = byId[id] ?: continue
            val values = values(task)
            values.put(LIST_ID, listId)
            parentRow(task)?.let { values.put(PARENT_ID, it) }
            // The provider leaves a row an app inserts without a UID until
            // DAVx5 uploads it (#1164). A task linked to nothing went in again
            // on every sync, and each copy came back once DAVx5 named it, so
            // the list doubled. The UID is set here, as a new row's may be.
            val uid = UUID.randomUUID().toString()
            val inserted = insertWithUid(resolver, uri, values, uid) ?: continue
            val stored = readUid(resolver, inserted) ?: uid
            inserted.lastPathSegment?.toLongOrNull()?.let { rowByUid[stored] = it }
            byId[id] = task.copy(uid = stored)
            pushed++
        }

        val final = plan.local.map { byId[it.id] ?: it }
        store.replaceAll(final, emptySet())
        store.save()
        val after = readRows(resolver, app, listId)
        writeState(stateFile, TodoProviderMerge.stateAfter(app.authority, listId, after, final, now))
        return TodoSyncResult.Done(plan.pulled, pushed, plan.removed, now)
    }

    private fun values(task: TodoTask): ContentValues = ContentValues().apply {
        put(TITLE, task.text)
        put(STATUS, if (task.done) STATUS_COMPLETED else STATUS_NEEDS_ACTION)
        if (task.done) put(COMPLETED, task.completedAt ?: task.updatedAt) else putNull(COMPLETED)
        if (task.due != null) {
            put(DUE, task.due)
            put(IS_ALLDAY, if (task.dueAllDay) 1 else 0)
            // An all-day date has no zone; a moment has the phone's.
            if (task.dueAllDay) putNull(TZ) else put(TZ, TimeZone.getDefault().id)
        } else {
            putNull(DUE)
        }
    }

    private fun listExists(resolver: ContentResolver, app: TaskApp, listId: Long): Boolean =
        resolver.query(Uri.withAppendedPath(listsUri(app), listId.toString()), arrayOf(ID), null, null, null)
            ?.use { it.moveToFirst() } == true

    private fun readRows(resolver: ContentResolver, app: TaskApp, listId: Long): List<RemoteTask> {
        val projection = arrayOf(ID, UID, TITLE, STATUS, COMPLETED, DUE, IS_ALLDAY, PARENT_ID, VERSION, LAST_MODIFIED)
        return resolver.query(
            tasksUri(app),
            projection,
            "$LIST_ID = ? AND $DELETED = 0",
            arrayOf(listId.toString()),
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val uid = c.getString(1) ?: continue
                    val status = c.intOrNull(3) ?: STATUS_NEEDS_ACTION
                    val completed = c.longOrNull(4)
                    add(
                        RemoteTask(
                            rowId = c.getLong(0),
                            uid = uid,
                            title = c.getString(2).orEmpty(),
                            done = status == STATUS_COMPLETED || status == STATUS_CANCELLED || completed != null,
                            completedAt = completed,
                            due = c.longOrNull(5),
                            dueAllDay = c.intOrNull(6) == 1,
                            parentRowId = c.longOrNull(7),
                            version = c.longOrNull(8) ?: 0L,
                            modified = c.longOrNull(9) ?: 0L,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /**
     * Inserts [values] with [uid]. A provider that refuses an app's UID gets
     * the row without one; the task still keeps [uid] here, so it is not
     * inserted again, and the next sync links it to its row by its title.
     */
    private fun insertWithUid(resolver: ContentResolver, uri: Uri, values: ContentValues, uid: String): Uri? {
        val withUid = ContentValues(values).apply { put(UID, uid) }
        return try {
            resolver.insert(uri, withUid)
        } catch (refused: IllegalArgumentException) {
            Log.w(TAG, "the task app refused a UID", refused)
            resolver.insert(uri, values)
        }
    }

    private fun readUid(resolver: ContentResolver, row: Uri): String? =
        resolver.query(row, arrayOf(UID), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun readState(file: File): TodoSyncState =
        runCatching { json.decodeFromString<TodoSyncState>(file.readText()) }.getOrDefault(TodoSyncState())

    private fun writeState(file: File, state: TodoSyncState) {
        runCatching {
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(json.encodeToString(state))
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
    }

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
}
