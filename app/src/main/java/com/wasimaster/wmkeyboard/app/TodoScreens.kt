package com.wasimaster.wmkeyboard.app

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.todo.TaskApp
import com.wasimaster.wmkeyboard.core.todo.TaskListInfo
import com.wasimaster.wmkeyboard.core.todo.TodoPluginImport
import com.wasimaster.wmkeyboard.core.todo.TodoProviderSync
import com.wasimaster.wmkeyboard.core.todo.TodoStore
import com.wasimaster.wmkeyboard.core.todo.TodoSyncResult
import com.wasimaster.wmkeyboard.core.todo.TodoTask
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The To-do tool's screens in the settings app (#920): the list, and the
 * tool's own page with the task-app sync and the plugin import.
 *
 * Both open the file the keyboard writes, `todo/tasks.json`, through
 * [TodoStore], and re-read it when the screen comes back to the foreground.
 * Every change runs on one worker thread, the keyboard's rule, so the sync
 * this screen starts never meets an edit halfway.
 */

internal const val TODO_ROUTE = "todo"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val TodoWorker = Dispatchers.IO.limitedParallelism(1, "wmkb-todo-app")

/** The list as a screen sees it: the store, and a snapshot that recomposes on change. */
@Stable
internal class TodoScreenState(val store: TodoStore, private val context: android.content.Context) {
    var tops by mutableStateOf<List<TodoTask>>(emptyList())
    var steps by mutableStateOf<Map<Long, List<TodoTask>>>(emptyMap())

    fun refresh() {
        val list = store.topLevel()
        tops = list
        steps = list.associate { it.id to store.subtasks(it.id) }
    }

    /** Runs [block] on the store off the main thread, saves, and republishes. Returns what [block] did. */
    suspend fun <T> mutate(block: (TodoStore) -> T): T {
        val out = withContext(TodoWorker) {
            val result = block(store)
            store.save()
            // A date set or a task ticked here moves the next reminder (#995).
            com.wasimaster.wmkeyboard.core.todo.TodoReminders.rearm(context)
            result
        }
        refresh()
        return out
    }
}

@Composable
private fun rememberTodoState(): TodoScreenState? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TodoScreenState?>(null) }
    LaunchedEffect(Unit) {
        val store = withContext(TodoWorker) { TodoStore(File(context.filesDir, TodoStore.FILE_PATH)) }
        state = TodoScreenState(store, context.applicationContext).also { it.refresh() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = state ?: return@LifecycleEventEffect
        scope.launch {
            val changed = withContext(TodoWorker) { s.store.reloadIfChanged() }
            if (changed) s.refresh()
        }
    }
    return state
}

// ---- the list ----

/** Tools › To-do list: every task, ticked, opened to its steps, edited and deleted. */
@Composable
internal fun TodoListScreen(repository: SettingsRepository, settings: LiveSettings) {
    val state = rememberTodoState() ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSettingsSnackbar.current
    var draft by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var editing by remember { mutableStateOf<TodoTask?>(null) }
    var stepFor by remember { mutableStateOf<Long?>(null) }
    val syncReady = settings.watch { it.todo.syncReady }

    // The keyboard holds its own copy; tell it this screen wrote the file,
    // and let a sync that is set up carry the change to the task app.
    fun changed() {
        scope.launch { repository.bumpTodoVersion() }
        if (syncReady) scope.launch { runTodoSync(context, state, settings) }
    }

    fun add() {
        val text = draft
        if (text.isBlank()) return
        draft = ""
        scope.launch {
            state.mutate { it.add(text) }
            changed()
        }
    }

    fun delete(task: TodoTask) {
        scope.launch {
            val gone = state.mutate { it.delete(task.id) }
            changed()
            snackbar?.undo(context.getString(R.string.todo_deleted_snackbar), context.getString(CommonR.string.common_undo)) {
                scope.launch {
                    state.mutate { it.restore(gone) }
                    changed()
                }
            }
        }
    }

    RegisterPinned {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.replace("\n", " ") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.todo_add_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = { add() }, enabled = draft.isNotBlank()) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.todo_add_desc))
                }
            },
        )
    }

    if (state.tops.isEmpty()) {
        CaptionText(stringResource(R.string.todo_empty))
    } else {
        SettingsGroup {
            for (task in state.tops) {
                val steps = state.steps[task.id].orEmpty()
                val open = task.id in expanded
                item(key = task.id) {
                    TodoRow(
                        task = task,
                        steps = steps,
                        open = open,
                        onTick = { scope.launch { state.mutate { it.setDone(task.id, !task.done) }; changed() } },
                        onOpen = { expanded = if (open) expanded - task.id else expanded + task.id },
                        onEdit = { editing = task },
                        onDelete = { delete(task) },
                    )
                }
                if (open) {
                    for (step in steps) {
                        item(key = step.id) {
                            TodoRow(
                                task = step,
                                steps = emptyList(),
                                open = false,
                                step = true,
                                onTick = { scope.launch { state.mutate { it.setDone(step.id, !step.done) }; changed() } },
                                onOpen = { editing = step },
                                onEdit = { editing = step },
                                onDelete = { delete(step) },
                            )
                        }
                    }
                    item(key = "add-step-${task.id}") {
                        WmRow(
                            title = stringResource(R.string.todo_add_step_action),
                            leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            modifier = Modifier.padding(start = 32.dp),
                            onClick = { stepFor = task.id },
                        )
                    }
                }
            }
        }
        val doneCount = state.tops.count { it.done } + state.steps.values.sumOf { list -> list.count { it.done } }
        SettingsGroup {
            item {
                ActionRow(
                    R.string.todo_clear_done_title,
                    subtitle = stringResource(R.string.todo_clear_done_subtitle),
                    action = stringResource(R.string.todo_clear_done_button),
                    enabled = doneCount > 0,
                ) {
                    scope.launch {
                        val gone = state.mutate { it.clearDone() }
                        changed()
                        snackbar?.undo(
                            context.resources.getQuantityString(R.plurals.todo_cleared_snackbar, gone.size, gone.size),
                            context.getString(CommonR.string.common_undo),
                        ) {
                            scope.launch {
                                state.mutate { it.restore(gone) }
                                changed()
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { task ->
        TodoEditDialog(
            task = task,
            onDismiss = { editing = null },
            onSave = { text, due ->
                editing = null
                scope.launch {
                    state.mutate {
                        it.setText(task.id, text)
                        if (due != task.due) it.setDue(task.id, due, allDay = due != null)
                        Unit
                    }
                    changed()
                }
            },
            onDelete = {
                editing = null
                delete(task)
            },
        )
    }
    stepFor?.let { parent ->
        TodoStepDialog(
            onDismiss = { stepFor = null },
            onAdd = { text ->
                stepFor = null
                scope.launch {
                    state.mutate { it.add(text, parentId = parent) }
                    changed()
                }
            },
        )
    }
}

/** One task, or one step indented under its task. The box ticks; a long press or a swipe edits or deletes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    task: TodoTask,
    steps: List<TodoTask>,
    open: Boolean,
    step: Boolean = false,
    onTick: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val delete = deleteSwipeAction(stringResource(R.string.todo_delete_action), onDelete)
    val editLabel = stringResource(R.string.todo_edit_desc)
    SwipeActionsRow(actions = listOf(delete), fullSwipe = delete) {
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onEdit, onLongClickLabel = editLabel)
                .padding(start = if (step) 32.dp else 0.dp),
        ) {
            WmRow(
                title = task.text,
                titleContent = {
                    Text(
                        task.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leading = { Checkbox(checked = task.done, onCheckedChange = { onTick() }) },
                trailing = if (!step) ({
                    Icon(
                        if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (open) R.string.todo_collapse_desc else R.string.todo_expand_desc),
                    )
                }) else null,
                supporting = todoSupporting(task, steps),
            )
        }
    }
}

/** The due date and the step count under a task's words, or null when it has neither. */
@Composable
private fun todoSupporting(task: TodoTask, steps: List<TodoTask>): (@Composable () -> Unit)? {
    val due = task.due
    if (due == null && steps.isEmpty()) return null
    val now = System.currentTimeMillis()
    val dueText = due?.let {
        val text = formatTodoDue(LocalContext.current, it, task.dueAllDay)
        stringResource(if (task.overdue(now)) R.string.todo_overdue_label else R.string.todo_due_label, text)
    }
    val stepText = if (steps.isEmpty()) null else stringResource(R.string.todo_steps_progress, steps.count { it.done }, steps.size)
    val line = listOfNotNull(dueText, stepText).joinToString(" · ")
    val overdue = task.overdue(now)
    return {
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatTodoDue(context: android.content.Context, due: Long, allDay: Boolean): String {
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
        (if (allDay) DateUtils.FORMAT_UTC else DateUtils.FORMAT_SHOW_TIME)
    return DateUtils.formatDateTime(context, due, flags)
}

/** The words, the due day, and Delete. The day is a whole day, as a CalDAV all-day due date is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditDialog(
    task: TodoTask,
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(task.text) }
    var due by rememberSaveable { mutableStateOf(task.due) }
    var allDay by rememberSaveable { mutableStateOf(task.dueAllDay) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", " ") },
                    label = { Text(stringResource(R.string.todo_text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picking = true }) {
                        Text(
                            due?.let { formatTodoDue(context, it, allDay) }
                                ?: stringResource(R.string.todo_due_set_action),
                        )
                    }
                    if (due != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { due = null }) { Text(stringResource(R.string.todo_due_clear_action)) }
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.todo_delete_action), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, if (due == task.due && allDay == task.dueAllDay) task.due else due) }, enabled = text.isNotBlank()) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
    if (picking) {
        // The picker hands back midnight UTC of the day picked, which is what
        // an all-day due date is; a timed one starts on its own day.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = due)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    pickerState.selectedDateMillis?.let {
                        due = it
                        allDay = true
                    }
                }) { Text(stringResource(CommonR.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(CommonR.string.common_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun TodoStepDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_add_step_action)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", " ") },
                placeholder = { Text(stringResource(R.string.todo_step_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.todo_add_desc)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

// ---- the tool's own page ----

/** The To-do tool's settings page body, called from [ToolDetailSettings]. */
@Composable
internal fun TodoToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.todo
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val open by produceState<Int?>(initialValue = null, revision) {
        value = withContext(TodoWorker) { TodoStore(File(context.filesDir, TodoStore.FILE_PATH)).openCount() }
    }
    val pluginItems by produceState<String?>(initialValue = null, revision) {
        value = withContext(Dispatchers.IO) { readPluginItems(context) }
    }
    val todo = settings.watch { it.todo }

    SettingsGroup(stringResource(R.string.tooldetail_todo_group)) {
        item {
            val count = open
            NavRow(
                R.string.tooldetail_todo_open_title,
                subtitle = if (count == null || count == 0) {
                    stringResource(R.string.tooldetail_todo_open_none_subtitle)
                } else {
                    pluralStringResource(R.plurals.tooldetail_todo_open_subtitle, count, count)
                },
                route = TODO_ROUTE,
            ) { onNavigate(TODO_ROUTE) }
        }
        item(level = SettingLevel.STANDARD) { HomeScreenShortcutRow(R.string.home_shortcut_todo_label, TODO_ROUTE) }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.tooldetail_todo_hide_done_title,
                stringResource(R.string.tooldetail_todo_hide_done_subtitle),
                todo.hideDone,
                default = d.hideDone,
            ) { on -> scope.launch { repository.updateTodo { it.copy(hideDone = on) } } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.tooldetail_todo_due_chip_title,
                stringResource(R.string.tooldetail_todo_due_chip_subtitle),
                todo.dueChip,
                default = d.dueChip,
            ) { on -> scope.launch { repository.updateTodo { it.copy(dueChip = on) } } }
        }
    }

    val installed by produceState(initialValue = emptyList<TaskApp>(), revision) {
        value = withContext(Dispatchers.IO) { TodoProviderSync.installed(context) }
    }
    SettingsGroup(stringResource(R.string.tooldetail_todo_sync_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.tooldetail_todo_sync_title,
                stringResource(
                    if (installed.isEmpty() && !todo.syncEnabled) R.string.tooldetail_todo_sync_none_subtitle else R.string.tooldetail_todo_sync_subtitle,
                ),
                todo.syncEnabled,
                info = stringResource(R.string.tooldetail_todo_sync_info),
                enabled = installed.isNotEmpty() || todo.syncEnabled,
                default = d.syncEnabled,
            ) { on ->
                if (on) {
                    picking = true
                } else {
                    scope.launch {
                        repository.updateTodo { it.copy(syncEnabled = false) }
                        withContext(Dispatchers.IO) { TodoProviderSync.forget(context) }
                    }
                }
            }
        }
        if (todo.syncEnabled) {
            item {
                NavRow(
                    R.string.tooldetail_todo_list_title,
                    value = todo.syncListName.ifEmpty { stringResource(R.string.tooldetail_todo_list_none) },
                ) { picking = true }
            }
            item {
                val last = remember(revision) { TodoProviderSync.lastSyncAt(context) }
                ActionRow(
                    R.string.tooldetail_todo_sync_now_title,
                    subtitle = if (last > 0) {
                        stringResource(R.string.tooldetail_todo_sync_last, DateUtils.getRelativeTimeSpanString(last).toString())
                    } else {
                        stringResource(R.string.tooldetail_todo_sync_never)
                    },
                    action = stringResource(R.string.tooldetail_todo_sync_now_button),
                    enabled = todo.syncReady,
                ) {
                    scope.launch {
                        message = syncMessage(context, runTodoSync(context, null, settings))
                        revision++
                        repository.bumpTodoVersion()
                    }
                }
            }
        }
    }

    SettingsGroup(level = SettingLevel.STANDARD) {
        if (!pluginItems.isNullOrBlank()) {
            item {
                ActionRow(
                    R.string.tooldetail_todo_import_title,
                    subtitle = stringResource(R.string.tooldetail_todo_import_subtitle),
                    action = stringResource(R.string.tooldetail_todo_import_button),
                ) {
                    scope.launch {
                        val added = withContext(TodoWorker) {
                            val store = TodoStore(File(context.filesDir, TodoStore.FILE_PATH))
                            val missing = TodoPluginImport.missing(TodoPluginImport.parse(pluginItems), store.all())
                            missing.forEach { store.add(it) }
                            store.save()
                            missing.size
                        }
                        repository.bumpTodoVersion()
                        revision++
                        message = if (added == 0) {
                            context.getString(R.string.tooldetail_todo_imported_none)
                        } else {
                            context.resources.getQuantityString(R.plurals.tooldetail_todo_imported, added, added)
                        }
                    }
                }
            }
        }
        item {
            ActionRow(
                R.string.tooldetail_todo_clear_title,
                subtitle = stringResource(R.string.tooldetail_todo_clear_subtitle),
                action = stringResource(CommonR.string.common_delete),
                confirm = stringResource(R.string.tooldetail_todo_clear_confirm),
            ) {
                scope.launch {
                    withContext(TodoWorker) {
                        val store = TodoStore(File(context.filesDir, TodoStore.FILE_PATH))
                        store.clearAll()
                        store.save()
                    }
                    repository.bumpTodoVersion()
                    revision++
                }
            }
        }
    }

    if (picking) {
        TaskListPicker(
            installed = installed,
            selected = todo.syncAuthority to todo.syncListId,
            onDismiss = { picking = false },
            onPick = { list ->
                picking = false
                scope.launch {
                    val changedList = list.app.authority != todo.syncAuthority || list.id != todo.syncListId
                    if (changedList) withContext(Dispatchers.IO) { TodoProviderSync.forget(context) }
                    repository.updateTodo {
                        it.copy(syncEnabled = true, syncAuthority = list.app.authority, syncListId = list.id, syncListName = list.name)
                    }
                    val result = withContext(TodoWorker) {
                        TodoProviderSync.sync(context, TodoStore(File(context.filesDir, TodoStore.FILE_PATH)), list.app, list.id)
                    }
                    message = syncMessage(context, result)
                    repository.bumpTodoVersion()
                    revision++
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
            text = { Text(text) },
        )
    }
}

/**
 * The task apps installed, each with its lists once its permission is in,
 * or an Allow button that asks for it behind the disclosure first.
 */
@Composable
private fun TaskListPicker(
    installed: List<TaskApp>,
    selected: Pair<String, Long>,
    onDismiss: () -> Unit,
    onPick: (TaskListInfo) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableIntStateOf(0) }
    val lists by produceState(initialValue = emptyList<TaskListInfo>(), granted) {
        value = withContext(Dispatchers.IO) { TodoProviderSync.lists(context) }
    }
    val askOpenTasks = rememberDisclosedPermissionRequest(PermissionDisclosures.TASKS_OPENTASKS) { granted++ }
    val askTasksOrg = rememberDisclosedPermissionRequest(PermissionDisclosures.TASKS_TASKS_ORG) { granted++ }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tooldetail_todo_list_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (app in installed) {
                    Text(
                        stringResource(app.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    // Read again on each recomposition after a grant, through [granted].
                    val allowed = remember(granted) { TodoProviderSync.hasPermission(context, app) }
                    val mine = lists.filter { it.app == app }
                    if (!allowed) {
                        TextButton(onClick = { if (app == TaskApp.OPENTASKS) askOpenTasks() else askTasksOrg() }) {
                            Text(stringResource(R.string.tooldetail_todo_list_allow_action, stringResource(app.labelRes())))
                        }
                    } else if (mine.isEmpty()) {
                        Text(
                            stringResource(R.string.tooldetail_todo_list_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (list in mine) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(list) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == list.app.authority to list.id, onClick = { onPick(list) })
                            Text(
                                if (list.account.isBlank()) list.name else stringResource(R.string.tooldetail_todo_list_entry, list.name, list.account),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private fun TaskApp.labelRes(): Int = when (this) {
    TaskApp.OPENTASKS -> R.string.tooldetail_todo_app_opentasks
    TaskApp.TASKS_ORG -> R.string.tooldetail_todo_app_tasks_org
}

/** One sync of the file with the picked list; reloads [state] after when there is one. */
private suspend fun runTodoSync(context: android.content.Context, state: TodoScreenState?, settings: LiveSettings): TodoSyncResult? {
    val todo = settings.value.todo
    val app = TaskApp.of(todo.syncAuthority) ?: return null
    if (!todo.syncReady) return null
    val result = withContext(TodoWorker) {
        val store = state?.store ?: TodoStore(File(context.filesDir, TodoStore.FILE_PATH))
        TodoProviderSync.sync(context, store, app, todo.syncListId)
    }
    state?.refresh()
    return result
}

private fun syncMessage(context: android.content.Context, result: TodoSyncResult?): String? = when (result) {
    null -> null
    is TodoSyncResult.Done -> context.getString(R.string.tooldetail_todo_sync_done, result.pulled, result.pushed, result.removed)
    TodoSyncResult.NoList -> context.getString(R.string.tooldetail_todo_sync_no_list)
    TodoSyncResult.NoPermission -> context.getString(R.string.tooldetail_todo_sync_no_permission)
    is TodoSyncResult.Failed -> context.getString(R.string.tooldetail_todo_sync_failed, result.message)
}

/** The Todo List plugin's saved `items`, or null when it is not installed or holds nothing. */
private fun readPluginItems(context: android.content.Context): String? {
    val file = PluginStore.get(context).storageFile(TodoPluginImport.PLUGIN_ID) ?: return null
    if (!file.isFile) return null
    return PluginStorage(file).get(TodoPluginImport.STORAGE_KEY)?.takeIf { TodoPluginImport.parse(it).isNotEmpty() }
}
