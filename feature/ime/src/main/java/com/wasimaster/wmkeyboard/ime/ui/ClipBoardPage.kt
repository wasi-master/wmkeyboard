package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardEntry
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dashboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sync
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.board.ClipBoardAction
import com.wasimaster.wmkeyboard.ime.board.ClipBoardNotice
import com.wasimaster.wmkeyboard.ime.board.ClipBoardUi
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton

/**
 * The clipboard panel's Board view (#1201): the clips on the shared board,
 * newest first, each one a tap from the field and a button from this phone's
 * clipboard, its editor or gone. The + lists this phone's text clips to send.
 * Drawn in the history's place inside the clipboard panel, in the keyboard's
 * theme, with the panel's own header and back.
 */
@Composable
internal fun ClipBoardPage(state: KeyboardUiState, ui: ClipBoardUi, act: (ClipBoardAction) -> Unit) {
    FullBleedTool(
        state = state,
        title = "",
        onClose = { act(if (ui.sending) ClipBoardAction.HideSend else ClipBoardAction.Close) },
        headerActions = { BoardHeader(ui, act) },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (ui.sending) {
                SendList(state.clipboardItems, ui.sent, act)
            } else {
                Entries(ui, act)
            }
            val asked = ui.confirmDelete
            AnimatedVisibility(
                visible = asked != null,
                enter = fadeIn(tween(if (state.settings.reduceMotion) 0 else 120)),
                exit = fadeOut(tween(if (state.settings.reduceMotion) 0 else 100)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { act(ClipBoardAction.AnswerDelete(false)) },
                    contentAlignment = Alignment.Center,
                ) { DeleteQuestion(act) }
            }
            BoardNotice(ui.notice, state.settings.reduceMotion, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                act(ClipBoardAction.NoticeDismiss)
            }
        }
    }
}

/** The name and where the board stands, then the + and the sync button, or Done while sending. */
@Composable
private fun RowScope.BoardHeader(ui: ClipBoardUi, act: (ClipBoardAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.weight(1f).padding(start = 6.dp)) {
        Text(
            stringResource(if (ui.sending) R.string.ime_board_send_title else R.string.ime_board_title),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            boardStatus(ui),
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (ui.sending) {
        ToolPanelChip(stringResource(R.string.ime_board_send_done), selected = true) { act(ClipBoardAction.HideSend) }
    } else {
        RoundButton(icon = Icons.Outlined.Add, description = stringResource(R.string.ime_board_send_desc), size = 34.dp) {
            act(ClipBoardAction.ShowSend)
        }
        Spacer(Modifier.width(4.dp))
        if (ui.syncing) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), color = kb.secondaryText, strokeWidth = 2.dp)
            }
        } else {
            RoundButton(icon = Icons.Outlined.Sync, description = stringResource(R.string.ime_board_sync_desc), size = 34.dp) {
                act(ClipBoardAction.Refresh)
            }
        }
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun boardStatus(ui: ClipBoardUi): String =
    when {
        ui.syncing -> stringResource(R.string.ime_board_status_syncing)
        ui.error.isNotEmpty() && ui.pending -> stringResource(R.string.ime_board_status_waiting_offline)
        ui.error.isNotEmpty() -> stringResource(R.string.ime_board_status_failed)
        ui.pending -> stringResource(R.string.ime_board_status_waiting)
        ui.syncedAtMs > 0 -> stringResource(
            R.string.ime_board_status_synced,
            DateUtils.getRelativeTimeSpanString(ui.syncedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString(),
        )
        else -> stringResource(R.string.ime_board_status_never)
    }

@Composable
private fun Entries(ui: ClipBoardUi, act: (ClipBoardAction) -> Unit) {
    if (ui.entries.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Dashboard,
            title = stringResource(R.string.ime_board_empty_title),
            body = stringResource(R.string.ime_board_empty_body),
        ) {
            PillButton(stringResource(R.string.ime_board_send_desc), icon = Icons.Outlined.Add, primary = true) {
                act(ClipBoardAction.ShowSend)
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ui.entries.size, key = { ui.entries[it].id }) { index ->
            EntryCard(ui.entries[index], act)
        }
    }
}

/** One entry: a tap types it, the buttons copy, edit or take it off. */
@Composable
private fun EntryCard(entry: BoardEntry, act: (ClipBoardAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val detail = remember(entry.from, entry.createdAtMs) {
        val time = if (entry.createdAtMs > 0) {
            DateUtils.getRelativeTimeSpanString(entry.createdAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } else {
            ""
        }
        listOf(entry.from, time).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { act(ClipBoardAction.Paste(entry)) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            entry.text,
            color = kb.suggestionText,
            fontSize = 14.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (entry.tags.isEmpty()) detail else detail + "  #" + entry.tags.joinToString(" #")).ifEmpty { " " },
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            EntryButton(Icons.Outlined.ContentCopy, stringResource(R.string.ime_board_copy_desc)) { act(ClipBoardAction.Copy(entry)) }
            EntryButton(Icons.Outlined.Edit, stringResource(R.string.ime_board_edit_desc)) { act(ClipBoardAction.Edit(entry)) }
            EntryButton(Icons.Outlined.Delete, stringResource(R.string.ime_board_delete_desc)) { act(ClipBoardAction.AskDelete(entry)) }
        }
    }
}

@Composable
private fun EntryButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Icon(
        icon,
        contentDescription = description,
        tint = kb.secondaryText,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(9.dp),
    )
}

/** This phone's clips that may go on the board: text that is not a secret, newest first. */
@Composable
private fun SendList(items: List<ClipItem>, sent: Set<Long>, act: (ClipBoardAction) -> Unit) {
    val kb = LocalKbTheme.current
    val sendable = remember(items) { items.filter { it.kind.isTextual && !it.sensitive && it.text.isNotBlank() } }
    if (sendable.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ime_board_send_empty), color = kb.secondaryText, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(sendable.size, key = { sendable[it].id }) { index ->
            val clip = sendable[index]
            val done = clip.id in sent
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !done) { act(ClipBoardAction.Send(clip)) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    clip.text,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (done) Icons.Outlined.Check else Icons.Outlined.CloudUpload,
                    contentDescription = stringResource(if (done) R.string.ime_board_sent_desc else R.string.ime_board_send_one_desc),
                    tint = if (done) kb.accent else kb.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Taking an entry off takes it off every device, which the card says. */
@Composable
private fun DeleteQuestion(act: (ClipBoardAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        Modifier
            .padding(horizontal = 24.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.ime_board_delete_title), color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_board_delete_body), color = kb.popupText, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.ime_board_delete_no)) { act(ClipBoardAction.AnswerDelete(false)) }
            PillButton(stringResource(R.string.ime_board_delete_yes), primary = true) { act(ClipBoardAction.AnswerDelete(true)) }
        }
    }
}

@Composable
private fun BoardNotice(notice: ClipBoardNotice?, reduceMotion: Boolean, modifier: Modifier, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(if (reduceMotion) 0 else 150)),
        exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
        modifier = modifier,
    ) {
        var last by remember { mutableStateOf(notice) }
        if (notice != null) last = notice
        val n = last ?: return@AnimatedVisibility
        Text(
            stringResource(
                when (n) {
                    ClipBoardNotice.SENT -> R.string.ime_board_notice_sent
                    ClipBoardNotice.COPIED -> R.string.ime_board_notice_copied
                    ClipBoardNotice.REFUSED -> R.string.ime_board_notice_refused
                    ClipBoardNotice.DELETED -> R.string.ime_board_notice_deleted
                },
            ),
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

/**
 * The way into the Board view from the history: a slim row under the tabs,
 * there only while a place is picked for the board.
 */
@Composable
internal fun ClipBoardEntryRow(ui: ClipBoardUi, act: (ClipBoardAction) -> Unit) {
    if (!ui.configured) return
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { act(ClipBoardAction.Open) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Dashboard, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.ime_board_title),
            color = kb.suggestionText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (ui.pending) {
            Icon(
                Icons.Outlined.CloudUpload,
                contentDescription = stringResource(R.string.ime_board_status_waiting),
                tint = kb.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(ui.entries.size.toString(), color = kb.secondaryText, fontSize = 11.sp)
    }
}
