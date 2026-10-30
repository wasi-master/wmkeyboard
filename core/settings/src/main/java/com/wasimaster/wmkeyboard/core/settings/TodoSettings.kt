package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The To-do tool's settings (#920), one nested bag for the reason
 * [GallerySettings] gives, with flat `todo_*` DataStore keys read and written
 * by [TodoPrefs].
 *
 * The task-app fields name one list of one app on this phone, so they stay
 * here when settings sync (see `SyncKeys`): another phone's list ids mean
 * nothing on this one.
 */
data class TodoSettings(
    /** The keyboard's list leaves finished tasks out; the settings app's always has them. */
    val hideDone: Boolean = false,
    /** A task that has fallen due waits on the suggestion strip until it is ticked, put off or closed. */
    val dueChip: Boolean = true,
    /** Sync with a task app's list through its provider (DAVx5's OpenTasks or Tasks.org). Off until set up. */
    val syncEnabled: Boolean = false,
    /** The task app's provider authority, see `TaskApp`; "" for none picked. */
    val syncAuthority: String = "",
    val syncListId: Long = -1L,
    /** The list's name as it was when picked, for the settings row. */
    val syncListName: String = "",
    /**
     * Bumped when tasks.json was rewritten under the keyboard (a restore, a
     * sync, the settings app's list), so the running keyboard reloads instead
     * of saving its memory over the file. The twin of `notes.version`.
     */
    val version: Int = 0,
) {
    /** Whether a list is picked and sync is on. */
    val syncReady: Boolean get() = syncEnabled && syncAuthority.isNotEmpty() && syncListId >= 0
}

/** The DataStore half of [TodoSettings]. */
object TodoPrefs {
    private val HIDE_DONE = booleanPreferencesKey("todo_hide_done")
    private val DUE_CHIP = booleanPreferencesKey("todo_due_chip")
    private val SYNC_ENABLED = booleanPreferencesKey("todo_sync_enabled")
    private val SYNC_AUTHORITY = stringPreferencesKey("todo_sync_authority")
    private val SYNC_LIST_ID = longPreferencesKey("todo_sync_list_id")
    private val SYNC_LIST_NAME = stringPreferencesKey("todo_sync_list_name")
    private val VERSION = intPreferencesKey("todo_version")

    /** Keys that name something on this phone only; `SyncKeys` keeps them out of sync. */
    val LOCAL_KEYS: Set<String> = setOf(
        SYNC_ENABLED.name, SYNC_AUTHORITY.name, SYNC_LIST_ID.name, SYNC_LIST_NAME.name, VERSION.name,
    )

    fun read(p: Preferences, d: TodoSettings): TodoSettings = TodoSettings(
        hideDone = p[HIDE_DONE] ?: d.hideDone,
        dueChip = p[DUE_CHIP] ?: d.dueChip,
        syncEnabled = p[SYNC_ENABLED] ?: d.syncEnabled,
        syncAuthority = p[SYNC_AUTHORITY] ?: d.syncAuthority,
        syncListId = p[SYNC_LIST_ID] ?: d.syncListId,
        syncListName = p[SYNC_LIST_NAME] ?: d.syncListName,
        version = p[VERSION] ?: d.version,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: TodoSettings, new: TodoSettings) {
        if (new.hideDone != old.hideDone) p[HIDE_DONE] = new.hideDone
        if (new.dueChip != old.dueChip) p[DUE_CHIP] = new.dueChip
        if (new.syncEnabled != old.syncEnabled) p[SYNC_ENABLED] = new.syncEnabled
        if (new.syncAuthority != old.syncAuthority) p[SYNC_AUTHORITY] = new.syncAuthority
        if (new.syncListId != old.syncListId) p[SYNC_LIST_ID] = new.syncListId
        if (new.syncListName != old.syncListName) p[SYNC_LIST_NAME] = new.syncListName
        if (new.version != old.version) p[VERSION] = new.version
    }

    /** See [TodoSettings.version]. */
    fun bumpVersion(p: MutablePreferences) {
        p[VERSION] = (p[VERSION] ?: 0) + 1
    }
}
