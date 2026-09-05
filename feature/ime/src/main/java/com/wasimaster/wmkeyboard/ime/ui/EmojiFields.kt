package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.launch

/**
 * The emoji panel's components, each drawn into the cell its panel layout gives
 * it (issue #63): the category tabs, the search pill and the grid. What used to
 * be one `EmojiPanel` composable, cut at the seams the layout can now move.
 */

/** Everything the emoji components call back into the service with. */
@Immutable
internal class EmojiFieldCallbacks(
    val onEmoji: (String) -> Unit,
    val onEmojiVariant: (String, String) -> Unit,
    val onEmojiFavourite: (String) -> Unit,
    val onQueryTap: () -> Unit,
    val onClearRecents: () -> Unit,
    val onRecentRemove: (String) -> Unit,
    val onFavouritesReorder: (List<String>) -> Unit,
    val onLongPress: (String) -> Unit,
    val onLongPressEnd: () -> Unit,
    val onAnimatedSend: (String) -> Unit,
    val onStickerSend: (String) -> Unit,
    val onSearchFieldDelete: () -> Unit,
    val onTextArt: (String) -> Unit,
)

/**
 * What the tabs and the grid share for one opening of the panel: the tab
 * list, the pager both drive, and the derived catalog views. Created once
 * above both cells, so the page survives entering and leaving search, and a
 * layout without a tab strip still pages by swipe.
 */
@Stable
internal class EmojiPanelSession(
    val tabs: List<String>,
    val pagerState: PagerState,
    /** Gender/role variants (🏃‍♀️, 👨‍⚕️…) collapsed under their base emoji. */
    val variantChildren: Map<String, List<String>>,
    val history: List<String>,
    val historyMode: EmojiTabMode,
    /** The user-sized grid cell; never smaller than the glyph plus its padding. */
    val gridCell: Dp,
    private val reorderOpenState: MutableState<Boolean>,
) {
    /** The favourites reorder sheet, reached from any favourited emoji's popup. */
    var reorderOpen: Boolean
        get() = reorderOpenState.value
        set(value) { reorderOpenState.value = value }

    val selectedTab: String
        get() = tabs.getOrElse(pagerState.currentPage) { tabs.firstOrNull().orEmpty() }
}

@Composable
internal fun rememberEmojiPanelSession(state: KeyboardUiState): EmojiPanelSession {
    val variantChildren = remember(state.emojiCatalog) {
        state.emojiCatalog
            .mapNotNull { entry -> entry.parent?.let { parent -> parent to entry.emoji } }
            .groupBy({ it.first }, { it.second })
    }
    val historyMode = state.settings.emojiTabMode
    val history = (if (historyMode == EmojiTabMode.MOST_USED) state.emojiFrequents else state.emojiRecents)
        .let { if (state.hiddenEmoji.isEmpty()) it else it.filterNot { e -> e in state.hiddenEmoji } }
    val categories = remember(state.emojiCatalog) { state.emojiCatalog.map { it.category }.distinct() }
    val hasHistory = history.isNotEmpty()
    // Kaomoji and emoticons sit after the Unicode categories: opt-in extras,
    // and appending them leaves every existing tab where muscle memory expects it.
    val textArtTabs = state.settings.emoji.kaomojiTabs
    val tabs = remember(categories, hasHistory, textArtTabs) {
        buildList {
            if (hasHistory) add(RECENT_TAB)
            addAll(categories)
            if (textArtTabs) {
                add(KAOMOJI_TAB)
                add(EMOTICON_TAB)
            }
        }
    }
    // One pager for the panel's life; the page count follows the tab list
    // through an updated state so history appearing does not rebuild it.
    val tabCount = rememberUpdatedState(tabs.size)
    val pagerState = rememberPagerState(pageCount = { tabCount.value })
    val gridCell = with(state.settings.emoji) { maxOf(gridCellSize, gridEmojiSize + 12) }.dp
    // The reorder flag lives on the session object, which a new history list
    // replaces; it is held outside so the sheet survives a favourite landing.
    val reorderOpen = remember { mutableStateOf(false) }
    return remember(tabs, pagerState, variantChildren, history, historyMode, gridCell) {
        EmojiPanelSession(tabs, pagerState, variantChildren, history, historyMode, gridCell, reorderOpen)
    }
}

/** The emoji component for [kind]; a kind of another panel draws nothing. */
@Composable
internal fun EmojiField(
    kind: PanelFieldKind,
    state: KeyboardUiState,
    session: EmojiPanelSession,
    callbacks: EmojiFieldCallbacks,
) {
    when (kind) {
        PanelFieldKind.EMOJI_TABS -> EmojiTabsField(state, session)
        PanelFieldKind.EMOJI_SEARCH -> EmojiSearchFieldCell(state, callbacks)
        PanelFieldKind.EMOJI_GRID -> EmojiGridField(state, session, callbacks)
        else -> Unit
    }
}

/**
 * The category strip: every tab shares the cell's width evenly. Tab (the key)
 * reaches it through the CHIPS region; activating a chip scrolls the pager,
 * which is the selection, so there is no state to hoist.
 */
@Composable
private fun EmojiTabsField(state: KeyboardUiState, session: EmojiPanelSession) {
    val tabs = session.tabs
    val scope = rememberCoroutineScope()
    val reduceMotion = state.settings.reduceMotion
    val goToTab: (Int) -> Unit = { index ->
        scope.launch {
            if (reduceMotion) session.pagerState.scrollToPage(index)
            else session.pagerState.animateScrollToPage(index)
        }
    }
    PanelFocusTarget(
        panel = PanelMode.EMOJI,
        region = FocusRegion.CHIPS,
        count = tabs.size,
        columns = tabs.size.coerceAtLeast(1),
        onActivate = goToTab,
    )
    if (tabs.isEmpty()) return
    val focusedTab = state.focusedIndex(FocusRegion.CHIPS)
    val selectedTab = session.selectedTab
    val mostUsed = session.historyMode == EmojiTabMode.MOST_USED
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            EmojiTab(
                slot = emojiTabSlot(tab, mostUsed),
                description = when (tab) {
                    KAOMOJI_TAB -> stringResource(R.string.ime_emoji_tab_kaomoji)
                    EMOTICON_TAB -> stringResource(R.string.ime_emoji_tab_emoticons)
                    RECENT_TAB -> stringResource(
                        if (mostUsed) R.string.ime_emoji_tab_most_used else R.string.ime_emoji_tab_recent,
                    )
                    // An emoji group name, which comes from the catalog data.
                    else -> tab.replaceFirstChar { it.uppercase() }
                },
                label = textArtTabLabel(tab),
                selected = tab == selectedTab,
                focused = index == focusedTab,
                onClick = { goToTab(index) },
            )
        }
    }
}

/**
 * The search pill's cell. Wide enough, it is the pill itself with the live
 * query; in a narrow cell — the shipped layout gives it one key's width beside
 * the tabs — it is the search icon alone, which is what the tab strip used to
 * carry. Either way a tap enters search mode.
 */
@Composable
private fun EmojiSearchFieldCell(state: KeyboardUiState, callbacks: EmojiFieldCallbacks) {
    PanelFocusTarget(
        panel = PanelMode.EMOJI,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { callbacks.onQueryTap() },
    )
    val focused = state.focusedIndex(FocusRegion.SEARCH) == 0
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (maxWidth < 96.dp) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRing(focused, RoundedCornerShape(8.dp))
                    .clickable(onClick = callbacks.onQueryTap),
                contentAlignment = Alignment.Center,
            ) {
                SlotIcon(
                    IconSlots.EMOJI_TAB_SEARCH,
                    contentDescription = stringResource(R.string.ime_emoji_tab_search_desc),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            EmojiSearchField(
                state = state,
                onEmojiQueryTap = callbacks.onQueryTap,
                onSearchFieldDelete = callbacks.onSearchFieldDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRing(focused, RoundedCornerShape(20.dp)),
            )
        }
    }
}

/**
 * The grid: search results while a query is typed, otherwise one category
 * per page behind the pager. Swiping across pages works with or without a
 * tab strip in the layout.
 */
@Composable
internal fun EmojiGridField(
    state: KeyboardUiState,
    session: EmojiPanelSession,
    callbacks: EmojiFieldCallbacks,
) {
    val onReorderFavourite: (() -> Unit)? =
        if (state.emojiFavourites.size >= 2) ({ session.reorderOpen = true }) else null
    val gridCell = session.gridCell
    val variantChildren = session.variantChildren
    if (state.emojiQuery.isNotEmpty()) {
        // Memoized, and distinct so it is safe to key by: every emoji tap emits
        // fresh state, and an inline map rebuilt the result grid per keystroke.
        val results = remember(state.emojiResults) { state.emojiResults.map { it.emoji }.distinct() }
        val resultsGrid = rememberLazyGridState()
        val focusedResult = state.focusedIndex()
        PanelFocusTarget(
            panel = PanelMode.EMOJI,
            count = results.size,
            columns = adaptiveColumns(resultsGrid),
            onActivate = { index ->
                results.getOrNull(index)?.let { callbacks.onEmoji(emojiDisplay(state, it)) }
            },
        )
        ScrollFocusIntoView(focusedResult) { resultsGrid.animateScrollToItem(it) }
        LazyVerticalGrid(
            state = resultsGrid,
            columns = GridCells.Adaptive(minSize = gridCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(results, key = { _, emoji -> emoji }) { index, emoji ->
                EmojiCell(
                    base = emoji,
                    // Search honours the global default skin tone (and the
                    // last-used variant when that override is on).
                    display = emojiDisplay(state, emoji),
                    state = state,
                    genderVariants = variantChildren[emoji].orEmpty(),
                    onTap = callbacks.onEmoji,
                    onPick = { variant -> callbacks.onEmojiVariant(emoji, variant) },
                    onFavourite = callbacks.onEmojiFavourite,
                    onReorderFavourites = onReorderFavourite,
                    onLongPress = callbacks.onLongPress,
                    onLongPressEnd = callbacks.onLongPressEnd,
                    onAnimatedSend = callbacks.onAnimatedSend,
                    onStickerSend = callbacks.onStickerSend,
                    focused = index == focusedResult,
                )
            }
        }
        return
    }

    val tabs = session.tabs
    if (tabs.isEmpty()) return
    val pagerState = session.pagerState
    val history = session.history
    // A pager, not a swapped-in single grid: horizontal swipes cross
    // categories and every tab switch slides across. Each page keeps its own
    // scroll offset via the stable key below.
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // The default already limits composition to the visible page plus
        // whatever a swipe drags into view, so a deep catalog never all
        // mounts at once.
        beyondViewportPageCount = 0,
        // Stable per-tab key: a page keeps its own scroll offset as history
        // appears and shifts the indices, and an open popup rides with its tab.
        key = { tabs[it] },
    ) { page ->
        val tab = tabs[page]
        if (tab == KAOMOJI_TAB || tab == EMOTICON_TAB) {
            TextArtGrid(kaomoji = tab == KAOMOJI_TAB, onTap = callbacks.onTextArt)
        } else if (tab == RECENT_TAB) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Inside the page so the pager height stays fixed across a swipe.
                if (state.settings.emojiClearRecentsButton && session.historyMode == EmojiTabMode.RECENTS) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f))
                        TextButton(onClick = callbacks.onClearRecents) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Box(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.ime_emoji_clear_recents), fontSize = 12.sp)
                        }
                    }
                }
                val historyGrid = rememberLazyGridState()
                // Only the page in front owns the ring; two pages publishing
                // would race over one region.
                val focusedHistory = state.focusedIndex().takeIf { page == pagerState.currentPage }
                if (page == pagerState.currentPage) {
                    PanelFocusTarget(
                        panel = PanelMode.EMOJI,
                        count = history.size,
                        columns = adaptiveColumns(historyGrid),
                        onActivate = { index -> history.getOrNull(index)?.let(callbacks.onEmoji) },
                    )
                }
                ScrollFocusIntoView(focusedHistory) { historyGrid.animateScrollToItem(it) }
                LazyVerticalGrid(
                    state = historyGrid,
                    columns = GridCells.Adaptive(minSize = gridCell),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    // Keyed by emoji: this list reorders under the grid, and on a
                    // positional key a cell's open popup stayed with the slot.
                    itemsIndexed(history, key = { _, emoji -> emoji }) { index, emoji ->
                        // History cells are exact sequences: no variant pref to
                        // remember, taps in the popup commit directly.
                        EmojiCell(
                            base = emoji,
                            display = emoji,
                            state = state,
                            genderVariants = emptyList(),
                            onTap = callbacks.onEmoji,
                            onPick = callbacks.onEmoji,
                            onFavourite = callbacks.onEmojiFavourite,
                            onReorderFavourites = onReorderFavourite,
                            onLongPress = callbacks.onLongPress,
                            onLongPressEnd = callbacks.onLongPressEnd,
                            onAnimatedSend = callbacks.onAnimatedSend,
                            onStickerSend = callbacks.onStickerSend,
                            onRemove = callbacks.onRecentRemove,
                            focused = index == focusedHistory,
                        )
                    }
                }
            }
        } else {
            val emojis = remember(state.emojiCatalog, tab, state.hiddenEmoji) {
                state.emojiCatalog
                    .filter { it.category == tab && it.parent == null && it.emoji !in state.hiddenEmoji }
                    .map { it.emoji }
            }
            val categoryGrid = rememberLazyGridState()
            val focusedEmoji = state.focusedIndex().takeIf { page == pagerState.currentPage }
            if (page == pagerState.currentPage) {
                PanelFocusTarget(
                    panel = PanelMode.EMOJI,
                    count = emojis.size,
                    columns = adaptiveColumns(categoryGrid),
                    onActivate = { index ->
                        emojis.getOrNull(index)?.let { callbacks.onEmoji(emojiDisplay(state, it)) }
                    },
                )
            }
            ScrollFocusIntoView(focusedEmoji) { categoryGrid.animateScrollToItem(it) }
            LazyVerticalGrid(
                state = categoryGrid,
                columns = GridCells.Adaptive(minSize = gridCell),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
            ) {
                // Keyed by emoji, not by slot: a cell owns the open state of
                // its long-press popup.
                itemsIndexed(emojis, key = { _, emoji -> emoji }) { index, emoji ->
                    EmojiCell(
                        base = emoji,
                        // The grid honours the global default skin tone too (and
                        // the last-used variant when that override is on).
                        display = emojiDisplay(state, emoji),
                        state = state,
                        genderVariants = variantChildren[emoji].orEmpty(),
                        onTap = callbacks.onEmoji,
                        onPick = { variant -> callbacks.onEmojiVariant(emoji, variant) },
                        onFavourite = callbacks.onEmojiFavourite,
                        onReorderFavourites = onReorderFavourite,
                        onLongPress = callbacks.onLongPress,
                        onLongPressEnd = callbacks.onLongPressEnd,
                        onAnimatedSend = callbacks.onAnimatedSend,
                        onStickerSend = callbacks.onStickerSend,
                        focused = index == focusedEmoji,
                    )
                }
            }
        }
    }
}

/**
 * The panel while a search is being typed: the key rows come back under it
 * (they are how the query gets typed), so it shrinks to the search pill plus
 * a couple of result rows, and the layout's own grid stands down until the
 * search is closed. Unchanged from before the panel became a layout.
 */
@Composable
internal fun EmojiSearchPanel(
    state: KeyboardUiState,
    session: EmojiPanelSession,
    callbacks: EmojiFieldCallbacks,
    onClose: () -> Unit,
) {
    val fullBleed = state.settings.emojiFullBleed
    // The always-on emoji row hides while this panel is open; absorbing its
    // height here keeps the keyboard from resizing on panel switches. Search
    // mode hides the toolbar row too (see KeyboardBody).
    val barCompensation =
        if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
    val height = if (fullBleed) {
        EmojiSearchPanelHeight + fullBleedHiddenRows(state)
    } else {
        EmojiSearchPanelHeight + topBarHeight(state.settings) + barCompensation
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (fullBleed) {
            // Full-bleed header, standing in for the toolbar it replaced: back
            // to the keys, then the search pill.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolCircle(
                    slot = IconSlots.CHROME_PANEL_BACK,
                    description = stringResource(R.string.ime_panel_back_desc),
                    active = false,
                    onClick = onClose,
                )
                EmojiSearchField(
                    state = state,
                    onEmojiQueryTap = callbacks.onQueryTap,
                    onSearchFieldDelete = callbacks.onSearchFieldDelete,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp, end = 2.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            EmojiGridField(state, session, callbacks)
        }
        // The search field sits under the grid, beside the keys typing into it.
        if (!fullBleed) {
            EmojiSearchField(
                state = state,
                onEmojiQueryTap = callbacks.onQueryTap,
                onSearchFieldDelete = callbacks.onSearchFieldDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Panel height while the emoji search is capturing the keys. */
private val EmojiSearchPanelHeight = 120.dp
