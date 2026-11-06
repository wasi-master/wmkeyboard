package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Input
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AddLink
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SearchResultTap
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.GifSources
import com.wasimaster.wmkeyboard.core.tools.MediaCategories
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import com.wasimaster.wmkeyboard.core.tools.SearchBackend
import com.wasimaster.wmkeyboard.core.tools.MediaProviders
import com.wasimaster.wmkeyboard.core.tools.MediaTarget
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.SearchProviders
import com.wasimaster.wmkeyboard.core.tools.SearxClient
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import androidx.compose.runtime.collectAsState
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.WebResult
import com.wasimaster.wmkeyboard.core.tools.AiMarkdown
import com.wasimaster.wmkeyboard.ime.ImageSearchUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.MediaUi
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.SearchTab
import com.wasimaster.wmkeyboard.ime.WebSearchUi
import okhttp3.OkHttpClient

// ---- shared bits ----

/** Panel height while its search box is active and the key rows are shown below. */
private val MediaSearchHeight = 132.dp

/**
 * Shortest panel that still gets a category row: the search bar, the source
 * chips, the row itself and one whole row of results. Below this the row would
 * be taking the last of the grid, and a browsing aid that hides what it browses
 * is worse than no aid. A three-row layout at the default key height falls
 * under it; a four-row layout clears it, as does a three-row layout with the
 * number row (which ships on) adding its height.
 */
private val MediaCategoryMinPanelHeight = 190.dp

/**
 * Whether the GIF/sticker panel shows its category row.
 *
 * Pure and separate from the composable so every rule here is a unit test
 * rather than a device check.
 *
 * The row is a default-view thing: it starts a search, it does not refine one,
 * so a typed query replaces it. It survives a category tap, though — otherwise
 * you could enter a category and never pick another. Local packs have pack
 * chips instead, and a field that takes no rich media has a notice to show in
 * the same space.
 */
internal fun showMediaCategories(
    state: KeyboardUiState,
    localGrid: Boolean,
    fullBleed: Boolean,
    /** Passed in rather than read off [state]: the state's own getter needs a
     *  framework call, and this rule is worth having under a plain JVM test. */
    acceptsRichMedia: Boolean,
    /** The height the panel's body is given, when the keys of its layout take some of the key area (#538). */
    available: Dp? = null,
): Boolean {
    // A provider with no categories still has the panel's own lists to offer
    // (#597): without them the favourites had no way in at all.
    if (state.mediaCategories.isEmpty() && gifCollectionChips(state, state.panel == PanelMode.STICKER).isEmpty()) return false
    if (state.mediaSearchActive || localGrid || !acceptsRichMedia) return false
    if (state.mediaQuery.isNotBlank() && state.mediaCategory == null) return false
    return fullBleed || (available ?: keyRowsHeight(state)) >= MediaCategoryMinPanelHeight
}

/**
 * Query text with a caret in it, for the in-panel search fields.
 *
 * None of those are real text fields — keys are rerouted into panel state
 * instead of the focused editor — so the platform draws no cursor for them and
 * typing looked dead. This draws one, and since #161 it draws it *where the
 * caret actually is*: these fields have a movable caret now, a tap in the
 * middle of the text puts it there, and typing, backspace and a glide all land
 * at it.
 *
 * The text scrolls to keep the caret in view rather than ellipsizing, because
 * an ellipsis at the end of a query is an ellipsis over the part being typed.
 */
@Composable
internal fun SearchQueryText(
    query: String,
    placeholder: String,
    active: Boolean,
    textColor: Color,
    placeholderColor: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val handle = LocalCaptureCaret.current
    // The focus switch sent this field's keys to the app's field (#665): no
    // caret, faded, and a tap anywhere on it is a tap to type here again,
    // taken before the pill's own press can close the search.
    val toApp = active && handle.toApp
    val caret = if (active && !toApp) handle.at.coerceIn(0, query.length) else -1
    // Empty field: no text to press on, so the whole box takes the long press
    // that offers Paste at the caret (#434). Otherwise the one-line text
    // below does, where the press can also land on a word.
    val emptyActive = caret >= 0 && query.isEmpty()
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    var caretBar by remember(query, active) { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        SearchQueryLine(
            query = query,
            placeholder = placeholder,
            active = active,
            toApp = toApp,
            caret = caret,
            textColor = textColor,
            placeholderColor = placeholderColor,
            fontSize = fontSize,
            handle = handle,
            emptyActive = emptyActive,
            owner = owner,
            overlay = overlay,
            caretBar = caretBar,
            setCaretBar = { caretBar = it },
        )
        // Only on the field that has the keys, or had them until the switch
        // sent them to the app: one switch on screen, beside its field.
        if (active) FieldFocusSwitch(handle)
    }
}

/** [SearchQueryText] less the focus switch: the line itself, as wide as the field leaves it. */
@Composable
private fun RowScope.SearchQueryLine(
    query: String,
    placeholder: String,
    active: Boolean,
    toApp: Boolean,
    caret: Int,
    textColor: Color,
    placeholderColor: Color,
    fontSize: TextUnit,
    handle: CaptureCaretHandle,
    emptyActive: Boolean,
    owner: SelectionAnchor,
    overlay: SelectionOverlayState?,
    caretBar: Boolean,
    setCaretBar: (Boolean) -> Unit,
) {
    val line = Modifier.weight(1f)
    Row(
        modifier = when {
            toApp -> line
                .alpha(FieldToAppAlpha)
                .pointerInput(handle.onFocusSwitch) {
                    detectTapGestures { handle.onFocusSwitch?.invoke() }
                }
            emptyActive -> line
                .onGloballyPositioned {
                    owner.coordinates = it
                    overlay?.moved(owner)
                }
                .pointerInput(query, active) {
                    detectTapGestures(onLongPress = { setCaretBar(true) }, onTap = { setCaretBar(false) })
                }
            else -> line
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PublishFieldSelection(
            owner = owner,
            text = "",
            active = emptyActive,
            handle = handle,
            coordinates = { owner.coordinates },
            layout = { null },
            caretBar = caretBar,
            onCaretBarDismiss = { setCaretBar(false) },
        )
        // Empty field: the caret sits in front of the placeholder, where the
        // first character will land. Nothing to scroll and nowhere to tap.
        if (emptyActive) {
            SearchCaret(textColor, fontSize, query)
            Spacer(Modifier.width(4.dp))
        }
        if (query.isEmpty()) {
            Text(
                text = if (toApp) stringResource(R.string.ime_capture_focus_app_hint) else placeholder,
                color = placeholderColor,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            CaretQueryText(
                query = query,
                caret = caret,
                textColor = textColor,
                fontSize = fontSize,
                handle = handle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * The query itself, with the caret drawn inside it and a tap that moves it.
 *
 * The caret's x comes from the text's own layout, so it lands between the same
 * two glyphs the index names in any script and at any font scale, and the tap
 * is the inverse of that — `getOffsetForPosition`, which is what a real text
 * field uses. Both work in the *content's* coordinates: the scroll modifier is
 * applied outside them, so a query scrolled halfway along still maps a touch
 * to the character under the finger.
 */
@Composable
private fun CaretQueryText(
    query: String,
    caret: Int,
    textColor: Color,
    fontSize: TextUnit,
    handle: CaptureCaretHandle,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    // A selection in this field (#352): drawn here, its handles and bar by the
    // body's overlay, which needs to know where this text is as it scrolls.
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    val active = caret >= 0
    val selecting = active && handle.hasSelection
    val latestHandle by androidx.compose.runtime.rememberUpdatedState(handle)
    // Paste offered at the caret by a long press on no word (#434).
    var caretBar by remember(query) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .horizontalScroll(scroll)
            .pointerInput(query, active) {
                if (!active) return@pointerInput
                detectTapGestures(
                    onLongPress = { position ->
                        caretBar = !fieldLongPress(query, layout, position, latestHandle)
                    },
                ) { position ->
                    caretBar = false
                    layout?.takeIf { it.layoutInput.text.text == query }?.let {
                        latestHandle.onCaretTap(it.getOffsetForPosition(position))
                    }
                }
            },
    ) {
        Text(
            text = query,
            color = textColor,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = {
                layout = it
                overlay?.moved(owner)
            },
            modifier = Modifier
                .onGloballyPositioned {
                    owner.coordinates = it
                    overlay?.moved(owner)
                }
                .selectionHighlight(
                    query,
                    if (selecting) handle.selectionStart else 0,
                    if (selecting) handle.selectionEnd else 0,
                    LocalKbTheme.current.accent.copy(alpha = HighlightAlpha),
                ) { layout },
        )
        PublishFieldSelection(
            owner = owner,
            text = query,
            active = active,
            handle = handle,
            coordinates = { owner.coordinates },
            layout = { layout?.takeIf { it.layoutInput.text.text == query } },
            caretBar = caretBar,
            onCaretBarDismiss = { caretBar = false },
        )
        // Only a layout of *this* text can say where the caret goes. `Text`
        // reports its layout during the layout phase, which runs after the
        // composition that changed the text, so the first composition to see a
        // new query still holds the layout of the old one — and asking that one
        // for an offset past its end threw `offset(2) is out of bounds [0, 1]`
        // on the second keystroke in every one of these boxes (#252). The
        // caret waits the one frame instead: writing `layout` in `onTextLayout`
        // schedules the recomposition that draws it.
        val result = layout?.takeIf { it.layoutInput.text.text == query }
        // No caret while a span is selected: the highlight marks where typing lands.
        if (caret >= 0 && result != null && !selecting) {
            // Clamped against the layout's own text and not against [query]:
            // they are the same string here, and it is the layout that defines
            // the legal range.
            val offset = caret.coerceIn(0, result.layoutInput.text.length)
            val x = result.getHorizontalPosition(offset, usePrimaryDirection = true)
            Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }) {
                SearchCaret(textColor, fontSize, caret to query)
            }
            // Keep the caret on screen: typing at the end of a long query used
            // to run off the edge behind an ellipsis.
            val caretX = with(density) { x.toDp() }
            LaunchedEffect(caret, query, scroll.maxValue, scroll.viewportSize) {
                val viewport = scroll.viewportSize
                if (viewport <= 0) return@LaunchedEffect
                val target = with(density) { caretX.toPx() }.roundToInt()
                val margin = with(density) { CaretScrollMarginDp.dp.toPx() }.roundToInt()
                val want = when {
                    target - margin < scroll.value -> target - margin
                    target + margin > scroll.value + viewport -> target + margin - viewport
                    else -> return@LaunchedEffect
                }
                scroll.scrollTo(want.coerceIn(0, scroll.maxValue))
            }
        }
    }
}

/** How much text is kept visible either side of the caret when it scrolls. */
private const val CaretScrollMarginDp = 12

/** The blinking bar itself. [restartKey] resets the phase on each keystroke — and on
 *  each caret move, so a tap into the middle of the text shows the caret solid. */
@Composable
private fun SearchCaret(color: Color, fontSize: TextUnit, restartKey: Any?) {
    // Reduce motion holds it solid. The caret still has to be drawn — it is
    // the only thing marking where typing lands — so this is a branch on the
    // loop rather than on an animation spec: with the blink gone there is no
    // spec left to slow down, and a caret that snapped between visible and
    // hidden would be the same flicker at a harder edge.
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
    val height = with(LocalDensity.current) { fontSize.toDp() * 1.25f }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(height)
            .background(if (visible) color else Color.Transparent),
    )
}

@Volatile
private var sharedMediaLoader: ImageLoader? = null
private val mediaLoaderLock = Any()

/**
 * The one image loader for the whole process, with animated GIF/WebP support.
 *
 * A singleton for two reasons. A disk cache directory can have exactly one
 * owner — Coil refuses two live loaders pointing at the same one — so building
 * a loader per call site is what had been keeping the disk cache from existing
 * at all, and every thumbnail was re-fetched on every scroll back. And a dozen
 * loaders each holding their own memory cache is a lot of heap for an input
 * method, which the system already kills early.
 *
 * The keyboard service declares no `android:process`, so the settings app and
 * the keyboard really do share one process and one loader.
 */
fun mediaImageLoader(context: Context): ImageLoader =
    sharedMediaLoader ?: synchronized(mediaLoaderLock) {
        sharedMediaLoader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                // Every thumbnail and animation goes in the network activity
                // log. Image hosts are arbitrary third-party CDNs, so the rows
                // show the host and no path.
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor(InternetGate)
                                // OkHttp's own `okhttp/x.y` agent is refused
                                // outright by Wikimedia's image hosts, which
                                // left every Commons preview blank. Same agent
                                // the downloads send, so the preview and the
                                // file it stands for are fetched alike.
                                .addInterceptor { chain ->
                                    chain.proceed(
                                        chain.request().newBuilder()
                                            .header("User-Agent", ToolHttp.USER_AGENT)
                                            .build(),
                                    )
                                }
                                .addNetworkInterceptor(NetLogInterceptor(NetSource.MEDIA_IMAGES))
                                .build()
                        },
                    ),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // Below Coil's 20% default: a bloated keyboard is a visible jank
            // source, and three screens of thumbnails is all the scrollback
            // anyone flicks through.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
            // Under cacheDir, so the system can reclaim it under storage
            // pressure — correct for content that is trivially fetched again.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.applicationContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(MEDIA_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
            .also { sharedMediaLoader = it }
    }

/**
 * Empties the media loader's in-memory thumbnails, if one was ever built.
 *
 * Coil 3.2 has no notion of "the UI is not on screen" — the background
 * trimming its 3.3 release added keys off a process lifecycle an input method
 * never drives — so nothing here ever gave these back on its own. Twelve
 * percent of the heap in GIF and sticker thumbnails is worth holding while the
 * panel is open and worth nothing at all once the keyboard is a text field's
 * keyboard again, and the disk cache behind it makes a re-scroll a decode
 * rather than a download. Deliberately does not touch the loader itself:
 * shutting it down would close the disk cache the next panel wants.
 */
fun trimMediaImageMemory() {
    sharedMediaLoader?.memoryCache?.clear()
}

/** The process-wide media loader; see [mediaImageLoader]. */
@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { mediaImageLoader(context) }
}

private const val MEDIA_DISK_CACHE_BYTES = 64L * 1024 * 1024

/**
 * Header-row variant of the media search field, for full-bleed panels: it
 * sits next to the back button in the [FullBleedTool] header and takes the
 * row's free width. Same key-rerouting trick as emoji search — typing goes
 * into [KeyboardUiState.mediaQuery], never the focused field.
 */
@Composable
internal fun RowScope.MediaHeaderSearchBar(
    state: KeyboardUiState,
    placeholder: String,
    onQueryTap: () -> Unit,
    attribution: String? = null,
    // A provider's "Powered by" mark at the end of the box, while it answers.
    logo: GifSource? = null,
    // Prompt shown once the field is typed into. Null falls back to the search
    // wording; tools that aren't searching (e.g. translate) pass their own.
    // A default argument cannot hold a resource, hence the null.
    activePlaceholder: String? = null,
    focused: Boolean = false,
    // Shown while there is a query: starts a new search. Null hides it.
    onClear: (() -> Unit)? = null,
    // The buffer the bar draws. The media query by default; a panel with a
    // search box of its own (the Notes panel, #560) passes its own pair.
    query: String = state.mediaQuery,
    active: Boolean = state.mediaSearchActive,
) {
    val kb = LocalKbTheme.current
    val activeHint = activePlaceholder ?: stringResource(R.string.ime_media_search_active_hint)
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable { onQueryTap() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.toolbarIcon,
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = query,
            placeholder = if (active) activeHint else placeholder,
            active = active,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (attribution != null) {
            Text(
                attribution,
                color = kb.secondaryText,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (logo != null) ProviderLogo(logo, height = 8.dp, modifier = Modifier.padding(start = 6.dp))
        if (onClear != null && query.isNotEmpty()) {
            IconHint(stringResource(R.string.ime_clipboard_search_clear_desc)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_clipboard_search_clear_desc),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp)
                        .clickable { onClear() },
                    tint = kb.toolbarIcon,
                )
            }
        }
    }
}

/**
 * The tappable search field at the top of the media panels — same
 * key-rerouting trick as emoji search: tapping it keeps the key rows on
 * screen and typing goes into [KeyboardUiState.mediaQuery].
 */
@Composable
private fun MediaSearchBar(
    state: KeyboardUiState,
    placeholder: String,
    onQueryTap: () -> Unit,
    attribution: String? = null,
    // A provider's "Powered by" mark beside the box, while it answers.
    logo: GifSource? = null,
    focused: Boolean = false,
    // Drawn after the box and its credit: the switch to the emoji and the
    // other media panel, when it sits up here (issue #366).
    trailing: (@Composable () -> Unit)? = null,
    // The ✕ that empties the query, while there is one (#907).
    onClear: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = kb.cardShape()
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clickable { onQueryTap() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = kb.toolbarIcon,
            )
            Spacer(Modifier.width(8.dp))
            SearchQueryText(
                query = state.mediaQuery,
                placeholder = if (state.mediaSearchActive) {
                    stringResource(R.string.ime_media_search_active_hint)
                } else {
                    placeholder
                },
                active = state.mediaSearchActive,
                textColor = kb.suggestionText,
                placeholderColor = kb.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (onClear != null && state.mediaQuery.isNotEmpty()) {
                IconHint(stringResource(R.string.ime_clipboard_search_clear_desc)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ime_clipboard_search_clear_desc),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp)
                            .clickable { onClear() },
                        tint = kb.toolbarIcon,
                    )
                }
            }
        }
        if (attribution != null) {
            Text(
                attribution,
                color = kb.secondaryText,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (logo != null) ProviderLogo(logo, height = 9.dp, modifier = Modifier.padding(start = 8.dp))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Centered message with an optional action chip, in panel theme colors. */
@Composable
private fun PanelNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                actionLabel,
                color = kb.chipActiveText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(kb.chipShape())
                    .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                    .background(kb.chipActive)
                    .chipBorder(kb, kb.chipShape())
                    .clickable { onAction() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The panel is empty because data saving is holding the fetch.
 *
 * Two shapes, from the two answers the user can have given: "ask each time"
 * offers the fetch here, and a tap on that chip allows this feature for the
 * rest of the session; "turn off" says so and offers nothing, because they
 * have already answered. Both name the connection rather than blaming the
 * network, so an empty grid never reads as a bug.
 */
@Composable
private fun MeteredNotice(canAllow: Boolean, onAllow: () -> Unit) {
    PanelNotice(
        message = stringResource(
            if (canAllow) R.string.ime_metered_ask_body else R.string.ime_metered_off_body,
        ),
        actionLabel = stringResource(R.string.ime_metered_allow_action).takeIf { canAllow },
        onAction = onAllow,
    )
}

/**
 * Standing bar across the top of a media grid: this field takes no rich
 * content, so nothing here can be sent into it. Deliberately not a toast —
 * the limitation belongs to the field, not to the tap, so it stays visible
 * for as long as that field is focused.
 */
@Composable
private fun MediaUnsupportedNotice(stickers: Boolean) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(kb.cardShape())
            .frostGlass(kb, kb.cardShape(), FrostSurface.CARDS)
            .background(kb.chip)
            .chipBorder(kb, kb.cardShape())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = kb.toolbarIcon,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (stickers) {
                stringResource(R.string.ime_sticker_unsupported_field_notice)
            } else {
                stringResource(R.string.ime_gif_unsupported_field_notice)
            },
            color = kb.toolbarIcon,
            fontSize = 11.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun PanelSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = LocalKbTheme.current.accent,
        )
    }
}

// ---- GIF & sticker panels ----

// GIF/sticker picker: trending on open, live search from the search bar,
// animated previews, tap to download & commit into the editor. With
// several providers configured, either a chip per source or one
// evenly-mixed grid, per the tool's settings.

/**
 * The hint in the search box. A grid from KLIPY or GIPHY names it: "Search
 * KLIPY" is a condition of KLIPY's production key, and GIPHY's own search
 * boxes read "Search GIPHY". Otherwise GIFs and stickers each have their own
 * wording.
 */
@Composable
private fun gifSearchHint(state: KeyboardUiState, stickers: Boolean): String {
    val brand = gifBrand(state, stickers)
    if (brand != null) {
        return stringResource(R.string.ime_media_search_provider, stringResource(GifSources.displayNameRes(brand)))
    }
    return stringResource(if (stickers) R.string.ime_sticker_search_hint else R.string.ime_gif_search_hint)
}

/**
 * "from Wikimedia Commons": the providers this grid is pulling from that have
 * no mark of their own. KLIPY and GIPHY get theirs from [ProviderLogo].
 */
@Composable
private fun gifAttribution(state: KeyboardUiState, stickers: Boolean = false): String? {
    // Nothing to credit for packs on the device.
    val credited = gifTargets(state, stickers).filter { !it.onDevice && it.source !in BRANDED_SOURCES }
    if (credited.isEmpty()) return null
    val names = StringBuilder()
    for (target in credited) {
        if (names.isNotEmpty()) names.append(" · ")
        names.append(target.name.ifBlank { stringResource(GifSources.displayNameRes(target.source)) })
    }
    return stringResource(R.string.ime_media_attribution, names.toString())
}

/**
 * KLIPY or GIPHY, when the grid pulls from one: the search bar then carries
 * its "Powered by" mark. GIPHY is never mixed with anything
 * ([GifSource.ownGrid]), so a grid has at most one of the two.
 */
private fun gifBrand(state: KeyboardUiState, stickers: Boolean): GifSource? =
    gifTargets(state, stickers).firstOrNull { it.source in BRANDED_SOURCES }?.source

private fun gifTargets(state: KeyboardUiState, stickers: Boolean): List<MediaTarget> = GifSources.targets(
    gifSourcesFor(state, stickers),
    state.mediaProvider,
    MediaProviders.mode(state.settings, stickers),
)

/** The tool's providers as the service asks them; see [MediaProviders]. */
private fun gifSourcesFor(state: KeyboardUiState, stickers: Boolean): List<MediaTarget> =
    MediaProviders.targets(state.settings, stickers)

/** The providers whose terms ask for their own "Powered by" mark rather than a line of text. */
private val BRANDED_SOURCES = setOf(GifSource.KLIPY, GifSource.GIPHY)

/**
 * The provider's "Powered by" mark at the end of the search box, from the kit
 * each one hands to API partners. Both make it a condition of a production key.
 *
 * KLIPY's is one colour, so it takes the panel's secondary text, as KLIPY
 * ships it in grey, black and white for exactly that. GIPHY's carries its
 * coloured logo and cannot be tinted: white lettering on a dark panel, and on
 * a light one the black plate GIPHY's kit gives for light backgrounds.
 */
@Composable
private fun ProviderLogo(source: GifSource, height: Dp, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    if (source == GifSource.KLIPY) {
        Icon(
            painterResource(R.drawable.ime_powered_by_klipy),
            contentDescription = stringResource(R.string.ime_media_powered_by_klipy),
            modifier = modifier
                .height(height)
                .aspectRatio(KLIPY_LOGO_ASPECT),
            tint = kb.secondaryText,
        )
        return
    }
    // Light text means a dark panel.
    val darkPanel = kb.suggestionText.luminance() > 0.5f
    val art = if (darkPanel) R.drawable.ime_powered_by_giphy_on_dark else R.drawable.ime_powered_by_giphy_on_light
    Image(
        painterResource(art),
        contentDescription = stringResource(R.string.ime_media_powered_by_giphy),
        // The plate has its own margin round the lettering, so it stands a little taller.
        modifier = modifier
            .height(if (darkPanel) height else height * GIPHY_PLATE_SCALE)
            .aspectRatio(if (darkPanel) GIPHY_LOGO_ASPECT else GIPHY_PLATE_ASPECT),
    )
}

/** Width over height of each "Powered by" artwork, so it sizes from a height alone. */
private const val KLIPY_LOGO_ASPECT = 2335.63f / 396.67f
private const val GIPHY_LOGO_ASPECT = 578f / 77f
private const val GIPHY_PLATE_ASPECT = 641f / 136f
private const val GIPHY_PLATE_SCALE = 1.5f

/**
 * "via Brave" or "via Tavily" in the web and image search bars, for whichever
 * service answers. Brave's terms ask for the credit. A SearXNG instance is the
 * user's own server, so it gets none.
 */
@Composable
internal fun searchAttribution(settings: KeyboardSettings): String? =
    when (ToolApiKeys.searchBackend(settings)) {
        SearchBackend.BRAVE -> stringResource(R.string.ime_search_attribution_brave)
        SearchBackend.TAVILY -> stringResource(R.string.ime_search_attribution_tavily)
        SearchBackend.SEARXNG, null -> null
    }

/** The GIF/sticker search box sized for a [FullBleedTool] header row. */
@Composable
internal fun RowScope.GifHeaderSearchBar(
    state: KeyboardUiState,
    stickers: Boolean,
    onQueryTap: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    MediaHeaderSearchBar(
        state = state,
        placeholder = gifSearchHint(state, stickers),
        onQueryTap = onQueryTap,
        attribution = gifAttribution(state, stickers),
        logo = gifBrand(state, stickers),
        onClear = onClear,
    )
}

/**
 * @param state the live keyboard UI state, read for the gif/sticker tab.
 * @param stickers true for the sticker tab, false for gif.
 * @param onQueryTap the search box was tapped.
 * @param onRetry the failed load should be retried.
 * @param onSelect a result was picked for sending.
 * @param onSourceSelect the gif/sticker source was switched.
 * @param onOpenToolSettings the panel's own settings row was tapped.
 * @param fullBleed the panel is inside a [FullBleedTool], which owns the
 *   height and hosts the search box in its header — so the body draws
 *   neither.
 * @param onCategorySelect a category chip was tapped.
 * @param onLongPress a result was long-pressed.
 * @param onPackFilter the sticker-pack filter changed, or was cleared.
 * @param onSaveToPack a result should be saved into a local pack.
 * @param onFavouriteToggle a GIF should go into the favourites, or come out (#597).
 * @param onCopy a result should be copied to the clipboard.
 * @param onReport a result should be reported.
 * @param onDismissAction the open long-press action sheet should close.
 * @param onOpenRoute a settings route should open.
 * @param switcher the switch to the emoji and the other media panel, drawn at
 *   the end of the search bar (issue #366). Unused in [fullBleed], whose
 *   header is the host's to fill.
 * @param inGrid the panel is the browser cell of its layout (#538), which
 *   owns the height, so the body fills the cell it is given.
 * @param onQueryClear the ✕ in the search box was tapped (#907).
 */
@Composable
internal fun GifPanel(
    state: KeyboardUiState,
    stickers: Boolean,
    onQueryTap: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (GifItem) -> Unit,
    onSourceSelect: (String) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
    fullBleed: Boolean = false,
    onCategorySelect: (String) -> Unit = {},
    onLongPress: (GifItem) -> Unit = {},
    onPackFilter: (String?) -> Unit = {},
    onSaveToPack: (GifItem, String?) -> Unit = { _, _ -> },
    onFavouriteToggle: (GifItem) -> Unit = {},
    onCopy: (GifItem) -> Unit = {},
    onReport: (GifItem) -> Unit = {},
    onDismissAction: () -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
    switcher: (@Composable () -> Unit)? = null,
    inGrid: Boolean = false,
    onQueryClear: (() -> Unit)? = null,
) {
    val ui = if (stickers) state.sticker else state.gif
    val tool = if (stickers) ToolbarTool.STICKER else ToolbarTool.GIF
    val sources = gifSourcesFor(state, stickers)
    val mode = MediaProviders.mode(state.settings, stickers)
    val chips = GifSources.chips(sources, mode)
    val localGrid = GifSources.targets(sources, state.mediaProvider, mode).map { it.source } == listOf(GifSource.LOCAL)
    val sizing = if (fullBleed || inGrid) {
        Modifier.fillMaxSize()
    } else {
        val height = if (state.mediaSearchActive) MediaSearchHeight else keyRowsHeight(state)
        Modifier
            .fillMaxWidth()
            .height(height)
    }
    // "Add to which pack?", up when the add chip is pressed under All with
    // more than one pack to choose from. Panel-local: nothing else reads it.
    var choosingAddPack by remember { mutableStateOf(false) }
    // With no packs at all the empty grid has its own way in, so the add
    // chip waits for the first one.
    val canAdd = localGrid && state.stickerPacks.isNotEmpty() && !state.mediaSearchActive
    val onAdd: () -> Unit = {
        val target = stickerAddTarget(state.stickerPacks, state.stickerPackId)
        if (target == null) {
            choosingAddPack = true
        } else {
            onOpenRoute(stickerPackAddRoute(target))
        }
    }
    // The sticker generator, into the same pack the add chip would pick; with
    // several and none chosen, the generator asks.
    val onCreate: () -> Unit = {
        onOpenRoute(stickerGeneratorRoute(stickerAddTarget(state.stickerPacks, state.stickerPackId)))
    }
    // The search box and the chip rows fold away while the results scroll
    // down, when the setting asks (see [MediaChrome]).
    val chrome = LocalMediaChrome.current
    BoxWithConstraints(modifier = sizing) {
        // In a layout cell the keys around it have taken some of the key area.
        val available = if (inGrid) maxHeight else null
        val categoryRow = showMediaCategories(state, localGrid, fullBleed, state.acceptsRichMedia, available)
        // The favourites live on the category row, which a short panel, or a
        // field that takes no images, does not draw. A star in the search bar
        // is then their way in, and costs no height (#597).
        val favouritesStar = !stickers && !categoryRow && !state.mediaSearchActive &&
            (state.gifFavouriteIds.isNotEmpty() || state.mediaCategory == MediaCategories.FAVOURITES)
        val trailing: (@Composable () -> Unit)? = if (favouritesStar) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FavouritesStar(
                        on = state.mediaCategory == MediaCategories.FAVOURITES,
                        onClick = { onCategorySelect(MediaCategories.FAVOURITES) },
                    )
                    if (switcher != null) {
                        Spacer(Modifier.width(8.dp))
                        switcher()
                    }
                }
            }
        } else {
            switcher
        }
        Column(modifier = Modifier.fillMaxSize()) {
            PanelFocusTarget(
                panel = state.panel,
                region = FocusRegion.SEARCH,
                count = 1,
                columns = 1,
                onActivate = { onQueryTap() },
            )
            MediaHeaderReveal(collapsed = chrome?.collapsed(state) == true) {
                if (!fullBleed) {
                    MediaSearchBar(
                        state = state,
                        placeholder = gifSearchHint(state, stickers),
                        onQueryTap = onQueryTap,
                        attribution = gifAttribution(state, stickers),
                        logo = gifBrand(state, stickers),
                        focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
                        trailing = trailing,
                        onClear = onQueryClear,
                    )
                }
                if (chips.isNotEmpty() && !state.mediaSearchActive) {
                    // Tab reaches the source chips: results are useless if the
                    // keyboard can browse them but not switch where they come from.
                    PanelFocusTarget(
                        panel = state.panel,
                        region = FocusRegion.CHIPS,
                        count = chips.size,
                        columns = chips.size,
                        onActivate = { index -> chips.getOrNull(index)?.let { onSourceSelect(it.key) } },
                    )
                    // The add chip ends this row rather than taking a row of its
                    // own under it: with one pack there is nothing else to put
                    // there, and a row for one small chip was a row of stickers.
                    GifSourceChips(
                        chips = chips,
                        selectedIndex = GifSources.selectedChip(chips, state.mediaProvider, sources, mode),
                        onSelect = onSourceSelect,
                        focused = state.focusedIndex(FocusRegion.CHIPS),
                        onAdd = if (canAdd) onAdd else null,
                        onCreate = if (canAdd) onCreate else null,
                    )
                }
                // The pack filter, from two packs up; with no source chips to sit
                // beside, the add chip leads it, alone for a single pack.
                if (canAdd && (chips.isEmpty() || state.stickerPacks.size > 1)) {
                    StickerPackChips(
                        packs = state.stickerPacks,
                        selected = state.stickerPackId,
                        onSelect = onPackFilter,
                        onAdd = if (chips.isEmpty()) onAdd else null,
                        onCreate = if (chips.isEmpty()) onCreate else null,
                    )
                }
                if (categoryRow) {
                    // Trending first, so there is always a way back out of a
                    // category — and somewhere for the focus ring to sit when no
                    // category is on. The GIF panel's own lists lead it (#597):
                    // favourites once there are any, recent when turned on.
                    val entries = gifCollectionChips(state, stickers) + listOf(
                        MediaCategory(term = "", labelRes = R.string.ime_media_category_trending_label),
                    ) + state.mediaCategories
                    PanelFocusTarget(
                        panel = state.panel,
                        region = FocusRegion.CATEGORIES,
                        count = entries.size,
                        columns = entries.size,
                        onActivate = { index ->
                            entries.getOrNull(index)?.let { onCategorySelect(it.term) }
                        },
                    )
                    GifCategoryChips(
                        categories = entries,
                        selected = state.mediaCategory,
                        onSelect = onCategorySelect,
                        focused = state.focusedIndex(FocusRegion.CATEGORIES),
                    )
                }
            }
            // The field publishes the MIME types it accepts; when none of them
            // is an image, nothing this panel can send will ever arrive. Say so
            // once, up front, instead of letting every tap end in a download
            // and a "copied, paste it instead" toast.
            val unsupported = !state.acceptsRichMedia
            // Not while the search box is up — the panel is squeezed to a couple
            // of rows there, and the notice is waiting when the results land.
            if (unsupported && !state.mediaSearchActive) MediaUnsupportedNotice(stickers)
            // Weighted, so the results, the notices and the spinner fill
            // whatever the rows above leave.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (ui) {
                    MediaUi.NeedKey -> PanelNotice(
                        if (stickers) {
                            stringResource(R.string.ime_sticker_need_key_body)
                        } else {
                            stringResource(R.string.ime_gif_need_key_body)
                        },
                        actionLabel = stringResource(R.string.ime_open_settings_action),
                        onAction = { onOpenToolSettings(tool) },
                    )
                    MediaUi.Loading -> PanelSpinner()
                    is MediaUi.Error -> PanelNotice(
                        ui.message,
                        actionLabel = stringResource(CommonR.string.common_retry),
                        onAction = onRetry,
                    )
                    is MediaUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
                    is MediaUi.Ready -> {
                        if (ui.items.isEmpty()) {
                            LocalStickerEmptyNotice(
                                category = state.mediaCategory,
                                localGrid = localGrid,
                                state = state,
                                query = ui.query,
                                stickers = stickers,
                                onOpenRoute = onOpenRoute,
                            )
                        } else {
                            // Dimmed rather than removed when the field can't take
                            // them: long-press (save, copy, report) still works, and
                            // the user can see what they'd get in a field that
                            // accepts it.
                            Box(modifier = Modifier.alpha(if (unsupported) 0.45f else 1f)) {
                                val gif = state.settings.gif
                                GifGrid(
                                    items = ui.items,
                                    downloadingId = state.mediaDownloadingId,
                                    progress = state.mediaDownloadProgress,
                                    queue = state.mediaQueue,
                                    onSelect = onSelect,
                                    onLongPress = onLongPress,
                                    panel = state.panel,
                                    focused = state.focusedIndex(),
                                    perRow = if (stickers) gif.stickerPerRow else gif.gifPerRow,
                                    spacing = gif.gridSpacing.dp,
                                    scroll = chrome?.connection,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (choosingAddPack) {
            StickerAddPackSheet(
                packs = state.stickerPacks,
                onPick = { packId ->
                    choosingAddPack = false
                    onOpenRoute(stickerPackAddRoute(packId))
                },
                onDismiss = { choosingAddPack = false },
            )
        }
        val action = state.mediaAction
        if (action != null) {
            MediaActionSheet(
                item = action,
                stickers = stickers,
                packs = state.stickerPacks,
                favourite = action.id in state.gifFavouriteIds,
                onSaveToPack = onSaveToPack,
                onFavouriteToggle = onFavouriteToggle,
                onCopy = onCopy,
                onReport = onReport,
                onOpenRoute = onOpenRoute,
                onDismiss = onDismissAction,
            )
        }
    }
}

/**
 * The chips for the GIF panel's own lists (#597), ahead of Trending.
 * Favourites waits for the first one, so a user who never stars a GIF never
 * sees an empty chip; it stays while it is the one selected, so taking the
 * last one out does not pull the chip out from under the finger.
 */
internal fun gifCollectionChips(state: KeyboardUiState, stickers: Boolean): List<MediaCategory> {
    if (stickers) return emptyList()
    val chips = ArrayList<MediaCategory>(2)
    if (state.gifFavouriteIds.isNotEmpty() || state.mediaCategory == MediaCategories.FAVOURITES) {
        chips += MediaCategory(term = MediaCategories.FAVOURITES, labelRes = R.string.ime_gif_favourites_label)
    }
    if (state.settings.gif.recentGifs) {
        chips += MediaCategory(term = MediaCategories.RECENT, labelRes = R.string.ime_gif_recent_label)
    }
    return chips
}

/** The search bar's way into the GIF favourites, for when the category row is not drawn; lit while they show. */
@Composable
private fun FavouritesStar(on: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (on) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Star,
            contentDescription = stringResource(R.string.ime_gif_favourites_label),
            modifier = Modifier.size(20.dp),
            tint = if (on) kb.chipActiveText else kb.toolbarIcon,
        )
    }
}

/** The empty grid: local packs have their own way of being empty, and so do favourites and recent GIFs. */
@Composable
private fun LocalStickerEmptyNotice(
    category: String?,
    localGrid: Boolean,
    state: KeyboardUiState,
    query: String,
    stickers: Boolean,
    onOpenRoute: (String) -> Unit,
) {
    when {
        !stickers && category == MediaCategories.FAVOURITES ->
            PanelNotice(stringResource(R.string.ime_gif_favourites_empty))
        !stickers && category == MediaCategories.RECENT ->
            PanelNotice(stringResource(R.string.ime_gif_recent_empty))
        !localGrid -> PanelNotice(
            when {
                query.isBlank() -> stringResource(R.string.ime_media_trending_empty)
                stickers -> stringResource(R.string.ime_sticker_search_empty, query)
                else -> stringResource(R.string.ime_gif_search_empty, query)
            },
        )
        query.isNotBlank() ->
            PanelNotice(stringResource(R.string.ime_sticker_local_search_empty, query))
        state.stickerPacks.isEmpty() -> PanelNotice(
            stringResource(R.string.ime_sticker_no_packs_empty),
            actionLabel = stringResource(R.string.ime_sticker_manage_packs_action),
            onAction = { onOpenRoute(STICKER_PACKS_ROUTE) },
        )
        else -> PanelNotice(
            stringResource(R.string.ime_sticker_pack_empty),
            actionLabel = stringResource(R.string.ime_sticker_manage_packs_action),
            onAction = { onOpenRoute(STICKER_PACKS_ROUTE) },
        )
    }
}

/** Settings route hosting the sticker pack manager. */
internal const val STICKER_PACKS_ROUTE = "sticker_packs"

/**
 * Settings route that opens one pack with the photo picker already up. The
 * app's nav graph declares it as `sticker_pack/{packId}/add`.
 */
internal fun stickerPackAddRoute(packId: String): String =
    "sticker_pack/${android.net.Uri.encode(packId)}/add"

/**
 * Settings route that opens one pack with [stickerId]'s title and keywords up
 * for editing (#1157). Declared as `sticker_pack/{packId}/edit/{stickerId}`.
 */
internal fun stickerEditRoute(packId: String, stickerId: String): String =
    "sticker_pack/${android.net.Uri.encode(packId)}/edit/${android.net.Uri.encode(stickerId)}"

/**
 * Settings route of the sticker generator, saving into [packId] when there is
 * one. The app's nav graph declares it as `sticker_generator?pack={pack}`.
 */
internal fun stickerGeneratorRoute(packId: String?): String =
    if (packId == null) "sticker_generator" else "sticker_generator?pack=${android.net.Uri.encode(packId)}"

/**
 * Which pack the add chip adds to, or null when the user has to say.
 *
 * The pack the grid is filtered to, if one is; otherwise the only pack there
 * is. Under All with several packs any pick would be a guess, and a sticker
 * filed in the wrong pack is a second trip to move it.
 */
internal fun stickerAddTarget(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    selected: String?,
): String? =
    selected?.takeIf { id -> packs.any { it.id == id } } ?: packs.singleOrNull()?.id

/** Source chips: Klipy / GIPHY / My stickers, or Online / My stickers when mixed. */
@Composable
private fun GifSourceChips(
    chips: List<com.wasimaster.wmkeyboard.core.tools.SourceChip>,
    selectedIndex: Int,
    onSelect: (String) -> Unit,
    focused: Int? = null,
    /** Adds a sticker to a pack of your own: a [StickerAddChip] at the row's end. Null, none. */
    onAdd: (() -> Unit)? = null,
    /** Opens the sticker generator: a second chip beside [onAdd]'s. */
    onCreate: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = kb.chipShape()
        chips.forEachIndexed { index, chip ->
            val active = index == selectedIndex
            Text(
                chip.name.ifBlank { stringResource(chip.labelRes) },
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(chip.key) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (onAdd != null) StickerAddChip(onAdd, verticalPadding = 4.dp)
        if (onCreate != null) StickerAddChip(onCreate, verticalPadding = 4.dp, create = true)
    }
}

/**
 * The chip that adds a sticker to one of your packs: a plus, as wide as it
 * needs. With [create], the sparkle beside it that opens the sticker
 * generator instead.
 */
@Composable
private fun StickerAddChip(onAdd: () -> Unit, verticalPadding: Dp, create: Boolean = false) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Box(
        modifier = Modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { onAdd() }
            .padding(horizontal = 10.dp, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (create) Icons.Outlined.AutoAwesome else Icons.Outlined.Add,
            contentDescription = stringResource(if (create) R.string.ime_sticker_create_desc else R.string.ime_sticker_add_desc),
            modifier = Modifier.size(16.dp),
            tint = kb.chipText,
        )
    }
}

/**
 * Category chips for the default view. Pressing one runs it as a search.
 *
 * A `LazyRow` rather than the plain `horizontalScroll` the pack chips use: a
 * ScrollState has no index-to-offset map, so a focus ring landing on a chip
 * past the right edge could never be scrolled into view.
 */
@Composable
private fun GifCategoryChips(
    categories: List<MediaCategory>,
    selected: String?,
    onSelect: (String) -> Unit,
    focused: Int? = null,
) {
    val kb = LocalKbTheme.current
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(categories, key = { _, category -> category.term }) { index, category ->
            // Trending carries the blank term, so it is the active chip
            // exactly when no category is.
            val active = category.term == selected.orEmpty()
            val shape = kb.chipShape()
            Text(
                categoryLabel(category),
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(category.term) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** A bundled category names itself from resources; a provider's names itself. */
@Composable
private fun categoryLabel(category: MediaCategory): String =
    if (category.labelRes != 0) stringResource(category.labelRes) else category.label

/**
 * Pack chips under the "My stickers" tab: the add chip, then All and one per
 * pack. A single pack has nothing to filter, so it gets the add chip alone.
 * A null [onAdd] leaves the chip out, for when it ends the source chips' row
 * above instead.
 *
 * Add comes first so a long row of packs cannot scroll it out of reach.
 */
@Composable
private fun StickerPackChips(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAdd: (() -> Unit)?,
    onCreate: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = kb.chipShape()
        if (onAdd != null) StickerAddChip(onAdd, verticalPadding = 2.dp)
        if (onCreate != null) StickerAddChip(onCreate, verticalPadding = 2.dp, create = true)
        if (packs.size < 2) return@Row
        val allLabel = stringResource(R.string.ime_sticker_pack_all_label)
        val entries = listOf<Pair<String?, String>>(null to allLabel) + packs.map { it.id to it.name }
        for ((id, label) in entries) {
            val active = id == selected
            Text(
                label,
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Long-press menu over the GIF or sticker grid.
 *
 * An IME can't put up a dialog — it has no activity window — so this is a
 * scrim plus a panel-local surface, dismissed by tapping outside.
 *
 * Which rows show depends on where the item came from. Saving to a pack is
 * sticker-only (a GIF can't become one), and neither reporting nor saving
 * makes sense for the user's own stickers — they own the file already, and
 * there's nobody to report it to.
 */
@Composable
private fun MediaActionSheet(
    item: GifItem,
    stickers: Boolean,
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    /** The GIF is one of the favourites already, so the row takes it out. */
    favourite: Boolean,
    onSaveToPack: (GifItem, String?) -> Unit,
    onFavouriteToggle: (GifItem) -> Unit,
    onCopy: (GifItem) -> Unit,
    onReport: (GifItem) -> Unit,
    onOpenRoute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            // The item's name first — long-press is also the only way to
            // find out what a result is called. One of the user's own
            // stickers says everything it is found by instead: its title,
            // its pack, and the keywords and emoji that offer it while typing.
            val own = if (item.source == GifSource.LOCAL) ownSticker(item, packs) else null
            if (own != null) {
                OwnStickerInfo(own.first, own.second)
            } else if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            // Who made it, where the provider says: GIPHY's API terms ask for
            // its uploader's credit wherever there is one, and its own apps
            // put it on this same long-press view.
            if (own == null && item.creator.isNotBlank()) {
                Text(
                    stringResource(R.string.ime_media_creator, item.creator),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
                )
            }
            val local = item.source == GifSource.LOCAL
            if (stickers) {
                if (local) {
                    // Its title and keywords, straight to the editor (#1157).
                    if (own != null) {
                        MediaActionRow(stringResource(R.string.ime_sticker_edit_keywords_action)) {
                            onOpenRoute(stickerEditRoute(own.first.id, own.second.id))
                        }
                    }
                    MediaActionRow(stringResource(R.string.ime_sticker_manage_packs_action)) {
                        onOpenRoute(STICKER_PACKS_ROUTE)
                    }
                } else if (packs.isEmpty()) {
                    MediaActionRow(stringResource(R.string.ime_sticker_save_new_pack_action)) {
                        onSaveToPack(item, null)
                    }
                } else {
                    for (pack in packs) {
                        val label =
                            stringResource(R.string.ime_sticker_save_to_pack_action, pack.name)
                        MediaActionRow(label) { onSaveToPack(item, pack.id) }
                    }
                }
            } else {
                // The GIF panel's counterpart of saving to a pack (#597).
                MediaActionRow(
                    stringResource(
                        if (favourite) R.string.ime_gif_favourite_remove_action else R.string.ime_gif_favourite_add_action,
                    ),
                ) { onFavouriteToggle(item) }
            }
            MediaActionRow(stringResource(CommonR.string.common_copy)) { onCopy(item) }
            // Reporting goes to the provider; an imported pack has none.
            if (!item.source.onDevice) {
                MediaActionRow(stringResource(R.string.ime_media_report_action)) { onReport(item) }
            }
        }
    }
}

/** The pack and sticker behind a `local_…` grid item, from the packs the panel already holds. */
private fun ownSticker(
    item: GifItem,
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
): Pair<com.wasimaster.wmkeyboard.core.stickers.StickerPack, com.wasimaster.wmkeyboard.core.stickers.CustomSticker>? =
    packs.firstNotNullOfOrNull { pack ->
        val prefix = com.wasimaster.wmkeyboard.core.stickers.StickerPackStore.ITEM_PREFIX + pack.id + "_"
        if (!item.id.startsWith(prefix)) return@firstNotNullOfOrNull null
        pack.stickers.firstOrNull { it.id == item.id.removePrefix(prefix) }?.let { pack to it }
    }

/**
 * The head of the long-press sheet for one of the user's own stickers: the
 * title (or that it has none), the pack, then the word keywords and the emoji
 * on lines of their own, since those are two different ways of calling it up.
 */
@Composable
private fun OwnStickerInfo(
    pack: com.wasimaster.wmkeyboard.core.stickers.StickerPack,
    sticker: com.wasimaster.wmkeyboard.core.stickers.CustomSticker,
) {
    val kb = LocalKbTheme.current
    val (emoji, words) = sticker.keywords.partition {
        com.wasimaster.wmkeyboard.core.stickers.StickerKeywords.isEmoji(it)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            sticker.name.ifBlank { stringResource(R.string.ime_sticker_info_untitled) },
            color = kb.popupText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_pack, pack.name))
        if (words.isNotEmpty()) {
            OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_keywords, words.joinToString(", ")))
        }
        if (emoji.isNotEmpty()) {
            OwnStickerInfoLine(stringResource(R.string.ime_sticker_info_emoji, emoji.joinToString(" ")))
        }
    }
}

@Composable
private fun OwnStickerInfoLine(text: String) {
    Text(
        text,
        color = LocalKbTheme.current.secondaryText,
        fontSize = 12.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "Add to which pack?" for the add chip under All. The same scrim and surface
 * as [MediaActionSheet], for the same reason: an IME has no dialog.
 */
@Composable
private fun StickerAddPackSheet(
    packs: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            for (pack in packs) {
                MediaActionRow(stringResource(R.string.ime_sticker_add_to_pack_action, pack.name)) {
                    onPick(pack.id)
                }
            }
        }
    }
}

@Composable
private fun MediaActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = LocalKbTheme.current.popupText,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

@Composable
private fun GifGrid(
    items: List<GifItem>,
    downloadingId: String?,
    progress: Float?,
    /** Ids waiting to send after the one downloading, in order. */
    queue: List<String> = emptyList(),
    onSelect: (GifItem) -> Unit,
    onLongPress: ((GifItem) -> Unit)? = null,
    panel: PanelMode = PanelMode.GIF,
    focused: Int? = null,
    /** At most this many to a row, from the tool's setting. */
    perRow: Int = GifSources.DEFAULT_PER_ROW,
    /** The gap between cells, and around the grid's edge. */
    spacing: Dp = 4.dp,
    /** Watches the scroll for the header that folds away ([MediaChrome]); null, nothing does. */
    scroll: NestedScrollConnection? = null,
) {
    val loader = rememberMediaImageLoader()
    val listState = rememberLazyListState()
    // Justified rows instead of a fixed grid: each row shares one height and
    // every preview keeps its own aspect ratio, so nothing is cropped. Wide
    // GIFs pair up into rows of two; squarish ones sit [perRow] across.
    val rows = remember(items, perRow) { GifSources.rows(items, perRow) }
    val target = GifSources.targetRowRatio(perRow)
    // First flat index of each row, for mapping the focus ring to a row.
    val rowStarts = remember(rows) {
        var start = 0
        rows.map { row -> start.also { start += row.size } }
    }
    PanelFocusTarget(
        panel = panel,
        count = items.size,
        columns = perRow,
        onActivate = { index -> items.getOrNull(index)?.let(onSelect) },
    )
    ScrollFocusIntoView(focused) { index ->
        val rowIndex = rowStarts.indexOfLast { it <= index }.coerceAtLeast(0)
        listState.animateScrollToItem(rowIndex)
    }
    LazyColumn(
        state = listState,
        modifier = if (scroll != null) Modifier.fillMaxSize().nestedScroll(scroll) else Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        itemsIndexed(rows, key = { _, row -> row.first().id }) { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { indexInRow, item ->
                    val ratio = GifSources.cellRatio(item)
                    GifCell(
                        item = item,
                        loader = loader,
                        downloadingId = downloadingId,
                        progress = progress,
                        queuePosition = queue.indexOf(item.id) + 1,
                        focused = rowStarts[rowIndex] + indexInRow == focused,
                        onSelect = onSelect,
                        onLongPress = onLongPress,
                        modifier = Modifier
                            .weight(ratio)
                            .aspectRatio(ratio),
                    )
                }
                // Weights hand a sparse row the full width, which would blow
                // its height up; pad thin rows out to the target instead.
                val sum = row.fold(0f) { acc, item -> acc + GifSources.cellRatio(item) }
                if (sum < target) {
                    Spacer(Modifier.weight(target - sum))
                }
            }
        }
    }
}

@Composable
private fun GifCell(
    item: GifItem,
    loader: ImageLoader,
    downloadingId: String?,
    progress: Float?,
    /** Place in the send queue, 1 for next; 0 when it isn't waiting. */
    queuePosition: Int,
    focused: Boolean,
    onSelect: (GifItem) -> Unit,
    onLongPress: ((GifItem) -> Unit)?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .frostGlass(LocalKbTheme.current, RoundedCornerShape(8.dp), FrostSurface.CHIPS)
            .background(LocalKbTheme.current.chip)
            .focusRing(focused, RoundedCornerShape(8.dp))
            // Stays tappable while another sends: a tap then queues this one
            // behind it, and a second tap takes it back out.
            .combinedClickable(
                onClick = { onSelect(item) },
                onLongClick = onLongPress?.let { { it(item) } },
            ),
    ) {
        AsyncImage(
            model = item.previewUrl,
            contentDescription = item.title.ifBlank { stringResource(R.string.ime_gif_item_desc) },
            imageLoader = loader,
            modifier = Modifier.matchParentSize(),
            // Fit, not Crop: the cell already has the preview's shape, so
            // this only letterboxes the few whose ratio the cell clamped.
            contentScale = ContentScale.Fit,
        )
        if (downloadingId == item.id) MediaDownloadOverlay(progress)
        if (queuePosition > 0) MediaQueueBadge(queuePosition)
    }
}

/**
 * A waiting cell's place in the send queue: the preview dimmed a little, and
 * its number in the corner, so a row of taps reads as the order they go out.
 */
@Composable
private fun BoxScope.MediaQueueBadge(position: Int) {
    val theme = LocalKbTheme.current
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.25f)),
    )
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(theme.chipActive, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = position.toString(),
            color = theme.chipActiveText,
            fontSize = 12.sp,
            // The inherited 24 sp line box would sit the digit low in a 20 dp dot.
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * What a media cell wears while its file comes down: the preview dimmed, and
 * over it how far the transfer has got.
 *
 * [progress] is null until the first bytes arrive, and stays null for a server
 * that declared no size — those keep the indeterminate spinner, because a ring
 * frozen at zero says less than one that turns. The fill is animated between
 * the whole percents the service publishes so it sweeps rather than steps.
 */
@Composable
internal fun BoxScope.MediaDownloadOverlay(progress: Float?) {
    val fraction by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "mediaDownloadProgress",
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
            )
        } else {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

// ---- web search panel ----

/**
 * The search panel (full-bleed body; the search bar lives in the
 * [FullBleedTool] header). Web results and images are its two tabs (#879):
 * image search used to be a panel of its own, and its tool now opens the
 * Images tab here.
 */
@Composable
internal fun WebSearchPanel(
    state: KeyboardUiState,
    onRetry: () -> Unit,
    onResult: (WebResult) -> Unit,
    onOpen: (WebResult) -> Unit,
    onImageResult: (ImageResult) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val actions = LocalSearchActions.current
    // The result whose menu is open (#879), drawn over the whole panel.
    var menu by remember { mutableStateOf<SearchMenu?>(null) }
    // A new search, or the other tab, leaves no menu behind for a result gone.
    LaunchedEffect(state.searchTab, state.webSearch is WebSearchUi.Ready, state.imageSearch is ImageSearchUi.Ready) {
        menu = null
    }
    val onMenu: (SearchMenu) -> Unit = { menu = it }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchTabChips(state, actions, Modifier.weight(1f))
                // Pick mode (#727): which search provider answers, over the results.
                ProviderChooser(ProviderTool.SEARCH, state.settings, Modifier.padding(horizontal = 8.dp))
                SearchInBrowserButton(state)
            }
            when (state.searchTab) {
                SearchTab.ALL -> WebResults(state, onRetry, onResult, onOpen, onImageResult, onMenu, onOpenToolSettings)
                SearchTab.IMAGES -> ImageResults(state, onRetry, onImageResult, onMenu, onOpenToolSettings)
            }
        }
        menu?.let { shown ->
            SearchResultMenu(
                menu = shown,
                onInsertLink = { link -> onResult(WebResult(title = "", snippet = link, url = link, displayUrl = "")) },
                onInsertImage = onImageResult,
                onDismiss = { menu = null },
            )
        }
    }
}

/** A search result whose menu is open (#879). */
private sealed interface SearchMenu {
    data class Web(val result: WebResult) : SearchMenu
    data class Image(val result: ImageResult) : SearchMenu
}

/**
 * The menu of one search result (#879), over the panel: open it in the
 * browser, insert its link, copy the link, and for a picture also insert the
 * picture itself and save it to the gallery. A tap outside closes it.
 */
@Composable
private fun SearchResultMenu(
    menu: SearchMenu,
    onInsertLink: (String) -> Unit,
    onInsertImage: (ImageResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val actions = LocalSearchActions.current
    val (title, link, page) = when (menu) {
        is SearchMenu.Web -> Triple(menu.result.title, menu.result.url, menu.result.url)
        is SearchMenu.Image -> Triple(
            menu.result.title,
            menu.result.imageUrl,
            // The page a picture is on reads better than the bare file, when known.
            menu.result.contextUrl.ifEmpty { menu.result.imageUrl },
        )
    }
    fun closeThen(action: () -> Unit) {
        onDismiss()
        action()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            Text(
                title.ifBlank { hostOf(page) },
                color = kb.popupText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            if (menu is SearchMenu.Image) {
                MediaActionRow(stringResource(R.string.ime_search_menu_insert_image)) { closeThen { onInsertImage(menu.result) } }
            }
            MediaActionRow(stringResource(R.string.ime_web_search_open_desc)) { closeThen { actions.onOpen(page) } }
            MediaActionRow(stringResource(R.string.ime_web_search_insert_desc)) { closeThen { onInsertLink(link) } }
            MediaActionRow(stringResource(R.string.ime_search_menu_copy_link)) { closeThen { actions.onCopy(link) } }
            if (menu is SearchMenu.Image) {
                MediaActionRow(stringResource(R.string.ime_search_menu_save_image)) { closeThen { actions.onSaveImage(menu.result) } }
            }
        }
    }
}

/** The host of an address, for a result with no title of its own. */
private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

/**
 * All and Images, over the search panel's results (#879), and between them
 * the SearXNG categories the user chose (#934), while an instance is the
 * one asked. They scroll sideways when there are more than fit.
 */
@Composable
private fun SearchTabChips(state: KeyboardUiState, actions: SearchActions, modifier: Modifier = Modifier) {
    val picks by ProviderPicks.picks.collectAsState()
    val settings = state.settings
    val chosen = settings.webSearch.searxCategories
    val pick = picks[ProviderTool.SEARCH].orEmpty()
    val categories = remember(settings, pick) {
        if (chosen.isNotEmpty() && SearchProviders.searxFirst(settings, pick)) chosen else emptyList()
    }
    // A chip turned off in settings, or a provider that has none, is All again.
    val category = state.searchCategory?.takeIf { it in categories }
    val tab = state.searchTab
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchChip(stringResource(R.string.ime_search_tab_all), tab == SearchTab.ALL && category == null) {
            actions.onCategory(null)
        }
        for (name in categories) {
            SearchChip(SearxClient.categoryLabel(name), tab == SearchTab.ALL && category == name) {
                actions.onCategory(name)
            }
        }
        SearchChip(stringResource(R.string.ime_search_tab_images), tab == SearchTab.IMAGES) {
            actions.onTab(SearchTab.IMAGES)
        }
    }
}

@Composable
private fun SearchChip(label: String, active: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Text(
        label,
        color = if (active) kb.chipActiveText else kb.chipText,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (active) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/** The All tab: the engine's answer, then the web results. */
@Composable
private fun WebResults(
    state: KeyboardUiState,
    onRetry: () -> Unit,
    onResult: (WebResult) -> Unit,
    onOpen: (WebResult) -> Unit,
    onImageResult: (ImageResult) -> Unit,
    onMenu: (SearchMenu) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val openLink = LocalSearchActions.current.onOpen
    val kb = LocalKbTheme.current
    val thumbLoader = rememberMediaImageLoader()
    Column(modifier = Modifier.fillMaxSize()) {
        when (val ui = state.webSearch) {
            WebSearchUi.NeedKey -> PanelNotice(
                stringResource(R.string.ime_web_search_need_key_body),
                actionLabel = stringResource(R.string.ime_open_settings_action),
                onAction = { onOpenToolSettings(ToolbarTool.WEB_SEARCH) },
            )
            WebSearchUi.Idle -> PanelNotice(stringResource(R.string.ime_web_search_idle))
            WebSearchUi.Loading -> PanelSpinner()
            is WebSearchUi.Error -> PanelNotice(
                ui.message,
                actionLabel = stringResource(CommonR.string.common_retry),
                onAction = onRetry,
            )
            is WebSearchUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
            is WebSearchUi.Ready -> {
                if (ui.results.isEmpty() && ui.answer == null) {
                    PanelNotice(stringResource(R.string.ime_web_search_empty, ui.query))
                } else {
                    val focused = state.focusedIndex()
                    val listState = rememberLazyListState()
                    // What a tap does is the user's (#470, #879): the menu, or
                    // straight to inserting or opening, with the side button
                    // doing the other of the two. A long press is the menu.
                    val tap = state.settings.webSearch.resultTap
                    val openFirst = tap == SearchResultTap.OPEN
                    val rowAction: (WebResult) -> Unit = when (tap) {
                        SearchResultTap.MENU -> { result -> onMenu(SearchMenu.Web(result)) }
                        SearchResultTap.INSERT -> onResult
                        SearchResultTap.OPEN -> onOpen
                    }
                    val sideAction = if (openFirst) onResult else onOpen
                    // The answer box and its pictures lead the list when there
                    // are any, so focus indices start after them.
                    val lead = (if (ui.answer != null) 1 else 0) + (if (ui.images.isNotEmpty()) 1 else 0)
                    PanelFocusTarget(
                        panel = PanelMode.WEB_SEARCH,
                        count = ui.results.size,
                        columns = 1,
                        // The side icon stays touch-only; Enter does what a tap
                        // on the row does.
                        onActivate = { index -> ui.results.getOrNull(index)?.let(rowAction) },
                    )
                    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it + lead) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        ui.answer?.let { answer ->
                            item(key = "answer") {
                                WebSearchAnswer(answer)
                            }
                        }
                        if (ui.images.isNotEmpty()) {
                            item(key = "answerImages") {
                                AnswerImages(
                                    ui.images,
                                    state.mediaDownloadingId,
                                    state.mediaDownloadProgress,
                                    imageTap(state.settings.webSearch.resultTap, onImageResult, onMenu, openLink),
                                    onLongPress = { onMenu(SearchMenu.Image(it)) },
                                )
                            }
                        }
                        itemsIndexed(ui.results) { index, result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { rowAction(result) },
                                        onLongClick = { onMenu(SearchMenu.Web(result)) },
                                    )
                                    .focusRing(index == focused, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // A video's still, on a SearXNG Videos search (#934).
                                result.thumbnail?.let { thumb ->
                                    AsyncImage(
                                        model = thumb,
                                        contentDescription = null,
                                        imageLoader = thumbLoader,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(width = 80.dp, height = 45.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(kb.chip),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        result.title,
                                        color = kb.accent,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        // Which engines a SearXNG instance found it with (#932).
                                        if (result.engines.isEmpty()) {
                                            result.displayUrl
                                        } else {
                                            result.displayUrl + " · " + result.engines.joinToString(", ")
                                        },
                                        color = kb.secondaryText,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (result.snippet.isNotBlank()) {
                                        Text(
                                            result.snippet,
                                            color = kb.suggestionText,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (tap != SearchResultTap.MENU) IconHint(
                                    stringResource(
                                        if (openFirst) R.string.ime_web_search_insert_desc
                                        else R.string.ime_web_search_open_desc,
                                    ),
                                ) {
                                    IconButton(onClick = { sideAction(result) }) {
                                        Icon(
                                            if (openFirst) Icons.Outlined.AddLink else Icons.AutoMirrored.Outlined.OpenInNew,
                                            contentDescription = stringResource(
                                                if (openFirst) R.string.ime_web_search_insert_desc
                                                else R.string.ime_web_search_open_desc,
                                            ),
                                            modifier = Modifier.size(18.dp),
                                            tint = kb.toolbarIcon,
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "more") { MoreResultsButton(ui.loadingMore, ui.noMore) }
                    }
                }
            }
        }
    }
}

/**
 * More results, after the last result of either tab (#879): the same search
 * for its next page. A spinner while it loads; once a page brings nothing
 * new it says so instead of offering another.
 */
@Composable
private fun MoreResultsButton(loading: Boolean, noMore: Boolean) {
    val kb = LocalKbTheme.current
    val onMore = LocalSearchActions.current.onMore
    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp), color = kb.accent, strokeWidth = 2.5.dp)
            noMore -> Text(stringResource(R.string.ime_search_no_more), color = kb.secondaryText, fontSize = 12.sp)
            else -> {
                val shape = kb.chipShape()
                Text(
                    stringResource(R.string.ime_search_more),
                    color = kb.chipText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(shape)
                        .frostGlass(kb, shape, FrostSurface.CHIPS)
                        .background(kb.chip)
                        .chipBorder(kb, shape)
                        .clickable(onClick = onMore)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The backend's own answer to the query, above the results (#470): Tavily's
 * written answer, a SearXNG instance's instant answer. Folded to a few lines,
 * a tap opens the rest. Two icons beside its label (#879): Copy puts the whole
 * of it on the clipboard, Insert in the field, as plain text.
 *
 * Drawn as the AI chat draws an answer (#879): LaTeX formulas typeset,
 * otherwise Markdown, with code blocks in the chat's boxes and their own
 * Copy and Insert. A plain answer, which most are, reads as it always did.
 */
@Composable
private fun WebSearchAnswer(answer: String) {
    val kb = LocalKbTheme.current
    val actions = LocalSearchActions.current
    var expanded by remember(answer) { mutableStateOf(false) }
    val colors = ChatMarkdownColors(
        text = kb.chipText,
        dim = kb.chipText.copy(alpha = kb.chipText.alpha * 0.6f),
        codeBackground = kb.chipText.copy(alpha = 0.10f),
        link = kb.accent,
    )
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = kb.accent,
            )
            Text(
                stringResource(R.string.ime_web_search_answer_label),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            AnswerAction(Icons.Outlined.ContentCopy, stringResource(CommonR.string.common_copy)) { actions.onCopy(answer) }
            AnswerAction(Icons.AutoMirrored.Outlined.Input, stringResource(R.string.ime_insert_action)) {
                actions.onInsertText(AiMarkdown.strip(answer).ifBlank { answer })
            }
        }
        ChatMarkdown(
            answer,
            colors,
            // Folded, as many lines as the plain answer showed; the tap that
            // opens the rest is the box's own.
            modifier = if (expanded) Modifier else Modifier.heightIn(max = WebAnswerFoldedHeight).clipToBounds(),
            codeActions = { block ->
                CodeAction(stringResource(CommonR.string.common_copy), colors) { actions.onCopy(block.code) }
                CodeAction(stringResource(R.string.ime_insert_action), colors) { actions.onInsertText(block.code) }
            },
        )
    }
}

/** One of the answer box's icon buttons, its name on a long press. */
@Composable
private fun AnswerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconHint(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = LocalKbTheme.current.accent)
        }
    }
}

/**
 * A few pictures for the query under the engine's answer (#879): the ones
 * Tavily sends with it, at no extra cost. A tap does what it does on the
 * Images tab; a long press is the menu.
 */
@Composable
private fun AnswerImages(
    images: List<ImageResult>,
    downloadingId: String?,
    progress: Float?,
    onTap: (ImageResult) -> Unit,
    onLongPress: (ImageResult) -> Unit,
) {
    val loader = rememberMediaImageLoader()
    val kb = LocalKbTheme.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(images, key = { _, image -> image.imageUrl }) { _, image ->
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .frostGlass(kb, RoundedCornerShape(8.dp), FrostSurface.CHIPS)
                    .background(kb.chip)
                    .pointerInput(image.imageUrl, downloadingId == null) {
                        detectTapGestures(
                            onTap = { if (downloadingId == null) onTap(image) },
                            onLongPress = { onLongPress(image) },
                        )
                    },
            ) {
                AsyncImage(
                    model = image.thumbUrl,
                    contentDescription = image.title,
                    imageLoader = loader,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                if (downloadingId == image.imageUrl) MediaDownloadOverlay(progress)
            }
        }
    }
}

/** What a tap on a picture result does, from Tap a result (#879). */
private fun imageTap(
    tap: SearchResultTap,
    onInsert: (ImageResult) -> Unit,
    onMenu: (SearchMenu) -> Unit,
    onOpen: (String) -> Unit,
): (ImageResult) -> Unit = when (tap) {
    SearchResultTap.MENU -> { result -> onMenu(SearchMenu.Image(result)) }
    SearchResultTap.INSERT -> onInsert
    SearchResultTap.OPEN -> { result -> onOpen(result.contextUrl.ifEmpty { result.imageUrl }) }
}

/** How much of the answer shows before a tap opens the rest: four lines of it. */
private val WebAnswerFoldedHeight = 76.dp

// ---- image search panel ----

/**
 * The typed query as a search page in the browser (#915), beside the tabs,
 * for when the browser's own results are what is wanted. Nothing is fetched
 * here: the browser does the searching.
 */
@Composable
private fun SearchInBrowserButton(state: KeyboardUiState) {
    val onOpen = LocalSearchActions.current.onOpen
    val query = state.mediaQuery.trim()
    if (query.isEmpty()) return
    val kb = LocalKbTheme.current
    val label = stringResource(R.string.ime_search_in_browser_desc)
    IconHint(label) {
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = label,
            modifier = Modifier
                .padding(start = 4.dp, end = 8.dp)
                .size(20.dp)
                .clickable { onOpen(SearchProviders.browserUrl(state.settings, query)) },
            tint = kb.toolbarIcon,
        )
    }
}

/**
 * Camera button beside the search box (#349, #879): opens the camera tool
 * with Search in place of Send, to search by a photo instead of by words.
 */
@Composable
internal fun SearchByPhotoButton(onClick: () -> Unit) {
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.CAMERA),
        description = stringResource(R.string.ime_image_search_by_photo_desc),
        active = false,
        onClick = onClick,
    )
}

/**
 * The Images tab: grid of thumbnails. A tap opens the picture's menu, or
 * does what the user picked instead: inserts the image itself (via
 * commitContent, like clipboard images) or opens its page. A long press is
 * always the menu (#879).
 */
@Composable
private fun ImageResults(
    state: KeyboardUiState,
    onRetry: () -> Unit,
    onResult: (ImageResult) -> Unit,
    onMenu: (SearchMenu) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val onOpen = LocalSearchActions.current.onOpen
    Column(modifier = Modifier.fillMaxSize()) {
        when (val ui = state.imageSearch) {
            ImageSearchUi.NeedKey -> PanelNotice(
                stringResource(R.string.ime_image_search_need_key_body),
                actionLabel = stringResource(R.string.ime_open_settings_action),
                onAction = { onOpenToolSettings(ToolbarTool.WEB_SEARCH) },
            )
            ImageSearchUi.Idle -> PanelNotice(stringResource(R.string.ime_image_search_idle))
            ImageSearchUi.Loading -> PanelSpinner()
            is ImageSearchUi.Error -> PanelNotice(
                ui.message,
                actionLabel = stringResource(CommonR.string.common_retry),
                onAction = onRetry,
            )
            is ImageSearchUi.Metered -> MeteredNotice(ui.canAllow, onRetry)
            is ImageSearchUi.Ready -> {
                if (ui.results.isEmpty()) {
                    PanelNotice(stringResource(R.string.ime_image_search_empty, ui.query))
                } else {
                    ImageGrid(
                        results = ui.results,
                        loadingMore = ui.loadingMore,
                        noMore = ui.noMore,
                        downloadingId = state.mediaDownloadingId,
                        progress = state.mediaDownloadProgress,
                        columnCount = state.settings.emoji.mediaGridColumns,
                        onResult = imageTap(state.settings.webSearch.resultTap, onResult, onMenu, onOpen),
                        onResultLink = { result -> onMenu(SearchMenu.Image(result)) },
                        focused = state.focusedIndex(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageGrid(
    results: List<ImageResult>,
    loadingMore: Boolean,
    noMore: Boolean,
    downloadingId: String?,
    progress: Float?,
    /** Columns, from the user's setting. Two gives a bigger look before sending. */
    columnCount: Int,
    onResult: (ImageResult) -> Unit,
    onResultLink: (ImageResult) -> Unit,
    focused: Int? = null,
) {
    val loader = rememberMediaImageLoader()
    // imageUrl is the LazyGrid key, which must be unique — Brave can return the
    // same full-image URL for two results (a widely-reposted image), and a
    // duplicate key throws during composition and crashes the IME. Dedup first.
    val uniqueResults = remember(results) { results.distinctBy { it.imageUrl } }
    val gridState = rememberLazyGridState()
    // Indexed against the deduped list, which is what the grid actually draws.
    PanelFocusTarget(
        panel = PanelMode.WEB_SEARCH,
        count = uniqueResults.size,
        columns = columnCount,
        onActivate = { index ->
            if (downloadingId == null) uniqueResults.getOrNull(index)?.let(onResult)
        },
    )
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(uniqueResults, key = { _, result -> result.imageUrl }) { index, result ->
            Box(
                modifier = Modifier
                    .height(86.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .frostGlass(LocalKbTheme.current, RoundedCornerShape(8.dp), FrostSurface.CHIPS)
                    .background(LocalKbTheme.current.chip)
                    .focusRing(index == focused, RoundedCornerShape(8.dp))
                    .pointerInput(result.imageUrl, downloadingId == null) {
                        detectTapGestures(
                            onTap = { if (downloadingId == null) onResult(result) },
                            onLongPress = { onResultLink(result) },
                        )
                    },
            ) {
                AsyncImage(
                    model = result.thumbUrl,
                    contentDescription = result.title,
                    imageLoader = loader,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                if (downloadingId == result.imageUrl) MediaDownloadOverlay(progress)
            }
        }
        item(key = "more", span = { GridItemSpan(maxLineSpan) }) { MoreResultsButton(loadingMore, noMore) }
    }
}
