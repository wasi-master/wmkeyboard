package com.wasimaster.wmkeyboard.ime.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
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
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.ScrollFocusIntoView
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.UndoBarClearance
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.MiniRing
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringBouncy
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringGentle
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin

/** The panel's height while its text box shares the window with the key rows. */
private val TodoTypingHeight = 164.dp

/**
 * The To-do tool (#920) in the panel language the Notes shelf set: a DUE
 * carousel of wide tinted cards for what is overdue or due today, the rest
 * of the tasks as cards in one column, and a floating "Add a task…" pill
 * along the bottom.
 *
 * A card's round box ticks the task, with a spring on the box and a fade on
 * the strike-through; a tap on the card unfolds its steps inside it, each
 * with a box of its own and an "Add a step" line; a long press edits the
 * words, and the card then carries the chips (quick due dates, repeat,
 * delete, type it, add a step, cancel) while the keys type into the header.
 * The header counts what is left, hides what is done for this visit, clears
 * it, and runs the task-app sync when one is set up. Nothing here owns a
 * task: `TodoController` does, and the panel draws what it publishes.
 */
@Composable
internal fun TodoPanel(
    state: KeyboardUiState,
    callbacks: TodoCallbacks,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.todoText
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    // Due today and overdue go to the carousel; the split is re-read each
    // minute at most, since a task crossing midnight is the only thing that
    // moves one without the list itself changing.
    val lists = remember(ui.rows, now / MINUTE_MS) { splitDue(ui.rows, now, zone) }

    PanelFocusTarget(
        panel = PanelMode.TODO,
        region = FocusRegion.RESULTS,
        count = if (typing == null) lists.ordered.size else 0,
        columns = 1,
    ) { index -> lists.ordered.getOrNull(index)?.let { act(TodoAction.Toggle(it.task.id)) } }

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
                typing != null -> TypingBody(ui, typing, now, zone, act)
                ui.rows.isEmpty() -> EmptyList(ui, act)
                else -> TaskList(ui, lists, focusedRow, now, zone, act)
            }
            if (ui.available && typing == null && ui.rows.isNotEmpty()) {
                // The way to a new task floats over the list, and steps aside
                // for the Undo bar, which takes the same place.
                FloatingAdd(
                    visible = ui.undo == null,
                    animate = kb.motion.any,
                    act = act,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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

// ---- which tasks go where ----

/** How a task's date stands against now: nothing to say, due today, or past. */
private enum class DueKind { NONE, TODAY, OVERDUE }

/** The carousel's tasks, the column's, and the two in the order the hardware ring walks them. */
private class TodoLists(val due: List<TodoRow>, val rest: List<TodoRow>) {
    val ordered: List<TodoRow> = due + rest
}

private fun splitDue(rows: List<TodoRow>, now: Long, zone: ZoneId): TodoLists {
    val due = rows.filter { it.task.dueKind(now, zone) != DueKind.NONE }
    return TodoLists(due, if (due.isEmpty()) rows else rows.filterNot { it in due })
}

/** An open task's date against [now]; a ticked one has nothing to say however late it was. */
private fun TodoTask.dueKind(now: Long, zone: ZoneId): DueKind {
    val at = due ?: return DueKind.NONE
    if (done) return DueKind.NONE
    if (overdue(now)) return DueKind.OVERDUE
    // An all-day date is midnight UTC of its day; a timed one is a moment where the phone is.
    val day = Instant.ofEpochMilli(at).atZone(if (dueAllDay) ZoneOffset.UTC else zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (day == today) DueKind.TODAY else DueKind.NONE
}

// ---- headers ----

/** What is left in secondary text, then the chips scrolling, the sync button when sync is on, and settings. */
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
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(2.dp))
        // The toggle stays while it is on, so there is always a way to turn it off.
        if (ui.doneCount > 0 || ui.hideDone) {
            ToolPanelChip(stringResource(R.string.ime_todo_hide_done_label), selected = ui.hideDone) { act(TodoAction.ToggleHideDone) }
        }
        if (ui.doneCount > 0) {
            ToolPanelChip(stringResource(R.string.ime_todo_clear_done_action)) { act(TodoAction.ClearDone) }
        }
        Spacer(Modifier.width(2.dp))
    }
    if (ui.sync.enabled) {
        SyncButton(ui, act)
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
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val description = stringResource(
        if (ui.sync.failed) R.string.ime_todo_sync_failed_desc else R.string.ime_todo_sync_desc,
    )
    IconHint(description) {
        Box(
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .size(38.dp)
                .pressScale(source, kb.motion.any)
                .clip(CircleShape)
                .background(if (ui.sync.failed) kb.accent.copy(alpha = if (kb.dark) 0.22f else 0.14f) else Color.Transparent)
                .clickable(enabled = !ui.sync.running, interactionSource = source, indication = null) {
                    feedback()
                    act(TodoAction.SyncNow)
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.sync.running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = kb.accent)
            } else {
                Icon(
                    Icons.Outlined.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (ui.sync.failed) kb.accent else kb.toolbarIcon,
                )
            }
        }
    }
}

/** While the keys type into the panel, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: TodoTextEntry, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
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
 * Under the box while it types: what the words belong to, or for an edit the
 * task's own card wearing its chips, which the long press brought up.
 */
@Composable
private fun TypingBody(ui: TodoUi, entry: TodoTextEntry, now: Long, zone: ZoneId, act: (TodoAction) -> Unit) {
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
                val row = ui.rows.firstOrNull { it.task.id == entry.ref }
                val step = if (row == null) ui.rows.firstNotNullOfOrNull { r -> r.steps.firstOrNull { it.id == entry.ref } } else null
                val shown = row ?: step?.let { TodoRow(it, emptyList()) }
                if (shown == null) {
                    // The task went while its words were open: only the way out is left.
                    ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(TodoAction.TextCancel) }
                } else {
                    TaskCard(
                        row = shown,
                        expanded = false,
                        focused = false,
                        kind = shown.task.dueKind(now, zone),
                        index = 0,
                        now = now,
                        act = act,
                        modifier = Modifier.fillMaxWidth(),
                        held = true,
                    ) { EditChips(shown.task, isTop = row != null, now, zone, act) }
                }
            }
        }
    }
}

/** The chip row inside a held card: quick due dates (#995), repeat, delete, type it, add a step, cancel. */
@Composable
private fun EditChips(task: TodoTask, isTop: Boolean, now: Long, zone: ZoneId, act: (TodoAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (preset in TodoDue.offered(now, zone, task.due != null)) {
            ToolPanelChip(stringResource(duePresetLabel(preset))) { act(TodoAction.SetDue(task.id, preset)) }
        }
        if (isTop) {
            ToolPanelChip(stringResource(repeatLabel(task.repeat))) { act(TodoAction.CycleRepeat(task.id)) }
        }
        ToolPanelChip(stringResource(R.string.ime_todo_delete_action)) { act(TodoAction.Delete(task.id)) }
        ToolPanelChip(stringResource(R.string.ime_todo_insert_action)) { act(TodoAction.Insert(task.id)) }
        if (isTop) {
            ToolPanelChip(stringResource(R.string.ime_todo_add_step_action)) { act(TodoAction.StartStep(task.id)) }
        }
        ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(TodoAction.TextCancel) }
    }
}

// ---- the list ----

/**
 * The DUE label and its carousel when anything is due, then the rest of the
 * tasks as cards in one column, the whole thing one scroll. Cards pop in a
 * beat apart as they first show, and a task that moves (ticked, so it sinks
 * to the bottom) slides to its new place.
 */
@Composable
private fun TaskList(
    ui: TodoUi,
    lists: TodoLists,
    focusedRow: Int?,
    now: Long,
    zone: ZoneId,
    act: (TodoAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val listState = rememberLazyListState()
    // The label and the carousel sit ahead of the column's own cards.
    val head = if (lists.due.isNotEmpty()) 2 else 0
    ScrollFocusIntoView(focusedRow) { index ->
        listState.animateScrollToItem(if (index < lists.due.size) 0 else head + (index - lists.due.size))
    }
    val focusedId = focusedRow?.let { lists.ordered.getOrNull(it)?.task?.id }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 4.dp,
            end = 12.dp,
            // Room under the last cards for the add pill, or the Undo bar while it is up.
            bottom = if (ui.undo != null) UndoBarClearance else AddPillClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lists.due.isNotEmpty()) {
            item(key = "due-label") { DueLabel() }
            item(key = "due-shelf") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(lists.due, key = { _, row -> row.task.id }) { index, row ->
                        TaskCard(
                            row = row,
                            expanded = row.task.id in ui.expanded,
                            focused = focusedId == row.task.id,
                            kind = row.task.dueKind(now, zone),
                            index = index,
                            now = now,
                            act = act,
                            modifier = Modifier
                                .width(DueCardWidth)
                                .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (animate) kitSpringGentle() else null),
                        )
                    }
                }
            }
        }
        itemsIndexed(lists.rest, key = { _, row -> row.task.id }) { index, row ->
            TaskCard(
                row = row,
                expanded = row.task.id in ui.expanded,
                focused = focusedId == row.task.id,
                kind = DueKind.NONE,
                index = index + head,
                now = now,
                act = act,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (animate) kitSpringGentle() else null),
            )
        }
    }
}

/** "DUE" in small caps with a clock beside it. */
@Composable
private fun DueLabel() {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, top = 2.dp)) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = kb.accent, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.ime_todo_due_section).uppercase(),
            color = kb.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

// ---- a task's card ----

/**
 * The card every task wears: a pop as it first shows, a squash under the
 * finger, a tap that unfolds its steps and a hold that edits it. A due card
 * is washed with the accent, an overdue one with a warmer colour, each
 * striped along the leading edge; a done one sits dim. [held] is the card
 * while its words are being edited: it carries [chips] in place of its
 * steps, a tap on it puts the edit away, and its ring and chevron rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    row: TodoRow,
    expanded: Boolean,
    focused: Boolean,
    kind: DueKind,
    index: Int,
    now: Long,
    act: (TodoAction) -> Unit,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    chips: (@Composable () -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val animate = kb.motion.any
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    val task = row.task
    val tint = when (kind) {
        DueKind.OVERDUE -> overdueColour(kb)
        DueKind.TODAY -> kb.accent
        DueKind.NONE -> null
    }
    val dim by animateFloatAsState(if (task.done) DONE_ALPHA else 1f, if (animate) tween(STRIKE_MS) else snap(), label = "todo-dim")
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, if (animate) kitSpringGentle() else snap(), label = "todo-chevron")
    val editLabel = stringResource(R.string.ime_todo_edit_desc)
    Column(
        modifier = modifier
            .then(if (kind != DueKind.NONE) Modifier.heightIn(min = DueCardMinHeight) else Modifier)
            .popIn(index.coerceAtMost(POP_STAGGER_CAP), animate)
            .pressScale(source, animate)
            .graphicsLayer { alpha = dim }
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(
                if (held) kb.chipActive.copy(alpha = kb.chipActive.alpha * 0.5f) else kb.chip.copy(alpha = kb.chip.alpha * 0.75f),
            )
            .then(if (tint != null) Modifier.dueTint(tint) else Modifier)
            .chipBorder(kb, shape)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = { if (held) act(TodoAction.TextCancel) else act(TodoAction.Expand(task.id)) },
                onLongClick = {
                    feedback()
                    act(TodoAction.Edit(task.id))
                },
                onLongClickLabel = editLabel,
            )
            .then(if (animate) Modifier.animateContentSize(kitSpringGentle()) else Modifier)
            .padding(start = if (tint != null) 8.dp else 4.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        TaskLine(task = task, step = false, kind = kind, now = now, animate = animate, act = act) {
            if (!held) {
                if (!row.progress.isEmpty) {
                    MiniRing(
                        fraction = row.progress.done.toFloat() / row.progress.total,
                        colour = tint ?: kb.accent,
                        size = 16.dp,
                        stroke = 2.5.dp,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.ime_todo_progress_of, row.progress.done, row.progress.total),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(chevron),
                    tint = kb.secondaryText,
                )
                Spacer(Modifier.width(2.dp))
            }
        }
        when {
            chips != null -> chips()
            expanded -> {
                for (step in row.steps) {
                    key(step.id) {
                        TaskLine(
                            task = step,
                            step = true,
                            kind = DueKind.NONE,
                            now = now,
                            animate = animate,
                            act = act,
                            onHold = {
                                feedback()
                                act(TodoAction.Edit(step.id))
                            },
                        )
                    }
                }
                AddStepLine(task.id, act)
            }
        }
    }
}

/** A wash of [colour] over the card and a 4 dp stripe of it along the leading edge; drawn inside the clip. */
private fun Modifier.dueTint(colour: Color): Modifier =
    background(colour.copy(alpha = TINT_ALPHA)).drawBehind {
        val w = STRIPE.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - w
        drawRect(colour, Offset(x, 0f), Size(w, size.height))
    }

/** The warmer colour an overdue card and its date wear: amber, deepened on a light theme so the date still reads. */
private fun overdueColour(kb: KbTheme): Color = if (kb.dark) OverdueWarmDark else OverdueWarmLight

/**
 * One line: the box, the words with the date under them, and [trailing].
 * The box ticks. The words of a top-level task belong to the card around
 * them, which takes the tap and the hold; a step's words take only a hold,
 * through [onHold].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskLine(
    task: TodoTask,
    step: Boolean,
    kind: DueKind,
    now: Long,
    animate: Boolean,
    act: (TodoAction) -> Unit,
    onHold: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val tickLabel = stringResource(R.string.ime_todo_tick_desc, task.text)
    val editLabel = stringResource(R.string.ime_todo_edit_desc)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onHold != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onHold, onLongClickLabel = editLabel)
                } else {
                    Modifier
                },
            )
            .padding(start = if (step) 26.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(checked = task.done, small = step, animate = animate, description = tickLabel) { act(TodoAction.Toggle(task.id)) }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            TaskWords(task.text, done = task.done, step = step, animate = animate)
            val due = task.due
            if (due != null) {
                val overdue = task.overdue(now)
                Text(
                    dueLine(due, task.dueAllDay, overdue),
                    color = when {
                        task.done -> kb.secondaryText
                        overdue -> overdueColour(kb)
                        kind == DueKind.TODAY -> kb.accent
                        else -> kb.secondaryText
                    },
                    fontSize = 11.sp,
                    fontWeight = if (!task.done && kind != DueKind.NONE) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** The task's words, struck through once it is done; the two states cross-fade rather than snap. */
@Composable
private fun TaskWords(text: String, done: Boolean, step: Boolean, animate: Boolean) {
    val kb = LocalKbTheme.current
    val words: @Composable (Boolean) -> Unit = { struck ->
        Text(
            text,
            color = if (struck) kb.secondaryText else kb.suggestionText,
            fontSize = if (step) 13.sp else 14.sp,
            fontWeight = if (step) FontWeight.Normal else FontWeight.Medium,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            maxLines = if (step) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (animate) {
        Crossfade(targetState = done, animationSpec = tween(STRIKE_MS), label = "todo-strike") { words(it) }
    } else {
        words(done)
    }
}

/**
 * A themed round tick box in a 36 dp hit area; Material3's ignores the
 * keyboard theme. The fill eases to the selected-chip colour and the mark
 * springs in with a little overshoot, the box swelling with it.
 */
@Composable
private fun TickBox(checked: Boolean, small: Boolean, animate: Boolean, description: String, onToggle: () -> Unit) {
    val kb = LocalKbTheme.current
    val size = if (small) 18.dp else 22.dp
    val mark by animateFloatAsState(if (checked) 1f else 0f, if (animate) kitSpringBouncy() else snap(), label = "todo-mark")
    val fill by animateColorAsState(if (checked) kb.chipActive else kb.chip, if (animate) tween(STRIKE_MS) else snap(), label = "todo-fill")
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .toggleable(value = checked, role = Role.Checkbox) { onToggle() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val swell = 1f + TICK_SWELL * sin(mark.coerceIn(0f, 1f) * PI).toFloat()
                    scaleX = swell
                    scaleY = swell
                }
                .clip(CircleShape)
                .background(fill)
                .chipBorder(kb, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = kb.chipActiveText,
                modifier = Modifier
                    .size(size - 6.dp)
                    .graphicsLayer {
                        scaleX = mark
                        scaleY = mark
                        alpha = mark.coerceIn(0f, 1f)
                    },
            )
        }
    }
}

@Composable
private fun AddStepLine(parentId: Long, act: (TodoAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(kb.chipShape())
            .clickable { act(TodoAction.StartStep(parentId)) }
            .padding(start = 34.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.accent)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ime_todo_add_step_action), color = kb.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ---- the way to a new task ----

/**
 * The floating "Add a task…" pill, fading and sliding in and out. A
 * function of its own so the AnimatedVisibility resolves against the Box
 * it floats in and not a Column further out.
 */
@Composable
private fun FloatingAdd(visible: Boolean, animate: Boolean, act: (TodoAction) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animate) fadeIn(tween(PILL_MS)) + slideInVertically(tween(PILL_MS)) { it / 2 } else fadeIn(snap()),
        exit = if (animate) fadeOut(tween(PILL_MS)) + slideOutVertically(tween(PILL_MS)) { it / 2 } else fadeOut(snap()),
    ) {
        AddPill(act, Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp))
    }
}

/** The pill along the bottom: a tap, or its + button, starts a new task. */
@Composable
private fun AddPill(act: (TodoAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val source = remember { MutableInteractionSource() }
    val newTask = {
        feedback()
        act(TodoAction.StartNew)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(AddPillHeight)
            .pressScale(source, kb.motion.any)
            .clip(shape)
            // The board under the chip colour, so a translucent chip theme
            // still reads over the cards scrolling beneath.
            .background(kb.board)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null, onClick = newTask)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ime_todo_add_hint),
            color = kb.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(kb.accent)
                .clickable(onClick = newTask),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.ime_todo_add_action),
                tint = maxContrastOn(kb.accent),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- empty, and unavailable ----

@Composable
private fun EmptyList(ui: TodoUi, act: (TodoAction) -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Checklist,
        title = stringResource(if (ui.doneCount > 0) R.string.ime_todo_empty_hidden else R.string.ime_todo_empty),
        body = stringResource(R.string.ime_todo_empty_hint),
    ) {
        PillButton(
            label = stringResource(R.string.ime_todo_add_action),
            icon = Icons.Outlined.Add,
            primary = true,
        ) { act(TodoAction.StartNew) }
    }
}

@Composable
private fun Message(text: String) {
    val kb = LocalKbTheme.current
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = kb.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---- words for dates and repeats ----

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
    val context = LocalContext.current
    val text = android.text.format.DateUtils.formatDateTime(context, due, flags or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
    return stringResource(if (overdue) R.string.ime_todo_overdue_label else R.string.ime_todo_due_label, text)
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

/** A due card's size: wide enough for a task and its date, short enough for the next to peek. */
private val DueCardWidth = 230.dp
private val DueCardMinHeight = 84.dp

/** The add pill's height, and the room under the last cards so they scroll clear of it. */
private val AddPillHeight = 44.dp
private val AddPillClearance = 64.dp

/** How far a due card's tint goes: a wash, not a fill. */
private const val TINT_ALPHA = 0.16f
private val STRIPE = 4.dp

/** The warmer colour of an overdue card, by theme. */
private val OverdueWarmDark = Color(0xFFF6C177)
private val OverdueWarmLight = Color(0xFFB8690F)

/** A done task's card sits at this alpha. */
private const val DONE_ALPHA = 0.6f

/** How much the tick box swells at the middle of its spring. */
private const val TICK_SWELL = 0.14f

/** Past this many beats the stagger stops growing, so a long list never waits on its tail. */
private const val POP_STAGGER_CAP = 7

private const val STRIKE_MS = 220
private const val PILL_MS = 180
private const val MINUTE_MS = 60_000L
