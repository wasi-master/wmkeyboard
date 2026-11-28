package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiOrder
import com.wasimaster.wmkeyboard.core.emoji.Kaomoji
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiAction
import com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiUi
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The emoji panel as one long list (#540): every tab's contents in a single
 * lazy grid, each behind a short heading, so a swipe runs from one category
 * into the next instead of stopping at a page edge.
 *
 * One grid rather than a column of grids: a lazy grid composes only the rows
 * on screen, whatever the length of the list, and every cell keeps the
 * content type it had on the pages, so scrolling the whole catalog recycles
 * the same few dozen cells. The flat list itself is plain data, built once
 * per change of the catalog or the history and never per frame.
 */

/** One entry of the continuous list. [key] is unique across the whole list. */
@Immutable
internal sealed class EmojiListItem(val key: String) {
    /** A tab's heading; [tab] is its index in the tab list. */
    class Header(val tabId: String, val tab: Int) : EmojiListItem("§h\u0000$tabId")

    /** A mood heading inside a text-art section. */
    class SubHeader(tabId: String, val name: String) : EmojiListItem("§s\u0000$tabId\u0000$name")

    /** An emoji cell. [history] cells are exact sequences, as on the history page. */
    class Emoji(tabId: String, val emoji: String, val history: Boolean) : EmojiListItem("e\u0000$tabId\u0000$emoji")

    /** A face from the kaomoji library, under [group]: the tag shown, or the heading it sits under. */
    class Art(tabId: String, group: String, val face: Kaomoji) :
        EmojiListItem("a\u0000$tabId\u0000$group\u0000${face.id}")

    /** A text-art section's tag strip, a row of its own at the head of the section. */
    class ArtStrip(val tabId: String) : EmojiListItem("§k\u0000$tabId")
}

/** The flat list and where each tab's heading sits in it. */
@Immutable
internal class EmojiSections(val items: List<EmojiListItem>, val starts: IntArray) {

    /** The tab whose section holds item [index]: the last heading at or before it. */
    fun tabAt(index: Int): Int {
        if (starts.isEmpty()) return 0
        var lo = 0
        var hi = starts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Where the tab bar sits for a list scrolled to item [index]: the tab, plus
     * how far through its section the top of the list has got, so the bar
     * glides between tabs as the list scrolls rather than jumping.
     */
    fun position(index: Int): Float {
        val tab = tabAt(index)
        val start = starts.getOrElse(tab) { 0 }
        val end = starts.getOrElse(tab + 1) { items.size }
        val length = (end - start).coerceAtLeast(1)
        return tab + ((index - start).toFloat() / length).coerceIn(0f, 1f)
    }

    companion object {
        fun build(
            tabs: List<String>,
            history: List<String>,
            catalog: List<EmojiEntry>,
            emojiOrder: Map<String, List<String>>,
            hidden: Set<String>,
            /** The Suggested tab's emoji (#1554). */
            suggested: List<String> = emptyList(),
            /** A text-art tab's rows; see [kaomojiListItems]. */
            textArt: (String) -> List<EmojiListItem> = { emptyList() },
        ): EmojiSections {
            val items = ArrayList<EmojiListItem>(catalog.size + tabs.size * 2)
            val starts = IntArray(tabs.size)
            // Once for every tab: a tab can hold emoji another category lent it.
            val layout = EmojiOrder.layout(catalog, emojiOrder, hidden)
            tabs.forEachIndexed { index, tab ->
                starts[index] = items.size
                items += EmojiListItem.Header(tab, index)
                when (tab) {
                    RECENT_TAB -> history.distinct().forEach { items += EmojiListItem.Emoji(tab, it, history = true) }
                    SUGGESTED_TAB -> suggested.distinct().forEach { items += EmojiListItem.Emoji(tab, it, history = false) }
                    KAOMOJI_TAB, EMOTICON_TAB -> items += textArt(tab)
                    else -> layout[tab].orEmpty()
                        .forEach { items += EmojiListItem.Emoji(tab, it, history = false) }
                }
            }
            return EmojiSections(items, starts)
        }
    }
}

/**
 * The continuous grid. Its scroll state lives on the session, so the tab
 * strip can jump it and follow it, and it survives a trip into search.
 */
@Composable
internal fun ContinuousEmojiGrid(
    state: KeyboardUiState,
    session: EmojiPanelSession,
    sections: EmojiSections,
    callbacks: EmojiFieldCallbacks,
    kaomoji: KaomojiUi,
) {
    val list = sections.items
    val kaomojiAct = callbacks.kaomoji.onAction
    val gridCell = session.gridCell
    val variantChildren = session.variantChildren
    val grid: LazyGridState = session.listState
    val onReorderFavourite: (() -> Unit)? =
        if (state.emojiFavourites.size >= 2) ({ session.reorderOpen = true }) else null
    val focused = state.focusedIndex()
    PanelFocusTarget(
        panel = PanelMode.EMOJI,
        count = list.size,
        columns = adaptiveColumns(grid),
        onActivate = { index ->
            when (val item = list.getOrNull(index)) {
                is EmojiListItem.Emoji ->
                    callbacks.onEmoji(if (item.history) item.emoji else emojiDisplay(state, item.emoji))
                is EmojiListItem.Art -> kaomojiAct(KaomojiAction.Insert(item.face.text))
                else -> Unit
            }
        },
    )
    ScrollFocusIntoView(focused) { grid.revealItem(it) }
    val clearRecents = state.settings.emojiClearRecentsButton && session.historyMode == EmojiTabMode.RECENTS
    LazyVerticalGrid(
        state = grid,
        columns = session.columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(
            count = list.size,
            key = { list[it].key },
            span = { index ->
                when (val item = list[index]) {
                    is EmojiListItem.Header, is EmojiListItem.SubHeader, is EmojiListItem.ArtStrip ->
                        GridItemSpan(maxLineSpan)
                    // The text-art chips keep the widths they have on their pages.
                    is EmojiListItem.Art -> GridItemSpan(kaomojiSpan(item.face.kind, maxLineSpan, gridCell))
                    is EmojiListItem.Emoji -> GridItemSpan(1)
                }
            },
            contentType = { list[it]::class },
        ) { index ->
            when (val item = list[index]) {
                is EmojiListItem.Header -> SectionHeader(
                    title = emojiTabTitle(item.tabId, session.historyMode == EmojiTabMode.MOST_USED),
                    onClear = if (item.tabId == RECENT_TAB && clearRecents) callbacks.onClearRecents else null,
                )
                is EmojiListItem.SubHeader -> Text(
                    text = item.name,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                is EmojiListItem.Art -> KaomojiCell(item.face, kaomoji.copy(editMode = false), kaomojiAct, dragging = false)
                // No rail in the long list: its buttons ride at the strip's ends.
                is EmojiListItem.ArtStrip -> KaomojiTagStrip(
                    tab = item.tabId,
                    ui = kaomoji,
                    kind = kaomojiKindFor(item.tabId, state.settings.emoji.kaomoji),
                    act = kaomojiAct,
                    leading = listOf(
                        KaomojiRailItem(
                            com.wasimaster.wmkeyboard.core.settings.KaomojiRailButton.SEARCH,
                            Icons.Outlined.Search,
                            stringResource(R.string.ime_kaomoji_search),
                            kaomoji.searching,
                        ) { kaomojiAct(KaomojiAction.ToggleSearch) },
                    ),
                    trailing = listOf(
                        KaomojiRailItem(
                            com.wasimaster.wmkeyboard.core.settings.KaomojiRailButton.ADD,
                            Icons.Outlined.Add,
                            stringResource(R.string.ime_kaomoji_add),
                            false,
                        ) { kaomojiAct(KaomojiAction.Add(item.tabId)) },
                        KaomojiRailItem(
                            com.wasimaster.wmkeyboard.core.settings.KaomojiRailButton.TAGS,
                            Icons.Outlined.Label,
                            stringResource(R.string.ime_kaomoji_tags),
                            kaomoji.tagsOpen,
                        ) { kaomojiAct(KaomojiAction.ToggleTags) },
                    ),
                )
                is EmojiListItem.Emoji -> if (item.history) {
                    EmojiCell(
                        base = item.emoji,
                        display = item.emoji,
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
                        focused = index == focused,
                    )
                } else {
                    val emoji = item.emoji
                    EmojiCell(
                        base = emoji,
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
                        focused = index == focused,
                    )
                }
            }
        }
    }
}

/** A section's heading: its name, and the clear button on the history's. */
@Composable
private fun SectionHeader(title: String, onClear: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SectionHeaderHeight)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClear != null) {
            TextButton(onClick = onClear) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Box(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ime_emoji_clear_recents), fontSize = 12.sp)
            }
        }
    }
}

/** A heading's height: a line of small text with room above it, short of a row of emoji. */
private val SectionHeaderHeight = 28.dp

/** A tab's spoken name, which the continuous list also prints as the section's heading. */
@Composable
internal fun emojiTabTitle(tab: String, mostUsed: Boolean): String = when (tab) {
    SUGGESTED_TAB -> stringResource(R.string.ime_emoji_tab_suggested)
    KAOMOJI_TAB -> stringResource(R.string.ime_emoji_tab_kaomoji)
    EMOTICON_TAB -> stringResource(R.string.ime_emoji_tab_emoticons)
    RECENT_TAB -> stringResource(
        if (mostUsed) R.string.ime_emoji_tab_most_used else R.string.ime_emoji_tab_recent,
    )
    // An emoji group name, which comes from the catalog data.
    else -> tab.replaceFirstChar { it.uppercase() }
}
