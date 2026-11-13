package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import kotlin.math.roundToInt

/**
 * The centre gap of a split keyboard as a hole in the board (#1256): the app
 * behind shows through it and takes the taps that land in it, the way it does
 * everywhere above the keyboard.
 *
 * Each split row's gap spacer reports its rectangle here, in window pixels
 * (the spacer's width, the row's height). The docked frame cuts the
 * rectangles out of everything it draws, and the service cuts them out of
 * the window's touchable region. Rows are cut at their own midpoints, so the
 * holes are one per row and need not line up; a row whose spacebar bridges
 * the gap (#399) has no spacer and stays solid.
 *
 * The bar rows over (or under) the keys — the suggestion strip, the toolbar,
 * the tools' own row and the rest of the stack — are split too, around the
 * nearest key row's hole: each is laid out that much narrower and drawn in
 * two halves either side of it (see [rememberSplitBarHalves]), so the hole runs up
 * through them in one column. An open panel has no key rows and so no hole,
 * and the bars over it keep the whole width.
 */
internal class SplitGapCutout(
    /**
     * How far apart a bar row and the key rows may be and still have their
     * holes joined: the board's own padding between them, which would
     * otherwise cross the column as a solid band.
     */
    private val bridgePx: Float = 0f,
) {
    /** Each spacer's hole in window pixels, keyed by the spacer. */
    val gaps = mutableStateMapOf<Any, Rect>()

    /** Each bar row's own bounds in window pixels, keyed by the row. */
    val bands = mutableStateMapOf<Any, Rect>()

    /** Where the board's top-left corner sits in the window, so a hole can be drawn in board space. */
    var boardOrigin by mutableStateOf(Offset.Zero)

    fun put(key: Any, rect: Rect) {
        if (gaps[key] != rect) gaps[key] = rect
    }

    fun putBand(key: Any, rect: Rect) {
        if (bands[key] != rect) bands[key] = rect
    }

    /** The key row hole a bar row lines up with: the one nearest it, up or down. */
    private fun columnFor(band: Rect): Rect? = gaps.values.minByOrNull { gap ->
        maxOf(0f, maxOf(band.top, gap.top) - minOf(band.bottom, gap.bottom))
    }

    /**
     * The hole across a bar row, in window pixels, or null while the row is
     * drawn whole: no key row has a hole, or the hole is not inside the row.
     * Stretched over the board's padding to the key rows when that is all
     * there is between them.
     */
    fun barHole(band: Rect): Rect? {
        if (band.width <= 0f || band.height <= 0f) return null
        val column = columnFor(band) ?: return null
        if (column.left <= band.left || column.right >= band.right) return null
        var top = band.top
        var bottom = band.bottom
        if (column.top >= bottom && column.top - bottom <= bridgePx) bottom = column.top
        if (top >= column.bottom && top - column.bottom <= bridgePx) top = column.bottom
        return Rect(column.left, top, column.right, bottom)
    }

    /** Every hole in the board: the key rows' and the bar rows'. */
    fun holes(): List<Rect> = gaps.values + bands.values.mapNotNull(::barHole)

    /**
     * The right halves of the split bar rows, where a touch has to be moved
     * back by the hole's width to reach what is drawn there (see
     * [SplitBarTouchFrame]).
     */
    fun shifts(): List<SplitBarShift> = bands.values.mapNotNull { band ->
        val hole = barHole(band) ?: return@mapNotNull null
        SplitBarShift(
            Rect(hole.right, band.top, band.right, band.bottom).roundToIntRect(),
            -(hole.right.roundToInt() - hole.left.roundToInt()),
        )
    }
}

/**
 * The right half of a split bar row, in window pixels, and how far a touch
 * there moves sideways to land on what the row drew there: the row is laid
 * out [dx] narrower and its right half drawn [dx] further right.
 */
data class SplitBarShift(val area: IntRect, val dx: Int)

/** The cutout the docked frame keeps while the setting is on; null draws every gap solid. */
internal val LocalSplitGapCutout = staticCompositionLocalOf<SplitGapCutout?> { null }

/**
 * Where the service hears about the holes, as window rectangles, so it can
 * leave them out of the touchable region, and about the split bar rows'
 * right halves, whose touches it moves to where the row laid them out. Empty
 * lists put the whole board back. A no-op outside the IME (the settings
 * previews).
 */
val LocalSplitGapReport = staticCompositionLocalOf<(List<IntRect>, List<SplitBarShift>) -> Unit> { { _, _ -> } }

/**
 * The gap between a split row's halves. With no cutout it is the plain spacer
 * it always was; with one it also reports its rectangle, and takes it back
 * when the row leaves.
 */
@Composable
internal fun RowScope.SplitGapSpacer(weight: Float) {
    val cutout = LocalSplitGapCutout.current
    // The spacer's own name in the cutout's map.
    val key = remember { Any() }
    // Remembered: the row recomposes on every keystroke, and a fresh
    // onGloballyPositioned lambda each time reads as a changed modifier.
    val modifier = remember(cutout, weight) {
        val base = Modifier.weight(weight)
        if (cutout == null) {
            base
        } else {
            base.onGloballyPositioned { spacer ->
                // The spacer has no height of its own; the row it sits in has.
                val row = spacer.parentLayoutCoordinates ?: return@onGloballyPositioned
                val x = spacer.positionInWindow().x
                val y = row.positionInWindow().y
                cutout.put(key, Rect(x, y, x + spacer.size.width, y + row.size.height))
            }
        }
    }
    if (cutout != null) {
        DisposableEffect(cutout) {
            onDispose { cutout.gaps.remove(key) }
        }
    }
    Spacer(modifier = modifier)
}

/**
 * Draws the board with the holes cut out of it, and records where the board
 * sits so the window rectangles can be moved into its space. Both reads are
 * in the draw phase, so a hole moving redraws the board and recomposes
 * nothing.
 */
internal fun Modifier.splitGapHoles(cutout: SplitGapCutout?): Modifier =
    if (cutout == null) {
        this
    } else {
        this
            .onGloballyPositioned { cutout.boardOrigin = it.positionInWindow() }
            .drawWithContent {
                val holes = cutout.holes()
                if (holes.isEmpty()) {
                    drawContent()
                } else {
                    val origin = cutout.boardOrigin
                    val path = Path()
                    for (hole in holes) path.addRect(hole.translate(-origin))
                    clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                }
            }
    }

/**
 * Passes the holes to the service as they change, sorted so the same holes
 * always read the same, and clears them when the cutout goes away (the
 * setting turned off, a resize started, the keyboard left).
 */
@Composable
internal fun SplitGapReporter(cutout: SplitGapCutout?) {
    val report = LocalSplitGapReport.current
    LaunchedEffect(cutout, report) {
        if (cutout == null) {
            report(emptyList(), emptyList())
            return@LaunchedEffect
        }
        snapshotFlow {
            val holes = cutout.holes()
                .map { IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
                .filter { it.width > 0 && it.height > 0 }
                .sortedWith(compareBy({ it.top }, { it.left }))
            val shifts = cutout.shifts()
                .filter { it.area.width > 0 && it.area.height > 0 && it.dx != 0 }
                .sortedWith(compareBy({ it.area.top }, { it.area.left }))
            holes to shifts
        }.collect { (holes, shifts) -> report(holes, shifts) }
    }
    DisposableEffect(report) {
        onDispose { report(emptyList(), emptyList()) }
    }
}

/**
 * Splits a bar row around the key rows' hole while the cutout has one under
 * or over it. The row is laid out the hole's width narrower, as if the keys'
 * gap were not there, and then drawn in two: the part left of the hole where
 * it was, the part right of it moved over by the hole's width. Nothing sits
 * in the hole, and a touch on the right half is moved back by the same width
 * before it reaches the row (see [SplitBarTouchFrame]).
 *
 * The cut itself falls wherever the hole does, so what is on the row has to
 * keep an edge there or be drawn half on each side. The row's halves are
 * provided as [LocalSplitBarHalves] for that: the suggestion strip's word
 * slots and the toolbar's spread-out buttons meet at the cut (see
 * [cellsAroundCut] and [insetToCut]).
 *
 * The row's bounds are read in the layout pass after it is placed, so the
 * first frame a row appears in is drawn whole, and the next one split.
 */
@Composable
internal fun rememberSplitBarHalves(cutout: SplitGapCutout?): SplitBarHalves? {
    if (cutout == null) return null
    val halves = remember(cutout) { SplitBarHalves(cutout) }
    DisposableEffect(halves) {
        onDispose { cutout.bands.remove(halves) }
    }
    return halves
}

/**
 * One bar row split around the hole: the [modifier] that lays it out and
 * draws it in two, and where it is cut, for what is on the row to keep clear
 * of (see [cutIn]).
 */
internal class SplitBarHalves(private val cutout: SplitGapCutout) {
    /**
     * Where the hole falls across the row, in the row's own pixels: its start
     * and its width. Read in the layout and draw phases, so a hole moving
     * re-lays out and redraws the row and recomposes nothing.
     */
    fun span(width: Int): Pair<Int, Int>? {
        val band = cutout.bands[this] ?: return null
        val hole = cutout.barHole(band) ?: return null
        val start = (hole.left - band.left).roundToInt()
        val gap = hole.right.roundToInt() - hole.left.roundToInt()
        return if (start <= 0 || gap <= 0 || start + gap >= width) null else start to gap
    }

    /**
     * Where the row is cut, in window pixels as the row lays out (its right
     * half is only drawn further over, so a node on it reads its own
     * position as if the hole were not there), or null while it is whole.
     */
    fun cutX(): Float? {
        val band = cutout.bands[this] ?: return null
        val (start, _) = span(band.width.roundToInt()) ?: return null
        return band.left + start
    }

    val modifier: Modifier = Modifier.splitBarHalves(cutout, this)
}

/**
 * The split bar row a node sits on, or null off one. Provided by the bar
 * stack (see [rememberSplitBarHalves]).
 */
internal val LocalSplitBarHalves = staticCompositionLocalOf<SplitBarHalves?> { null }

/**
 * Where the split bar row is cut across a node that reports its place
 * through [tracker], in the node's own pixels, or null when the cut is not
 * inside it (or nothing is split). Read in composition: what lays itself out
 * around the cut recomposes when it moves, which is only when the board does.
 */
internal class SplitCutTracker(private val halves: SplitBarHalves) {
    private var left by mutableStateOf(Float.NaN)
    private var width by mutableStateOf(0)

    val tracker: Modifier = Modifier.onGloballyPositioned {
        left = it.positionInWindow().x
        width = it.size.width
    }

    /** The cut from the node's start, strictly inside it, or null. */
    fun cutIn(): Float? {
        val x = halves.cutX() ?: return null
        if (left.isNaN()) return null
        val local = x - left
        return local.takeIf { it > 0f && it < width }
    }

    /** The node's width as last laid out. */
    val laidOutWidth: Int get() = width
}

/** A [SplitCutTracker] while the node is on a split bar row, else null. */
@Composable
internal fun rememberSplitCutTracker(): SplitCutTracker? {
    val halves = LocalSplitBarHalves.current ?: return null
    return remember(halves) { SplitCutTracker(halves) }
}

/**
 * Widths for [count] cells across [width] pixels, [divider] apart, that put
 * the edge between two cells on [cut]: the cells before it share what is left
 * of it, the rest what is right of it, so no cell is cut in two by the hole.
 * The second value is the index of the first cell after the cut. Null for a
 * single cell, which can only be cut.
 */
internal fun cellsAroundCut(count: Int, width: Float, cut: Float, divider: Float = 0f): Pair<List<Float>, Int>? {
    if (count < 2 || cut <= 0f || cut >= width) return null
    val before = (cut / width * count).roundToInt().coerceIn(1, count - 1)
    val after = count - before
    val left = (cut - divider * (before - 1)) / before
    val right = (width - cut - divider * after) / after
    if (left <= 0f || right <= 0f) return null
    return List(count) { if (it < before) left else right } to before
}

/**
 * The inset that moves a row of [count] equal cells, [width] pixels in all,
 * so that an edge between two of them lands on [cut]: first how much goes at
 * the left, then how much at the right; one of the two is always zero. The
 * cells stay equal, a little narrower, with the inset as empty bar at one
 * end. Null when [cut] is not inside the row or there is one cell.
 */
internal fun insetToCut(count: Int, width: Float, cut: Float): Pair<Float, Float>? {
    if (count < 2 || cut <= 0f || cut >= width) return null
    val cell = width / count
    val before = (cut / cell).roundToInt().coerceIn(1, count - 1)
    val miss = cut - before * cell
    return if (miss >= 0f) miss * count / (count - before) to 0f else 0f to -miss * count / before
}

private fun Modifier.splitBarHalves(cutout: SplitGapCutout, halves: SplitBarHalves): Modifier {
    val key = halves
    fun span(width: Int) = halves.span(width)
    return this
        .onGloballyPositioned { row ->
            val origin = row.positionInWindow()
            cutout.putBand(key, Rect(origin.x, origin.y, origin.x + row.size.width, origin.y + row.size.height))
        }
        .drawWithContent {
            val split = span(size.width.roundToInt())
            if (split == null) {
                drawContent()
            } else {
                val (start, gap) = split
                clipRect(left = -FarPx, top = -FarPx, right = start.toFloat(), bottom = FarPx) {
                    this@drawWithContent.drawContent()
                }
                translate(left = gap.toFloat()) {
                    clipRect(left = start.toFloat(), top = -FarPx, right = FarPx, bottom = FarPx) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
        }
        .layout { measurable, constraints ->
            val split = if (constraints.hasBoundedWidth) span(constraints.maxWidth) else null
            if (split == null) {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                val gap = split.second
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = (constraints.minWidth - gap).coerceAtLeast(0),
                        maxWidth = constraints.maxWidth - gap,
                    ),
                )
                layout(placeable.width + gap, placeable.height) { placeable.place(0, 0) }
            }
        }
}

/**
 * The most board padding a bar row's hole is stretched over to meet the key
 * rows' (see [SplitGapCutout.barHole]): more than the padding, less than a row.
 */
internal val SplitGapBridge = 16.dp

/** Far enough past the row's edges that what it draws over them is kept. */
private const val FarPx = 100_000f
