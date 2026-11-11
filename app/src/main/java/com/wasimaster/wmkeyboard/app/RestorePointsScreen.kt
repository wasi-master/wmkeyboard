package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsHistory
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository.HistoryResult
import com.wasimaster.wmkeyboard.core.settings.undoable
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** How far back the two windowed undo rows reach. */
private const val TEN_MINUTES_MS = 10 * 60 * 1000L
private const val ONE_HOUR_MS = 60 * 60 * 1000L

/** History rows drawn before "Show more"; the screen is a plain column. */
private const val HISTORY_PAGE = 40

/**
 * Data / Backup & restore / Undo and restore points (#1242): take back recent
 * settings changes, and save or go back to named copies of every setting.
 * The store and its rules are [SettingsHistory].
 */
@Composable
internal fun RestorePointsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val history = repository.history
    LaunchedEffect(history) { history.load() }
    val batches by history.batches.collectAsStateWithLifecycle()
    val points by history.points.collectAsStateWithLifecycle()
    val keepHistory = settings.watch { it.settingsHistory }
    val snackbar = LocalSettingsSnackbar.current
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<SettingsHistory.RestorePoint?>(null) }
    var renaming by remember { mutableStateOf<SettingsHistory.RestorePoint?>(null) }
    var deleting by remember { mutableStateOf<SettingsHistory.RestorePoint?>(null) }
    var opened by remember { mutableStateOf<SettingsHistory.Batch?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(HISTORY_PAGE) }
    val redoLabel = stringResource(R.string.restore_redo_action)

    // An undo says what it did on the screen's own bar, with the way back.
    fun report(result: HistoryResult) {
        when (result) {
            is HistoryResult.Applied -> {
                val text = context.resources.getQuantityString(R.plurals.restore_undo_done, result.count, result.count)
                val undo = history.batches.value.firstOrNull()
                if (snackbar != null && undo != null && undo.kind == SettingsHistory.Kind.UNDO) {
                    snackbar.undo(text, redoLabel) { scope.launch { repository.undoSettingsBatch(undo.id) } }
                } else {
                    message = text
                }
            }
            HistoryResult.NothingToDo -> message = context.getString(R.string.restore_nothing_to_undo)
            HistoryResult.Missing -> message = context.getString(R.string.restore_point_missing)
            HistoryResult.RolledBack -> message = context.getString(R.string.restore_rolled_back)
        }
    }

    val last = batches.firstOrNull { it.undoable }
    val now = System.currentTimeMillis()
    val tenMinutes = batches.count { it.undoable && it.at >= now - TEN_MINUTES_MS }
    val hour = batches.count { it.undoable && it.at >= now - ONE_HOUR_MS }

    SettingsGroup(stringResource(R.string.restore_undo_group)) {
        item {
            ActionRow(
                title = R.string.restore_undo_last_title,
                subtitle = last?.let { batchSummary(context, it) + " · " + relativeTime(it.at) }
                    ?: stringResource(if (keepHistory) R.string.restore_undo_none else R.string.restore_history_off_note),
                action = stringResource(R.string.restore_undo_action),
                icon = Icons.Outlined.History,
                enabled = last != null,
            ) { scope.launch { report(repository.undoLastSettingsChange()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.restore_undo_ten_minutes_title,
                subtitle = pluralStringResource(R.plurals.restore_undo_changes_count, tenMinutes, tenMinutes),
                action = stringResource(R.string.restore_undo_action),
                enabled = tenMinutes > 0,
            ) { scope.launch { report(repository.undoSettingsWithin(TEN_MINUTES_MS)) } }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.restore_undo_hour_title,
                subtitle = pluralStringResource(R.plurals.restore_undo_changes_count, hour, hour),
                action = stringResource(R.string.restore_undo_action),
                enabled = hour > 0,
            ) { scope.launch { report(repository.undoSettingsWithin(ONE_HOUR_MS)) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.restore_points_group),
        info = stringResource(R.string.restore_points_info),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.restore_point_save_title),
                subtitle = stringResource(R.string.restore_point_save_subtitle),
                icon = Icons.Outlined.Save,
                highlightKey = R.string.restore_point_save_title,
                onClick = { saving = true },
            )
        }
        if (points.isEmpty()) {
            item { CaptionText(stringResource(R.string.restore_points_empty), Modifier.padding(16.dp)) }
        }
        for (point in points) {
            item(key = point.id) {
                val deleteLabel = stringResource(CommonR.string.common_delete)
                val renameLabel = stringResource(R.string.restore_point_rename_action)
                WmRow(
                    title = pointName(context, point),
                    subtitle = pointSubtitle(context, point),
                    icon = Icons.Outlined.Restore,
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, renameLabel) { renaming = point },
                        deleteSwipeAction(deleteLabel) { deleting = point },
                    ),
                    onLongClick = { renaming = point },
                    onClick = { restoring = point },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.restore_history_group)) {
        item {
            ToggleSetting(
                R.string.restore_history_keep_title,
                stringResource(R.string.restore_history_keep_subtitle),
                keepHistory,
                default = SettingsDefaults.settingsHistory,
            ) { on -> scope.launch { repository.setSettingsHistory(on) } }
        }
        if (keepHistory && batches.isEmpty()) {
            item { CaptionText(stringResource(R.string.restore_history_empty), Modifier.padding(16.dp)) }
        }
        for (batch in batches.take(shown)) {
            item(key = batch.id) {
                WmRow(
                    title = batchSummary(context, batch),
                    subtitle = batchSubtitle(context, batch),
                    enabled = true,
                    onClick = { opened = batch },
                )
            }
        }
        if (batches.size > shown) {
            item {
                TextButton(
                    onClick = { shown += HISTORY_PAGE },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    val more = batches.size - shown
                    Text(pluralStringResource(R.plurals.restore_history_more, more, more))
                }
            }
        }
        if (batches.isNotEmpty()) {
            item {
                ActionRow(
                    title = R.string.restore_history_clear_title,
                    subtitle = null,
                    action = stringResource(R.string.restore_history_clear_action),
                ) { clearing = true }
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    if (saving) {
        NameDialog(
            title = stringResource(R.string.restore_point_save_title),
            initial = "",
            confirm = stringResource(CommonR.string.common_save),
            onDismiss = { saving = false },
        ) { name ->
            saving = false
            scope.launch {
                val point = repository.createRestorePoint(name, BuildConfig.VERSION_NAME)
                message = context.resources.getQuantityString(R.plurals.restore_point_saved, point.count, point.count)
            }
        }
    }

    renaming?.let { point ->
        NameDialog(
            title = stringResource(R.string.restore_point_rename_action),
            initial = point.name.orEmpty(),
            confirm = stringResource(CommonR.string.common_save),
            onDismiss = { renaming = null },
        ) { name ->
            renaming = null
            scope.launch { history.renamePoint(point.id, name) }
        }
    }

    deleting?.let { point ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.restore_point_delete_title)) },
            text = { Text(stringResource(R.string.restore_point_delete_body, pointName(context, point))) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { history.deletePoint(point.id) }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }

    restoring?.let { point -> RestoreDialog(repository, point, onDismiss = { restoring = null }) { result ->
        restoring = null
        message = when (result) {
            is HistoryResult.Applied ->
                context.resources.getQuantityString(R.plurals.restore_point_restored, result.count, result.count)
            HistoryResult.NothingToDo -> context.getString(R.string.restore_point_same)
            HistoryResult.Missing -> context.getString(R.string.restore_point_missing)
            HistoryResult.RolledBack -> context.getString(R.string.restore_rolled_back)
        }
    } }

    opened?.let { batch ->
        BatchDialog(context, batch, onDismiss = { opened = null }) {
            opened = null
            scope.launch { report(repository.undoSettingsBatch(batch.id)) }
        }
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.restore_history_clear_title)) },
            text = { Text(stringResource(R.string.restore_history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    scope.launch { history.clearHistory() }
                }) { Text(stringResource(R.string.restore_history_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/** Asks before a restore, with how many settings it changes, worked out against the settings as they are now. */
@Composable
private fun RestoreDialog(
    repository: SettingsRepository,
    point: SettingsHistory.RestorePoint,
    onDismiss: () -> Unit,
    onDone: (HistoryResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember(point.id) { mutableStateOf<SettingsHistory.Plan?>(null) }
    var missing by remember(point.id) { mutableStateOf(false) }
    LaunchedEffect(point.id) {
        val p = repository.restorePointPlan(point.id)
        if (p == null) missing = true else plan = p
    }
    val p = plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_point_restore_title, pointName(context, point))) },
        text = {
            Text(
                when {
                    missing -> stringResource(R.string.restore_point_missing)
                    p == null -> stringResource(R.string.restore_point_counting)
                    p.isEmpty -> stringResource(R.string.restore_point_same)
                    else -> pluralStringResource(R.plurals.restore_point_restore_body, p.size, p.size)
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = p != null && !p.isEmpty,
                onClick = {
                    scope.launch { onDone(repository.restoreRestorePoint(point.id, BuildConfig.VERSION_NAME)) }
                },
            ) { Text(stringResource(R.string.restore_point_restore_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** One batch's changes in full, each as "name: before → after", with the undo for that batch alone. */
@Composable
private fun BatchDialog(
    context: Context,
    batch: SettingsHistory.Batch,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(batchSubtitle(context, batch)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (batch.undone) {
                    Text(
                        stringResource(R.string.restore_batch_undone_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                for (change in batch.changes) {
                    Text(
                        settingName(change.key),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            R.string.restore_change_line,
                            valueText(context, change.before),
                            valueText(context, change.after),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !batch.undone, onClick = onUndo) {
                Text(
                    stringResource(
                        if (batch.kind == SettingsHistory.Kind.UNDO) R.string.restore_redo_action else R.string.restore_undo_action,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                label = { Text(stringResource(R.string.restore_point_name_label)) },
                placeholder = { Text(stringResource(R.string.restore_point_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private fun dateTime(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

private fun relativeTime(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun pointName(context: Context, point: SettingsHistory.RestorePoint): String = point.name ?: when (point.kind) {
    SettingsHistory.PointKind.BEFORE_RESTORE ->
        context.getString(R.string.restore_point_before_restore, point.label.orEmpty())
    SettingsHistory.PointKind.MANUAL -> dateTime(point.at)
}

private fun pointSubtitle(context: Context, point: SettingsHistory.RestorePoint): String {
    val count = context.resources.getQuantityString(R.plurals.restore_point_count, point.count, point.count)
    return context.getString(R.string.restore_point_subtitle, dateTime(point.at), count)
}

/**
 * A batch in a line: the one setting it changed, or how many, or the point it
 * restored. Names are the stored key made readable ("key_height" reads "Key
 * height"); the history is a record of the store, and the store has no titles.
 */
private fun batchSummary(context: Context, batch: SettingsHistory.Batch): String {
    val count = batch.changes.size
    return when {
        batch.kind == SettingsHistory.Kind.RESTORE ->
            context.getString(R.string.restore_batch_restored, batch.label.orEmpty())
        batch.kind == SettingsHistory.Kind.UNDO ->
            context.resources.getQuantityString(R.plurals.restore_batch_undo, count, count)
        count == 1 -> context.getString(
            R.string.restore_batch_one,
            settingName(batch.changes[0].key),
            valueText(context, batch.changes[0].after),
        )
        else -> context.resources.getQuantityString(
            R.plurals.restore_batch_many, count, count, settingName(batch.changes[0].key),
        )
    }
}

private fun batchSubtitle(context: Context, batch: SettingsHistory.Batch): String {
    val time = relativeTime(batch.at)
    return if (batch.undone) context.getString(R.string.restore_batch_undone, time) else time
}

/** "key_height_landscape" → "Key height landscape". */
internal fun settingName(key: String): String =
    key.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

private const val VALUE_PREVIEW_CHARS = 40

private fun valueText(context: Context, entry: SettingsBackup.Entry?): String {
    val value = entry?.value ?: return context.getString(R.string.restore_value_default)
    return when (value) {
        is Boolean -> context.getString(if (value) R.string.restore_value_on else R.string.restore_value_off)
        is Float -> "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        is Double -> "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        is Int, is Long -> "%d".format(value)
        is Set<*> -> context.resources.getQuantityString(R.plurals.restore_value_items, value.size, value.size)
        is String -> when {
            value.isEmpty() -> context.getString(R.string.restore_value_empty)
            value.length > VALUE_PREVIEW_CHARS || value.contains('\n') ->
                context.resources.getQuantityString(R.plurals.restore_value_text, value.length, value.length)
            else -> "“$value”"
        }
        else -> value.toString()
    }
}
