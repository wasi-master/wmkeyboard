package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntity
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.clipboard.matchesQuery
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The clipboard panel's components, each in the cell its panel layout gives it
 * (issue #63): the search pill, the strip of fragments lifted out of the clips,
 * and the history itself.
 */

/** Everything the clipboard components call back into the service with. */
@Immutable
internal class ClipboardFieldCallbacks(
    val onItem: (ClipItem) -> Unit,
    val onSticker: (ClipItem) -> Unit,
    val onPin: (ClipItem) -> Unit,
    val onDelete: (ClipItem) -> Unit,
    val onSearchToggle: () -> Unit,
    val onEntity: (ClipEntity) -> Unit,
)

/** The derived views of the history the three cells share. */
@Stable
internal class ClipboardPanelSession(
    val shownItems: List<ClipItem>,
    val entities: List<ClipEntity>,
    /** The search pill is only offered once there is history to filter and the feature is on. */
    val showSearch: Boolean,
    val query: String,
    val gridState: LazyStaggeredGridState,
)

@Composable
internal fun rememberClipboardPanelSession(state: KeyboardUiState): ClipboardPanelSession {
    val showSearch = state.settings.clipboard.search && state.clipboardItems.isNotEmpty()
    val query = state.clipboardQuery.trim()
    val shownItems = if (query.isEmpty()) {
        state.clipboardItems
    } else {
        state.clipboardItems.filter { it.matchesQuery(query) }
    }
    // Scanning every clip with three regexes is not free, so it happens once
    // per history change rather than on every recomposition.
    val phoneFormats = state.settings.clipboard.phoneFormats
    val phoneMasks = remember(phoneFormats) { PhoneFormats.parseAll(phoneFormats) }
    val allEntities = if (state.settings.clipboard.detectEntities) {
        remember(state.clipboardItems, phoneMasks) { ClipEntities.entitiesIn(state.clipboardItems, phoneMasks) }
    } else {
        emptyList()
    }
    // While searching the panel is only a couple of rows tall — the keys have
    // taken the rest — so the fragment strip stands down rather than eating one.
    val entities = if (state.clipboardSearchActive) {
        emptyList()
    } else {
        allEntities.filter { query.isEmpty() || it.value.contains(query, ignoreCase = true) }
    }
    val gridState = rememberLazyStaggeredGridState()
    return remember(shownItems, entities, showSearch, query, gridState) {
        ClipboardPanelSession(shownItems, entities, showSearch, query, gridState)
    }
}

/** The clipboard component for [kind]; a kind of another panel draws nothing. */
@Composable
internal fun ClipboardField(
    kind: PanelFieldKind,
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    when (kind) {
        PanelFieldKind.CLIPBOARD_SEARCH -> ClipboardSearchFieldCell(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_ENTITIES -> ClipboardEntitiesField(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_LIST -> ClipboardListField(state, session, callbacks)
        else -> Unit
    }
}

/** The search pill, or an empty cell while there is nothing to filter. */
@Composable
private fun ClipboardSearchFieldCell(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    // Published even when hidden, at zero, so a stale count left behind cannot
    // let Tab land on a pill nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = if (session.showSearch) 1 else 0,
        columns = 1,
        onActivate = { callbacks.onSearchToggle() },
    )
    if (!session.showSearch) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ClipboardSearchField(
            state = state,
            onToggle = callbacks.onSearchToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .focusRing(state.focusedIndex(FocusRegion.SEARCH) == 0, RoundedCornerShape(18.dp)),
        )
    }
}

/** The fragments strip — codes, numbers, links — or an empty cell when there are none. */
@Composable
private fun ClipboardEntitiesField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val entities = session.entities
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = entities.size,
        columns = entities.size.coerceAtLeast(1),
        onActivate = { index -> entities.getOrNull(index)?.let(callbacks.onEntity) },
    )
    if (entities.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        ClipEntityStrip(
            entities = entities,
            focused = state.focusedIndex(FocusRegion.CHIPS),
            onPaste = callbacks.onEntity,
        )
    }
}

/**
 * The history, two columns of cards packed independently, with the empty and
 * no-match placeholders where the cards would be.
 */
@Composable
private fun ClipboardListField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shownItems = session.shownItems
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        count = shownItems.size,
        columns = 2,
        onActivate = { index -> shownItems.getOrNull(index)?.let(callbacks.onItem) },
    )
    if (state.clipboardItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ime_clipboard_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (shownItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ime_clipboard_no_match, session.query),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val focused = state.focusedIndex()
    val gridState = session.gridState
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // Staggered, not a fixed grid: each column packs independently, so a tall
    // image sits next to two or three stacked text clips.
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
    ) {
        itemsIndexed(shownItems, key = { _, item -> item.id }) { index, item ->
            // Deleting fades the card out and slides the survivors up into the
            // gap; pinning re-sorts the list, so the card glides to the front.
            SwipeToDeleteCard(
                onDelete = { callbacks.onDelete(item) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(160),
                    placementSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                    fadeOutSpec = tween(140),
                ),
            ) {
                ClipCard(item, focused = index == focused, callbacks)
            }
        }
    }
}

/**
 * The panel while its search bar is capturing the keys: the pill and a couple
 * of rows of matches, the key rows returning underneath. The layout's own grid
 * stands down until the search is closed. Unchanged from before the panel
 * became a layout.
 */
@Composable
internal fun ClipboardSearchPanel(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
    modifier: Modifier = Modifier,
) {
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { callbacks.onSearchToggle() },
    )
    // No fragment strip while searching; publish its region empty so a stale
    // count cannot let Tab land on a chip nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    Column(modifier = modifier) {
        ClipboardSearchField(
            state = state,
            onToggle = callbacks.onSearchToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .focusRing(state.focusedIndex(FocusRegion.SEARCH) == 0, RoundedCornerShape(18.dp)),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ClipboardListField(state, session, callbacks)
        }
    }
}

/** One history card: body by kind, then the pin and delete circles. */
@Composable
private fun ClipCard(item: ClipItem, focused: Boolean, callbacks: ClipboardFieldCallbacks) {
    var showInfo by remember { mutableStateOf(false) }
    val kb = LocalKbTheme.current
    val cardShape = kb.cardShape()
    Column(
        modifier = Modifier
            .clip(cardShape)
            .background(kb.chip)
            .chipBorder(kb, cardShape)
            .focusRing(focused, cardShape)
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { callbacks.onItem(item) },
                    onLongPress = { showInfo = true },
                )
            }
            // An image card insets less: the picture is the content.
            .padding(if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) 5.dp else 10.dp),
    ) {
        if (showInfo) {
            ClipInfoPopup(
                item,
                onSendSticker = if (item.kind == ClipKind.IMAGE) {
                    { callbacks.onSticker(item); showInfo = false }
                } else null,
                onDismiss = { showInfo = false },
            )
        }
        when {
            // A masked secret outranks every other body: the point is that its
            // content is not on screen.
            item.sensitive && item.kind.isTextual -> ClipSensitiveBody(item)
            item.kind == ClipKind.IMAGE -> ClipThumbnail(item)
            item.kind == ClipKind.VIDEO -> ClipVideoBody(item)
            item.kind == ClipKind.FILE || item.kind == ClipKind.FOLDER -> ClipFileBody(item)
            item.kind == ClipKind.LINK -> ClipLinkBody(item)
            else -> Text(
                text = item.text,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.kind == ClipKind.HTML) {
                Text(
                    stringResource(R.string.ime_clip_type_rich_text),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            ClipActionCircle(
                icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                description = stringResource(if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin),
                tint = if (item.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { callbacks.onPin(item) }
            ClipActionCircle(
                icon = Icons.Outlined.Delete,
                description = stringResource(CommonR.string.common_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { callbacks.onDelete(item) }
        }
    }
}
