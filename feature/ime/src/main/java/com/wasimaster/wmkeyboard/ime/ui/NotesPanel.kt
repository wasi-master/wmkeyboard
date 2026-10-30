package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Subject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Inventory2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteChecklist
import com.wasimaster.wmkeyboard.core.notes.NoteSort
import com.wasimaster.wmkeyboard.core.notes.NoteTag
import com.wasimaster.wmkeyboard.core.notes.NoteTagPalette
import com.wasimaster.wmkeyboard.core.settings.NotesPanelLayout
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.ui.SwipeAction
import com.wasimaster.wmkeyboard.core.ui.SwipeTone
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.NoteEdit
import com.wasimaster.wmkeyboard.ime.NoteEditField
import com.wasimaster.wmkeyboard.ime.NoteMarkdownAction
import com.wasimaster.wmkeyboard.ime.NotesConfirm
import com.wasimaster.wmkeyboard.ime.NotesFilter
import com.wasimaster.wmkeyboard.ime.NotesPanelUi
import com.wasimaster.wmkeyboard.ime.NotesUndo
import com.wasimaster.wmkeyboard.ime.NotesUndoKind
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.MiniRing
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringGentle
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import java.util.Calendar

/**
 * The Notes panel's service callbacks (#560), bundled into one value for the
 * reason [VocabCallbacks] is: the callers sit against the JVM's 64K
 * method-size ceiling, so a new panel may not add parameters to them. The
 * bundle rides [ToolHoldCallbacks.notes].
 */
data class NotesCallbacks(
    val onFilter: (NotesFilter) -> Unit = {},
    val onSearchToggle: () -> Unit = {},
    val onSearchClear: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    /** Out of the editor, then out of the note, then out of the panel. */
    val onBack: () -> Unit = {},
    val onReadExpand: () -> Unit = {},
    /** A new note, born with this tag when one is given. */
    val onNew: (String?) -> Unit = {},
    /** Edit a note, the caret at the start of this source line when one is given. */
    val onEditStart: (Long, Int?) -> Unit = { _, _ -> },
    /** One of the editor's fields takes the keys, the caret at this offset when given. */
    val onEditFocus: (NoteEditField, Int?) -> Unit = { _, _ -> },
    val onEditDone: () -> Unit = {},
    val onEditCancel: () -> Unit = {},
    val onMarkdown: (NoteMarkdownAction) -> Unit = {},
    /** A tag on or off a note; the palette index is what a suggested tag is born with. */
    val onTagToggle: (Long, String, Int) -> Unit = { _, _, _ -> },
    val onTagPickerToggle: () -> Unit = {},
    val onTagTypeStart: () -> Unit = {},
    val onTagCommit: () -> Unit = {},
    val onTagTypeCancel: () -> Unit = {},
    val onInsert: (Long) -> Unit = {},
    val onCopy: (Long) -> Unit = {},
    val onShare: (Long) -> Unit = {},
    val onPin: (Long, Boolean) -> Unit = { _, _ -> },
    val onArchive: (Long, Boolean) -> Unit = { _, _ -> },
    val onLock: (Long) -> Unit = {},
    val onTrash: (Long) -> Unit = {},
    val onRestore: (Long) -> Unit = {},
    val onDeleteForever: (Long) -> Unit = {},
    val onEmptyTrash: () -> Unit = {},
    val onConfirmDismiss: () -> Unit = {},
    /** A task box tapped: the note, and the source line of the box. */
    val onTaskToggle: (Long, Int) -> Unit = { _, _ -> },
    /** A row long-pressed shows its action chips; null puts them away. */
    val onHold: (Long?) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoDismiss: () -> Unit = {},
    /** A chip on the Quick note row (#864): a note to add to, or null for a new one. */
    val onQuickPick: (Long?) -> Unit = {},
)

/** Settings routes the panel links to; `SettingsRoutesTest` scans these constants. */
internal const val NOTES_ROUTE = "notes"
internal const val NOTES_TAGS_ROUTE = "notes/tags"

/** Where the read view stops drawing a long note until Show more is tapped. */
private const val READ_INITIAL_CHARS = 6_000

/** The three things the panel can be showing. */
private enum class NotesFace { LIST, READ, EDIT }

/**
 * The Notes panel inside its full-bleed frame (#560): the list as a shelf of
 * cards (or a timeline, by the setting), a note open to read as its card
 * grown to fill the panel, or the editor with the keys back beneath it.
 *
 * The list and the read view are the same card at two sizes: a tap grows it
 * out of the shelf, Back shrinks it away. Nothing here owns a note; the
 * service does, and the panel draws what it publishes.
 */
@Composable
internal fun NotesPanelHost(
    state: KeyboardUiState,
    callbacks: NotesCallbacks,
    onPanelChange: (PanelMode) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    val editing = notes.editing
    val open = notes.openNote()
    val reading = editing == null && open != null
    val face = when {
        editing != null -> NotesFace.EDIT
        open != null -> NotesFace.READ
        else -> NotesFace.LIST
    }
    // What a face on its way out keeps drawing: the note is already gone from
    // the state by the time its card has faded.
    var lastOpen by remember { mutableStateOf(open) }
    if (open != null) lastOpen = open
    var lastEdit by remember { mutableStateOf(editing) }
    if (editing != null) lastEdit = editing
    // The read view's second row of actions, behind the header's More button.
    var moreShown by rememberSaveable(open?.id) { mutableStateOf(false) }
    val lineHeightDp = textLineHeightDp()
    FullBleedTool(
        state,
        title = "",
        onClose = {
            when {
                editing != null -> callbacks.onEditDone()
                open != null -> callbacks.onBack()
                else -> onPanelChange(PanelMode.NOTES)
            }
        },
        // The read view may grow to the screen's share (#348's expand); the
        // frame caps it below the status bar.
        extraHeight = if (reading && notes.readExpanded) LocalConfiguration.current.screenHeightDp.dp else 0.dp,
        compact = state.notesTypingActive,
        compactHeight = when {
            editing != null -> notesEditCompactHeight(lineHeightDp, quickRow = notes.quick)
            // Searching: the pill, the chips, and a few rows of results under them.
            else -> FullBleedHeaderHeight + lineHeightDp * 8
        },
        headerActions = {
            when {
                editing != null -> NotesEditHeader(state, editing.titleDraft, editing.focused == NoteEditField.TITLE, callbacks)
                open != null -> NotesReadHeader(state, open, moreShown, onMore = { moreShown = !moreShown }, callbacks = callbacks)
                else -> NotesListHeader(state, callbacks, onOpenRoute)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NotesFaceSwitch(face, animate = kb.motion.panels) { shown ->
                Box(Modifier.fillMaxSize().blockTouches(shown != face)) {
                    when (shown) {
                        NotesFace.EDIT -> lastEdit?.let { NotesEditor(state, it, callbacks, onOpenRoute) }
                        NotesFace.READ -> lastOpen?.let { NotesReadView(state, it, moreShown, callbacks, onOpenRoute) }
                        NotesFace.LIST -> NotesList(state, callbacks, onOpenRoute)
                    }
                }
            }
            NotesUndoBar(
                undo = notes.undo,
                reduceMotion = state.settings.reduceMotion,
                onUndo = callbacks.onUndo,
                onDismiss = callbacks.onUndoDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * The list, the read view and the editor, one shown at a time. A note opens
 * by growing out of the shelf and closes by shrinking back; the editor
 * cross-fades, since the frame is already changing height under it. With
 * [animate] off the face is drawn straight.
 */
@Composable
private fun NotesFaceSwitch(face: NotesFace, animate: Boolean, content: @Composable (NotesFace) -> Unit) {
    if (!animate) {
        Box(Modifier.fillMaxSize()) { content(face) }
        return
    }
    AnimatedContent(
        targetState = face,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val opening = targetState == NotesFace.READ && initialState == NotesFace.LIST
            val closing = targetState == NotesFace.LIST && initialState == NotesFace.READ
            val transform = when {
                opening -> (scaleIn(kitSpringGentle(), initialScale = FACE_SCALE) + fadeIn(tween(FACE_IN_MS))) togetherWith
                    fadeOut(tween(FACE_OUT_MS))
                closing -> fadeIn(tween(FACE_IN_MS, delayMillis = FACE_IN_DELAY_MS)) togetherWith
                    (scaleOut(tween(FACE_OUT_MS), targetScale = FACE_SCALE) + fadeOut(tween(FACE_OUT_MS)))
                else -> fadeIn(tween(FACE_IN_MS, delayMillis = FACE_IN_DELAY_MS)) togetherWith fadeOut(tween(FACE_OUT_MS))
            }
            transform using null
        },
        label = "notes-face",
    ) { shown -> content(shown) }
}

// ---- headers ----

/**
 * Back, then the filter chips scrolling, then a round Search button. While
 * searching the whole row is the search pill, and the chips move down into
 * the body so a filter stays a tap away.
 */
@Composable
private fun RowScope.NotesListHeader(state: KeyboardUiState, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit) {
    val notes = state.notes
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.SEARCH, count = 1, columns = 1) {
        callbacks.onSearchToggle()
    }
    if (notes.searchActive) {
        MediaHeaderSearchBar(
            state = state,
            placeholder = stringResource(R.string.ime_notes_search_hint),
            onQueryTap = callbacks.onSearchToggle,
            activePlaceholder = stringResource(R.string.ime_notes_search_active_hint),
            focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
            onClear = callbacks.onSearchClear,
            query = notes.query,
            active = notes.searchActive,
        )
        return
    }
    if (notes.available) {
        NotesChipRow(state, callbacks, onOpenRoute, Modifier.weight(1f).padding(start = 4.dp, end = 2.dp))
    } else {
        Spacer(Modifier.weight(1f))
    }
    NotesHeaderButton(
        Icons.Outlined.Search,
        stringResource(R.string.ime_notes_search_desc),
        ringed = state.focusedIndex(FocusRegion.SEARCH) == 0,
    ) { callbacks.onSearchToggle() }
}

/** Pin, Lock, taller, and More: the ways with a note that are not on the bar along the bottom. */
@Composable
private fun RowScope.NotesReadHeader(
    state: KeyboardUiState,
    note: Note,
    moreShown: Boolean,
    onMore: () -> Unit,
    callbacks: NotesCallbacks,
) {
    val ringed = state.focusedIndex(FocusRegion.ACTIONS)
    Spacer(Modifier.weight(1f))
    NotesHeaderButton(
        Icons.Outlined.PushPin,
        stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action),
        active = note.pinned,
        ringed = ringed == READ_ACTION_PIN && !note.inTrash && !note.locked,
    ) { callbacks.onPin(note.id, !note.pinned) }
    if (!note.locked && !note.inTrash) {
        NotesHeaderButton(Icons.Outlined.Lock, stringResource(R.string.ime_notes_lock_action), ringed = ringed == READ_ACTION_LOCK) {
            callbacks.onLock(note.id)
        }
    }
    if (!shortLandscape()) {
        NotesHeaderButton(
            if (state.notes.readExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
            stringResource(if (state.notes.readExpanded) R.string.ime_notes_collapse_desc else R.string.ime_notes_expand_desc),
            onClick = callbacks.onReadExpand,
        )
    }
    if (!note.locked && !note.inTrash) {
        NotesHeaderButton(Icons.Outlined.MoreHoriz, stringResource(R.string.ime_notes_more_desc), active = moreShown, onClick = onMore)
    }
}

@Composable
private fun RowScope.NotesEditHeader(state: KeyboardUiState, title: String, titleFocused: Boolean, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val ringed = state.focusedIndex(FocusRegion.ACTIONS)
    NotesHeaderButton(Icons.Outlined.Check, stringResource(R.string.ime_notes_done_desc), active = true, ringed = ringed == 0) {
        callbacks.onEditDone()
    }
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { callbacks.onEditFocus(NoteEditField.TITLE, null) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = title,
            placeholder = stringResource(R.string.ime_notes_title_hint),
            active = titleFocused,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(CommonR.string.common_cancel),
        color = kb.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .focusRing(ringed == 1, shape)
            .clip(shape)
            .clickable { callbacks.onEditCancel() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A round icon button in the header, the size of the tool circles beside it. */
@Composable
internal fun NotesHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    ringed: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    IconHint(description) {
        Box(
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .size(38.dp)
                .pressScale(source, kb.motion.any)
                .focusRing(ringed, CircleShape)
                .clip(CircleShape)
                .background(if (active) kb.accent.copy(alpha = if (kb.dark) 0.22f else 0.14f) else Color.Transparent)
                .clickable(interactionSource = source, indication = null) {
                    feedback()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = if (active) kb.accent else kb.toolbarIcon, modifier = Modifier.size(20.dp))
        }
    }
}

// ---- the filter chips ----

private class NotesFilterChip(val filter: NotesFilter, val label: String, val colour: Int?)

@Composable
private fun filterChips(notes: NotesPanelUi): List<NotesFilterChip> {
    @Composable
    fun counted(labelRes: Int, count: Int): String {
        val label = stringResource(labelRes)
        return if (count > 0) stringResource(R.string.ime_notes_filter_count, label, count) else label
    }
    return buildList {
        add(NotesFilterChip(NotesFilter.All, stringResource(R.string.ime_notes_filter_all), null))
        add(NotesFilterChip(NotesFilter.Pinned, counted(R.string.ime_notes_filter_pinned, notes.counts.pinned), null))
        for (tag in notes.tags) add(NotesFilterChip(NotesFilter.Tag(tag.name), tag.name, tag.colour))
        add(NotesFilterChip(NotesFilter.Archived, counted(R.string.ime_notes_filter_archived, notes.counts.archived), null))
        add(NotesFilterChip(NotesFilter.Trash, counted(R.string.ime_notes_filter_trash, notes.counts.trash), null))
    }
}

/** All, Pinned, a chip per tag with its dot, Archived, Trash, then Manage tags, scrolling sideways. */
@Composable
private fun NotesChipRow(state: KeyboardUiState, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    val chips = filterChips(notes)
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { index, chip ->
            NoteTagChip(
                label = chip.label,
                colour = chip.colour,
                filled = chip.filter == notes.filter,
                focused = focusedChip == index,
                onClick = { callbacks.onFilter(chip.filter) },
                onLongClick = if (chip.filter is NotesFilter.Tag) ({ onOpenRoute(NOTES_TAGS_ROUTE) }) else null,
            )
        }
        ToolPanelChip(
            stringResource(R.string.ime_notes_manage_tags_desc),
            icon = Icons.Outlined.Label,
            modifier = Modifier.focusRing(focusedChip == chips.size, kb.chipShape()),
        ) { onOpenRoute(NOTES_TAGS_ROUTE) }
    }
}

// ---- the list ----

@Composable
private fun NotesList(state: KeyboardUiState, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    if (!notes.available) {
        NotesMessage(stringResource(R.string.ime_notes_unlock_info))
        return
    }
    val chips = filterChips(notes)
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.CHIPS, count = chips.size + 1, columns = chips.size + 1) { index ->
        if (index < chips.size) callbacks.onFilter(chips[index].filter) else onOpenRoute(NOTES_TAGS_ROUTE)
    }
    val timeline = state.settings.notes.panelLayout == NotesPanelLayout.TIMELINE
    // The shelf keeps pinned notes on their own row while the list is one
    // that mixes them with the rest; under Pinned every note is pinned, and
    // the archive and the trash keep their pins as a memory, so there the
    // grid takes them all.
    val shelfPinned = !timeline && (notes.filter == NotesFilter.All || notes.filter is NotesFilter.Tag)
    val sort = state.settings.notes.sort
    val carousel = remember(notes.items, shelfPinned) { if (shelfPinned) notes.items.filter { it.pinned } else emptyList() }
    val rest = remember(notes.items, carousel, timeline, sort) {
        when {
            timeline -> timelineOrder(notes.items, sort)
            carousel.isEmpty() -> notes.items
            else -> notes.items.filterNot { it.pinned }
        }
    }
    // What the hardware ring walks, in the order it is drawn.
    val ordered = remember(carousel, rest) { carousel + rest }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.RESULTS, count = ordered.size, columns = 1) { index ->
        ordered.getOrNull(index)?.let { callbacks.onOpen(it.id) }
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = 0, columns = 1) {}
    val focusedRow = state.focusedIndex()
    Column(Modifier.fillMaxSize()) {
        if (notes.searchActive) {
            NotesChipRow(state, callbacks, onOpenRoute, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (notes.filter == NotesFilter.Trash && notes.items.isNotEmpty()) {
            TrashHeader(state, callbacks)
        }
        if (notes.items.isEmpty()) {
            NotesEmpty(state, callbacks)
            return@Column
        }
        val now = System.currentTimeMillis()
        val tagColours = remember(notes.tags) { notes.tags.associate { it.name.lowercase() to it.colour } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (timeline) {
                TimelineBody(state, rest, focusedRow, now, tagColours, callbacks, onOpenRoute)
            } else {
                ShelfBody(state, carousel, rest, focusedRow, now, tagColours, callbacks, onOpenRoute)
            }
            // The way to a new note floats over the list, and steps aside
            // for the Undo bar, which takes the same place.
            val animate = kb.motion.any
            AnimatedVisibility(
                visible = notes.undo == null,
                modifier = Modifier.align(if (timeline) Alignment.BottomEnd else Alignment.BottomCenter),
                enter = if (animate) fadeIn(tween(PILL_MS)) + slideInVertically(tween(PILL_MS)) { it / 2 } else fadeIn(snap()),
                exit = if (animate) fadeOut(tween(PILL_MS)) + slideOutVertically(tween(PILL_MS)) { it / 2 } else fadeOut(snap()),
            ) {
                if (timeline) {
                    NewNoteFab(state, callbacks, Modifier.padding(end = 14.dp, bottom = 12.dp))
                } else {
                    JotPill(state, callbacks, Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp))
                }
            }
        }
    }
}

/** The trash's retention line and its two-tap Empty trash chip. */
@Composable
private fun TrashHeader(state: KeyboardUiState, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            retentionText(state.settings.notes.trashDays),
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ConfirmChip(
            label = stringResource(R.string.ime_notes_empty_trash_action),
            armed = notes.confirm == NotesConfirm.EmptyTrash,
            onClick = callbacks.onEmptyTrash,
        )
    }
}

@Composable
private fun retentionText(days: Int): String =
    if (days > 0) stringResource(R.string.ime_notes_trash_retention, days) else stringResource(R.string.ime_notes_trash_kept)

/** A destructive chip that asks for a second tap: armed, it says so in the accent. */
@Composable
private fun ConfirmChip(label: String, armed: Boolean, enabled: Boolean = true, focused: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    ToolPanelChip(
        if (armed) stringResource(R.string.ime_notes_confirm_action) else label,
        selected = armed,
        enabled = enabled,
        modifier = Modifier.focusRing(focused, kb.chipShape()),
        onClick = onClick,
    )
}

@Composable
private fun NotesEmpty(state: KeyboardUiState, callbacks: NotesCallbacks) {
    val notes = state.notes
    if (notes.query.isNotBlank()) {
        NotesMessage(stringResource(R.string.ime_notes_no_match, notes.query.trim()), stringResource(CommonR.string.common_clear)) {
            callbacks.onSearchClear()
        }
        return
    }
    when (val filter = notes.filter) {
        NotesFilter.All -> NotesMessage(stringResource(R.string.ime_notes_empty_all), stringResource(R.string.ime_notes_new_action)) {
            callbacks.onNew(null)
        }
        NotesFilter.Pinned -> NotesMessage(stringResource(R.string.ime_notes_empty_pinned))
        is NotesFilter.Tag -> NotesMessage(
            stringResource(R.string.ime_notes_empty_tag, filter.name),
            stringResource(R.string.ime_notes_new_tagged_action, filter.name),
        ) { callbacks.onNew(filter.name) }
        NotesFilter.Archived -> NotesMessage(stringResource(R.string.ime_notes_empty_archived))
        NotesFilter.Trash -> NotesMessage(stringResource(R.string.ime_notes_empty_trash) + "\n" + retentionText(state.settings.notes.trashDays))
    }
}

@Composable
internal fun NotesMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = kb.toolbarIcon, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            ToolPanelChip(action, selected = true, onClick = onAction)
        }
    }
}

// ---- the shelf ----

/**
 * The shelf: a PINNED row of wide tinted cards scrolling sideways, then the
 * rest in a two-column staggered grid, the whole thing one scroll. Cards
 * pop in a beat apart as they first show.
 */
@Composable
private fun ShelfBody(
    state: KeyboardUiState,
    carousel: List<Note>,
    grid: List<Note>,
    focusedRow: Int?,
    now: Long,
    tagColours: Map<String, Int>,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val notes = state.notes
    val gridState = rememberLazyStaggeredGridState()
    // The label and the shelf sit ahead of the grid's own cards.
    val head = if (carousel.isNotEmpty()) 2 else 0
    ScrollFocusIntoView(focusedRow) { index ->
        gridState.animateScrollToItem(if (index < carousel.size) 0 else head + (index - carousel.size))
    }
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 4.dp,
            end = 12.dp,
            // Room under the last cards for the jot pill, or the Undo bar while it is up.
            bottom = if (notes.undo != null) UndoBarClearance else JotClearance,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        if (carousel.isNotEmpty()) {
            item(key = "pinned-label", span = StaggeredGridItemSpan.FullLine) { ShelfLabel() }
            item(key = "pinned-shelf", span = StaggeredGridItemSpan.FullLine) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(count = carousel.size, key = { carousel[it].id }) { index ->
                        val note = carousel[index]
                        ShelfCard(
                            state = state,
                            note = note,
                            now = now,
                            tagColours = tagColours,
                            index = index,
                            focused = focusedRow == index,
                            held = notes.held == note.id,
                            callbacks = callbacks,
                            onOpenRoute = onOpenRoute,
                        )
                    }
                }
            }
        }
        items(count = grid.size, key = { grid[it].id }) { index ->
            val note = grid[index]
            GridCard(
                state = state,
                note = note,
                now = now,
                tagColours = tagColours,
                index = carousel.size + index,
                focused = focusedRow == carousel.size + index,
                held = notes.held == note.id,
                callbacks = callbacks,
                onOpenRoute = onOpenRoute,
            )
        }
    }
}

/** "PINNED" in small caps with the pin beside it. */
@Composable
private fun ShelfLabel() {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, top = 2.dp)) {
        Icon(Icons.Outlined.PushPin, contentDescription = null, tint = kb.accent, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.ime_notes_pinned_section).uppercase(),
            color = kb.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

/**
 * A pinned note on the shelf: wide, tinted with its first tag's colour and
 * striped with it along the leading edge, the title over two lines of
 * preview, and a footer of its checklist ring and when it was touched.
 */
@Composable
private fun ShelfCard(
    state: KeyboardUiState,
    note: Note,
    now: Long,
    tagColours: Map<String, Int>,
    index: Int,
    focused: Boolean,
    held: Boolean,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val look = rememberNoteLook(state, note, now, tagColours, previewLines = state.settings.notes.previewLines.coerceAtMost(2))
    NoteCard(
        state = state,
        note = note,
        look = look,
        index = index,
        focused = focused,
        held = held,
        tinted = true,
        callbacks = callbacks,
        modifier = Modifier.width(ShelfCardWidth),
        minHeight = ShelfCardHeight,
    ) {
        Text(look.title, color = kb.modifierKeyText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (look.preview.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                look.preview,
                color = kb.toolbarIcon,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (held) {
            NoteActionChips(state, note, callbacks, onOpenRoute, modifier = Modifier.padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                NoteGlyph(note, look, size = 14.dp)
                if (look.progress != null || note.locked) Spacer(Modifier.width(5.dp))
                Text(look.footer, color = kb.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A note in the grid: title, the preview clamped to the setting, and a footer of ring or lock, time and tag dots. */
@Composable
private fun GridCard(
    state: KeyboardUiState,
    note: Note,
    now: Long,
    tagColours: Map<String, Int>,
    index: Int,
    focused: Boolean,
    held: Boolean,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val previewLines = state.settings.notes.previewLines
    val look = rememberNoteLook(state, note, now, tagColours, previewLines)
    NoteCard(
        state = state,
        note = note,
        look = look,
        index = index,
        focused = focused,
        held = held,
        tinted = false,
        callbacks = callbacks,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                look.title,
                color = kb.modifierKeyText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.PushPin, contentDescription = null, tint = kb.accent, modifier = Modifier.size(12.dp))
            }
        }
        if (look.preview.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                look.preview,
                color = kb.toolbarIcon,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = previewLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (held) {
            NoteActionChips(state, note, callbacks, onOpenRoute, modifier = Modifier.padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    NoteGlyph(note, look, size = 14.dp)
                    if (look.progress != null || note.locked) Spacer(Modifier.width(5.dp))
                    Text(look.footer, color = kb.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (note.archived && state.notes.filter != NotesFilter.Archived) {
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.ime_notes_archived_badge), color = kb.secondaryText, fontSize = 9.sp, maxLines = 1)
                    }
                }
                for (tag in note.tags.take(3)) {
                    Spacer(Modifier.width(3.dp))
                    TagDot(noteTagColour(kb, tagColours[tag.lowercase()] ?: 0), size = 7.dp)
                }
                if (note.tags.size > 3) {
                    Spacer(Modifier.width(3.dp))
                    Text("+${note.tags.size - 3}", color = kb.secondaryText, fontSize = 9.sp)
                }
            }
        }
    }
}

/**
 * The card every note wears on the shelf and in the grid: the swipe actions
 * behind it, a press that squashes it, a pop as it first shows, a tap that
 * opens it and a hold that shows its chips. [tinted] washes it with the
 * note's colour and stripes the leading edge; the grid's cards stay plain.
 */
@Composable
private fun NoteCard(
    state: KeyboardUiState,
    note: Note,
    look: NoteLook,
    index: Int,
    focused: Boolean,
    held: Boolean,
    tinted: Boolean,
    callbacks: NotesCallbacks,
    modifier: Modifier = Modifier,
    /** A floor for the card's height: the footer's weight fills down to it, and a held card grows past it. */
    minHeight: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val animate = kb.motion.any
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    val (swipeActions, fullSwipe) = noteSwipeActions(note, callbacks)
    KbSwipeActionsRow(
        actions = swipeActions,
        reduceMotion = state.settings.reduceMotion,
        fullSwipe = fullSwipe,
        // A held card is showing its chips, and a sideways drag there is the
        // chip row scrolling.
        enabled = !held,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .popIn(index.coerceAtMost(POP_STAGGER_CAP), animate)
                .pressScale(source, animate)
                .focusRing(focused, shape)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CARDS)
                .background(if (held) kb.chipActive.copy(alpha = kb.chipActive.alpha * 0.5f) else kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
                .then(if (tinted) Modifier.noteTint(look.colour) else Modifier)
                .chipBorder(kb, shape)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = { if (held) callbacks.onHold(null) else callbacks.onOpen(note.id) },
                    onLongClick = {
                        feedback()
                        callbacks.onHold(if (held) null else note.id)
                    },
                )
                .padding(start = if (tinted) 14.dp else 11.dp, end = 11.dp, top = 9.dp, bottom = 9.dp)
                .semantics { contentDescription = look.description },
            content = content,
        )
    }
}

/** A wash of [colour] over the card and a 4 dp stripe of it along the leading edge; drawn inside the clip. */
private fun Modifier.noteTint(colour: Color): Modifier =
    background(colour.copy(alpha = TINT_ALPHA)).drawBehind {
        val w = STRIPE.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - w
        drawRect(colour, Offset(x, 0f), Size(w, size.height))
    }

/** The checklist ring, or the lock, that leads a card's footer; nothing for a plain note. */
@Composable
private fun NoteGlyph(note: Note, look: NoteLook, size: Dp) {
    val kb = LocalKbTheme.current
    when {
        note.locked -> Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(size))
        look.progress != null -> MiniRing(look.progress.done.toFloat() / look.progress.total, look.colour, size = size, stroke = 2.5.dp)
    }
}

// ---- the timeline ----

/**
 * The timeline: a rail of days down the left, Today first, and one compact
 * row per note beside it, newest first whatever the pins say (a pinned
 * row wears a pin instead). Same chips, search, swipes and holds as the shelf.
 */
@Composable
private fun TimelineBody(
    state: KeyboardUiState,
    rows: List<Note>,
    focusedRow: Int?,
    now: Long,
    tagColours: Map<String, Int>,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val notes = state.notes
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
    val today = remember(now) { dayStart(now) }
    val days = remember(rows) { rows.map { dayStart(timelineStamp(it)) } }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 4.dp,
            end = 12.dp,
            bottom = if (notes.undo != null) UndoBarClearance else FabClearance,
        ),
    ) {
        items(count = rows.size, key = { rows[it].id }) { index ->
            val note = rows[index]
            val day = days[index]
            val label = if (index == 0 || days[index - 1] != day) dayLabel(day, today) else null
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                TimelineRail(
                    label = label,
                    today = day == today,
                    first = index == 0,
                    last = index == rows.lastIndex,
                    modifier = Modifier.width(RailWidth).fillMaxHeight(),
                )
                Spacer(Modifier.width(8.dp))
                TimelineRow(
                    state = state,
                    note = note,
                    now = now,
                    today = today,
                    tagColours = tagColours,
                    index = index,
                    focused = focusedRow == index,
                    held = notes.held == note.id,
                    callbacks = callbacks,
                    onOpenRoute = onOpenRoute,
                    modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                )
            }
        }
    }
}

/** The thin line down the rail, broken by a day's label where a day starts. */
@Composable
private fun TimelineRail(label: String?, today: Boolean, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val line = kb.divider
    Box(
        modifier.drawBehind {
            val x = size.width / 2f
            val top = if (first) RAIL_LABEL_TOP.toPx() else 0f
            val bottom = if (last) size.height * 0.6f else size.height
            if (bottom > top) drawLine(line, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx())
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (label != null) {
            Text(
                label.uppercase(),
                color = if (today) kb.accent else kb.secondaryText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(kb.board)
                    .padding(vertical = 2.dp, horizontal = 2.dp),
            )
        }
    }
}

/**
 * One note on the timeline: its ring, lock or text glyph in the tag's
 * colour, the title with a pin when pinned, one line of preview with the
 * checklist's count in front, its tag pills, and the time or weekday at
 * the trailing edge.
 */
@Composable
private fun TimelineRow(
    state: KeyboardUiState,
    note: Note,
    now: Long,
    today: Long,
    tagColours: Map<String, Int>,
    index: Int,
    focused: Boolean,
    held: Boolean,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val look = rememberNoteLook(state, note, now, tagColours, previewLines = 1)
    val stamp = timelineStamp(note)
    val whenText = timelineWhen(LocalContext.current, stamp, now, today)
    val preview = buildString {
        look.progress?.let { append(stringResource(R.string.ime_notes_tasks_done_of, it.done, it.total)) }
        if (look.preview.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(look.preview)
        }
    }
    NoteCard(
        state = state,
        note = note,
        look = look,
        index = index,
        focused = focused,
        held = held,
        tinted = false,
        callbacks = callbacks,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimelineGlyph(note, look)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        look.title,
                        color = kb.modifierKeyText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (note.pinned) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Outlined.PushPin, contentDescription = null, tint = kb.accent, modifier = Modifier.size(11.dp))
                    }
                }
                if (preview.isNotEmpty()) {
                    Text(preview, color = kb.toolbarIcon, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (note.tags.isNotEmpty()) {
                    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tag in note.tags.take(3)) TagPill(tag, noteTagColour(kb, tagColours[tag.lowercase()] ?: 0))
                        if (note.tags.size > 3) Text("+${note.tags.size - 3}", color = kb.secondaryText, fontSize = 9.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(whenText, color = kb.secondaryText, fontSize = 10.sp, maxLines = 1)
        }
        if (held) {
            NoteActionChips(state, note, callbacks, onOpenRoute, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A 26 dp ring for a checklist, else a soft square in the tag's colour holding a lock or a text glyph. */
@Composable
private fun TimelineGlyph(note: Note, look: NoteLook) {
    val kb = LocalKbTheme.current
    val progress = look.progress
    if (progress != null && !note.locked) {
        MiniRing(progress.done.toFloat() / progress.total, look.colour, size = TimelineGlyphSize, stroke = 3.dp)
        return
    }
    Box(
        Modifier
            .size(TimelineGlyphSize)
            .clip(RoundedCornerShape(8.dp))
            .background(if (note.locked || note.tags.isNotEmpty()) look.colour.copy(alpha = TINT_ALPHA) else kb.chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (note.locked) Icons.Outlined.Lock else Icons.AutoMirrored.Outlined.Subject,
            contentDescription = null,
            tint = if (note.locked || note.tags.isNotEmpty()) look.colour else kb.secondaryText,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** A tag's name on a small pill of its own colour. */
@Composable
private fun TagPill(name: String, colour: Color) {
    Text(
        name,
        color = colour,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colour.copy(alpha = TINT_ALPHA))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The timeline's order: by the stamp its rail labels, newest first, pins aside. */
private fun timelineOrder(items: List<Note>, sort: NoteSort): List<Note> =
    if (sort == NoteSort.TITLE) items else items.sortedByDescending { timelineStamp(it, sort) }

/** The moment a note sits at on the rail: when it went to the trash, or was made, or was last edited. */
private fun timelineStamp(note: Note, sort: NoteSort = NoteSort.UPDATED): Long = when {
    note.inTrash -> note.deletedAt ?: note.updatedAt
    sort == NoteSort.CREATED -> note.createdAt
    else -> note.updatedAt
}

/** Midnight at the start of [stamp]'s day, in the device's zone. */
private fun dayStart(stamp: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = stamp
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Today, Yesterday, or the date without its year. */
@Composable
private fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> stringResource(R.string.ime_notes_day_today)
    dayStart(today - DateUtils.DAY_IN_MILLIS / 2) -> stringResource(R.string.ime_notes_day_yesterday)
    else -> DateUtils.formatDateTime(LocalContext.current, day, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
}

/** The time for today's notes, the weekday inside a week, the date beyond. */
private fun timelineWhen(context: android.content.Context, stamp: Long, now: Long, today: Long): String = when {
    dayStart(stamp) == today -> DateUtils.formatDateTime(context, stamp, DateUtils.FORMAT_SHOW_TIME)
    stamp > now - DateUtils.DAY_IN_MILLIS * 6 ->
        DateUtils.formatDateTime(context, stamp, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    else -> DateUtils.formatDateTime(context, stamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
}

// ---- the ways to a new note ----

/** The floating "Jot something…" pill along the bottom of the shelf: a tap, or its + button, starts a new note. */
@Composable
private fun JotPill(state: KeyboardUiState, callbacks: NotesCallbacks, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val source = remember { MutableInteractionSource() }
    val newNote = {
        feedback()
        // Opened by the Quick note action, the editor keeps its row of recent
        // notes, so a new note goes through that row's own New.
        if (state.notes.quick) callbacks.onQuickPick(null) else callbacks.onNew(null)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(JotPillHeight)
            .pressScale(source, kb.motion.any)
            .clip(shape)
            // The board under the chip colour, so a translucent chip theme
            // still reads over the cards scrolling beneath.
            .background(kb.board)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null, onClick = newNote)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ime_notes_jot_hint),
            color = kb.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(kb.accent)
                .clickable(onClick = newNote),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.ime_notes_new_desc), tint = maxContrastOn(kb.accent), modifier = Modifier.size(18.dp))
        }
    }
}

/** The timeline's accent button, bottom right, for a new note. */
@Composable
private fun NewNoteFab(state: KeyboardUiState, callbacks: NotesCallbacks, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val description = stringResource(R.string.ime_notes_new_desc)
    IconHint(description, modifier) {
        Box(
            Modifier
                .size(FabSize)
                .pressScale(source, kb.motion.any)
                .clip(RoundedCornerShape(18.dp))
                .background(kb.accent)
                .clickable(interactionSource = source, indication = null) {
                    feedback()
                    if (state.notes.quick) callbacks.onQuickPick(null) else callbacks.onNew(null)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = description, tint = maxContrastOn(kb.accent), modifier = Modifier.size(24.dp))
        }
    }
}

// ---- what a note looks like in a list ----

/** The strings and colour a note's card draws, worked out once per note. */
private class NoteLook(
    val title: String,
    val preview: String,
    /** The relative time, with the checklist's count in front when there is one. */
    val footer: String,
    val relative: String,
    val progress: NoteChecklist.Progress?,
    /** The first tag's colour, or the accent for an untagged note. */
    val colour: Color,
    val description: String,
)

@Composable
private fun rememberNoteLook(state: KeyboardUiState, note: Note, now: Long, tagColours: Map<String, Int>, previewLines: Int): NoteLook {
    val kb = LocalKbTheme.current
    val untitled = stringResource(R.string.ime_notes_untitled)
    val lockedLabel = stringResource(R.string.ime_notes_locked_label)
    val pinnedLabel = stringResource(R.string.ime_notes_pin_action)
    val title = note.displayTitle().ifEmpty { untitled }
    val preview = when {
        note.locked -> lockedLabel
        previewLines <= 0 -> ""
        else -> remember(note.body, note.title, previewLines) { clipPreviewText(note.previewBody(), previewLines) }
    }
    val relative = if (note.inTrash) {
        stringResource(R.string.ime_notes_deleted_ago, DateUtils.getRelativeTimeSpanString(note.deletedAt ?: now, now, DateUtils.MINUTE_IN_MILLIS))
    } else {
        DateUtils.getRelativeTimeSpanString(note.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val progress = remember(note.body, note.locked) { if (note.locked) null else note.checklist().takeIf { !it.isEmpty } }
    val footer = if (progress != null) stringResource(R.string.ime_notes_tasks_done_of, progress.done, progress.total) + " · " + relative else relative
    val colour = noteTagColour(kb, note.tags.firstOrNull()?.let { tagColours[it.lowercase()] } ?: 0)
    val description = remember(title, preview, note.pinned, note.locked, note.tags, progress, relative) {
        buildList {
            add(title)
            if (preview.isNotEmpty()) add(preview)
            if (note.pinned) add(pinnedLabel)
            if (note.locked) add(lockedLabel)
            if (note.tags.isNotEmpty()) add(note.tags.joinToString(", "))
            progress?.let { add("${it.done}/${it.total}") }
            add(relative)
        }.joinToString(". ")
    }
    return NoteLook(title, preview, footer, relative, progress, colour, description)
}

/**
 * The chips a swipe reveals: pin, archive and delete for a note, restore for
 * one in the trash. A long swipe trashes outright, since the panel's Undo
 * bar brings the note back; in the trash nothing is a long swipe away.
 */
@Composable
private fun noteSwipeActions(note: Note, callbacks: NotesCallbacks): Pair<List<SwipeAction>, SwipeAction?> {
    val trash = kbDeleteSwipeAction(stringResource(CommonR.string.common_delete)) { callbacks.onTrash(note.id) }
    if (note.inTrash) {
        val restore = SwipeAction(Icons.Outlined.Restore, stringResource(R.string.ime_notes_restore_action), SwipeTone.ACCENT) {
            callbacks.onRestore(note.id)
        }
        return listOf(restore) to null
    }
    val pin = SwipeAction(
        Icons.Outlined.PushPin,
        stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action),
        SwipeTone.ACCENT,
    ) { callbacks.onPin(note.id, !note.pinned) }
    val archive = SwipeAction(
        Icons.Outlined.Inventory2,
        stringResource(if (note.archived) R.string.ime_notes_unarchive_action else R.string.ime_notes_archive_action),
    ) { callbacks.onArchive(note.id, !note.archived) }
    return listOf(pin, archive, trash) to trash
}

/**
 * What can be done with a note, as chips: inside a held card, and under a
 * locked or trashed note's read view. The trash offers Restore and Delete
 * forever; a locked note offers only what does not show its text.
 */
@Composable
internal fun NoteActionChips(
    state: KeyboardUiState,
    note: Note,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Register the chips as the panel's ACTIONS focus region; the read view does, a held row does not. */
    focusRegion: Boolean = false,
    includeEdit: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val confirm = state.notes.confirm
    val tooLong = note.body.length > NoteEdit.EDITOR_MAX_LENGTH
    val actions: List<Pair<String, () -> Unit>> = buildList {
        if (note.inTrash) {
            add(stringResource(R.string.ime_notes_restore_action) to { callbacks.onRestore(note.id) })
            return@buildList
        }
        if (!note.locked) {
            add(stringResource(R.string.ime_notes_insert_action) to { callbacks.onInsert(note.id) })
            add(stringResource(CommonR.string.common_copy) to { callbacks.onCopy(note.id) })
            if (includeEdit) {
                if (tooLong) {
                    add(stringResource(R.string.ime_notes_edit_in_settings_action) to { onOpenRoute(NOTES_ROUTE) })
                } else {
                    add(stringResource(R.string.ime_notes_edit_action) to { callbacks.onEditStart(note.id, null) })
                }
            }
            add(stringResource(CommonR.string.common_share) to { callbacks.onShare(note.id) })
        }
        add(
            stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action) to
                { callbacks.onPin(note.id, !note.pinned) },
        )
        add(
            stringResource(if (note.archived) R.string.ime_notes_unarchive_action else R.string.ime_notes_archive_action) to
                { callbacks.onArchive(note.id, !note.archived) },
        )
        if (!note.locked) add(stringResource(R.string.ime_notes_lock_action) to { callbacks.onLock(note.id) })
        add(stringResource(CommonR.string.common_delete) to { callbacks.onTrash(note.id) })
    }
    val total = actions.size + if (note.inTrash) 1 else 0
    if (focusRegion) {
        PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = total, columns = total) { index ->
            if (index < actions.size) actions[index].second() else callbacks.onDeleteForever(note.id)
        }
    }
    val focusedIndex = if (focusRegion) state.focusedIndex(FocusRegion.ACTIONS) else null
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, (label, action) ->
            ToolPanelChip(
                label,
                selected = index == 0 && !note.inTrash,
                modifier = Modifier.focusRing(focusedIndex == index, kb.chipShape()),
                onClick = action,
            )
        }
        if (note.inTrash) {
            ConfirmChip(
                label = stringResource(R.string.ime_notes_delete_forever_action),
                armed = (confirm as? NotesConfirm.DeleteForever)?.id == note.id,
                focused = focusedIndex == actions.size,
            ) { callbacks.onDeleteForever(note.id) }
        }
    }
}

// ---- the read view ----

/**
 * A note open to read: its card grown to the panel's width, tag pills and
 * "edited … ago" along the top, the title large, the body as Markdown with
 * its task boxes live, and a bar along the bottom with Insert first. Pin,
 * Lock and the rest are in the header, with a second row of chips behind
 * More. A locked or trashed note keeps its chip row, which offers only what
 * it may.
 */
@Composable
private fun NotesReadView(
    state: KeyboardUiState,
    note: Note,
    moreShown: Boolean,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val tagColours = remember(state.notes.tags) { state.notes.tags.associate { it.name.lowercase() to it.colour } }
    val now = System.currentTimeMillis()
    val look = rememberNoteLook(state, note, now, tagColours, previewLines = 0)
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.SEARCH, count = 0, columns = 1) {}
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.CHIPS, count = note.tags.size, columns = note.tags.size.coerceAtLeast(1)) { index ->
        note.tags.getOrNull(index)?.let { callbacks.onFilter(NotesFilter.Tag(it)); callbacks.onBack() }
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.RESULTS, count = 0, columns = 1) {}
    var showAll by rememberSaveable(note.id) { mutableStateOf(false) }
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val cardShape = RoundedCornerShape(topStart = ReadCardCorner, topEnd = ReadCardCorner)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(cardShape)
                .frostGlass(kb, cardShape, FrostSurface.CARDS)
                .background(kb.chip.copy(alpha = kb.chip.alpha * 0.55f))
                .noteTint(look.colour)
                .chipBorder(kb, cardShape)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    note.tags.forEachIndexed { index, tag ->
                        val colour = noteTagColour(kb, tagColours[tag.lowercase()] ?: 0)
                        Box(
                            Modifier
                                .focusRing(focusedChip == index, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    callbacks.onFilter(NotesFilter.Tag(tag))
                                    callbacks.onBack()
                                },
                        ) { TagPill(tag, colour) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (note.inTrash) look.relative else stringResource(R.string.ime_notes_edited_ago, look.relative),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.locked) {
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.ime_notes_locked_label), tint = kb.secondaryText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    look.title,
                    color = kb.modifierKeyText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp,
                    letterSpacing = (-0.3).sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            when {
                note.locked -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.toolbarIcon, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ime_notes_locked_info), color = kb.toolbarIcon, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    ToolPanelChip(stringResource(R.string.ime_notes_unlock_settings_action), selected = true) { onOpenRoute(NOTES_ROUTE) }
                }
                note.body.isBlank() -> Text(
                    stringResource(R.string.ime_notes_body_hint),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { if (!note.inTrash) callbacks.onEditStart(note.id, null) },
                )
                else -> {
                    val body = remember(note.body, showAll) { if (showAll) note.body else readPrefix(note.body) }
                    val editable = !note.inTrash && note.body.length <= NoteEdit.EDITOR_MAX_LENGTH
                    // A long press selects, with Copy, Insert and Ask AI on its bar, as in Wikipedia.
                    val title = note.displayTitle()
                    val askLabel = stringResource(R.string.ime_ask_ai_label_note, title.ifEmpty { stringResource(R.string.ime_notes_untitled) })
                    val ask = remember(title, askLabel) { AskAiSource(AskAiContext.noteSource(title), askLabel) }
                    if (state.settings.notes.renderMarkdown) {
                        ChatMarkdown(
                            body,
                            ChatMarkdownColors(text = kb.modifierKeyText, dim = kb.secondaryText, codeBackground = kb.chip, link = kb.accent),
                            fontSize = 13.sp,
                            onTaskToggle = if (note.inTrash) null else ({ task -> callbacks.onTaskToggle(note.id, task.line) }),
                            onBlockTap = if (editable) ({ block -> callbacks.onEditStart(note.id, block.line) }) else null,
                            selectable = true,
                            ask = ask,
                        )
                    } else {
                        SelectableText(
                            body,
                            color = kb.modifierKeyText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth(),
                            ask = ask,
                            onTap = if (editable) ({ callbacks.onEditStart(note.id, null) }) else null,
                        )
                    }
                    if (!showAll && body.length < note.body.length) {
                        Spacer(Modifier.height(6.dp))
                        ToolPanelChip(stringResource(R.string.ime_notes_show_more_action)) { showAll = true }
                    }
                }
            }
        }
        if (note.inTrash || note.locked) {
            NoteActionChips(
                state,
                note,
                callbacks,
                onOpenRoute,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                focusRegion = true,
            )
        } else {
            ReadActions(state, note, moreShown, callbacks, onOpenRoute)
        }
    }
}

/**
 * The bar along the bottom of an open note: Insert as the accent pill,
 * Edit, Copy and Share as round buttons, and above it, when More is open
 * or the hardware ring is on one of them, a row of the rest: Pin,
 * Archive, Lock, Delete. One focus region covers both rows in that order.
 */
@Composable
private fun ReadActions(state: KeyboardUiState, note: Note, moreShown: Boolean, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val tooLong = note.body.length > NoteEdit.EDITOR_MAX_LENGTH
    val edit: () -> Unit = if (tooLong) ({ onOpenRoute(NOTES_ROUTE) }) else ({ callbacks.onEditStart(note.id, null) })
    val more: List<Pair<String, () -> Unit>> = listOf(
        stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action) to { callbacks.onPin(note.id, !note.pinned) },
        stringResource(if (note.archived) R.string.ime_notes_unarchive_action else R.string.ime_notes_archive_action) to { callbacks.onArchive(note.id, !note.archived) },
        stringResource(R.string.ime_notes_lock_action) to { callbacks.onLock(note.id) },
        stringResource(CommonR.string.common_delete) to { callbacks.onTrash(note.id) },
    )
    val barActions: List<() -> Unit> = listOf(
        { callbacks.onInsert(note.id) },
        edit,
        { callbacks.onCopy(note.id) },
        { callbacks.onShare(note.id) },
    )
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = barActions.size + more.size, columns = barActions.size + more.size) { index ->
        if (index < barActions.size) barActions[index]() else more.getOrNull(index - barActions.size)?.second?.invoke()
    }
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val animate = kb.motion.any
    AnimatedVisibility(
        visible = moreShown || (focused != null && focused >= barActions.size),
        enter = if (animate) expandVertically(tween(PILL_MS)) + fadeIn(tween(PILL_MS)) else fadeIn(snap()),
        exit = if (animate) shrinkVertically(tween(PILL_MS)) + fadeOut(tween(PILL_MS)) else fadeOut(snap()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            more.forEachIndexed { index, (label, action) ->
                ToolPanelChip(
                    label,
                    selected = index == 0 && note.pinned,
                    modifier = Modifier.focusRing(focused == barActions.size + index, kb.chipShape()),
                    onClick = action,
                )
            }
        }
    }
    ActionBar(
        listOf(
            KitAction(stringResource(R.string.ime_notes_insert_action), focused = focused == 0, primary = true) { callbacks.onInsert(note.id) },
            KitAction(
                stringResource(if (tooLong) R.string.ime_notes_edit_in_settings_action else R.string.ime_notes_edit_action),
                if (tooLong) Icons.Outlined.OpenInNew else Icons.Outlined.Edit,
                focused = focused == 1,
                onClick = edit,
            ),
            KitAction(stringResource(CommonR.string.common_copy), Icons.Outlined.ContentCopy, focused = focused == 2) { callbacks.onCopy(note.id) },
            KitAction(stringResource(CommonR.string.common_share), Icons.Outlined.Share, focused = focused == 3) { callbacks.onShare(note.id) },
        ),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    )
}

/** The first [READ_INITIAL_CHARS] of a long body, cut at a paragraph break. */
private fun readPrefix(body: String): String {
    if (body.length <= READ_INITIAL_CHARS) return body
    val cut = body.lastIndexOf("\n\n", READ_INITIAL_CHARS).takeIf { it > READ_INITIAL_CHARS / 2 } ?: READ_INITIAL_CHARS
    return body.substring(0, cut)
}

// ---- shared pieces ----

/** The colour a tag's dot wears: the theme's accent for palette index 0, else the palette's own. */
internal fun noteTagColour(kb: KbTheme, index: Int): Color {
    val safe = NoteTagPalette.clamp(index)
    return if (safe == 0) kb.accent else Color(NoteTagPalette.ARGB[safe])
}

@Composable
internal fun TagDot(colour: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(colour))
}

/** A filter or tag chip: [VocabChip]'s shape with a colour dot in front when [colour] is given. */
@Composable
internal fun NoteTagChip(
    label: String,
    colour: Int?,
    filled: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape: Shape = kb.chipShape()
    Row(
        modifier = Modifier
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (filled) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { feedback(); it() } },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (colour != null) {
            TagDot(noteTagColour(kb, colour))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (filled) kb.chipActiveText else kb.chipText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun NotesUndoBar(
    undo: NotesUndo?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var last by remember { mutableStateOf(NotesUndo(NotesUndoKind.TRASHED, listOf(0L))) }
    if (undo != null) last = undo
    val count = last.ids.size
    PanelUndoBar(
        text = when (last.kind) {
            NotesUndoKind.TRASHED -> pluralStringResource(R.plurals.ime_notes_trashed, count, count)
            NotesUndoKind.ARCHIVED -> pluralStringResource(R.plurals.ime_notes_archived, count, count)
        },
        visible = undo != null,
        key = undo,
        reduceMotion = reduceMotion,
        onUndo = onUndo,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/** One text line's height in dp at the user's font scale, for the compact heights. */
@Composable
internal fun textLineHeightDp(): Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { 19.sp.toDp() }

/** Whether [tag] is one of [tags], ignoring case. */
internal fun List<NoteTag>.hasTag(tag: String): Boolean = any { it.name.equals(tag, ignoreCase = true) }

/** A shelf card's size: wide enough for a title and two lines, short enough for two to peek. */
private val ShelfCardWidth = 230.dp
private val ShelfCardHeight = 84.dp

/** The jot pill's height, and the room under the last cards so they scroll clear of it. */
private val JotPillHeight = 44.dp
private val JotClearance = 64.dp

/** The timeline's button, and the room under the last row for it. */
private val FabSize = 52.dp
private val FabClearance = 72.dp

/** The timeline's rail of days, and where its line starts under the first label. */
private val RailWidth = 44.dp
private val RAIL_LABEL_TOP = 20.dp
private val TimelineGlyphSize = 26.dp

/** The read view's card rounds its top only; its bottom runs under the action bar. */
private val ReadCardCorner = 20.dp

/** How far a card's tint and a tag pill's fill go: a wash, not a fill. */
private const val TINT_ALPHA = 0.16f
private val STRIPE = 4.dp

/** Past this many beats the stagger stops growing, so a long grid never waits on its tail. */
private const val POP_STAGGER_CAP = 7

/** The read view's growth out of the shelf: from this scale, and the fade either way. */
private const val FACE_SCALE = 0.92f
private const val FACE_IN_MS = 200
private const val FACE_IN_DELAY_MS = 40
private const val FACE_OUT_MS = 140
private const val PILL_MS = 180

/** Where Pin and Lock sit in the read view's ACTIONS region, after the bar's four. */
private const val READ_ACTION_PIN = 4
private const val READ_ACTION_LOCK = 6
