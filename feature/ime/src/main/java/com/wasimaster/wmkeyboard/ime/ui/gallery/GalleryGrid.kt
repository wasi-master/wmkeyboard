package com.wasimaster.wmkeyboard.ime.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.settings.GallerySettings
import com.wasimaster.wmkeyboard.core.settings.GalleryTapAction
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The photo grid: a camera tile, then the album newest first.
 *
 * One gesture handler for the whole grid rather than one per cell, because
 * the gestures span cells: a tap on a cell's corner picks it, a tap on the
 * rest opens it (or picks it, per the setting), a hold peeks at it full
 * size, and a hold that then slides becomes a drag across the grid that picks
 * everything it passes, Google Photos' way, scrolling as it nears an edge. A
 * two-finger pinch changes the column count.
 */
@Composable
internal fun GalleryGrid(
    ui: GalleryUi,
    settings: GallerySettings,
    act: (GalleryAction) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    // Search results are photos found, not the camera roll's head.
    val camera = settings.cameraTile && ui.search?.showing != true
    val lead = if (camera) 1 else 0
    val latest by rememberUpdatedState(ui)
    val tap by rememberUpdatedState(settings.tapAction)
    var heightPx by remember { mutableIntStateOf(1) }
    val cornerPx = with(density) { CORNER_HIT.toPx() }

    // Back to the top when the album or the filter changes.
    // And when a search's answer, or a new answer, takes the grid.
    val bestMatch = if (ui.search?.showing == true) ui.items.firstOrNull()?.id else null
    LaunchedEffect(ui.album, ui.filter, ui.search?.showing, bestMatch) { state.scrollToItem(0) }

    // Pages in as the end comes near.
    LaunchedEffect(state) {
        snapshotFlow {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - LOAD_AHEAD
        }.distinctUntilChanged().collect { near -> if (near) act(GalleryAction.LoadMore) }
    }

    /** The item under [pos], and whether the finger is on its corner check. */
    fun hit(pos: Offset): Pair<Int, Boolean>? {
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { i ->
            pos.x >= i.offset.x && pos.x < i.offset.x + i.size.width &&
                pos.y >= i.offset.y && pos.y < i.offset.y + i.size.height
        } ?: return null
        val corner = pos.x > info.offset.x + info.size.width - cornerPx && pos.y < info.offset.y + cornerPx
        return info.index to corner
    }

    Box(modifier.fillMaxSize().onSizeChanged { heightPx = it.height }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(ui.columns),
            state = state,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = bottomPadding + 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                // Two fingers: the column count, a step per clear pinch.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var scale = 1f
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.count { it.pressed } < 2) continue
                            scale *= e.calculateZoom()
                            e.changes.forEach { it.consume() }
                            val cols = latest.columns
                            if (scale > PINCH_STEP && cols > GallerySettings.MIN_COLUMNS) {
                                act(GalleryAction.SetColumns(cols - 1))
                                scale = 1f
                            } else if (scale < 1f / PINCH_STEP && cols < GallerySettings.MAX_COLUMNS) {
                                act(GalleryAction.SetColumns(cols + 1))
                                scale = 1f
                            }
                        } while (e.changes.any { it.pressed })
                    }
                }
                .pointerInput(lead) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = hit(down.position) ?: return@awaitEachGesture
                        val long = awaitLongPressOrCancellation(down.id)
                        if (long == null) {
                            // Lifted before the hold: a tap, unless the grid took it as a scroll.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture
                            val (index, corner) = start
                            if (index < lead) {
                                act(GalleryAction.OpenCamera)
                                return@awaitEachGesture
                            }
                            val item = latest.items.getOrNull(index - lead) ?: return@awaitEachGesture
                            if (corner || tap == GalleryTapAction.SELECT) {
                                act(GalleryAction.Toggle(item))
                            } else {
                                act(GalleryAction.OpenViewer(index - lead))
                            }
                            return@awaitEachGesture
                        }
                        val first = start.first - lead
                        val item = latest.items.getOrNull(first) ?: return@awaitEachGesture
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        act(GalleryAction.Peek(item))
                        // Held: peeking until the finger moves, then a sweep.
                        val select = latest.selection.none { it.id == item.id }
                        val before = latest.selectedIds
                        var sweeping = false
                        var lastRange = IntRange.EMPTY
                        var scroller: Job? = null
                        var pointer = long.position
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            pointer = c.position
                            if (!sweeping && (c.position - down.position).getDistance() > slop) {
                                sweeping = true
                                act(GalleryAction.Peek(null))
                            }
                            c.consume()
                            if (!sweeping) continue
                            if (scroller == null) {
                                // Near an edge the grid scrolls under the finger, and the
                                // sweep keeps reaching the rows that come into view.
                                scroller = scope.launch {
                                    while (isActive) {
                                        val edge = with(density) { EDGE.toPx() }
                                        val speed = when {
                                            pointer.y < edge -> -(edge - pointer.y)
                                            pointer.y > heightPx - edge -> pointer.y - (heightPx - edge)
                                            else -> 0f
                                        }
                                        if (speed != 0f) state.scrollBy(speed * EDGE_SPEED)
                                        delay(16)
                                    }
                                }
                            }
                            val now = hit(pointer)?.first?.minus(lead) ?: continue
                            if (now < 0) continue
                            val range = minOf(first, now)..maxOf(first, now)
                            if (range == lastRange) continue
                            val items = latest.items
                            val entering = range.filter { it !in lastRange }.mapNotNull { items.getOrNull(it) }
                            // What the sweep leaves goes back to how it was before it began.
                            val leaving = lastRange.filter { it !in range }.mapNotNull { items.getOrNull(it) }
                            if (entering.isNotEmpty()) {
                                act(GalleryAction.SelectRange(entering, select))
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            val restoreOn = leaving.filter { it.id in before }
                            val restoreOff = leaving.filter { it.id !in before }
                            if (select && restoreOff.isNotEmpty()) act(GalleryAction.SelectRange(restoreOff, false))
                            if (!select && restoreOn.isNotEmpty()) act(GalleryAction.SelectRange(restoreOn, true))
                            lastRange = range
                        }
                        scroller?.cancel()
                        if (!sweeping) {
                            act(GalleryAction.Peek(null))
                        } else if (lastRange.isEmpty()) {
                            // Slid off and back onto the cell it began on: just that one.
                            act(GalleryAction.SelectRange(listOf(item), select))
                        }
                    }
                },
        ) {
            if (camera) {
                item(key = "camera", contentType = "camera") { CameraTile() }
            }
            itemsIndexed(ui.items, key = { _, it -> it.id }, contentType = { _, _ -> "item" }) { _, item ->
                GridCell(
                    item = item,
                    order = ui.orderOf(item.id),
                    sent = item.id in ui.sentIds,
                    edited = ui.edits[item.id]?.current?.isEmpty == false,
                    columns = ui.columns,
                )
            }
        }
        if (settings.dateScrubber && ui.items.size > SCRUBBER_MIN) {
            FastScroller(state, ui, lead, Modifier.align(Alignment.TopEnd).padding(bottom = bottomPadding))
        }
        if (ui.items.isEmpty() && !ui.loading) {
            Text(
                stringResource(if (ui.search?.showing == true) R.string.ime_gallery_search_none else R.string.ime_gallery_empty_album),
                color = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun CameraTile() {
    val kb = LocalKbTheme.current
    val label = stringResource(R.string.ime_gallery_camera)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Brush.linearGradient(listOf(kb.accent.copy(alpha = 0.55f), kb.accent.copy(alpha = 0.2f))))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(28.dp))
            Text(label, color = OnPhoto, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GridCell(item: GalleryItem, order: Int, sent: Boolean, edited: Boolean, columns: Int) {
    val kb = LocalKbTheme.current
    val loader = rememberGalleryImageLoader()
    val picked = order > 0
    // One progress for the inset, the corners, the outline and the dim, so
    // they move together. Springy going in; going out it settles without
    // overshoot, since past 0 the inset turns negative.
    val sel = animateFloatAsState(
        targetValue = if (picked) 1f else 0f,
        animationSpec = when {
            kb.reduceMotion -> snap()
            picked -> spring(dampingRatio = 0.6f, stiffness = 600f)
            else -> spring(dampingRatio = 1f, stiffness = 900f)
        },
        label = "picked",
    )
    val density = LocalDensity.current
    val px = with(density) { (360.dp / columns.coerceAtLeast(1)).roundToPx() * 2 }
    val insetPx = with(density) { PICKED_INSET.toPx() }
    val cornerPx = with(density) { PICKED_CORNER.toPx() }
    val outlinePx = with(density) { 2.dp.toPx() }
    val accent = kb.accent
    val description = when (item.kind) {
        GalleryKind.VIDEO -> stringResource(R.string.ime_gallery_cell_video, durationText(item.durationMs))
        GalleryKind.GIF -> stringResource(R.string.ime_gallery_cell_gif)
        GalleryKind.IMAGE -> stringResource(R.string.ime_gallery_cell_photo)
    } + if (picked) ", " + stringResource(R.string.ime_gallery_cell_picked, order) else ""
    Box(
        Modifier
            .aspectRatio(1f)
            .background(kb.chip)
            .semantics { contentDescription = description },
    ) {
        AsyncImage(
            model = MediaThumb.of(item, px),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // Shrunk in the draw layer rather than padded: no layout pass a frame.
                .graphicsLayer {
                    val s = sel.value.coerceAtLeast(0f)
                    val k = 1f - 2f * insetPx * s / size.width.coerceAtLeast(1f)
                    scaleX = k
                    scaleY = k
                    shape = RoundedCornerShape(cornerPx * s / k.coerceAtLeast(0.5f))
                    clip = s > 0f
                }
                .drawWithContent {
                    drawContent()
                    val s = sel.value.coerceIn(0f, 1f)
                    if (s <= 0f) return@drawWithContent
                    val k = 1f - 2f * insetPx * s / size.width.coerceAtLeast(1f)
                    drawRect(Color.Black.copy(alpha = 0.12f * s))
                    // Divided by the layer's scale, so it lands 2 dp wide on screen.
                    val w = outlinePx / k.coerceAtLeast(0.5f)
                    drawRoundRect(
                        color = accent.copy(alpha = s),
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(cornerPx * s / k.coerceAtLeast(0.5f)),
                        style = Stroke(w),
                    )
                },
        )
        SelectRing(order, Modifier.align(Alignment.TopEnd).padding(5.dp), size = if (columns >= 5) 20.dp else 24.dp)
        if (item.kind == GalleryKind.VIDEO) {
            PhotoPill(durationText(item.durationMs), Modifier.align(Alignment.BottomStart).padding(5.dp), Icons.Outlined.PlayArrow)
        } else if (item.kind == GalleryKind.GIF) {
            PhotoPill(stringResource(R.string.ime_gallery_badge_gif), Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        if (item.favorite) CornerMark(Icons.Outlined.Star, Alignment.BottomEnd)
        if (edited) {
            CornerMark(Icons.Outlined.Edit, Alignment.TopStart)
        } else if (sent) {
            CornerMark(Icons.AutoMirrored.Outlined.Send, Alignment.TopStart)
        }
    }
}

/**
 * The handle down the grid's right edge: drag it to fly through years of
 * photos, with the month under it in a bubble. Fades in while the grid
 * scrolls and while held, and away a moment after.
 *
 * Only a narrow track takes touches, and only while the handle shows: it sits
 * over the last column, and a full-height catcher there swallowed every tap
 * on that column's corner check (#721).
 */
@Composable
private fun FastScroller(state: LazyGridState, ui: GalleryUi, lead: Int, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    var trackPx by remember { mutableFloatStateOf(1f) }
    var held by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, held) {
        if (state.isScrollInProgress || held) {
            visible = true
        } else {
            delay(SCRUBBER_LINGER_MS)
            visible = false
        }
    }
    val total = (ui.items.size + lead).coerceAtLeast(1)
    val first = state.firstVisibleItemIndex
    val fraction = (first.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (kb.reduceMotion) 0 else 180), label = "scrubber")
    val thumbDp = 40.dp
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxHeight()
            .width(SCRUBBER_WIDTH)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .graphicsLayer { this.alpha = alpha },
    ) {
        if (visible || held) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(SCRUBBER_TOUCH_WIDTH)
                    .pointerInput(total) {
                        detectVerticalDragGestures(
                            onDragStart = { held = true },
                            onDragEnd = { held = false },
                            onDragCancel = { held = false },
                        ) { change, _ ->
                            change.consume()
                            val f = (change.position.y / trackPx).coerceIn(0f, 1f)
                            scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
                        }
                    },
            )
        }
        val thumbPx = with(density) { thumbDp.toPx() }
        val y = ((trackPx - thumbPx) * fraction).toInt()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .padding(end = 2.dp)
                .size(width = 6.dp, height = thumbDp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (held) kb.accent else kb.secondaryText.copy(alpha = 0.8f)),
        )
        AnimatedVisibility(
            visible = held,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 14.dp),
        ) {
            val date = ui.items.getOrNull((first - lead).coerceAtLeast(0))?.dateMs ?: 0L
            Text(
                if (date > 0) monthText(date) else "",
                color = onAccent(kb.accent),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(kb.accent)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** How close to a cell's top-right corner a tap has to land to pick rather than open. */
private val CORNER_HIT = 40.dp

/** How far a picked cell's picture draws in from its edges, and how round it gets. */
private val PICKED_INSET = 6.dp
private val PICKED_CORNER = 10.dp

/** How near the end the next page starts loading, in cells. */
private const val LOAD_AHEAD = 30

/** A pinch has to grow or shrink by this much to change the column count once. */
private const val PINCH_STEP = 1.28f

/** The band at either edge where a sweep scrolls, and how fast. */
private val EDGE = 56.dp
private const val EDGE_SPEED = 0.35f

private const val SCRUBBER_MIN = 60

/** The scroller's drawing width (room for the month bubble) and the strip of it that takes a drag. */
private val SCRUBBER_WIDTH = 160.dp
private val SCRUBBER_TOUCH_WIDTH = 24.dp
private const val SCRUBBER_LINGER_MS = 1100L
