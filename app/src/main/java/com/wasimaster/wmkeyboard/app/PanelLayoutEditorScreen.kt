package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutCodec
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.fitRowToGrid
import com.wasimaster.wmkeyboard.core.layout.hasFlicks
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.isAllowedOnPanel
import com.wasimaster.wmkeyboard.core.layout.panelFlexRows
import com.wasimaster.wmkeyboard.core.layout.panelRowTops
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayout
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.layout.spanRowWidths
import com.wasimaster.wmkeyboard.core.layout.validatePanelLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.keyShape
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/**
 * One panel's layout editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`panel_edit/{panel}`) is the same string for every
 * panel and would hang one key on all of them.
 */
internal fun panelEditRoute(kind: PanelKind): String = "panel_edit/${kind.name}"

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
    PanelKind.GIF -> R.string.panel_name_gif
    PanelKind.STICKER -> R.string.panel_name_sticker
    PanelKind.CLIPBOARD -> R.string.panel_name_clipboard
    PanelKind.TEXT_EDIT -> R.string.panel_name_text_edit
    PanelKind.TRACKPAD -> R.string.panel_name_trackpad
    PanelKind.NUMPAD -> R.string.panel_name_numpad
    PanelKind.HANDWRITING -> R.string.panel_name_handwriting
    PanelKind.EMOJI_MIX -> R.string.panel_name_emoji_mix
}

/** A component's name, in the picker, the preview cell and the reorder dialog. */
@StringRes
internal fun fieldTitleRes(kind: PanelFieldKind): Int = when (kind) {
    PanelFieldKind.EMOJI_TABS -> R.string.field_name_emoji_tabs
    PanelFieldKind.EMOJI_SEARCH -> R.string.field_name_emoji_search
    PanelFieldKind.EMOJI_GRID -> R.string.field_name_emoji_grid
    PanelFieldKind.MEDIA_TABS -> R.string.field_name_media_tabs
    PanelFieldKind.CLIPBOARD_SEARCH -> R.string.field_name_clipboard_search
    PanelFieldKind.CLIPBOARD_ENTITIES -> R.string.field_name_clipboard_entities
    PanelFieldKind.CLIPBOARD_LIST -> R.string.field_name_clipboard_list
    PanelFieldKind.CLIPBOARD_VIEW -> R.string.field_name_clipboard_view
    PanelFieldKind.TRACKPAD -> R.string.field_name_trackpad
    PanelFieldKind.GIF_BROWSER -> R.string.field_name_gif_browser
    PanelFieldKind.STICKER_BROWSER -> R.string.field_name_sticker_browser
    PanelFieldKind.HANDWRITING_CANVAS -> R.string.field_name_handwriting_canvas
    PanelFieldKind.EMOJI_MIX_BROWSER -> R.string.field_name_emoji_mix_browser
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
    TextEditAction.FORWARD_DELETE -> R.string.textedit_action_forward_delete
    TextEditAction.DOC_START -> R.string.textedit_action_doc_start
    TextEditAction.DOC_END -> R.string.textedit_action_doc_end
    TextEditAction.CUT -> R.string.textedit_action_cut
    TextEditAction.RETYPE_WORD -> R.string.textedit_action_retype_word
    TextEditAction.NO_SPACE -> R.string.textedit_action_no_space
    TextEditAction.NO_SPACE_NEXT -> R.string.textedit_action_no_space_next
    TextEditAction.DELETE_WORD -> R.string.textedit_action_delete_word
    TextEditAction.DELETE_WORD_FORWARD -> R.string.textedit_action_delete_word_forward
}

/** The components a layout of [kind] may place; empty for the keys-only pad. */
internal fun fieldKindsFor(kind: PanelKind): List<PanelFieldKind> =
    PanelFieldKind.entries.filter { it.isReal && it.isOn(kind) }

/**
 * The actions a panel key may take: the panel's own component first, then
 * every typing action the panel repair accepts — which since issue #183 is
 * every action of the key catalog but the two `isAllowedOnPanel` names.
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
    SettingsGroup(
        stringResource(R.string.panel_layouts_title),
        info = stringResource(R.string.panel_layouts_subtitle),
    ) {
        for (kind in PanelKind.entries.filter { it.shipped }) {
            item {
                val name = stringResource(panelTitleRes(kind))
                val isCustom = custom.any { it.panel == kind }
                WmRow(
                    title = name,
                    icon = SettingsRowIcons[panelTitleRes(kind)],
                    subtitle = stringResource(
                        if (isCustom) R.string.panel_layout_value_custom else R.string.panel_layout_value_default,
                    ),
                    flightTo = panelEditRoute(kind),
                    onClick = { onNavigate(panelEditRoute(kind)) },
                )
            }
        }
    }
}

@Composable
internal fun PanelLayoutEditorScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
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

    val keyHeightDp = settings.watch { it.keyHeightDp }
    val (compiled, previewHeightsDp) = remember(spec, keyHeightDp, actualSize) {
        panelPreview(kind, spec.grid, spec.appearance, keyHeightDp, actualSize, themeId = spec.grid.themeId)
    }

    SectionHeaderPublic(
        stringResource(panelTitleRes(kind)),
        info = stringResource(R.string.panel_layout_editor_caption),
    )

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
        },
        rowHeightsDp = previewHeightsDp,
        // A drop is one edit and one undo step, through the same guarded move
        // the sheet's arrows use.
        onKeyDragged = { from, to ->
            editGrid { it.copy(rows = moveKeyIn(it.rows, from, to)) }
            selection = to
            stepPushed = false
        },
    )

    if (selection == null) CaptionText(stringResource(R.string.layout_editor_drag_caption))

    PanelEditorBody(
        kind = kind,
        grid = spec.grid,
        selection = selection,
        onSelectionChange = { selection = it },
        actualSize = actualSize,
        onActualSizeChange = { actualSize = it },
        editGrid = ::editGrid,
        editGridCoalesced = ::editGridCoalesced,
        jsonRoute = "panel_json/${kind.name}",
        onNavigate = onNavigate,
        settings = settings,
        reset = if (isCustom) {
            ResetRow(
                R.string.panel_layout_reset_title,
                stringResource(R.string.panel_layout_reset_subtitle),
            ) { confirmReset = true }
        } else {
            null
        },
    )

    // The switch to GIFs and stickers draws nothing while neither of their
    // tools is on: a switch with only the emoji panel in it goes nowhere. A
    // grid that places it says so here, or the cell reads as broken (#856).
    val switchIdle = settings.watch { !mediaSwitchReachable(it) }
    if (switchIdle && spec.grid.rows.any { row -> row.any { (it.action as? KeyAction.Field)?.kind == PanelFieldKind.MEDIA_TABS } }) {
        SettingsGroup(stringResource(R.string.panel_layout_media_tabs_idle_group)) {
            item {
                NavRow(
                    title = R.string.panel_layout_media_tabs_idle_title,
                    subtitle = stringResource(R.string.panel_layout_media_tabs_idle_body),
                    route = toolRoute(ToolbarTool.GIF),
                    onClick = { onNavigate(toolRoute(ToolbarTool.GIF)) },
                )
            }
        }
    }

    val findings = validatePanelLayout(spec)
    if (findings.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.layout_editor_problems_title),
            info = stringResource(R.string.panel_layout_live_caption),
        ) {
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
}

/**
 * Whether the emoji panel's switch has somewhere to go: the GIF or the Sticker
 * tool switched on and usable. The keyboard's own test (`mediaTabs`) lives in
 * the IME module; this is the same rule for the editor.
 */
internal fun mediaSwitchReachable(settings: KeyboardSettings): Boolean =
    listOf(ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.EMOJI_MIX).any { tool ->
        tool in settings.enabledTools && isSupportedTool(tool) && isUsableTool(tool, settings)
    }

/**
 * The panel's grid as the preview draws it, with the row heights the keyboard
 * would give it: key rows at key height, component rows over the rest, four
 * key rows' worth in all — which is what the key area is on the shipped
 * layouts. Shared by the panel's own screen and the layout editor's panel
 * tabs (issue #63), so both previews read the same.
 *
 * [themeId] is the owning layout's theme, when a layout owns this grid; the
 * preview then draws in it, the way the typing tabs do.
 */
internal fun panelPreview(
    kind: PanelKind,
    grid: LayerSpec,
    appearance: LayoutAppearance?,
    keyHeightDp: Int,
    actualSize: Boolean,
    themeId: String? = null,
): Pair<KeyboardLayout, List<Int>> {
    val rows = grid.rows
    val rowHeights = grid.rowHeights
    val compiled = KeyboardLayout(
        name = kind.name,
        rows = rows,
        rowHeights = rowHeights,
        appearance = LayoutAppearance(
            fontId = appearance?.fontId,
            fontScale = grid.fontScale ?: appearance?.fontScale,
        ).takeUnless { it.isEmpty },
        themeId = themeId,
    )
    val baseHeightDp = if (actualSize) keyHeightDp else keyHeightDp.coerceIn(38, 56)
    val heights = if (rows.isEmpty()) {
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
    return compiled to heights
}

/** The row that puts a panel grid back: what it is called, and what it does. */
internal class ResetRow(
    @StringRes val titleRes: Int,
    val subtitle: String,
    val onClick: () -> Unit,
)

/**
 * Everything under a panel's preview grid: the selected key's section, the row
 * tools for its row, and the group of layout-wide rows. Owned by neither
 * screen that shows it — the panel's own screen edits the shared panel layout
 * through the repository, the layout editor edits a layout's own copy through
 * its undo stack — so every edit goes out through [editGrid] and
 * [editGridCoalesced] and every piece of state comes in as a parameter.
 */
@Composable
internal fun PanelEditorBody(
    kind: PanelKind,
    grid: LayerSpec,
    selection: KeyRef?,
    onSelectionChange: (KeyRef?) -> Unit,
    actualSize: Boolean,
    onActualSizeChange: (Boolean) -> Unit,
    editGrid: ((LayerSpec) -> LayerSpec) -> Unit,
    editGridCoalesced: ((LayerSpec) -> LayerSpec) -> Unit,
    jsonRoute: String,
    onNavigate: (String) -> Unit,
    reset: ResetRow?,
    /** For the theme row: the picker lists this user's themes. */
    settings: LiveSettings,
) {
    val context = LocalContext.current
    val rows = grid.rows
    val rowHeights = grid.rowHeights
    val selectedKey = selection?.let { rows.getOrNull(it.row)?.getOrNull(it.col) }
    var themePickerOpen by remember(kind) { mutableStateOf(false) }

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

    // The selected key first, then its row (issue #769): on the page under
    // the grid rather than in a sheet over it.
    val ref = selection
    if (ref != null && selectedKey != null) {
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
                    onSelectionChange(ref.copy(col = target))
                }
            },
            onMoveRow = { delta ->
                // The key layers' rule, on a panel: the same guarded helper the
                // preview's drag lands through.
                rowMoveTarget(rows, ref, delta)?.let { to ->
                    editRows { moveKeyIn(it, ref, to) }
                    onSelectionChange(to)
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
                onSelectionChange(null)
            },
            onDismiss = { onSelectionChange(null) },
            catalog = panelKeyActionCatalog(kind),
            fieldKinds = fieldKindsFor(kind).takeIf { it.isNotEmpty() },
            // An "open a layout" key on a panel names a secondary layout the
            // same way one on a typing grid does.
            secondaryLayouts = secondaryLayouts(settings.watch { it.customLayouts }),
            keyboardModes = settings.watch { it.keyboardModes },
        )
    }

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
                    onSelectionChange(null)
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
                icon = SettingsRowIcons[R.string.layout_editor_reorder_rows_title],
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
            item(visible = ref.row in rows.indices && rows[ref.row].size > 1) {
                ReorderSetting(
                    title = stringResource(R.string.layout_editor_reorder_keys_title, ref.row + 1),
                    icon = SettingsRowIcons[R.string.layout_editor_reorder_keys_title],
                    dialogTitle = stringResource(R.string.layout_editor_key_order_dialog_title),
                    items = rows[ref.row].indices.toList(),
                    label = { keyReorderLabel(context, rows[ref.row][it]) },
                ) { order ->
                    editRows { r ->
                        r.mapIndexed { i, row ->
                            if (i == ref.row && order.size == row.size) order.map { row[it] } else row
                        }
                    }
                    onSelectionChange(null)
                }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_editor_actual_size_title,
                stringResource(R.string.layout_editor_actual_size_subtitle),
                actualSize,
                info = stringResource(R.string.layout_editor_actual_size_info),
            ) { onActualSizeChange(it) }
        }
        item {
            // Issue #60's switch, on a panel: the same field on the same
            // LayerSpec the key layers carry, so it needs no second model.
            ToggleSetting(
                R.string.layout_editor_persist_title,
                stringResource(R.string.panel_layout_persist_subtitle),
                grid.persistent,
                info = stringResource(R.string.panel_layout_persist_info),
            ) { on -> editGrid { it.copy(persistent = on) } }
        }
        // Issue #588 on a panel: the Numpad's flick arms, over the setting.
        if (rows.any { row -> row.any { k -> k.hasFlicks() } }) {
            item {
                val global = settings.watch { it.layoutBehavior.flickHints }
                ToggleSetting(
                    R.string.layout_editor_flick_hints_title,
                    stringResource(R.string.layout_editor_flick_hints_subtitle),
                    grid.flickHints ?: global,
                    info = stringResource(R.string.layout_editor_flick_hints_info),
                ) { on -> editGrid { it.copy(flickHints = on.takeIf { value -> value != global }) } }
            }
        }
        item {
            // A theme of the panel's own (issue #61 on a panel): the board
            // draws in it while this panel is open, over the layout's theme
            // and the settings'. Same picker, same Clear, as a key layer's.
            val clearLabel = stringResource(CommonR.string.common_clear)
            WmRow(
                title = stringResource(R.string.layout_editor_panel_theme_title),
                icon = SettingsRowIcons[R.string.layout_editor_panel_theme_title],
                subtitle = grid.themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.layout_editor_panel_theme_inherit_subtitle),
                trailing = {
                    if (grid.themeId != null) {
                        TextButton(onClick = { editGrid { it.copy(themeId = null) } }) { Text(clearLabel) }
                    }
                },
                onClick = { themePickerOpen = true },
            )
        }
        item {
            LayoutFontScaleRow(
                scale = grid.fontScale,
                title = stringResource(R.string.layout_editor_font_scale_label, grid.fontScale ?: 1f),
                autoTitle = stringResource(R.string.layout_editor_font_scale_auto_label),
                hint = stringResource(R.string.layout_editor_font_scale_hint),
                onChange = { value -> editGridCoalesced { it.copy(fontScale = value) } },
            )
        }
        item {
            NavRow(
                R.string.layout_editor_json_title,
                subtitle = stringResource(R.string.layout_editor_json_subtitle),
            ) { onNavigate(jsonRoute) }
        }
        if (reset != null) {
            item {
                WmRow(
                    title = stringResource(reset.titleRes),
                    subtitle = reset.subtitle,
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = reset.onClick,
                )
            }
        }
    }

    if (themePickerOpen) {
        ModeThemePickerDialog(
            settings = settings,
            selectedId = grid.themeId,
            title = stringResource(R.string.layout_editor_panel_theme_picker_title),
            onPick = { id ->
                themePickerOpen = false
                editGrid { it.copy(themeId = id) }
            },
            onDismiss = { themePickerOpen = false },
        )
    }
}

/** What "Add a key" puts on a panel: a labelled text key, or an arrow on the keys-only pad. */
private fun newPanelKey(kind: PanelKind): Key = when (kind) {
    PanelKind.TEXT_EDIT -> Key("", action = KeyAction.Edit(TextEditAction.LEFT))
    PanelKind.NUMPAD -> Key("0")
    else -> Key("new")
}

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
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_edit_picker_title)) },
        text = {
            // Twenty operations behind a 420 dp window, and the same complaint
            // the action picker got (issue #202): a plain column with a rail
            // beside it, since twenty rows are not worth a lazy list.
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 420.dp)) {
                for (op in TextEditAction.entries) {
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

/**
 * Builds a run of text-editing operations for one press (#985): what is in it,
 * each with a way out, and a row that asks for one more. [onPick] takes the run.
 */
@Composable
internal fun TextEditSequenceDialog(
    current: List<TextEditAction>,
    onDismiss: () -> Unit,
    onPick: (List<TextEditAction>) -> Unit,
) {
    var ops by remember { mutableStateOf(current) }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_sequence_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ops.forEachIndexed { index, op ->
                    WmRow(
                        title = "${index + 1}. ${stringResource(textEditActionTitle(op))}",
                        onClick = { ops = ops.filterIndexed { i, _ -> i != index } },
                    )
                }
                WmRow(
                    title = stringResource(R.string.layout_editor_sequence_add),
                    subtitle = stringResource(R.string.layout_editor_sequence_add_subtitle),
                    onClick = { adding = true },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ops.isNotEmpty(), onClick = { onPick(ops) }) {
                Text(stringResource(CommonR.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
    if (adding) {
        TextEditActionPickerDialog(
            current = null,
            onDismiss = { adding = false },
            onPick = { op ->
                adding = false
                ops = ops + op
            },
        )
    }
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
    settings: LiveSettings,
    kind: PanelKind,
    onDone: () -> Unit,
) {
    val custom by repository.customPanelLayouts.collectAsStateWithLifecycle(null)
    val stored = custom ?: return
    LayoutJsonEditorScreen(
        title = stringResource(R.string.panel_layout_json_title),
        documentKey = "panel:${kind.name}",
        root = LayoutJsonRoot.PANEL,
        settings = settings,
        initialText = { PanelLayoutCodec.encodeForEditing(resolvePanelLayout(kind, stored)) },
        onApply = { text ->
            val parsed = PanelLayoutCodec.decode(text)
            if (parsed == null) {
                JsonApplyOutcome.Invalid
            } else {
                // The panel named in the text is ignored: this screen edits one
                // panel, and honouring a pasted one would overwrite another.
                val repaired = parsed.copy(panel = kind).repair()
                repository.upsertPanelLayout(repaired.spec)
                JsonApplyOutcome.Applied(repaired.repairNotes)
            }
        },
        onBack = onDone,
    )
}
