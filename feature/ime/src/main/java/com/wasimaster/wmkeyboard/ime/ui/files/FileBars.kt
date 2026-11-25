package com.wasimaster.wmkeyboard.ime.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.files.Clash
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Deselect
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderZip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LibraryAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenWith
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sort
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.ClashAsk
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileClip
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.files.FileNotice
import com.wasimaster.wmkeyboard.ime.files.FileOpKind
import com.wasimaster.wmkeyboard.ime.files.FileOpUi
import com.wasimaster.wmkeyboard.ime.files.FileUndoUi
import com.wasimaster.wmkeyboard.ime.files.UndoKind
import com.wasimaster.wmkeyboard.ime.files.folderCrumbs
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn

/**
 * The header while something is picked (3A): clear, how many and how big,
 * Select all, Invert, and the less common actions behind ⋮.
 */
@Composable
internal fun RowScope.SelectionHeader(ui: FileManagerUi, act: (FileAction) -> Unit, onMore: () -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    RoundButton(icon = Icons.Outlined.Close, description = stringResource(R.string.ime_files_clear_desc), size = 34.dp) { act(FileAction.ClearSelection) }
    Column(Modifier.weight(1f).padding(start = 8.dp)) {
        Text(
            pluralStringResource(R.plurals.ime_files_selected, ui.selection.size, ui.selection.size),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (ui.selectionBytes > 0) Text(bytes(context, ui.selectionBytes), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
    }
    RoundButton(icon = Icons.Outlined.SelectAll, description = stringResource(R.string.ime_files_select_all), size = 34.dp) { act(FileAction.SelectAll) }
    Spacer(Modifier.width(4.dp))
    RoundButton(icon = Icons.Outlined.Deselect, description = stringResource(R.string.ime_files_invert), size = 34.dp) { act(FileAction.InvertSelection) }
    Spacer(Modifier.width(4.dp))
    RoundButton(icon = Icons.Outlined.MoreVert, description = stringResource(R.string.ime_files_more_desc), size = 34.dp) { onMore() }
    Spacer(Modifier.width(4.dp))
}

/** One slot of the action bar: an icon in a pill, its name under it. */
internal data class BarAction(
    val icon: ImageVector,
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** The bar along the bottom (3A): labelled icons, the first in the accent when it is the one thing to do. */
@Composable
internal fun ActionStrip(actions: List<BarAction>) {
    val kb = LocalKbTheme.current
    Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 4.dp)) {
        actions.forEach { action ->
            val alpha = if (action.enabled) 1f else 0.38f
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = action.enabled, onClick = action.onClick)
                    .padding(top = 6.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bg = if (action.primary) kb.accent.copy(alpha = alpha) else Color.Transparent
                val fg = if (action.primary) maxContrastOn(kb.accent) else kb.popupText.copy(alpha = alpha)
                Box(Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(action.label, color = kb.popupText.copy(alpha = alpha), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What the bar offers for a selection: Send first in the Files tool, Add first in a pick. */
@Composable
internal fun selectionActions(ui: FileManagerUi, act: (FileAction) -> Unit): List<BarAction> {
    val across = ui.otherFolder?.let { folderCrumbs(it, ui.volumes).lastOrNull()?.label }
    return buildList {
        if (ui.request != null) {
            add(BarAction(Icons.Outlined.Check, stringResource(R.string.ime_files_add), primary = true, enabled = ui.canPick) { act(FileAction.Add) })
        } else {
            add(BarAction(Icons.Outlined.Send, stringResource(R.string.ime_files_send), primary = true, enabled = ui.selectionIsFiles) { act(FileAction.Send) })
            add(BarAction(Icons.Outlined.Share, stringResource(R.string.ime_files_share), enabled = ui.selectionIsFiles) { act(FileAction.Share) })
        }
        add(
            BarAction(
                Icons.Outlined.ContentCopy,
                if (across != null) stringResource(R.string.ime_files_copy_to, across) else stringResource(R.string.ime_files_copy_desc),
            ) { act(FileAction.Copy) },
        )
        add(
            BarAction(
                Icons.Outlined.OpenWith,
                if (across != null) stringResource(R.string.ime_files_move_to, across) else stringResource(R.string.ime_files_move_desc),
            ) { act(FileAction.Move) },
        )
        if (ui.request == null) add(BarAction(Icons.Outlined.FolderZip, stringResource(R.string.ime_files_zip), enabled = ui.folder != null) { act(FileAction.Zip) })
        add(BarAction(Icons.Outlined.Delete, stringResource(R.string.ime_files_delete_desc)) { act(FileAction.AskDelete) })
    }
}

/** A copy, move, delete, zip, unzip or send under way (7A): what, the bar, bytes, speed, time left, and what waits. */
@Composable
internal fun OpBar(op: FileOpUi, queued: List<FileOpKind>, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val at = (op.done + 1).coerceAtMost(op.total.coerceAtLeast(1))
            Text(
                stringResource(opLabel(op.kind), at, op.total) + if (op.name.isNotEmpty()) " · ${op.name}" else "",
                color = kb.popupText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            ToolPanelChip(stringResource(R.string.ime_files_op_cancel)) { act(FileAction.CancelOp) }
        }
        val fraction = op.fraction
        if (fraction != null) {
            MeterBar(fraction, kb.accent, Modifier.fillMaxWidth())
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                color = kb.accent,
                trackColor = kb.chip,
            )
        }
        if (op.bytesTotal > 0) {
            Row {
                Text(
                    stringResource(R.string.ime_files_op_bytes, bytes(context, op.bytesDone), bytes(context, op.bytesTotal)) +
                        if (op.bytesPerSecond > 0) " · " + stringResource(R.string.ime_files_op_speed, bytes(context, op.bytesPerSecond)) else "",
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (op.secondsLeft >= 0) {
                    Text(timeLeft(op.secondsLeft), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        queued.firstOrNull()?.let { next ->
            Text(
                stringResource(R.string.ime_files_op_next, stringResource(queuedLabel(next))) +
                    if (queued.size > 1) " +${queued.size - 1}" else "",
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun timeLeft(seconds: Long): String = when {
    seconds < 60 -> pluralStringResource(R.plurals.ime_files_seconds_left, seconds.toInt(), seconds.toInt())
    else -> {
        val minutes = ((seconds + 30) / 60).toInt()
        pluralStringResource(R.plurals.ime_files_minutes_left, minutes, minutes)
    }
}

private fun opLabel(kind: FileOpKind): Int = when (kind) {
    FileOpKind.COPY -> R.string.ime_files_op_copy
    FileOpKind.MOVE -> R.string.ime_files_op_move
    FileOpKind.DELETE -> R.string.ime_files_op_delete
    FileOpKind.ZIP -> R.string.ime_files_op_zip
    FileOpKind.UNZIP -> R.string.ime_files_op_unzip
    FileOpKind.SEND -> R.string.ime_files_op_send
}

private fun queuedLabel(kind: FileOpKind): Int = when (kind) {
    FileOpKind.COPY -> R.string.ime_files_copy_desc
    FileOpKind.MOVE -> R.string.ime_files_move_desc
    FileOpKind.DELETE -> R.string.ime_files_delete_desc
    FileOpKind.ZIP -> R.string.ime_files_zip
    FileOpKind.UNZIP -> R.string.ime_files_unzip
    FileOpKind.SEND -> R.string.ime_files_send
}

@Composable
internal fun ClipBar(clip: FileClip, canPaste: Boolean, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** The menu behind the header's ⋮: new folder, add a folder, sort, hidden files, the view. */
@Composable
internal fun OverflowMenu(ui: FileManagerUi, modifier: Modifier, pick: (FileAction) -> Unit) {
    MenuCard(modifier) {
        if (ui.folder != null) MenuRow(Icons.Outlined.Folder, stringResource(R.string.ime_files_new_folder)) { pick(FileAction.NewFolder) }
        if (ui.folder != null) MenuRow(Icons.Outlined.Info, stringResource(R.string.ime_files_info)) { pick(FileAction.Info) }
        MenuRow(Icons.Outlined.Add, stringResource(R.string.ime_files_add_tree)) { pick(FileAction.AddTree) }
        for ((sort, label) in listOf(
            FileSort.NAME to R.string.ime_files_sort_name,
            FileSort.DATE to R.string.ime_files_sort_date,
            FileSort.SIZE to R.string.ime_files_sort_size,
            FileSort.TYPE to R.string.ime_files_sort_type,
        )) {
            val current = ui.sort == sort
            MenuRow(
                Icons.Outlined.Sort,
                stringResource(label) + if (current) (if (ui.descending) " ↓" else " ↑") else "",
                selected = current,
            ) { pick(FileAction.SetSort(sort)) }
        }
        MenuRow(
            if (ui.prefs.showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            stringResource(if (ui.prefs.showHidden) R.string.ime_files_hide_hidden else R.string.ime_files_show_hidden),
        ) { pick(FileAction.ToggleHidden) }
    }
}

/** The menu behind the selection header's ⋮: what applies to one thing, or to the open folder. */
@Composable
internal fun SelectionMenu(ui: FileManagerUi, modifier: Modifier, pick: (FileAction) -> Unit) {
    val one = ui.selection.size == 1
    val oneFile = one && ui.selectedFolders.isEmpty()
    val oneFolder = one && ui.selectedFolders.isNotEmpty()
    val zip = oneFile && ui.selection.first().let { com.wasimaster.wmkeyboard.core.files.FileKinds.isZip(it.substringAfterLast('/')) }
    MenuCard(modifier) {
        if (one) MenuRow(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.ime_files_rename_desc)) { pick(FileAction.Rename) }
        if (ui.request == null && ui.folder != null) MenuRow(Icons.Outlined.LibraryAdd, stringResource(R.string.ime_files_duplicate)) { pick(FileAction.Duplicate) }
        if (zip && ui.request == null) MenuRow(Icons.Outlined.FolderOpen, stringResource(R.string.ime_files_unzip)) { pick(FileAction.Unzip) }
        if (oneFile) MenuRow(Icons.Outlined.OpenInNew, stringResource(R.string.ime_files_open_with)) { pick(FileAction.OpenWith) }
        if (one) MenuRow(Icons.Outlined.Info, stringResource(R.string.ime_files_info)) { pick(FileAction.Info) }
        if (one) MenuRow(Icons.Outlined.Link, stringResource(R.string.ime_files_copy_path)) { pick(FileAction.CopyPath) }
        if (oneFolder) MenuRow(Icons.Outlined.PushPin, stringResource(R.string.ime_files_pin_desc)) { pick(FileAction.TogglePin) }
    }
}

@Composable
private fun MenuCard(modifier: Modifier, content: @Composable () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) kb.accent else kb.popupText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) kb.accent else kb.popupText, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Deleting is permanent, so it always asks, and says so. */
@Composable
internal fun DeleteCard(count: Int, modifier: Modifier, act: (FileAction) -> Unit) {
    QuestionCard(
        title = pluralStringResource(R.plurals.ime_files_delete_title, count, count),
        body = stringResource(R.string.ime_files_delete_body),
        modifier = modifier,
    ) {
        PillButton(stringResource(R.string.ime_files_delete_no)) { act(FileAction.AnswerDelete(false)) }
        PillButton(stringResource(R.string.ime_files_delete_yes), primary = true) { act(FileAction.AnswerDelete(true)) }
    }
}

@Composable
internal fun RemoveTreeCard(label: String, modifier: Modifier, act: (FileAction) -> Unit) {
    QuestionCard(
        title = stringResource(R.string.ime_files_remove_tree_title, label),
        body = stringResource(R.string.ime_files_remove_tree_body),
        modifier = modifier,
    ) {
        PillButton(stringResource(R.string.ime_files_delete_no)) { act(FileAction.AnswerRemoveTree(false)) }
        PillButton(stringResource(R.string.ime_files_remove_tree_yes), primary = true) { act(FileAction.AnswerRemoveTree(true)) }
    }
}

/**
 * Names already taken where a copy or move is going (7A): which ones, Skip,
 * Replace or Keep both, and whether one answer does for all of them. With
 * that off, it asks about each name in turn.
 */
@Composable
internal fun ClashCard(ask: ClashAsk, modifier: Modifier, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .padding(horizontal = 18.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(16.dp),
    ) {
        val title = if (ask.sameForAll || ask.names.size == 1) {
            pluralStringResource(R.plurals.ime_files_clash_title, ask.names.size, ask.names.size, ask.folderLabel)
        } else {
            stringResource(R.string.ime_files_clash_one, ask.names[ask.index], ask.index + 1, ask.names.size)
        }
        Text(title, color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_files_clash_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 12.5.sp)
        if (ask.sameForAll) {
            Spacer(Modifier.height(6.dp))
            ask.names.take(CLASH_NAMES).forEach { name ->
                Text("• $name", color = kb.popupText, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            if (ask.names.size > CLASH_NAMES) {
                Text(stringResource(R.string.ime_files_clash_more, ask.names.size - CLASH_NAMES), color = kb.secondaryText, fontSize = 12.sp)
            }
        }
        if (ask.names.size > 1) {
            Row(
                Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable { act(FileAction.ToggleClashForAll) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (ask.sameForAll) kb.accent else kb.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ask.sameForAll) Icon(Icons.Outlined.Check, contentDescription = null, tint = maxContrastOn(kb.accent), modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ime_files_clash_all), color = kb.popupText, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.ime_files_clash_skip), modifier = Modifier.weight(1f)) { act(FileAction.AnswerClash(Clash.SKIP)) }
            PillButton(stringResource(R.string.ime_files_clash_replace), modifier = Modifier.weight(1f)) { act(FileAction.AnswerClash(Clash.REPLACE)) }
            PillButton(stringResource(R.string.ime_files_clash_keep), modifier = Modifier.weight(1f), primary = true) { act(FileAction.AnswerClash(Clash.KEEP_BOTH)) }
        }
    }
}

@Composable
private fun QuestionCard(title: String, body: String, modifier: Modifier, buttons: @Composable RowScope.() -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .padding(horizontal = 24.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(16.dp),
    ) {
        Text(title, color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = kb.popupText, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

/** Something to dismiss behind a card: a soft scrim that a tap clears. */
@Composable
internal fun Scrimmed(visible: Boolean, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
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

/** A short line about what just happened, or the Undo chip (7A plus the owner's ask): the change and a way to take it back. */
@Composable
internal fun NoticeLine(notice: FileNotice?, undo: FileUndoUi?, modifier: Modifier, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val showing = undo != null || notice != null
    AnimatedVisibility(
        visible = showing,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 150)),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 150)),
        modifier = modifier,
    ) {
        var lastNotice by remember { mutableStateOf(notice) }
        var lastUndo by remember { mutableStateOf(undo) }
        if (notice != null) lastNotice = notice
        if (undo != null) lastUndo = undo
        val u = undo ?: lastUndo.takeIf { notice == null }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(kb.popup)
                .clickable { act(FileAction.NoticeDismiss) }
                .padding(start = 14.dp, end = if (u != null) 4.dp else 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = when {
                notice != null -> noticeText(notice)
                u != null -> undoText(u)
                else -> lastNotice?.let { noticeText(it) }.orEmpty()
            }
            Text(text, color = kb.popupText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(vertical = 3.dp))
            if (u != null) {
                Spacer(Modifier.width(6.dp))
                IconHint(stringResource(R.string.ime_files_undo)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { act(FileAction.Undo) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Replay, contentDescription = null, tint = kb.accent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ime_files_undo), color = kb.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun undoText(u: FileUndoUi): String = when (u.kind) {
    UndoKind.COPY -> pluralStringResource(R.plurals.ime_files_notice_copied, u.count, u.count)
    UndoKind.MOVE -> pluralStringResource(R.plurals.ime_files_notice_moved, u.count, u.count)
    UndoKind.RENAME -> stringResource(R.string.ime_files_notice_renamed)
    UndoKind.NEW_FOLDER -> stringResource(R.string.ime_files_notice_folder_made)
    UndoKind.ZIP -> stringResource(R.string.ime_files_notice_zipped_one)
    UndoKind.UNZIP -> stringResource(R.string.ime_files_notice_unzipped)
    UndoKind.DUPLICATE -> pluralStringResource(R.plurals.ime_files_notice_duplicated, u.count, u.count)
}

@Composable
private fun noticeText(n: FileNotice): String = when (n.kind) {
    FileNotice.Kind.COPIED -> pluralStringResource(R.plurals.ime_files_notice_copied, n.count, n.count)
    FileNotice.Kind.MOVED -> pluralStringResource(R.plurals.ime_files_notice_moved, n.count, n.count)
    FileNotice.Kind.DELETED -> pluralStringResource(R.plurals.ime_files_notice_deleted, n.count, n.count)
    FileNotice.Kind.FAILED -> pluralStringResource(R.plurals.ime_files_notice_failed, n.count, n.count)
    FileNotice.Kind.LIMIT -> pluralStringResource(R.plurals.ime_files_notice_limit, n.count, n.count)
    FileNotice.Kind.BAD_NAME -> stringResource(R.string.ime_files_notice_bad_name)
    FileNotice.Kind.TREE_REMOVED -> stringResource(R.string.ime_files_notice_tree_removed)
    FileNotice.Kind.NO_APP -> stringResource(R.string.ime_files_notice_no_app)
    FileNotice.Kind.ZIPPED -> pluralStringResource(R.plurals.ime_files_notice_zipped, n.count, n.count)
    FileNotice.Kind.UNZIPPED -> stringResource(R.string.ime_files_notice_unzipped)
    FileNotice.Kind.ZIP_FAILED -> stringResource(R.string.ime_files_notice_zip_failed)
    FileNotice.Kind.SKIPPED -> pluralStringResource(R.plurals.ime_files_notice_skipped, n.count, n.count)
    FileNotice.Kind.UNDONE -> stringResource(R.string.ime_files_notice_undone)
    FileNotice.Kind.UNDO_FAILED -> stringResource(R.string.ime_files_notice_undo_failed)
    FileNotice.Kind.PATH_COPIED -> stringResource(R.string.ime_files_notice_path_copied)
    FileNotice.Kind.TEXT_COPIED -> stringResource(R.string.ime_files_notice_text_copied)
    FileNotice.Kind.PINNED -> stringResource(R.string.ime_files_notice_pinned)
    FileNotice.Kind.UNPINNED -> stringResource(R.string.ime_files_notice_unpinned)
    FileNotice.Kind.FIELD_REFUSED -> stringResource(R.string.ime_files_notice_field_refused)
    FileNotice.Kind.SENT -> pluralStringResource(R.plurals.ime_files_notice_sent, n.count, n.count)
    FileNotice.Kind.TOO_BIG_TO_TYPE -> stringResource(R.string.ime_files_notice_too_big)
    FileNotice.Kind.QUEUED -> stringResource(R.string.ime_files_notice_queued)
}

/** Whether a bar takes the bottom: what is going on wins over the selection, the selection over a clip. */
internal fun FileManagerUi.hasBottomBar(): Boolean = op != null || selection.isNotEmpty() || clip != null

@Composable
internal fun BottomBar(ui: FileManagerUi, act: (FileAction) -> Unit) {
    val op = ui.op
    val clip = ui.clip
    when {
        op != null -> OpBar(op, ui.queued, act)
        ui.selection.isNotEmpty() -> ActionStrip(selectionActions(ui, act))
        clip != null -> ClipBar(clip, canPaste = ui.folder != null && ui.query == null, act)
    }
}

private const val CLASH_NAMES = 4
