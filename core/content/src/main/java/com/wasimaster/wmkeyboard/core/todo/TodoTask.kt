package com.wasimaster.wmkeyboard.core.todo

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One task in the To-do tool (#920).
 *
 * A task is either top-level or a subtask of one ([parentId]). Subtasks go one
 * level deep and no further: a keyboard panel has room for a task and the
 * steps under it, not an outline. [TodoStore] holds that rule; a subtask
 * asked to take subtasks of its own hands them to its parent.
 *
 * [createdAt] and [updatedAt] are always written, defaults or not, because
 * the sync merge keys items by `id@createdAt` (see `SyncEntries`). The rest is
 * written only when set, so a plain task is one short line of JSON.
 *
 * [uid] is the iCalendar UID the task has in a task app's list once the
 * optional task-app sync has written it there (see [TodoProviderSync]). It is
 * the same on every phone that syncs that list, which is how two phones that
 * share both this file and a CalDAV list recognise one task rather than two.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TodoTask(
    val id: Long,
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val done: Boolean = false,
    /** The task this is a step of, or null for a top-level task. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentId: Long? = null,
    /** When it is due, in epoch milliseconds; null for no date. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val due: Long? = null,
    /** [due] names a day rather than a moment: midnight UTC of that day. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dueAllDay: Boolean = false,
    val createdAt: Long = 0,
    /** When the text, the tick or the date last changed. */
    val updatedAt: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val completedAt: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val uid: String? = null,
    /**
     * How often it comes round again: ticking it moves [due] to the next time
     * instead of finishing it. Kept on this phone's list: a task app's copy has
     * only the date.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val repeat: TodoRepeat? = null,
    /** Reminders of it hold off until this time. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val snoozedUntil: Long? = null,
) {
    val isSubtask: Boolean get() = parentId != null

    /** Open and past its due time. An all-day task is overdue from the day after. */
    fun overdue(now: Long): Boolean {
        val at = due ?: return false
        if (done) return false
        return if (dueAllDay) at + DAY_MS <= now else at < now
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Every [every] [unit]s. */
@Serializable
data class TodoRepeat(val every: Int, val unit: TodoRepeatUnit) {
    companion object {
        /** What the keyboard's panel steps through. */
        val OFFERED: List<TodoRepeat> = listOf(
            TodoRepeat(30, TodoRepeatUnit.MINUTE),
            TodoRepeat(1, TodoRepeatUnit.HOUR),
            TodoRepeat(1, TodoRepeatUnit.DAY),
            TodoRepeat(1, TodoRepeatUnit.WEEK),
            TodoRepeat(1, TodoRepeatUnit.MONTH),
            TodoRepeat(1, TodoRepeatUnit.YEAR),
        )
    }
}

@Serializable
enum class TodoRepeatUnit { MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

/** How many of a task's steps are ticked, out of how many. */
data class TodoProgress(val done: Int, val total: Int) {
    val isEmpty: Boolean get() = total == 0
}
