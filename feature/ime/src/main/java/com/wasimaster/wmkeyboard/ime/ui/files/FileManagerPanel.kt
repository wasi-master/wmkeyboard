package com.wasimaster.wmkeyboard.ime.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewAgenda
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileManagerCallbacks
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.files.FilePane
import com.wasimaster.wmkeyboard.ime.files.FileView
import com.wasimaster.wmkeyboard.ime.files.FilesTextField
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.gallery.trimGalleryImageMemory

/**
 * The keyboard's file manager: the Files tool of its own (#1267), and what a
 * tool opens when it wants a file, so picking one does not mean leaving the
 * field. A panel at the keyboard's height, in the keyboard's theme, that grows
 * for a preview or a Properties card.
 *
 * The faces are the owner's picks from the 2026-11-26 mockups: Places as a
 * dashboard (1A), folders as a grid with rows a button away (2B), a selection
 * that follows the view with a labelled action bar (3), a viewer for
 * pictures and a header card for documents (4A and 4B), a detail list for a
 * file and tiles for a folder (5A and 5B), filters and recent searches (6A),
 * progress with a clash card and an Undo chip (7A), two lists side by side (8A).
 */
@Composable
internal fun FileManagerPanel(
    state: KeyboardUiState,
    callbacks: FileManagerCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.filesText
    val screen = LocalConfiguration.current.screenHeightDp.dp
    // The ⋮ menus, drawn inside the panel rather than as popup windows,
    // which would sit over the app behind the keyboard.
    var overflow by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { trimGalleryImageMemory() } }

    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    val searchMode = typing?.field == FilesTextField.SEARCH || ui.query != null
    val places = ui.view == FileView.Places && ui.query == null
    val targets = if (places) placeTargets(ui) else emptyList()
    val faceOpen = ui.preview != null || ui.info != null
    val historyOpen = typing?.field == FilesTextField.SEARCH && typing.text.isBlank() && ui.query == null && !ui.filters.any
    PanelFocusTarget(
        panel = PanelMode.FILES,
        region = FocusRegion.RESULTS,
        count = when {
            typing != null || faceOpen || historyOpen -> 0
            places -> targets.size
            else -> ui.entries.size
        },
        columns = if (!places && ui.grid && ui.query == null) (if (ui.twoPanes) 2 else 4) else 1,
    ) { index ->
        if (places) {
            targets.getOrNull(index)?.let(act)
        } else {
            ui.entries.getOrNull(index)?.let { act(FileAction.Tap(it)) }
        }
    }

    // A pick with no way in at all yet: what the grant is, and the picker that needs none.
    val noWayIn = ui.request != null && !ui.access && ui.trees.isEmpty()

    androidx.compose.runtime.CompositionLocalProvider(LocalFilePlugins provides callbacks.plugins()) {
        FullBleedTool(
            state = state,
            title = "",
            onClose = onClose,
            extraHeight = if (ui.expanded || faceOpen) screen else 0.dp,
            compact = typing != null,
            compactHeight = if (typing?.field == FilesTextField.SEARCH) 300.dp else 240.dp,
            headerActions = {
                when {
                    typing != null -> TypingHeader(
                        text = typing.text,
                        placeholder = stringResource(
                            when (typing.field) {
                                FilesTextField.SEARCH -> R.string.ime_files_search_hint
                                FilesTextField.RENAME -> R.string.ime_files_rename_hint
                                FilesTextField.NEW_FOLDER -> R.string.ime_files_new_folder_hint
                                FilesTextField.ZIP_NAME -> R.string.ime_files_zip_name_hint
                            },
                        ),
                        pending = ui.searching,
                        act = act,
                    )
                    ui.selection.isNotEmpty() && !faceOpen -> SelectionHeader(ui, act) { selectionMenu = !selectionMenu }
                    else -> Header(ui, browsable = !ui.locked && !noWayIn, act) { overflow = !overflow }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                val info = ui.info
                val preview = ui.preview
                when {
                    ui.locked -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.ime_files_locked_title),
                        body = stringResource(R.string.ime_files_locked_body),
                    )
                    noWayIn -> NoAccess(act)
                    info != null -> InfoFace(info, act)
                    preview != null -> PreviewFace(ui, preview, act)
                    else -> Column(Modifier.fillMaxSize()) {
                        if (searchMode) SearchFiltersRow(ui.filters, act)
                        if (historyOpen) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { RecentSearches(ui.searches, act) }
                        } else if (ui.twoPanes) {
                            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                                ui.panes.forEachIndexed { index, pane ->
                                    if (index > 0) Spacer(Modifier.width(4.dp))
                                    PaneColumn(ui, index, pane, focusedRow, act, Modifier.weight(1f))
                                }
                            }
                        } else {
                            Crumbs(ui, ui.pane, active = true, act)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Listing(ui, ui.pane, active = true, compact = false, focusedRow = focusedRow, act = act)
                            }
                        }
                        BottomBar(ui, act)
                    }
                }

                Scrimmed(overflow && !faceOpen, onDismiss = { overflow = false }) {
                    OverflowMenu(ui, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp)) { action ->
                        overflow = false
                        act(action)
                    }
                }
                Scrimmed(selectionMenu && ui.selection.isNotEmpty() && !faceOpen, onDismiss = { selectionMenu = false }) {
                    SelectionMenu(ui, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp)) { action ->
                        selectionMenu = false
                        act(action)
                    }
                }
                Scrimmed(ui.confirmDelete, onDismiss = { act(FileAction.AnswerDelete(false)) }) {
                    DeleteCard(ui.selection.size, Modifier.align(Alignment.Center), act)
                }
                Scrimmed(ui.confirmRemoveTree != null, onDismiss = { act(FileAction.AnswerRemoveTree(false)) }) {
                    ui.confirmRemoveTree?.let { RemoveTreeCard(it.label, Modifier.align(Alignment.Center), act) }
                }
                Scrimmed(ui.clash != null, onDismiss = { act(FileAction.CancelClash) }) {
                    ui.clash?.let { ClashCard(it, Modifier.align(Alignment.Center), act) }
                }
                NoticeLine(ui.notice, ui.undo, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), act)
            }
        }
    }
}

/** The header: what the panel is for, and its own buttons: search, grid or rows, two lists (and swap), taller, ⋮. */
@Composable
private fun RowScope.Header(ui: FileManagerUi, browsable: Boolean, act: (FileAction) -> Unit, onMore: () -> Unit) {
    val kb = LocalKbTheme.current
    val request = ui.request
    val title = when {
        request == null -> R.string.ime_tool_files
        request.mimes == listOf("image/*") && request.limit == 1 -> R.string.ime_files_title_picture
        request.limit == 1 -> R.string.ime_files_title_one
        else -> R.string.ime_files_title_many
    }
    Text(
        stringResource(title),
        color = kb.suggestionText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = 6.dp),
    )
    if (browsable) {
        HeaderButton(Icons.Outlined.Search, stringResource(R.string.ime_files_search_desc)) { act(FileAction.SearchFocus) }
        HeaderButton(
            if (ui.grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
            stringResource(if (ui.grid) R.string.ime_files_list_view_desc else R.string.ime_files_grid_view_desc),
        ) { act(FileAction.ToggleLayout) }
        if (ui.twoPanes) HeaderButton(Icons.Outlined.SwapHoriz, stringResource(R.string.ime_files_swap_desc)) { act(FileAction.SwapPanes) }
        HeaderButton(
            Icons.Outlined.VerticalSplit,
            stringResource(if (ui.twoPanes) R.string.ime_files_one_pane_desc else R.string.ime_files_two_panes_desc),
        ) { act(FileAction.TogglePanes) }
    }
    if (request != null) {
        HeaderButton(Icons.Outlined.FileOpen, stringResource(R.string.ime_files_system_picker_desc)) { act(FileAction.UseSystemPicker) }
    }
    HeaderButton(
        if (ui.expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
        stringResource(if (ui.expanded) R.string.ime_files_collapse_desc else R.string.ime_files_expand_desc),
    ) { act(FileAction.ToggleExpanded) }
    if (browsable) HeaderButton(Icons.Outlined.MoreVert, stringResource(R.string.ime_files_more_desc)) { onMore() }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    RoundButton(icon = icon, description = description, size = 34.dp, onClick = onClick)
    Spacer(Modifier.width(4.dp))
}

/** While a box has the keys, the header is the box and a Done. */
@Composable
private fun RowScope.TypingHeader(text: String, placeholder: String, pending: Boolean, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (pending) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(14.dp), color = kb.secondaryText, strokeWidth = 1.5.dp)
        }
    }
    ToolPanelChip(stringResource(R.string.ime_files_done), selected = true) { act(FileAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

/**
 * One of two lists side by side (8A). The active one is outlined in the
 * accent; a touch on the other makes it the active one first, then does
 * what it does.
 */
@Composable
private fun PaneColumn(
    ui: FileManagerUi,
    index: Int,
    pane: FilePane,
    focusedRow: Int?,
    act: (FileAction) -> Unit,
    modifier: Modifier,
) {
    val kb = LocalKbTheme.current
    val active = index == ui.active
    val paneAct: (FileAction) -> Unit = { action ->
        if (!active) act(FileAction.FocusPane(index))
        act(action)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .border(1.5.dp, if (active) kb.accent.copy(alpha = 0.75f) else kb.secondaryText.copy(alpha = 0.22f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act(FileAction.FocusPane(index)) },
    ) {
        Crumbs(ui, pane, active, paneAct)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Listing(ui, pane, active, compact = true, focusedRow = if (active) focusedRow else null, act = paneAct)
        }
    }
}

/** A pick with no grant yet and no added folder: what it is, the grant, and the picker that needs none. */
@Composable
private fun NoAccess(act: (FileAction) -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Folder,
        title = stringResource(R.string.ime_files_access_title),
        body = stringResource(R.string.ime_files_access_body),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.ime_files_access_allow), primary = true) { act(FileAction.AskPermission) }
                PillButton(stringResource(R.string.ime_files_access_pick)) { act(FileAction.UseSystemPicker) }
            }
            ToolPanelChip(stringResource(R.string.ime_files_access_always)) { act(FileAction.AlwaysSystemPicker) }
        }
    }
}
