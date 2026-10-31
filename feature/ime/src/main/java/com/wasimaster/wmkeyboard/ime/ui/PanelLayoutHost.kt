package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.settings.ClipPanelExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.layoutKind
import com.wasimaster.wmkeyboard.ime.R

/**
 * The panels that are layouts now (issue #63), each hosted here: the grid the
 * user laid out, filled to the key area's height, with the panel's own
 * components in its field cells and its search mode left exactly as it was.
 *
 * The callbacks ride in one object so `KeyboardBody` builds it once from the
 * lambdas it already has and nothing new travels through `KeyboardScreen`'s
 * argument list, which sits under the JVM's method-size ceiling.
 */
@Immutable
internal class PanelLayoutCallbacks(
    val onKey: (Key) -> Unit,
    val onText: (String) -> Unit,
    val onCursorMove: (Int) -> Unit,
    val onLayoutSelect: (String) -> Unit,
    val onPanelChange: (PanelMode) -> Unit,
    val emoji: EmojiFieldCallbacks,
    val clipboard: ClipboardFieldCallbacks,
    val trackpad: TrackpadFieldCallbacks,
    /** The GIF and sticker panels' height bar let go: dp over the keyboard's, for the three media panels (#537). */
    val onMediaPanelHeight: (Int) -> Unit = {},
)

/**
 * What the trackpad surface reaches (issue #39). Every caret move is an edit
 * key through [onKey], the path the text-editing panel's own keys take, so it
 * arrives at the service's `onTextEdit` with the selection mode it already
 * honours; the two-finger tap is a real space key for the same reason. The
 * hold rides the Selection mode tool's hold callback: on at the long press,
 * off at the release, the selection left standing. [onCaretDrag] brackets a
 * drag for the caret magnifier (discussion #303), the same way.
 */
@Immutable
internal class TrackpadFieldCallbacks(
    private val onKey: (Key) -> Unit,
    val onSelectionHold: (Boolean) -> Unit,
    val onCaretDrag: (Boolean) -> Unit = {},
) {
    fun onEdit(op: TextEditAction) = onKey(Key("", action = KeyAction.Edit(op)))
    fun onSpace() = onKey(Key(" ", action = KeyAction.Space))
}

/**
 * The layout the keyboard draws for [kind]: the typing layout's own grid when
 * it carries one (issue #63, per layout), else the user's shared panel layout,
 * else the shipped set. The one place this is decided; the service's
 * persistence check reads it too.
 */
internal fun KeyboardUiState.panelLayout(kind: PanelKind): PanelLayoutSpec {
    layouts.panels[kind]?.let { return it }
    val shared = panelLayouts[kind]
    // The shared map carries the shipped grid for a panel the user never
    // edited; only a grid of the user's own outranks what follows.
    if (shared != null && shared != BuiltInPanelLayouts.default(kind)) return shared
    if (kind == PanelKind.NUMPAD) {
        // Issue #55 kept: a layout that authored its own Number layer is drawn
        // on the pad. Below that, the shipped pad in the digit order the
        // Numpad tool's setting asks for.
        layouts.number?.let { number ->
            return PanelLayoutSpec(
                PanelKind.NUMPAD,
                LayerSpec(rows = number.rows, rowHeights = number.rowHeights, flickHints = number.flickHints),
                appearance = number.appearance,
            )
        }
        return BuiltInPanelLayouts.numpad(calculator = settings.numpadCalculatorLayout)
    }
    // The shipped emoji panel with the switch to GIFs and stickers where the
    // setting puts it (issue #366), or left out while there is nowhere to switch.
    if (kind == PanelKind.EMOJI) return BuiltInPanelLayouts.emoji(mediaSwitcherPlacement(settings))
    // GIFs and stickers the user never laid out end the way the emoji panel
    // does, whatever shape that is (#538).
    if (kind == PanelKind.GIF || kind == PanelKind.STICKER || kind == PanelKind.EMOJI_MIX) {
        return mediaPanelLayout(kind, panelLayout(PanelKind.EMOJI), mediaSwitcherPlacement(settings))
    }
    // The shipped clipboard with the abc / space / enter row its setting adds
    // (#615), the way the emoji panel takes its switch placement.
    if (kind == PanelKind.CLIPBOARD) return BuiltInPanelLayouts.clipboard(keyRow = settings.clipboard.keyRow)
    return shared ?: BuiltInPanelLayouts.default(kind)
}

/**
 * [this] shipped field keypad with its backspace and enter moved to where the
 * user put them on their own Numpad (issue #508). A phone, number or date field
 * opens its own pad rather than the Numpad tool's, and someone who swapped
 * backspace and enter on the tool kept hitting the wrong one of the two the
 * moment a dialer field opened the stock pad.
 *
 * Only the two action keys travel, each by trading places with whatever key of
 * the pad sits where the user's has it; everything a field pad carries for its
 * kind (the dialer's * # +, a date's separators) stays on the pad. A Numpad
 * the user never laid out moves nothing, which keeps the Number pad's
 * deliberately low backspace where it is, and a key placed outside the pad's
 * four by four stays put too.
 */
internal fun KeyboardLayout.withNumpadActionKeys(state: KeyboardUiState): KeyboardLayout {
    val numpad = state.panelLayout(PanelKind.NUMPAD)
    if (numpad == BuiltInPanelLayouts.numpad(calculator = state.settings.numpadCalculatorLayout)) return this
    val grid = rows.map { it.toMutableList() }
    for (action in NumpadActionKeys) {
        val (row, col) = numpad.grid.rows.positionOf(action) ?: continue
        val (fromRow, fromCol) = grid.positionOf(action) ?: continue
        if (row !in grid.indices || col !in grid[row].indices) continue
        if (row == fromRow && col == fromCol) continue
        val moving = grid[fromRow][fromCol]
        grid[fromRow][fromCol] = grid[row][col]
        grid[row][col] = moving
    }
    return if (grid == rows) this else copy(rows = grid)
}

/** The keys [withNumpadActionKeys] moves, backspace first. */
private val NumpadActionKeys = listOf(KeyAction.Delete, KeyAction.Enter)

/** Row and column of the first key here firing [action], or null. */
private fun List<List<Key>>.positionOf(action: KeyAction): Pair<Int, Int>? {
    forEachIndexed { r, row ->
        val c = row.indexOfFirst { it.action == action }
        if (c >= 0) return r to c
    }
    return null
}

/**
 * The theme the grid on screen asks for (issue #61, and #63's panels): with
 * no panel open, the typing grid's — a layer's, else its layout's — else
 * null for whatever the settings say. With a panel open, the panel layout's
 * own theme, else the *layout's*, never the typing layer's: a panel with no
 * theme of its own draws in its layout's, so a themed layout's panels match
 * it unless told otherwise, but a theme given to the letters layer alone is
 * that layer's and stops at its edge (issue #196). That is the rule the
 * editor's panel tab previews with, and the keyboard has to agree with it.
 *
 * [layout] is the typing grid when the caller already has it built; without
 * it the grid is built here.
 */
internal fun screenThemeId(state: KeyboardUiState, layout: KeyboardLayout? = null): String? {
    val panelKind = state.panel.layoutKind ?: return (layout ?: currentLayout(state)).themeId
    return state.panelLayout(panelKind).grid.themeId ?: state.layouts.themeId
}

/**
 * The first row of a panel layout when it is nothing but [strip] components —
 * the emoji panel's tabs and search pill, the clipboard's search pill and view
 * switch — the row full-bleed lifts into its header; or null.
 */
internal fun PanelLayoutSpec.leadingStrip(
    strip: Set<PanelFieldKind> = EmojiHeaderFields,
): List<Key>? {
    val row = grid.rows.firstOrNull() ?: return null
    if (grid.rows.size < 2 || row.isEmpty()) return null
    val isStrip = row.all { key -> (key.action as? KeyAction.Field)?.kind in strip }
    return if (isStrip) row else null
}

/** This layout without its first row, for when full-bleed has lifted that row into the header. */
private fun PanelLayoutSpec.withoutLeadingRow(): PanelLayoutSpec = copy(
    grid = grid.copy(
        rows = grid.rows.drop(1),
        rowHeights = grid.rowHeights?.drop(1),
    ),
)

/** The emoji components a full-bleed header can carry. */
private val EmojiHeaderFields =
    setOf(PanelFieldKind.EMOJI_TABS, PanelFieldKind.EMOJI_SEARCH, PanelFieldKind.MEDIA_TABS)

/** The clipboard components a full-bleed header can carry. */
private val ClipboardHeaderFields = setOf(PanelFieldKind.CLIPBOARD_SEARCH, PanelFieldKind.CLIPBOARD_VIEW)

/**
 * The emoji panel. Search mode keeps its compact form — the key rows return
 * beneath it, they are how the query is typed — and the layout draws the rest
 * of the time, inside the full-bleed chrome when that setting is on.
 */
@Composable
internal fun EmojiPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val kaomoji by callbacks.emoji.kaomoji.state.collectAsState()
    val session = rememberEmojiPanelSession(state, kaomoji)
    // Toggling the open panel closes it — back to the keys.
    val onClose = { callbacks.onPanelChange(PanelMode.EMOJI) }
    // The search and the layout trade places at once, as the clipboard's do
    // (#612). A cross-fade held the outgoing layout's full height while the
    // keys came back underneath, so every search, face edit and tag rename
    // grew the keyboard by a whole panel for the length of the fade and then
    // dropped it again. The layout's cells still stagger back in on their own
    // clock (see [PanelLayoutGrid]). The kaomoji tab's own boxes (its search,
    // its editor) shrink the panel the same way.
    EmojiPanelPage(state, session, callbacks, onClose, emojiPanelTyping(state))
    // A Popup overlay, so opening it never reflows the fixed-height panel.
    if (session.reorderOpen) {
        FavouritesReorderPopup(
            favourites = state.emojiFavourites,
            onConfirm = {
                session.reorderOpen = false
                callbacks.emoji.onFavouritesReorder(it)
            },
            onDismiss = { session.reorderOpen = false },
        )
    }
}

/** One page of the emoji panel: its search, or the layout the user laid out. */
@Composable
private fun EmojiPanelPage(
    state: KeyboardUiState,
    session: EmojiPanelSession,
    callbacks: PanelLayoutCallbacks,
    onClose: () -> Unit,
    search: Boolean,
) {
    if (search && state.kaomojiText != null) {
        val kaomoji by callbacks.emoji.kaomoji.state.collectAsState()
        KaomojiTypingPanel(state, kaomoji, callbacks.emoji.kaomoji, onClose)
    } else if (search) {
        EmojiSearchPanel(state, session, callbacks.emoji, onClose)
    } else {
        val spec = state.panelLayout(PanelKind.EMOJI)
        val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
            // The switch to GIFs and stickers changes panels, which is this
            // host's business rather than the emoji components'.
            if (kind == PanelFieldKind.MEDIA_TABS) {
                MediaTabsField(state, callbacks.onPanelChange)
            } else {
                EmojiField(kind, state, session, callbacks.emoji)
            }
        }
        if (state.settings.emojiFullBleed) {
            // Full-bleed spends the reclaimed toolbar row on the panel's own
            // strip, as it always has: a first row made only of the tabs and the
            // search pill moves up into the header beside the back button, and
            // the grid starts right under it. A layout that puts something else
            // in its first row keeps the plain header with the panel's name.
            val strip = spec.leadingStrip()
            if (strip != null) {
                val rest = spec.withoutLeadingRow()
                FullBleedTool(
                    state, title = "", onClose = onClose,
                    extraHeight = mediaPanelExtraHeight(state),
                    headerActions = {
                        for (key in strip) {
                            Box(
                                modifier = Modifier
                                    .weight(key.width)
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp),
                            ) { fields((key.action as KeyAction.Field).kind) }
                        }
                    },
                ) {
                    PanelLayoutGrid(state, rest, callbacks, onClose, fields, Modifier.fillMaxSize())
                }
            } else {
                FullBleedTool(
                    state,
                    stringResource(R.string.ime_tool_emoji),
                    onClose = onClose,
                    extraHeight = mediaPanelExtraHeight(state),
                ) {
                    PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
                }
            }
        } else {
            // The always-on emoji row hides while this panel is open; absorbing
            // its height keeps the keyboard from resizing on panel switches.
            val barCompensation =
                if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mediaPanelHeight(state, keyRowsHeight(state) + barCompensation)),
            ) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The clipboard panel. Searching or editing a clip hands the key rows back
 * and steps out of full-bleed, as before; otherwise the layout fills the key
 * area, inside the full-bleed chrome when that setting is on, plus however
 * much taller the height bar on top has made it (#414).
 */
@Composable
internal fun ClipboardPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val session = rememberClipboardPanelSession(state)
    val onClose = { callbacks.onPanelChange(PanelMode.CLIPBOARD) }
    // Combine (#612) takes the panel the way the editor does, keys and all.
    val combine = state.clipCombine
    if (combine != null && state.clipCombineActive) {
        ClipCombineDialog(
            state, combine, callbacks.clipboard.actions,
            modifier = Modifier
                .fillMaxWidth()
                .height(clipEditPanelHeight(state)),
        )
        return
    }
    val edit = state.clipEdit
    if (edit != null && state.clipEditActive) {
        // The keys come back underneath, as for the search, but the editor
        // keeps the history's own height rather than the search's few rows
        // (#371): a clip is read and changed here, and a note needs the room.
        ClipEditDialog(
            state, edit, callbacks.clipboard.actions,
            modifier = Modifier
                .fillMaxWidth()
                .height(clipEditPanelHeight(state)),
        )
        return
    }
    // The search and the history trade places at once (#612). They used to
    // cross-fade like the emoji panel's, but the two pages are very different
    // heights, so the keyboard grew and shrank under the fade, and both grids
    // laid out at once: the worst jump of any panel. The editor above was
    // instant already.
    if (state.clipboardSearchActive) {
        ClipboardSearchPage(state, session, callbacks)
    } else {
        ClipboardHistoryPage(state, session, callbacks, onClose)
    }
}

/**
 * The clipboard's search page: the field and a couple of result rows, the
 * keys back underneath for typing the query.
 */
@Composable
private fun ClipboardSearchPage(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: PanelLayoutCallbacks,
) {
    // The panel shrinks to its search field plus a couple of result rows,
    // and the bar on top drags it taller over the keys (#615). The toolbar
    // row is hidden too (see KeyboardBody), so the panel absorbs its
    // height the way the emoji panel's search mode does, less the row the
    // query's own suggestion strip takes below the panel (#161).
    val size = panelOverKeysSize(
        state,
        wanted = ClipboardSearchHeight + topBarHeight(state.settings) - captureStripHeight(state),
        floor = FullBleedHeaderHeight,
    )
    Column(modifier = size.probe.fillMaxWidth().height(size.height)) {
        size.bar?.invoke()
        ClipboardSearchPanel(
            state, session, callbacks.clipboard,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** The clipboard's history page: the layout, filling the key area or stacked over the keys. */
@Composable
private fun ClipboardHistoryPage(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: PanelLayoutCallbacks,
    onClose: () -> Unit,
) {
    // Stacked over the keys (#414): a short panel, the keys under it, and the
    // height bar of every panel over the keys rather than the clipboard's own.
    val stacked = clipboardStacked(state)
    // The key row setting's abc / space / enter stand down while stacked: the
    // real keys are right under the panel, and the row was history's room.
    val spec = state.panelLayout(PanelKind.CLIPBOARD).let { spec ->
        if (stacked && spec == BuiltInPanelLayouts.clipboard(keyRow = true)) BuiltInPanelLayouts.CLIPBOARD else spec
    }
    val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
        ClipboardField(kind, state, session, callbacks.clipboard)
    }
    // The strips only exist when they have something to show, as before: a
    // row holding nothing but an empty strip takes no height.
    val collapsed = buildSet {
        if (clipSearchCellParts(session).isEmpty()) add(PanelFieldKind.CLIPBOARD_SEARCH)
        // Nor does the fragment strip while stacked (#615): it took a third of
        // the short panel, and with the tabs under it the history was down to
        // a single line. The fragments are a tap on the keys toggle away.
        if (session.entities.isEmpty() || stacked) add(PanelFieldKind.CLIPBOARD_ENTITIES)
        // Nothing to lay out either way until there is a clip.
        if (state.clipboardItems.isEmpty()) add(PanelFieldKind.CLIPBOARD_VIEW)
    }
    // The height bar (#414): the panel opens this much taller than the
    // keyboard, up to what the screen can give it, and the bar on top drags it.
    val clipboard = state.settings.clipboard
    val resize = rememberClipPanelResize(clipboard.panelExtraHeightDp)
    val base = if (clipboard.fullBleed) keyRowsHeight(state) + fullBleedHiddenRows(state, macroRowAtPanelOpen(state)) else keyRowsHeight(state)
    val maxPanel = toolPanelHeight(state, wanted = base + ClipPanelExtraHeightRange.last.dp, floor = base)
    val maxExtra = (maxPanel - base).coerceAtLeast(0.dp)
    val extra = resize.shownDp(clipboard.panelExtraHeightDp).dp.coerceIn(0.dp, maxExtra)
    val maxPanelPx = with(LocalDensity.current) { maxPanel.roundToPx() }
    SideEffect { resize.maxPanelPx = maxPanelPx }
    val probe = remember(resize) { Modifier.clipPanelHeightProbe(resize) }
    // No bar where the screen leaves no room to grow: it would drag nothing.
    val bar: (@Composable () -> Unit)? = if (maxExtra < 1.dp) null else {
        { ClipPanelHeightBar(resize, extra, maxExtra, callbacks.clipboard.actions.onPanelHeight) }
    }
    val keysToggle: @Composable () -> Unit = {
        ToolCircle(
            slot = IconSlots.KEY_INPUT_METHOD_PICKER,
            description = stringResource(
                if (stacked) R.string.ime_clipboard_keys_hide_desc else R.string.ime_clipboard_keys_show_desc,
            ),
            active = stacked,
            onClick = callbacks.clipboard.actions.onKeysToggle,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
    if (clipboard.fullBleed) {
        // Full-bleed: the toolbar row becomes the back header and the
        // reclaimed rows go to the history. A first row of nothing but the
        // search pill and the view switch moves up into that header, as the
        // emoji panel's tabs do, so the history starts right under it. With
        // the pill hidden the header keeps the panel's name, and the switch
        // sits at its end.
        val strip = spec.leadingStrip(ClipboardHeaderFields)
        if (strip != null) {
            val shown = strip.filter { (it.action as KeyAction.Field).kind !in collapsed }
            val searchShown = shown.any { (it.action as KeyAction.Field).kind == PanelFieldKind.CLIPBOARD_SEARCH }
            Box(modifier = probe) {
                FullBleedTool(
                    state,
                    title = if (searchShown) "" else stringResource(R.string.ime_tool_clipboard),
                    onClose = onClose,
                    extraHeight = extra,
                    compact = stacked,
                    compactHeight = ClipStackedHeight,
                    topHandle = if (stacked) null else bar,
                    headerActions = {
                        run {
                            for (key in shown) {
                                val kind = (key.action as KeyAction.Field).kind
                                // The pill takes its share of the width; the switch
                                // is a fixed square beside it, or alone at the end,
                                // and so is the clear button with the pill hidden.
                                val cell = when {
                                    kind != PanelFieldKind.CLIPBOARD_SEARCH -> Modifier.width(HeaderToggleWidth)
                                    session.showSearch -> Modifier.weight(key.width)
                                    // The bin and the fragments button with no
                                    // pill: a square for each (#612).
                                    else -> Modifier.width(HeaderToggleWidth * clipSearchCellParts(session).size)
                                }
                                Box(
                                    modifier = cell
                                        .fillMaxHeight()
                                        // Clear of the header's edges the way its
                                        // circles are, so the pill no longer sits
                                        // on the cards scrolled up under it (#414).
                                        .padding(horizontal = 2.dp, vertical = 4.dp),
                                ) { fields(kind) }
                            }
                        }
                        keysToggle()
                    },
                ) {
                    PanelLayoutGrid(
                        state, spec.withoutLeadingRow(), callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed,
                    )
                }
            }
        } else {
            Box(modifier = probe) {
                FullBleedTool(
                    state,
                    stringResource(R.string.ime_tool_clipboard),
                    onClose = onClose,
                    extraHeight = extra,
                    compact = stacked,
                    compactHeight = ClipStackedHeight,
                    topHandle = if (stacked) null else bar,
                    headerActions = { keysToggle() },
                ) {
                    PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed)
                }
            }
        }
    } else {
        Column(
            modifier = probe
                .fillMaxWidth()
                .height(toolPanelHeight(state, wanted = base + extra, floor = base)),
        ) {
            bar?.invoke()
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed)
            }
        }
    }
}

/**
 * The clipboard's height while stacked over the keys (#414): its header, the
 * Unpinned and Pinned tabs and about two rows of cards, which is what a paste
 * between sentences needs. The bar on top adds to it (#615).
 */
private val ClipStackedHeight = 220.dp

/** The text-editing pad: keys only, filling the key area. */
@Composable
internal fun TextEditPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TEXT_EDIT) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TEXT_EDIT), callbacks, onClose,
            fields = { _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The Numpad tool's pad, a keys-only panel layout like the text-editing pad
 * (issue #63): the layout's own tab, the shared pad, the layout's Number
 * layer or the shipped pad, in that order — see [panelLayout].
 */
@Composable
internal fun NumpadPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.NUMPAD) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.NUMPAD), callbacks, onClose,
            fields = { _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The trackpad panel (issue #39): the pointing surface over whatever keys the
 * layout puts beside it, filling the key area. Never full-bleed, because the
 * toolbar has to stay for the hold gesture that opened it.
 */
@Composable
internal fun TrackpadPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TRACKPAD) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TRACKPAD), callbacks, onClose,
            fields = { kind ->
                if (kind == PanelFieldKind.TRACKPAD) TrackpadField(state.settings.trackpad, callbacks.trackpad)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The handwriting panel (#568): the writing canvas and whatever keys the
 * layout puts around it, filling the key area. The ink callbacks come straight
 * from the panel's call site rather than through [PanelLayoutCallbacks], which
 * every other panel would have to carry for this one.
 */
@Composable
internal fun HandwritingPanelHost(
    state: KeyboardUiState,
    callbacks: PanelLayoutCallbacks,
    onStroke: (HwStroke, IntSize) -> Unit,
    onUndoStroke: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    val onClose = { callbacks.onPanelChange(PanelMode.HANDWRITING) }
    val base = keyRowsHeight(state)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(toolPanelHeight(state, wanted = base + state.settings.handwritingInk.panelExtraHeightDp.dp, floor = base)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.HANDWRITING), callbacks, onClose,
            fields = { kind ->
                if (kind == PanelFieldKind.HANDWRITING_CANVAS) {
                    HandwritingCanvasField(state, onStroke, onUndoStroke, onDownloadModel, callbacks.onLayoutSelect)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * How much taller than the keyboard the emoji, GIF and sticker panels open
 * (#537): the setting, which the three share so a switch between them never
 * moves the keyboard's top edge. A full-bleed panel fits it to the screen
 * itself; [mediaPanelHeight] does that for the others.
 */
internal fun mediaPanelExtraHeight(state: KeyboardUiState): Dp =
    state.settings.emoji.panelExtraHeightDp.coerceAtLeast(0).dp

/**
 * The height of an emoji, GIF or sticker panel that is not full-bleed: the
 * [base] it has always had, plus [mediaPanelExtraHeight], fitted to the
 * screen the way every taller tool panel is (#333), and never shorter than
 * [base], so the setting can only ever add room.
 */
@Composable
internal fun mediaPanelHeight(state: KeyboardUiState, base: Dp): Dp {
    val extra = mediaPanelExtraHeight(state)
    if (extra <= 0.dp) return base
    return toolPanelHeight(state, wanted = base + extra, floor = base)
}

/** The clipboard's view switch in a full-bleed header: its square plus a little air. */
private val HeaderToggleWidth = 44.dp

/** Panel height while the clipboard search bar is capturing the keys. */
internal val ClipboardSearchHeight = 132.dp

/**
 * The clip editor's height (#371): the key area the history filled plus the
 * toolbar row, less the editor's strip below, so the editor opens as tall as
 * the panel it replaces and the keys push the keyboard up underneath it. The
 * same fit to the screen as the search's (#333) keeps a phone held sideways
 * from losing its keys to it, and it is never shorter than the search.
 */
@Composable
private fun clipEditPanelHeight(state: KeyboardUiState): Dp {
    val strip = captureStripHeight(state)
    val bar = topBarHeight(state.settings)
    return toolPanelHeight(
        state,
        wanted = maxOf(keyRowsHeight(state), ClipboardSearchHeight) + bar - strip,
        floor = FullBleedHeaderHeight,
    )
}
