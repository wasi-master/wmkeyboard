package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.selection.ColourCodes
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RadioButtonUnchecked
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Reorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalAlignTop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sell
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.MenuBook
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntity
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Place
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.clipboard.clipEditable
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.clipboard.clipQueryMatcher
import com.wasimaster.wmkeyboard.core.transliteration.Zawgyi
import com.wasimaster.wmkeyboard.core.clipboard.expiresAt
import com.wasimaster.wmkeyboard.core.clipboard.orderKey
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.settings.ClipCardShortcut
import com.wasimaster.wmkeyboard.core.settings.ClipGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.ClipSwipePin
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.ClipboardSettings
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.ime.ClipCombineDraft
import com.wasimaster.wmkeyboard.ime.floating.rememberFloatingAppIcon
import com.wasimaster.wmkeyboard.ime.ClipCombineField
import com.wasimaster.wmkeyboard.ime.ClipEdit
import com.wasimaster.wmkeyboard.ime.ClipUndo
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clipOcrAvailable
import com.wasimaster.wmkeyboard.core.tools.PhotoTranslate
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * The clipboard panel's components, each in the cell its panel layout gives it
 * (issue #63): the search pill, the grid / list switch, the strip of fragments
 * lifted out of the clips, and the history itself — plus the clip editor,
 * which stands in for all of them while it is open.
 */

/**
 * The panel's own actions that reach the service through [ToolHoldCallbacks]
 * rather than as parameters of their own, because `KeyboardScreen`'s caller
 * sits against the JVM's 64K method-size ceiling.
 */
data class ClipboardPanelActions(
    /** Open this clip in the editor; the keys type into it until it closes. */
    val onEdit: (ClipItem) -> Unit = {},
    /** Save the editor's draft over its clip, and close it. */
    val onEditSave: () -> Unit = {},
    /** Close the editor, leaving the clip as it was. */
    val onEditCancel: () -> Unit = {},
    /** Flip the history between the two-column grid and the one-per-row list. */
    val onViewToggle: () -> Unit = {},
    /** The Undo bar's button: put back the clips just deleted (#327). */
    val onUndoDelete: () -> Unit = {},
    /** The Undo bar swiped away: the delete is final now, not at the timeout (#371). */
    val onUndoDismiss: () -> Unit = {},
    /** The clear button's question answered Delete: every unpinned clip goes (#371). */
    val onClearUnpinned: () -> Unit = {},
    /** A link clip's Open link: the address in the browser (#371). */
    val onOpenLink: (ClipItem) -> Unit = {},
    /** An image clip's View: the picture full screen (#371). */
    val onViewImage: (ClipItem) -> Unit = {},
    /** An image clip's Extract text: the OCR panel, reading the picture (#371). */
    val onExtractText: (ClipItem) -> Unit = {},
    /** An image clip's Translate: the camera's confirm step, translating the picture (#807). */
    val onTranslateImage: (ClipItem) -> Unit = {},
    /** The height bar let go: how many dp taller than the keyboard the panel opens (#414). */
    val onPanelHeight: (Int) -> Unit = {},
    /** The header's keyboard button: stack the panel over the live keys, or back (#414). */
    val onKeysToggle: () -> Unit = {},
    /** The selection bar's Combine: open the rule for these clips, in the order picked (#612). */
    val onCombine: (List<Long>) -> Unit = {},
    /** Which of Combine's boxes the keys type into. */
    val onCombineFocus: (ClipCombineField) -> Unit = {},
    /** One of Combine's ready-made joiners. */
    val onCombineJoiner: (String) -> Unit = {},
    /** Join the clips by the rule into a new clip. */
    val onCombineSave: () -> Unit = {},
    /** Leave Combine. */
    val onCombineCancel: () -> Unit = {},
    /** The selection bar's Delete: every picked clip, with one Undo (#612). */
    val onDeleteMany: (List<Long>) -> Unit = {},
    /** A clip dragged to this place in its group, in the Reorder view (#612). */
    val onMove: (ClipItem, Int) -> Unit = { _, _ -> },
    /** The `.*` button beside the search: regular expressions on or off (#612). */
    val onRegexToggle: () -> Unit = {},
    /** The fragments strip folded away (true) or brought back (#612). */
    val onEntityStripHidden: (Boolean) -> Unit = {},
    /** A clip dropped between two others in the Reorder view, newer one first (#612). */
    val onPlace: (ClipItem, Long?, Long?) -> Unit = { _, _, _ -> },
    /** Combine's switch that deletes the clips it joins (#612). */
    val onCombineDeleteToggle: () -> Unit = {},
    /** The selection bar's Tidy: the copy rules run over these clips (#612). */
    val onTidy: (List<Long>) -> Unit = {},
    /** The selection bar's Zawgyi: these clips' Burmese between Zawgyi and Unicode. */
    val onZawgyi: (List<Long>) -> Unit = {},
    /** The hold popup's Tags: the editor opens on this clip's tags (#612). */
    val onTags: (ClipItem) -> Unit = {},
    /** A tag already in the history, pressed under the tags editor (#612). */
    val onTagAdd: (String) -> Unit = {},
    /** A text clip's QR code: the QR tool, on the clip's text (#766). */
    val onQr: (ClipItem) -> Unit = {},
    /** A text clip searched for (#914): on Wikipedia when true, else the web search tool. */
    val onSearch: (ClipItem, Boolean) -> Unit = { _, _ -> },
)

/**
 * What a press and hold on a copy's chip on the suggestion strip does (#612):
 * deletes that copy. Provided by the service around the whole keyboard, so it
 * reaches the strip without one more parameter through `KeyboardScreen`.
 */
val LocalClipChipDelete = androidx.compose.runtime.staticCompositionLocalOf<((ClipItem) -> Unit)?> { null }

/**
 * Which clips the history shows (#612), on top of the search and the tabs.
 * Picked from the row under the search's filter button; [ALL] is the panel as
 * it always was.
 */
internal enum class ClipFilter {
    ALL, PINNED, UNPINNED, TEXT, LINKS, IMAGES, FILES,

    /**
     * Clips whose text is another's but for case and spacing (#612):
     * `GitHub` and `github`. Drawn side by side; see [duplicateGroups].
     */
    DUPLICATES,
    ;

    fun matches(item: ClipItem): Boolean = when (this) {
        ALL, DUPLICATES -> true
        PINNED -> item.pinned
        UNPINNED -> !item.pinned
        TEXT -> item.kind == ClipKind.TEXT || item.kind == ClipKind.HTML
        LINKS -> item.kind == ClipKind.LINK
        IMAGES -> item.kind == ClipKind.IMAGE
        FILES -> item.kind.isUriBacked
    }
}

/**
 * What two clips' texts are compared by in the duplicates filter (#612): the
 * text in lower case with its runs of spaces and line breaks made one space.
 */
internal fun duplicateKey(item: ClipItem): String? =
    item.text.takeIf { item.kind.isTextual && !item.sensitive && it.isNotBlank() }
        ?.trim()?.lowercase()?.replace(DuplicateSpace, " ")

private val DuplicateSpace = Regex("\\s+")

/**
 * [items] that share a [duplicateKey] with another, each group together in
 * the place its first clip had, newest group first.
 */
internal fun duplicateGroups(items: List<ClipItem>): List<ClipItem> {
    val groups = LinkedHashMap<String, MutableList<ClipItem>>()
    for (item in items) duplicateKey(item)?.let { groups.getOrPut(it) { ArrayList() } += item }
    return groups.values.filter { it.size > 1 }.flatten()
}

/**
 * The filter and order the panel was last left with (#612), so the next
 * search opens the way the last one was set up. For as long as the keyboard
 * runs; a filter is a moment's tool, not a setting.
 */
private object ClipPanelMemory {
    var filter: ClipFilter = ClipFilter.ALL
    var oldest: Boolean = false
    var tag: String? = null
}

/**
 * [now] with an empty slot kept for every clip [previous] showed that has
 * since left the history for good (#612): not in [now], and not among the
 * [live] clips either, which only a delete does (a clip a pin moved to the
 * other tab is still live). Each sits after the clip it followed.
 *
 * The history is a staggered grid, which remembers each clip's column by its
 * place in the list. Taking one clip out moved every clip after it up a
 * place, into a column remembered for another, and scrolling back up then
 * laid them out again in a new order. With the slot kept, no place changes;
 * the slots go when the panel closes or the view is changed on purpose.
 */
internal fun withDeletedSlots(previous: List<ClipItem>, now: List<ClipItem>, live: Set<Long>): List<ClipItem> {
    if (previous.isEmpty()) return now
    val nowIds = now.mapTo(HashSet()) { it.id }
    if (previous.none { it.id !in nowIds && it.id !in live }) return now
    val out = now.toMutableList()
    for ((index, item) in previous.withIndex()) {
        if (item.id in nowIds || item.id in live) continue
        var at = 0
        for (before in index - 1 downTo 0) {
            val found = out.indexOfFirst { it.id == previous[before].id }
            if (found >= 0) {
                at = found + 1
                break
            }
        }
        out.add(at, item)
    }
    return out
}

/** What [withDeletedSlots] compares against: the grid as last composed, for one view of the history. */
private class ClipGridMemory {
    var drawn: List<ClipItem> = emptyList()
}

/** Every tag the clips in [items] carry (#612), each once whatever its case, the most used first. */
internal fun clipTagsIn(items: List<ClipItem>): List<String> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    for (item in items) for (tag in item.tags) {
        val key = tag.lowercase()
        val seen = counts[key]
        counts[key] = (seen?.first ?: tag) to ((seen?.second ?: 0) + 1)
    }
    return counts.values.sortedByDescending { it.second }.map { it.first }
}

/** A folder of pinned clips (#768): a tag, and how many pinned clips carry it. */
@Immutable
internal data class ClipFolder(val name: String, val count: Int)

/**
 * The folders the pinned clips in [items] are filed in (#768): one per tag,
 * each once whatever its case, in alphabetical order so a folder keeps its
 * place as clips come and go.
 */
internal fun pinnedClipFolders(items: List<ClipItem>): List<ClipFolder> {
    val counts = LinkedHashMap<String, ClipFolder>()
    for (item in items) {
        if (!item.pinned) continue
        for (tag in item.tags.distinctBy { it.lowercase() }) {
            val key = tag.lowercase()
            val seen = counts[key]
            counts[key] = ClipFolder(seen?.name ?: tag, (seen?.count ?: 0) + 1)
        }
    }
    return counts.values.sortedBy { it.name.lowercase() }
}

/** [items] without the pinned clips that are filed in a folder (#768). */
internal fun withoutFiledPins(items: List<ClipItem>): List<ClipItem> =
    items.filterNot { it.pinned && it.tags.isNotEmpty() }

/**
 * [items] oldest first instead of newest, inside each run of pinned or
 * unpinned clips, so the pinned ones stay where the settings put them.
 */
internal fun oldestFirst(items: List<ClipItem>): List<ClipItem> {
    val out = ArrayList<ClipItem>(items.size)
    var start = 0
    while (start < items.size) {
        var end = start
        while (end < items.size && items[end].pinned == items[start].pinned) end++
        items.subList(start, end).sortedBy { it.timestamp }.let(out::addAll)
        start = end
    }
    return out
}

/**
 * Each clip's number by id when the history is split into tabs (#612): each
 * tab counts its own clips from 1, so the first pinned clip is 1 and so is the
 * first unpinned one.
 */
internal fun clipNumbersPerTab(items: List<ClipItem>): Map<Long, Int> =
    clipNumbers(items.filter { it.pinned }) + clipNumbers(items.filter { !it.pinned })

/** The history's two tabs, when [ClipboardSettings.pinnedTabs] is on (#371). */
internal enum class ClipTab { UNPINNED, PINNED }

/** Everything the clipboard components call back into the service with. */
@Immutable
internal class ClipboardFieldCallbacks(
    val onItem: (ClipItem) -> Unit,
    val onSticker: (ClipItem) -> Unit,
    val onPin: (ClipItem) -> Unit,
    val onDelete: (ClipItem) -> Unit,
    val onSearchToggle: () -> Unit,
    val onEntity: (ClipEntity) -> Unit,
    val actions: ClipboardPanelActions,
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
    /** One clip per row rather than two columns of cards. */
    val list: Boolean,
    /**
     * Each clip's number, by id, when numbering is on — its place in the whole
     * history, not in [shownItems], so a search never renumbers what it finds.
     * Empty when numbering is off.
     */
    val numbers: Map<Long, Int>,
    /**
     * The unpinned / pinned tabs are drawn and [shownItems] is one of them
     * (#371): the setting is on, there is history, and no search is running,
     * since a search looks through both.
     */
    val tabbed: Boolean,
    /** The open tab. Kept while the panel is, so a pin does not throw the user back. */
    val tab: MutableState<ClipTab>,
    val pinnedCount: Int,
    val unpinnedCount: Int,
    /** The bin beside the search pill (#371): the setting is on and something is unpinned. */
    val showClear: Boolean,
    /**
     * The bin's question is up. Its own state rather than a field of this
     * session, which is rebuilt whenever the history moves: a clip copied
     * meanwhile must not take the question away.
     */
    val clearAsking: MutableState<Boolean>,
    /** Image clips offer Extract text: the OCR tool is on in a build that has it. */
    val ocr: Boolean,
    /**
     * Each clip's own fragments, by id (#472), for the Extract action and,
     * with [entityIcons], the icons on the cards. Empty with detection off.
     */
    val perClip: Map<Long, List<ClipEntity>> = emptyMap(),
    /** The cards count their fragments in icons, and the chip strip stands down. */
    val entityIcons: Boolean = false,
    /** A pinned clip's delete asks first (#636). */
    val confirmPinned: Boolean = false,
    /**
     * The pinned clip whose delete is being asked about, or null. Kept apart
     * from the session for the reason [clearAsking] is.
     */
    val deleteAsking: MutableState<ClipItem?> = mutableStateOf(null),
    /** Which clips the history shows (#612). Kept for the panel's visit, like [tab]. */
    val filter: MutableState<ClipFilter> = mutableStateOf(ClipFilter.ALL),
    /** The history oldest first (#612). */
    val oldest: MutableState<Boolean> = mutableStateOf(false),
    /** The filter row is open under the search. */
    val filtersOpen: MutableState<Boolean> = mutableStateOf(false),
    /**
     * The clips picked for Combine or Delete, in the order picked (#612), or
     * null while the panel is not picking: a tap pastes as always.
     */
    val selection: MutableState<List<Long>?> = mutableStateOf(null),
    /** The history is the Reorder view, a list to drag clips around in (#612). */
    val reordering: MutableState<Boolean> = mutableStateOf(false),
    /**
     * The clip just pinned or unpinned while the tabs are on (#612), which
     * sent it to the other tab: its notice is up, with an Undo.
     */
    val pinNotice: MutableState<ClipItem?> = mutableStateOf(null),
    /** The fragments strip is folded away and has something to show: its button is beside the search. */
    val showFragments: Boolean = false,
    /** How many fragments the folded strip holds, for its button. */
    val hiddenFragments: Int = 0,
    /** The `.*` button beside the search, lit while the search reads patterns (#612). */
    val regex: Boolean = false,
    /**
     * The search page's own grid (#612). It shared [gridState] with the
     * history, and the two pages cross over for a moment: two grids laying
     * out from one state left a hole beside the first results and threw the
     * clips sideways.
     */
    val searchGridState: LazyStaggeredGridState = gridState,
    /** A clip the history should scroll to once the search closes (#612), or null. */
    val jumpTo: MutableState<Long?> = mutableStateOf(null),
    /** The selection bar offers Tidy: there are copy rules, or plain letters is on (#612). */
    val tidy: Boolean = false,
    /** Pinned clips stay among the others (#612): nothing groups them, and Reorder moves across. */
    val pinsInPlace: Boolean = false,
    /** Every tag in the history, for the filter row's chips (#612). */
    val tags: List<String> = emptyList(),
    /** The tag the history is cut down to, or null for none (#612). Kept like [filter]. */
    val tag: MutableState<String?> = mutableStateOf(null),
    /**
     * What the grid draws: [shownItems] with an empty slot where each clip
     * deleted during this visit was (#612); see [withDeletedSlots].
     */
    val gridItems: List<ClipItem> = shownItems,
    /** The ids in [gridItems] that are empty slots. */
    val emptySlots: Set<Long> = emptySet(),
    /**
     * The folders over the pinned clips (#768), whose clips [shownItems] leaves
     * out. Empty with the setting off, inside a folder, or with none to show.
     */
    val folders: List<ClipFolder> = emptyList(),
    /** The pinned clips are a folder's (#768): its name and a way back are over them. */
    val inFolder: Boolean = false,
) {
    /** The question is on screen: asked, and there is still something to delete. */
    val confirmingClear: Boolean get() = showClear && clearAsking.value

    /** Something in the filter row is set, so the row stays up and the button stays lit. */
    val filtered: Boolean get() = filter.value != ClipFilter.ALL || oldest.value || tag.value != null
}

@Composable
internal fun rememberClipboardPanelSession(state: KeyboardUiState): ClipboardPanelSession {
    val showSearch = state.settings.clipboard.search && state.clipboardItems.isNotEmpty()
    val query = state.clipboardQuery.trim()
    val tab = remember { mutableStateOf(ClipTab.UNPINNED) }
    val pinsInPlace = state.settings.clipboard.pinnedInPlace
    val tabbed = state.settings.clipboard.pinnedTabs && !state.clipboardSearchActive &&
        !pinsInPlace && state.clipboardItems.isNotEmpty()
    val pinnedCount = state.clipboardItems.count { it.pinned }
    val unpinnedCount = state.clipboardItems.size - pinnedCount
    val inTab = if (tabbed) {
        val pinned = tab.value == ClipTab.PINNED
        state.clipboardItems.filter { it.pinned == pinned }
    } else {
        state.clipboardItems
    }
    val regexSearch = state.settings.clipboard.searchRegex
    val matcher = remember(query, regexSearch) { clipQueryMatcher(query, regexSearch) }
    val filter = remember { mutableStateOf(ClipPanelMemory.filter) }
    val oldest = remember { mutableStateOf(ClipPanelMemory.oldest) }
    val allTags = remember(state.clipboardItems) { clipTagsIn(state.clipboardItems) }
    val tag = remember { mutableStateOf(ClipPanelMemory.tag) }
    // A tag no clip carries any more filters nothing in.
    val tagShown = tag.value?.takeIf { wanted -> allTags.any { it.equals(wanted, ignoreCase = true) } }
    androidx.compose.runtime.SideEffect {
        ClipPanelMemory.filter = filter.value
        ClipPanelMemory.oldest = oldest.value
        ClipPanelMemory.tag = tag.value
    }
    val jumpTo = remember { mutableStateOf<Long?>(null) }
    val filtersOpen = remember { mutableStateOf(false) }
    val selection = remember { mutableStateOf<List<Long>?>(null) }
    val reordering = remember { mutableStateOf(false) }
    val pinNotice = remember { mutableStateOf<ClipItem?>(null) }
    val kindFilter = filter.value
    // Pinned folders (#768): where the pinned clips are drawn, outside a
    // search, each tag they carry is a folder, and its clips are in there
    // rather than among the rest. A folder is opened by the tag filter. With
    // the pinned clips kept in place (no tabs, only filters) the folders head
    // the whole history instead, and the clips filed in them leave its flow.
    val pinnedPage = !tabbed || tab.value == ClipTab.PINNED
    val foldersOn = state.settings.clipboard.pinnedFolders && !state.clipboardSearchActive && pinnedPage
    val folders = if (foldersOn && tagShown == null) {
        remember(state.clipboardItems) { pinnedClipFolders(state.clipboardItems) }
    } else {
        emptyList()
    }
    val inFolder = foldersOn && tagShown != null
    val tagged = when {
        tagShown != null -> inTab.filter { item -> item.tags.any { it.equals(tagShown, ignoreCase = true) } }
        folders.isNotEmpty() -> withoutFiledPins(inTab)
        else -> inTab
    }
    val filtered = when (kindFilter) {
        ClipFilter.ALL -> tagged
        ClipFilter.DUPLICATES -> remember(tagged) { duplicateGroups(tagged) }
        else -> tagged.filter(kindFilter::matches)
    }
    // A search lists what it finds newest first, pinned or not (#612): with
    // the pinned clips lifted to the top, the first result of an empty
    // search was the oldest pinned clip rather than the last copy.
    val searching = state.clipboardSearchActive
    val ordered = if (searching && !pinsInPlace && kindFilter != ClipFilter.DUPLICATES) {
        remember(filtered) { filtered.sortedByDescending { it.orderKey } }
    } else {
        filtered
    }
    // Then the clips only search by meaning found, best first; only from
    // [ordered], so the tab, tag and kind filters still hold.
    val meaningIds = state.meaning.clips.forQuery(query)
    val matched = when {
        query.isEmpty() -> ordered
        meaningIds.isEmpty() -> ordered.filter(matcher)
        else -> com.wasimaster.wmkeyboard.ime.semantic.MeaningHits.append(ordered.filter(matcher), ordered, meaningIds) { it.id.toString() }
    }
    val shownItems = if (oldest.value) {
        remember(matched, searching) { if (searching) matched.reversed() else oldestFirst(matched) }
    } else {
        matched
    }
    // A slot kept for each clip deleted in this view of the history (#612).
    // A new view, by a filter, a tag, a tab, the search or the order, starts
    // from the clips as they are.
    val gridMemory = remember(kindFilter, tagShown, tab.value, query, oldest.value, searching, tabbed, folders.isEmpty()) { ClipGridMemory() }
    val live = remember(state.clipboardItems) { state.clipboardItems.mapTo(HashSet()) { it.id } }
    val gridItems = withDeletedSlots(gridMemory.drawn, shownItems, live)
    androidx.compose.runtime.SideEffect { gridMemory.drawn = gridItems }
    val emptySlots = if (gridItems === shownItems) emptySet() else gridItems.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id !in live } }
    // A picked clip that has gone, by a delete or its timer, is no longer picked.
    val picked = selection.value
    LaunchedEffect(picked, state.clipboardItems) {
        if (picked != null) {
            val ids = state.clipboardItems.mapTo(HashSet()) { it.id }
            val kept = picked.filter { it in ids }
            if (kept.size != picked.size) selection.value = kept
        }
    }
    val showClear = state.settings.clipboard.clearButton && unpinnedCount > 0
    val clearAsking = remember { mutableStateOf(false) }
    // A question left standing while there was nothing to clear would pop
    // back up, unasked, with the next copy.
    LaunchedEffect(showClear) { if (!showClear) clearAsking.value = false }
    val confirmPinned = state.settings.clipboard.confirmPinnedDelete
    val deleteAsking = remember { mutableStateOf<ClipItem?>(null) }
    // The clip asked about went some other way, or lost its pin: nothing left to ask.
    val asked = deleteAsking.value
    LaunchedEffect(asked, state.clipboardItems, confirmPinned) {
        if (asked != null && (!confirmPinned || state.clipboardItems.none { it.id == asked.id && it.pinned })) {
            deleteAsking.value = null
        }
    }
    val ocr = clipOcrAvailable(state.settings)
    // Scanning every clip with three regexes is not free, so it happens once
    // per history change rather than on every recomposition.
    val phoneFormats = state.settings.clipboard.phoneFormats
    val phoneMasks = remember(phoneFormats) { PhoneFormats.parseAll(phoneFormats) }
    val detect = state.settings.clipboard.detectEntities
    val entityIcons = detect && state.settings.clipboard.entityIcons
    val allEntities = if (detect && !entityIcons) {
        remember(state.clipboardItems, phoneMasks) { ClipEntities.entitiesIn(state.clipboardItems, phoneMasks) }
    } else {
        emptyList()
    }
    // Folded away by its ✕ (#612): the strip goes, and a button beside the
    // search counts what it holds and brings it back.
    val stripHidden = state.settings.clipboard.entityStripHidden
    val hiddenFragments = if (stripHidden) allEntities.size else 0
    val perClip = if (detect) {
        remember(state.clipboardItems, phoneMasks) { ClipEntities.byClip(state.clipboardItems, phoneMasks) }
    } else {
        emptyMap()
    }
    // While searching the panel is only a couple of rows tall — the keys have
    // taken the rest — so the fragment strip stands down rather than eating one.
    val entities = if (state.clipboardSearchActive || stripHidden) {
        emptyList()
    } else {
        allEntities.filter { query.isEmpty() || it.value.contains(query, ignoreCase = true) }
    }
    val gridState = rememberLazyStaggeredGridState()
    val searchGridState = rememberLazyStaggeredGridState()
    val tidy = state.settings.clipboard.copyRules.any { it.enabled } || state.settings.clipboard.plainLetters
    val list = state.settings.clipboard.view == ClipboardView.LIST
    val showNumbers = state.settings.clipboard.showNumbers
    // With the tabs on, each tab counts its own clips (#612).
    val perTab = state.settings.clipboard.pinnedTabs
    val numbers = remember(state.clipboardItems, showNumbers, perTab) {
        when {
            !showNumbers -> emptyMap()
            perTab -> clipNumbersPerTab(state.clipboardItems)
            else -> clipNumbers(state.clipboardItems)
        }
    }
    return remember(
        shownItems, entities, showSearch, query, gridState, list, numbers,
        tabbed, pinnedCount, unpinnedCount, showClear, ocr, perClip, entityIcons, confirmPinned,
        hiddenFragments, regexSearch, tidy, pinsInPlace, allTags, gridItems, folders, inFolder,
    ) {
        ClipboardPanelSession(
            shownItems, entities, showSearch, query, gridState, list, numbers,
            tabbed, tab, pinnedCount, unpinnedCount, showClear, clearAsking, ocr, perClip, entityIcons,
            confirmPinned = confirmPinned,
            deleteAsking = deleteAsking,
            filter = filter,
            oldest = oldest,
            filtersOpen = filtersOpen,
            selection = selection,
            reordering = reordering,
            pinNotice = pinNotice,
            showFragments = hiddenFragments > 0,
            hiddenFragments = hiddenFragments,
            regex = regexSearch,
            searchGridState = searchGridState,
            jumpTo = jumpTo,
            tidy = tidy,
            pinsInPlace = pinsInPlace,
            tags = allTags,
            tag = tag,
            gridItems = gridItems,
            emptySlots = emptySlots,
            folders = folders,
            inFolder = inFolder,
        )
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
        PanelFieldKind.CLIPBOARD_VIEW -> ClipboardViewField(state, session, callbacks)
        else -> Unit
    }
}

/**
 * The search pill with the clear button at its end when that is on (#371), or
 * an empty cell while there is nothing to filter or clear. The bin rides the
 * pill's cell rather than a cell of its own, so it turns up in every panel
 * layout the user already has, beside the search bar the way it was asked for.
 */
@Composable
private fun ClipboardSearchFieldCell(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    // What the cell holds, in order: the pill and its `.*` (#612), the bin, and
    // the folded fragments strip's button (#612). Published even when empty,
    // at zero, so a stale count left behind cannot let Tab land on a pill
    // nothing is drawing. Enter on the bin asks, and Enter again, with the
    // question up, deletes.
    val parts = clipSearchCellParts(session)
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = parts.size,
        columns = parts.size.coerceAtLeast(1),
        onActivate = { index ->
            when (parts.getOrNull(index)) {
                ClipSearchPart.PILL -> callbacks.onSearchToggle()
                ClipSearchPart.REGEX -> callbacks.actions.onRegexToggle()
                ClipSearchPart.CLEAR -> if (session.confirmingClear) {
                    session.clearAsking.value = false
                    callbacks.actions.onClearUnpinned()
                } else {
                    session.clearAsking.value = true
                }
                ClipSearchPart.FRAGMENTS -> callbacks.actions.onEntityStripHidden(false)
                null -> Unit
            }
        },
    )
    if (parts.isEmpty()) return
    val focused = state.focusedIndex(FocusRegion.SEARCH)
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.showSearch) {
            ClipboardSearchField(
                state = state,
                onToggle = callbacks.onSearchToggle,
                filterOn = session.filtersOpen.value || session.filtered,
                onFilter = { session.filtersOpen.value = !session.filtersOpen.value },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .focusRing(focused == parts.indexOf(ClipSearchPart.PILL), RoundedCornerShape(18.dp)),
            )
            ClipRegexButton(
                on = session.regex,
                focused = focused == parts.indexOf(ClipSearchPart.REGEX),
                onClick = callbacks.actions.onRegexToggle,
                modifier = Modifier.padding(end = 4.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (session.showClear) {
            ClipboardClearButton(
                asking = session.confirmingClear,
                focused = focused == parts.indexOf(ClipSearchPart.CLEAR),
                // A second tap takes the question back down.
                onClick = { session.clearAsking.value = !session.clearAsking.value },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (session.showFragments) {
            ClipFragmentsButton(
                count = session.hiddenFragments,
                focused = focused == parts.indexOf(ClipSearchPart.FRAGMENTS),
                onClick = { callbacks.actions.onEntityStripHidden(false) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/** What the search cell holds; see [clipSearchCellParts]. */
internal enum class ClipSearchPart { PILL, REGEX, CLEAR, FRAGMENTS }

/** The search cell's parts, in the order they are drawn and the focus ring walks them. */
internal fun clipSearchCellParts(session: ClipboardPanelSession): List<ClipSearchPart> = buildList {
    if (session.showSearch) {
        add(ClipSearchPart.PILL)
        add(ClipSearchPart.REGEX)
    }
    if (session.showClear) add(ClipSearchPart.CLEAR)
    if (session.showFragments) add(ClipSearchPart.FRAGMENTS)
}

/**
 * The `.*` beside the search (#612): the search reads regular expressions
 * while it is lit. The same switch as the setting, a press closer.
 */
@Composable
internal fun ClipRegexButton(
    on: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val description = stringResource(if (on) R.string.ime_clipboard_regex_off_desc else R.string.ime_clipboard_regex_on_desc)
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .heightIn(max = 36.dp)
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(if (on) kb.toolCircleActive else kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .semantics {
                    role = Role.Switch
                    selected = on
                    contentDescription = description
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ".*",
                fontSize = 14.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (on) kb.toolCircleActiveIcon else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The folded fragments strip's way back (#612): the scissors it was captioned
 * with, and how many parts it holds.
 */
@Composable
private fun ClipFragmentsButton(
    count: Int,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val description = pluralStringResource(R.plurals.ime_clip_entity_show_desc, count, count)
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .heightIn(max = 36.dp)
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ContentCut,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .heightIn(min = 13.dp)
                    .widthIn(min = 13.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 99) "99+" else count.toString(),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * The clear button: a square in the view switch's style, in the error colour
 * while its question is up so the two read as one thing.
 */
@Composable
private fun ClipboardClearButton(
    asking: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val description = stringResource(R.string.ime_clipboard_clear_desc)
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .heightIn(max = 36.dp)
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(if (asking) MaterialTheme.colorScheme.errorContainer else kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DeleteSweep,
                contentDescription = description,
                modifier = Modifier.size(18.dp),
                tint = if (asking) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            onHide = { callbacks.actions.onEntityStripHidden(true) },
        )
    }
}

/**
 * The history, with the Undo bar over its bottom edge after a delete. The bar
 * sits on the history rather than in a cell of its own so it is there however
 * the panel layout is arranged, and in the search panel too, and it outlives
 * the last clip: deleting that one is when the empty placeholder shows.
 */
@Composable
private fun ClipboardListField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
    /** Drawn by the search page, whose grid keeps its own scroll (#612). */
    searchPage: Boolean = false,
) {
    val selection = session.selection.value
    val reordering = session.reordering.value
    // Published at zero with the tabs off, for the reason the search pill is.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CATEGORIES,
        count = if (session.tabbed && selection == null && !reordering) ClipTab.entries.size else 0,
        columns = ClipTab.entries.size,
        onActivate = { index -> ClipTab.entries.getOrNull(index)?.let { session.tab.value = it } },
    )
    // Every tap, pin and delete in the history passes through here: a pinned
    // clip's delete asks first wherever it came from (#636), a tap picks
    // rather than pastes while clips are being picked (#612), and a pin that
    // sends a clip to the other tab says so (#612).
    val confirmPinned = session.confirmPinned
    val tabbed = session.tabbed
    val guarded = remember(callbacks, confirmPinned, tabbed) {
        ClipboardFieldCallbacks(
            onItem = { item ->
                val picked = session.selection.value
                if (picked == null) {
                    callbacks.onItem(item)
                } else {
                    session.selection.value = if (item.id in picked) picked - item.id else picked + item.id
                }
            },
            onSticker = callbacks.onSticker,
            onPin = { item ->
                callbacks.onPin(item)
                if (tabbed) session.pinNotice.value = item.copy(pinned = !item.pinned)
            },
            onDelete = { item ->
                if (confirmPinned && item.pinned) {
                    session.clearAsking.value = false
                    session.deleteAsking.value = item
                } else {
                    callbacks.onDelete(item)
                }
            },
            onSearchToggle = callbacks.onSearchToggle,
            onEntity = callbacks.onEntity,
            actions = callbacks.actions,
        )
    }
    val deleteAsked = session.deleteAsking.value
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                reordering -> ClipReorderBar(onDone = { session.reordering.value = false })
                selection != null -> ClipSelectionBar(
                    picked = selection,
                    items = state.clipboardItems,
                    onCancel = { session.selection.value = null },
                    onSelectAll = {
                        val shown = session.shownItems.map { it.id }
                        session.selection.value = if (selection.containsAll(shown)) {
                            emptyList()
                        } else {
                            selection + (shown - selection.toSet())
                        }
                    },
                    onCombine = {
                        session.selection.value = null
                        callbacks.actions.onCombine(selection)
                    },
                    onDelete = {
                        session.selection.value = null
                        callbacks.actions.onDeleteMany(selection)
                    },
                    onTidy = if (session.tidy) {
                        {
                            session.selection.value = null
                            callbacks.actions.onTidy(selection)
                        }
                    } else null,
                    onZawgyi = {
                        session.selection.value = null
                        callbacks.actions.onZawgyi(selection)
                    },
                )
                session.tabbed -> Box(
                    modifier = Modifier.swipeTabs(session.tab.value.ordinal, ClipTab.entries.size) {
                        session.tab.value = ClipTab.entries[it]
                    },
                ) {
                    ClipTabRow(
                        selected = session.tab.value,
                        unpinned = session.unpinnedCount,
                        pinned = session.pinnedCount,
                        focused = state.focusedIndex(FocusRegion.CATEGORIES),
                        onSelect = { session.tab.value = it },
                    )
                }
            }
            if (!reordering && selection == null) {
                if (session.inFolder) {
                    ClipFolderHeader(session.tag.value.orEmpty(), onBack = { session.tag.value = null })
                } else if (session.folders.isNotEmpty()) {
                    ClipFolderRow(session.folders, onOpen = { session.tag.value = it.name })
                }
            }
            if (!reordering && (session.filtersOpen.value || session.filtered)) {
                ClipFilterRow(session)
            }
            // A clip's own swipe (delete, pin) wins on the clip; elsewhere a
            // sideways swipe changes the tab. Not while the clips swipe too
            // (#612): a finger that slipped into the gap between two clips
            // changed the tab instead of deleting, and the tabs themselves
            // still take the swipe.
            val tabs = ClipTab.entries
            val clipsSwipe = state.settings.clipboard.swipeToDelete || state.settings.clipboard.swipePin != ClipSwipePin.OFF
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .swipeTabs(
                        session.tab.value.ordinal,
                        tabs.size,
                        enabled = session.tabbed && selection == null && !reordering && !clipsSwipe,
                    ) { session.tab.value = tabs[it] },
            ) {
                if (reordering) {
                    ClipReorderList(state, session, callbacks.actions.onPlace)
                } else {
                    ClipboardHistory(state, session, guarded, searchPage)
                }
            }
        }
        val clearCount = session.unpinnedCount
        ClipClearQuestion(
            text = when {
                deleteAsked != null -> stringResource(R.string.ime_clip_delete_pinned_question)
                // Not over the search's few rows: the bin that asked is not drawn there.
                session.confirmingClear && !state.clipboardSearchActive ->
                    pluralStringResource(R.plurals.ime_clip_clear_question, clearCount, clearCount)
                else -> null
            },
            reduceMotion = state.settings.reduceMotion,
            onCancel = {
                session.clearAsking.value = false
                session.deleteAsking.value = null
            },
            onDelete = {
                if (deleteAsked != null) {
                    session.deleteAsking.value = null
                    callbacks.onDelete(deleteAsked)
                } else {
                    session.clearAsking.value = false
                    callbacks.actions.onClearUnpinned()
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        ClipPinNotice(
            notice = session.pinNotice,
            reduceMotion = state.settings.reduceMotion,
            onUndo = { item -> callbacks.onPin(item) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        ClipUndoBar(
            undo = state.clipboardUndo,
            reduceMotion = state.settings.reduceMotion,
            onUndo = callbacks.actions.onUndoDelete,
            onDismiss = callbacks.actions.onUndoDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * "Pinned · Undo" for a clip a pin sent to the other tab (#612). Without it a
 * swipe that pinned a clip looked exactly like one that deleted it: the clip
 * left the tab either way. Up for a few seconds, the way the Undo bar is.
 */
@Composable
private fun ClipPinNotice(
    notice: MutableState<ClipItem?>,
    reduceMotion: Boolean,
    onUndo: (ClipItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = notice.value
    var lastPinned by remember { mutableStateOf(true) }
    if (shown != null) lastPinned = shown.pinned
    LaunchedEffect(shown) {
        if (shown != null) {
            delay(ClipPinNoticeMs)
            if (notice.value === shown) notice.value = null
        }
    }
    PanelUndoBar(
        text = stringResource(if (lastPinned) R.string.ime_clip_pinned_notice else R.string.ime_clip_unpinned_notice),
        visible = shown != null,
        key = shown,
        reduceMotion = reduceMotion,
        onUndo = {
            val item = notice.value ?: return@PanelUndoBar
            notice.value = null
            onUndo(item)
        },
        onDismiss = { notice.value = null },
        modifier = modifier,
    )
}

/** How long [ClipPinNotice] stays up. */
private const val ClipPinNoticeMs = 4_000L

/**
 * The bar over the history while clips are being picked (#612): how many,
 * Combine (two or more text clips) and Delete, and a ✕ that stops picking.
 */
@Composable
private fun ClipSelectionBar(
    picked: List<Long>,
    items: List<ClipItem>,
    onCancel: () -> Unit,
    /** Every clip on show picked, in the order they stand after what is already picked; or, with all of them picked, none. */
    onSelectAll: () -> Unit,
    onCombine: () -> Unit,
    onDelete: () -> Unit,
    /** Runs the copy rules over the picked clips (#612); none without rules. */
    onTidy: (() -> Unit)? = null,
    /** Converts the picked clips between Zawgyi and Unicode; shown while one holds Burmese. */
    onZawgyi: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val textual = remember(picked, items) {
        val byId = items.associateBy { it.id }
        picked.count { byId[it]?.kind?.isTextual == true }
    }
    val burmese = remember(picked, items) {
        val byId = items.associateBy { it.id }
        picked.any { id -> byId[id]?.takeIf { it.kind.isTextual && !it.sensitive }?.let { Zawgyi.hasMyanmar(it.text) } == true }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ClipTabHeight + 10.dp)
            .padding(start = 2.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cancel = stringResource(R.string.ime_clip_select_cancel_desc)
        IconHint(cancel) {
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = cancel,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            pluralStringResource(R.plurals.ime_clip_selected_count, picked.size, picked.size),
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        val selectAll = stringResource(R.string.ime_clip_select_all_desc)
        IconHint(selectAll) {
            IconButton(onClick = onSelectAll, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.SelectAll,
                    contentDescription = selectAll,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // A greyed-out button says on a hold what it is waiting for: image
        // clips count towards the selection, not towards these two.
        if (onTidy != null) {
            IconHint(
                null,
                disabledReason = DisabledReason(stringResource(R.string.ime_disabled_clip_tidy)).takeIf { textual < 1 },
            ) {
                TextButton(onClick = onTidy, enabled = textual >= 1) {
                    Text(stringResource(R.string.ime_clip_tidy))
                }
            }
        }
        if (onZawgyi != null && burmese) {
            TextButton(onClick = onZawgyi) {
                Text(stringResource(R.string.ime_clip_zawgyi))
            }
        }
        IconHint(
            null,
            disabledReason = DisabledReason(stringResource(R.string.ime_disabled_clip_combine)).takeIf { textual < 2 },
        ) {
            TextButton(onClick = onCombine, enabled = textual >= 2) {
                Text(stringResource(R.string.ime_clip_combine))
            }
        }
        TextButton(onClick = onDelete, enabled = picked.isNotEmpty()) {
            Text(
                stringResource(CommonR.string.common_delete),
                color = if (picked.isNotEmpty()) MaterialTheme.colorScheme.error else kb.popupText.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * The folders over the pinned clips (#768): one chip per tag, with a folder
 * glyph and how many clips it holds. A press opens it, which is the tag filter
 * set to that tag.
 */
@Composable
private fun ClipFolderRow(folders: List<ClipFolder>, onOpen: (ClipFolder) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(folders, key = { "folder-${it.name.lowercase()}" }) { folder ->
            val description = pluralStringResource(R.plurals.ime_clip_folder_open_desc, folder.count, folder.name, folder.count)
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(kb.chip)
                    .chipBorder(kb, shape)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = description
                    }
                    .clickable { onOpen(folder) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(folder.name, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    folder.count.toString(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An open folder of pinned clips (#768): a back button and the folder's name. */
@Composable
private fun ClipFolderHeader(name: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = stringResource(R.string.ime_clip_folder_back_desc)
        IconHint(back) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = back,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The filter row (#612): newest or oldest first, then which clips. Opened by
 * the filter button in the search pill, and kept up while anything in it is
 * set, so a filtered history always says why it is short.
 */
@Composable
private fun ClipFilterRow(session: ClipboardPanelSession) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "order") {
            ClipFilterChip(
                label = stringResource(if (session.oldest.value) R.string.ime_clip_order_oldest else R.string.ime_clip_order_newest),
                selected = session.oldest.value,
                icon = Icons.Outlined.SwapVert,
            ) { session.oldest.value = !session.oldest.value }
        }
        items(ClipFilter.entries, key = { it.name }) { filter ->
            ClipFilterChip(
                label = stringResource(filter.labelRes),
                selected = session.filter.value == filter,
            ) { session.filter.value = filter }
        }
        // The user's tags (#612), after the kinds: a second press lets go.
        items(session.tags, key = { "tag-$it" }) { tag ->
            val on = session.tag.value.equals(tag, ignoreCase = true)
            ClipFilterChip(
                label = "#$tag",
                selected = on,
                icon = Icons.Outlined.Sell,
            ) { session.tag.value = if (on) null else tag }
        }
    }
}

/** One chip of [ClipFilterRow], in the tabs' style. */
@Composable
private fun ClipFilterChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val color = if (selected) kb.toolCircleActiveIcon else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (selected) kb.toolCircleActive else kb.chip)
            .chipBorder(kb, shape)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, color = color)
    }
}

/** A filter's caption. */
private val ClipFilter.labelRes: Int
    get() = when (this) {
        ClipFilter.ALL -> R.string.ime_clip_filter_all
        ClipFilter.PINNED -> R.string.ime_clipboard_tab_pinned
        ClipFilter.UNPINNED -> R.string.ime_clipboard_tab_unpinned
        ClipFilter.TEXT -> R.string.ime_clip_type_text
        ClipFilter.LINKS -> R.string.ime_clip_extract_links
        ClipFilter.IMAGES -> R.string.ime_clip_filter_images
        ClipFilter.FILES -> R.string.ime_clip_filter_files
        ClipFilter.DUPLICATES -> R.string.ime_clip_filter_duplicates
    }

/**
 * The Unpinned and Pinned tabs over the history (#371), each with its count,
 * so a pin moving a clip across is visible as a number changing.
 */
@Composable
private fun ClipTabRow(
    selected: ClipTab,
    unpinned: Int,
    pinned: Int,
    focused: Int?,
    onSelect: (ClipTab) -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A margin under the tabs as well: without it a card scrolled up
            // under the row ran straight into the buttons (#414).
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((index, tab) in ClipTab.entries.withIndex()) {
            val on = tab == selected
            val color = if (on) kb.toolCircleActiveIcon else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ClipTabHeight)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (on) kb.toolCircleActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(focused == index, shape)
                    .semantics {
                        role = Role.Tab
                        this.selected = on
                    }
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (tab == ClipTab.PINNED) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = color,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (tab == ClipTab.PINNED) R.string.ime_clipboard_tab_pinned else R.string.ime_clipboard_tab_unpinned,
                    ),
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = color,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (tab == ClipTab.PINNED) pinned else unpinned).toString(),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = color.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * A delete's question, in the Undo bar's popup style, over the top of the
 * history: the clear button's (#371), where the bin that asked it is, or a
 * pinned clip's (#636). [text] is the question, or null while nothing is
 * being asked.
 */
@Composable
private fun ClipClearQuestion(
    text: String?,
    reduceMotion: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The last question, kept while it slides away so it does not blank out.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
    ) {
        val kb = LocalKbTheme.current
        Surface(
            shape = kb.menuShape(),
            color = kb.popupFill(FrostSurface.MENUS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp)) {
                Text(
                    lastText,
                    fontSize = 13.sp,
                    color = kb.popupText,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(CommonR.string.common_delete),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Clip deleted · Undo", in the keyboard's popup style: [PanelUndoBar] with
 * the clipboard's words on it.
 */
@Composable
private fun ClipUndoBar(
    undo: ClipUndo?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastCount by remember { mutableIntStateOf(1) }
    if (undo != null) lastCount = undo.items.size
    PanelUndoBar(
        text = pluralStringResource(R.plurals.ime_clip_deleted, lastCount, lastCount),
        visible = undo != null,
        key = undo,
        reduceMotion = reduceMotion,
        onUndo = onUndo,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * "Something happened · Undo", in the keyboard's popup style. A snackbar in
 * all but name: Compose's own needs a Scaffold, and a keyboard panel has none.
 * The clipboard's delete and the Notes panel's trash and archive (#560) draw it.
 *
 * [text] should be what the caller last had to say even once [visible] has
 * gone false, so the bar does not blank out under its exit animation; [key]
 * changing restarts the swipe gesture for a fresh offer.
 */
@Composable
internal fun PanelUndoBar(
    text: String,
    visible: Boolean,
    key: Any?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The offer last shown, kept while it slides away, so the swipe below
    // starts afresh for each new offer but not for its own exit animation.
    var shown by remember { mutableStateOf(key) }
    if (visible) shown = key
    val dismissLabel = stringResource(R.string.ime_clip_undo_dismiss)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
    ) {
        val kb = LocalKbTheme.current
        // A sideways swipe puts the bar away and ends the Undo there and then
        // (#371), the way a snackbar goes.
        key(shown) {
            SwipeToDeleteCard(onDelete = onDismiss) {
                Surface(
                    shape = kb.menuShape(),
                    color = kb.popupFill(FrostSurface.MENUS),
                    border = kb.popupSurfaceBorder(),
                    shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = kb.popupText,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onUndo) {
                            Text(
                                stringResource(R.string.ime_clip_undo_delete),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The history — two columns of cards packed independently, or one clip per
 * row — with the empty and no-match placeholders where the clips would be.
 *
 * Both views are the same lazy grid with a different column count, so the
 * switch keeps the scroll position, the swipe-to-delete and the item
 * animations, and the focus ring moves the way the clips are drawn.
 */
@Composable
private fun ClipboardHistory(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
    searchPage: Boolean = false,
) {
    val shownItems = session.shownItems
    // The grid's own list, with the empty slots of clips just deleted (#612).
    val gridItems = session.gridItems
    val emptySlots = session.emptySlots
    val clipboard = state.settings.clipboard
    // Image clips offer Translate while a photo translation server is on (#807).
    val photoTranslate = remember(state.settings) { PhotoTranslate.rows(state.settings).isNotEmpty() }
    val searchTools = remember(state.settings.enabledTools) {
        (isSupportedTool(ToolbarTool.WEB_SEARCH) && ToolbarTool.WEB_SEARCH in state.settings.enabledTools) to
            (isSupportedTool(ToolbarTool.WIKIPEDIA) && ToolbarTool.WIKIPEDIA in state.settings.enabledTools)
    }
    val columns = if (session.list) 1 else clipboard.gridColumns.coerceIn(ClipGridColumnsRange)
    // 0 is the view's own: six lines on a card, three in a row, where a list
    // is for scanning many clips.
    val lines = clipboard.previewLines.takeIf { it > 0 } ?: if (session.list) 3 else 6
    // The clock the time labels read. Ticks only while they are shown, and
    // only twice a minute: they count in minutes.
    val timeLabel = clipboard.timeLabel
    // With the swipe off (#344), or the buttons (#414), the hold popup
    // carries the delete instead; without the buttons, the pin as well.
    // Picking clips (#612) takes the swipe and the buttons away for a while:
    // a tap picks, and nothing else should happen to a clip being picked.
    val picked = session.selection.value
    val swipe = clipboard.swipeToDelete && picked == null
    val buttons = clipboard.cardButtons && picked == null
    // Keep formatting off (#612): a link is drawn as the plain text it is.
    val plainLinks = !clipboard.keepRichText
    val lineHeight = (clipboard.lineSpacing / 100f).em
    val now by produceState(System.currentTimeMillis(), timeLabel) {
        if (timeLabel == ClipTimeLabel.OFF) return@produceState
        while (true) {
            delay(ClipTimeTickMs)
            value = System.currentTimeMillis()
        }
    }
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        count = gridItems.size,
        columns = columns,
        onActivate = { index -> gridItems.getOrNull(index)?.takeIf { it.id !in emptySlots }?.let(callbacks.onItem) },
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
                when {
                    session.query.isNotEmpty() -> stringResource(R.string.ime_clipboard_no_match, session.query)
                    session.tab.value == ClipTab.PINNED -> stringResource(R.string.ime_clipboard_tab_pinned_empty)
                    else -> stringResource(R.string.ime_clipboard_tab_unpinned_empty)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val focused = state.focusedIndex()
    val gridState = if (searchPage) session.searchGridState else session.gridState
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // Show in history (#612): once the search has closed, the history page
    // scrolls to the clip found, and rings it for a moment so it is the one
    // the eye lands on among its neighbours.
    var flashId by remember { mutableStateOf<Long?>(null) }
    val jump = session.jumpTo.value
    if (!searchPage) {
        LaunchedEffect(jump, gridItems) {
            val id = jump ?: return@LaunchedEffect
            val index = gridItems.indexOfFirst { it.id == id }
            if (index < 0) return@LaunchedEffect
            session.jumpTo.value = null
            gridState.scrollToItem(index)
            flashId = id
            delay(ClipJumpFlashMs)
            flashId = null
        }
    }
    val duplicates = session.filter.value == ClipFilter.DUPLICATES
    val shortcut = clipboard.cardShortcut
    // Staggered, not a fixed grid: each column packs independently, so a tall
    // image sits next to two or three stacked text clips.
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        // Room under the last clips for the Undo bar while it is up, so the
        // bottom row can still be scrolled clear of it.
        contentPadding = PaddingValues(
            start = 6.dp,
            top = 6.dp,
            end = 6.dp,
            bottom = if (state.clipboardUndo != null) UndoBarClearance else 6.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
    ) {
        itemsIndexed(gridItems, key = { _, item -> item.id }) { index, item ->
            // A deleted clip's slot (#612): nothing to draw, only the place.
            if (item.id in emptySlots) {
                Spacer(Modifier.fillMaxWidth().height(0.dp))
                return@itemsIndexed
            }
            // Deleting fades the card out and slides the survivors up into the
            // gap; pinning re-sorts the list, so the card glides to the front.
            // Under Reduce motion the list simply redraws in its new order.
            SwipeToDeleteCard(
                onDelete = { callbacks.onDelete(item) },
                enabled = swipe,
                // A pinned clip's swipe only asks (#636), so the card comes back.
                keepOnDelete = item.pinned && session.confirmPinned,
                // One way pins and the other deletes, when asked (#414), and
                // which way round is the user's (#612).
                onSwipePin = if (clipboard.swipePin != ClipSwipePin.OFF) {
                    { callbacks.onPin(item) }
                } else null,
                pinLeft = clipboard.swipePin == ClipSwipePin.LEFT,
                modifier = if (state.settings.reduceMotion) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        fadeInSpec = tween(160),
                        placementSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                        fadeOutSpec = tween(140),
                    )
                },
            ) {
                val number = session.numbers[item.id]
                val time = clipTimeText(item, timeLabel, clipboard, now)
                val hold = ClipHold(
                    holdDelete = !clipboard.swipeToDelete || !clipboard.cardButtons,
                    holdPin = !clipboard.cardButtons,
                    ocr = session.ocr,
                    photoTranslate = photoTranslate,
                    webSearch = searchTools.first,
                    wikipedia = searchTools.second,
                    entities = session.perClip[item.id].orEmpty(),
                    icons = session.entityIcons && picked == null,
                    typeTag = clipboard.typeTags,
                    plainLinks = plainLinks,
                    lineHeight = lineHeight,
                    picked = picked?.let { item.id in it },
                    onPick = { session.selection.value = listOf(item.id) },
                    onReorder = { session.reordering.value = true },
                    onJump = if (searchPage) {
                        { clipJumpTo(session, item, callbacks) }
                    } else null,
                    onKeepOnly = if (duplicates) {
                        {
                            val key = duplicateKey(item)
                            val others = shownItems.filter { it.id != item.id && duplicateKey(it) == key }.map { it.id }
                            if (others.isNotEmpty()) callbacks.actions.onDeleteMany(others)
                        }
                    } else null,
                    shortcut = shortcut,
                )
                val outlined = (clipboard.outlinePinned && item.pinned) || item.id == flashId
                if (session.list) {
                    ClipRow(item, number, lines, time, focused = index == focused, outlined, buttons, hold, callbacks)
                } else {
                    ClipCard(item, number, lines, time, focused = index == focused, outlined, buttons, hold, callbacks)
                }
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
        count = 2,
        columns = 2,
        onActivate = { index -> if (index == 0) callbacks.onSearchToggle() else callbacks.actions.onRegexToggle() },
    )
    // No fragment strip or view switch while searching; publish their regions
    // empty so a stale count cannot let Tab land on something nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    Column(modifier = modifier) {
        val focused = state.focusedIndex(FocusRegion.SEARCH)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The back button stays where the header had it (#612), and
            // closes the search, instead of the panel's back going and a ✕
            // turning up inside the far end of the pill.
            ToolCircle(
                slot = com.wasimaster.wmkeyboard.core.icons.IconSlots.CHROME_PANEL_BACK,
                description = stringResource(R.string.ime_clipboard_search_clear_desc),
                active = false,
                onClick = callbacks.onSearchToggle,
                modifier = Modifier.padding(end = 4.dp),
            )
            ClipboardSearchField(
                state = state,
                onToggle = callbacks.onSearchToggle,
                filterOn = session.filtersOpen.value || session.filtered,
                onFilter = { session.filtersOpen.value = !session.filtersOpen.value },
                showClose = false,
                modifier = Modifier
                    .weight(1f)
                    .focusRing(focused == 0, RoundedCornerShape(18.dp)),
            )
            ClipRegexButton(
                on = session.regex,
                focused = focused == 1,
                onClick = callbacks.actions.onRegexToggle,
                modifier = Modifier.padding(start = 6.dp),
            )
            // How many clips the search and the filter leave (#612).
            val count = session.shownItems.size
            Text(
                count.toString(),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(min = 18.dp),
                textAlign = TextAlign.Center,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ClipboardListField(state, session, callbacks, searchPage = true)
        }
    }
}

/**
 * Each clip's number by id: its place in [items], counted from 1. The history
 * as the panel orders it, so the numbers read down the page in order, and a
 * filter that hides some clips leaves the rest with the numbers they had.
 */
internal fun clipNumbers(items: List<ClipItem>): Map<Long, Int> =
    items.withIndex().associate { (index, item) -> item.id to index + 1 }

/**
 * The tap, the hold and the focus ring every clip shares, card or row.
 * Composable only because [focusRing] reads the theme.
 */
@Composable
private fun Modifier.clipSurface(
    kb: KbTheme,
    item: ClipItem,
    focused: Boolean,
    outlined: Boolean,
    callbacks: ClipboardFieldCallbacks,
    onHold: () -> Unit,
): Modifier {
    val shape = kb.cardShape()
    // The gesture outlives the composition it started in: read the clip and
    // the callbacks as they are now, or a tap pastes a clip's text from before
    // an edit, and picks clips (#612) with the callbacks from before picking.
    val current by rememberUpdatedState(item)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val hold by rememberUpdatedState(onHold)
    return this
        .clip(shape)
        .frostGlass(kb, shape, FrostSurface.CARDS)
        .background(kb.chip)
        .chipBorder(kb, shape)
        // A pinned clip's outline, when that is on (#371): the pin circle's
        // own accent, around the whole clip.
        .then(if (outlined) Modifier.border(PinnedOutlineWidth, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .focusRing(focused, shape)
        .pointerInput(item.id) {
            detectTapGestures(
                onTap = { currentCallbacks.onItem(current) },
                onLongPress = { hold() },
            )
        }
}

/**
 * What a clip's press-and-hold popup offers beyond its kind: a Delete when
 * [holdDelete] (the swipe or the bin that would otherwise delete is off), Pin
 * when [holdPin] (the clip has no pin button, #414), and Extract text on a
 * picture when [ocr] (#371). Extract, when the clip holds [entities] (#472),
 * which the card also shows as icons while [icons] is on.
 */
@Immutable
private class ClipHold(
    val holdDelete: Boolean,
    val holdPin: Boolean,
    val ocr: Boolean,
    /** Translate on a picture (#807): a photo translation server is on. */
    val photoTranslate: Boolean = false,
    val entities: List<ClipEntity> = emptyList(),
    val icons: Boolean = false,
    /** Whether a rich-text clip wears its "Rich text" tag (#414). */
    val typeTag: Boolean = true,
    /** A link clip is drawn as plain text, with Keep formatting off (#612). */
    val plainLinks: Boolean = false,
    /** The text's line height, from the line spacing setting (#612). */
    val lineHeight: TextUnit = TextUnit.Unspecified,
    /** Whether this clip is picked, while clips are being picked (#612); null while they are not. */
    val picked: Boolean? = null,
    /** The hold popup's Select: start picking, with this clip (#612). */
    val onPick: () -> Unit = {},
    /** The hold popup's Reorder: the history becomes the list to drag clips in (#612). */
    val onReorder: () -> Unit = {},
    /** Show in history: close the search and scroll to this clip (#612); null outside a search. */
    val onJump: (() -> Unit)? = null,
    /** Keep only this: delete the clips the duplicates filter groups with it (#612); null outside it. */
    val onKeepOnly: (() -> Unit)? = null,
    /** The button at the end of the clip's bottom row (#612). */
    val shortcut: ClipCardShortcut = ClipCardShortcut.EDITED_MARK,
    /** The web search and Wikipedia tools are on, for the hold popup's two searches (#914). */
    val webSearch: Boolean = false,
    val wikipedia: Boolean = false,
)

/**
 * Show in history (#612): the search closes, every filter with it, and the
 * history page scrolls to [item] on its tab, so the clips copied around it
 * are in view, the way a chat app's Go to message works.
 */
private fun clipJumpTo(session: ClipboardPanelSession, item: ClipItem, callbacks: ClipboardFieldCallbacks) {
    session.filter.value = ClipFilter.ALL
    session.tag.value = null
    session.filtersOpen.value = false
    session.tab.value = if (item.pinned) ClipTab.PINNED else ClipTab.UNPINNED
    session.jumpTo.value = item.id
    callbacks.onSearchToggle()
}

/** How long a clip reached by Show in history keeps its ring. */
private const val ClipJumpFlashMs = 1_600L

/**
 * The press-and-hold popup for a clip, with the actions its kind allows: Edit
 * for text, Open link for a bare address, View and Extract text for a picture
 * (#371), and Pin and Delete when [hold] asks.
 *
 * A text clip opens straight on its whole text (#414): that is what a hold most
 * often wants, and the details that used to come first sit one press away
 * behind the ⓘ there. The actions ride under the text as icons, the shapes
 * every phone user already knows, so a row of worded buttons does not push the
 * text up. Everything else — a picture, a file, a secret — still opens on the
 * details, which for those is the useful half.
 */
@Composable
private fun ClipHoldPopup(
    item: ClipItem,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
    onDismiss: () -> Unit,
) {
    val textual = item.kind.isTextual && !item.sensitive && item.text.isNotEmpty()
    var fullText by remember { mutableStateOf(textual) }
    var extracting by remember { mutableStateOf(false) }
    val image = item.kind == ClipKind.IMAGE
    // Not a secret's: the panel never shows one's text, and the browser would.
    val link = item.kind.isTextual && !item.sensitive && ClipLinks.asUrl(item.text) != null
    // One part is simply that part; more open the picker (#472).
    val extractParts: (() -> Unit)? = when (hold.entities.size) {
        0 -> null
        1 -> { { onDismiss(); callbacks.onEntity(hold.entities.single()) } }
        else -> { { extracting = true } }
    }
    if (extracting) {
        ClipExtractPopup(hold.entities, onPick = { onDismiss(); callbacks.onEntity(it) }, onDismiss = onDismiss)
        return
    }
    // Picking and reordering are for the history as it is drawn; not while a
    // search or a filter has cut it down, or while clips are already picked.
    val onPick: (() -> Unit)? = if (hold.picked == null) {
        { onDismiss(); hold.onPick() }
    } else null
    val onReorder: (() -> Unit)? = if (hold.picked == null) {
        { onDismiss(); hold.onReorder() }
    } else null
    if (fullText) {
        ClipFullTextPopup(
            item,
            plainLinks = hold.plainLinks,
            actions = ClipTextActions(
                onInfo = { fullText = false },
                onPick = onPick,
                onReorder = onReorder,
                onJump = hold.onJump?.let { jump -> { onDismiss(); jump() } },
                onKeepOnly = hold.onKeepOnly?.let { keep -> { onDismiss(); keep() } },
                onTags = if (hold.picked == null) {
                    { onDismiss(); callbacks.actions.onTags(item) }
                } else null,
                onQr = if (clipQrable(item) && hold.picked == null) {
                    { onDismiss(); callbacks.actions.onQr(item) }
                } else null,
                onWebSearch = if (hold.webSearch && clipQrable(item) && hold.picked == null) {
                    { onDismiss(); callbacks.actions.onSearch(item, false) }
                } else null,
                onWikipedia = if (hold.wikipedia && clipQrable(item) && hold.picked == null) {
                    { onDismiss(); callbacks.actions.onSearch(item, true) }
                } else null,
                onEdit = if (item.clipEditable) {
                    { onDismiss(); callbacks.actions.onEdit(item) }
                } else null,
                onOpenLink = if (link) {
                    { onDismiss(); callbacks.actions.onOpenLink(item) }
                } else null,
                onExtractParts = extractParts,
                onTogglePin = if (hold.holdPin) {
                    { onDismiss(); callbacks.onPin(item) }
                } else null,
                onDelete = if (hold.holdDelete) {
                    { onDismiss(); callbacks.onDelete(item) }
                } else null,
            ),
            onPaste = { onDismiss(); callbacks.onItem(item) },
            onDismiss = onDismiss,
        )
        return
    }
    ClipInfoPopup(
        item,
        onViewText = if (textual) {
            { fullText = true }
        } else null,
        onTogglePin = if (hold.holdPin) {
            { onDismiss(); callbacks.onPin(item) }
        } else null,
        onSendSticker = if (image) {
            { callbacks.onSticker(item); onDismiss() }
        } else null,
        onEdit = if (item.clipEditable) {
            { onDismiss(); callbacks.actions.onEdit(item) }
        } else null,
        onOpenLink = if (link) {
            { onDismiss(); callbacks.actions.onOpenLink(item) }
        } else null,
        onView = if (image) {
            { onDismiss(); callbacks.actions.onViewImage(item) }
        } else null,
        onTranslateImage = if (image && hold.photoTranslate) {
            { onDismiss(); callbacks.actions.onTranslateImage(item) }
        } else null,
        onExtractText = if (image && hold.ocr) {
            { onDismiss(); callbacks.actions.onExtractText(item) }
        } else null,
        onExtractParts = extractParts,
        onDelete = if (hold.holdDelete) {
            { onDismiss(); callbacks.onDelete(item) }
        } else null,
        onPick = onPick,
        onReorder = onReorder,
        onJump = hold.onJump?.let { jump -> { onDismiss(); jump() } },
        onTags = if (hold.picked == null) {
            { onDismiss(); callbacks.actions.onTags(item) }
        } else null,
        onPaste = { onDismiss(); callbacks.onItem(item) },
        onDismiss = onDismiss,
    )
}

/**
 * What [ClipFullTextPopup] can do to its clip besides paste it, each null when
 * the clip or the settings leave it out. [onInfo] is never null: the details
 * are always one press away.
 */
@Immutable
private class ClipTextActions(
    val onInfo: () -> Unit,
    /** Start picking clips, with this one (#612). */
    val onPick: (() -> Unit)? = null,
    /** The Reorder view (#612). */
    val onReorder: (() -> Unit)? = null,
    /** Show in history, from a search (#612). */
    val onJump: (() -> Unit)? = null,
    /** Keep only this one of its duplicates (#612). */
    val onKeepOnly: (() -> Unit)? = null,
    /** The clip's tags, in the editor (#612). */
    val onTags: (() -> Unit)? = null,
    /** The clip as a QR code (#766). */
    val onQr: (() -> Unit)? = null,
    /** The clip searched for on the web, or on Wikipedia (#914). */
    val onWebSearch: (() -> Unit)? = null,
    val onWikipedia: (() -> Unit)? = null,
    val onEdit: (() -> Unit)? = null,
    val onOpenLink: (() -> Unit)? = null,
    val onExtractParts: (() -> Unit)? = null,
    val onTogglePin: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * A clip's whole text (#414), the first thing a hold on a text clip shows: a
 * card shows a few lines, and the rest of a long clip could only be read by
 * pasting it or by opening the editor. The text scrolls under a fixed ceiling,
 * in the chunks [clipTextChunks] cuts, so a clip the size of a document lays
 * out a screenful at a time rather than whole. Under it, [actions] as a row of
 * icons with Paste at the end, which does what a tap on the clip does. Tapping
 * outside puts it away.
 */
@Composable
private fun ClipFullTextPopup(
    item: ClipItem,
    actions: ClipTextActions,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    /** Keep formatting off (#612): links are not set apart in the text. */
    plainLinks: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val chunks = remember(item.text) { clipTextChunks(item.text) }
    val length = item.text.length
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        IconHintHost {
            GlassPopupBackdrop(kb, FrostSurface.MENUS)
            Surface(
                shape = kb.menuShape(),
                color = kb.popupFill(FrostSurface.MENUS),
                border = kb.popupSurfaceBorder(),
                shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(max = ClipFullTextMaxWidth)
                    .fillMaxWidth()
                    .frostGlass(kb, kb.menuShape(), FrostSurface.MENUS),
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        pluralStringResource(R.plurals.ime_clip_character_count, length, length),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = kb.popupText.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = ClipFullTextMaxHeight),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        items(chunks) { chunk ->
                            // Links in the accent colour (#472), so an address
                            // stands out of a long clip.
                            val accent = kb.accent
                            val shown = remember(chunk, accent, plainLinks) {
                                if (plainLinks) AnnotatedString(chunk) else withLinksMarked(chunk, accent)
                            }
                            Text(shown, fontSize = 13.sp, lineHeight = 18.sp, color = kb.popupText)
                        }
                    }
                    // The icons at the end, under the thumb, the way Chrome's menu
                    // row sits (#612), with Paste last.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = kb.popupText
                        // Scrolls rather than squeezing Paste off a narrow screen
                        // when a clip offers every action at once.
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ClipTextActionIcon(Icons.Outlined.Info, stringResource(R.string.ime_clip_details_desc), tint, actions.onInfo)
                                actions.onPick?.let {
                                    ClipTextActionIcon(Icons.Outlined.Checklist, stringResource(R.string.ime_clip_select), tint, it)
                                }
                                actions.onReorder?.let {
                                    ClipTextActionIcon(Icons.Outlined.Reorder, stringResource(R.string.ime_clip_reorder), tint, it)
                                }
                                actions.onJump?.let {
                                    ClipTextActionIcon(Icons.Outlined.History, stringResource(R.string.ime_clip_show_in_history), tint, it)
                                }
                                actions.onKeepOnly?.let {
                                    ClipTextActionIcon(Icons.Outlined.ContentCopy, stringResource(R.string.ime_clip_keep_only), tint, it)
                                }
                                actions.onEdit?.let {
                                    ClipTextActionIcon(Icons.Outlined.Edit, stringResource(R.string.ime_clip_edit), tint, it)
                                }
                                actions.onTags?.let {
                                    ClipTextActionIcon(Icons.Outlined.Sell, stringResource(R.string.ime_clip_tags), tint, it)
                                }
                                actions.onQr?.let {
                                    ClipTextActionIcon(Icons.Outlined.QrCode2, stringResource(R.string.ime_clip_qr), tint, it)
                                }
                                actions.onWebSearch?.let {
                                    ClipTextActionIcon(Icons.Outlined.Search, stringResource(R.string.ime_clip_search_web), tint, it)
                                }
                                actions.onWikipedia?.let {
                                    ClipTextActionIcon(
                                        Icons.AutoMirrored.Outlined.MenuBook,
                                        stringResource(R.string.ime_clip_search_wikipedia),
                                        tint,
                                        it,
                                    )
                                }
                                actions.onOpenLink?.let {
                                    ClipTextActionIcon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        stringResource(R.string.ime_clip_open_link),
                                        tint,
                                        it,
                                    )
                                }
                                actions.onExtractParts?.let {
                                    ClipTextActionIcon(Icons.Outlined.ContentCut, stringResource(R.string.ime_clip_extract_parts), tint, it)
                                }
                                actions.onTogglePin?.let {
                                    ClipTextActionIcon(
                                        if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        stringResource(if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin),
                                        tint,
                                        it,
                                    )
                                }
                                actions.onDelete?.let {
                                    ClipTextActionIcon(
                                        Icons.Outlined.Delete,
                                        stringResource(CommonR.string.common_delete),
                                        MaterialTheme.colorScheme.error,
                                        it,
                                    )
                                }
                            }
                        }
                        TextButton(onClick = onPaste) {
                            Text(stringResource(CommonR.string.common_paste), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** One icon in the clip popups' action rows: the glyph says what it does, TalkBack says it in words. */
@Composable
internal fun ClipTextActionIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconHint(description) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * [text] cut into pieces of at most [max] characters for [ClipFullTextPopup]'s
 * lazy list: at the last line break inside the limit, else the last space,
 * else at the limit itself, one character early rather than between the two
 * halves of a surrogate pair. The break a piece ends on is dropped, since the
 * next piece starts on a line of its own anyway. Empty text is one empty piece.
 */
internal fun clipTextChunks(text: String, max: Int = ClipTextChunkChars): List<String> {
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + max, text.length)
        var next = end
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            val space = text.lastIndexOf(' ', end - 1)
            when {
                newline > start -> { end = newline; next = newline + 1 }
                space > start -> { end = space; next = space + 1 }
                Character.isHighSurrogate(text[end - 1]) -> { end--; next = end }
            }
        }
        out += text.substring(start, end)
        start = next
    }
    return out
}

/** [text] with every web address in it set in [color] and underlined. */
internal fun withLinksMarked(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (match in WebAddress.findAll(text)) {
        val end = match.range.last + 1 - match.value.takeLastWhile { it in TrailingPunctuation }.length
        addStyle(
            SpanStyle(color = color, textDecoration = TextDecoration.Underline),
            match.range.first,
            end,
        )
    }
}

/** An http(s) address or one starting with www., up to the next space. */
private val WebAddress = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

/** What a sentence leaves stuck to the end of an address it ends on. */
private const val TrailingPunctuation = ".,;:!?)]}'\""

/** How much text one item of [ClipFullTextPopup]'s list holds. */
internal const val ClipTextChunkChars = 2_000

/** [ClipFullTextPopup]'s size: most of a phone's width, and a few paragraphs tall. */
private val ClipFullTextMaxWidth = 440.dp
private val ClipFullTextMaxHeight = 320.dp

/**
 * A clip's body by kind; [maxLines] bounds plain text, the one body that can
 * run on. [hold] says how text is set: its line height (#612), and whether a
 * link is drawn as one or as the plain text it is.
 */
@Composable
private fun ClipBody(item: ClipItem, maxLines: Int, hold: ClipHold) {
    when {
        // A masked secret outranks every other body: the point is that its
        // content is not on screen.
        item.sensitive && item.kind.isTextual -> ClipSensitiveBody(item)
        item.kind == ClipKind.IMAGE -> ClipThumbnail(item)
        item.kind == ClipKind.VIDEO -> ClipVideoBody(item)
        item.kind == ClipKind.FILE || item.kind == ClipKind.FOLDER -> ClipFileBody(item)
        // With Keep formatting off a link is plain text too (#612): no colour,
        // no underline, no icon taking the first line's room. A fetched
        // preview is still the link's, since previews were asked for.
        item.kind == ClipKind.LINK && !(hold.plainLinks && item.linkPreview?.let { it.failed || it.isEmpty } != false) ->
            ClipLinkBody(item, maxLines, hold.lineHeight)
        else -> {
            // A clip that is one colour code (#fff, rgb(…), hsl(…), 0xAARRGGBB)
            // wears the colour as a square before the code. parse() turns down
            // anything over 40 characters before reading it, so a long clip
            // costs nothing here.
            val colour = remember(item.text) { ColourCodes.parse(item.text) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (colour != null) ClipColourSwatch(colour.argb, Modifier.padding(end = 6.dp))
                // Cut before layout: a paragraph is measured whole however few lines
                // are drawn, and one clip can be a whole document.
                Text(
                    text = remember(item.text, maxLines) { clipPreviewText(item.text, maxLines) },
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    // Set, not inherited: Material's body line height is nearly twice
                    // this size, which is what spread a three-line clip over five (#612).
                    lineHeight = hold.lineHeight,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The colour a colour-code clip names, as a small rounded square. The hairline
 * keeps a colour close to the card's own (white on a light theme, black on a
 * dark one) from vanishing into it.
 */
@Composable
private fun ClipColourSwatch(argb: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(shape)
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), shape),
    )
}

/**
 * The mark on a clip while clips are being picked (#612): a filled tick on a
 * picked one, an empty ring on the rest, in the clip's top corner.
 */
@Composable
private fun ClipPickMark(picked: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(if (picked) R.string.ime_clip_picked_desc else R.string.ime_clip_not_picked_desc)
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (picked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The pin and delete circles, in that order, with the shortcut between them
 * (#612): the edited mark on a clip that was edited (#576), or the button the
 * user chose, to the right of the pin.
 */
@Composable
private fun ClipActions(item: ClipItem, hold: ClipHold, callbacks: ClipboardFieldCallbacks) {
    ClipActionCircle(
        icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        description = stringResource(if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin),
        tint = if (item.pinned) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onPin(item) }
    if (clipShortcut(item, hold)) ClipShortcutButton(item, hold, callbacks)
    ClipActionCircle(
        icon = Icons.Outlined.Delete,
        description = stringResource(CommonR.string.common_delete),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onDelete(item) }
}

/** Whether [item] shows a shortcut, by the setting (#612): only where it has something to do. */
private fun clipShortcut(item: ClipItem, hold: ClipHold): Boolean = when (hold.shortcut) {
    ClipCardShortcut.EDITED_MARK -> item.editedAt != null
    ClipCardShortcut.NONE -> false
    ClipCardShortcut.EDIT -> item.clipEditable && hold.picked == null
    ClipCardShortcut.SELECT -> hold.picked == null
    ClipCardShortcut.QR -> clipQrable(item) && hold.picked == null
}

/** Whether [item] can be shown as a QR code (#766): text the panel shows, so not a secret's. */
private fun clipQrable(item: ClipItem): Boolean = item.kind.isTextual && !item.sensitive && item.text.isNotBlank()

/** The shortcut [clipShortcut] said [item] shows. */
@Composable
private fun ClipShortcutButton(item: ClipItem, hold: ClipHold, callbacks: ClipboardFieldCallbacks) {
    when (hold.shortcut) {
        ClipCardShortcut.EDIT -> ClipActionCircle(
            icon = Icons.Outlined.Edit,
            description = stringResource(R.string.ime_clip_edit),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { callbacks.actions.onEdit(item) }
        ClipCardShortcut.SELECT -> ClipActionCircle(
            icon = Icons.Outlined.Checklist,
            description = stringResource(R.string.ime_clip_select),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { hold.onPick() }
        ClipCardShortcut.QR -> ClipActionCircle(
            icon = Icons.Outlined.QrCode2,
            description = stringResource(R.string.ime_clip_qr),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { callbacks.actions.onQr(item) }
        else -> ClipEditedMark()
    }
}

/**
 * The mark on a clip whose text was edited (#576): the edit glyph alone, with
 * no circle under it, so it does not read as one more button. Nothing happens
 * on a press; when the edit was is in the clip's details.
 */
@Composable
private fun ClipEditedMark() {
    val description = stringResource(R.string.ime_clip_info_edited)
    Icon(
        Icons.Outlined.Edit,
        contentDescription = null,
        modifier = Modifier
            .size(15.dp)
            .clearAndSetSemantics { contentDescription = description },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A clip's number, as a small filled tag. Filled with the accent so it reads
 * as a label the keyboard put there and never as part of the clip's text.
 */
@Composable
private fun ClipNumberBadge(number: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ime_clip_number_desc, number)
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * One history card: body by kind, then the number, the pin, the edited mark
 * (#576) and the delete circle along the bottom. The number rides the row the circles already
 * take, so turning numbering on never makes a card taller. With [buttons] off
 * (#414) the row goes too, on any card it would have left empty.
 */
@Composable
private fun ClipCard(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    outlined: Boolean,
    buttons: Boolean,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                // The whole column, whatever the text: the swipe wrapper is a plain
                // Box and hands its child no minimum width, so a clip of one short
                // word shrank to it and the grid looked broken (#414).
                .fillMaxWidth()
                .clipSurface(LocalKbTheme.current, item, focused, outlined || hold.picked == true, callbacks) { showInfo = true }
                // An image card insets less: the picture is the content.
                .padding(if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) 5.dp else 10.dp),
        ) {
            if (showInfo) ClipHoldPopup(item, hold, callbacks) { showInfo = false }
            ClipBody(item, maxLines = lines, hold = hold)
            if (item.tags.isNotEmpty()) ClipTagsLine(item.tags, Modifier.padding(top = 4.dp))
            val icons = hold.icons && hold.entities.isNotEmpty()
            val tag = item.kind == ClipKind.HTML && hold.typeTag
            // Per card: one clip may still have a number or a time to show while
            // the next, pinned and unnumbered, has nothing left for the row.
            val shortcut = clipShortcut(item, hold)
            // The app it was copied from, as that app's icon (#970).
            val appIcon = rememberFloatingAppIcon(item.sourcePackage, ClipSourceIconDp)
            val footer = buttons || number != null || time != null || tag || icons || shortcut || appIcon != null
            if (footer) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (number != null) ClipNumberBadge(number)
                if (appIcon != null) ClipSourceIcon(appIcon, item.sourceApp)
                if (tag) {
                    Text(
                        stringResource(R.string.ime_clip_type_rich_text),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (time != null) ClipTimeText(time, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (icons) ClipEntityIcons(hold.entities, callbacks)
                // Without the buttons there is no pin to sit beside; the
                // shortcut, or the mark, stays.
                if (buttons) ClipActions(item, hold, callbacks) else if (shortcut) ClipShortcutButton(item, hold, callbacks)
            }
        }
        hold.picked?.let { ClipPickMark(it, Modifier.align(Alignment.TopEnd).padding(5.dp)) }
    }
}

/** The source app's icon, small, at the head of a card's or a row's footer (#970). */
@Composable
private fun ClipSourceIcon(icon: ImageBitmap, label: String?) {
    Image(
        bitmap = icon,
        contentDescription = label,
        modifier = Modifier.size(ClipSourceIconDp.dp),
    )
}

private const val ClipSourceIconDp = 14

/**
 * What was found in one clip, as an icon per kind with a count once there is
 * more than one (#472): the strip's chips folded onto the card they came from.
 * A press opens the parts to pick from; a kind with one part pastes it.
 */
@Composable
private fun ClipEntityIcons(entities: List<ClipEntity>, callbacks: ClipboardFieldCallbacks) {
    var picking by remember { mutableStateOf<List<ClipEntity>?>(null) }
    picking?.let { shown ->
        ClipExtractPopup(shown, onPick = { picking = null; callbacks.onEntity(it) }, onDismiss = { picking = null })
    }
    for ((kind, ofKind) in entities.groupBy { it.kind }) {
        val label = stringResource(kind.labelRes)
        Box {
            ClipActionCircle(
                icon = clipEntityIcon(kind),
                description = label,
                tint = MaterialTheme.colorScheme.primary,
            ) {
                if (ofKind.size == 1) callbacks.onEntity(ofKind.single()) else picking = ofKind
            }
            if (ofKind.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .heightIn(min = 14.dp)
                        .widthIn(min = 14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ofKind.size.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** The icon each kind of fragment wears, here and on the strip's chips. */
internal fun clipEntityIcon(kind: ClipEntityKind): ImageVector = when (kind) {
    ClipEntityKind.OTP -> Icons.Outlined.Password
    ClipEntityKind.PHONE -> Icons.Outlined.Phone
    ClipEntityKind.URL -> Icons.Outlined.Link
    ClipEntityKind.PLACE -> Icons.Outlined.Place
}

/**
 * The parts found in a clip, in sections by kind (#472): links, phone numbers,
 * codes. A press on one pastes it, or copies it with "Copy picked parts" on.
 */
@Composable
private fun ClipExtractPopup(entities: List<ClipEntity>, onPick: (ClipEntity) -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        GlassPopupBackdrop(kb, FrostSurface.MENUS)
        Surface(
            shape = kb.menuShape(),
            color = kb.popupFill(FrostSurface.MENUS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(min = 200.dp, max = ClipFullTextMaxWidth)
                .frostGlass(kb, kb.menuShape(), FrostSurface.MENUS),
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = ClipFullTextMaxHeight),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // Links first, then numbers, then codes: the order the request
                // names them in, and the order they are looked at.
                val sections = listOf(ClipEntityKind.URL, ClipEntityKind.PLACE, ClipEntityKind.PHONE, ClipEntityKind.OTP)
                    .mapNotNull { kind -> entities.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it } }
                for ((kind, ofKind) in sections) {
                    item(key = "header-${kind.name}") {
                        Text(
                            stringResource(kind.sectionRes),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = kb.popupText.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(ofKind, key = { it.key }) { entity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entity) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                clipEntityIcon(kind),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = kb.accent,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                entity.value,
                                fontSize = 14.sp,
                                color = kb.popupText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A section heading in [ClipExtractPopup]. */
private val ClipEntityKind.sectionRes: Int
    get() = when (this) {
        ClipEntityKind.URL -> R.string.ime_clip_extract_links
        ClipEntityKind.PHONE -> R.string.ime_clip_extract_phones
        ClipEntityKind.OTP -> R.string.ime_clip_extract_codes
        ClipEntityKind.PLACE -> R.string.ime_clip_extract_places
    }

/**
 * One history row of the list view: the number, the clip across the full
 * width, and the pin and delete circles at the end ([buttons], #414). Text
 * gets three lines rather than a card's six — a list is for scanning many
 * clips — and a picture is a thumbnail at the start of the row rather than the
 * whole row.
 */
@Composable
private fun ClipRow(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    outlined: Boolean,
    buttons: Boolean,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    val visual = item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clipSurface(LocalKbTheme.current, item, focused, outlined || hold.picked == true, callbacks) { showInfo = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showInfo) ClipHoldPopup(item, hold, callbacks) { showInfo = false }
        hold.picked?.let { ClipPickMark(it) }
        if (number != null) ClipNumberBadge(number)
        rememberFloatingAppIcon(item.sourcePackage, ClipSourceIconDp)?.let { ClipSourceIcon(it, item.sourceApp) }
        if (visual) {
            // Width-bound, so the picture keeps its own shape at thumbnail
            // size, and height-capped so a portrait shot stays a row.
            Box(
                modifier = Modifier
                    .width(ListThumbnailWidth)
                    .heightIn(max = ListThumbnailMaxHeight)
                    .clip(RoundedCornerShape(8.dp)),
            ) { ClipBody(item, maxLines = lines, hold = hold) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (item.kind == ClipKind.IMAGE) R.string.ime_clip_type_image else R.string.ime_clip_type_video,
                    ),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                ClipBody(item, maxLines = lines, hold = hold)
                if (item.tags.isNotEmpty()) ClipTagsLine(item.tags, Modifier.padding(top = 2.dp))
                if (item.kind == ClipKind.HTML && hold.typeTag) {
                    Text(
                        stringResource(R.string.ime_clip_type_rich_text),
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        }
        val icons = hold.icons && hold.entities.isNotEmpty()
        val shortcut = clipShortcut(item, hold)
        if (buttons || icons || shortcut) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icons) ClipEntityIcons(hold.entities, callbacks)
                if (buttons) ClipActions(item, hold, callbacks) else if (shortcut) ClipShortcutButton(item, hold, callbacks)
            }
        }
    }
}

/** A clip's tags (#612), on one line under its body, each with its `#`, in the accent. */
@Composable
private fun ClipTagsLine(tags: List<String>, modifier: Modifier = Modifier) {
    Text(
        tags.joinToString("  ") { "#$it" },
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = LocalKbTheme.current.accent,
        modifier = modifier,
    )
}

/** A clip's time label, in the rich-text tag's small muted type. */
@Composable
private fun ClipTimeText(time: String, modifier: Modifier = Modifier) {
    Text(
        time,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The time [item] shows under [label] at [now], or null for none: how long ago
 * it was copied, or how long it has left by the same rule the store prunes by
 * ([expiresAt]). A pinned clip, or one history keeps forever, has no time left
 * to show.
 */
@Composable
private fun clipTimeText(item: ClipItem, label: ClipTimeLabel, clipboard: ClipboardSettings, now: Long): String? =
    when (label) {
        ClipTimeLabel.OFF -> null
        ClipTimeLabel.COPIED -> android.text.format.DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            now,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        ClipTimeLabel.EXPIRES -> {
            val sensitiveLeash = if (clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
                clipboard.sensitiveExpiryMinutes * MinuteMs
            } else {
                0L
            }
            item.expiresAt(clipboard.expiryHours * 60 * MinuteMs, sensitiveLeash)?.let { at ->
                val minutes = ((at - now).coerceAtLeast(0L) + MinuteMs - 1) / MinuteMs
                when {
                    minutes < 60 -> minutes.coerceAtLeast(1L).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_minutes, it, it)
                    }
                    minutes < 48 * 60 -> (minutes / 60).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_hours, it, it)
                    }
                    else -> (minutes / (24 * 60)).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_days, it, it)
                    }
                }
            }
        }
    }

private const val MinuteMs = 60_000L

/** How often the time labels are brought up to date while the panel is open. */
private const val ClipTimeTickMs = 30_000L

/** The history's bottom padding while the Undo bar covers its last few dp. */
internal val UndoBarClearance = 64.dp

/** How big a picture is drawn in a list row. */
private val ListThumbnailWidth = 96.dp
private val ListThumbnailMaxHeight = 80.dp

/** A pinned clip's outline, when that setting is on: thin, a hairline over the card's own edge. */
private val PinnedOutlineWidth = 1.5.dp

/** The unpinned / pinned tabs' height. */
private val ClipTabHeight = 32.dp

/**
 * The grid / list switch. Its icon is the view a tap switches *to*, the way a
 * toolbar's view toggle reads, and it stands down with the history: with no
 * clips there is nothing to lay out either way.
 */
@Composable
private fun ClipboardViewField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shown = state.clipboardItems.isNotEmpty()
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = if (shown) 1 else 0,
        columns = 1,
        onActivate = { callbacks.actions.onViewToggle() },
    )
    if (!shown) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ClipboardViewToggle(
            list = session.list,
            focused = state.focusedIndex(FocusRegion.ACTIONS) == 0,
            onToggle = callbacks.actions.onViewToggle,
        )
    }
}

/** The switch itself, a square in the search pill's own card style. */
@Composable
internal fun ClipboardViewToggle(
    list: Boolean,
    focused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val description = stringResource(
        if (list) R.string.ime_clipboard_view_grid_desc else R.string.ime_clipboard_view_list_desc,
    )
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                // Square, and no taller than the pill beside it: a strip row is
                // shorter than 36 dp at small key heights, and the cell wins.
                .heightIn(max = 36.dp)
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (list) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                contentDescription = description,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The clip editor: a dialog in the panel's place, the keys back beneath it.
 *
 * Not a real text field — an input method has none of its own to raise — so
 * the keys type into the service's draft the way they type into the search
 * pill, and this draws that draft with its caret. Enter is a line break;
 * Save and Cancel are the dialog's own, and Back cancels too.
 */
@Composable
internal fun ClipEditDialog(
    state: KeyboardUiState,
    edit: ClipEdit,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier,
) {
    // Nothing else in the panel is on screen, so no other region may keep a
    // ring on a card or a chip that is not drawn.
    for (region in listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS)) {
        PanelFocusTarget(panel = PanelMode.CLIPBOARD, region = region, count = 0, columns = 1, onActivate = {})
    }
    // Cancel and Save, for a physical keyboard, whose Enter is a line break here.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 2,
        columns = 2,
        onActivate = { index -> if (index == 0) actions.onEditCancel() else actions.onEditSave() },
    )
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val kb = LocalKbTheme.current
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popupFill(FrostSurface.MENUS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = kb.popupText.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(if (edit.tags) R.string.ime_clip_tags_title else R.string.ime_clip_edit_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = kb.popupText,
                        )
                        // Only once the draft has moved: until then nothing is lost.
                        if (!edit.tags && edit.rich && edit.draft.trim() != edit.original) {
                            Text(
                                stringResource(R.string.ime_clip_edit_rich_note),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = kb.popupText.copy(alpha = 0.6f),
                            )
                        }
                    }
                    TextButton(
                        onClick = actions.onEditCancel,
                        modifier = Modifier.focusRing(focused == 0, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(
                        onClick = actions.onEditSave,
                        // Saving an empty list takes the tags off (#612).
                        enabled = edit.tags || edit.draft.isNotBlank(),
                        modifier = Modifier.focusRing(focused == 1, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(R.string.ime_clip_edit_save), fontWeight = FontWeight.SemiBold) }
                }
                val fieldShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(fieldShape)
                        .frostGlass(kb, fieldShape, FrostSurface.CHIPS)
                        .background(kb.chip)
                        .chipBorder(kb, fieldShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    ClipEditText(
                        text = edit.draft,
                        placeholder = stringResource(
                            if (edit.tags) R.string.ime_clip_tags_placeholder else R.string.ime_clip_edit_placeholder,
                        ),
                        textColor = MaterialTheme.colorScheme.onSurface,
                        placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The tags other clips already carry, a press away (#612),
                // so one spelling is used for one group.
                if (edit.tags) {
                    val have = remember(edit.draft) {
                        com.wasimaster.wmkeyboard.core.clipboard.parseClipTags(edit.draft).map { it.lowercase() }.toSet()
                    }
                    val offered = remember(state.clipboardItems, have) {
                        clipTagsIn(state.clipboardItems).filter { it.lowercase() !in have }
                    }
                    if (offered.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.padding(top = 6.dp, end = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(offered, key = { it }) { tag ->
                                ClipFilterChip(label = "#$tag", selected = false, icon = Icons.Outlined.Sell) {
                                    actions.onTagAdd(tag)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The draft with its caret, wrapped over as many lines as it takes and
 * scrolled to keep the caret in sight. A tap puts the caret under the finger.
 *
 * The one-line [SearchQueryText] asks the layout for an x; this asks it for
 * the caret's whole rectangle, which is what finds the right line. Both only
 * ever ask a layout of *this* text, never one a frame stale (#252).
 *
 * Shared with the AI panel's chat composer (#280), the keyboard's other
 * free-text field. That one passes a [modifier] that wraps its lines up to a
 * cap, where the clip editor fills the room it is given.
 */
@Composable
internal fun ClipEditText(
    text: String,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val handle = LocalCaptureCaret.current
    val caret = handle.at.coerceIn(0, text.length)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val fontSize = 14.sp
    // A selection in the draft (#352), as the one-line fields have it.
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    val selecting = handle.hasSelection && handle.selectionEnd <= text.length
    val latestHandle by rememberUpdatedState(handle)
    // Paste offered at the caret by a long press on no word, or on the empty
    // draft (#434).
    var caretBar by remember(text) { mutableStateOf(false) }
    // The box the text is set in reaches the bottom of the field, not only of
    // the text (#612): a press on the empty part under a short clip, or in an
    // empty one, landed on nothing, so a long press there to paste did
    // nothing at all. There, the caret goes to the end and Paste is offered.
    // The focus switch sent the keys to the app's field (#665): faded, no
    // caret, and a tap on it (the service's caret tap) takes them back.
    val toApp = handle.toApp
    val switchShown = handle.onFocusSwitch != null
    BoxWithConstraints(modifier = modifier) {
        val fieldHeight = minHeight
        Box(
            modifier = Modifier
                .then(if (switchShown) Modifier.padding(end = FieldSwitchRoom) else Modifier)
                .then(if (toApp) Modifier.alpha(FieldToAppAlpha) else Modifier)
                .verticalScroll(scroll),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = fieldHeight)
                    .pointerInput(text) {
                        detectTapGestures(
                            onLongPress = { position ->
                                val result = layout?.takeIf { it.layoutInput.text.text == text }
                                caretBar = if (result != null && position.y > result.size.height) {
                                    latestHandle.onCaretTap(text.length)
                                    true
                                } else {
                                    !fieldLongPress(text, layout, position, latestHandle)
                                }
                            },
                            // A double tap selects the word under it (#612), as
                            // in any field; it only ever worked by luck, when the
                            // second tap happened to land as a long press.
                            onDoubleTap = { position ->
                                caretBar = !fieldLongPress(text, layout, position, latestHandle)
                            },
                        ) { position ->
                            caretBar = false
                            layout?.takeIf { it.layoutInput.text.text == text }
                                ?.let { latestHandle.onCaretTap(it.getOffsetForPosition(position)) }
                        }
                    },
            ) {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = fontSize,
                    onTextLayout = {
                        layout = it
                        overlay?.moved(owner)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            owner.coordinates = it
                            overlay?.moved(owner)
                        }
                        .selectionHighlight(
                            text,
                            if (selecting) handle.selectionStart else 0,
                            if (selecting) handle.selectionEnd else 0,
                            LocalKbTheme.current.accent.copy(alpha = HighlightAlpha),
                        ) { layout },
                )
                PublishFieldSelection(
                    owner = owner,
                    text = text,
                    active = !toApp,
                    handle = handle,
                    coordinates = { owner.coordinates },
                    layout = { layout?.takeIf { it.layoutInput.text.text == text } },
                    caretBar = caretBar,
                    onCaretBarDismiss = { caretBar = false },
                )
                if (text.isEmpty()) {
                    Text(
                        text = if (toApp) stringResource(R.string.ime_capture_focus_app_hint) else placeholder,
                        color = placeholderColor,
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                val result = layout?.takeIf { it.layoutInput.text.text == text }
                // No caret while a span is selected: the highlight marks where typing lands.
                if (result != null && !selecting && !toApp) {
                    val rect = result.getCursorRect(caret.coerceIn(0, result.layoutInput.text.length))
                    Box(
                        Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) },
                    ) {
                        EditCaret(
                            color = textColor,
                            height = with(density) { rect.height.toDp() },
                            restartKey = caret to text,
                        )
                    }
                    // Keep the caret's line in view: typing at the end of a long
                    // clip, or a newline, would otherwise run off the bottom.
                    LaunchedEffect(rect.top, rect.bottom, scroll.viewportSize, scroll.maxValue) {
                        val viewport = scroll.viewportSize
                        if (viewport <= 0) return@LaunchedEffect
                        val margin = with(density) { 4.dp.toPx() }
                        val want = when {
                            rect.top - margin < scroll.value -> rect.top - margin
                            rect.bottom + margin > scroll.value + viewport -> rect.bottom + margin - viewport
                            else -> return@LaunchedEffect
                        }
                        scroll.scrollTo(want.roundToInt().coerceIn(0, scroll.maxValue))
                    }
                }
            }
        }
        // The focus switch (#665), in the corner the text leaves it.
        FieldFocusSwitch(handle, Modifier.align(Alignment.TopEnd))
    }
}

/** The room [ClipEditText] keeps clear down its end for the focus switch. */
private val FieldSwitchRoom = 32.dp

/** The editor's blinking bar; held solid under reduce motion, like the search caret. */
@Composable
private fun EditCaret(color: Color, height: androidx.compose.ui.unit.Dp, restartKey: Any?) {
    val reduceMotion = LocalKbTheme.current.reduceMotion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, reduceMotion) {
        visible = true
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(height)
            .background(if (visible) color else Color.Transparent),
    )
}

/** The bar over the Reorder view (#612): what to do, and Done. */
@Composable
private fun ClipReorderBar(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ClipTabHeight + 10.dp)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Reorder,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.ime_clip_reorder_hint),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) {
            Text(stringResource(R.string.ime_clip_reorder_done), fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The Reorder view (#612): the clips as the history shows them — its tab,
 * its filter, newest or oldest first — two lines each, with a picture's
 * thumbnail where it has one. A clip is dragged by its handle at once, or
 * from anywhere on it after a press and hold, and a button sends it to the
 * top. It stays where it is put: the next copy lands under a clip dragged to
 * the top rather than over it. With pinned clips grouped, a clip moves inside
 * its own group; with them kept in place, anywhere.
 *
 * Rows are one fixed height, so where a drag lands is plain arithmetic, and
 * the list itself never moves under the finger: the rows in between step
 * aside, and the move is made once, on the drop, between the clips drawn
 * either side of where it landed.
 */
@Composable
private fun ClipReorderList(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    onPlace: (ClipItem, Long?, Long?) -> Unit,
) {
    // The history's own focus region stands down: nothing here pastes.
    PanelFocusTarget(panel = PanelMode.CLIPBOARD, count = 0, columns = 1, onActivate = {})
    val items = session.shownItems
    val latest by rememberUpdatedState(items)
    val oldestFirst = session.oldest.value
    val grouped = !session.pinsInPlace
    val density = LocalDensity.current
    val step = with(density) { (ReorderRowHeight + ReorderRowGap).toPx() }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    // The rows a clip may cross: its own pinned or unpinned run, or all of them.
    fun bounds(list: List<ClipItem>, at: Int): IntRange {
        if (!grouped) return list.indices
        val pinned = list[at].pinned
        return list.indexOfFirst { it.pinned == pinned }..list.indexOfLast { it.pinned == pinned }
    }
    // The clip at [at] dropped at [to]: placed between its new neighbours,
    // newer first, as the store sorts. Oldest first turns the list over.
    fun place(list: List<ClipItem>, at: Int, to: Int) {
        val item = list[at]
        val others = list.filterIndexed { index, _ -> index != at }
        fun neighbour(index: Int) = others.getOrNull(index)?.takeIf { !grouped || it.pinned == item.pinned }
        val above = neighbour(to - 1)
        val below = neighbour(to)
        if (above == null && below == null) return
        onPlace(item, (if (oldestFirst) below else above)?.id, (if (oldestFirst) above else below)?.id)
    }
    val from = dragId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
    val target = if (from < 0) -1 else (from + (dragOffset / step).roundToInt()).coerceIn(bounds(items, from))
    val reduceMotion = state.settings.reduceMotion
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(ReorderRowGap),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val dragging = item.id == dragId
            val shift = when {
                from < 0 || dragging -> 0f
                index in (from + 1)..target -> -step
                index in target until from -> step
                else -> 0f
            }
            val shown by androidx.compose.animation.core.animateFloatAsState(
                targetValue = shift,
                animationSpec = if (reduceMotion) tween(0) else tween(140),
                label = "reorderShift",
            )
            val start = {
                dragId = item.id
                dragOffset = 0f
            }
            val drop = {
                val list = latest
                val at = list.indexOfFirst { it.id == item.id }
                if (at >= 0) {
                    val to = (at + (dragOffset / step).roundToInt()).coerceIn(bounds(list, at))
                    if (to != at) place(list, at, to)
                }
                dragId = null
                dragOffset = 0f
            }
            val cancel = {
                dragId = null
                dragOffset = 0f
            }
            ClipReorderRow(
                item = item,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else shown }
                    .then(if (dragging) Modifier.shadow(6.dp, LocalKbTheme.current.cardShape()) else Modifier)
                    // The whole row after a hold (#612): a handle alone was a
                    // small target for a finger aiming at one clip of many.
                    .pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start() },
                            onDragEnd = { drop() },
                            onDragCancel = { cancel() },
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        }
                    },
                handle = Modifier.pointerInput(item.id) {
                    detectDragGestures(
                        onDragStart = { start() },
                        onDragEnd = { drop() },
                        onDragCancel = { cancel() },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                    }
                },
                onTop = {
                    val list = latest
                    val at = list.indexOfFirst { it.id == item.id }
                    if (at >= 0) place(list, at, bounds(list, at).first)
                },
                first = index == bounds(items, index).first,
            )
        }
    }
    // Leaving the panel's visit mid-drag must not leave a row floating.
    LaunchedEffect(session.reordering.value) { if (!session.reordering.value) dragId = null }
}

/** One row of the Reorder view: the handle, the clip on two lines, and To the top. */
@Composable
private fun ClipReorderRow(
    item: ClipItem,
    modifier: Modifier,
    handle: Modifier,
    onTop: () -> Unit,
    first: Boolean,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ReorderRowHeight)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = handle
                .fillMaxHeight()
                .width(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = stringResource(R.string.ime_clip_reorder_handle_desc),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = stringResource(R.string.ime_clipboard_tab_pinned),
                modifier = Modifier.padding(end = 6.dp).size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // The picture itself rather than the word "Image" (#612).
        if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(ReorderRowHeight - 12.dp)
                    .clip(RoundedCornerShape(6.dp)),
            ) { ClipBody(item, maxLines = 1, hold = ReorderHold) }
        }
        Text(
            text = when {
                item.sensitive -> "•".repeat(8)
                item.kind == ClipKind.IMAGE -> stringResource(R.string.ime_clip_type_image)
                item.kind == ClipKind.VIDEO -> stringResource(R.string.ime_clip_type_video)
                item.kind.isUriBacked -> item.fileName.orEmpty().ifBlank { item.text }
                else -> remember(item.text) { clipPreviewText(item.text, 2).replace('\n', ' ') }
            },
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Already first in its group: nowhere higher to send it.
        if (!first) {
            val top = stringResource(R.string.ime_clip_reorder_top_desc)
            IconHint(top) {
                IconButton(onClick = onTop, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.VerticalAlignTop,
                        contentDescription = top,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

/** How a thumbnail in the Reorder view is drawn: a picture body and nothing else. */
private val ReorderHold = ClipHold(holdDelete = false, holdPin = false, ocr = false)

/** The Reorder view's row height and the gap between rows; together, one step of a drag. */
private val ReorderRowHeight = 60.dp
private val ReorderRowGap = 4.dp

/**
 * Combine (#612): the clips picked in the panel joined into one, by a rule the
 * keys type into the way they type into the clip editor. The clips stay in
 * view at the top with the part each gives marked, so a pattern can be worked
 * out against them. Then the boxes: what goes between the clips, a prefix, a
 * pattern that keeps only the matching part of each clip, and what each match
 * becomes. The result shows under them and can be edited by hand, and
 * Combine adds it to the top of the history, deleting the clips joined when
 * that switch is on. The rule is remembered for the next time.
 */
@Composable
internal fun ClipCombineDialog(
    state: KeyboardUiState,
    combine: ClipCombineDraft,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier,
) {
    for (region in listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS)) {
        PanelFocusTarget(panel = PanelMode.CLIPBOARD, region = region, count = 0, columns = 1, onActivate = {})
    }
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 2,
        columns = 2,
        onActivate = { index -> if (index == 0) actions.onCombineCancel() else actions.onCombineSave() },
    )
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val kb = LocalKbTheme.current
    val texts = remember(combine.ids, state.clipboardItems) {
        val byId = state.clipboardItems.associateBy { it.id }
        combine.ids.mapNotNull { id -> byId[id]?.takeIf { it.kind.isTextual }?.text }
    }
    val patternCompiles = remember(combine.append) {
        com.wasimaster.wmkeyboard.core.clipboard.ClipCombine.appendPattern(combine.append) != null
    }
    val badPattern = combine.append.isNotEmpty() && !patternCompiles
    val ruled = remember(texts, combine.joiner, combine.base, combine.append, combine.template) {
        com.wasimaster.wmkeyboard.core.clipboard.ClipCombine.combine(
            texts, combine.joiner, combine.base, combine.append, combine.template,
        )
    }
    val ranges = remember(texts, combine.append) {
        com.wasimaster.wmkeyboard.core.clipboard.ClipCombine.matchedRanges(texts, combine.append)
    }
    val result = combine.result ?: ruled
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popupFill(FrostSurface.MENUS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.ime_clip_combine_title, texts.size, texts.size),
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = kb.popupText,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = actions.onCombineCancel,
                        modifier = Modifier.focusRing(focused == 0, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(
                        onClick = actions.onCombineSave,
                        enabled = !result.isNullOrBlank(),
                        modifier = Modifier.focusRing(focused == 1, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(R.string.ime_clip_combine), fontWeight = FontWeight.SemiBold) }
                }
                // The clips themselves, out of the scrolling part (#612): the
                // pattern is written against them, and they used to be gone
                // from view the moment one was being typed.
                ClipCombineOriginals(texts, ranges, Modifier.padding(end = 6.dp, bottom = 6.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ClipCombineBox(
                        label = stringResource(R.string.ime_clip_combine_joiner),
                        text = combine.joiner,
                        focused = combine.focused == ClipCombineField.JOINER,
                        showWhitespace = true,
                        onFocus = { actions.onCombineFocus(ClipCombineField.JOINER) },
                    )
                    // The joiners most clips want, a press away from typing them.
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ClipCombineJoiners, key = { it.first }) { (labelRes, joiner) ->
                            ClipFilterChip(
                                label = stringResource(labelRes),
                                selected = combine.joiner == joiner,
                            ) { actions.onCombineJoiner(joiner) }
                        }
                    }
                    ClipCombineBox(
                        label = stringResource(R.string.ime_clip_combine_base),
                        text = combine.base,
                        focused = combine.focused == ClipCombineField.BASE,
                        onFocus = { actions.onCombineFocus(ClipCombineField.BASE) },
                    )
                    ClipCombineBox(
                        label = stringResource(R.string.ime_clip_combine_append),
                        text = combine.append,
                        focused = combine.focused == ClipCombineField.APPEND,
                        monospace = true,
                        onFocus = { actions.onCombineFocus(ClipCombineField.APPEND) },
                    )
                    ClipCombineBox(
                        label = stringResource(R.string.ime_clip_combine_template),
                        text = combine.template,
                        focused = combine.focused == ClipCombineField.TEMPLATE,
                        monospace = true,
                        onFocus = { actions.onCombineFocus(ClipCombineField.TEMPLATE) },
                    )
                    // The result scrolls rather than being cut at four lines,
                    // and a press edits it by hand (#612).
                    val editingResult = combine.focused == ClipCombineField.RESULT
                    if (editingResult || (result != null && !badPattern)) {
                        ClipCombineBox(
                            label = stringResource(
                                if (combine.result != null) R.string.ime_clip_combine_result_edited
                                else R.string.ime_clip_combine_result,
                            ),
                            text = result.orEmpty(),
                            focused = editingResult,
                            tall = true,
                            onFocus = { actions.onCombineFocus(ClipCombineField.RESULT) },
                        )
                    } else {
                        Text(
                            stringResource(
                                if (badPattern) R.string.ime_clip_combine_bad_pattern else R.string.ime_clip_combine_no_match,
                            ),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                    ClipCombineDeleteSwitch(combine.deleteOriginals, actions.onCombineDeleteToggle)
                }
            }
        }
    }
}

/**
 * The clips being combined, one or two lines each, with the part each gives
 * marked in the accent (#612) and a clip the pattern misses dimmed. Scrolls
 * under a ceiling, so a dozen clips do not push the boxes off the panel.
 */
@Composable
private fun ClipCombineOriginals(texts: List<String>, ranges: List<IntRange?>, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.ime_clip_combine_originals),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = kb.popupText.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ClipCombineOriginalsMaxHeight)
                .clip(shape)
                .background(kb.chip.copy(alpha = 0.6f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val accent = kb.accent
            texts.forEachIndexed { index, text ->
                val range = ranges.getOrNull(index)
                val shown = remember(text, range, accent) {
                    val head = clipPreviewText(text, 2)
                    buildAnnotatedString {
                        append(head)
                        if (range != null && range.first < head.length) {
                            addStyle(
                                SpanStyle(background = accent.copy(alpha = 0.28f), fontWeight = FontWeight.Medium),
                                range.first,
                                minOf(range.last + 1, head.length),
                            )
                        }
                    }
                }
                Text(
                    shown,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = kb.popupText.copy(alpha = if (range == null) 0.45f else 1f),
                )
            }
        }
    }
}

/** How tall [ClipCombineOriginals] grows before it scrolls. */
private val ClipCombineOriginalsMaxHeight = 84.dp

/** Combine's "Delete these clips after combining" (#612), remembered for the next time. */
@Composable
private fun ClipCombineDeleteSwitch(on: Boolean, onToggle: () -> Unit) {
    val kb = LocalKbTheme.current
    val label = stringResource(R.string.ime_clip_combine_delete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                selected = on
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            color = kb.popupText,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

/** Combine's ready-made joiners: their names, and what each puts between the clips. */
private val ClipCombineJoiners: List<Pair<Int, String>> = listOf(
    R.string.ime_clip_joiner_space to " ",
    R.string.ime_clip_joiner_newline to "\n",
    R.string.ime_clip_joiner_comma to ", ",
    R.string.ime_clip_joiner_semicolon to "; ",
    R.string.ime_clip_joiner_bar to " | ",
)

/**
 * One of Combine's boxes: its label, then the text, with the caret while the
 * keys type into it, or a press to make them. [showWhitespace] draws a space
 * and a line break as marks, so a joiner of one space is not an empty box. A
 * long pattern wraps onto more lines instead of scrolling sideways (#612),
 * up to a ceiling; [tall] gives the result a higher one.
 */
@Composable
private fun ClipCombineBox(
    label: String,
    text: String,
    focused: Boolean,
    onFocus: () -> Unit,
    showWhitespace: Boolean = false,
    monospace: Boolean = false,
    tall: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(8.dp)
    val ceiling = if (tall) 140.dp else 88.dp
    Column {
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = kb.popupText.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                // The accent ring marks the box the keys type into; none while
                // the focus switch has sent them to the app's field (#665).
                .then(
                    if (focused && !LocalCaptureCaret.current.toApp) {
                        Modifier.border(1.5.dp, kb.accent, shape)
                    } else {
                        Modifier.chipBorder(kb, shape)
                    },
                )
                .then(if (focused) Modifier else Modifier.clickable(onClick = onFocus))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (focused) {
                ClipEditText(
                    text = text,
                    placeholder = "",
                    textColor = MaterialTheme.colorScheme.onSurface,
                    placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = ceiling),
                )
            } else {
                Text(
                    if (showWhitespace) text.replace(" ", "␣").replace("\n", "⏎") else text,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = ceiling)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
