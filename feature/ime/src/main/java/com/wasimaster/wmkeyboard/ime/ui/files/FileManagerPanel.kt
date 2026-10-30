package com.wasimaster.wmkeyboard.ime.ui.files

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileVolume
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AudioFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Movie
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Photo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sort
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileClip
import com.wasimaster.wmkeyboard.ime.files.FileManagerCallbacks
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.files.FileNotice
import com.wasimaster.wmkeyboard.ime.files.FileOpKind
import com.wasimaster.wmkeyboard.ime.files.FileOpUi
import com.wasimaster.wmkeyboard.ime.files.FileView
import com.wasimaster.wmkeyboard.ime.files.FilesTextField
import com.wasimaster.wmkeyboard.ime.files.fileCrumbs
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
import com.wasimaster.wmkeyboard.ime.ui.gallery.MediaThumb
import com.wasimaster.wmkeyboard.ime.ui.gallery.rememberGalleryImageLoader
import com.wasimaster.wmkeyboard.ime.ui.gallery.trimGalleryImageMemory

/**
 * The file manager a tool opens when it wants a file, so picking one does not
 * mean leaving the field. A sheet at the keyboard's height that grows like the
 * gallery's: the places, the folders, a selection, and what can be done to it.
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
    // The ⋮ menu, drawn inside the sheet rather than as a popup window, which
    // would sit over the app behind the keyboard.
    var overflow by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { trimGalleryImageMemory() } }

    val rows = placeRows(ui)
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    val listed = if (ui.view == FileView.Places && ui.query == null) rows.size else ui.entries.size
    PanelFocusTarget(
        panel = PanelMode.FILES,
        region = FocusRegion.RESULTS,
        count = if (typing == null) listed else 0,
        columns = 1,
    ) { index ->
        if (ui.view == FileView.Places && ui.query == null) {
            rows.getOrNull(index)?.let { act(it.action) }
        } else {
            ui.entries.getOrNull(index)?.let { act(FileAction.Tap(it)) }
        }
    }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = if (ui.expanded) screen else 0.dp,
        compact = typing != null,
        compactHeight = 240.dp,
        headerActions = {
            if (typing != null) {
                TypingHeader(
                    text = typing.text,
                    placeholder = stringResource(
                        when (typing.field) {
                            FilesTextField.SEARCH -> R.string.ime_files_search_hint
                            FilesTextField.RENAME -> R.string.ime_files_rename_hint
                            FilesTextField.NEW_FOLDER -> R.string.ime_files_new_folder_hint
                        },
                    ),
                    pending = ui.searching,
                    act = act,
                )
            } else {
                Header(ui, act) { overflow = !overflow }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (!ui.access) {
                NoAccess(act)
            } else {
                Column(Modifier.fillMaxSize()) {
                    Crumbs(ui, act)
                    Box(Modifier.weight(1f).fillMaxWidth()) { Listing(ui, rows, focusedRow, act) }
                    BottomBar(ui, act)
                }
            }

            Scrimmed(overflow, onDismiss = { overflow = false }) {
                OverflowMenu(ui, Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp)) { action ->
                    overflow = false
                    act(action)
                }
            }
            Scrimmed(ui.confirmDelete, onDismiss = { act(FileAction.AnswerDelete(false)) }) {
                DeleteCard(ui.selection.size, Modifier.align(Alignment.Center), act)
            }
            NoticeLine(ui.notice, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                act(FileAction.NoticeDismiss)
            }
        }
    }
}

/** The header: what is being picked, and the sheet's own buttons. */
@Composable
private fun RowScope.Header(ui: FileManagerUi, act: (FileAction) -> Unit, onMore: () -> Unit) {
    val kb = LocalKbTheme.current
    val request = ui.request
    val title = when {
        request == null -> R.string.ime_files_title_many
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
    if (ui.access) {
        RoundButton(icon = Icons.Outlined.Search, description = stringResource(R.string.ime_files_search_desc), size = 34.dp) {
            act(FileAction.SearchFocus)
        }
        Spacer(Modifier.width(4.dp))
    }
    RoundButton(icon = Icons.Outlined.FileOpen, description = stringResource(R.string.ime_files_system_picker_desc), size = 34.dp) {
        act(FileAction.UseSystemPicker)
    }
    Spacer(Modifier.width(4.dp))
    RoundButton(
        icon = if (ui.expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
        description = stringResource(if (ui.expanded) R.string.ime_files_collapse_desc else R.string.ime_files_expand_desc),
        size = 34.dp,
    ) { act(FileAction.ToggleExpanded) }
    if (ui.access) {
        Spacer(Modifier.width(4.dp))
        RoundButton(icon = Icons.Outlined.MoreVert, description = stringResource(R.string.ime_files_more_desc), size = 34.dp) {
            onMore()
        }
    }
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

/** Where the list is: Places, the volume, each folder down, any of them a tap away. A search says so instead. */
@Composable
private fun Crumbs(ui: FileManagerUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val query = ui.query
        if (query != null) {
            Crumb(query, current = true) { act(FileAction.SearchFocus) }
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_files_search_clear_desc),
                tint = kb.secondaryText,
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable { act(FileAction.ClearSearch) }.padding(5.dp),
            )
            return@Row
        }
        Crumb(stringResource(R.string.ime_files_places), current = ui.view == FileView.Places) { act(FileAction.OpenPlaces) }
        when (val view = ui.view) {
            FileView.Places -> Unit
            FileView.Recent -> {
                CrumbGap()
                Crumb(stringResource(R.string.ime_files_recent), current = true) {}
            }
            is FileView.Folder -> {
                val crumbs = fileCrumbs(view.path, ui.volumes)
                crumbs.forEachIndexed { index, crumb ->
                    CrumbGap()
                    Crumb(crumb.label, current = index == crumbs.lastIndex) { act(FileAction.OpenFolder(crumb.path)) }
                }
            }
        }
    }
}

@Composable
private fun CrumbGap() {
    Icon(
        Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = LocalKbTheme.current.secondaryText,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Text(
        label,
        color = if (current) kb.suggestionText else kb.secondaryText,
        fontSize = 12.sp,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** One row of the Places list. */
private data class PlaceRow(val label: String, val icon: ImageVector, val action: FileAction)

@Composable
private fun placeRows(ui: FileManagerUi): List<PlaceRow> = buildList {
    add(PlaceRow(stringResource(R.string.ime_files_recent), Icons.Outlined.History, FileAction.OpenRecent))
    ui.volumes.forEach { volume -> add(volumeRow(volume)) }
    ui.shortcuts.forEach { shortcut ->
        add(PlaceRow(stringResource(shortcutLabel(shortcut)), shortcutIcon(shortcut), FileAction.OpenShortcut(shortcut)))
    }
}

private fun volumeRow(volume: FileVolume) = PlaceRow(
    volume.label,
    if (volume.primary) Icons.Outlined.Smartphone else Icons.Outlined.Storage,
    FileAction.OpenFolder(volume.path),
)

private fun shortcutLabel(shortcut: FileShortcut): Int = when (shortcut) {
    FileShortcut.DOWNLOADS -> R.string.ime_files_downloads
    FileShortcut.DOCUMENTS -> R.string.ime_files_documents
    FileShortcut.PICTURES -> R.string.ime_files_pictures
    FileShortcut.CAMERA -> R.string.ime_files_camera
    FileShortcut.MUSIC -> R.string.ime_files_music
    FileShortcut.MOVIES -> R.string.ime_files_movies
}

private fun shortcutIcon(shortcut: FileShortcut): ImageVector = when (shortcut) {
    FileShortcut.DOWNLOADS -> Icons.Outlined.Download
    FileShortcut.DOCUMENTS -> Icons.Outlined.Description
    FileShortcut.PICTURES -> Icons.Outlined.Image
    FileShortcut.CAMERA -> Icons.Outlined.Photo
    FileShortcut.MUSIC -> Icons.Outlined.MusicNote
    FileShortcut.MOVIES -> Icons.Outlined.Movie
}

/** The list itself: the places, a folder, the recent files, or what a search found. */
@Composable
private fun Listing(ui: FileManagerUi, rows: List<PlaceRow>, focusedRow: Int?, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val places = ui.view == FileView.Places && ui.query == null
    // Each folder opens at its top, and comes back there.
    key(ui.view, ui.query) {
        when {
            places -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(rows.size) { index ->
                    val row = rows[index]
                    PlainRow(row.label, row.icon, focused = focusedRow == index) { act(row.action) }
                }
            }
            ui.loading || (ui.searching && ui.entries.isEmpty()) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = kb.secondaryText, strokeWidth = 2.dp)
            }
            ui.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val filtered = ui.request?.mimes?.isNotEmpty() == true
                Text(
                    stringResource(
                        when {
                            ui.query != null -> R.string.ime_files_search_empty
                            ui.unreadable -> R.string.ime_files_unreadable
                            filtered -> R.string.ime_files_empty_filtered
                            else -> R.string.ime_files_empty
                        },
                    ),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                )
            }
            else -> {
                val list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 4.dp)) {
                    items(ui.entries.size, key = { ui.entries[it].path }) { index ->
                        val entry = ui.entries[index]
                        EntryRow(entry, picked = entry.path in ui.selection, focused = focusedRow == index, act)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainRow(label: String, icon: ImageVector, focused: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) kb.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = kb.suggestionText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EntryRow(entry: FileEntry, picked: Boolean, focused: Boolean, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val detail = remember(entry.path, entry.size, entry.modifiedMs) {
        val date = DateUtils.formatDateTime(
            context,
            entry.modifiedMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
        if (entry.isDirectory) date else Formatter.formatShortFileSize(context, entry.size) + " · " + date
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    picked -> kb.accent.copy(alpha = 0.22f)
                    focused -> kb.accent.copy(alpha = 0.18f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = { act(FileAction.Tap(entry)) },
                onLongClick = { act(FileAction.Hold(entry)) },
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(entry, picked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = kb.suggestionText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(detail, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** A tick when picked, a picture's own thumbnail where there is one, otherwise what kind of file it is. */
@Composable
private fun EntryIcon(entry: FileEntry, picked: Boolean) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val picture = entry.mime.startsWith("image/")
    val video = entry.mime.startsWith("video/")
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(kb.chip), contentAlignment = Alignment.Center) {
        if ((picture || video) && !picked) {
            val thumb = remember(entry.path, entry.modifiedMs) {
                MediaThumb(FilePlaces.uriOf(context, entry.path), video, THUMB_PX, entry.modifiedMs / 1000)
            }
            AsyncImage(
                model = thumb,
                contentDescription = null,
                imageLoader = rememberGalleryImageLoader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                when {
                    picked -> Icons.Outlined.Check
                    entry.isDirectory -> Icons.Outlined.Folder
                    entry.mime.startsWith("audio/") -> Icons.Outlined.AudioFile
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = if (picked) kb.accent else kb.secondaryText,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Whichever one thing is going on: a copy under way, the selection, or a clip to put down. */
@Composable
private fun BottomBar(ui: FileManagerUi, act: (FileAction) -> Unit) {
    val op = ui.op
    val clip = ui.clip
    when {
        op != null -> OpBar(op, act)
        ui.selection.isNotEmpty() -> SelectionBar(ui, act)
        clip != null -> ClipBar(clip, canPaste = ui.folder != null && ui.query == null, act)
    }
}

@Composable
private fun OpBar(op: FileOpUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    BarRow {
        CircularProgressIndicator(Modifier.size(16.dp), color = kb.secondaryText, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        val at = (op.done + 1).coerceAtMost(op.total)
        Text(
            stringResource(
                when (op.kind) {
                    FileOpKind.COPY -> R.string.ime_files_op_copy
                    FileOpKind.MOVE -> R.string.ime_files_op_move
                    FileOpKind.DELETE -> R.string.ime_files_op_delete
                },
                at,
                op.total,
            ),
            color = kb.suggestionText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        ToolPanelChip(stringResource(R.string.ime_files_op_cancel)) { act(FileAction.CancelOp) }
    }
}

@Composable
private fun SelectionBar(ui: FileManagerUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    BarRow {
        Icon(
            Icons.Outlined.Close,
            contentDescription = stringResource(R.string.ime_files_clear_desc),
            tint = kb.secondaryText,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(50)).clickable { act(FileAction.ClearSelection) }.padding(6.dp),
        )
        Text(
            pluralStringResource(R.plurals.ime_files_selected, ui.selection.size, ui.selection.size),
            color = kb.suggestionText,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.selection.size == 1) {
                ToolPanelChip(stringResource(R.string.ime_files_rename_desc)) { act(FileAction.Rename) }
            }
            ToolPanelChip(stringResource(R.string.ime_files_copy_desc)) { act(FileAction.Copy) }
            ToolPanelChip(stringResource(R.string.ime_files_move_desc)) { act(FileAction.Move) }
            ToolPanelChip(stringResource(R.string.ime_files_delete_desc)) { act(FileAction.AskDelete) }
        }
        Spacer(Modifier.width(6.dp))
        PillButton(
            stringResource(R.string.ime_files_add),
            primary = true,
            enabled = ui.canPick,
            height = 32.dp,
        ) { act(FileAction.Add) }
    }
}

@Composable
private fun ClipBar(clip: FileClip, canPaste: Boolean, act: (FileAction) -> Unit) {
    BarRow {
        PillButton(
            pluralStringResource(
                if (clip.move) R.plurals.ime_files_paste_move else R.plurals.ime_files_paste_copy,
                clip.paths.size,
                clip.paths.size,
            ),
            modifier = Modifier.weight(1f),
            primary = true,
            enabled = canPaste,
            height = 32.dp,
        ) { act(FileAction.Paste) }
        Spacer(Modifier.width(6.dp))
        ToolPanelChip(stringResource(R.string.ime_files_paste_cancel)) { act(FileAction.ClearClip) }
    }
}

@Composable
private fun BarRow(content: @Composable RowScope.() -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(kb.popup)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun OverflowMenu(ui: FileManagerUi, modifier: Modifier, pick: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .widthIn(min = 200.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(vertical = 4.dp),
    ) {
        if (ui.folder != null) {
            OverflowRow(Icons.Outlined.Folder, stringResource(R.string.ime_files_new_folder)) { pick(FileAction.NewFolder) }
        }
        OverflowRow(Icons.Outlined.Sort, stringResource(R.string.ime_files_sort_name)) { pick(FileAction.SetSort(FileSort.NAME)) }
        OverflowRow(Icons.Outlined.Sort, stringResource(R.string.ime_files_sort_date)) { pick(FileAction.SetSort(FileSort.DATE)) }
        OverflowRow(Icons.Outlined.Sort, stringResource(R.string.ime_files_sort_size)) { pick(FileAction.SetSort(FileSort.SIZE)) }
        OverflowRow(
            if (ui.showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            stringResource(if (ui.showHidden) R.string.ime_files_hide_hidden else R.string.ime_files_show_hidden),
        ) { pick(FileAction.ToggleHidden) }
    }
}

@Composable
private fun OverflowRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = kb.popupText, fontSize = 14.sp)
    }
}

/** Deleting is permanent, so it always asks, and says so. */
@Composable
private fun DeleteCard(count: Int, modifier: Modifier, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .padding(horizontal = 24.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(16.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.ime_files_delete_title, count, count),
            color = kb.popupText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_files_delete_body), color = kb.popupText, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.ime_files_delete_no)) { act(FileAction.AnswerDelete(false)) }
            PillButton(stringResource(R.string.ime_files_delete_yes), primary = true) { act(FileAction.AnswerDelete(true)) }
        }
    }
}

/** No grant yet: what it is, the grant, and the picker that needs none. */
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

/** Something to dismiss behind a card: a soft scrim that a tap clears. */
@Composable
private fun Scrimmed(visible: Boolean, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 120)),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 100)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            content = content,
        )
    }
}

@Composable
private fun NoticeLine(notice: FileNotice?, modifier: Modifier, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 150)),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 150)),
        modifier = modifier,
    ) {
        var last by remember { mutableStateOf(notice) }
        if (notice != null) last = notice
        val n = last ?: return@AnimatedVisibility
        Text(
            noticeText(n),
            color = kb.popupText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(kb.popup)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun noticeText(n: FileNotice): String = when (n.kind) {
    FileNotice.Kind.COPIED -> pluralStringResource(R.plurals.ime_files_notice_copied, n.count, n.count)
    FileNotice.Kind.MOVED -> pluralStringResource(R.plurals.ime_files_notice_moved, n.count, n.count)
    FileNotice.Kind.DELETED -> pluralStringResource(R.plurals.ime_files_notice_deleted, n.count, n.count)
    FileNotice.Kind.FAILED -> pluralStringResource(R.plurals.ime_files_notice_failed, n.count, n.count)
    FileNotice.Kind.LIMIT -> pluralStringResource(R.plurals.ime_files_notice_limit, n.count, n.count)
    FileNotice.Kind.BAD_NAME -> stringResource(R.string.ime_files_notice_bad_name)
}

private const val THUMB_PX = 128
