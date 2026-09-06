package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.ime.ui.ModeIcons
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.DefaultKeyboardModes
import com.wasimaster.wmkeyboard.core.settings.ManualModeDuration
import com.wasimaster.wmkeyboard.core.settings.SymbolRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.ModeField
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ---- rows & bars ----

@StringRes
private fun barRowTitle(row: BarRow): Int = when (row) {
    BarRow.TOPBAR -> R.string.rows_bar_topbar_title
    BarRow.EMOJI -> R.string.rows_bar_emoji_title
    BarRow.SYMBOL -> R.string.rows_symbol_row_title
}
@StringRes
private fun barRowSubtitle(row: BarRow, settings: KeyboardSettings): Int = when (row) {
    BarRow.TOPBAR -> R.string.rows_bar_topbar_subtitle
    BarRow.EMOJI -> when (settings.emojiBarMode) {
        EmojiBarMode.OFF -> R.string.rows_bar_emoji_off_subtitle
        EmojiBarMode.BUTTON -> R.string.rows_bar_emoji_button_subtitle
        EmojiBarMode.ALWAYS -> R.string.rows_bar_emoji_always_subtitle
    }
    BarRow.SYMBOL -> if (settings.symbolRowEnabled) {
        CommonR.string.common_on
    } else {
        CommonR.string.common_off
    }
}
/** Row layout above the keys: symbol row, row order and symbol sets. */
@Composable
internal fun RowsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Resolved before the group: its builder is a plain lambda, and the drag
    // list takes a plain (T) -> String.
    val order = settings.barOrder
    val rowNames = order.associateWith { stringResource(barRowTitle(it)) }
    // The top bar stays where it is, as the arrows used to keep it: only the
    // rows under it trade places.
    val pinnedTop = order.firstOrNull()?.takeIf { it == BarRow.TOPBAR }
    val movable = if (pinnedTop != null) order.drop(1) else order
    SettingsGroup(
        stringResource(R.string.rows_row_order_title),
        info = stringResource(R.string.rows_row_order_caption),
    ) {
        if (pinnedTop != null) {
            item {
                WmRow(
                    title = rowNames[pinnedTop].orEmpty(),
                    subtitle = stringResource(barRowSubtitle(pinnedTop, settings)),
                )
            }
        }
        item {
            ReorderableColumn(
                movable,
                label = { rowNames[it].orEmpty() },
                onReorder = { next ->
                    scope.launch { repository.setBarOrder(listOfNotNull(pinnedTop) + next) }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    SettingsGroup(stringResource(R.string.rows_symbol_row_title)) {
        item {
            ToggleSetting(
                R.string.rows_symbol_row_title,
                stringResource(R.string.rows_symbol_row_subtitle),
                settings.symbolRowEnabled,
                info = stringResource(R.string.rows_symbol_row_info),
                default = SettingsDefaults.symbolRowEnabled,
            ) { scope.launch { repository.setSymbolRowEnabled(it) } }
        }
        if (settings.symbolRowEnabled) {
            item {
                val dpFormat = stringResource(R.string.typing_value_dp)
                SliderSetting(
                    R.string.rows_symbol_row_height_title,
                    subtitle = stringResource(R.string.rows_symbol_row_height_subtitle),
                    value = settings.rows.symbolRowHeightDp.toFloat(),
                    range = SymbolRowHeightRange.first.toFloat()..SymbolRowHeightRange.last.toFloat(),
                    display = { dpFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.rows_symbol_row_height_info),
                    default = SettingsDefaults.rows.symbolRowHeightDp.toFloat(),
                ) { scope.launch { repository.setSymbolRowHeightDp(it.roundToInt()) } }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.rows_symbol_sets_title),
        info = stringResource(R.string.rows_symbol_sets_caption),
    ) {
        val allSets = resolveSymbolSets(settings.customSymbolSets)
        for (set in allSets) {
            item {
                val enabled = set.id in settings.symbolRowSetIds
                val edited = settings.customSymbolSets.any { it.id == set.id }
                val builtIn = BuiltInSymbolSets.byId(set.id) != null
                // A shipped set the user has not renamed draws its translated
                // name; anything the user named draws that name as typed.
                val shippedNameRes = BuiltInSymbolSets.nameRes(set)
                val setName = if (shippedNameRes != null) {
                    stringResource(shippedNameRes)
                } else {
                    set.name
                }
                WmRow(
                    title = setName,
                    supporting = {
                        Text(
                            set.chars.take(8).joinToString(" ") + if (set.chars.size > 8) " …" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leading = {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { on ->
                                val next = if (on) {
                                    settings.symbolRowSetIds + set.id
                                } else {
                                    settings.symbolRowSetIds - set.id
                                }
                                // At least one set stays enabled — an empty row
                                // would have nothing to show.
                                if (next.isNotEmpty()) {
                                    scope.launch { repository.setSymbolRowSetIds(next) }
                                }
                            },
                        )
                    },
                    // Every set is editable now, built-ins included: editing
                    // one stores an override under the same id, so modes that
                    // reference it keep working and "Reset" brings it back.
                    trailing = {
                        IconButton(onClick = { onNavigate("symbol_set_edit/${set.id}") }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    if (builtIn && !edited) {
                                        R.string.rows_symbol_set_edit_builtin_desc
                                    } else {
                                        R.string.rows_symbol_set_edit_desc
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
    RegisterAddFab(stringResource(R.string.rows_symbol_set_new_title)) {
        onNavigate("symbol_set_edit/custom_${System.currentTimeMillis()}")
    }
}
/**
 * Create or edit one symbol set, built-ins included. Editing a built-in
 * saves an override stored under the same id, so anything referencing that
 * id (a mode's pinned sets, the row's active set) keeps pointing at it and
 * "Reset" simply drops the override to bring the shipped set back.
 */
@Composable
internal fun SymbolSetEditor(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    setId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.customSymbolSets.firstOrNull { it.id == setId }
    val builtIn = BuiltInSymbolSets.byId(setId)
    val existing = override ?: builtIn
    var name by remember(setId) { mutableStateOf(existing?.name.orEmpty()) }
    var charsText by remember(setId) { mutableStateOf(existing?.chars?.joinToString(" ").orEmpty()) }
    if (builtIn != null) {
        // The stored English name is what a shipped set is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        val shippedNameRes = BuiltInSymbolSets.nameRes(builtIn)
        val shippedName = if (shippedNameRes != null) {
            stringResource(shippedNameRes)
        } else {
            builtIn.name
        }
        StateBanner(stringResource(R.string.rows_symbol_set_builtin_caption, shippedName))
    }
    val defaultSetName = stringResource(R.string.rows_symbol_set_default_name)
    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rows_symbol_set_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            OutlinedTextField(
                value = charsText,
                onValueChange = { charsText = it },
                label = { Text(stringResource(R.string.rows_symbol_set_chars_label)) },
                supportingText = {
                    Text(stringResource(R.string.rows_symbol_set_chars_hint))
                },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Only an existing stored set can be removed — and for a built-in
        // that removal is a reset, not a delete.
        if (override != null) {
            TextButton(onClick = {
                scope.launch {
                    repository.deleteSymbolSet(setId)
                }
                onDone()
            }) {
                Icon(
                    if (builtIn != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (builtIn != null) {
                            R.string.rows_symbol_set_reset_action
                        } else {
                            R.string.rows_symbol_set_delete_action
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            enabled = charsText.isNotBlank(),
            onClick = {
                val chars = charsText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                scope.launch {
                    repository.upsertSymbolSet(
                        SymbolSet(
                            setId,
                            name.trim().ifEmpty { builtIn?.name ?: defaultSetName },
                            chars,
                        ),
                    )
                    // A new set should show up in the row right away.
                    if (setId !in settings.symbolRowSetIds) {
                        repository.setSymbolRowSetIds(settings.symbolRowSetIds + setId)
                    }
                }
                onDone()
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
}
// ---- keyboard modes ----

/**
 * One-line recap of a mode's bindings for the list screen.
 *
 * The parts are joined, so nothing here may be re-cased afterwards: the first
 * letter of a translated word is not ours to change. The lower-case field
 * names are their own resources for the same reason.
 */
@Composable
private fun modeBindingsSummary(mode: KeyboardMode): String {
    val resources = LocalContext.current.resources
    val parts = mutableListOf<String>()
    if (mode.apps.isNotEmpty()) {
        parts += resources.getQuantityString(
            R.plurals.rows_mode_bindings_apps, mode.apps.size, mode.apps.size,
        )
    }
    if (mode.fieldKinds.isNotEmpty()) {
        parts += resources.getString(
            R.string.rows_mode_bindings_fields,
            mode.fieldKinds.joinToString(", ") {
                resources.getString(modeFieldLowercaseLabel(it))
            },
        )
    }
    // " + " rather than " · ": with both set, both have to match.
    return if (parts.isEmpty()) {
        resources.getString(R.string.rows_mode_bindings_manual)
    } else {
        resources.getString(R.string.rows_mode_bindings_auto, parts.joinToString(" + "))
    }
}
@Composable
private fun modeFieldLabel(field: ModeField): String = stringResource(
    when (field) {
        ModeField.PASSWORD -> R.string.rows_mode_field_password_label
        ModeField.EMAIL -> R.string.rows_mode_field_email_label
        ModeField.URL -> R.string.rows_mode_field_url_label
        ModeField.NUMBER -> R.string.rows_mode_field_number_label
        ModeField.PHONE -> R.string.rows_mode_field_phone_label
        ModeField.TEXT -> R.string.rows_mode_field_text_label
        ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_label
    },
)
/** The same names, written the way they read inside a sentence. */
@StringRes
private fun modeFieldLowercaseLabel(field: ModeField): Int = when (field) {
    ModeField.PASSWORD -> R.string.rows_mode_field_password_lowercase_label
    ModeField.EMAIL -> R.string.rows_mode_field_email_lowercase_label
    ModeField.URL -> R.string.rows_mode_field_url_lowercase_label
    ModeField.NUMBER -> R.string.rows_mode_field_number_lowercase_label
    ModeField.PHONE -> R.string.rows_mode_field_phone_lowercase_label
    ModeField.TEXT -> R.string.rows_mode_field_text_lowercase_label
    ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_lowercase_label
}
/** Row height inside [ReorderableColumn] — fixed, so drags map to index shifts. */
private val ReorderRowHeight = 52.dp

/**
 * A list the user drags into order, in place. Rows carry a handle on the
 * right; dragging one past the next row's height swaps the two, so the item
 * tracks the finger and the list settles as it goes. Every swap reaches the
 * caller through [onReorder] with the whole new order — the column holds no
 * order of its own, only which row is mid-drag.
 *
 * Deliberately not a LazyColumn: every row has to stay composed for a drag
 * to swap past it, and these lists are short enough that laying them all out
 * is free.
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    label: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // -1 = nothing being dragged.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ReorderRowHeight.toPx() }
    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            val dragging = index == dragIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ReorderRowHeight)
                    // The dragged row rides above its neighbours.
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
            ) {
                Text(
                    stringResource(R.string.rows_reorder_position_label, index + 1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    label(item),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = stringResource(
                        R.string.rows_reorder_handle_desc, label(item),
                    ),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(28.dp)
                        // Keyed on Unit so a swap mid-drag never restarts the
                        // gesture: slot `index` is fixed for the life of the
                        // row, only the item in it moves. `dragIndex` is the
                        // live position.
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    dragIndex = index
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                },
                            ) { change, drag ->
                                change.consume()
                                dragOffset += drag.y
                                val from = dragIndex
                                val to = from + (dragOffset / rowPx).roundToInt()
                                if (from >= 0 && to != from && to in items.indices) {
                                    onReorder(items.toMutableList().apply { add(to, removeAt(from)) })
                                    dragIndex = to
                                    // Keep the offset relative to the row's new
                                    // home, or the item would jump a full row.
                                    dragOffset -= (to - from) * rowPx
                                }
                            }
                        },
                )
            }
        }
    }
}

/**
 * [ReorderableColumn] in a dialog, for the callers that still open one. The
 * working copy only reaches the caller through [onConfirm] — backing out
 * leaves the stored order alone.
 */
@Composable
internal fun <T> ReorderDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onConfirm: (List<T>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(items) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CaptionText(stringResource(R.string.rows_reorder_caption))
                ReorderableColumn(working, label, onReorder = { working = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * A "Reorder…" row that opens a [ReorderDialog]. Disabled with a nudge when
 * there is nothing to reorder yet.
 */
@Composable
internal fun <T> ReorderSetting(
    title: String,
    dialogTitle: String,
    items: List<T>,
    label: (T) -> String,
    onReordered: (List<T>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val enabled = items.size > 1
    WmRow(
        title = title,
        subtitle = if (enabled) {
                items.joinToString(" · ", limit = 4) { label(it) }
            } else {
                stringResource(R.string.rows_reorder_empty_subtitle)
            },
        trailing = { Icon(Icons.Outlined.DragHandle, contentDescription = null) },
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        ReorderDialog(
            title = dialogTitle,
            items = items,
            label = label,
            onConfirm = {
                open = false
                onReordered(it)
            },
            onDismiss = { open = false },
        )
    }
}
/** A wrapping row of tool chips, used for a mode's pins and toolbox order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChips(
    tools: List<ToolbarTool>,
    selected: List<ToolbarTool>,
    onToggle: (ToolbarTool) -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tool in tools) {
            FilterChip(
                selected = tool in selected,
                onClick = { onToggle(tool) },
                label = { Text(stringResource(toolTitle(tool)), maxLines = 1) },
            )
        }
    }
}
/** The modes list: tap to edit, plus creating a new mode. */
@Composable
internal fun ModesSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val deleteModeDesc = stringResource(R.string.modes_delete_action)
    // A mode is a screenful of bindings and overrides that took real effort to
    // set up, and the delete button sits on the row you tap to open it. Both
    // delete paths ask first; the editor's own button does the same below.
    var confirmDelete by remember { mutableStateOf<KeyboardMode?>(null) }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.modes_enabled_title,
                stringResource(R.string.modes_enabled_subtitle),
                settings.modesEnabled,
                info = stringResource(R.string.modes_intro_body) + "\n\n" +
                    stringResource(R.string.modes_enabled_info),
                default = SettingsDefaults.modesEnabled,
            ) { scope.launch { repository.setModesEnabled(it) } }
        }
    }
    // The rest of the screen is what modes do, so it is only worth drawing
    // while they are on. The list itself stays: switching the feature off
    // keeps every mode, and hiding them would read as having deleted them.
    if (!settings.modesEnabled) {
        StateBanner(
            stringResource(R.string.modes_disabled_body),
            action = stringResource(CommonR.string.common_enable),
        ) { scope.launch { repository.setModesEnabled(true) } }
    }
    SettingsGroup {
        item {
            ChoiceSetting(
                R.string.modes_manual_duration_title,
                subtitle = stringResource(R.string.modes_manual_duration_subtitle),
                options = listOf(
                    ManualModeDuration.UNTIL_APP_CHANGES to
                        stringResource(R.string.modes_manual_duration_app_label),
                    ManualModeDuration.UNTIL_CHANGED to
                        stringResource(R.string.modes_manual_duration_changed_label),
                ),
                selected = settings.rows.manualModeDuration,
                info = stringResource(R.string.modes_manual_duration_info),
                default = SettingsDefaults.rows.manualModeDuration,
                detail = { duration ->
                    ChoiceDetail(
                        stringResource(
                            if (duration == ManualModeDuration.UNTIL_CHANGED) {
                                R.string.modes_manual_duration_changed_desc
                            } else {
                                R.string.modes_manual_duration_app_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setManualModeDuration(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.modes_group_title)) {
        for (mode in settings.keyboardModes) {
            item {
                WmRow(
                    title = mode.name,
                    subtitle = modeBindingsSummary(mode),
                    leading = {
                        Icon(ModeIcons.icon(mode.icon), contentDescription = null)
                    },
                    trailing = {
                        IconButton(onClick = { confirmDelete = mode }) {
                            Icon(Icons.Outlined.Delete, contentDescription = deleteModeDesc)
                        }
                    },
                    onClick = { onNavigate("mode_edit/${mode.id}") },
                )
            }
        }
    }
    RegisterAddFab(stringResource(R.string.modes_new_title)) {
        onNavigate("mode_edit/mode_custom_${System.currentTimeMillis()}")
    }
    SettingsGroup(stringResource(R.string.modes_rearrange_group_title)) {
        item {
            ToggleSetting(
                R.string.modes_drag_edits_title,
                stringResource(R.string.modes_drag_edits_subtitle),
                settings.modeToolOrderEdits,
                info = stringResource(R.string.modes_drag_edits_info) + "\n\n" +
                    stringResource(R.string.modes_tool_order_body),
                default = SettingsDefaults.modeToolOrderEdits,
            ) { scope.launch { repository.setModeToolOrderEdits(it) } }
        }
    }
    confirmDelete?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, mode.name)) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { repository.deleteKeyboardMode(mode.id) }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/** Everything one mode overrides, and when it activates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeEditor(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    modeId: String,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Resolved up here: the name lands in stored settings from a plain lambda,
    // which is no place for stringResource().
    val newModeName = stringResource(R.string.modes_new_default_name)
    val unnamedModeName = stringResource(R.string.modes_unnamed_name)
    val mode = settings.keyboardModes.firstOrNull { it.id == modeId }
        ?: KeyboardMode(modeId, newModeName)
    // A brand-new mode is only persisted on its first edit — backing out of
    // an untouched editor leaves nothing behind.
    val save: (KeyboardMode) -> Unit = { scope.launch { repository.upsertKeyboardMode(it) } }
    // Only the shipped modes can be reset — a user-made mode has no default to
    // fall back to. Matched by id so an edited built-in still offers it.
    val builtInDefault = DefaultKeyboardModes.firstOrNull { it.id == modeId }
    var confirmReset by remember { mutableStateOf(false) }
    // Deleting a mode throws away a screenful of bindings and overrides, and
    // the button sits beside the reset one, which does not. Ask first.
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsGroup {
        item {
            TextFieldSetting(
                label = stringResource(R.string.modes_name_label),
                value = mode.name,
                hint = stringResource(R.string.modes_name_hint),
            ) {
                repository.upsertKeyboardMode(
                    mode.copy(name = it.trim().ifEmpty { unnamedModeName }),
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_icon_title),
                subtitle = stringResource(R.string.modes_icon_subtitle),
                leading = {
                    Icon(ModeIcons.icon(mode.icon), contentDescription = null)
                },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                ModeIconPickerDialog(
                    selected = mode.icon,
                    onPick = { id ->
                        pickerOpen = false
                        save(mode.copy(icon = id))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
    val themeOverrideNote = stringResource(R.string.modes_theme_override_body).takeIf { mode.themeId != null }
    SettingsGroup(
        stringResource(R.string.modes_changes_group_title),
        info = listOfNotNull(
            themeOverrideNote,
            stringResource(R.string.modes_toolbox_order_body),
            stringResource(R.string.modes_symbol_set_order_body),
        ).joinToString("\n\n"),
    ) {
        item {
            ChoiceSetting(
                title = R.string.modes_emoji_row_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    EmojiBarMode.OFF to stringResource(CommonR.string.common_off),
                    EmojiBarMode.BUTTON to stringResource(R.string.modes_emoji_row_button_label),
                    EmojiBarMode.ALWAYS to stringResource(R.string.modes_emoji_row_row_label),
                ),
                selected = mode.emojiBarMode,
                detail = { barMode -> ChoiceDetail(stringResource(modeEmojiRowDescRes(barMode))) },
            ) { save(mode.copy(emojiBarMode = it)) }
        }
        item {
            ChoiceSetting(
                title = R.string.modes_symbol_row_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = mode.symbolRowEnabled,
            ) { save(mode.copy(symbolRowEnabled = it)) }
        }
        // Typing behaviour. A mode dressed the keyboard but never changed what
        // it did to the text, so Coding mode still corrected identifiers into
        // English words — the one thing a mode for a code editor is for.
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocorrect_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.autocorrect,
            ) { save(mode.copy(autocorrect = it)) }
        }
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocapitalize_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.autoCapitalize,
            ) { save(mode.copy(autoCapitalize = it)) }
        }
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_suggestions_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.suggestions,
            ) { save(mode.copy(suggestions = it)) }
        }
        // Only the layouts the user actually has switched on: a mode naming one
        // they have since removed would pin the keyboard to something that
        // cannot be drawn, which applyMode also guards against at read time.
        item {
            val layoutOptions = listOf(
                null to stringResource(R.string.modes_inherit_label),
            ) + settings.enabledLayoutIds.map { id ->
                id to resolveLayout(settings.customLayouts, id).name
            }
            ChoiceSetting(
                title = R.string.modes_layout_title,
                subtitle = stringResource(R.string.modes_layout_subtitle),
                options = layoutOptions,
                selected = mode.layoutId?.takeIf { it in settings.enabledLayoutIds },
                info = stringResource(R.string.modes_layout_info),
            ) { save(mode.copy(layoutId = it)) }
        }
        item {
            var themePickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_theme_title),
                subtitle = mode.themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.modes_theme_inherit_subtitle),
                trailing = {
                    if (mode.themeId != null) {
                        TextButton(onClick = { save(mode.copy(themeId = null)) }) {
                            Text(stringResource(CommonR.string.common_clear))
                        }
                    }
                },
                onClick = { themePickerOpen = true },
            )
            if (themePickerOpen) {
                ModeThemePickerDialog(
                    settings = settings,
                    selectedId = mode.themeId,
                    onPick = { id ->
                        themePickerOpen = false
                        save(mode.copy(themeId = id))
                    },
                    onDismiss = { themePickerOpen = false },
                )
            }
        }
        item {
            ToggleSetting(
                R.string.modes_pinned_tools_title,
                stringResource(R.string.modes_pinned_tools_subtitle),
                mode.toolbarTools != null,
            ) { on ->
                save(
                    mode.copy(
                        // Appending starts from nothing (the user's own pins
                        // are already there); replacing starts from a copy of
                        // the current toolbar to edit down.
                        toolbarTools = if (on) {
                            if (mode.toolbarToolsAppend) emptyList() else settings.toolbarTools
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        val pinned = mode.toolbarTools
        if (pinned != null) {
            item {
                ChoiceSetting(
                    title = R.string.modes_pinned_behaviour_title,
                    subtitle = if (mode.toolbarToolsAppend) {
                        stringResource(R.string.modes_pinned_behaviour_append_subtitle)
                    } else {
                        stringResource(R.string.modes_pinned_behaviour_replace_subtitle)
                    },
                    options = listOf(
                        true to stringResource(R.string.modes_pinned_behaviour_append_label),
                        false to stringResource(R.string.modes_pinned_behaviour_replace_label),
                    ),
                    selected = mode.toolbarToolsAppend,
                    detail = { append ->
                        ChoiceDetail(
                            stringResource(
                                if (append) {
                                    R.string.modes_pinned_behaviour_append_desc
                                } else {
                                    R.string.modes_pinned_behaviour_replace_desc
                                },
                            ),
                        )
                    },
                ) { append ->
                    // Switching to append: the copied-in global pins would
                    // duplicate what is already on the toolbar, so drop them.
                    save(
                        mode.copy(
                            toolbarToolsAppend = append,
                            toolbarTools = if (append) pinned - settings.toolbarTools.toSet() else pinned,
                        ),
                    )
                }
            }
            item {
                ToolChips(
                    tools = ToolbarTool.entries.filter {
                        it in settings.enabledTools && isSupportedTool(it) &&
                            isUsableTool(it, settings)
                    },
                    selected = pinned,
                ) { tool ->
                    save(
                        mode.copy(
                            toolbarTools = if (tool in pinned) pinned - tool else pinned + tool,
                        ),
                    )
                }
            }
            item {
                // toolTitle() hands back a resource id, and the reorder dialog
                // takes a plain (T) -> String, so the names are resolved here.
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in pinned) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderableColumn(
                    pinned,
                    label = { toolNames[it].orEmpty() },
                    onReorder = { save(mode.copy(toolbarTools = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.modes_toolbox_order_title,
                stringResource(R.string.modes_toolbox_order_subtitle),
                mode.toolboxOrder != null,
            ) { on ->
                save(mode.copy(toolboxOrder = if (on) emptyList() else null))
            }
        }
        val order = mode.toolboxOrder
        if (order != null) {
            item {
                ToolChips(
                    tools = settings.toolboxOrder.filter {
                        it in settings.enabledTools && isSupportedTool(it) &&
                            isUsableTool(it, settings)
                    },
                    selected = order,
                ) { tool ->
                    save(
                        mode.copy(
                            toolboxOrder = if (tool in order) order - tool else order + tool,
                        ),
                    )
                }
            }
            item {
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in order) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderableColumn(
                    order,
                    label = { toolNames[it].orEmpty() },
                    onReorder = { save(mode.copy(toolboxOrder = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.modes_symbol_sets_title,
                stringResource(R.string.modes_symbol_sets_subtitle),
                mode.symbolSetIds != null,
            ) { on ->
                save(
                    mode.copy(
                        symbolSetIds = if (on) {
                            settings.symbolRowSetIds.ifEmpty { BuiltInSymbolSets.defaultEnabledIds }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        val modeSets = mode.symbolSetIds
        if (modeSets != null) {
            item {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (set in resolveSymbolSets(settings.customSymbolSets)) {
                        // A shipped set that still carries its shipped name is
                        // drawn from resources; a renamed one keeps the name
                        // the user typed.
                        val setLabel = BuiltInSymbolSets.nameRes(set)
                            ?.let { stringResource(it) } ?: set.name
                        FilterChip(
                            selected = set.id in modeSets,
                            onClick = {
                                val next =
                                    if (set.id in modeSets) modeSets - set.id else modeSets + set.id
                                if (next.isNotEmpty()) save(mode.copy(symbolSetIds = next))
                            },
                            label = { Text(setLabel, maxLines = 1) },
                        )
                    }
                }
            }
            item {
                val setNames = mutableMapOf<String, String>()
                for (set in resolveSymbolSets(settings.customSymbolSets)) {
                    setNames[set.id] = BuiltInSymbolSets.nameRes(set)
                        ?.let { stringResource(it) } ?: set.name
                }
                val setName = { id: String -> setNames[id] ?: id }
                ReorderableColumn(
                    modeSets,
                    label = setName,
                    onReorder = { save(mode.copy(symbolSetIds = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
    val bothMatchNote = stringResource(R.string.modes_auto_both_match_body)
        .takeIf { mode.apps.isNotEmpty() && mode.fieldKinds.isNotEmpty() }
    SettingsGroup(
        stringResource(R.string.modes_auto_group_title),
        info = listOfNotNull(stringResource(R.string.modes_matching_body), bothMatchNote)
            .joinToString("\n\n"),
    ) {
        item {
            Text(
                stringResource(R.string.modes_field_types_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (field in ModeField.entries) {
                    FilterChip(
                        selected = field in mode.fieldKinds,
                        onClick = {
                            save(
                                mode.copy(
                                    fieldKinds =
                                        if (field in mode.fieldKinds) mode.fieldKinds - field
                                        else mode.fieldKinds + field,
                                ),
                            )
                        },
                        label = { Text(modeFieldLabel(field), maxLines = 1) },
                    )
                }
            }
        }
        for (pkg in mode.apps) {
            item {
                val context = LocalContext.current
                val label = remember(pkg) {
                    runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0),
                        ).toString()
                    }.getOrDefault(pkg)
                }
                WmRow(
                    title = label,
                    supporting = if (label != pkg) {
                        { Text(pkg) }
                    } else null,
                    trailing = {
                        IconButton(onClick = { save(mode.copy(apps = mode.apps - pkg)) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.modes_app_remove_desc),
                            )
                        }
                    },
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_add_app_title),
                subtitle = if (mode.fieldKinds.isEmpty()) {
                        stringResource(R.string.modes_add_app_subtitle_any)
                    } else {
                        stringResource(R.string.modes_add_app_subtitle_fields)
                    },
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                AppPickerDialog(
                    exclude = mode.apps,
                    onPick = { pkg ->
                        pickerOpen = false
                        save(mode.copy(apps = mode.apps + pkg))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (builtInDefault != null) {
            TextButton(onClick = { confirmReset = true }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.modes_reset_default_action))
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.modes_delete_action))
        }
    }
    if (confirmReset && builtInDefault != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = {
                Text(stringResource(R.string.modes_reset_confirm_title, builtInDefault.name))
            },
            text = { Text(stringResource(R.string.modes_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { repository.resetKeyboardModeToDefault(modeId) }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, mode.name)) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repository.deleteKeyboardMode(modeId) }
                    onDeleted()
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * Picks a mode's icon from [ModeIcons.catalog]. Chips rather than a grid of
 * bare icons: the selected state comes styled and the touch targets land on
 * the same size the rest of the settings use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeIconPickerDialog(
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_icon_picker_title)) },
        text = {
            FlowRow(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((id, vector) in ModeIcons.catalog) {
                    FilterChip(
                        selected = id == selected,
                        onClick = { onPick(id) },
                        label = {
                            Icon(vector, contentDescription = id, modifier = Modifier.size(22.dp))
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** Picks one installed app (launcher activities) for a mode binding. */
@Composable
private fun AppPickerDialog(
    exclude: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val shown = apps.filter { (pkg, label) ->
        pkg !in exclude &&
            (query.isBlank() || label.contains(query, ignoreCase = true) ||
                pkg.contains(query, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_app_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(CommonR.string.common_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.first }) { (pkg, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(pkg) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What a mode does to the emoji row, one line, for the picker sheet. Null is
 * the "inherit" option: the mode says nothing and the Emoji screen decides.
 */
private fun modeEmojiRowDescRes(mode: EmojiBarMode?): Int = when (mode) {
    null -> R.string.modes_emoji_row_inherit_desc
    EmojiBarMode.OFF -> R.string.modes_emoji_row_off_desc
    EmojiBarMode.BUTTON -> R.string.modes_emoji_row_button_desc
    EmojiBarMode.ALWAYS -> R.string.modes_emoji_row_row_desc
}
