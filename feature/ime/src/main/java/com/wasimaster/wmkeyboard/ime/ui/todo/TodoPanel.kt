package com.wasimaster.wmkeyboard.ime.ui.todo

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sync
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.todo.TodoDue
import com.wasimaster.wmkeyboard.core.todo.TodoDuePreset
import com.wasimaster.wmkeyboard.core.todo.TodoRepeat
import com.wasimaster.wmkeyboard.core.todo.TodoRepeatUnit
import com.wasimaster.wmkeyboard.core.todo.TodoTask
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.todo.TodoAction
import com.wasimaster.wmkeyboard.ime.todo.TodoCallbacks
import com.wasimaster.wmkeyboard.ime.todo.TodoRow
import com.wasimaster.wmkeyboard.ime.todo.TodoTextEntry
import com.wasimaster.wmkeyboard.ime.todo.TodoTextField
import com.wasimaster.wmkeyboard.ime.todo.TodoUi
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.ScrollFocusIntoView
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass

/** The panel's height while its text box shares the window with the key rows. */
private val TodoTypingHeight = 150.dp

/**
 * The To-do tool (#920): tasks with a box to tick, and the steps under each.
 *
 * A tap on the box ticks it; a tap on the words opens the task's steps and an
 * "Add a step" row under them; a long press edits the words, with Delete and
 * Type it beside the box. The header counts what is left, hides what is done
 * for this visit, clears it, and runs the task-app sync when one is set up.
 */
@Composable
internal fun TodoPanel(
    state: KeyboardUiState,
    callbacks: TodoCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.todoText
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)

    PanelFocusTarget(
        panel = PanelMode.TODO,
        region = FocusRegion.RESULTS,
        count = if (typing == null) ui.rows.size else 0,
        columns = 1,
    ) { index -> ui.rows.getOrNull(index)?.let { act(TodoAction.Toggle(it.task.id)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        compact = typing != null,
        compactHeight = TodoTypingHeight,
        headerActions = {
            if (typing != null) TypingHeader(typing, act) else ListHeader(ui, act)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.available -> Message(stringResource(R.string.ime_todo_unavailable))
                typing != null -> TypingBody(ui, typing, act)
                ui.rows.isEmpty() -> EmptyList(ui, act)
                else -> TaskList(ui, focusedRow, act)
            }
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let {
                    if (it.cleared) {
                        pluralStringResource(R.plurals.ime_todo_undo_cleared, it.tasks.size, it.tasks.size)
                    } else {
                        stringResource(R.string.ime_todo_undo_deleted)
                    }
                }.orEmpty(),
                visible = undo != null,
                key = undo,
                reduceMotion = state.settings.reduceMotion,
                onUndo = { act(TodoAction.Undo) },
                onDismiss = { act(TodoAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun RowScope.ListHeader(ui: TodoUi, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        Text(
            if (ui.openCount == 0 && ui.doneCount > 0) {
                stringResource(R.string.ime_todo_all_done)
            } else {
                pluralStringResource(R.plurals.ime_todo_left_count, ui.openCount, ui.openCount)
            },
            color = kb.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
        )
        ToolPanelChip(stringResource(R.string.ime_todo_add_action), selected = true) { act(TodoAction.StartNew) }
        if (ui.doneCount > 0) {
            ToolPanelChip(stringResource(R.string.ime_todo_hide_done_label), selected = ui.hideDone) { act(TodoAction.ToggleHideDone) }
            ToolPanelChip(stringResource(R.string.ime_todo_clear_done_action)) { act(TodoAction.ClearDone) }
        }
        Spacer(Modifier.width(2.dp))
    }
    if (ui.sync.enabled) {
        SyncButton(ui, act)
        Spacer(Modifier.width(4.dp))
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_todo_settings_desc),
        active = false,
    ) { act(TodoAction.OpenSettings) }
}

/** Runs the task-app sync now; spins while one runs, and wears the accent after one failed. */
@Composable
private fun SyncButton(ui: TodoUi, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    val description = stringResource(
        if (ui.sync.failed) R.string.ime_todo_sync_failed_desc else R.string.ime_todo_sync_desc,
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !ui.sync.running) { act(TodoAction.SyncNow) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (ui.sync.running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = kb.accent)
        } else {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (ui.sync.failed) kb.accent else kb.toolbarIcon,
            )
        }
    }
}

/** While the keys type into the panel, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: TodoTextEntry, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val placeholder = stringResource(
        when (entry.field) {
            TodoTextField.NEW -> R.string.ime_todo_new_hint
            TodoTextField.NEW_STEP -> R.string.ime_todo_step_hint
            TodoTextField.EDIT -> R.string.ime_todo_edit_hint
        },
    )
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = entry.text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    ToolPanelChip(stringResource(R.string.ime_todo_done_action), selected = true) { act(TodoAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

/**
 * Under the box while it types: what the words belong to, and for an edit
 * the task's own actions, which the long press brought up.
 */
@Composable
private fun TypingBody(ui: TodoUi, entry: TodoTextEntry, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    val parent = ui.rows.firstOrNull { it.task.id == entry.ref }?.task
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        when (entry.field) {
            TodoTextField.NEW -> Text(
                stringResource(R.string.ime_todo_new_info),
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
            )
            TodoTextField.NEW_STEP -> Text(
                stringResource(R.string.ime_todo_step_info, parent?.text.orEmpty()),
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TodoTextField.EDIT -> {
                val isTop = parent != null
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Quick due dates (#995): the task reminds at the time.
                    val task = ui.rows.firstNotNullOfOrNull { row ->
                        if (row.task.id == entry.ref) row.task else row.steps.firstOrNull { it.id == entry.ref }
                    }
                    for (preset in TodoDue.offered(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), task?.due != null)) {
                        ToolPanelChip(stringResource(duePresetLabel(preset))) { act(TodoAction.SetDue(entry.ref, preset)) }
                    }
                    if (isTop) {
                        ToolPanelChip(stringResource(repeatLabel(task?.repeat))) { act(TodoAction.CycleRepeat(entry.ref)) }
                    }
                    ToolPanelChip(stringResource(R.string.ime_todo_delete_action)) { act(TodoAction.Delete(entry.ref)) }
                    ToolPanelChip(stringResource(R.string.ime_todo_insert_action)) { act(TodoAction.Insert(entry.ref)) }
                    if (isTop) {
                        ToolPanelChip(stringResource(R.string.ime_todo_add_step_action)) { act(TodoAction.StartStep(entry.ref)) }
                    }
                    ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(TodoAction.TextCancel) }
                }
            }
        }
    }
}

@Composable
private fun TaskList(ui: TodoUi, focusedRow: Int?, act: (TodoAction) -> Unit) {
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(ui.rows, key = { it.task.id }) { row ->
            TaskCard(
                row = row,
                expanded = row.task.id in ui.expanded,
                focused = focusedRow != null && ui.rows.getOrNull(focusedRow)?.task?.id == row.task.id,
                act = act,
            )
        }
    }
}

/** A task, and its steps under it while it is open. */
@Composable
private fun TaskCard(row: TodoRow, expanded: Boolean, focused: Boolean, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (expanded) kb.chip else kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .focusRing(focused, shape)
            .animateContentSize(tween(160)),
    ) {
        TaskLine(task = row.task, step = false, act = act) {
            if (!row.progress.isEmpty) {
                Text(
                    stringResource(R.string.ime_todo_progress_label, row.progress.done, row.progress.total),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = kb.secondaryText,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (expanded) {
            for (step in row.steps) TaskLine(task = step, step = true, act = act)
            AddStepLine(row.task.id, act)
        }
    }
}

/**
 * One line: the box, and the words. The box ticks; the words open the steps
 * (a step's words do nothing on a tap); a long press anywhere edits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskLine(
    task: TodoTask,
    step: Boolean,
    act: (TodoAction) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val now = System.currentTimeMillis()
    val tickLabel = stringResource(R.string.ime_todo_tick_desc, task.text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!step) act(TodoAction.Expand(task.id)) },
                onLongClick = { act(TodoAction.Edit(task.id)) },
                onLongClickLabel = stringResource(R.string.ime_todo_edit_desc),
            )
            .padding(start = if (step) 30.dp else 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .toggleable(value = task.done, role = Role.Checkbox) { act(TodoAction.Toggle(task.id)) }
                .semantics { contentDescription = tickLabel },
            contentAlignment = Alignment.Center,
        ) {
            TickBox(task.done, small = step)
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                task.text,
                color = if (task.done) kb.secondaryText else kb.suggestionText,
                fontSize = if (step) 13.sp else 14.sp,
                fontWeight = if (step) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val due = task.due
            if (due != null) {
                Text(
                    dueLine(due, task.dueAllDay, task.overdue(now)),
                    color = if (task.overdue(now)) kb.accent else kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        trailing()
    }
}

/** The chip's words for a quick due date (#995). */
private fun duePresetLabel(preset: TodoDuePreset): Int = when (preset) {
    TodoDuePreset.LATER_TODAY -> R.string.ime_todo_due_later
    TodoDuePreset.THIS_EVENING -> R.string.ime_todo_due_evening
    TodoDuePreset.TOMORROW -> R.string.ime_todo_due_tomorrow
    TodoDuePreset.NEXT_WEEK -> R.string.ime_todo_due_next_week
    TodoDuePreset.NO_DATE -> R.string.ime_todo_due_none
}

@Composable
private fun dueLine(due: Long, allDay: Boolean, overdue: Boolean): String {
    val flags = if (allDay) {
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_UTC
    } else {
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = android.text.format.DateUtils.formatDateTime(context, due, flags or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
    return stringResource(if (overdue) R.string.ime_todo_overdue_label else R.string.ime_todo_due_label, text)
}

@Composable
private fun AddStepLine(parentId: Long, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { act(TodoAction.StartStep(parentId)) }
            .padding(start = 38.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.accent)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ime_todo_add_step_action), color = kb.accent, fontSize = 13.sp)
    }
}

/** A themed tick box; Material3's ignores the keyboard theme. Round, like Material You's task lists. */
@Composable
private fun TickBox(checked: Boolean, small: Boolean) {
    val kb = LocalKbTheme.current
    val size = if (small) 18.dp else 20.dp
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (checked) kb.chipActive else kb.chip)
            .chipBorder(kb, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(size - 6.dp))
        }
    }
}

@Composable
private fun EmptyList(ui: TodoUi, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Checklist, contentDescription = null, modifier = Modifier.size(28.dp), tint = kb.accent)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (ui.doneCount > 0) R.string.ime_todo_empty_hidden else R.string.ime_todo_empty),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ime_todo_empty_hint),
            color = kb.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        ToolPanelChip(stringResource(R.string.ime_todo_add_action), selected = true) { act(TodoAction.StartNew) }
    }
}

@Composable
private fun Message(text: String) {
    val kb = LocalKbTheme.current
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = kb.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

private fun repeatLabel(repeat: TodoRepeat?): Int = when (repeat?.unit) {
    null -> R.string.ime_todo_repeat_off
    TodoRepeatUnit.MINUTE -> R.string.ime_todo_repeat_30_min
    TodoRepeatUnit.HOUR -> R.string.ime_todo_repeat_hour
    TodoRepeatUnit.DAY -> R.string.ime_todo_repeat_day
    TodoRepeatUnit.WEEK -> R.string.ime_todo_repeat_week
    TodoRepeatUnit.MONTH -> R.string.ime_todo_repeat_month
    TodoRepeatUnit.YEAR -> R.string.ime_todo_repeat_year
}
