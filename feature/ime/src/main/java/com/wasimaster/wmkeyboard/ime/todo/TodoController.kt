package com.wasimaster.wmkeyboard.ime.todo

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.TodoSettings
import com.wasimaster.wmkeyboard.core.todo.TaskApp
import com.wasimaster.wmkeyboard.core.todo.TodoDue
import com.wasimaster.wmkeyboard.core.todo.TodoDuePreset
import com.wasimaster.wmkeyboard.core.todo.TodoProgress
import com.wasimaster.wmkeyboard.core.todo.TodoProviderSync
import com.wasimaster.wmkeyboard.core.todo.TodoReminders
import com.wasimaster.wmkeyboard.core.todo.TodoStore
import com.wasimaster.wmkeyboard.core.todo.TodoSyncResult
import com.wasimaster.wmkeyboard.core.todo.TodoTask
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the panel's text box is for. */
enum class TodoTextField {
    /** A new top-level task. Enter adds it and leaves the box open for the next. */
    NEW,

    /** A new step under the task [TodoTextEntry.ref]. */
    NEW_STEP,

    /** The text of task [TodoTextEntry.ref], opened by a long press. */
    EDIT,
}

/**
 * The text box the panel types into. Lives in `KeyboardUiState`, like
 * `ClockTextEntry`, because the keyboard's shared capture path (caret,
 * glide, suggestions, backspace) works on that state.
 */
data class TodoTextEntry(val field: TodoTextField, val text: String = "", val ref: Long = 0L)

/** One top-level task as the panel lists it, with its steps. */
data class TodoRow(val task: TodoTask, val steps: List<TodoTask>) {
    val progress: TodoProgress get() = TodoProgress(steps.count { it.done }, steps.size)
}

/** What the Undo bar would put back. */
data class TodoUndo(val cleared: Boolean, val tasks: List<TodoTask>)

/** Where the task-app sync stands, for the header's sync button. */
data class TodoSyncUi(
    val enabled: Boolean = false,
    val running: Boolean = false,
    /** The last run could not read or write the task app. */
    val failed: Boolean = false,
)

/**
 * Everything the To-do panel draws, owned by [TodoController] rather than
 * `KeyboardUiState`, for the reason `ClockUi` gives.
 */
data class TodoUi(
    /** The store is attached: false before the first unlock. */
    val available: Boolean = false,
    val rows: List<TodoRow> = emptyList(),
    /** Top-level tasks with their steps open. Kept across visits, like the notes filter. */
    val expanded: Set<Long> = emptySet(),
    val openCount: Int = 0,
    val doneCount: Int = 0,
    val hideDone: Boolean = false,
    val undo: TodoUndo? = null,
    val sync: TodoSyncUi = TodoSyncUi(),
)

sealed interface TodoAction {
    data object StartNew : TodoAction

    /** The next of [com.wasimaster.wmkeyboard.core.todo.TodoRepeat.OFFERED], then off. */
    data class CycleRepeat(val id: Long) : TodoAction
    data class StartStep(val parentId: Long) : TodoAction
    data class Edit(val id: Long) : TodoAction

    /** The Done chip: whatever the box holds is kept, and the keys go back. */
    data object TextDone : TodoAction
    data object TextCancel : TodoAction
    data class Toggle(val id: Long) : TodoAction
    data class Expand(val id: Long) : TodoAction
    data class Delete(val id: Long) : TodoAction

    /** Types the task's text into the field. */
    data class Insert(val id: Long) : TodoAction

    /** A quick due date for the task being edited (#995); its words are kept too. */
    data class SetDue(val id: Long, val preset: TodoDuePreset) : TodoAction
    data object ClearDone : TodoAction
    data object ToggleHideDone : TodoAction
    data object Undo : TodoAction
    data object UndoDismiss : TodoAction
    data object SyncNow : TodoAction
    data object OpenSettings : TodoAction
}

/** What the To-do panel hands back; one bundle, see `ToolHoldCallbacks.todo`. */
data class TodoCallbacks(
    val state: StateFlow<TodoUi> = MutableStateFlow(TodoUi()),
    val onAction: (TodoAction) -> Unit = {},
)

/**
 * The keyboard half of the To-do tool (#920).
 *
 * Holds the panel's [TodoUi] and turns its [TodoAction]s into changes to
 * the [TodoStore] that the settings app shares. Every store change and every
 * task-app sync runs on one worker thread, one after another, so a sync can
 * never read the list halfway through an edit or write over one. Built on
 * first use; nothing here runs until the panel opens.
 */
internal class TodoController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> TodoSettings,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (TodoTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now. */
    private val currentText: () -> TodoTextEntry?,
) {

    private val app = context.applicationContext
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1, "wmkb-todo")
    private val _state = MutableStateFlow(TodoUi())
    val state: StateFlow<TodoUi> = _state

    val callbacks = TodoCallbacks(state = state, onAction = ::dispatch)

    private var store: TodoStore? = null
    private var undoJob: Job? = null
    private var syncJob: Job? = null

    fun onPanelOpen() {
        // Each visit starts from the setting; the header's chip is for this visit.
        _state.update { it.copy(hideDone = settings().hideDone) }
        onWorker {
            val current = store ?: attach()
            current?.reloadIfChanged()
            publish()
        }
        scheduleSync(delayMs = 0)
    }

    fun onPanelClose() {
        if (currentText() != null) endText()
        undoJob?.cancel()
        _state.update { it.copy(undo = null) }
    }

    /** tasks.json was rewritten under the keyboard: a restore, a sync of settings. */
    fun reload() {
        onWorker {
            store?.reload()
            publish()
        }
    }

    /**
     * Enter in the box. A new task or step is added and the box stays open,
     * empty, for the next one; an edit is kept and the keys go back.
     */
    fun onTextEnter(entry: TodoTextEntry) {
        when (entry.field) {
            TodoTextField.NEW, TodoTextField.NEW_STEP -> {
                if (entry.text.isBlank()) {
                    endText()
                    return
                }
                addFrom(entry)
                beginText(entry.copy(text = ""))
            }
            TodoTextField.EDIT -> {
                keepEdit(entry)
                endText()
            }
        }
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: TodoAction) {
        when (action) {
            TodoAction.StartNew -> beginText(TodoTextEntry(TodoTextField.NEW))
            is TodoAction.CycleRepeat -> {
                haptic()
                change { store ->
                    val offered = com.wasimaster.wmkeyboard.core.todo.TodoRepeat.OFFERED
                    val now = store.item(action.id)?.repeat
                    store.setRepeat(action.id, offered.getOrNull(offered.indexOf(now) + 1))
                }
            }
            is TodoAction.StartStep -> {
                _state.update { it.copy(expanded = it.expanded + action.parentId) }
                beginText(TodoTextEntry(TodoTextField.NEW_STEP, ref = action.parentId))
            }
            is TodoAction.Edit -> {
                val task = rowTask(action.id) ?: return
                haptic()
                beginText(TodoTextEntry(TodoTextField.EDIT, task.text, task.id))
            }
            TodoAction.TextDone -> {
                val entry = currentText()
                if (entry != null) {
                    when (entry.field) {
                        TodoTextField.NEW, TodoTextField.NEW_STEP -> if (entry.text.isNotBlank()) addFrom(entry)
                        TodoTextField.EDIT -> keepEdit(entry)
                    }
                }
                endText()
            }
            TodoAction.TextCancel -> endText()
            is TodoAction.Toggle -> {
                haptic()
                change { it.setDone(action.id, !(it.item(action.id)?.done ?: return@change), System.currentTimeMillis()) }
            }
            is TodoAction.Expand -> _state.update {
                it.copy(expanded = if (action.id in it.expanded) it.expanded - action.id else it.expanded + action.id)
            }
            is TodoAction.Delete -> {
                haptic()
                if (currentText()?.ref == action.id) endText()
                change { s -> offerUndo(TodoUndo(cleared = false, tasks = s.delete(action.id))) }
            }
            is TodoAction.Insert -> {
                val task = rowTask(action.id) ?: return
                if (currentText() != null) endText()
                insertText(task.text)
            }
            is TodoAction.SetDue -> {
                haptic()
                val entry = currentText()
                if (entry?.field == TodoTextField.EDIT && entry.ref == action.id) {
                    keepEdit(entry)
                    endText()
                }
                val at = TodoDue.at(action.preset, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                change { it.setDue(action.id, at) }
            }
            TodoAction.ClearDone -> change { s -> offerUndo(TodoUndo(cleared = true, tasks = s.clearDone())) }
            TodoAction.ToggleHideDone -> {
                _state.update { it.copy(hideDone = !it.hideDone) }
                onWorker { publish() }
            }
            TodoAction.Undo -> {
                val undo = _state.value.undo ?: return
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
                change { it.restore(undo.tasks) }
            }
            TodoAction.UndoDismiss -> {
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
            }
            TodoAction.SyncNow -> scheduleSync(delayMs = 0)
            TodoAction.OpenSettings -> openToolSettings()
        }
    }

    // ---- the store, on the worker ----------------------------------------

    private fun attach(): TodoStore? {
        if (!DirectBoot.isUserUnlocked(app)) return null
        return TodoStore(File(app.filesDir, TodoStore.FILE_PATH)).also { store = it }
    }

    /** Runs [block] on the store, saves, redraws, and asks for a sync soon. */
    private fun change(block: (TodoStore) -> Unit) {
        onWorker {
            val s = store ?: attach() ?: return@onWorker
            block(s)
            s.save()
            publish()
            // A tick, a date or a delete moves the next reminder (#995).
            TodoReminders.rearm(app)
        }
        scheduleSync(delayMs = SYNC_AFTER_EDIT_MS)
    }

    private fun addFrom(entry: TodoTextEntry) {
        val parent = entry.ref.takeIf { entry.field == TodoTextField.NEW_STEP }
        change { it.add(entry.text, parentId = parent) }
    }

    private fun keepEdit(entry: TodoTextEntry) {
        // Blank keeps the old text: a delete is its own button, not an empty box.
        if (entry.text.isBlank()) return
        change { it.setText(entry.ref, entry.text) }
    }

    private fun offerUndo(undo: TodoUndo) {
        if (undo.tasks.isEmpty()) return
        _state.update { it.copy(undo = undo) }
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_MS)
            _state.update { if (it.undo === undo) it.copy(undo = null) else it }
        }
    }

    /** Rebuilds the list the panel draws. Worker only. */
    private fun publish() {
        val s = store
        val hide = _state.value.hideDone
        if (s == null) {
            _state.update { it.copy(available = false, rows = emptyList(), openCount = 0, doneCount = 0) }
            return
        }
        val rows = s.topLevel(hideDone = hide).map { TodoRow(it, s.subtasks(it.id)) }
        val all = s.topLevel()
        _state.update { ui ->
            ui.copy(
                available = true,
                rows = rows,
                openCount = all.count { !it.done },
                doneCount = all.count { it.done },
                expanded = ui.expanded.filterTo(HashSet()) { id -> rows.any { it.task.id == id } },
                sync = ui.sync.copy(enabled = settings().syncReady),
            )
        }
    }

    private fun rowTask(id: Long): TodoTask? = _state.value.rows.firstNotNullOfOrNull { row ->
        if (row.task.id == id) row.task else row.steps.firstOrNull { it.id == id }
    }

    private fun onWorker(block: suspend () -> Unit) {
        scope.launch(worker) { block() }
    }

    // ---- the task app ----------------------------------------------------

    /**
     * A sync a moment after the last edit, so a run of ticks is one sync,
     * or straight away for the panel opening and the sync button.
     */
    private fun scheduleSync(delayMs: Long) {
        val config = settings()
        if (!config.syncReady) {
            _state.update { it.copy(sync = TodoSyncUi()) }
            return
        }
        val taskApp = TaskApp.of(config.syncAuthority) ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            _state.update { it.copy(sync = it.sync.copy(enabled = true, running = true)) }
            var result: TodoSyncResult? = null
            kotlinx.coroutines.withContext(worker) {
                val s = store ?: attach() ?: return@withContext
                result = TodoProviderSync.sync(app, s, taskApp, config.syncListId)
                publish()
                // A task app's dates remind too.
                TodoReminders.rearm(app)
            }
            _state.update { it.copy(sync = TodoSyncUi(enabled = true, running = false, failed = result !is TodoSyncResult.Done)) }
        }
    }

    companion object {
        private const val UNDO_MS = 6_000L
        private const val SYNC_AFTER_EDIT_MS = 2_500L
    }
}
