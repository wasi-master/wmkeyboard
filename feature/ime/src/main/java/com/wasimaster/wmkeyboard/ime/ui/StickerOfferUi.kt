package com.wasimaster.wmkeyboard.ime.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Interests
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.StickerOffer
import com.wasimaster.wmkeyboard.ime.StickerOfferItem
import com.wasimaster.wmkeyboard.ime.dynstickers.DynLivePlayer
import com.wasimaster.wmkeyboard.ime.dynstickers.DynThumbs
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the stickers offered while typing reach (#329). Rides [ToolHoldCallbacks]
 * for the reason its other passengers do: the caller cannot afford another
 * parameter.
 */
data class StickerOfferCallbacks(
    /** A sticker was tapped: send it, taking its trigger back out if the setting says so. */
    val onPick: (StickerOfferItem) -> Unit = {},
    /** The chip or the "more" button: open the tray with every match. */
    val onExpand: () -> Unit = {},
    /** The tray's ✕, or a hold on the strip's thumbnails: not these, not for this text. */
    val onDismiss: () -> Unit = {},
    /** An offer of emoji mixes: the button at the tray's end that opens the Mixes panel on its emoji. */
    val onOpenMixes: (StickerOffer) -> Unit = {},
)

/** Height of the tray row: a sticker at a size you can tell apart, and a little air. */
private val StickerTrayHeight = 64.dp
private val StickerTrayCell = 56.dp

/** Thumbnails the strip style shows before the rest wait behind "+N". */
private const val StripThumbs = 3
private val StripThumb = 34.dp

private const val TrayMotionMs = 140

/** The sticker a hold on the tray shows large (#404), and how far above the tray it floats. */
private val MagnifiedSticker = 168.dp
private val MagnifiedGap = 8.dp

/**
 * The large sticker's window never takes a touch or the focus, and it exists
 * only while a finger holds the tray, so it can never sit idle over the app
 * and swallow a tap there (the rule [CaretMagnifierHost] follows too).
 */
private val MagnifiedPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
)

/** A sticker shown large, and the middle of its thumbnail in the tray, in the tray's pixels. */
private data class Magnified(val pick: StickerOfferItem, val centerX: Int)

/**
 * Whether the tray is up: for an emoji picked in the emoji panel, while that
 * panel is open; for typed text, on the keys, always in the tray style and in
 * the other two once their chip has opened it.
 */
internal fun stickerTrayShows(state: KeyboardUiState): Boolean {
    val offer = state.stickerOffer ?: return false
    return when {
        offer.inEmojiPanel -> state.panel == PanelMode.EMOJI
        state.panel != PanelMode.NONE -> false
        offerStyle(state, offer) == StickerSuggestStyle.TRAY -> true
        else -> offer.expanded
    }
}

/** How [offer] is drawn on the strip: the sticker setting's style, or the mixes' or dynamic stickers' own. */
internal fun offerStyle(state: KeyboardUiState, offer: StickerOffer): StickerSuggestStyle = when {
    offer.dynamic -> state.settings.gif.dynamic.style
    offer.mix -> state.settings.emoji.mix.suggestStyle
    else -> state.settings.gif.stickerSuggestStyle
}

/** Whether the suggestion strip draws the offer: the two narrow styles, on the keys. */
internal fun stickerStripShows(state: KeyboardUiState): Boolean {
    val offer = state.stickerOffer ?: return false
    return !offer.inEmojiPanel && state.panel == PanelMode.NONE &&
        offerStyle(state, offer) != StickerSuggestStyle.TRAY
}

/**
 * The tray, as its own row of the bar stack ([com.wasimaster.wmkeyboard.core.settings.BarRow.STICKERS]):
 * on top by default, wherever Rows & bars puts it otherwise. It grows in
 * and out the way the selection macro row does, inside a docked frame that
 * holds still (see [RevealingBarRow]), so a match arriving resizes the window
 * once rather than on every frame of the move.
 *
 * The offer it last drew is kept for the way out: the service drops the offer
 * in the same update that hides the row, and a row that empties as it starts
 * to shrink would collapse in one step instead of sliding away.
 */
@Composable
internal fun ColumnScope.StickerTrayRow(state: KeyboardUiState, callbacks: StickerOfferCallbacks) {
    val visible = stickerTrayShows(state)
    val last = remember { arrayOfNulls<StickerOffer>(1) }
    if (visible) last[0] = state.stickerOffer
    val motion = LocalKbTheme.current.motion.any
    RevealingBarRow(
        visible = visible,
        enter = if (motion) {
            expandVertically(tween(TrayMotionMs)) + fadeIn(tween(TrayMotionMs))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            shrinkVertically(tween(TrayMotionMs)) + fadeOut(tween(TrayMotionMs))
        } else {
            ExitTransition.None
        },
    ) {
        last[0]?.let { StickerOfferTray(it, callbacks, magnify = state.settings.gif.stickerSuggestMagnify) }
    }
}

@Composable
private fun StickerOfferTray(offer: StickerOffer, callbacks: StickerOfferCallbacks, magnify: Boolean) {
    val kb = LocalKbTheme.current
    val loader = rememberMediaImageLoader()
    val listState = rememberLazyListState()
    var magnified by remember { mutableStateOf<Magnified?>(null) }
    val feedback = LocalKeyPressFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Read by the gesture at the moment it needs them: the offer changes
    // under a hold as the text does, and the gesture is not restarted for it.
    val stickersNow by rememberUpdatedState(offer.stickers)
    val peek = rememberStickerPeek(offer.trigger)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StickerTrayHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (magnify) {
                        Modifier.magnifyOnHold(
                            listState = listState,
                            rtl = rtl,
                            stickers = { stickersNow },
                            onHold = feedback,
                            onShow = { magnified = it },
                        )
                    } else {
                        Modifier
                    },
                ),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(offer.stickers, key = { _, pick -> pick.item.id }) { index, pick ->
                StickerThumb(
                    pick = pick,
                    loader = loader,
                    size = StickerTrayCell,
                    peek = { peek.of(index) },
                    onClick = { callbacks.onPick(pick) },
                )
            }
        }
        if (offer.mix) {
            // Every mix of this emoji, and a free choice of the other one.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable { callbacks.onOpenMixes(offer) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Interests,
                    contentDescription = stringResource(R.string.ime_emoji_mix_open_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = callbacks.onDismiss)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_sticker_offer_dismiss_desc, offer.trigger),
                tint = kb.toolbarIcon,
                modifier = Modifier.size(18.dp),
            )
        }
        magnified?.let { MagnifiedStickerPopup(it, loader) }
    }
}

/**
 * Hold a sticker to see it large, then slide along the tray to see the others
 * the same way; lifting the finger closes it and sends nothing (#404). The
 * hold has to come before any slide: a finger that moves first scrolls the
 * tray, and the tray's own scroll cancels the hold.
 *
 * Once the hold has fired, every event is consumed on the way down
 * ([PointerEventPass.Initial]), before the tray's scroll and the thumbnails'
 * taps see it: the slide must not scroll the row out from under the finger,
 * and the lift must not send the sticker it ends on.
 */
private fun Modifier.magnifyOnHold(
    listState: LazyListState,
    rtl: Boolean,
    stickers: () -> List<StickerOfferItem>,
    onHold: () -> Unit,
    onShow: (Magnified?) -> Unit,
): Modifier = pointerInput(listState, rtl) {
    /** The sticker under [x], or null in the gap between two. */
    fun hit(x: Float): Magnified? {
        val info = listState.layoutInfo
        val fromStart = if (rtl) size.width - x else x
        // Item offsets start after the content padding; the viewport's
        // start is that padding, negated.
        val along = fromStart + info.viewportStartOffset
        val item = info.visibleItemsInfo.firstOrNull { along >= it.offset && along < it.offset + it.size }
            ?: return null
        val pick = stickers().getOrNull(item.index) ?: return null
        val middle = item.offset - info.viewportStartOffset + item.size / 2
        return Magnified(pick, if (rtl) size.width - middle else middle)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        var shown = hit(held.position.x)
        onHold()
        onShow(shown)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == held.id } ?: break
                event.changes.forEach { it.consume() }
                if (!change.pressed) break
                val next = hit(change.position.x)
                if (next != null && next.pick.item.id != shown?.pick?.item?.id) {
                    shown = next
                    onShow(next)
                }
            }
        } finally {
            onShow(null)
        }
    }
}

/**
 * The held sticker, large, centred over its thumbnail and just above the
 * tray, with its title under it when it has one. A window of its own so it
 * can reach above the keyboard over the app, where there is room for it.
 */
@Composable
private fun MagnifiedStickerPopup(magnified: Magnified, loader: ImageLoader) {
    val kb = LocalKbTheme.current
    val gapPx = with(LocalDensity.current) { MagnifiedGap.roundToPx() }
    val provider = remember(magnified.centerX, gapPx) { MagnifiedPosition(magnified.centerX, gapPx) }
    val shape = RoundedCornerShape(16.dp)
    val title = magnified.pick.item.title
    Popup(popupPositionProvider = provider, properties = MagnifiedPopupProperties) {
        Column(
            modifier = Modifier
                .shadow(6.dp, shape, clip = false)
                .background(kb.popup, shape)
                .clip(shape)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = magnified.pick.item.previewUrl,
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.size(MagnifiedSticker),
                    contentScale = ContentScale.Fit,
                )
                if (DynThumbs.live(magnified.pick.item.previewUrl)) DynLivePlayer(magnified.pick.item, Modifier.size(MagnifiedSticker))
            }
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    color = kb.popupText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = MagnifiedSticker)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/** Centred on the held thumbnail, kept on screen sideways, above the tray. */
private class MagnifiedPosition(private val centerX: Int, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left + centerX - popupContentSize.width / 2).coerceIn(0, maxX)
        return IntOffset(x, anchorBounds.top - popupContentSize.height - gapPx)
    }
}

/**
 * The offer on the suggestion strip, for the two styles that put it there. It
 * shares the row with the word candidates rather than taking it: the word
 * that asked for the stickers may just as well be the start of a sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StickerStripOffer(
    offer: StickerOffer,
    style: StickerSuggestStyle,
    callbacks: StickerOfferCallbacks,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val loader = rememberMediaImageLoader()
    val dismissLabel = stringResource(R.string.ime_sticker_offer_dismiss_desc, offer.trigger)
    if (style == StickerSuggestStyle.STRIP) {
        val peek = rememberStickerPeek(offer.trigger)
        Row(
            modifier = modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            offer.stickers.take(StripThumbs).forEachIndexed { index, pick ->
                StickerThumb(
                    pick = pick,
                    loader = loader,
                    size = StripThumb,
                    peek = { peek.of(index) },
                    onClick = { callbacks.onPick(pick) },
                    onLongClick = callbacks.onDismiss,
                    longClickLabel = dismissLabel,
                )
            }
            val rest = offer.stickers.size - StripThumbs
            if (rest > 0 && !offer.expanded) {
                OfferPill(onClick = callbacks.onExpand, onLongClick = callbacks.onDismiss, dismissLabel = dismissLabel) {
                    Text(
                        text = stringResource(R.string.ime_sticker_offer_more, rest),
                        color = kb.accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        return
    }
    OfferPill(
        onClick = callbacks.onExpand,
        onLongClick = callbacks.onDismiss,
        dismissLabel = dismissLabel,
        modifier = modifier,
    ) {
        offer.stickers.firstOrNull()?.let { first ->
            AsyncImage(
                model = first.item.previewUrl,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.size(24.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = pluralStringResource(R.plurals.ime_sticker_offer_count, offer.stickers.size, offer.stickers.size),
            color = kb.accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** A strip chip in the house style ([StripIconChip]'s tint and border), holding whatever it is given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfferPill(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp, horizontal = 4.dp)
            .clip(shape)
            .background(kb.accent.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, kb.accent.copy(alpha = 0.32f), shape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = dismissLabel,
                onLongClick = {
                    feedback()
                    onLongClick()
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * The stickers peeking up as an offer arrives: each rises from just below its
 * place with a little wobble, the next a stagger after the one before. One
 * clock for the row, read by each sticker in its own layer. Keyed on the
 * trigger, so the same offer republished as the text moves does not peek
 * again, and stickers scrolled into view later find the clock run out.
 */
@Stable
private class StickerPeek(private val clock: Animatable<Float, AnimationVector1D>) {
    /** How far sticker [index] is through its peek, 0 to 1. */
    fun of(index: Int): Float {
        val ms = clock.value * PeekClockMs
        return ((ms - minOf(index, PeekStaggerCap) * PeekStaggerMs) / PeekMs).coerceIn(0f, 1f)
    }
}

@Composable
private fun rememberStickerPeek(trigger: String): StickerPeek {
    val motion = LocalKbTheme.current.motion.any
    val clock = remember(trigger) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(clock) { clock.animateTo(1f, tween(PeekClockMs.toInt(), easing = LinearEasing)) }
    return remember(clock) { StickerPeek(clock) }
}

/** A peek: the rise and its wobble, then the stagger between stickers. */
private const val PeekMs = 220f
private const val PeekStaggerMs = 25f
private const val PeekStaggerCap = 3
private const val PeekClockMs = PeekMs + PeekStaggerMs * PeekStaggerCap
private val PeekRise = 10.dp
private const val PeekWobbleDegrees = 7f

/** A sent sticker swelling and fading as it goes; the send itself never waits for it. */
private const val SentMs = 160
private const val SentGrow = 0.18f

/** An ease that runs a little past its end and settles back: the peek's bounce. */
private fun backOut(t: Float): Float {
    val u = t - 1f
    return 1f + 2.70158f * u * u * u + 1.70158f * u * u
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerThumb(
    pick: StickerOfferItem,
    loader: ImageLoader,
    size: Dp,
    peek: () -> Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val title = pick.item.title
    val motion = LocalKbTheme.current.motion.any
    val sent = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rise = with(LocalDensity.current) { PeekRise.toPx() }
    Box(
        modifier = Modifier
            .graphicsLayer {
                val p = peek()
                val gone = sent.value
                val grow = 1f + SentGrow * gone
                translationY = (1f - backOut(p)) * rise
                rotationZ = PeekWobbleDegrees * (1f - p) * sin(p * PI.toFloat() * 3f)
                alpha = (p * 3f).coerceAtMost(1f) * (1f - gone)
                scaleX = grow
                scaleY = grow
            }
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    onClick()
                    if (motion) {
                        scope.launch {
                            sent.animateTo(1f, tween(SentMs, easing = FastOutSlowInEasing))
                            // Still here (a tray that stays up): back in its place.
                            sent.snapTo(0f)
                        }
                    }
                },
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = pick.item.previewUrl,
            contentDescription = if (title.isBlank()) {
                stringResource(R.string.ime_sticker_offer_item_untitled_desc)
            } else {
                stringResource(R.string.ime_sticker_offer_item_desc, title)
            },
            imageLoader = loader,
            modifier = Modifier
                .size(size)
                .padding(2.dp),
            contentScale = ContentScale.Fit,
        )
        // "Moving in the row": a text sticker plays over its still.
        if (DynThumbs.live(pick.item.previewUrl)) DynLivePlayer(pick.item, Modifier.size(size).padding(2.dp))
    }
}
