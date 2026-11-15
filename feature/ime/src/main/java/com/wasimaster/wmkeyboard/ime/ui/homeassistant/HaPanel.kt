package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.homeassistant.HaKind
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HomeIotDevice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarBorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAction
import com.wasimaster.wmkeyboard.ime.homeassistant.HaCallbacks
import com.wasimaster.wmkeyboard.ime.homeassistant.HaTab
import com.wasimaster.wmkeyboard.ime.homeassistant.HaTextEntry
import com.wasimaster.wmkeyboard.ime.homeassistant.HaTextField
import com.wasimaster.wmkeyboard.ime.homeassistant.HaUi
import com.wasimaster.wmkeyboard.ime.homeassistant.HaUndo
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.swipeTabs

/**
 * The Home Assistant tool: the house as tiles, a tab per room, live from the
 * server, with a typed Assist bar underneath. The header is the room tabs; the
 * dot by the house's name says how the connection stands. A tap on a tile
 * acts, a hold opens its card over the dimmed grid, and a tile the server
 * changes from elsewhere rings once.
 */
@Composable
internal fun HaPanel(
    state: KeyboardUiState,
    callbacks: HaCallbacks,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.haText
    val animate = kb.motion.any && !state.settings.reduceMotion

    PanelFocusTarget(panel = PanelMode.HOME_ASSISTANT, region = FocusRegion.CHIPS, count = ui.tabs.size, columns = ui.tabs.size.coerceAtLeast(1)) { index ->
        ui.tabs.getOrNull(index)?.let { act(HaAction.Tab(it.id)) }
    }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = 36.dp,
        compact = typing != null,
        compactHeight = 150.dp,
        headerActions = {
            if (typing != null) {
                TypingHeader(typing, act)
            } else {
                TabsHeader(state, ui, animate, act)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.enabled || !ui.hasServer || !ui.hasToken -> SetupState(ui, act)
                ui.phase == HaPhase.TOKEN_REJECTED -> EmptyState(
                    icon = Icons.Outlined.HomeIotDevice,
                    title = stringResource(R.string.ime_ha_signed_out_title),
                    body = stringResource(R.string.ime_ha_signed_out_body),
                ) { PillButton(stringResource(R.string.ime_ha_signed_out_action), primary = true) { act(HaAction.OpenSettings) } }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Body(state, ui, animate, act)
                        ConnectionBanner(ui, act, Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                    if (typing == null) HomeFooter(ui, animate)
                    if (ui.assistEnabled && ui.expanded == null) {
                        HaAssistBar(ui.assist, typing = typing?.field == HaTextField.ASSIST, animate = animate, act = act)
                    }
                }
            }
            ExpandedCard(ui, animate, act)
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let { undoText(it) }.orEmpty(),
                visible = undo != null,
                key = undo,
                reduceMotion = !animate,
                onUndo = { act(HaAction.Undo) },
                onDismiss = { act(HaAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun undoText(undo: HaUndo): String = when (undo) {
    is HaUndo.Unfavourited -> stringResource(R.string.ime_ha_undo_unfavourite)
    is HaUndo.Hidden -> stringResource(R.string.ime_ha_undo_hidden)
}

// ---- Header ---------------------------------------------------------------------

@Composable
private fun RowScope.TabsHeader(state: KeyboardUiState, ui: HaUi, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        if (ui.enabled && ui.hasToken) {
            ui.tabs.forEachIndexed { i, tab ->
                TabChip(
                    label = tab.name,
                    selected = ui.tab == tab.id && !ui.searchOpen,
                    count = if (tab.id == HaTab.FAVOURITES) 0 else tab.count,
                    star = tab.id == HaTab.FAVOURITES,
                    focused = focused == i,
                    onClick = { act(HaAction.Tab(tab.id)) },
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
    if (ui.enabled && ui.hasToken) {
        ToolCircle(
            slot = IconSlots.forTool(ToolbarTool.WEB_SEARCH),
            description = stringResource(R.string.ime_ha_search_desc),
            active = ui.searchOpen,
            tint = kb.toolbarIcon,
        ) { act(HaAction.SearchOpen) }
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_ha_settings_desc),
        active = false,
    ) { act(HaAction.OpenSettings) }
}

/**
 * The house's name under the tiles, with a dot that breathes while connecting
 * and settles when live. A footer rather than a header chip, so the room tabs
 * get the whole width.
 */
@Composable
private fun HomeFooter(ui: HaUi, animate: Boolean) {
    val kb = LocalKbTheme.current
    val connecting = ui.phase == HaPhase.CONNECTING || ui.phase == HaPhase.AUTHENTICATING
    val dot = when (ui.phase) {
        HaPhase.LIVE -> Color(0xFF43A047)
        HaPhase.REST_FALLBACK -> Color(0xFFFFB300)
        HaPhase.CONNECTING, HaPhase.AUTHENTICATING -> kb.accent
        HaPhase.TOKEN_REJECTED, HaPhase.UNREACHABLE -> Color(0xFFE53935)
        HaPhase.OFFLINE -> kb.secondaryText
    }
    val alpha = breathing(connecting, animate)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(
            ui.instanceName,
            color = kb.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, count: Int, star: Boolean, focused: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (selected) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) kb.chipActiveText else kb.chipText
        if (star) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(count.toString(), color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** While the keys type into the panel, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: HaTextEntry, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val placeholder = stringResource(
        when (entry.field) {
            HaTextField.SEARCH -> R.string.ime_ha_search_hint
            HaTextField.ASSIST -> R.string.ime_ha_assist_hint
            HaTextField.CODE -> R.string.ime_ha_code_hint
            HaTextField.VALUE -> R.string.ime_ha_value_hint
        },
    )
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
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    val label = when (entry.field) {
        HaTextField.SEARCH -> stringResource(R.string.ime_ha_done)
        HaTextField.ASSIST -> stringResource(R.string.ime_ha_assist_send_desc)
        HaTextField.CODE, HaTextField.VALUE -> stringResource(R.string.ime_ha_done)
    }
    ToolPanelChip(label, selected = true) {
        act(if (entry.field == HaTextField.SEARCH) HaAction.SearchClose else HaAction.TextDone)
    }
    Spacer(Modifier.width(4.dp))
}

// ---- Body -------------------------------------------------------------------------

@Composable
private fun Body(state: KeyboardUiState, ui: HaUi, animate: Boolean, act: (HaAction) -> Unit) {
    val tabIndex = ui.tabs.indexOfFirst { it.id == ui.tab }.coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .swipeTabs(tabIndex, ui.tabs.size.coerceAtLeast(1), enabled = state.haText == null && ui.expanded == null && !ui.searchOpen) { i ->
                ui.tabs.getOrNull(i)?.let { act(HaAction.Tab(it.id)) }
            },
    ) {
        when {
            ui.entityCount == 0 && ui.phase != HaPhase.LIVE && ui.phase != HaPhase.REST_FALLBACK && ui.phase != HaPhase.UNREACHABLE -> SkeletonGrid(animate)
            ui.searchOpen -> if (ui.tiles.isEmpty() && ui.search.isNotBlank()) {
                EmptyState(Icons.Outlined.Search, stringResource(R.string.ime_ha_no_results), "")
            } else {
                HaTileGrid(state, ui, animate, act)
            }
            else -> AnimatedContent(
                targetState = ui.tab,
                transitionSpec = {
                    if (!animate) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        val from = ui.tabs.indexOfFirst { it.id == initialState }
                        val to = ui.tabs.indexOfFirst { it.id == targetState }
                        val dir = if (to > from) 1 else -1
                        (slideInHorizontally(tween(HaMotion.TAB_MS)) { it / 6 * dir } + fadeIn(tween(HaMotion.TAB_MS))) togetherWith
                            (slideOutHorizontally(tween(HaMotion.TAB_MS)) { -it / 6 * dir } + fadeOut(tween(HaMotion.TAB_MS / 2)))
                    }
                },
                label = "haTab",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                if (tab == HaTab.FAVOURITES && ui.tiles.isEmpty() && ui.tab == HaTab.FAVOURITES) {
                    EmptyState(
                        icon = Icons.Outlined.StarBorder,
                        title = stringResource(R.string.ime_ha_no_favourites_title),
                        body = stringResource(R.string.ime_ha_no_favourites_body),
                    ) { PillButton(stringResource(R.string.ime_ha_no_favourites_action)) { act(HaAction.OpenSettings) } }
                } else {
                    HaTileGrid(state, ui, animate, act)
                }
            }
        }
    }
}

/** Six tile-sized slabs that breathe slowly while the first states load; nothing else moves. */
@Composable
private fun SkeletonGrid(animate: Boolean) {
    val kb = LocalKbTheme.current
    val alpha = breathing(true, animate)
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(kb.cardShape())
                            .frostGlass(kb, kb.cardShape(), FrostSurface.CARDS)
                            .background(kb.chip.copy(alpha = kb.chip.alpha * (0.35f + 0.2f * alpha))),
                    )
                }
            }
        }
        Text(stringResource(R.string.ime_ha_loading), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Over the (dimmed, cached) tiles when the server is out of reach. */
@Composable
private fun ConnectionBanner(ui: HaUi, act: (HaAction) -> Unit, modifier: Modifier) {
    AnimatedVisibility(visible = ui.phase == HaPhase.UNREACHABLE && ui.entityCount > 0, modifier = modifier) {
        Banner(stringResource(R.string.ime_ha_unreachable_banner, ui.instanceName), stringResource(R.string.ime_ha_retry)) { act(HaAction.Retry) }
    }
}

/** Nothing connected yet: what the tool is for, and the server the LAN just announced, if any. */
@Composable
private fun SetupState(ui: HaUi, act: (HaAction) -> Unit) {
    val found = ui.found
    if (found != null && !ui.hasServer) {
        EmptyState(
            icon = Icons.Outlined.HomeIotDevice,
            title = stringResource(R.string.ime_ha_found_title, found.name),
            body = stringResource(R.string.ime_ha_found_body),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.ime_ha_found_sign_in), primary = true, icon = Icons.Outlined.OpenInNew) { act(HaAction.SignInFound) }
                PillButton(stringResource(R.string.ime_ha_empty_connect)) { act(HaAction.Connect) }
            }
        }
    } else {
        EmptyState(
            icon = Icons.Outlined.HomeIotDevice,
            title = stringResource(R.string.ime_ha_empty_title),
            body = stringResource(R.string.ime_ha_empty_body),
        ) { PillButton(stringResource(R.string.ime_ha_empty_connect), primary = true) { act(HaAction.Connect) } }
    }
}

// ---- The open tile -------------------------------------------------------------------

/**
 * A tile's card over the dimmed grid: the glyph, the name, the room, the live
 * state line, the kind's controls, and a row of what else can be done with
 * it. Grows from the middle and fades in; a tap outside or the X puts it away.
 */
@Composable
private fun ExpandedCard(ui: HaUi, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val tile = ui.expandedTile
    AnimatedVisibility(
        visible = tile != null,
        enter = if (animate) fadeIn(tween(HaMotion.EXPAND_MS)) + scaleIn(tween(HaMotion.EXPAND_MS, easing = HaMotion.EmphasizedDecelerate), initialScale = 0.86f) else fadeIn(tween(0)),
        exit = if (animate) fadeOut(tween(HaMotion.COLLAPSE_MS)) + scaleOut(tween(HaMotion.COLLAPSE_MS, easing = HaMotion.EmphasizedAccelerate), targetScale = 0.92f) else fadeOut(tween(0)),
    ) {
        val t = tile ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(kb.board.copy(alpha = 0.55f))
                .clickable { act(HaAction.Collapse) },
            contentAlignment = Alignment.Center,
        ) {
            val shape = kb.cardShape()
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CARDS)
                    .background(kb.popup)
                    .chipBorder(kb, shape)
                    .clickable(enabled = false) {}
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(kb.accent.copy(alpha = if (t.shownOn) 0.9f else 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(t.glyph.vector(), contentDescription = null, tint = if (t.shownOn) com.wasimaster.wmkeyboard.ime.ui.maxContrastOn(kb.accent) else kb.accent, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade(t.stateLine, animate, label = "haCardState") { line ->
                            Text(listOfNotNull(t.areaName, line).joinToString(" · "), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(
                        if (t.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(if (t.favourite) R.string.ime_ha_unfavourite else R.string.ime_ha_favourite),
                        tint = if (t.favourite) kb.accent else kb.secondaryText,
                        modifier = Modifier.size(22.dp).clickable { act(HaAction.Favourite(t.id, !t.favourite)) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ime_ha_back_desc),
                        tint = kb.secondaryText,
                        modifier = Modifier.size(20.dp).clickable { act(HaAction.Collapse) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                HaKindControls(t, ui.snapshot, ui.insertFormat, ui.confirmRisky, animate, act)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (t.kind != HaKind.SENSOR && t.kind != HaKind.WEATHER && t.kind != HaKind.BINARY_SENSOR && t.kind != HaKind.PERSON) {
                        PillButton(stringResource(R.string.ime_ha_insert), height = 30.dp) { act(HaAction.Insert(t.id)) }
                    }
                    PillButton(stringResource(R.string.ime_ha_hide), icon = Icons.Outlined.VisibilityOff, height = 30.dp) { act(HaAction.Hide(t.id)) }
                    PillButton(stringResource(R.string.ime_ha_open_in_ha), icon = Icons.Outlined.OpenInNew, height = 30.dp) { act(HaAction.OpenInHomeAssistant(t.id)) }
                }
            }
        }
    }
}
