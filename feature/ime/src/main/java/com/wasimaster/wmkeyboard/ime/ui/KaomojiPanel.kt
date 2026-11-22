package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.emoji.Kaomoji
import com.wasimaster.wmkeyboard.core.emoji.KaomojiKind
import com.wasimaster.wmkeyboard.core.emoji.KaomojiLibrary
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.KaomojiRailButton
import com.wasimaster.wmkeyboard.core.settings.KaomojiRailSide
import com.wasimaster.wmkeyboard.core.settings.KaomojiSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiAction
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiCallbacks
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiEdit
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiTextField
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiUi
import kotlin.math.ceil

/**
 * The emoji panel's kaomoji tab: a strip of tag tabs across the top, the
 * edit rail down one side, and the faces of the chosen tag in a grid. Its
 * state is [com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiController]'s; this
 * file only draws it and reports taps.
 *
 * The long-press actions and the tag list are drawn inside the panel, over the
 * grid, rather than as popups: every Compose popup in an input method is a
 * window of its own, and one that is not needed is one fewer to get wrong.
 */

/** Whether one of the emoji panel's own boxes has the keys, so the panel shrinks and they come back. */
internal fun emojiPanelTyping(state: KeyboardUiState): Boolean =
    state.panel == PanelMode.EMOJI && (state.emojiSearchActive || state.kaomojiText != null)

/** The family a text-art tab shows: both when merged, one each when split. */
internal fun kaomojiKindFor(tab: String, settings: KaomojiSettings): KaomojiKind? = when {
    !settings.splitEmoticons -> null
    tab == EMOTICON_TAB -> KaomojiKind.EMOTICON
    else -> KaomojiKind.KAOMOJI
}

/**
 * A text-art tab's rows in the continuous list (#540): the tag strip and the
 * chosen tag's faces, or, with the tag tabs off, every tag under its heading.
 */
internal fun kaomojiListItems(tab: String, ui: KaomojiUi, settings: KaomojiSettings): List<EmojiListItem> {
    val kind = kaomojiKindFor(tab, settings)
    val library = ui.library
    return buildList {
        if (settings.tagTabs) {
            add(EmojiListItem.ArtStrip(tab))
            val view = ui.view(tab)
            library.view(view, kind).forEach { add(EmojiListItem.Art(tab, view, it)) }
        } else {
            for (tag in library.visibleTags(kind)) {
                val faces = library.view(tag, kind)
                if (faces.isEmpty()) continue
                add(EmojiListItem.SubHeader(tab, tag))
                // A face under two tags is two chips, one per heading.
                faces.forEach { add(EmojiListItem.Art(tab, tag, it)) }
            }
        }
    }
}

/** One page of the paged emoji panel: the kaomoji tab, or the split Emoticons tab. */
@Composable
internal fun KaomojiPage(state: KeyboardUiState, tab: String, ui: KaomojiUi, callbacks: KaomojiCallbacks) {
    val settings = state.settings.emoji.kaomoji
    val act = callbacks.onAction
    val kind = kaomojiKindFor(tab, settings)
    val railOn = settings.rail != KaomojiRailSide.OFF && settings.railButtons.isNotEmpty()
    val railItems = kaomojiRailItems(tab, ui, settings, act, onRail = railOn)
    Row(modifier = Modifier.fillMaxSize()) {
        if (railOn && settings.rail == KaomojiRailSide.LEFT) KaomojiRail(railItems)
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (settings.tagTabs) {
                KaomojiTagStrip(
                    tab = tab,
                    ui = ui,
                    kind = kind,
                    act = act,
                    // Without a rail its buttons ride at the end of the strip.
                    leading = if (KaomojiRailButton.SEARCH !in settings.railButtons || !railOn) {
                        listOf(searchItem(ui, act))
                    } else {
                        emptyList()
                    },
                    trailing = if (railOn) emptyList() else railItems.filter { it.button != KaomojiRailButton.SEARCH },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (ui.tagsOpen) {
                    KaomojiTagManager(ui, act)
                } else {
                    val view = ui.view(tab)
                    val gridState = rememberLazyGridState()
                    if (settings.tagTabs) {
                        val items = remember(ui.library, view, kind) { ui.library.view(view, kind) }
                        KaomojiGrid(items, ui, act, gridState, emptyFor = view)
                    } else {
                        KaomojiSectionedGrid(ui, kind, act, gridState)
                    }
                    // A long-pressed face's actions, over the bottom of the grid.
                    KaomojiHeldBar(ui, act, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        if (railOn && settings.rail == KaomojiRailSide.RIGHT) KaomojiRail(railItems)
    }
}

/** One rail button, also drawn at the end of the tag strip when the rail is off. */
internal class KaomojiRailItem(
    val button: KaomojiRailButton,
    val icon: ImageVector,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun searchItem(ui: KaomojiUi, act: (KaomojiAction) -> Unit) = KaomojiRailItem(
    KaomojiRailButton.SEARCH,
    Icons.Outlined.Search,
    stringResource(R.string.ime_kaomoji_search),
    ui.searching,
) { act(KaomojiAction.ToggleSearch) }

@Composable
private fun kaomojiRailItems(
    tab: String,
    ui: KaomojiUi,
    settings: KaomojiSettings,
    act: (KaomojiAction) -> Unit,
    onRail: Boolean,
): List<KaomojiRailItem> {
    val all = listOf(
        searchItem(ui, act),
        KaomojiRailItem(KaomojiRailButton.ADD, Icons.Outlined.Add, stringResource(R.string.ime_kaomoji_add), false) {
            act(KaomojiAction.Add(tab))
        },
        KaomojiRailItem(
            KaomojiRailButton.EDIT,
            if (ui.editMode) Icons.Outlined.Check else Icons.Outlined.Edit,
            stringResource(if (ui.editMode) R.string.ime_kaomoji_done else R.string.ime_kaomoji_edit_mode),
            ui.editMode,
        ) { act(KaomojiAction.ToggleEditMode) },
        KaomojiRailItem(
            KaomojiRailButton.TAGS,
            Icons.Outlined.Label,
            stringResource(R.string.ime_kaomoji_tags),
            ui.tagsOpen,
        ) { act(KaomojiAction.ToggleTags) },
    )
    // The rail carries what the user kept on it; with the rail off, the strip
    // carries Add, Edit and Tags so editing is never out of reach.
    return if (onRail) all.filter { it.button in settings.railButtons } else all
}

@Composable
private fun KaomojiRail(items: List<KaomojiRailItem>) {
    Column(
        modifier = Modifier
            .width(KaomojiRailWidth)
            .fillMaxHeight()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in items) KaomojiIconButton(item)
    }
}

@Composable
private fun KaomojiIconButton(item: KaomojiRailItem) {
    val tint = if (item.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                if (item.active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = item.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(item.icon, contentDescription = item.description, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/**
 * The tag tabs: Favourites, Recent and All, then the user's tags in their
 * order. Tapping one filters the grid in place; the strip scrolls sideways and
 * keeps the chosen tab in view. No swiping between tags: the panel's own pager
 * already owns the sideways swipe.
 */
@Composable
internal fun KaomojiTagStrip(
    tab: String,
    ui: KaomojiUi,
    kind: KaomojiKind?,
    act: (KaomojiAction) -> Unit,
    leading: List<KaomojiRailItem> = emptyList(),
    trailing: List<KaomojiRailItem> = emptyList(),
) {
    val library = ui.library
    val selected = ui.view(tab)
    val favouritesLabel = stringResource(R.string.ime_kaomoji_favourites)
    val recentLabel = stringResource(R.string.ime_kaomoji_recent)
    val allLabel = stringResource(R.string.ime_kaomoji_all)
    val views = remember(library, kind, favouritesLabel, recentLabel, allLabel) {
        buildList {
            if (library.favourites(kind).isNotEmpty()) add(KaomojiLibrary.VIEW_FAVOURITES to "★ $favouritesLabel")
            if (library.recents(kind, 1).isNotEmpty()) add(KaomojiLibrary.VIEW_RECENT to recentLabel)
            add(KaomojiLibrary.VIEW_ALL to allLabel)
            for (tag in library.visibleTags(kind)) add(tag to tag)
        }
    }
    val scroll = rememberScrollState()
    val index = views.indexOfFirst { it.first.equals(selected, ignoreCase = true) }
    val chipPx = with(LocalDensity.current) { KaomojiChipEstimate.toPx() }
    LaunchedEffect(index, scroll.maxValue) {
        if (index >= 0) scroll.animateScrollTo((index * chipPx - chipPx).toInt().coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KaomojiStripHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in leading) KaomojiIconButton(item)
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((view, label) in views) {
                KaomojiChip(
                    label = label,
                    selected = view.equals(selected, ignoreCase = true),
                ) { act(KaomojiAction.SelectView(tab, view)) }
            }
        }
        for (item in trailing) KaomojiIconButton(item)
    }
}

@Composable
private fun KaomojiChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(28.dp)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (selected) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

/** How many grid columns a face takes, out of [maxLineSpan], on a grid of [KaomojiCellMin] columns. */
internal fun kaomojiSpan(kind: KaomojiKind, maxLineSpan: Int, column: Dp = KaomojiCellMin): Int {
    val wanted = if (kind == KaomojiKind.KAOMOJI) KaomojiWide else EmoticonWide
    return ceil(wanted.value / column.value).toInt().coerceIn(1, maxLineSpan.coerceAtLeast(1))
}

/**
 * The faces of one view. In edit mode a tap opens the face's editor and a
 * held face drags to a new place; the order is written once, on the drop,
 * and the faces outside this view keep theirs.
 */
@Composable
private fun KaomojiGrid(
    items: List<Kaomoji>,
    ui: KaomojiUi,
    act: (KaomojiAction) -> Unit,
    gridState: LazyGridState,
    emptyFor: String,
) {
    if (items.isEmpty()) {
        KaomojiEmpty(emptyFor)
        return
    }
    // The drag's working copy: moved under the finger, committed on the drop.
    var working by remember(items) { mutableStateOf(items) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    val editMode = ui.editMode
    // Recent is in time order, not the user's; nothing there to drag.
    val draggable = editMode && emptyFor != KaomojiLibrary.VIEW_RECENT
    fun itemAt(at: Offset): Long? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        at.x >= it.offset.x && at.x < it.offset.x + it.size.width &&
            at.y >= it.offset.y && at.y < it.offset.y + it.size.height
    }?.key as? Long
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = KaomojiCellMin),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(draggable, items) {
                if (!draggable) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        dragId = itemAt(at)
                        dragAt = at
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAt += amount
                        val moving = dragId ?: return@detectDragGesturesAfterLongPress
                        val over = itemAt(dragAt) ?: return@detectDragGesturesAfterLongPress
                        if (over != moving) {
                            val from = working.indexOfFirst { it.id == moving }
                            val to = working.indexOfFirst { it.id == over }
                            if (from >= 0 && to >= 0) {
                                working = working.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        }
                    },
                    onDragEnd = {
                        if (working.map { it.id } != items.map { it.id }) {
                            act(KaomojiAction.Reorder(working.map { it.id }))
                        }
                        dragId = null
                    },
                    onDragCancel = {
                        working = items
                        dragId = null
                    },
                )
            },
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 48.dp),
    ) {
        items(
            count = working.size,
            key = { working[it].id },
            span = { GridItemSpan(kaomojiSpan(working[it].kind, maxLineSpan)) },
        ) { index ->
            val item = working[index]
            KaomojiCell(item, ui, act, dragging = item.id == dragId)
        }
    }
}

/** With the tag tabs off: every visible tag as a heading, the way the tab always looked. */
@Composable
private fun KaomojiSectionedGrid(ui: KaomojiUi, kind: KaomojiKind?, act: (KaomojiAction) -> Unit, gridState: LazyGridState) {
    val library = ui.library
    val sections = remember(library, kind) {
        library.visibleTags(kind).map { tag -> tag to library.view(tag, kind) }.filter { it.second.isNotEmpty() } +
            listOfNotNull(library.items(kind).filter { it.tags.isEmpty() }.takeIf { it.isNotEmpty() }?.let { "" to it })
    }
    val other = stringResource(R.string.ime_kaomoji_untagged)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = KaomojiCellMin),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 48.dp),
    ) {
        for ((tag, faces) in sections) {
            item(key = "§$tag", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = tag.ifEmpty { other },
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            // A face under two tags shows under both, so the key carries the tag.
            items(
                count = faces.size,
                key = { "$tag\u0000${faces[it].id}" },
                span = { GridItemSpan(kaomojiSpan(faces[it].kind, maxLineSpan)) },
            ) { index -> KaomojiCell(faces[index], ui, act, dragging = false) }
        }
    }
}

@Composable
private fun KaomojiEmpty(view: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(
                when (view) {
                    KaomojiLibrary.VIEW_FAVOURITES -> R.string.ime_kaomoji_empty_favourites
                    KaomojiLibrary.VIEW_RECENT -> R.string.ime_kaomoji_empty_recent
                    else -> R.string.ime_kaomoji_empty
                },
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One face: a key-coloured chip whose text shrinks until it fits on a line,
 * since half a kaomoji is unrecognisable. A tap puts it in (or, in edit mode,
 * opens its editor); a hold shows its actions. Favourites wear a star.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KaomojiCell(item: Kaomoji, ui: KaomojiUi, act: (KaomojiAction) -> Unit, dragging: Boolean) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val held = ui.held == item.id
    var fontSize by remember(item.text) { mutableStateOf(15.sp) }
    var settled by remember(item.text) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .height(38.dp)
            .graphicsLayer {
                if (dragging) {
                    scaleX = 1.08f
                    scaleY = 1.08f
                    alpha = 0.85f
                }
            }
            .background(kb.keyFace(kb.key), kb.keyShape())
            .then(
                if (held || dragging || ui.editMode) {
                    Modifier.border(
                        if (held || dragging) 2.dp else 1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = if (held || dragging) 0.9f else 0.35f),
                        kb.keyShape(),
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    feedback()
                    if (ui.editMode) act(KaomojiAction.Edit(item.id)) else act(KaomojiAction.Insert(item.text))
                },
                // In edit mode the hold belongs to the grid's drag.
                onLongClick = if (ui.editMode) null else ({ act(KaomojiAction.Hold(item.id)) }),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.text,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { alpha = if (settled) 1f else 0f },
            maxLines = 1,
            softWrap = false,
            fontSize = fontSize,
            color = kb.keyText,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && fontSize.value > 8f) {
                    fontSize = (fontSize.value * 0.88f).sp
                } else {
                    settled = true
                }
            },
        )
        if (item.favourite) {
            Text(
                text = "★",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 1.dp),
            )
        }
    }
}

/** The held face's actions, as a bar over the bottom of the grid. */
@Composable
internal fun KaomojiHeldBar(ui: KaomojiUi, act: (KaomojiAction) -> Unit, modifier: Modifier = Modifier) {
    val id = ui.held ?: return
    val item = ui.library.items.firstOrNull { it.id == id } ?: return
    val kb = LocalKbTheme.current
    val confirming = ui.confirmDelete == id
    Row(
        modifier = modifier
            .padding(6.dp)
            .fillMaxWidth()
            .height(42.dp)
            .background(kb.popup, RoundedCornerShape(21.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(21.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.text,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        KaomojiIconButton(
            KaomojiRailItem(
                KaomojiRailButton.EDIT,
                Icons.Outlined.Star,
                stringResource(if (item.favourite) R.string.ime_kaomoji_unfavourite else R.string.ime_kaomoji_favourite),
                item.favourite,
            ) { act(KaomojiAction.Favourite(id)) },
        )
        KaomojiIconButton(
            KaomojiRailItem(KaomojiRailButton.EDIT, Icons.Outlined.Edit, stringResource(R.string.ime_kaomoji_edit), false) {
                act(KaomojiAction.Edit(id))
            },
        )
        KaomojiIconButton(
            KaomojiRailItem(KaomojiRailButton.EDIT, Icons.Outlined.ContentCopy, stringResource(R.string.ime_kaomoji_copy), false) {
                act(KaomojiAction.Copy(id))
            },
        )
        KaomojiIconButton(
            KaomojiRailItem(
                KaomojiRailButton.EDIT,
                Icons.Outlined.Delete,
                stringResource(if (confirming) R.string.ime_kaomoji_delete_confirm else R.string.ime_kaomoji_delete),
                confirming,
            ) { act(KaomojiAction.Delete(id)) },
        )
        KaomojiIconButton(
            KaomojiRailItem(KaomojiRailButton.EDIT, Icons.Outlined.Close, stringResource(R.string.ime_kaomoji_cancel), false) {
                act(KaomojiAction.Hold(null))
            },
        )
    }
}

/**
 * The tag list, in place of the grid: each tag with its place in the strip
 * (up and down), whether the strip shows it, a rename and a delete. A new tag
 * starts empty and fills from the editor or here.
 */
@Composable
internal fun KaomojiTagManager(ui: KaomojiUi, act: (KaomojiAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val counts = remember(ui.library) {
        ui.library.tags.associateWith { tag -> ui.library.items.count { it.hasTag(tag) } }
    }
    Column(modifier = modifier.fillMaxSize().background(kb.popup)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ime_kaomoji_tags),
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.Add, stringResource(R.string.ime_kaomoji_new_tag), false) {
                    act(KaomojiAction.TagRename(""))
                },
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.Settings, stringResource(R.string.ime_kaomoji_more_settings), false) {
                    act(KaomojiAction.OpenSettings)
                },
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.Check, stringResource(R.string.ime_kaomoji_done), false) {
                    act(KaomojiAction.ToggleTags)
                },
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(ui.library.tags, key = { it }) { tag ->
                val hidden = ui.library.isHidden(tag)
                val confirming = ui.tagConfirmDelete == tag
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tag,
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = if (hidden) 0.5f else 1f },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = (counts[tag] ?: 0).toString(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    KaomojiIconButton(
                        KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.ArrowUpward, stringResource(R.string.ime_kaomoji_move_up), false) {
                            act(KaomojiAction.TagMove(tag, -1))
                        },
                    )
                    KaomojiIconButton(
                        KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.ArrowDownward, stringResource(R.string.ime_kaomoji_move_down), false) {
                            act(KaomojiAction.TagMove(tag, 1))
                        },
                    )
                    KaomojiIconButton(
                        KaomojiRailItem(
                            KaomojiRailButton.TAGS,
                            if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (hidden) R.string.ime_kaomoji_show_tag else R.string.ime_kaomoji_hide_tag),
                            false,
                        ) { act(KaomojiAction.TagHidden(tag, !hidden)) },
                    )
                    KaomojiIconButton(
                        KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.Edit, stringResource(R.string.ime_kaomoji_rename), false) {
                            act(KaomojiAction.TagRename(tag))
                        },
                    )
                    KaomojiIconButton(
                        KaomojiRailItem(
                            KaomojiRailButton.TAGS,
                            Icons.Outlined.Delete,
                            stringResource(if (confirming) R.string.ime_kaomoji_delete_confirm else R.string.ime_kaomoji_delete),
                            confirming,
                        ) { act(KaomojiAction.TagDelete(tag)) },
                    )
                }
            }
        }
    }
}

/**
 * The emoji panel while one of the kaomoji tab's boxes has the keys: the
 * search with a couple of rows of matches, the add/edit sheet, or a tag's
 * name. The key rows come back underneath, as they do for the emoji search,
 * and the panel gives up the height they take so the window keeps its size.
 */
@Composable
internal fun KaomojiTypingPanel(
    state: KeyboardUiState,
    ui: KaomojiUi,
    callbacks: KaomojiCallbacks,
    onClose: () -> Unit,
) {
    val entry = state.kaomojiText ?: return
    val act = callbacks.onAction
    val fullBleed = state.settings.emojiFullBleed
    val barCompensation = if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
    val strip = captureStripHeight(state)
    val wanted = if (fullBleed) {
        KaomojiTypingPanelHeight + fullBleedHiddenRows(state, macroRowAtPanelOpen(state)) - strip
    } else {
        KaomojiTypingPanelHeight + topBarHeight(state.settings) + barCompensation - strip
    }
    val height = toolPanelHeight(state, wanted, floor = FullBleedHeaderHeight)
    Column(modifier = Modifier.fillMaxWidth().height(height)) {
        if (fullBleed) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolCircle(
                    slot = IconSlots.CHROME_PANEL_BACK,
                    description = stringResource(R.string.ime_panel_back_desc),
                    active = false,
                    onClick = onClose,
                )
            }
        }
        val edit = ui.edit
        when {
            entry.field == KaomojiTextField.SEARCH -> KaomojiSearchBody(state, ui, act, entry.text)
            edit != null -> KaomojiEditorBody(ui, edit, act, entry.text)
            else -> KaomojiRenameBody(ui, act, entry.text)
        }
    }
}

@Composable
private fun KaomojiField(
    text: String,
    placeholder: String,
    active: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .border(
                if (active) 1.5.dp else 0.dp,
                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        SearchQueryText(
            query = text,
            placeholder = placeholder,
            active = active,
            textColor = MaterialTheme.colorScheme.onSurface,
            placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun KaomojiSearchBody(state: KeyboardUiState, ui: KaomojiUi, act: (KaomojiAction) -> Unit, query: String) {
    val language = state.language.localeTag
    val results = remember(ui.library, query, language) {
        ui.library.search(query, null, kaomojiStemMatcher(language, query))
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = KaomojiCellMin),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(
                count = results.size,
                key = { results[it].id },
                span = { GridItemSpan(kaomojiSpan(results[it].kind, maxLineSpan)) },
            ) { index -> KaomojiCell(results[index], ui.copy(editMode = false, held = null), act, dragging = false) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KaomojiField(
                text = query,
                placeholder = stringResource(R.string.ime_kaomoji_search_hint),
                active = true,
                onTap = {},
                modifier = Modifier.weight(1f),
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.SEARCH, Icons.Outlined.Close, stringResource(R.string.ime_kaomoji_cancel), false) {
                    act(KaomojiAction.CloseText)
                },
            )
        }
    }
}

@Composable
private fun KaomojiEditorBody(ui: KaomojiUi, edit: KaomojiEdit, act: (KaomojiAction) -> Unit, typed: String) {
    val faceText = if (edit.focused == KaomojiTextField.FACE) typed else edit.text
    val tagText = if (edit.focused == KaomojiTextField.TAG) typed else edit.tagDraft
    val tags = remember(ui.library.tags, edit.tags) {
        // The face's own tags first, then every other tag to add with a tap.
        edit.tags + ui.library.tags.filter { t -> edit.tags.none { it.equals(t, ignoreCase = true) } }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            KaomojiField(
                text = faceText,
                placeholder = stringResource(R.string.ime_kaomoji_face_hint),
                active = edit.focused == KaomojiTextField.FACE,
                onTap = { act(KaomojiAction.EditorFocus(KaomojiTextField.FACE)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            KaomojiIconButton(
                KaomojiRailItem(
                    KaomojiRailButton.EDIT,
                    Icons.Outlined.Delete,
                    stringResource(if (edit.confirmDelete) R.string.ime_kaomoji_delete_confirm else R.string.ime_kaomoji_delete),
                    edit.confirmDelete,
                ) { act(KaomojiAction.EditorDelete) },
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.EDIT, Icons.Outlined.Close, stringResource(R.string.ime_kaomoji_cancel), false) {
                    act(KaomojiAction.EditorCancel)
                },
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.EDIT, Icons.Outlined.Check, stringResource(R.string.ime_kaomoji_save), true) {
                    act(KaomojiAction.EditorSave)
                },
            )
        }
        // The tags: those on the face are lit; the last chip types a new one.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KaomojiStripHeight)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val kind = edit.kind ?: KaomojiKind.of(faceText)
            KaomojiChip(
                label = stringResource(
                    if (kind == KaomojiKind.EMOTICON) R.string.ime_kaomoji_kind_emoticon else R.string.ime_kaomoji_kind_kaomoji,
                ),
                selected = false,
            ) {
                act(KaomojiAction.EditorKind(if (kind == KaomojiKind.EMOTICON) KaomojiKind.KAOMOJI else KaomojiKind.EMOTICON))
            }
            for (tag in tags) {
                KaomojiChip(label = tag, selected = edit.tags.any { it.equals(tag, ignoreCase = true) }) {
                    act(KaomojiAction.EditorTag(tag))
                }
            }
            KaomojiField(
                text = tagText,
                placeholder = "+ " + stringResource(R.string.ime_kaomoji_new_tag),
                active = edit.focused == KaomojiTextField.TAG,
                onTap = { act(KaomojiAction.EditorFocus(KaomojiTextField.TAG)) },
                modifier = Modifier.width(140.dp),
            )
        }
    }
}

@Composable
private fun KaomojiRenameBody(ui: KaomojiUi, act: (KaomojiAction) -> Unit, typed: String) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(if (ui.tagRename.isNullOrEmpty()) R.string.ime_kaomoji_new_tag else R.string.ime_kaomoji_rename),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            KaomojiField(
                text = typed,
                placeholder = stringResource(R.string.ime_kaomoji_rename_hint),
                active = true,
                onTap = {},
                modifier = Modifier.weight(1f),
            )
            KaomojiIconButton(
                KaomojiRailItem(KaomojiRailButton.TAGS, Icons.Outlined.Close, stringResource(R.string.ime_kaomoji_cancel), false) {
                    act(KaomojiAction.CloseText)
                },
            )
        }
    }
}

/** Width of the edit rail. */
private val KaomojiRailWidth = 40.dp

/** Height of the tag strip. */
internal val KaomojiStripHeight = 36.dp

/** The grid's column; a kaomoji takes enough of them for [KaomojiWide], an emoticon for [EmoticonWide]. */
internal val KaomojiCellMin = 66.dp
private val KaomojiWide = 132.dp
private val EmoticonWide = 64.dp

/** A guess at one tag chip's width, to keep the chosen one in view. */
private val KaomojiChipEstimate = 72.dp

/** The panel's own height while one of its boxes has the keys. */
private val KaomojiTypingPanelHeight = 128.dp

/**
 * Faces with a tag holding another form of [query]'s words, stemmed in
 * [languageTag]: the kaomoji search's last group.
 */
internal fun kaomojiStemMatcher(languageTag: String?, query: String): ((Kaomoji) -> Boolean)? =
    com.wasimaster.wmkeyboard.core.engine.SearchStems.matcher<Kaomoji>(languageTag, query) {
        it.tags.joinToString(" ")
    }
