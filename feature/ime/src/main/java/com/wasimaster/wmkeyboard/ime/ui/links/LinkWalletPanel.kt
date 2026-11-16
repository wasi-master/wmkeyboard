package com.wasimaster.wmkeyboard.ime.ui.links

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.links.LinkWalletStore
import com.wasimaster.wmkeyboard.core.links.SavedLink
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.links.LinkAction
import com.wasimaster.wmkeyboard.ime.links.LinkTextEntry
import com.wasimaster.wmkeyboard.ime.links.LinkTextField
import com.wasimaster.wmkeyboard.ime.links.LinkWalletCallbacks
import com.wasimaster.wmkeyboard.ime.links.LinkWalletUi
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
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
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn

/** The panel's height while its box shares the window with the key rows. */
private val LinkTypingHeight = 150.dp

/**
 * The Link wallet (#1214): the user's saved links as buttons in a grid, each
 * with the first letter of its name in a disc and the site under the name. A
 * tap opens the link in the browser; a hold lifts the button and brings up a
 * row of its actions along the bottom (type it, copy, share, rename, change
 * the address, move it either way, delete). Add starts on the address box,
 * with a link already copied waiting in it; the keys type into the header.
 * Nothing here owns a link: `LinkWalletController` does.
 */
@Composable
internal fun LinkWalletPanel(
    state: KeyboardUiState,
    callbacks: LinkWalletCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.linksText
    val focused = state.focusedIndex(FocusRegion.RESULTS)

    PanelFocusTarget(
        panel = PanelMode.LINKS,
        region = FocusRegion.RESULTS,
        count = if (typing == null) ui.links.size else 0,
        columns = 2,
    ) { index -> ui.links.getOrNull(index)?.let { act(LinkAction.Open(it.id)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        compact = typing != null,
        compactHeight = LinkTypingHeight,
        headerActions = {
            if (typing != null) TypingHeader(typing, act) else ListHeader(ui, act)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.available -> Message(stringResource(R.string.ime_links_unavailable))
                typing != null -> TypingBody(ui, typing, act)
                ui.links.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Link,
                    title = stringResource(R.string.ime_links_empty),
                    body = stringResource(R.string.ime_links_empty_hint),
                ) {
                    PillButton(
                        label = stringResource(R.string.ime_links_add_action),
                        icon = Icons.Outlined.Add,
                        primary = true,
                    ) { act(LinkAction.StartAdd) }
                }
                else -> LinkGrid(ui, focused, act)
            }
            val held = ui.held?.let { id -> ui.links.firstOrNull { it.id == id } }
            if (typing == null && held != null) {
                HeldActions(
                    link = held,
                    first = ui.links.firstOrNull()?.id == held.id,
                    last = ui.links.lastOrNull()?.id == held.id,
                    act = act,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let { stringResource(R.string.ime_links_undo_deleted, it.link.title) }.orEmpty(),
                visible = undo != null && held == null,
                key = undo,
                reduceMotion = state.settings.reduceMotion,
                onUndo = { act(LinkAction.Undo) },
                onDismiss = { act(LinkAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** How many links there are, then Add. */
@Composable
private fun RowScope.ListHeader(ui: LinkWalletUi, act: (LinkAction) -> Unit) {
    val kb = LocalKbTheme.current
    Text(
        if (ui.links.isEmpty()) {
            stringResource(R.string.ime_tool_links)
        } else {
            pluralStringResource(R.plurals.ime_links_count, ui.links.size, ui.links.size)
        },
        color = kb.secondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.weight(1f).padding(start = 8.dp),
    )
    if (ui.available) {
        ToolPanelChip(stringResource(R.string.ime_links_add_action), selected = true) { act(LinkAction.StartAdd) }
        Spacer(Modifier.width(4.dp))
    }
}

/** While the keys type into the panel, the header is the box, Paste and Next or Save. */
@Composable
private fun RowScope.TypingHeader(entry: LinkTextEntry, act: (LinkAction) -> Unit) {
    val kb = LocalKbTheme.current
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
            placeholder = stringResource(
                if (entry.field == LinkTextField.URL) R.string.ime_links_url_hint else R.string.ime_links_title_hint,
            ),
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_paste)) { act(LinkAction.PasteClip) }
    Spacer(Modifier.width(4.dp))
    ToolPanelChip(
        stringResource(if (entry.field == LinkTextField.URL) R.string.ime_links_next_action else R.string.ime_links_save_action),
        selected = true,
    ) { act(LinkAction.TextNext) }
    Spacer(Modifier.width(4.dp))
}

/** Under the box: what it is for, why an address was not taken, and Cancel. */
@Composable
private fun TypingBody(ui: LinkWalletUi, entry: LinkTextEntry, act: (LinkAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            when (entry.field) {
                LinkTextField.URL -> stringResource(R.string.ime_links_url_info)
                LinkTextField.TITLE -> stringResource(R.string.ime_links_title_info, entry.url)
            },
            color = kb.secondaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val note = ui.note
        if (note != null) {
            Text(stringResource(note), color = kb.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(LinkAction.TextCancel) }
    }
}

/** The links as buttons, two or more to a row as the width allows. */
@Composable
private fun LinkGrid(ui: LinkWalletUi, focused: Int?, act: (LinkAction) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(LinkButtonMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 4.dp,
            end = 12.dp,
            // Room under the last row for the actions bar or the Undo bar.
            bottom = if (ui.held != null || ui.undo != null) UndoBarClearance else 12.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(ui.links, key = { _, link -> link.id }) { index, link ->
            LinkButton(link, held = ui.held == link.id, focused = focused == index, act = act)
        }
    }
}

/**
 * One saved link: a disc with its first letter, the name, and the site under
 * it. Tap opens it; a hold brings up its actions, and a second hold puts them
 * away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkButton(link: SavedLink, held: Boolean, focused: Boolean, act: (LinkAction) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    val holdLabel = stringResource(R.string.ime_links_hold_desc)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LinkButtonHeight)
            .pressScale(source, kb.motion.any)
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(if (held) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = { act(LinkAction.Open(link.id)) },
                onLongClick = {
                    feedback()
                    act(LinkAction.Hold(link.id))
                },
                onLongClickLabel = holdLabel,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val disc = if (held) kb.chip else kb.accent
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(disc),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                link.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = maxContrastOn(disc),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                link.title,
                color = if (held) kb.chipActiveText else kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                LinkWalletStore.hostOf(link.url),
                color = if (held) kb.chipActiveText.copy(alpha = 0.75f) else kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A held link's actions, one scrolling row along the bottom of the panel. */
@Composable
private fun HeldActions(
    link: SavedLink,
    first: Boolean,
    last: Boolean,
    act: (LinkAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(shape)
            .background(kb.board)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolPanelChip(stringResource(R.string.ime_links_insert_action), selected = true) { act(LinkAction.Insert(link.id)) }
        ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_copy)) { act(LinkAction.Copy(link.id)) }
        ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_share)) { act(LinkAction.Share(link.id)) }
        ToolPanelChip(stringResource(R.string.ime_links_rename_action)) { act(LinkAction.EditTitle(link.id)) }
        ToolPanelChip(stringResource(R.string.ime_links_edit_url_action)) { act(LinkAction.EditUrl(link.id)) }
        if (!first) ToolPanelChip(stringResource(R.string.ime_links_move_earlier_action)) { act(LinkAction.Move(link.id, -1)) }
        if (!last) ToolPanelChip(stringResource(R.string.ime_links_move_later_action)) { act(LinkAction.Move(link.id, 1)) }
        ToolPanelChip(stringResource(R.string.ime_links_delete_action)) { act(LinkAction.Delete(link.id)) }
        ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { act(LinkAction.Release) }
    }
}

@Composable
private fun Message(text: String) {
    val kb = LocalKbTheme.current
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = kb.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** A link button's size: two to a row on a phone, a name and its site on two lines. */
private val LinkButtonMinWidth = 150.dp
private val LinkButtonHeight = 52.dp
