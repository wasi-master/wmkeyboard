package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.ArcMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.rounded.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.rounded.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.KeymanLayoutPackDownloader
import com.wasimaster.wmkeyboard.core.layout.KeymanLayoutPack
import com.wasimaster.wmkeyboard.core.layout.resolveLayout

/**
 * A language's layouts as a row of cards, each one the keyboard itself drawn
 * small (see [LayoutKeyboardPreview]) over a pill with the layout's name and a
 * tick. Tapping a card switches that layout on or off, so the row is both the
 * picture and the control.
 *
 * It replaced a switch per layout, which spent a full-width row on each of
 * English's eight layouts for a choice most people make once. Side by side,
 * the cards take the height of one small keyboard and answer the question the
 * names alone could not: what typing on this one looks like, in this theme.
 *
 * The row scrolls freely, the way a shelf does, rather than snapping a card to
 * its edge: the cards are small enough that two and a bit are in view, and a
 * snap would keep pulling the one being read out from under the eye.
 *
 * [moreCount] layouts the language keeps on its More layouts page are offered
 * by a last card, the size of the others, that opens it with [onMore].
 *
 * [onToggle] is asked to switch a layout and answers whether it did, so a card
 * the caller refuses — the last layout left on — neither ticks nor latches.
 */
@Composable
internal fun LayoutCarousel(
    layoutIds: List<String>,
    settings: LiveSettings,
    onToggle: (layoutId: String, enable: Boolean) -> Boolean,
    moreCount: Int = 0,
    onMore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (layoutIds.isEmpty() && moreCount == 0) return
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Small enough that the next card shows at the edge and says the row
        // scrolls, as a store's shelf of keyboards does; large enough that the
        // keys still read. Capped for tablets and landscape.
        val cardWidth = layoutCardWidth(maxWidth)
        val density = LocalDensity.current
        // How far into the card before it the row starts: all of that card
        // and its gap, less the sliver left showing.
        val leadPx = with(density) { (cardWidth + CardGap - CardPeek).roundToPx() }.coerceAtLeast(0)
        // Opens on the first layout that is on, with a sliver of the card
        // before it showing so the row reads as having a start somewhere to
        // the left. Only where the state is fresh: coming back to the screen
        // restores wherever the user left the row.
        val firstOn = settings.watch { s ->
            layoutIds.indexOfFirst { it in s.enabledLayoutIds }.coerceAtLeast(0)
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (firstOn - 1).coerceAtLeast(0),
            initialFirstVisibleItemScrollOffset = if (firstOn > 0) leadPx else 0,
        )
        // The row never gets shorter than the tallest card it has shown. The
        // cards are as tall as their boards, and a five-row layout scrolling
        // out of view would otherwise pull everything under the row up by a
        // key's height, and push it back down on the way back.
        val tallest = remember(cardWidth) { intArrayOf(0) }
        // The tallest board drawn so far, which the More card borrows so it
        // stands as tall as the cards beside it.
        val boardHeight = remember(cardWidth) { mutableIntStateOf(0) }
        // Names wrap rather than cut off, and each card is as tall as its own
        // name. Levelling them to the longest name on the shelf left a one-line
        // name over two or three lines of nothing on every other card.
        val names = rememberLayoutNames(layoutIds, settings)
        // No card composes a keyboard to capture while the row moves, or while
        // the cards are flying to or from the layout gallery.
        val scrolling = rememberPreviewsHeld { listState.isScrollInProgress }
        // Where the row is, for the flights: the card at the row's start
        // leaves first, so the stagger runs from the reader's left edge
        // whatever the scroll.
        val firstShown = remember(listState) { { listState.firstVisibleItemIndex } }
        CompositionLocalProvider(LocalLayoutPreviewScrolling provides scrolling) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = CarouselEdge),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                // Top, so the boards start level along the row and each card
                // ends where its own name does, the way a store's shelf does.
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (reduceMotion) Modifier
                        else Modifier.animateContentSize(spring(stiffness = GROW_STIFFNESS)),
                    )
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0))
                        tallest[0] = maxOf(tallest[0], placeable.height)
                        val height = constraints.constrainHeight(tallest[0])
                        layout(placeable.width, height) {
                            placeable.place(0, 0)
                        }
                    },
            ) {
                itemsIndexed(layoutIds, key = { _, id -> id }) { index, layoutId ->
                    val on = settings.watch { layoutId in it.enabledLayoutIds }
                    LayoutCard(
                        name = names[layoutId].orEmpty(),
                        layoutId = layoutId,
                        on = on,
                        settings = settings,
                        modifier = Modifier
                            .width(cardWidth)
                            .layoutCardFlight(layoutCardFlightKey(layoutId)) { index - firstShown() },
                        boardHeight = boardHeight,
                        onToggle = { enable -> onToggle(layoutId, enable) },
                    )
                }
                if (moreCount > 0) {
                    item(key = MORE_CARD_KEY) {
                        MoreLayoutsCard(
                            count = moreCount,
                            boardHeight = boardHeight.intValue,
                            width = cardWidth,
                            nameLines = 1,
                            onClick = onMore,
                            modifier = Modifier.layoutCardFlight(MORE_CARD_FLIGHT) { layoutIds.size - firstShown() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One layout's card: the board, and under it a pill with a tick and the name,
 * then whatever [footer] the page adds (the More layouts page puts the
 * layout's typing-rules state there).
 *
 * Switched on, the card takes a tint, the board a ring and the pill a filled
 * tick, so the state reads from any one of the three at a glance. The ring and
 * tint are drawn rather than laid out, so turning a card on redraws it without
 * recomposing the keyboard inside.
 *
 * Only the board and the pill toggle the layout; a [footer] keeps its own
 * controls, so a press on a download button cannot also switch the layout.
 *
 * [boardHeight], when given, is raised to the tallest board this card has
 * drawn, for a neighbour that wants to match it.
 *
 * The name wraps onto as many lines as it needs, and takes at least
 * [nameLines], so a grid row of cards can keep its pills level.
 */
@Composable
internal fun LayoutCard(
    name: String,
    layoutId: String,
    on: Boolean,
    settings: LiveSettings,
    onToggle: (Boolean) -> Boolean,
    modifier: Modifier = Modifier,
    boardHeight: MutableIntState? = null,
    nameLines: Int = 1,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberSettingsHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val fade = if (reduceMotion) snap<Float>() else tween(durationMillis = SELECT_MS)
    // One number drives the ring and the tick together.
    val selection by animateFloatAsState(if (on) 1f else 0f, fade, label = "layoutCardOn")
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) PRESSED_SCALE else 1f,
        spring(stiffness = PRESS_STIFFNESS),
        label = "layoutCardPress",
    )
    val tint by animateColorAsState(
        if (on) colors.secondaryContainer else colors.surfaceContainer,
        if (reduceMotion) snap() else tween(durationMillis = SELECT_MS),
        label = "layoutCardTint",
    )
    val ring by animateDpAsState(
        if (on) RingOn else RingOff,
        if (reduceMotion) snap() else tween(durationMillis = SELECT_MS),
        label = "layoutCardRing",
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .drawWithCache { onDrawBehind { drawRect(tint) } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = on,
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Checkbox,
                ) { enable ->
                    if (onToggle(enable)) haptics.toggle(enable) else haptics.tick()
                }
                .padding(CardInset),
        ) {
            val boardModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (boardHeight == null) Modifier
                    else Modifier.onSizeChanged {
                        if (it.height > boardHeight.intValue) boardHeight.intValue = it.height
                    },
                )
                .clip(BoardShape)
                .drawWithCache {
                    val outline = BoardShape.createOutline(size, layoutDirection, this)
                    onDrawWithContent {
                        drawContent()
                        // Centred on the edge and twice as wide as the
                        // ring, so the clip leaves exactly the ring inside.
                        drawOutline(
                            outline,
                            color = lerp(colors.outlineVariant, colors.primary, selection),
                            style = Stroke(width = ring.toPx() * 2f),
                        )
                    }
                }
            // A Keyman grid whose pack is not here would resolve to QWERTY, and
            // a picture of that would both mislead and outlive the wait in the
            // preview cache, so nothing is drawn until the real grid can be.
            val packRevision by KeymanLayoutPack.revision.collectAsState()
            val packMissing = remember(packRevision, layoutId) { KeymanLayoutPack.isMissing(layoutId) }
            if (packMissing) {
                KeymanPackPlaceholder(boardModifier)
            } else {
                LayoutKeyboardPreview(
                    settings = settings.watch { it },
                    layoutId = layoutId,
                    modifier = boardModifier,
                )
            }
            Spacer(Modifier.height(PillGap))
            Row(
                // Level with the first line of a name that wraps.
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PillInset, vertical = PillPad),
            ) {
                Tick(selection)
                Spacer(Modifier.width(TickGap))
                Text(
                    name,
                    style = layoutNameStyle(),
                    color = if (on) colors.onSecondaryContainer else colors.onSurface,
                    minLines = nameLines,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        footer?.invoke(this)
    }
}

/**
 * The last card of the row: the way to the language's More layouts page, drawn
 * the size of a layout card so the row ends on a card rather than on a link.
 * Its face is the board's space with a count in it, so it reads as "more of
 * what is to the left" before a word of it is read.
 *
 * [boardHeight] is the tallest board the row has drawn, in pixels; until one
 * has been drawn it falls back to a keyboard's usual proportions.
 */
@Composable
internal fun MoreLayoutsCard(
    count: Int,
    boardHeight: Int,
    width: Dp,
    nameLines: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val faceHeight = if (boardHeight > 0) {
        with(density) { boardHeight.toDp() }
    } else {
        (width - CardInset * 2) / FALLBACK_BOARD_ASPECT
    }
    Column(
        modifier = modifier
            .width(width)
            .clip(CardShape)
            .background(colors.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(CardInset),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(faceHeight)
                .clip(BoardShape)
                .background(colors.surfaceContainerHighest)
                .padding(8.dp),
        ) {
            Text(
                stringResource(R.string.languages_more_layouts_count, count),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                maxLines = 1,
            )
            Text(
                pluralStringResource(R.plurals.languages_more_layouts_card_body, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(PillGap))
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PillInset, vertical = PillPad),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(TickSize)
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(TickSize * 0.7f),
                )
            }
            Spacer(Modifier.width(TickGap))
            Text(
                stringResource(R.string.languages_more_layouts_title),
                style = layoutNameStyle(),
                minLines = nameLines,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The tick in the pill: an empty ring off, a filled disc with a check on,
 * crossfaded by [selection] so a tap reads as the one control it is.
 */
@Composable
private fun Tick(selection: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TickSize)
            .drawWithCache {
                val stroke = Stroke(width = 2.dp.toPx())
                onDrawBehind {
                    val radius = size.minDimension / 2f
                    drawCircle(
                        colors.outline.copy(alpha = 1f - selection),
                        radius = radius - stroke.width / 2f,
                        style = stroke,
                    )
                    drawCircle(colors.primary.copy(alpha = selection), radius = radius * (0.6f + 0.4f * selection))
                }
            },
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier
                .size(TickSize * 0.7f)
                .graphicsLayer {
                    alpha = selection
                    scaleX = 0.5f + 0.5f * selection
                    scaleY = 0.5f + 0.5f * selection
                },
        )
    }
}

/**
 * How wide a card in the row is, given the width the row has: small enough that
 * the next card shows at the edge, large enough that the keys still read.
 */
internal fun layoutCardWidth(maxWidth: Dp): Dp =
    (maxWidth * CARD_WIDTH_SHARE)
        .coerceIn(CardMinWidth, CardMaxWidth)
        .coerceAtMost(maxWidth - CarouselEdge * 2)

/** A card's name, in the label size the compact pill is drawn with. */
@Composable
private fun layoutNameStyle() = MaterialTheme.typography.labelMedium

/** Each layout's name, resolved once per custom-layout list. */
@Composable
internal fun rememberLayoutNames(layoutIds: List<String>, settings: LiveSettings): Map<String, String> {
    val customLayouts = settings.watch { it.customLayouts }
    return remember(customLayouts, layoutIds) {
        layoutIds.associateWith { resolveLayout(customLayouts, it).name }
    }
}

/**
 * How many lines each of [texts] takes as a card's name on a card [cardWidth]
 * wide, in order. A grid levels each row of cards to the longest name in that
 * row, so a row of one-line names stays tight and only the row with a long one
 * grows.
 */
@Composable
internal fun rememberLayoutNameLineCounts(texts: List<String>, cardWidth: Dp): List<Int> {
    val density = LocalDensity.current
    val nameStyle = layoutNameStyle()
    val measurer = rememberTextMeasurer()
    return remember(texts, nameStyle, cardWidth, density) {
        val width = with(density) { (cardWidth - CardInset * 2 - PillInset * 2 - TickSize - TickGap).roundToPx() }
        texts.map { name ->
            measurer.measure(
                name,
                style = nameStyle,
                constraints = Constraints(maxWidth = width.coerceAtLeast(1)),
                // Measured without the cache: each name is measured once.
                skipCache = true,
            ).lineCount
        }
    }
}

/**
 * Whether the cards on this screen should hold off composing a keyboard:
 * while [scrolling] says the list moves, and while a shared-element flight is
 * in the air. A board composed mid-flight is a frame or more of the main
 * thread taken out of the one animation that cannot afford it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberPreviewsHeld(scrolling: () -> Boolean): () -> Boolean {
    val shared = LocalSharedTransition.current
    return remember(shared, scrolling) { { scrolling() || shared?.isTransitionActive == true } }
}

/**
 * Flies a layout card between the language's row and its gallery: the card
 * lifts out of one screen and lands in its place on the other, on a slight arc,
 * while the screens slide under it.
 *
 * The cards leave one after another from [order] (the card's place counted
 * from the first one in view) but all land on the frame the screen does, so
 * the set reads as a wave that settles together rather than a queue still
 * arriving after the page has stopped. Past the first few the stagger stops
 * growing, so a long gallery does not keep the last card waiting.
 *
 * Only the arriving copy fades in, over the departing one, which stays solid
 * underneath: two copies of an opaque card at part alpha let the page show
 * through the pair. A card with no partner on the other screen, one scrolled
 * out of the row, simply travels with its own screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.layoutCardFlight(key: String, order: () -> Int): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    // Asked when the flight starts, not when the card composed: the row may
    // have scrolled in between.
    val currentOrder by rememberUpdatedState(order)
    val bounds = remember {
        BoundsTransform { initial, target ->
            val delay = currentOrder().coerceIn(0, FLIGHT_STAGGER_STEPS) * FLIGHT_STAGGER_MS
            keyframes {
                delayMillis = delay
                durationMillis = NavTransitionMs - delay
                // The standard curve rather than the screens' decelerate one,
                // which spends most of its travel in the first few frames:
                // the card has to be seen leaving its place to read as moving.
                initial at 0 using ArcMode.ArcBelow using FastOutSlowInEasing
                target at durationMillis
            }
        }
    }
    return with(shared) {
        this@layoutCardFlight.sharedBounds(
            rememberSharedContentState(key),
            anim,
            enter = fadeIn(tween(FLIGHT_FADE_MS)),
            exit = ExitTransition.None,
            boundsTransform = bounds,
            resizeMode = scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            zIndexInOverlay = FLIGHT_Z,
        )
    }
}

private const val FLIGHT_STAGGER_MS = 18
private const val FLIGHT_STAGGER_STEPS = 6
private const val FLIGHT_FADE_MS = 120
private const val FLIGHT_Z = 2f

/** Share of the screen's width a card takes, before the caps below. */
private const val CARD_WIDTH_SHARE = 0.42f

private val CardMinWidth = 140.dp
private val CardMaxWidth = 230.dp

/** The row's inset, level with the group cards on the rest of the screen. */
private val CarouselEdge = 16.dp

private val CardGap = 8.dp

/** How much of the card before the first switched-on one shows on arrival. */
private val CardPeek = 24.dp

/**
 * What a Keyman layout's card shows until the pack of grids is on the phone: its
 * download, one button for every card on the page. Kept apart from the card's
 * toggle, so pressing it does not also switch the layout on.
 */
@Composable
private fun KeymanPackPlaceholder(modifier: Modifier) {
    val context = LocalContext.current
    val status by KeymanLayoutPackDownloader.status.collectAsState()
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = PackPlaceholderHeight)
            .background(colors.surfaceContainerHigh)
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        when (val now = status) {
            is KeymanLayoutPackDownloader.Status.Downloading -> Text(
                stringResource(R.string.languages_keyman_pack_downloading, now.percent),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            else -> FilledTonalButton(onClick = { KeymanLayoutPackDownloader.start(context) }) {
                Text(
                    stringResource(
                        if (now is KeymanLayoutPackDownloader.Status.Failed) {
                            R.string.languages_keyman_pack_retry
                        } else {
                            R.string.languages_keyman_pack_get
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val PackPlaceholderHeight = 96.dp

// Compact: the frame round a board is as thin as the ring and the tick still
// read at, so the keyboard is most of the card and a gallery of them is a page
// of keyboards rather than a page of frames.
private val CardShape = RoundedCornerShape(16.dp)
private val BoardShape = RoundedCornerShape(10.dp)
private val CardInset = 4.dp
private val PillGap = 3.dp
private val PillPad = 1.dp
private val TickSize = 16.dp
private val TickGap = 6.dp
private val PillInset = 3.dp

private val RingOff = 1.dp
private val RingOn = 2.dp

/** Width to height of a board, for the More card before any board has drawn. */
private const val FALLBACK_BOARD_ASPECT = 1.45f

private const val MORE_CARD_KEY = "\u0000more"

/** The More card's flight: one per screen pair, as there is one per language. */
internal const val MORE_CARD_FLIGHT = "layoutCard/\u0000more"

/** The flight a layout's card takes between the row and the gallery. */
internal fun layoutCardFlightKey(layoutId: String): String = "layoutCard/$layoutId"

/** How far a card sinks under the finger. */
private const val PRESSED_SCALE = 0.97f

private const val SELECT_MS = 200
private const val PRESS_STIFFNESS = 1400f
private const val GROW_STIFFNESS = 500f
