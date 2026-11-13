package com.wasimaster.wmkeyboard.app

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.SymbolsPage
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.compileLayoutSet
import com.wasimaster.wmkeyboard.ime.compileSecondaryGrids
import com.wasimaster.wmkeyboard.ime.ui.KeyPreviewBandMode
import com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPreviewBand
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyboardPreviewHost
import com.wasimaster.wmkeyboard.ime.ui.rememberAutoThemeDarkSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * One layout drawn by the keyboard itself, as a picture: the layout cards on a
 * language's screen.
 *
 * This is the real [KeyboardScreen], fed the state the service would build for
 * [layoutId] in a plain text field, so the card is the board the user gets and
 * not an impression of it: their theme and its photo, their fonts, key height,
 * number row, hints, bottom row, toolbar, and whatever the layout itself brings
 * (its own theme, its row heights, a Keyman layer's shape). The grids come from
 * [compileLayoutSet], the function the service compiles them with, so the two
 * cannot drift apart.
 *
 * Laid out at the width of the screen, which is the width the keyboard has when
 * it is up, and drawn scaled down to the width on offer. Scaling the drawn
 * result rather than measuring the keyboard narrower is what keeps a key as
 * tall for its width here as it is under the thumb. The height it reports is
 * the scaled height of the whole board, so a five-row layout draws taller than
 * a four-row one, as it will on screen.
 *
 * The board is composed once and then kept as a bitmap ([LayoutPreviewCache]):
 * a keyboard is far too much to compose for every card on a screen, and again
 * for every card a carousel scrolls back into view. The state and the cache key
 * are worked out off the main thread; only the composition itself is not, and
 * the cards take turns at it ([BoardRenderGate]) so a screen of them spreads
 * over frames instead of stalling one, and none of them starts while the list
 * holding them scrolls ([LocalLayoutPreviewScrolling]). A card scrolled back
 * into view with nothing changed finds its picture on the main thread, in the
 * frame it appears. Until a card's turn comes it shows a
 * skeleton, or, when the settings changed under a picture it already has, that
 * picture, repainted in place once the new one is taken.
 *
 * It is a picture and nothing more: touches, focus and the screen reader all
 * stop at its edge, so a tap or a swipe over it belongs to whatever holds it —
 * the card's toggle, the carousel's scroll — and TalkBack reads the card's name
 * rather than forty keys.
 */
@Composable
fun LayoutKeyboardPreview(
    settings: KeyboardSettings,
    layoutId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val form = remember(configuration.smallestScreenWidthDp) {
        DeviceForm.of(configuration.smallestScreenWidthDp)
    }
    // Both asked once per process and per configuration rather than once per
    // card: a card scrolling into view is a first composition, and a binder
    // call and five colour lookups each time are frames a fling cannot spare.
    val television = remember(context) { LayoutPreviewCache.television(context) }
    val density = LocalDensity.current
    val systemDark = isSystemInDarkTheme()
    val darkSlot = rememberAutoThemeDarkSlot(settings, systemDark)
    val environment = remember(context, configuration, density, systemDark, darkSlot) {
        LayoutPreviewCache.environment(context, configuration, density, systemDark, darkSlot)
    }
    val scrolling = LocalLayoutPreviewScrolling.current
    // What the card shows: the picture for these settings once there is one,
    // and until then the last picture of this layout, if this process has
    // taken one.
    var picture by remember(layoutId) { mutableStateOf(LayoutPreviewCache.stale(layoutId)) }
    // The board being composed to take the picture from, or null.
    var render by remember(layoutId) { mutableStateOf<BoardRender?>(null) }
    // Whether the next picture fades in. Not when it replaces the live board
    // on show: the two are the same pixels, and a fade would start from
    // nothing the moment the board goes.
    var fade by remember { mutableStateOf(true) }
    var widthPx by remember { mutableIntStateOf(0) }
    val layer = rememberGraphicsLayer()
    // When the card came into view. A picture that turns up within a few
    // frames of it — the memory or disk cache answering while a fling carries
    // the card in — replaces the skeleton outright: a skeleton crossfading to
    // a keyboard on every card a fling passes reads as the row flickering.
    val bornAt = remember(layoutId) { SystemClock.uptimeMillis() }

    LaunchedEffect(settings, layoutId, form, television, environment, widthPx) {
        if (widthPx == 0) return@LaunchedEffect
        val inputs = PreviewInputs(settings, form, television, environment, widthPx)
        // A card scrolled back into view with nothing changed since its
        // picture was taken: the picture is known without leaving the main
        // thread, and nothing more is worked out for it.
        LayoutPreviewCache.knownKey(layoutId, inputs)?.let { LayoutPreviewCache.get(it) }?.let { known ->
            if (picture !== known) {
                fade = picture != null
                picture = known
            }
            render = null
            return@LaunchedEffect
        }
        // The key needs only the settings the card is drawn with; the grids,
        // which for a Keyman layout are the costly part, only a render needs.
        val (spec, shown, key) = withContext(Dispatchers.Default) {
            val (spec, shown) = layoutPreviewShown(settings, layoutId)
            Triple(
                spec,
                shown,
                LayoutPreviewCache.keyOf(context, settings, spec.id, shown, form, television, environment, widthPx),
            )
        }
        LayoutPreviewCache.noteKey(layoutId, inputs, key)
        val cached = LayoutPreviewCache.get(key) ?: LayoutPreviewCache.load(context, key)
        if (cached == null && picture == null) {
            // No picture for these settings, and nothing on the card yet:
            // the last one kept of this layout goes up straight away, and the
            // new one is drawn over it, rather than a skeleton in between.
            LayoutPreviewCache.loadLast(context, layoutId, widthPx)?.let { (lastKey, last) ->
                LayoutPreviewCache.put(layoutId, lastKey, last)
                if (picture == null) {
                    fade = SystemClock.uptimeMillis() - bornAt >= QUICK_PICTURE_MS
                    picture = last
                }
            }
        }
        if (cached != null) {
            LayoutPreviewCache.put(layoutId, key, cached)
            if (picture !== cached) {
                val quick = SystemClock.uptimeMillis() - bornAt < QUICK_PICTURE_MS
                fade = picture != null || (render == null && !quick)
                picture = cached
            }
            render = null
            return@LaunchedEffect
        }
        val job = BoardRender(
            withContext(Dispatchers.Default) { layoutPreviewState(spec, shown, form, television) },
        )
        // Not while the row or the grid is moving: composing a keyboard takes
        // a frame or more of the main thread, and a fling drops every one of
        // them. The cards passed over on the way wait, and the ones that are
        // still in view when the scroll settles take their turn then.
        snapshotFlow { scrolling() }.first { !it }
        BoardRenderGate.mutex.withLock {
            snapshotFlow { scrolling() }.first { !it }
            render = job
            // Held until the board has composed and drawn, and a frame or two
            // past that for whatever it recomposes on its first frame; then
            // the next card's turn. A card composed but never placed — the
            // one a lazy row prefetches — does not draw, and gives its turn
            // up rather than hold the queue.
            withTimeoutOrNull(FIRST_DRAW_TIMEOUT_MS) {
                job.drawn.await()
                repeat(SETTLE_FRAMES) { withFrameNanos { } }
            }
        }
        job.drawn.await()
        // What the board loads for itself — an icon pack, key textures, a
        // photo — arrives a moment after it first draws. A first picture goes
        // up once most of it is in; the one kept comes later, once the rest
        // has had time too, so a slow photo decode cannot leave a picture
        // without its photo in the cache for good. A card scrolled away
        // before then keeps nothing and simply renders again next time.
        delay(SETTLE_MS)
        val first = layer.toImageBitmap()
        LayoutPreviewCache.putProvisional(layoutId, key, first)
        fade = picture != null
        picture = first
        delay(KEEP_SETTLE_MS)
        val kept = layer.toImageBitmap()
        LayoutPreviewCache.put(layoutId, key, kept)
        LayoutPreviewCache.save(context, layoutId, widthPx, key, kept)
        fade = false
        picture = kept
        render = null
    }

    // The picture on top, and what it is fading in over. Not a Crossfade:
    // that fades the old picture out while the new one fades in, and two
    // opaque pictures at half strength each let a quarter of the card show
    // through at the midpoint. A card swapping a stale picture for the exact
    // one — which a fast scroll does on card after card — dipped and came
    // back. Here the old one stays solid until the new one is fully in.
    var top by remember(layoutId) { mutableStateOf(picture) }
    var below by remember(layoutId) { mutableStateOf<ImageBitmap?>(null) }
    var belowSkeleton by remember(layoutId) { mutableStateOf(false) }
    val fadeIn = remember(layoutId) { Animatable(1f) }
    LaunchedEffect(picture) {
        val next = picture
        if (next === top) return@LaunchedEffect
        if (fade && next != null) {
            below = top
            belowSkeleton = top == null
            top = next
            fadeIn.snapTo(0f)
            fadeIn.animateTo(1f, tween(PICTURE_FADE_MS))
        } else {
            fadeIn.snapTo(1f)
            top = next
        }
        below = null
        belowSkeleton = false
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width }
            .clearAndSetSemantics {}
            // A D-pad or a hardware Tab walking the settings screen must not
            // wander into the preview's toolbar buttons.
            .focusProperties { onEnter = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        val job = render
        // What was showing before, held fully opaque underneath while the
        // new picture fades in over it — or the skeleton, when that was it.
        val under = below
        if (under != null) {
            PreviewPicture(under)
        } else if (top == null || belowSkeleton) {
            if (job == null || belowSkeleton) BoardSkeleton(LayoutPreviewCache.lastAspect)
        }
        val shownTop = top
        if (shownTop != null) {
            PreviewPicture(shownTop, Modifier.graphicsLayer { alpha = fadeIn.value })
        }
        if (job != null) {
            // Drawn only when there is no picture to show: otherwise the board
            // is recorded for the picture and nothing else, and the old
            // picture stays up until the new one replaces it.
            val visible = shownTop == null
            LiveBoard(
                state = job.state,
                modifier = Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    if (visible) drawLayer(layer)
                    job.drawn.complete(Unit)
                },
            )
        }
    }
}

/** One picture of a board, as wide as the card and as tall as its own shape. */
@Composable
private fun PreviewPicture(picture: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = picture,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(picture.width.toFloat() / picture.height.coerceAtLeast(1)),
    )
}

/**
 * Whether the list holding the cards is scrolling, read by every card before
 * it composes a board: the carousel and the More layouts grid provide their
 * own state's `isScrollInProgress`. Outside either, never.
 */
val LocalLayoutPreviewScrolling = staticCompositionLocalOf<() -> Boolean> { { false } }

/** A board waiting to be composed and captured. */
private class BoardRender(val state: KeyboardUiState) {
    val drawn = CompletableDeferred<Unit>()
}

/** The turn cards take at composing a board, one at a time. */
private object BoardRenderGate {
    val mutex = Mutex()
}

/**
 * The live keyboard behind a picture. Laid out at the screen's width and
 * scaled to the card's by [ScaledBoard].
 */
@Composable
private fun LiveBoard(state: KeyboardUiState, modifier: Modifier) {
    // The screen wants a flow, as the service hands it one. Published after
    // the composition that built it, so the collector sees each state once.
    val stateFlow = remember { MutableStateFlow(state) }
    SideEffect { stateFlow.value = state }
    Box(modifier) {
        ScaledBoard {
            CompositionLocalProvider(
                // Clipped to the card, so a bubble has nowhere to escape to;
                // and no band either, which would be empty board over the keys.
                LocalKeyPreviewBand provides KeyPreviewBandMode.INSIDE,
                LocalKeyboardPreviewHost provides true,
            ) {
                // The card is nowhere near the navigation bar, and the board
                // pads for one all the same: consumed so there is no phantom
                // band along its floor.
                Box(Modifier.consumeWindowInsets(WindowInsets.navigationBars)) {
                    KeyboardScreen(
                        stateFlow = stateFlow,
                        onKey = Inert.key,
                        onSuggestion = Inert.text,
                        onEmoji = Inert.text,
                        onEmojiQueryTap = Inert.none,
                        onPanelChange = Inert.panel,
                        onClipboardItem = Inert.clip,
                        onClipboardPin = Inert.clip,
                        onClipboardDelete = Inert.clip,
                    )
                }
            }
        }
        // Over the board, as a sibling: a sibling on top wins the hit test, so
        // no key, strip or tool under it ever sees a finger. It consumes
        // nothing, so the press and the drag still reach the card and the
        // carousel around it.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        )
    }
}

/**
 * A card's stand-in until its picture is taken: a board's outline, a strip
 * along the top and four rows of keys, pulsing. Drawn rather than composed key
 * by key, and animated in the draw phase, so a screen of them costs nothing.
 */
@Composable
private fun BoardSkeleton(aspect: Float) {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "boardSkeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
        label = "boardSkeletonPulse",
    )
    // The keys come in only once the wait is a real one. Most cards find
    // their picture in a frame or two, and a skeleton's keys flashing up for
    // those frames is the flicker a fast fling showed on every card.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MS)
        reveal.animateTo(1f, tween(SKELETON_REVEAL_MS))
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect),
    ) {
        drawRect(colors.surfaceContainerHighest)
        if (reveal.value == 0f) return@Canvas
        val key = colors.surface.copy(alpha = pulse.value * reveal.value)
        val pad = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        // The strip takes the share a toolbar does; the keys split the rest.
        val strip = size.height * 0.14f
        drawRoundRect(
            key,
            topLeft = Offset(pad, pad),
            size = Size(size.width * 0.4f, strip - pad * 1.5f),
            cornerRadius = radius,
        )
        val rows = SkeletonRows.size
        val rowHeight = (size.height - strip - pad - gap * (rows - 1)) / rows
        val span = size.width - pad * 2
        val unit = (span - gap * (SKELETON_ROW_UNITS - 1)) / SKELETON_ROW_UNITS
        for ((index, widths) in SkeletonRows.withIndex()) {
            val top = strip + index * (rowHeight + gap)
            val used = widths.sum() * unit + gap * (widths.size - 1)
            var left = pad + (span - used) / 2f
            for (width in widths) {
                drawRoundRect(
                    key,
                    topLeft = Offset(left, top),
                    size = Size(width * unit, rowHeight),
                    cornerRadius = radius,
                )
                left += width * unit + gap
            }
        }
    }
}

/** A QWERTY board's rows, in key widths; the widest row sets the unit. */
private val SkeletonRows = listOf(
    List(10) { 1f },
    List(9) { 1f },
    listOf(1.5f) + List(7) { 1f } + listOf(1.5f),
    listOf(1.5f, 1f, 4f, 1f, 1.5f),
)
private const val SKELETON_ROW_UNITS = 10

private const val FIRST_DRAW_TIMEOUT_MS = 600L
private const val SETTLE_FRAMES = 2
private const val SETTLE_MS = 250L
private const val KEEP_SETTLE_MS = 1_200L
private const val PICTURE_FADE_MS = 180
private const val SKELETON_PULSE_MS = 700
private const val SKELETON_DELAY_MS = 160L
private const val SKELETON_REVEAL_MS = 220

/** How soon after a card appears a picture still counts as already there. */
private const val QUICK_PICTURE_MS = 160L

/**
 * The callbacks every preview hands the keyboard. Nothing reaches them, since
 * the board takes no touches; they are shared instances so the grid sees the
 * same lambda on every composition and keeps skipping.
 */
private object Inert {
    val key: (Key) -> Unit = {}
    val text: (String) -> Unit = {}
    val none: () -> Unit = {}
    val panel: (PanelMode) -> Unit = {}
    val clip: (ClipItem) -> Unit = {}
}

/**
 * The state the service would publish for [layoutId] in a plain text field with
 * nothing typed, on [form].
 *
 * The layout is shown as switched on even while it is not, since that is the
 * only way anyone types on it: a spacebar that names the layout once a second
 * one of the language is on, or the language arrows that appear with a second
 * layout, read the enabled list, and a card should show what the board will
 * look like after the tap, not a board nobody can reach.
 *
 * Floating and one-handed are left out. Both are a frame around the board that
 * needs a window to sit in, and a card of a one-handed board is a card about
 * the frame rather than the layout.
 */
internal fun layoutPreviewState(
    settings: KeyboardSettings,
    layoutId: String,
    form: DeviceForm,
    television: Boolean,
): KeyboardUiState {
    val (spec, shown) = layoutPreviewShown(settings, layoutId)
    return layoutPreviewState(spec, shown, form, television)
}

/** The layout [layoutId] resolves to, and the settings its card is drawn with. */
private fun layoutPreviewShown(
    settings: KeyboardSettings,
    layoutId: String,
): Pair<LayoutSpec, KeyboardSettings> {
    val spec = resolveLayout(settings.customLayouts, layoutId)
    val enabled = if (spec.id in settings.enabledLayoutIds) {
        settings.enabledLayoutIds
    } else {
        settings.enabledLayoutIds + spec.id
    }
    return spec to settings.copy(
        activeLayoutId = spec.id,
        enabledLayoutIds = enabled,
        floatingKeyboard = false,
        floatingKeyboardLandscape = false,
        oneHandedMode = OneHandedMode.OFF,
    )
}

private fun layoutPreviewState(
    spec: LayoutSpec,
    shown: KeyboardSettings,
    form: DeviceForm,
    television: Boolean,
): KeyboardUiState {
    val script = spec.script()
    return KeyboardUiState(
        settings = shown,
        language = spec.language(),
        script = script,
        composer = composerFor(script, spec.composerType(), spec.langId),
        layoutId = spec.id,
        layoutName = spec.name,
        layouts = PreviewLayoutSets.get(
            spec, form, shown.numberRow, shown.layoutBehavior.symbolsPage, shown.customLayouts, television,
        ),
    )
}

/**
 * The compiled grids behind the cards, kept across recompositions and across
 * visits to the screen.
 *
 * A card is rebuilt whenever the settings change — switching a layout on is a
 * settings change — and a carousel scrolled back and forth composes the same
 * cards again and again. Compiling five layers of a Keyman grid each time would
 * be the most expensive thing a card does, for a result that has not changed.
 * Keyed like the service's own cache: by the spec's value, and by the secondary
 * list's identity, which the settings flow keeps until the list changes.
 */
private object PreviewLayoutSets {
    private data class Key(
        val spec: LayoutSpec,
        val form: DeviceForm,
        val numberRow: Boolean,
        val symbolsPage: SymbolsPage,
        val television: Boolean,
    )

    private const val CAPACITY = 32

    /**
     * How many compiled keys the cache may hold in total, across however few
     * or many entries that turns out to be.
     *
     * The count alone was the wrong unit. Nearly every layout compiles to some
     * fifty keys, so thirty-two of them is nothing — but the converted Keyman
     * grids for the Ethiopic scripts run to thousands of keys apiece, and the
     * previews screen is exactly where somebody scrolls past a dozen of those
     * in a row. This object outlives the screen (it is a process singleton, in
     * the process the keyboard runs in), so what it was holding was held for
     * good. A key budget tracks the real cost closely enough and costs one
     * addition per compile to maintain.
     */
    private const val KEY_BUDGET = 20_000

    private val sets = LinkedHashMap<Key, Pair<Map<String, KeyboardLayout>, LayoutSet>>(
        CAPACITY, 0.75f, true,
    )

    /** Compiled key counts, parallel to [sets], and their running total. */
    private val weights = HashMap<Key, Int>()
    private var weight = 0

    /** The keys a spec compiles to, near enough: every layer's grid and its number row. */
    private fun keyCount(spec: LayoutSpec): Int =
        spec.layers.values.sumOf { layer ->
            layer.rows.sumOf { it.size } + (layer.numberRow?.size ?: 0)
        }

    /** Drops least-recently-used entries until the budget holds, keeping [keep]. */
    private fun trim(keep: Key) {
        val stale = sets.keys.iterator()
        while ((weight > KEY_BUDGET || sets.size > CAPACITY) && stale.hasNext()) {
            val key = stale.next()
            if (key == keep) continue
            weight -= weights.remove(key) ?: 0
            stale.remove()
        }
    }

    private var secondaryCache: Pair<List<LayoutSpec>, Map<String, KeyboardLayout>>? = null

    @Synchronized
    fun get(
        spec: LayoutSpec,
        form: DeviceForm,
        numberRow: Boolean,
        symbolsPage: SymbolsPage,
        customs: List<LayoutSpec>,
        television: Boolean,
    ): LayoutSet {
        val secondaries = secondaryCache?.takeIf { it.first === customs }?.second
            ?: compileSecondaryGrids(customs).also { secondaryCache = customs to it }
        val key = Key(spec, form, numberRow, symbolsPage, television)
        sets[key]?.let { (grids, set) -> if (grids === secondaries) return set }
        val set = compileLayoutSet(spec, FieldKind.TEXT, form, numberRow, secondaries, television, symbolsPage = symbolsPage)
        weight -= weights.remove(key) ?: 0
        sets[key] = secondaries to set
        weights[key] = keyCount(spec).also { weight += it }
        trim(keep = key)
        return set
    }
}

/**
 * Measures [content] at the screen's width, bounded by the screen's height as
 * the keyboard's window bounds it, and draws it scaled to the width on offer.
 * Reports the scaled size, so the caller's height follows the board's.
 *
 * The scale goes on the placement layer, so anything clipping this node clips
 * the board where it is drawn rather than where it was measured.
 */
@Composable
private fun ScaledBoard(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    Layout(content = content) { measurables, constraints ->
        val fullWidth = screenWidthDp.dp.roundToPx().coerceAtLeast(1)
        val placeable = measurables.first().measure(
            Constraints(
                minWidth = fullWidth,
                maxWidth = fullWidth,
                maxHeight = screenHeightDp.dp.roundToPx().coerceAtLeast(1),
            ),
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fullWidth
        val scale = width.toFloat() / placeable.width.coerceAtLeast(1)
        val height = (placeable.height * scale).roundToInt()
        layout(width, constraints.constrainHeight(height)) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
