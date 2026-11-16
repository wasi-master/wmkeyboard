package com.wasimaster.wmkeyboard.ime.ui.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.notes.vault.VaultMarkdown
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.ChatLink
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdown
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdownColors
import com.wasimaster.wmkeyboard.ime.ui.ClipEditText
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.UndoBarClearance
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.vault.VaultAction
import com.wasimaster.wmkeyboard.ime.vault.VaultCallbacks
import com.wasimaster.wmkeyboard.ime.vault.VaultEntry
import com.wasimaster.wmkeyboard.ime.vault.VaultNote
import com.wasimaster.wmkeyboard.ime.vault.VaultTextEntry
import com.wasimaster.wmkeyboard.ime.vault.VaultTextField
import com.wasimaster.wmkeyboard.ime.vault.VaultUi

/** The view's height while its box shares the window with the key rows. */
private val VaultTypingHeight = 180.dp

/**
 * The notes vault (#1211), opened from the Notes panel's folder button: an
 * Obsidian or Zettel Notes vault's folders and notes, one folder at a time
 * under a trail of crumbs back to the top; a note read with its front matter
 * out of sight, its tags on a line and its wiki-links tappable; a note's text
 * edited in place with the keys below; a new note in the folder on show; and
 * a quick capture sent to the file or folder picked for it. Back goes up the
 * same way it came down, and from the top to the built-in notes.
 */
@Composable
internal fun VaultPanel(
    state: KeyboardUiState,
    callbacks: VaultCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.vaultText
    val open = ui.open

    PanelFocusTarget(
        panel = PanelMode.NOTES_VAULT,
        region = FocusRegion.RESULTS,
        count = if (typing == null && open == null) ui.entries.size else 0,
        columns = 1,
    ) { index -> ui.entries.getOrNull(index)?.let { act(VaultAction.Open(it)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = {
            when {
                typing != null -> act(VaultAction.TextCancel)
                open != null -> act(VaultAction.CloseNote)
                ui.trail.size > 1 -> act(VaultAction.Up)
                else -> onClose()
            }
        },
        compact = typing != null,
        compactHeight = VaultTypingHeight,
        headerActions = {
            when {
                typing != null -> TypingHeader(typing, ui, act)
                open != null -> NoteHeader(open, ui, act)
                ui.configured -> FolderHeader(ui, act)
                else -> Spacer(Modifier.weight(1f))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.configured -> Unset(ui, act)
                typing != null -> TypingBody(typing, ui, act)
                open != null -> NoteBody(open, act)
                else -> FolderList(state, ui, act)
            }
            if (ui.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalKbTheme.current.accent,
                )
            }
            val message = ui.message
            if (message != null && typing == null) {
                MessageBar(stringResource(message, ui.messageArg), act, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// ---- headers ----

/** The trail of crumbs back to the top, then Capture and New note. */
@Composable
private fun RowScope.FolderHeader(ui: VaultUi, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ui.trail.forEachIndexed { index, dir ->
            if (index > 0) Text("›", color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 3.dp))
            val last = index == ui.trail.lastIndex
            Text(
                dir.name,
                color = if (last) kb.suggestionText else kb.secondaryText,
                fontSize = 12.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(kb.chipShape())
                    .clickable(enabled = !last) { act(VaultAction.Crumb(index)) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
    if (ui.writable) {
        ToolPanelChip(stringResource(R.string.ime_vault_capture_action), enabled = ui.capture != null) { act(VaultAction.StartCapture) }
        Spacer(Modifier.width(4.dp))
        ToolPanelChip(stringResource(R.string.ime_vault_new_action), selected = true) { act(VaultAction.NewNote) }
        Spacer(Modifier.width(4.dp))
    }
}

/** The note's name, then Edit and Type it. */
@Composable
private fun RowScope.NoteHeader(note: VaultNote, ui: VaultUi, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    Text(
        note.title,
        color = kb.suggestionText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 6.dp),
    )
    ToolPanelChip(stringResource(R.string.ime_vault_insert_action)) { act(VaultAction.Insert) }
    Spacer(Modifier.width(4.dp))
    if (ui.writable && !note.tooLong) {
        ToolPanelChip(stringResource(R.string.ime_vault_edit_action), selected = true) { act(VaultAction.Edit) }
        Spacer(Modifier.width(4.dp))
    }
}

/** While the keys type: a name in a one-line box here; a note's text or a capture below, with Save. */
@Composable
private fun RowScope.TypingHeader(entry: VaultTextEntry, ui: VaultUi, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    if (entry.field == VaultTextField.NAME) {
        val shape = kb.chipShape()
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
                placeholder = stringResource(R.string.ime_vault_name_hint),
                active = true,
                textColor = kb.suggestionText,
                placeholderColor = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Text(
            when (entry.field) {
                VaultTextField.CAPTURE -> stringResource(R.string.ime_vault_capture_to, ui.capture?.name.orEmpty())
                else -> ui.open?.title.orEmpty()
            },
            color = kb.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 6.dp),
        )
    }
    ToolPanelChip(
        stringResource(
            when (entry.field) {
                VaultTextField.NAME -> R.string.ime_vault_create_action
                VaultTextField.CAPTURE -> R.string.ime_vault_send_action
                VaultTextField.BODY -> R.string.ime_vault_save_action
            },
        ),
        selected = true,
    ) { act(VaultAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

// ---- bodies ----

/** No vault yet, or its grant gone: what this is, and the button to choose the folder. */
@Composable
private fun Unset(ui: VaultUi, act: (VaultAction) -> Unit) {
    EmptyState(
        icon = Icons.Outlined.FolderOpen,
        title = stringResource(if (ui.message == R.string.ime_vault_grant_lost) R.string.ime_vault_grant_lost else R.string.ime_vault_unset_title),
        body = stringResource(R.string.ime_vault_unset_body),
    ) {
        PillButton(
            label = stringResource(R.string.ime_vault_pick_action),
            icon = Icons.Outlined.Folder,
            primary = true,
        ) { act(VaultAction.PickFolder) }
    }
}

/** The folder's folders and notes; a hold shows a row's actions under it. */
@Composable
private fun FolderList(state: KeyboardUiState, ui: VaultUi, act: (VaultAction) -> Unit) {
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    if (ui.entries.isEmpty() && !ui.loading) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ime_vault_folder_empty), color = LocalKbTheme.current.secondaryText, fontSize = 13.sp)
            VaultFooter(ui, act)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = UndoBarClearance),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(ui.entries, key = { _, entry -> entry.path }) { index, entry ->
            EntryRow(entry, held = ui.held == entry.path, focused = focused == index, capture = ui.capture?.path == entry.path, ui, act)
        }
        item(key = "footer") { VaultFooter(ui, act) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: VaultEntry,
    held: Boolean,
    focused: Boolean,
    capture: Boolean,
    ui: VaultUi,
    act: (VaultAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(if (held) kb.chipActive.copy(alpha = kb.chipActive.alpha * 0.5f) else kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
            .chipBorder(kb, shape)
            .combinedClickable(
                onClick = { act(VaultAction.Open(entry)) },
                onLongClick = {
                    feedback()
                    act(VaultAction.Hold(entry.path))
                },
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.folder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (entry.folder) kb.accent else kb.secondaryText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (entry.folder) entry.name else VaultMarkdown.noteName(entry.name),
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = if (entry.folder) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (capture) {
                Text(stringResource(R.string.ime_vault_capture_badge), color = kb.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (held) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (ui.writable) {
                    ToolPanelChip(
                        stringResource(if (entry.folder) R.string.ime_vault_capture_new_here else R.string.ime_vault_capture_add_here),
                        selected = true,
                    ) { act(VaultAction.CaptureHere(entry)) }
                }
                ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(VaultAction.Hold(null)) }
            }
        }
    }
}

/** Under the list: where captures go, the way to change the folder, and the way to leave the vault. */
@Composable
private fun VaultFooter(ui: VaultUi, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val capture = ui.capture
        Text(
            if (capture == null) {
                stringResource(R.string.ime_vault_capture_hint)
            } else {
                stringResource(if (capture.folder) R.string.ime_vault_capture_folder_line else R.string.ime_vault_capture_file_line, capture.name)
            },
            color = kb.secondaryText,
            fontSize = 12.sp,
        )
        if (!ui.writable) Text(stringResource(R.string.ime_vault_read_only), color = kb.secondaryText, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (ui.writable && ui.trail.isNotEmpty()) {
                ToolPanelChip(stringResource(R.string.ime_vault_capture_this_folder)) { act(VaultAction.CaptureHereFolder) }
            }
            ToolPanelChip(stringResource(R.string.ime_vault_refresh_action)) { act(VaultAction.Refresh) }
            ToolPanelChip(stringResource(R.string.ime_vault_change_action)) { act(VaultAction.PickFolder) }
            ToolPanelChip(stringResource(R.string.ime_vault_forget_action)) { act(VaultAction.Forget) }
        }
    }
}

/** The note as Obsidian draws it, near enough: its tags, then its Markdown with wiki-links that open their notes. */
@Composable
private fun NoteBody(note: VaultNote, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shown = remember(note.body) { VaultMarkdown.forDisplay(note.body) }
    val linkStyle = remember(kb.accent) { SpanStyle(color = kb.accent) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = UndoBarClearance),
    ) {
        if (note.tags.isNotEmpty()) {
            Text(
                note.tags.joinToString("  ") { "#$it" },
                color = kb.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (note.body.isBlank()) {
            Text(stringResource(R.string.ime_vault_note_empty), color = kb.secondaryText, fontSize = 13.sp)
        } else {
            ChatMarkdown(
                shown,
                ChatMarkdownColors(text = kb.modifierKeyText, dim = kb.secondaryText, codeBackground = kb.chip, link = kb.accent),
                fontSize = 13.sp,
                customLink = { url ->
                    VaultMarkdown.targetOf(url)?.let { target -> ChatLink(linkStyle) { act(VaultAction.Follow(target)) } }
                },
            )
        }
        if (note.tooLong) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ime_vault_too_long), color = kb.secondaryText, fontSize = 12.sp)
        }
    }
}

/** Under the header while the keys type: the note's text or the capture as a box, and Cancel. */
@Composable
private fun TypingBody(entry: VaultTextEntry, ui: VaultUi, act: (VaultAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (entry.field) {
            VaultTextField.NAME -> Text(
                stringResource(R.string.ime_vault_name_info, ui.folder?.name.orEmpty()),
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
            )
            VaultTextField.BODY, VaultTextField.CAPTURE -> {
                val shape = kb.cardShape()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(shape)
                        .background(kb.chip)
                        .chipBorder(kb, shape)
                        .padding(10.dp),
                ) {
                    ClipEditText(
                        text = entry.text,
                        placeholder = stringResource(
                            if (entry.field == VaultTextField.CAPTURE) R.string.ime_vault_capture_hint_box else R.string.ime_vault_body_hint,
                        ),
                        textColor = kb.suggestionText,
                        placeholderColor = kb.secondaryText,
                    )
                }
            }
        }
        if (ui.conflict) {
            Text(stringResource(R.string.ime_vault_conflict), color = kb.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ToolPanelChip(stringResource(R.string.ime_vault_overwrite_action), selected = true) { act(VaultAction.Overwrite) }
                ToolPanelChip(stringResource(R.string.ime_vault_reload_action)) { act(VaultAction.Reload) }
            }
        } else {
            ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(VaultAction.TextCancel) }
        }
    }
}

/** A line along the bottom about what just happened, tapped away. */
@Composable
private fun MessageBar(text: String, act: (VaultAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Text(
        text,
        color = kb.suggestionText,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(shape)
            .background(kb.board)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { act(VaultAction.DismissMessage) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
