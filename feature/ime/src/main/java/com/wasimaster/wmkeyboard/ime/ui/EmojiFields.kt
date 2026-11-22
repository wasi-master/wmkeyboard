package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiUi
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiCallbacks
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.emoji.EmojiOrder
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    /** The ✕ that empties the search query (#907). */
    val onSearchClear: () -> Unit = {},
    /** The kaomoji tab's controller. */
    val kaomoji: KaomojiCallbacks = KaomojiCallbacks(),
)

/**
 * What the tabs and the grid share for one opening of the panel: the tab
 * list, the pager both drive, and the derived catalog views. Created once
 * above both cells, so the page survives entering and leaving search, and a
 * layout without a tab strip still pages by swipe.
 *
 * With the continuous list on (#540) the pager stands idle: [sections] is the
 * whole panel as one list, [listState] scrolls it, and the tabs follow that.
 */
@Stable
internal class EmojiPanelSession(
    val tabs: List<String>,
    val pagerState: PagerState,
    val listState: LazyGridState,
    /** The continuous list, or null while the panel pages. */
    val sections: EmojiSections?,
    /** Gender/role variants (🏃‍♀️, 👨‍⚕️…) collapsed under their base emoji. */
    val variantChildren: Map<String, List<String>>,
    val history: List<String>,
    val historyMode: EmojiTabMode,
    /** The user-sized grid cell; never smaller than the glyph plus its padding. */
    val gridCell: Dp,
    /** The grids' columns: a fixed count when one is set, else as many as [gridCell] fits. */
    val columns: GridCells,
    private val reorderOpenState: MutableState<Boolean>,
) {
    /** The favourites reorder sheet, reached from any favourited emoji's popup. */
    var reorderOpen: Boolean
        get() = reorderOpenState.value
        set(value) { reorderOpenState.value = value }

    /** The tab in front: the pager's page, or the section at the top of the list. */
    val selectedIndex: Int
        get() = sections?.tabAt(listState.firstVisibleItemIndex) ?: pagerState.currentPage

    /**
     * Where the tab bar sits, between tabs while a swipe or a scroll is under
     * way. Reads scroll state, so read it in the draw phase only.
     */
    fun barPosition(): Float = sections?.position(listState.firstVisibleItemIndex)
        ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction)

    /** Brings tab [index] to the front: its page, or its heading at the top of the list. */
    suspend fun goTo(index: Int) {
        val list = sections
        if (list == null) pagerState.scrollToPage(index) else listState.scrollToItem(list.starts.getOrElse(index) { 0 })
    }
}

@Composable
internal fun rememberEmojiPanelSession(state: KeyboardUiState, kaomoji: KaomojiUi): EmojiPanelSession {
    val variantChildren = remember(state.emojiCatalog) {
        state.emojiCatalog
            .mapNotNull { entry -> entry.parent?.let { parent -> parent to entry.emoji } }
            .groupBy({ it.first }, { it.second })
    }
    val historyMode = state.settings.emojiTabMode
    val history = (if (historyMode == EmojiTabMode.MOST_USED) state.emojiFrequents else state.emojiRecents)
        .let { if (state.hiddenEmoji.isEmpty()) it else it.filterNot { e -> e in state.hiddenEmoji } }
    val categoryOrder = state.settings.emoji.categoryOrder
    val hiddenCategories = state.settings.emoji.hiddenCategories
    val emojiOrder = state.settings.emoji.categoryEmojiOrder
    val categories = remember(state.emojiCatalog, categoryOrder, hiddenCategories, emojiOrder) {
        EmojiOrder.categories(state.emojiCatalog, categoryOrder, hiddenCategories, emojiOrder)
    }
    // Off still records history (the emoji row and suggestions read it); it only hides the tab.
    val hasHistory = historyMode != EmojiTabMode.OFF && history.isNotEmpty()
    // Kaomoji and emoticons sit after the Unicode categories: opt-in extras,
    // and appending them leaves every existing tab where muscle memory expects it.
    // One tab for the kaomoji library, or two with the emoticons split off.
    val textArtTabs = state.settings.emoji.kaomojiTabs
    val kaomojiSettings = state.settings.emoji.kaomoji
    val splitEmoticons = kaomojiSettings.splitEmoticons
    val tabs = remember(categories, hasHistory, textArtTabs, splitEmoticons) {
        buildList {
            if (hasHistory) add(RECENT_TAB)
            addAll(categories)
            if (textArtTabs) {
                add(KAOMOJI_TAB)
                if (splitEmoticons) add(EMOTICON_TAB)
            }
        }
    }
    // One pager for the panel's life; the page count follows the tab list
    // through an updated state so history appearing does not rebuild it.
    val tabCount = rememberUpdatedState(tabs.size)
    val pagerState = rememberPagerState(pageCount = { tabCount.value })
    val listState = rememberLazyGridState()
    // Built only with the setting on, and only when what it lists changes:
    // every emoji tap emits fresh state, and this must not run per tap.
    val continuous = state.settings.emoji.continuousScroll
    val sections = if (continuous) {
        remember(tabs, history, state.emojiCatalog, emojiOrder, state.hiddenEmoji, kaomoji.library, kaomoji.views, kaomojiSettings) {
            EmojiSections.build(tabs, history, state.emojiCatalog, emojiOrder, state.hiddenEmoji) { tab ->
                kaomojiListItems(tab, kaomoji, kaomojiSettings)
            }
        }
    } else {
        null
    }
    val gridCell = with(state.settings.emoji) { maxOf(gridCellSize, gridEmojiSize + 12) }.dp
    val gridColumns = state.settings.emoji.gridColumns
    val columns = remember(gridColumns, gridCell) {
        if (gridColumns > 0) GridCells.Fixed(gridColumns) else GridCells.Adaptive(minSize = gridCell)
    }
    // The reorder flag lives on the session object, which a new history list
    // replaces; it is held outside so the sheet survives a favourite landing.
    val reorderOpen = remember { mutableStateOf(false) }
    return remember(tabs, pagerState, listState, sections, variantChildren, history, historyMode, gridCell, columns) {
        EmojiPanelSession(
            tabs, pagerState, listState, sections, variantChildren, history, historyMode, gridCell, columns, reorderOpen,
        )
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
 * The category strip: every tab shares the cell's width evenly, or, when that
 * would make a tab narrower than the user's narrowest tab
 * ([com.wasimaster.wmkeyboard.core.settings.EmojiSettings.tabMinWidth], 40 dp
 * by default, a 20 dp icon with room either side), every tab takes that much
 * and the strip scrolls sideways under the finger (#539), keeping the tab in
 * front in view. Tab (the key) reaches it through the CHIPS region; activating
 * a chip moves the pager or the list, which is the selection, so there is no
 * state to hoist.
 */
@Composable
private fun EmojiTabsField(state: KeyboardUiState, session: EmojiPanelSession) {
    val tabs = session.tabs
    val scope = rememberCoroutineScope()
    val reduceMotion = state.settings.reduceMotion
    // A tapped tab moves the page in one step and slides only the bar.
    // Scrolling the pager there instead dragged every category in between
    // through composition: the pager snaps to three pages short of a far tab
    // and animates the rest, and each page it crosses is a whole emoji grid
    // being laid out and shaped mid-slide, which is what made the bar stutter
    // (and jump, at the snap). Now only the tab landed on is composed. The
    // continuous list jumps for the same reason.
    val tabSlide = remember { TabSlide() }
    val goToTab: (Int) -> Unit = { index ->
        scope.launch {
            // From wherever the bar is drawn now, a slide still running included.
            val from = tabSlide.active?.value ?: session.barPosition()
            val slide = tabSlide.start(from)
            launch { session.goTo(index) }
            if (!reduceMotion) {
                slide.animateTo(index.toFloat(), tween(EmojiTabSlideMs, easing = FastOutSlowInEasing))
            }
            tabSlide.finish(slide)
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
    // Derived, so a scroll recomposes the strip when the tab in front changes
    // and not on every row of emoji that goes by.
    val selected by remember(session) { derivedStateOf { session.selectedIndex } }
    val mostUsed = session.historyMode == EmojiTabMode.MOST_USED
    val indicatorColor = MaterialTheme.colorScheme.onSurface
    val row: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            // One bar for the row, in place of one per tab. Pages and tabs are
            // the same list, so outside a tap's own slide the pager's position
            // is the bar's position, and a swipe drags it under the finger.
            modifier = modifier.emojiTabIndicator(tabs.size, indicatorColor) {
                tabSlide.active?.value ?: session.barPosition()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                EmojiTab(
                    slot = emojiTabSlot(tab, mostUsed),
                    description = emojiTabTitle(tab, mostUsed),
                    label = textArtTabLabel(tab),
                    selected = index == selected,
                    focused = index == focusedTab,
                    onClick = { goToTab(index) },
                    bar = false,
                )
            }
        }
    }
    // The user's own narrowest tab (#970), so a strip that scrolled at the
    // default fits on a 1080p screen at a few dp less.
    val minWidth = state.settings.emoji.tabMinWidth.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        if (maxWidth / tabs.size >= minWidth) {
            row(Modifier.fillMaxSize())
        } else {
            val scroll = rememberScrollState()
            val tabPx = with(LocalDensity.current) { minWidth.toPx() }
            val viewport = with(LocalDensity.current) { maxWidth.toPx() }
            // The tab in front stays in view, centred where the strip allows,
            // as a swipe or the list's scroll moves it on.
            LaunchedEffect(selected, viewport, scroll.maxValue) {
                val target = (selected * tabPx + tabPx / 2f - viewport / 2f).roundToInt()
                scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
            }
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                row(
                    Modifier
                        .width(minWidth * tabs.size)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** How long the tab bar takes to slide to a tapped tab. */
private const val EmojiTabSlideMs = 220

/** How many most-used emoji head the history tab in [EmojiTabMode.BOTH]: about two rows. */
private const val BothMostUsedCount = 16

/** A section's name on the history tab, in the kaomoji heading's small muted type. */
@Composable
private fun HistoryHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

/**
 * A tapped tab's own bar slide, which the bar draws instead of the pager's
 * position while it runs. Snapshot state, read only in the draw phase, so the
 * hand-over either way repaints the row and never recomposes it.
 */
@Stable
private class TabSlide {
    var active by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
        private set

    /** A new slide starting at [from]; it replaces any still running as the one drawn. */
    fun start(from: Float): Animatable<Float, AnimationVector1D> = Animatable(from).also { active = it }

    /** Hands the bar back to the pager, unless a later tap has taken it over since. */
    fun finish(slide: Animatable<Float, AnimationVector1D>) {
        if (active === slide) active = null
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
                onClear = callbacks.onSearchClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRing(focused, LocalKbTheme.current.cardShape()),
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
    val kaomoji by callbacks.kaomoji.state.collectAsState()
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
            columns = session.columns,
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
            // Faces whose tag or text matches, after the emoji, when the
            // kaomoji tab is on and the user left them in search.
            val faces = if (state.settings.emoji.kaomojiTabs && state.settings.emoji.kaomoji.inSearch) {
                kaomoji.library.search(
                    state.emojiQuery, null, kaomojiStemMatcher(state.language.localeTag, state.emojiQuery),
                ).take(KaomojiSearchLimit)
            } else {
                emptyList()
            }
            if (faces.isNotEmpty()) {
                item(key = "§kaomoji", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.ime_emoji_tab_kaomoji),
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                items(
                    count = faces.size,
                    key = { "k\u0000${faces[it].id}" },
                    span = { GridItemSpan(kaomojiSpan(faces[it].kind, maxLineSpan, gridCell)) },
                ) { index -> KaomojiCell(faces[index], KaomojiUi(), callbacks.kaomoji.onAction, dragging = false) }
            }
        }
        return
    }

    val tabs = session.tabs
    if (tabs.isEmpty()) return
    session.sections?.let { sections ->
        Box(modifier = Modifier.fillMaxSize()) {
            ContinuousEmojiGrid(state, session, sections, callbacks, kaomoji)
            // The long list has no page to swap for these; they lie over it.
            if (kaomoji.tagsOpen) KaomojiTagManager(kaomoji, callbacks.kaomoji.onAction)
            KaomojiHeldBar(kaomoji, callbacks.kaomoji.onAction, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    val pagerState = session.pagerState
    val history = session.history
    // A pager, not a swapped-in single grid: horizontal swipes cross
    // categories. A tapped tab lands in one step instead (see EmojiTabsField).
    // Each page keeps its own scroll offset via the stable key below.
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
            KaomojiPage(state, tab, kaomoji, callbacks.kaomoji)
        } else if (tab == RECENT_TAB) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Inside the page so the pager height stays fixed across a swipe.
                if (state.settings.emojiClearRecentsButton && session.historyMode != EmojiTabMode.MOST_USED) {
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
                // Both views at once (#970): the most used first, a short run of
                // them under its own heading, then the recents under theirs.
                val both = session.historyMode == EmojiTabMode.BOTH
                val mostUsed = remember(both, state.emojiFrequents, state.hiddenEmoji) {
                    if (!both) {
                        emptyList()
                    } else {
                        state.emojiFrequents.filterNot { it in state.hiddenEmoji }.take(BothMostUsedCount)
                    }
                }
                // What the focus ring walks: the cells in the order they are drawn.
                val cells = if (both) mostUsed + history else history
                // Only the page in front owns the ring; two pages publishing
                // would race over one region.
                val focusedHistory = state.focusedIndex().takeIf { page == pagerState.currentPage }
                if (page == pagerState.currentPage) {
                    PanelFocusTarget(
                        panel = PanelMode.EMOJI,
                        count = cells.size,
                        columns = adaptiveColumns(historyGrid),
                        onActivate = { index -> cells.getOrNull(index)?.let(callbacks.onEmoji) },
                    )
                }
                ScrollFocusIntoView(focusedHistory) { historyGrid.animateScrollToItem(it) }
                LazyVerticalGrid(
                    state = historyGrid,
                    columns = session.columns,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    if (both && mostUsed.isNotEmpty()) {
                        item(key = "§most", span = { GridItemSpan(maxLineSpan) }) {
                            HistoryHeading(stringResource(R.string.ime_emoji_tab_most_used))
                        }
                        itemsIndexed(mostUsed, key = { _, emoji -> "m:$emoji" }) { index, emoji ->
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
                                focused = index == focusedHistory,
                            )
                        }
                        item(key = "§recent", span = { GridItemSpan(maxLineSpan) }) {
                            HistoryHeading(stringResource(R.string.ime_emoji_tab_recent))
                        }
                    }
                    val recentStart = if (both) mostUsed.size else 0
                    // Keyed by emoji: this list reorders under the grid, and on a
                    // positional key a cell's open popup stayed with the slot.
                    itemsIndexed(history, key = { _, emoji -> emoji }) { at, emoji ->
                        val index = recentStart + at
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
            val emojiOrder = state.settings.emoji.categoryEmojiOrder
            val emojis = remember(state.emojiCatalog, tab, state.hiddenEmoji, emojiOrder) {
                EmojiOrder.emoji(state.emojiCatalog, tab, emojiOrder, state.hiddenEmoji)
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
                columns = session.columns,
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
    // The suggestion strip for the query takes a row between this panel and
    // the keys (#161); it comes out of the panel, so opening search still does
    // not resize the window.
    val strip = captureStripHeight(state)
    val wanted = if (fullBleed) {
        EmojiSearchPanelHeight + fullBleedHiddenRows(state, macroRowAtPanelOpen(state)) - strip
    } else {
        EmojiSearchPanelHeight + topBarHeight(state.settings) + barCompensation - strip
    }
    // Fitted to the screen with the keys underneath (#333): sideways, the
    // panel and the keys together outgrew the window and squashed the keys.
    val height = toolPanelHeight(state, wanted, floor = FullBleedHeaderHeight)
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
                    onClear = callbacks.onSearchClear,
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
                onClear = callbacks.onSearchClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Most faces the emoji search lists after its emoji. */
private const val KaomojiSearchLimit = 24

/** Panel height while the emoji search is capturing the keys. */
private val EmojiSearchPanelHeight = 120.dp
