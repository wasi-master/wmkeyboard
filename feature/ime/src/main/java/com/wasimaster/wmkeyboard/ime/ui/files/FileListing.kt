package com.wasimaster.wmkeyboard.ime.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.files.FilePane
import com.wasimaster.wmkeyboard.ime.files.FileView
import com.wasimaster.wmkeyboard.ime.files.fileCrumbs
import com.wasimaster.wmkeyboard.ime.files.folderCrumbs
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Where a list is: Places, the volume or added folder, each folder down,
 * any of them a tap away, and a pin for the open folder. A search says what
 * it is doing instead: where it looks now, and how many it has found.
 */
@Composable
internal fun Crumbs(ui: FileManagerUi, pane: FilePane, active: Boolean, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val query = ui.query.takeIf { active }
    if (query != null) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val where = ui.searchingIn
            Text(
                if (ui.searching && where != null) stringResource(R.string.ime_files_searching_in, where) else stringResource(R.string.ime_files_search_done),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(R.plurals.ime_files_found, pane.entries.size, pane.entries.size),
                color = kb.suggestionText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_files_search_clear_desc),
                tint = kb.secondaryText,
                modifier = Modifier.padding(start = 4.dp).size(26.dp).clip(CircleShape).clickable { act(FileAction.ClearSearch) }.padding(5.dp),
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumb(stringResource(R.string.ime_files_places), current = pane.view == FileView.Places) { act(FileAction.OpenPlaces) }
            when (val view = pane.view) {
                FileView.Places -> Unit
                FileView.Recent -> {
                    CrumbGap()
                    Crumb(stringResource(R.string.ime_files_recent_files), current = true) {}
                }
                is FileView.Category -> {
                    CrumbGap()
                    Crumb(stringResource(categoryLabel(view.category)), current = true) {}
                }
                is FileView.Folder -> {
                    val crumbs = folderCrumbs(view, ui.volumes)
                    crumbs.forEachIndexed { index, crumb ->
                        CrumbGap()
                        Crumb(crumb.label, current = index == crumbs.lastIndex) { act(FileAction.OpenFolder(crumb.path)) }
                    }
                }
            }
        }
        val folder = (pane.view as? FileView.Folder)?.path
        if (folder != null && active) {
            val pinned = ui.pins.any { it.path == folder }
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = stringResource(if (pinned) R.string.ime_files_unpin_desc else R.string.ime_files_pin_desc),
                tint = if (pinned) kb.accent else kb.secondaryText.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { act(FileAction.TogglePin) }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun CrumbGap() {
    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = LocalKbTheme.current.secondaryText, modifier = Modifier.size(14.dp))
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
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * A list itself: the places, a folder, a category, the recent files, or what
 * a search found. A folder shows as a grid of thumbnails or as rows, by the
 * header's view button; a search always as rows, each saying where it is.
 *
 * One gesture handler covers every cell, as the gallery's does: a tap does
 * what a tap does, a hold picks, and a hold that then slides picks everything
 * it passes, scrolling as it nears an edge.
 */
@Composable
internal fun Listing(
    ui: FileManagerUi,
    pane: FilePane,
    active: Boolean,
    compact: Boolean,
    focusedRow: Int?,
    act: (FileAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val query = ui.query.takeIf { active }
    val places = pane.view == FileView.Places && query == null
    // Each folder opens at its top, and comes back there.
    key(pane.view, query) {
        when {
            places -> PlacesFace(ui, focusedRow, compact, act)
            (pane.loading || (active && ui.searching)) && pane.entries.isEmpty() ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = kb.secondaryText, strokeWidth = 2.dp)
                    if (pane.reading > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pluralStringResource(R.plurals.ime_files_reading, pane.reading, pane.reading),
                            color = kb.secondaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
            pane.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val filtered = ui.request?.mimes?.isNotEmpty() == true
                Text(
                    stringResource(
                        when {
                            query != null -> R.string.ime_files_search_empty
                            pane.unreadable -> R.string.ime_files_unreadable
                            filtered -> R.string.ime_files_empty_filtered
                            else -> R.string.ime_files_empty
                        },
                    ),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            else -> EntryGrid(ui, pane, grid = ui.grid && query == null, compact = compact, search = query != null, focusedRow = focusedRow, act = act)
        }
    }
}

@Composable
private fun EntryGrid(
    ui: FileManagerUi,
    pane: FilePane,
    grid: Boolean,
    compact: Boolean,
    search: Boolean,
    focusedRow: Int?,
    act: (FileAction) -> Unit,
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val entries by rememberUpdatedState(pane.entries)
    var heightPx by remember { mutableIntStateOf(1) }
    val columns = if (grid) (if (compact) 2 else 4) else 1

    fun hit(pos: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { i ->
        pos.x >= i.offset.x && pos.x < i.offset.x + i.size.width && pos.y >= i.offset.y && pos.y < i.offset.y + i.size.height
    }?.index

    Box(Modifier.fillMaxSize().onSizeChanged { heightPx = it.height }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = if (grid) PaddingValues(horizontal = 8.dp, vertical = 4.dp) else PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (grid) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 0.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(grid) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = hit(down.position) ?: return@awaitEachGesture
                        val long = awaitLongPressOrCancellation(down.id)
                        if (long == null) {
                            // Lifted before the hold: a tap, unless the list took it as a scroll.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture
                            entries.getOrNull(start)?.let { act(FileAction.Tap(it)) }
                            return@awaitEachGesture
                        }
                        val first = entries.getOrNull(start) ?: return@awaitEachGesture
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        act(FileAction.Hold(first))
                        var sweeping = false
                        var last = start
                        var scroller: Job? = null
                        var pointer = long.position
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            pointer = c.position
                            if (!sweeping && (c.position - down.position).getDistance() > slop) sweeping = true
                            c.consume()
                            if (!sweeping) continue
                            if (scroller == null) {
                                scroller = scope.launch {
                                    while (isActive) {
                                        val edge = with(density) { EDGE.toPx() }
                                        val speed = when {
                                            pointer.y < edge -> -(edge - pointer.y)
                                            pointer.y > heightPx - edge -> pointer.y - (heightPx - edge)
                                            else -> 0f
                                        }
                                        if (speed != 0f) state.scrollBy(speed * EDGE_SPEED)
                                        delay(16)
                                    }
                                }
                            }
                            val now = hit(pointer) ?: continue
                            if (now == last) continue
                            last = now
                            act(FileAction.SelectRange(start, now))
                        }
                        scroller?.cancel()
                    }
                },
        ) {
            itemsIndexed(pane.entries, key = { _, it -> it.path }, contentType = { _, _ -> if (grid) "cell" else "row" }) { index, entry ->
                val picked = entry.path in ui.selection
                val focused = focusedRow == index
                if (grid) {
                    GridCell(entry, picked, focused, ui.selecting || ui.selection.isNotEmpty(), ui.prefs.thumbnails, compact)
                } else {
                    EntryRow(ui, entry, picked, focused, compact, search)
                }
            }
        }
    }
}

/** A grid cell (2B): the file's face, a ring to pick it while picking, its name under it. */
@Composable
private fun GridCell(entry: FileEntry, picked: Boolean, focused: Boolean, picking: Boolean, thumbnails: Boolean, compact: Boolean) {
    val kb = LocalKbTheme.current
    val face = if (compact) 64.dp else 78.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(face)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        picked -> kb.accent.copy(alpha = 0.28f)
                        focused -> kb.accent.copy(alpha = 0.18f)
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val inset = if (picked) 6.dp else 0.dp
            KindTile(
                entry = entry,
                side = face - inset * 2,
                picked = false,
                thumbnails = thumbnails,
                modifier = Modifier.fillMaxWidth().padding(horizontal = inset),
                corner = 10.dp,
                iconScale = 0.42f,
            )
            if (picking || picked) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .then(
                            if (picked) {
                                Modifier.background(kb.accent)
                            } else {
                                Modifier.background(Color.Black.copy(alpha = 0.18f)).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Icon(Icons.Outlined.Check, contentDescription = null, tint = maxContrastOn(kb.accent), modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.name,
            color = kb.suggestionText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

/** A row (2's list view and every search): a ticked tile when picked, else the file's face; the size and date; for a search, where it is. */
@Composable
private fun EntryRow(ui: FileManagerUi, entry: FileEntry, picked: Boolean, focused: Boolean, compact: Boolean, search: Boolean) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val detail = remember(entry.path, entry.size, entry.modifiedMs) { entryDetail(context, entry) }
    val where = if (search && !DocFiles.isDoc(entry.path)) {
        remember(entry.path, ui.volumes) { File(entry.path).parent?.let { p -> fileCrumbs(p, ui.volumes).joinToString(" / ") { it.label } } }
    } else {
        null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    picked -> kb.accent.copy(alpha = 0.20f)
                    focused -> kb.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 5.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(entry, if (compact) 30.dp else 38.dp, picked, ui.prefs.thumbnails)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = kb.suggestionText, fontSize = if (compact) 13.sp else 13.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (detail.isNotEmpty()) Text(detail, color = kb.secondaryText, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1)
            if (where != null) Text(where, color = kb.secondaryText, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
    }
}

private val EDGE = 48.dp
private const val EDGE_SPEED = 0.35f
