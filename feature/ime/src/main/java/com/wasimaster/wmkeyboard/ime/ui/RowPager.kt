package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.RowPage
import com.wasimaster.wmkeyboard.core.layout.RowPageDigits
import com.wasimaster.wmkeyboard.core.layout.RowPageFill
import com.wasimaster.wmkeyboard.core.layout.RowPageStarterEmoji
import com.wasimaster.wmkeyboard.core.layout.hasFlicks
import com.wasimaster.wmkeyboard.core.layout.nextRowPage
import com.wasimaster.wmkeyboard.core.layout.rowPageCount
import com.wasimaster.wmkeyboard.core.layout.withRowPages
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Which page each row of the grid on screen is showing, for the rows a layout
 * gave more pages ([com.wasimaster.wmkeyboard.core.layout.LayerSpec.rowPages]).
 *
 * Kept by the grid rather than by the service: a page is something drawn, not
 * something typed, and nothing outside the key rows has to know it. Filed by
 * the compiled grid's name, so a peek at the symbols and back finds the letters
 * as they were left, and a new layout starts every row on its own keys.
 */
internal class RowPager {
    private val pages = mutableStateMapOf<String, Map<Int, Int>>()

    /** The last flip, for the slide; its [RowSlide.token] changes with every flip. */
    var slide by mutableStateOf(RowSlide(row = -1, step = 0, token = 0))
        private set

    /** The page of each row of [grid] that is not on its own keys. */
    fun at(grid: String): Map<Int, Int> = pages[grid].orEmpty()

    /** Moves [row] of [grid] [step] pages along, out of [count] with its own. */
    fun flip(grid: String, row: Int, step: Int, count: Int) {
        val now = pages[grid].orEmpty()
        val next = nextRowPage(now[row] ?: 0, step, count)
        pages[grid] = if (next == 0) now - row else now + (row to next)
        slide = RowSlide(row, step, slide.token + 1)
    }
}

/** One flip: which row, which way (1 came in from the right), and its count. */
@Immutable
internal data class RowSlide(val row: Int, val step: Int, val token: Int)

/**
 * [base] with each row on the page [pager] has it on, filled from [state]. The
 * same instance while no row has moved off its own keys.
 */
@Composable
internal fun rememberPagedLayout(base: KeyboardLayout, pager: RowPager, state: KeyboardUiState): KeyboardLayout {
    val at = pager.at(base.name)
    if (at.isEmpty() || base.rowPages.isEmpty()) return base
    return remember(base, at, state.emojiFrequents, state.emojiRecents, state.settings.customSymbolSets) {
        base.withRowPages(at) { page -> rowPageTexts(page, state) }
    }
}

/** What a filled page shows, as the texts its keys type. */
internal fun rowPageTexts(page: RowPage, state: KeyboardUiState): List<String> = when (page.fill) {
    RowPageFill.EMOJI -> (state.emojiFrequents + state.emojiRecents + RowPageStarterEmoji).distinct()
    RowPageFill.SYMBOLS -> {
        val id = page.symbolSet ?: BuiltInSymbolSets.MATH_ID
        val set = resolveSymbolSets(state.settings.customSymbolSets).firstOrNull { it.id == id }
            ?: BuiltInSymbolSets.byId(BuiltInSymbolSets.MATH_ID)
        // A tab draws nothing on a key face.
        set?.chars.orEmpty().filter { it.isNotBlank() }
    }
    RowPageFill.DIGITS -> RowPageDigits
    null -> emptyList()
}

/**
 * Decides whether one finger is a swipe across a row that has more pages, one
 * pointer event at a time. Pure, so the rules are tested on the JVM.
 *
 * With glide typing off ([strict] false) any sideways swipe will do: it has
 * to go [claimPx] along the row, mostly sideways, within [windowMs] of
 * landing, so a hold that drifts is still a hold. That is less than a
 * one-finger board swipe needs, so on a row with pages the row wins.
 *
 * With glide typing on, a sideways swipe across the letters is a word, so
 * only a fling that no word looks like flips the row: it crosses [strictShare]
 * of the row's width within [windowMs], keeps inside the row's height
 * (give or take [bandSlack] of it) and never turns back. A word bends, slows
 * at its letters and leaves the row; the borderline cases stay words.
 *
 * Until the claim nothing is consumed, so the key or the glide under the
 * finger carries on as it always did.
 */
internal class RowFlingTracker(
    private val strict: Boolean,
    private val rowTop: Float,
    private val rowBottom: Float,
    private val rowWidth: Float,
    private val claimPx: Float,
    private val windowMs: Long,
) {
    private var id: Long? = null
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var downAt = 0L
    private var lifted = false

    /** The furthest the finger has gone along the row, either way, signed. */
    private var furthest = 0f

    var claimed = false
        private set

    var abandoned = false
        private set

    fun abandon() {
        if (!claimed) abandoned = true
    }

    /** One pointer's state in the latest event; true when this call claimed the swipe. */
    fun track(pointer: Long, x: Float, y: Float, pressed: Boolean, uptimeMs: Long): Boolean {
        val first = id
        if (first == null) {
            if (!pressed) return false
            id = pointer
            startX = x
            startY = y
            lastX = x
            downAt = uptimeMs
            return false
        }
        if (pointer != first) {
            if (pressed && !claimed) abandoned = true
            return false
        }
        if (!pressed) {
            lifted = true
            if (!claimed) abandoned = true
            return false
        }
        lastX = x
        if (claimed || abandoned) return false
        val dx = x - startX
        val dy = y - startY
        if (abs(dx) > abs(furthest)) furthest = dx
        val late = uptimeMs - downAt > windowMs
        return if (strict) trackStrict(dx, y, late) else trackLoose(dx, dy, late)
    }

    private fun trackLoose(dx: Float, dy: Float, late: Boolean): Boolean {
        if (abs(dx) < claimPx) {
            if (late || abs(dy) > claimPx) abandoned = true
            return false
        }
        if (late || abs(dy) > abs(dx) * LooseConeTan) {
            abandoned = true
            return false
        }
        claimed = true
        return true
    }

    private fun trackStrict(dx: Float, y: Float, late: Boolean): Boolean {
        val slack = (rowBottom - rowTop) * BandSlack
        val turnedBack = abs(furthest) - abs(dx) > rowWidth * TurnBackShare ||
            (furthest != 0f && dx != 0f && (furthest > 0f) != (dx > 0f))
        if (y < rowTop - slack || y > rowBottom + slack || turnedBack || late) {
            abandoned = true
            return false
        }
        if (abs(dx) < rowWidth * StrictShare) return false
        claimed = true
        return true
    }

    /**
     * The page step once the finger is up, or null unless it was claimed: 1
     * for a swipe to the left, which brings the next page in from the right.
     */
    fun result(): Int? {
        if (!claimed || !lifted) return null
        val dx = lastX - startX
        if (dx == 0f) return null
        return if (dx < 0f) 1 else -1
    }

    companion object {
        /** How much of the row's width a fling has to cross with glide typing on. */
        const val StrictShare = 0.6f

        /** How far outside its row's height a fling may wander, as a share of that height. */
        const val BandSlack = 0.35f

        /** How far back along the row a fling may come before it is no fling. */
        const val TurnBackShare = 0.08f

        /** The steepest a loose swipe may be: about 27°. */
        const val LooseConeTan = 0.5f
    }
}

/** How far a swipe goes along a row before it is a row swipe, with glide typing off. */
internal const val RowSwipeClaimDp = 40f

/** How soon a row swipe has to get there, with glide off. */
internal const val RowSwipeLooseWindowMs = 500L

/** How soon a fling has to cross the row, with glide on. */
internal const val RowSwipeStrictWindowMs = 320L

/** The slide a row's new page comes in with. */
private const val RowSlideMs = 200

/**
 * The grid's loop for row swipes: a finger landing on a row with more pages
 * that swipes across it ([RowFlingTracker]) moves that row a page along. Runs
 * on the Initial pass, ahead of the keys and the glide; from the claim on it
 * consumes the touch and sets [gate], so the key it left never types and the
 * glide drops its stroke. [rowAt] answers which pageable row, and where, holds
 * a point in this box's space.
 */
internal suspend fun PointerInputScope.detectRowSwipes(
    strict: () -> Boolean,
    rowAt: (Offset) -> RowHit?,
    gate: FingerSwipeGate,
    alternatesGate: AlternatesGate,
    onFlip: (row: Int, step: Int) -> Unit,
) {
    val claimPx = RowSwipeClaimDp.dp.toPx()
    // Whether this loop set the gate, so it is the one to put it back.
    var heldGate = false
    try {
        rowSwipeLoop(strict, rowAt, gate, alternatesGate, claimPx, onFlip) { held -> heldGate = held }
    } finally {
        // A loop restarted for a new grid must not leave the board's keys
        // thinking a swipe still has the touch.
        if (heldGate) gate.claimed = false
    }
}

private suspend fun PointerInputScope.rowSwipeLoop(
    strict: () -> Boolean,
    rowAt: (Offset) -> RowHit?,
    gate: FingerSwipeGate,
    alternatesGate: AlternatesGate,
    claimPx: Float,
    onFlip: (row: Int, step: Int) -> Unit,
    setHeld: (Boolean) -> Unit,
) {
    var heldGate = false
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (heldGate) {
            gate.claimed = false
            heldGate = false
            setHeld(false)
        }
        val hit = rowAt(first.position) ?: return@awaitEachGesture
        if (hit.startKey.hasFlicks()) return@awaitEachGesture
        val isStrict = strict()
        val tracker = RowFlingTracker(
            strict = isStrict,
            rowTop = hit.bounds.top,
            rowBottom = hit.bounds.bottom,
            rowWidth = hit.bounds.width,
            claimPx = claimPx,
            windowMs = if (isStrict) RowSwipeStrictWindowMs else RowSwipeLooseWindowMs,
        )
        tracker.track(first.id.value, first.position.x, first.position.y, pressed = true, uptimeMs = first.uptimeMillis)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (gate.claimed && !tracker.claimed) tracker.abandon()
            if (alternatesGate.open && !tracker.claimed) tracker.abandon()
            for (change in event.changes) {
                if (tracker.track(change.id.value, change.position.x, change.position.y, change.pressed, change.uptimeMillis)) {
                    gate.claimed = true
                    heldGate = true
                    setHeld(true)
                }
            }
            if (tracker.abandoned) return@awaitEachGesture
            if (tracker.claimed) event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
        val step = tracker.result() ?: return@awaitEachGesture
        onFlip(hit.row, step)
    }
}

/** A pageable row under a finger: its index, its extent in the grid box, and the key the finger landed on. */
internal class RowHit(val row: Int, val bounds: Rect, val startKey: Key)

/**
 * Which pageable row of [layout] holds [point] (grid box space), by the key
 * under it in [rects] (root space, so [origin] lifts the point there). The row's
 * extent is its keys' cells joined, so a short row centred in the grid swipes
 * across its own width.
 */
internal fun rowHitAt(
    point: Offset,
    origin: Offset,
    layout: KeyboardLayout,
    rowOfKey: Map<Key, Int>,
    rects: KeyRects,
): RowHit? {
    val key = rects.keyAt(point + origin) ?: return null
    val row = rowOfKey[key] ?: return null
    if (layout.rowPageCount(row) <= 1) return null
    var bounds: Rect? = null
    for (each in layout.rows[row]) {
        val cell = rects.rectOf(each) ?: continue
        bounds = bounds?.let { Rect(minOf(it.left, cell.left), minOf(it.top, cell.top), maxOf(it.right, cell.right), maxOf(it.bottom, cell.bottom)) }
            ?: cell
    }
    return bounds?.let { RowHit(row, it.translate(-origin), key) }
}

/** Each key of [layout] by the row it sits in, by identity: the instances the grid draws and files. */
internal fun rowsByKey(layout: KeyboardLayout): Map<Key, Int> {
    val out = IdentityHashMap<Key, Int>()
    layout.rows.forEachIndexed { r, row -> row.forEach { out.putIfAbsent(it, r) } }
    return out
}

/**
 * A row that just changed pages sliding its new keys in from the side the
 * swipe came from. Draws only; the keys' cells are where they will stay from
 * the first frame. Still with motion off.
 */
@Composable
internal fun RowPageSlideBox(slide: RowSlide, row: Int, animate: Boolean, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val mine = slide.row == row
    LaunchedEffect(if (mine) slide.token else 0) {
        if (!mine || slide.token == 0 || !animate) return@LaunchedEffect
        offset.snapTo(slide.step.toFloat())
        offset.animateTo(0f, tween(RowSlideMs, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val shift = offset.value
                if (shift != 0f) {
                    translationX = shift * size.width * RowSlideTravel
                    alpha = 1f - abs(shift) * (1f - RowSlideFloorAlpha)
                }
            },
    ) { content() }
}

/** How far across its width the incoming page starts, and how faint. */
private const val RowSlideTravel = 0.35f
private const val RowSlideFloorAlpha = 0.2f
