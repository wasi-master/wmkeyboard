package com.wasimaster.wmkeyboard.ime.ui.wmchroma

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Colorize
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.SectionLabel
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaAction
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaCallbacks
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaColour
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaDescribed
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaLibrary
import com.wasimaster.wmkeyboard.ime.wmchroma.ChromaPhase

/**
 * The WM Chroma tool: Pick from screen in the header, then recent colours, the tray and every
 * saved palette as rows of swatches. A tap types the colour in WM Chroma's copy format; a hold
 * lists its other formats to type one of those instead.
 */
@Composable
internal fun ChromaPanel(
    state: KeyboardUiState,
    callbacks: ChromaCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        headerActions = { Header(ui.phase is ChromaPhase.Ready, act) },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (val phase = ui.phase) {
                ChromaPhase.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp,
                    color = LocalKbTheme.current.accent,
                )
                ChromaPhase.Missing -> EmptyState(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.ime_wmchroma_missing_title),
                    body = stringResource(R.string.ime_wmchroma_missing_body),
                ) {
                    PillButton(stringResource(R.string.ime_wmchroma_get), icon = Icons.Outlined.Download, primary = true) { act(ChromaAction.GetApp) }
                }
                ChromaPhase.Off -> EmptyState(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.ime_wmchroma_off_title),
                    body = stringResource(R.string.ime_wmchroma_off_body),
                ) {
                    PillButton(stringResource(R.string.ime_wmchroma_open), icon = Icons.Outlined.OpenInNew, primary = true) { act(ChromaAction.OpenApp) }
                }
                ChromaPhase.Failed -> EmptyState(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.ime_wmchroma_failed_title),
                    body = stringResource(R.string.ime_wmchroma_failed_body),
                ) {
                    PillButton(stringResource(R.string.ime_wmchroma_retry), icon = Icons.Outlined.Refresh) { act(ChromaAction.Retry) }
                }
                is ChromaPhase.Ready -> Library(phase.library, act)
            }
            ui.formats?.let { FormatsBar(it, act, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable
private fun RowScope.Header(ready: Boolean, act: (ChromaAction) -> Unit) {
    Row(
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        ToolPanelChip(stringResource(R.string.ime_wmchroma_pick), enabled = ready) { act(ChromaAction.Pick) }
        ToolPanelChip(stringResource(R.string.ime_wmchroma_open), enabled = ready) { act(ChromaAction.OpenApp) }
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_wmchroma_settings_desc),
        active = false,
    ) { act(ChromaAction.OpenSettings) }
}

@Composable
private fun Library(library: ChromaLibrary, act: (ChromaAction) -> Unit) {
    if (library.recent.isEmpty() && library.tray.isEmpty() && library.palettes.all { it.swatches.isEmpty() }) {
        EmptyState(
            icon = Icons.Outlined.Colorize,
            title = stringResource(R.string.ime_wmchroma_empty_title),
            body = stringResource(R.string.ime_wmchroma_empty_body),
        ) {
            PillButton(stringResource(R.string.ime_wmchroma_pick), icon = Icons.Outlined.Colorize, primary = true) { act(ChromaAction.Pick) }
        }
        return
    }
    val recent = stringResource(R.string.ime_wmchroma_recent)
    val tray = stringResource(R.string.ime_wmchroma_tray)
    val rows = buildList {
        if (library.recent.isNotEmpty()) add(recent to library.recent)
        if (library.tray.isNotEmpty()) add(tray to library.tray)
        library.palettes.filter { it.swatches.isNotEmpty() }.forEach { add(it.name to it.swatches) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
    ) {
        items(rows.size) { i ->
            val (name, swatches) = rows[i]
            SectionLabel(name)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                items(swatches) { c -> Swatch(c, act) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(colour: ChromaColour, act: (ChromaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val said = listOf(colour.name, colour.text).filter { it.isNotBlank() }.joinToString(", ")
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(colour.argb))
            .border(1.dp, kb.secondaryText.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { act(ChromaAction.Insert(colour.text)) },
                onLongClick = { act(ChromaAction.Formats(colour)) },
            )
            .semantics { contentDescription = said },
    )
}

/** A held colour's formats, along the bottom: a tap types that one. */
@Composable
private fun FormatsBar(described: ChromaDescribed, act: (ChromaAction) -> Unit, modifier: Modifier) {
    val kb = LocalKbTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(kb.board)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(described.colour.argb)).border(1.dp, kb.secondaryText.copy(alpha = 0.3f), CircleShape))
            Text(
                described.colour.name.ifBlank { described.colour.text },
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolPanelChip(stringResource(R.string.ime_wmchroma_close_formats)) { act(ChromaAction.CloseFormats) }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            described.formats.forEach { f -> ToolPanelChip(f.text) { act(ChromaAction.Insert(f.text)) } }
        }
    }
}
