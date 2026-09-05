package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutAppearance
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutCodec
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.fitRowToGrid
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.isAllowedOnPanel
import com.wasimaster.wmkeyboard.core.layout.panelFlexRows
import com.wasimaster.wmkeyboard.core.layout.panelRowTops
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayout
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.spanRowWidths
import com.wasimaster.wmkeyboard.core.layout.validatePanelLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.keyShape
import kotlinx.coroutines.launch

/**
 * The panel layouts (issue #63): the emoji, clipboard and text-editing panels
 * as grids the user rearranges in the key layout editor's own controls.
 *
 * A thin screen of its own rather than a mode of `KeyLayoutEditorScreen`: that
 * screen's identity rows, layer tabs, Fn template, tablet toggle and enable
 * gate are all about typing layouts, while what a panel needs — the grid, the
 * row tools, the key sheet, undo — it borrows whole. One layout per panel,
 * edited in place; Reset goes back to the shipped grid.
 */

/** The panel's name, as the gallery and the editor title it. */
@StringRes
internal fun panelTitleRes(kind: PanelKind): Int = when (kind) {
    PanelKind.EMOJI -> R.string.panel_name_emoji
    PanelKind.CLIPBOARD -> R.string.panel_name_clipboard
    PanelKind.TEXT_EDIT -> R.string.panel_name_text_edit
    PanelKind.TRACKPAD -> R.string.panel_name_trackpad
}

/** A component's name, in the picker, the preview cell and the reorder dialog. */
@StringRes
internal fun fieldTitleRes(kind: PanelFieldKind): Int = when (kind) {
    PanelFieldKind.EMOJI_TABS -> R.string.field_name_emoji_tabs
    PanelFieldKind.EMOJI_SEARCH -> R.string.field_name_emoji_search
    PanelFieldKind.EMOJI_GRID -> R.string.field_name_emoji_grid
    PanelFieldKind.CLIPBOARD_SEARCH -> R.string.field_name_clipboard_search
    PanelFieldKind.CLIPBOARD_ENTITIES -> R.string.field_name_clipboard_entities
    PanelFieldKind.CLIPBOARD_LIST -> R.string.field_name_clipboard_list
    PanelFieldKind.TRACKPAD -> R.string.field_name_trackpad
    PanelFieldKind.UNKNOWN -> R.string.field_name_unknown
}

/**
 * The name each text-editing operation is listed under — the keyboard's own
 * spoken labels, so the editor and TalkBack call the same key the same thing.
 */
@StringRes
internal fun textEditActionTitle(op: TextEditAction): Int = when (op) {
    TextEditAction.LEFT -> R.string.textedit_action_left
    TextEditAction.RIGHT -> R.string.textedit_action_right
    TextEditAction.UP -> R.string.textedit_action_up
    TextEditAction.DOWN -> R.string.textedit_action_down
    TextEditAction.WORD_LEFT -> R.string.textedit_action_word_left
    TextEditAction.WORD_RIGHT -> R.string.textedit_action_word_right
    TextEditAction.PAGE_UP -> R.string.textedit_action_page_up
    TextEditAction.PAGE_DOWN -> R.string.textedit_action_page_down
    TextEditAction.HOME -> R.string.textedit_action_home
    TextEditAction.END -> R.string.textedit_action_end
    TextEditAction.SELECT -> R.string.textedit_action_select
    TextEditAction.SELECT_ALL -> R.string.textedit_action_select_all
    TextEditAction.SELECT_WORD -> R.string.textedit_action_select_word
    TextEditAction.SELECT_LINE -> R.string.textedit_action_select_line
    TextEditAction.COPY -> R.string.textedit_action_copy
    TextEditAction.PASTE -> R.string.textedit_action_paste
    TextEditAction.BACKSPACE -> R.string.textedit_action_backspace
}

/** The components a layout of [kind] may place; empty for the keys-only pad. */
internal fun fieldKindsFor(kind: PanelKind): List<PanelFieldKind> =
    PanelFieldKind.entries.filter { it.isReal && it.panel == kind }

/**
 * The actions a panel key may take: the panel's own component first, then
 * every typing action the panel repair accepts. Shift, caps lock, Fn and the
 * chorded keys are left out because `repair` would drop them anyway.
 */
internal fun panelKeyActionCatalog(kind: PanelKind): List<KeyActionOption> {
    val kinds = fieldKindsFor(kind)
    val field = kinds.firstOrNull()?.let { first ->
        KeyActionOption(
            R.string.layout_editor_action_field_title,
            R.string.layout_editor_action_group_panel,
            R.string.layout_editor_action_field_detail,
            { KeyAction.Field(kind.requiredField ?: first) }, { it is KeyAction.Field },
        )
    }
    return listOfNotNull(field) + KeyActionCatalog.filter { it.build().isAllowedOnPanel() }
}

/**
 * The gallery's group of panels, under the key layouts: each row names the
 * panel and says whether it is on the shipped grid or the user's own.
 */
@Composable
internal fun PanelLayoutsGroup(custom: List<PanelLayoutSpec>, onNavigate: (String) -> Unit) {
    CaptionText(stringResource(R.string.panel_layouts_subtitle))
    SettingsGroup(stringResource(R.string.panel_layouts_title)) {
        for (kind in PanelKind.entries.filter { it.shipped }) {
            item {
                val name = stringResource(panelTitleRes(kind))
                val isCustom = custom.any { it.panel == kind }
                WmRow(
                    title = name,
                    subtitle = stringResource(
                        if (isCustom) R.string.panel_layout_value_custom else R.string.panel_layout_value_default,
                    ),
                    onClick = { onNavigate("panel_edit/${kind.name}") },
                )
            }
        }
    }
}

@Composable
internal fun PanelLayoutEditorScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    kind: PanelKind,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Null until the store answers, so the first frame does not flash the
    // shipped grid in front of a layout the user already has.
    val custom by repository.customPanelLayouts.collectAsStateWithLifecycle(null)
    val stored = custom ?: return
    val spec = resolvePanelLayout(kind, stored)
    val isCustom = stored.any { it.panel == kind }

    var selection by remember(kind) { mutableStateOf<KeyRef?>(null) }
    var sheetOpen by remember(kind) { mutableStateOf(false) }
    var actualSize by rememberSaveable(kind) { mutableStateOf(false) }
    var confirmReset by remember(kind) { mutableStateOf(false) }

    var undo by remember(kind) { mutableStateOf(emptyList<PanelLayoutSpec>()) }
    var redo by remember(kind) { mutableStateOf(emptyList<PanelLayoutSpec>()) }
    var stepPushed by remember(kind) { mutableStateOf(false) }

    fun push() {
        undo = (undo + spec).takeLast(UndoDepth)
        redo = emptyList()
    }

    fun save(next: PanelLayoutSpec) {
        scope.launch { repository.upsertPanelLayout(next) }
    }

    // Through the repository, never the copy held here: the screen saves on
    // every keystroke and its copy lags the write it just made.
    fun apply(transform: (PanelLayoutSpec) -> PanelLayoutSpec) {
        scope.launch { repository.updatePanelLayout(kind, transform) }
    }

    fun edit(transform: (PanelLayoutSpec) -> PanelLayoutSpec) {
        push()
        stepPushed = true
        apply(transform)
    }

    fun editCoalesced(transform: (PanelLayoutSpec) -> PanelLayoutSpec) {
        if (!stepPushed) push()
        stepPushed = true
        apply(transform)
    }

    fun editGrid(transform: (LayerSpec) -> LayerSpec) = edit { it.copy(grid = transform(it.grid)) }

    fun editGridCoalesced(transform: (LayerSpec) -> LayerSpec) =
        editCoalesced { it.copy(grid = transform(it.grid)) }

    fun editRows(transform: (List<List<Key>>) -> List<List<Key>>) =
        editGrid { it.copy(rows = transform(it.rows)) }

    fun pickHeights(heights: List<Float>?, sourceIndices: List<Int>): List<Float>? =
        heights?.let { h -> sourceIndices.map { h.getOrNull(it) ?: 1f } }

    fun setRowHeight(rowIndex: Int, value: Float) {
        editGridCoalesced { ls ->
            val list = MutableList(ls.rows.size) { ls.rowHeights?.getOrNull(it) ?: 1f }
            if (rowIndex in list.indices) list[rowIndex] = value
            ls.copy(rowHeights = if (list.all { it == 1f }) null else list.toList())
        }
    }

    val rows = spec.grid.rows
    val rowHeights = spec.grid.rowHeights
    val selectedKey = selection?.let { rows.getOrNull(it.row)?.getOrNull(it.col) }
    val compiled = KeyboardLayout(
        name = kind.name,
        rows = rows,
        rowHeights = rowHeights,
        appearance = LayoutAppearance(
            fontId = spec.appearance?.fontId,
            fontScale = spec.grid.fontScale ?: spec.appearance?.fontScale,
        ).takeUnless { it.isEmpty },
    )
    // The preview's rows share a fixed height the way the keyboard's do: key
    // rows at key height, component rows over the rest. Four key rows' worth,
    // which is what the key area is on the shipped layouts.
    val baseHeightDp = if (actualSize) settings.keyHeightDp else settings.keyHeightDp.coerceIn(38, 56)
    val previewHeightsDp = remember(spec, baseHeightDp) {
        if (rows.isEmpty()) {
            emptyList()
        } else {
            val gap = 4
            val fixed = IntArray(rows.size) { rowScaledKeyHeight(baseHeightDp, rowHeights?.getOrNull(it)) + gap }
            val weights = FloatArray(rows.size) {
                (rowHeights?.getOrNull(it) ?: 1f).coerceIn(MinRowHeightScale, MaxRowHeightScale)
            }
            val tops = panelRowTops(fixed, weights, panelFlexRows(rows), (baseHeightDp + gap) * 4)
            List(rows.size) { (tops[it + 1] - tops[it] - gap).coerceAtLeast(8) }
        }
    }

    SectionHeaderPublic(stringResource(panelTitleRes(kind)))
    CaptionText(stringResource(R.string.panel_layout_editor_caption))

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = undo.isNotEmpty(),
            onClick = {
                val previous = undo.last()
                undo = undo.dropLast(1)
                redo = redo + spec
                stepPushed = false
                save(previous)
            },
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.layout_editor_undo_desc))
        }
        IconButton(
            enabled = redo.isNotEmpty(),
            onClick = {
                val next = redo.last()
                redo = redo.dropLast(1)
                undo = undo + spec
                stepPushed = false
                save(next)
            },
        ) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.layout_editor_redo_desc))
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.layout_editor_autosave_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    EditorGrid(
        layout = compiled,
        settings = settings,
        selection = selection,
        showShift = false,
        actualSize = actualSize,
        onSelect = { ref ->
            selection = ref
            stepPushed = false
            sheetOpen = true
        },
        rowHeightsDp = previewHeightsDp,
    )

    selection?.let { ref ->
        if (ref.row in rows.indices) {
            val spanWidth = spanRowWidths(rows)[ref.row]
            RowActionBar(
                rowIndex = ref.row,
                rowCount = rows.size,
                rowWidth = spanWidth,
                gridWeight = gridWeightOf(rows),
                onAddKey = {
                    editRows { r -> r.mapIndexed { i, row -> if (i == ref.row) row + newPanelKey(kind) else row } }
                },
                onDuplicateRow = {
                    editGrid { ls ->
                        val src = (0..ref.row) + ref.row + (ref.row + 1 until ls.rows.size)
                        ls.copy(rows = src.map { ls.rows[it] }, rowHeights = pickHeights(ls.rowHeights, src))
                    }
                },
                onDeleteRow = {
                    editGrid { ls ->
                        val src = ls.rows.indices.filter { it != ref.row }
                        ls.copy(rows = src.map { ls.rows[it] }, rowHeights = pickHeights(ls.rowHeights, src))
                    }
                    selection = null
                },
            )
            RowFitRow(rowWidth = spanWidth, gridWeight = gridWeightOf(rows)) {
                editRows { r ->
                    val held = spanRowWidths(r)[ref.row] - r[ref.row].sumOf { it.width.toDouble() }.toFloat()
                    r.mapIndexed { i, row -> if (i == ref.row) fitRowToGrid(row, gridWeightOf(r) - held) else row }
                }
            }
            RowHeightRow(
                rowIndex = ref.row,
                height = rowHeights?.getOrNull(ref.row) ?: 1f,
            ) { setRowHeight(ref.row, it) }
        }
    }

    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_add_row_title),
                subtitle = stringResource(R.string.layout_editor_add_row_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    editGrid { ls ->
                        ls.copy(rows = ls.rows + listOf(listOf(newPanelKey(kind))), rowHeights = ls.rowHeights?.plus(1f))
                    }
                },
            )
        }
        item {
            ReorderSetting(
                title = stringResource(R.string.layout_editor_reorder_rows_title),
                dialogTitle = stringResource(R.string.layout_editor_row_order_dialog_title),
                items = rows.indices.toList(),
                label = { i -> rowReorderLabel(context, i + 1, rows[i].size) },
            ) { order ->
                editGrid { ls ->
                    ls.copy(rows = order.map { ls.rows[it] }, rowHeights = pickHeights(ls.rowHeights, order))
                }
            }
        }
        selection?.let { ref ->
            if (ref.row in rows.indices && rows[ref.row].size > 1) {
                item {
                    ReorderSetting(
                        title = stringResource(R.string.layout_editor_reorder_keys_title, ref.row + 1),
                        dialogTitle = stringResource(R.string.layout_editor_key_order_dialog_title),
                        items = rows[ref.row].indices.toList(),
                        label = { keyReorderLabel(context, rows[ref.row][it]) },
                    ) { order ->
                        editRows { r ->
                            r.mapIndexed { i, row ->
                                if (i == ref.row && order.size == row.size) order.map { row[it] } else row
                            }
                        }
                        selection = null
                    }
                }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_editor_actual_size_title,
                stringResource(R.string.layout_editor_actual_size_subtitle),
                actualSize,
                info = stringResource(R.string.layout_editor_actual_size_info),
            ) { actualSize = it }
        }
        item {
            LayoutFontScaleRow(
                scale = spec.grid.fontScale,
                title = stringResource(R.string.layout_editor_font_scale_label, spec.grid.fontScale ?: 1f),
                autoTitle = stringResource(R.string.layout_editor_font_scale_auto_label),
                hint = stringResource(R.string.layout_editor_font_scale_hint),
                onChange = { value -> editGridCoalesced { it.copy(fontScale = value) } },
            )
        }
        item {
            NavRow(
                R.string.layout_editor_json_title,
                subtitle = stringResource(R.string.layout_editor_json_subtitle),
            ) { onNavigate("panel_json/${kind.name}") }
        }
        if (isCustom) {
            item {
                WmRow(
                    title = stringResource(R.string.panel_layout_reset_title),
                    subtitle = stringResource(R.string.panel_layout_reset_subtitle),
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    val findings = validatePanelLayout(spec)
    if (findings.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.layout_editor_problems_title)) {
            for (finding in findings) {
                item {
                    WmRow(
                        title = finding.text.format(context.resources),
                        subtitle = stringResource(
                            if (finding.severity == LayoutSeverity.BLOCKING) {
                                R.string.layout_editor_problem_blocking_subtitle
                            } else {
                                R.string.layout_editor_problem_warning_subtitle
                            },
                        ),
                    )
                }
            }
        }
    }

    CaptionText(stringResource(R.string.panel_layout_live_caption))

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.panel_layout_reset_confirm_title)) },
            text = { Text(stringResource(R.string.panel_layout_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        selection = null
                        undo = emptyList()
                        redo = emptyList()
                        scope.launch { repository.resetPanelLayout(kind) }
                    },
                ) { Text(stringResource(R.string.panel_layout_reset_title)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }

    val ref = selection
    if (sheetOpen && ref != null && selectedKey != null) {
        KeyEditSheet(
            key = selectedKey,
            ref = ref,
            rowSize = rows[ref.row].size,
            rowCount = rows.size,
            gridWeight = gridWeightOf(rows),
            otherWidthsInRow = spanRowWidths(rows)[ref.row] - selectedKey.width,
            onChange = { change ->
                editGridCoalesced { ls ->
                    ls.copy(
                        rows = ls.rows.mapIndexed { r, row ->
                            if (r != ref.row) row else row.mapIndexed { c, k -> if (c == ref.col) change(k) else k }
                        },
                    )
                }
            },
            onMove = { delta ->
                val target = ref.col + delta
                if (target in rows[ref.row].indices) {
                    editRows { r ->
                        r.mapIndexed { i, row ->
                            if (i != ref.row) row else row.toMutableList().apply { add(target, removeAt(ref.col)) }
                        }
                    }
                    selection = ref.copy(col = target)
                }
            },
            onDuplicate = {
                editRows { r ->
                    r.mapIndexed { i, row ->
                        if (i != ref.row) row else row.subList(0, ref.col + 1) + row[ref.col] + row.drop(ref.col + 1)
                    }
                }
            },
            onDelete = {
                editRows { r ->
                    r.mapIndexed { i, row -> if (i != ref.row) row else row.filterIndexed { c, _ -> c != ref.col } }
                }
                selection = null
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
            catalog = panelKeyActionCatalog(kind),
            fieldKinds = fieldKindsFor(kind).takeIf { it.isNotEmpty() },
        )
    }
}

/** What "Add a key" puts on a panel: a labelled text key, or an arrow on the keys-only pad. */
private fun newPanelKey(kind: PanelKind): Key =
    if (kind == PanelKind.TEXT_EDIT) Key("", action = KeyAction.Edit(TextEditAction.LEFT)) else Key("new")

/**
 * A component's cell in the preview: hatched, with the component's name, so
 * the grid reads as "the live part goes here" rather than as a blank key.
 */
@Composable
internal fun EditorFieldCell(
    kind: PanelFieldKind,
    kb: KbTheme,
    heightDp: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val stripe = kb.modifierKey.copy(alpha = 0.55f)
    Box(
        modifier = modifier
            .height(heightDp.dp)
            .clip(kb.keyShape())
            .background(kb.modifierKey.copy(alpha = 0.25f))
            .drawBehind {
                val step = 10.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(
                        color = stripe,
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Butt,
                    )
                    x += step
                }
            }
            .then(if (selected) Modifier.border(2.dp, kb.accent, kb.keyShape()) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(fieldTitleRes(kind)),
            color = kb.modifierKeyText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Picks which operation a text-editing key runs. */
@Composable
internal fun TextEditActionPickerDialog(
    current: TextEditAction?,
    onDismiss: () -> Unit,
    onPick: (TextEditAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_edit_picker_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(TextEditAction.entries, key = { it.name }) { op ->
                    WmRow(
                        title = stringResource(textEditActionTitle(op)),
                        leading = { RadioButton(selected = current == op, onClick = { onPick(op) }) },
                        onClick = { onPick(op) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Picks which component a cell hosts. */
@Composable
internal fun FieldKindPickerDialog(
    current: PanelFieldKind?,
    options: List<PanelFieldKind>,
    onDismiss: () -> Unit,
    onPick: (PanelFieldKind) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_field_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (kind in options) {
                    WmRow(
                        title = stringResource(fieldTitleRes(kind)),
                        leading = { RadioButton(selected = current == kind, onClick = { onPick(kind) }) },
                        onClick = { onPick(kind) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The panel layout as text, for pasting one in or fixing something the grid
 * editor has no control for. Draft plus an explicit Apply, like the key
 * layouts' JSON screen; applying runs the same repair the keyboard does.
 */
@Composable
internal fun PanelLayoutJsonScreen(
    repository: SettingsRepository,
    kind: PanelKind,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val custom by repository.customPanelLayouts.collectAsStateWithLifecycle(null)
    val stored = custom ?: return
    var text by rememberSaveable(kind) {
        mutableStateOf(PanelLayoutCodec.encodeForEditing(resolvePanelLayout(kind, stored)))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var repairs by remember { mutableStateOf<List<LayoutMessage>>(emptyList()) }
    val invalidJsonMessage = stringResource(R.string.layout_editor_json_invalid_error)

    CaptionText(stringResource(R.string.layout_editor_json_caption))

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = { Text(stringResource(R.string.layout_editor_json_field_label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = rememberJsonSyntaxHighlighter(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 420.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )

    if (repairs.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.layout_editor_json_applied_title)) {
            for (note in repairs) {
                item { WmRow(title = note.format(context.resources)) }
            }
        }
    }

    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Button(
            enabled = text.isNotBlank(),
            onClick = {
                val parsed = PanelLayoutCodec.decode(text)
                if (parsed == null) {
                    error = invalidJsonMessage
                    return@Button
                }
                // The panel named in the text is ignored: this screen edits one
                // panel, and honouring a pasted one would overwrite another.
                val repaired = parsed.copy(panel = kind).repair()
                repairs = repaired.repairNotes
                scope.launch {
                    repository.upsertPanelLayout(repaired.spec)
                    if (repaired.repairNotes.isEmpty()) onDone()
                }
            },
        ) { Text(stringResource(R.string.layout_editor_apply_action)) }
    }
}
