package com.wasimaster.wmkeyboard.ime.floating

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInFull
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.ime.CaptureSelectionAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockCallbacks
import com.wasimaster.wmkeyboard.ime.todo.TodoCallbacks
import com.wasimaster.wmkeyboard.ime.ui.CalculatorPanel
import com.wasimaster.wmkeyboard.ime.ui.CaptureCaretHandle
import com.wasimaster.wmkeyboard.ime.ui.ConverterCallbacks
import com.wasimaster.wmkeyboard.ime.ui.CurrencyPanel
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalCaptureCaret
import com.wasimaster.wmkeyboard.ime.ui.NotesCallbacks
import com.wasimaster.wmkeyboard.ime.ui.NotesPanelHost
import com.wasimaster.wmkeyboard.ime.ui.TranslateCallbacks
import com.wasimaster.wmkeyboard.ime.ui.TranslateTool
import com.wasimaster.wmkeyboard.ime.ui.UnitConverterPanel
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockPanel
import com.wasimaster.wmkeyboard.ime.ui.todo.TodoPanel
import com.wasimaster.wmkeyboard.ime.SearchTab
import com.wasimaster.wmkeyboard.ime.calendar.CalendarCallbacks
import com.wasimaster.wmkeyboard.ime.ui.ClipboardBoxHistory
import com.wasimaster.wmkeyboard.ime.ui.ClipboardFieldCallbacks
import com.wasimaster.wmkeyboard.ime.ui.DictionaryHeaderSearchBar
import com.wasimaster.wmkeyboard.ime.ui.DictionaryPanel
import com.wasimaster.wmkeyboard.ime.ui.FullBleedHeaderHeight
import com.wasimaster.wmkeyboard.ime.ui.LocalSearchActions
import com.wasimaster.wmkeyboard.ime.ui.MediaControlPanel
import com.wasimaster.wmkeyboard.ime.ui.MediaHeaderSearchBar
import com.wasimaster.wmkeyboard.ime.ui.SearchActions
import com.wasimaster.wmkeyboard.ime.ui.WebSearchPanel
import com.wasimaster.wmkeyboard.ime.ui.WikipediaBoxPanel
import com.wasimaster.wmkeyboard.ime.ui.calendar.CalendarPanel
import com.wasimaster.wmkeyboard.ime.ui.searchAttribution
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.WebResult

/**
 * What the floating box (#1155) needs of the keyboard to run a tool's panel
 * outside it: the same callback bundles the keyboard's own panels are handed,
 * so a tool behaves the same in the box as in the keyboard.
 */
@Immutable
internal class AppletCallbacks(
    val converter: ConverterCallbacks,
    val clock: ClockCallbacks,
    val todo: TodoCallbacks,
    val notes: NotesCallbacks,
    val translate: TranslateCallbacks,
    /** Translate's text box tapped: it takes the keys, the media search box's way. */
    val onQueryTap: () -> Unit,
    /** A result's Insert: into the field when the keyboard has one, else to the clipboard. */
    val onInsert: (String) -> Unit,
    val onPrefillConsumed: () -> Unit,
    /** A panel asking for another panel: opened in the keyboard. */
    val onPanelChange: (PanelMode) -> Unit,
    val onOpenRoute: (String) -> Unit,
    /** The tool's text box, as the keyboard's own caret handle reaches it. */
    val onCaretTap: (Int) -> Unit,
    val onSelect: (Int, Int) -> Unit,
    val onSelectionAction: (CaptureSelectionAction) -> Unit,
    /** The tools that joined the box in #1265. */
    val box: AppletToolCallbacks,
    val plugins: AppletPluginCallbacks,
)

/**
 * A plugin in the box: the keyboard's own plugin calls. A plugin's text box
 * wants the keys, which are the keyboard's, so tapping one moves the plugin
 * into the keyboard ([onToKeyboard]) rather than typing over the app.
 */
@Immutable
internal class AppletPluginCallbacks(
    val onOpen: (String) -> Unit,
    val onEvent: (com.wasimaster.wmkeyboard.core.plugins.PluginEvent) -> Unit,
    val onBack: () -> Unit,
    val onToKeyboard: () -> Unit,
    val onCopy: (String) -> Unit,
)

/**
 * The clipboard's, the calendar's, the media controls', the dictionary's and
 * the two searches' callbacks for the box (#1265): the keyboard's own, with
 * the ones that type into the app's field copying instead while the keyboard
 * is down. [calendar] is built only once the box shows the calendar.
 */
@Immutable
internal class AppletToolCallbacks(
    val clipboard: ClipboardFieldCallbacks,
    val calendar: Lazy<CalendarCallbacks>,
    val onMediaPlayPause: () -> Unit,
    val onMediaNext: () -> Unit,
    val onMediaPrevious: () -> Unit,
    val onMediaSeek: (Long) -> Unit,
    val onMediaAccessRequest: () -> Unit,
    val onMediaResume: () -> Unit,
    val onDictionaryLookup: (String) -> Unit,
    val onDictionarySearchToggle: () -> Unit,
    val onDictionaryInsert: (String) -> Unit,
    val onAddToVocab: (String) -> Unit,
    val onMediaQueryClear: () -> Unit,
    val onMediaRetry: () -> Unit,
    val onWebResult: (WebResult) -> Unit,
    val onWebResultOpen: (WebResult) -> Unit,
    val onImageResult: (ImageResult) -> Unit,
    val onOpenToolSettings: (ToolbarTool) -> Unit,
    val onWikiOpen: (String) -> Unit,
    val onWikiBack: () -> Unit,
    val onWikiLoadLinks: () -> Unit,
    val onWikiLoadFull: () -> Unit,
    val search: SearchActions,
)

/** A drag on the tool's own content that nothing in it took (#1263), as [FloatingAppletFrame] reports it. */
internal enum class BodyDrag { START, MOVE, END, CANCEL }

/** What the box shows; written by [FloatingApplet]. */
internal class FloatingAppletState {
    var panel by mutableStateOf<PanelMode?>(null)
    var title by mutableStateOf("")
    var icon by mutableStateOf<ImageVector?>(null)

    /** Tucked into the left edge (true) or the right (false), as a tab; null while the box is out. */
    var tuckedLeft by mutableStateOf<Boolean?>(null)
}

/**
 * The floating box (#1155): a rounded card in the keyboard's theme with a
 * title bar to drag it by, a close button, the tool's own panel under it, and
 * a grip in the corner to resize it. Drags are read as raw screen points
 * ([onMove], [onResize]), because the window moves under the finger and
 * points measured inside it would chase themselves.
 *
 * The bar along the bottom drags it too, and so does any part of the tool a
 * drag does nothing to ([onBodyDrag], #1263). With the box's gestures on
 * (#1262) the title bar keeps only the tool's name, the buttons' jobs going
 * to throws and to a screen reader's actions, and the bottom bar gives way to
 * a grip in the corner.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FloatingAppletFrame(
    box: FloatingAppletState,
    state: KeyboardUiState,
    callbacks: AppletCallbacks,
    onMove: (MotionEvent) -> Boolean,
    onBodyDrag: (BodyDrag) -> Unit,
    onResize: (MotionEvent) -> Boolean,
    onTuck: () -> Unit,
    onTabTouch: (MotionEvent) -> Boolean,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onOpenInKeyboard: () -> Unit,
) {
    val panel = box.panel ?: return
    KeyboardThemeProvider(state.settings) {
        val tuckedLeft = box.tuckedLeft
        if (tuckedLeft != null) {
            AppletTab(box, tuckedLeft, onTabTouch, onRestore)
            return@KeyboardThemeProvider
        }
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(20.dp)
        val gestures = state.settings.summonButton.boxGestures
        val tuckLabel = stringResource(R.string.ime_applet_tuck)
        val keyboardLabel = stringResource(R.string.ime_applet_open_in_keyboard)
        val closeLabel = stringResource(R.string.ime_applet_close)
        Box(Modifier.fillMaxSize().padding(FLOATING_APPLET_SHADOW_DP.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.outlineVariant, shape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The handle: the icon and the name, and the room around
                    // them, but not the buttons, which keep their own taps.
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInteropFilter(onTouchEvent = onMove)
                            // The buttons' jobs, for a screen reader, with them hidden (#1262).
                            .semantics {
                                if (gestures) {
                                    customActions = listOf(
                                        CustomAccessibilityAction(tuckLabel) { onTuck(); true },
                                        CustomAccessibilityAction(keyboardLabel) { onOpenInKeyboard(); true },
                                        CustomAccessibilityAction(closeLabel) { onClose(); true },
                                    )
                                }
                            }
                            .padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        box.icon?.let {
                            Icon(it, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            box.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!gestures) {
                        // Out of the way into the nearer side, a tab left there to bring it back.
                        IconButton(onClick = onTuck) {
                            Icon(Icons.Outlined.Remove, contentDescription = tuckLabel, tint = colors.onSurfaceVariant)
                        }
                        // Back into the keyboard, as its panel, for a tool that wants the keys.
                        IconButton(onClick = onOpenInKeyboard) {
                            Icon(Icons.Outlined.OpenInFull, contentDescription = keyboardLabel, tint = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, contentDescription = closeLabel, tint = colors.onSurfaceVariant)
                        }
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .dragsWhereUnclaimed(onBodyDrag),
                ) {
                    // The tool's own text box takes the keyboard's caret handle,
                    // as it does inside the keyboard, while the keys are the box's.
                    val caret = if (state.panel == PanelMode.NONE && state.toolUp(panel)) {
                        CaptureCaretHandle(
                            state.captureCaretIndex(),
                            callbacks.onCaretTap,
                            anchor = state.captureAnchorIndex(),
                            onSelect = callbacks.onSelect,
                            onSelectionAction = callbacks.onSelectionAction,
                        )
                    } else {
                        CaptureCaretHandle(0, {})
                    }
                    CompositionLocalProvider(
                        LocalCaptureCaret provides caret,
                        com.wasimaster.wmkeyboard.ime.ui.LocalInFloatingBox provides true,
                    ) {
                        AppletPanel(panel, state, callbacks, onClose)
                    }
                    // With the bottom bar gone (#1262), the grip alone, over the corner.
                    if (gestures) {
                        ResizeGrip(
                            onResize,
                            Modifier
                                .align(Alignment.BottomEnd)
                                .background(colors.surface, RoundedCornerShape(topStart = 10.dp)),
                        )
                    }
                }
                if (!gestures) {
                    // The bottom bar: a drag on it moves the box (#1263), and
                    // the grip at its end resizes it from the corner.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pointerInteropFilter(onTouchEvent = onMove),
                        )
                        ResizeGrip(onResize)
                    }
                }
            }
        }
    }
}

/** The corner grip: drag it to resize the box. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResizeGrip(onResize: (MotionEvent) -> Boolean, modifier: Modifier = Modifier) {
    val resize = stringResource(R.string.ime_applet_resize)
    Icon(
        Icons.Outlined.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(end = 6.dp)
            .size(28.dp, 18.dp)
            .semantics { contentDescription = resize }
            .pointerInteropFilter(onTouchEvent = onResize),
    )
}

/**
 * Moves the box by a drag on any part of the tool that does nothing with
 * one (#1263): read after the tool's own handlers, in the main pass, so a
 * list that scrolls or a slider that slides takes its drag first, and only
 * a finger past the touch slop with its moves still unclaimed is the box's.
 * Once it is, its moves are consumed, which cancels a button pressed under
 * it. Where the finger is on screen is the window's to say, not this.
 */
private fun Modifier.dragsWhereUnclaimed(onDrag: (BodyDrag) -> Unit): Modifier = pointerInput(onDrag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val slop = viewConfiguration.touchSlop
        var travelled = Offset.Zero
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            // A second finger is the window's (two fingers move and pinch it).
            if (change == null || event.changes.size > 1) {
                if (dragging) onDrag(BodyDrag.CANCEL)
                break
            }
            if (!change.pressed) {
                if (dragging) onDrag(BodyDrag.END)
                break
            }
            if (!dragging) {
                // Taken by the tool: a scroll, a slider, a text handle.
                if (change.isConsumed) break
                travelled += change.positionChange()
                if (travelled.getDistance() > slop) {
                    dragging = true
                    change.consume()
                    onDrag(BodyDrag.START)
                }
            } else {
                change.consume()
                onDrag(BodyDrag.MOVE)
            }
        }
    }
}

/**
 * What a tucked box leaves on its edge: a tab with the tool's icon, flat
 * against the side and rounded on the other. A tap brings the box back
 * ([onTouch] reads it raw, with the drag that moves the tab along the edge);
 * a screen reader gets the same as a click.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppletTab(
    box: FloatingAppletState,
    onLeft: Boolean,
    onTouch: (MotionEvent) -> Boolean,
    onRestore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val round = 16.dp
    val shape = if (onLeft) {
        RoundedCornerShape(topEnd = round, bottomEnd = round)
    } else {
        RoundedCornerShape(topStart = round, bottomStart = round)
    }
    val label = stringResource(R.string.ime_applet_untuck, box.title)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.primaryContainer)
            .border(1.dp, colors.outlineVariant, shape)
            .semantics {
                contentDescription = label
                onClick(label) {
                    onRestore()
                    true
                }
            }
            .pointerInteropFilter(onTouchEvent = onTouch),
        contentAlignment = Alignment.Center,
    ) {
        box.icon?.let {
            Icon(it, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The tool's own panel, the one the keyboard draws for it, run in the box.
 * Only the [com.wasimaster.wmkeyboard.core.settings.AppletTools] have one here.
 */
@Composable
private fun AppletPanel(panel: PanelMode, state: KeyboardUiState, callbacks: AppletCallbacks, onClose: () -> Unit) {
    when (panel) {
        PanelMode.CALCULATOR -> CalculatorPanel(state, callbacks.onInsert, callbacks.converter)
        PanelMode.UNIT_CONVERT -> UnitConverterPanel(state, callbacks.onInsert, callbacks.converter, callbacks.onPrefillConsumed)
        PanelMode.CURRENCY -> CurrencyPanel(
            state = state,
            callbacks = callbacks.converter,
            onInsert = callbacks.onInsert,
            onPrefillConsumed = callbacks.onPrefillConsumed,
        )
        PanelMode.CLOCK -> ClockPanel(state = state, callbacks = callbacks.clock, onClose = onClose)
        PanelMode.TODO -> TodoPanel(state = state, callbacks = callbacks.todo, onClose = onClose)
        PanelMode.NOTES -> NotesPanelHost(
            state,
            callbacks.notes,
            // Notes closes itself by asking for its own panel: here that is the box.
            onPanelChange = { if (it == PanelMode.NOTES) onClose() else callbacks.onPanelChange(it) },
            onOpenRoute = callbacks.onOpenRoute,
        )
        PanelMode.TRANSLATE -> {
            // Its text box is the search box the keyboard's own search panels
            // use: while one of them is open over the keys, the box shows the
            // text it last translated and waits, its buttons idle.
            val own = state.panel == PanelMode.NONE
            TranslateTool(
                state = if (own) state else state.copy(mediaQuery = state.translate.sourceText, mediaSearchActive = false),
                callbacks = if (own) callbacks.translate else IdleTranslate,
                onClose = onClose,
                onQueryTap = if (own) callbacks.onQueryTap else ({}),
            )
        }
        // The history alone (#1265): the panel's search and its keys are the keyboard's.
        PanelMode.CLIPBOARD -> ClipboardBoxHistory(
            state = if (state.clipboardSearchActive || state.clipboardQuery.isNotEmpty()) {
                state.copy(clipboardSearchActive = false, clipboardQuery = "")
            } else {
                state
            },
            callbacks = callbacks.box.clipboard,
        )
        PanelMode.CALENDAR -> CalendarPanel(state = state, callbacks = callbacks.box.calendar.value, onClose = onClose)
        PanelMode.MEDIA_CONTROL -> MediaControlPanel(
            state = state,
            onPlayPause = callbacks.box.onMediaPlayPause,
            onNext = callbacks.box.onMediaNext,
            onPrevious = callbacks.box.onMediaPrevious,
            onSeek = callbacks.box.onMediaSeek,
            onRequestAccess = callbacks.box.onMediaAccessRequest,
            onResume = callbacks.box.onMediaResume,
        )
        PanelMode.DICTIONARY -> Column(Modifier.fillMaxSize()) {
            BoxSearchHeader {
                DictionaryHeaderSearchBar(
                    state = state,
                    onSearchToggle = callbacks.box.onDictionarySearchToggle,
                    onLookup = callbacks.box.onDictionaryLookup,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DictionaryPanel(
                    state = state,
                    onLookup = callbacks.box.onDictionaryLookup,
                    onInsert = callbacks.box.onDictionaryInsert,
                    onAddToVocab = callbacks.box.onAddToVocab,
                )
            }
        }
        // The search box is shared with the keyboard's own search panels, as
        // Translate's is: under one of them the box keeps its results and waits.
        PanelMode.WIKIPEDIA -> {
            val own = state.panel == PanelMode.NONE
            WikipediaBoxPanel(
                state = if (own) state else state.copy(mediaQuery = "", mediaSearchActive = false),
                onQueryTap = if (own) callbacks.onQueryTap else ({}),
                onRetry = callbacks.box.onMediaRetry,
                onOpen = callbacks.box.onWikiOpen,
                onBack = callbacks.box.onWikiBack,
                onLoadLinks = callbacks.box.onWikiLoadLinks,
                onLoadFull = callbacks.box.onWikiLoadFull,
                onInsert = callbacks.onInsert,
            )
        }
        PanelMode.WEB_SEARCH -> {
            val own = state.panel == PanelMode.NONE
            val shown = if (own) state else state.copy(mediaQuery = "", mediaSearchActive = false)
            Column(Modifier.fillMaxSize()) {
                BoxSearchHeader {
                    MediaHeaderSearchBar(
                        state = shown,
                        placeholder = stringResource(
                            if (shown.searchTab == SearchTab.IMAGES) R.string.ime_image_search_hint
                            else R.string.ime_web_search_hint,
                        ),
                        onQueryTap = if (own) callbacks.onQueryTap else ({}),
                        onClear = if (own) callbacks.box.onMediaQueryClear else null,
                        attribution = searchAttribution(state.settings),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalSearchActions provides callbacks.box.search) {
                        WebSearchPanel(
                            state = shown,
                            onRetry = callbacks.box.onMediaRetry,
                            onResult = callbacks.box.onWebResult,
                            onOpen = callbacks.box.onWebResultOpen,
                            onImageResult = callbacks.box.onImageResult,
                            onOpenToolSettings = callbacks.box.onOpenToolSettings,
                        )
                    }
                }
            }
        }
        PanelMode.PLUGINS -> Column(Modifier.fillMaxSize()) {
            val running = state.plugins as? com.wasimaster.wmkeyboard.ime.PluginPanelUi.Running
            if (running != null) {
                // The keyboard's panel header, which the box does not have:
                // Back (a page, then the list), the page title, the plugin's buttons.
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = callbacks.plugins.onBack) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = stringResource(R.string.ime_applet_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        running.host?.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    com.wasimaster.wmkeyboard.ime.ui.PluginHeaderActions(running, callbacks.plugins.onEvent)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                com.wasimaster.wmkeyboard.ime.ui.PluginPanel(
                    state = state,
                    onOpenPlugin = callbacks.plugins.onOpen,
                    onEvent = callbacks.plugins.onEvent,
                    onInputFocus = { if (it != null) callbacks.plugins.onToKeyboard() },
                    onToolInsert = callbacks.onInsert,
                    onCopy = callbacks.plugins.onCopy,
                    onPaste = { callbacks.plugins.onToKeyboard() },
                    onManage = { callbacks.onOpenRoute("plugins") },
                )
            }
        }
        else -> Unit
    }
}

/** A search tool's header row in the box (#1265): its search bar, at the panel header's height. */
@Composable
private fun BoxSearchHeader(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FullBleedHeaderHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val IdleTranslate = TranslateCallbacks()

/** Room round the card for its shadow, in dp. */
internal const val FLOATING_APPLET_SHADOW_DP = 10
