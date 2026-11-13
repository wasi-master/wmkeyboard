package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataObject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyRowCodec
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.ui.arrowRowKey
import kotlinx.coroutines.launch

/**
 * The keyboard-wide rows: one number row and one arrow row, set once and drawn
 * by every layout that has not written a row of its own (asked for in the
 * Telegram group on 0.6.5's release day, by someone who had set the same
 * digits in every language's layout).
 */
internal enum class GlobalRow { NUMBER, ARROW }

internal fun globalRowRoute(row: GlobalRow): String = "global_row/${row.name}"

internal fun globalRowJsonRoute(row: GlobalRow): String = "global_row_json/${row.name}"

/** The row a route argument names; an unknown one falls back to the number row. */
internal fun globalRowOf(arg: String?): GlobalRow = GlobalRow.entries.firstOrNull { it.name == arg } ?: GlobalRow.NUMBER

@StringRes
internal fun GlobalRow.titleRes(): Int = when (this) {
    GlobalRow.NUMBER -> R.string.layout_custom_number_row_title
    GlobalRow.ARROW -> R.string.layout_custom_arrow_row_title
}

@StringRes
private fun GlobalRow.captionRes(): Int = when (this) {
    GlobalRow.NUMBER -> R.string.layout_custom_number_row_caption
    GlobalRow.ARROW -> R.string.layout_custom_arrow_row_caption
}

@StringRes
private fun GlobalRow.keyTitleRes(): Int = when (this) {
    GlobalRow.NUMBER -> R.string.layout_editor_number_row_key_title
    GlobalRow.ARROW -> R.string.layout_editor_arrow_row_key_title
}

@StringRes
private fun GlobalRow.jsonTitleRes(): Int = when (this) {
    GlobalRow.NUMBER -> R.string.layout_custom_number_row_json_title
    GlobalRow.ARROW -> R.string.layout_custom_arrow_row_json_title
}

/** The row the user set, or null while the built-in one is in use. */
internal fun GlobalRow.stored(settings: KeyboardSettings): List<Key>? = when (this) {
    GlobalRow.NUMBER -> settings.layoutBehavior.customNumberRow
    GlobalRow.ARROW -> settings.layoutBehavior.customArrowRow
}

/** What the keyboard draws while nothing is stored: the digits, or the four arrows in their set order. */
private fun GlobalRow.builtIn(settings: KeyboardSettings): List<Key> = when (this) {
    GlobalRow.NUMBER -> BuiltInLayouts.defaultNumberRow(LayoutLayer.LETTERS)
    GlobalRow.ARROW -> settings.layoutBehavior.arrowRowOrder.map(::arrowRowKey)
}

/** Saves [keys] as this row; null or empty puts the built-in row back. */
private suspend fun SettingsRepository.saveGlobalRow(row: GlobalRow, keys: List<Key>?) = when (row) {
    GlobalRow.NUMBER -> setCustomNumberRow(keys)
    GlobalRow.ARROW -> setCustomArrowRow(keys)
}

/**
 * One keyboard-wide row, edited the way the layout editor edits the rows
 * around a grid: the row drawn as keys, a key's settings under it once
 * pressed, and the row's own tools. Every change saves at once.
 */
@Composable
internal fun GlobalRowEditorScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    row: GlobalRow,
    onNavigate: (String) -> Unit,
) {
    RegisterHidingHeader()
    val scope = rememberCoroutineScope()
    val stored = settings.watch { row.stored(it) }
    val builtIn = settings.watch { row.builtIn(it) }
    val keys = stored ?: builtIn
    var selected by remember(row) { mutableStateOf<Int?>(null) }

    // Applied to the row as it is stored when the write lands, inside that
    // write, so a change made just before this one, still on its way to
    // disk, is not undone by it.
    fun edit(transform: (List<Key>) -> List<Key>) {
        val fallback = row.builtIn(settings.value)
        scope.launch { repository.updateCustomRow(row == GlobalRow.ARROW, fallback, transform) }
    }

    CaptionText(stringResource(row.captionRes()))

    val layout = remember(keys) { KeyboardLayout(name = "global_row/${row.name}", rows = listOf(keys)) }
    EditorGrid(
        layout = layout,
        settings = settings,
        selection = selected?.let { KeyRef(0, it) },
        showShift = false,
        actualSize = false,
        onSelect = { ref -> selected = ref.col.takeIf { it >= 0 } },
        onKeyDragged = { from, to ->
            edit { current -> moveKeyIn(listOf(current), from, to).flatten() }
            selected = to.col
        },
    )

    val col = selected
    val key = col?.let { keys.getOrNull(it) }
    if (col != null && key != null) {
        val rowWidth = keys.sumOf { it.width.toDouble() }.toFloat()
        KeyEditSheet(
            key = key,
            ref = KeyRef(0, col),
            rowSize = keys.size,
            rowCount = 1,
            gridWeight = rowWidth,
            otherWidthsInRow = rowWidth - key.width,
            title = stringResource(row.keyTitleRes(), col + 1),
            onChange = { change ->
                edit { current -> current.mapIndexed { c, k -> if (c == col) change(k) else k } }
            },
            onMove = { delta ->
                val target = col + delta
                if (target in keys.indices) {
                    edit { current ->
                        if (col in current.indices && target in current.indices) {
                            current.toMutableList().apply { add(target, removeAt(col)) }
                        } else {
                            current
                        }
                    }
                    selected = target
                }
            },
            // One row: the section's up and down arrows are off at rowCount 1.
            onMoveRow = {},
            onDuplicate = {
                edit { current ->
                    if (col in current.indices) current.take(col + 1) + current[col] + current.drop(col + 1) else current
                }
            },
            onInsertAfter = {
                edit { current -> current.take(col + 1) + Key("new") + current.drop(col + 1) }
                selected = col + 1
            },
            onDelete = {
                edit { current -> current.filterIndexed { c, _ -> c != col } }
                selected = null
            },
            onDismiss = { selected = null },
            secondaryLayouts = secondaryLayouts(settings.watch { it.customLayouts }),
            keyboardModes = settings.watch { it.keyboardModes },
            popupColumns = settings.watch { it.popup.alternatesColumns },
            popupNearestFirst = settings.watch { it.popup.alternatesNearestFirst },
            popupBalanced = settings.watch { it.popup.alternatesBalanced },
        )
    } else {
        CaptionText(stringResource(R.string.layout_editor_drag_caption))
    }

    SettingsGroup(stringResource(R.string.layout_custom_row_keys_title)) {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_extra_row_add_key_desc),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    edit { it + Key("new") }
                    selected = keys.size
                },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_json_title),
                subtitle = stringResource(R.string.layout_custom_row_json_subtitle),
                leading = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
                onClick = { onNavigate(globalRowJsonRoute(row)) },
            )
        }
        item(visible = stored != null) {
            WmRow(
                title = stringResource(R.string.layout_custom_row_reset),
                subtitle = stringResource(R.string.layout_custom_row_reset_subtitle),
                leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = {
                    scope.launch { repository.saveGlobalRow(row, null) }
                    selected = null
                },
            )
        }
    }
}

/**
 * One keyboard-wide row as JSON: a bare list of keys, in the format a layout's
 * `numberRow` and `arrowRow` take, in the layout JSON editor with its checks
 * and suggestions. Apply saves it; Back leaves it.
 */
@Composable
internal fun GlobalRowJsonScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    row: GlobalRow,
    onDone: () -> Unit,
) {
    LayoutJsonEditorScreen(
        title = stringResource(row.jsonTitleRes()),
        documentKey = "global_row_${row.name}",
        root = LayoutJsonRoot.ROW,
        settings = settings,
        initialText = {
            val now = settings.value
            KeyRowCodec.encodeForEditing(row.stored(now) ?: row.builtIn(now))
        },
        onApply = { text ->
            val keys = KeyRowCodec.decode(text)
            if (keys == null) {
                JsonApplyOutcome.Invalid
            } else {
                repository.saveGlobalRow(row, keys)
                JsonApplyOutcome.Applied(emptyList())
            }
        },
        onBack = onDone,
    )
}
