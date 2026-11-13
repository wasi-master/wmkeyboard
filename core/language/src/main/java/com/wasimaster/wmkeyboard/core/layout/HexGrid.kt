package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A honeycomb grid: rows of hexagonal cells, every other row shifted half a
 * cell so the cells interlock edge to edge ([LayoutSpec.grid] `"hex"`).
 *
 * ## The model
 *
 * A row is still a row of keys with widths in cells, laid out and centred the
 * way every other grid is. What makes it a honeycomb is where its cell edges
 * fall: every key boundary of a row sits either on whole cells or on half
 * cells, which is the row's *phase* ([hexRowPhase]). Two neighbouring rows of
 * different phase interlock, and the line between them is a zigzag rather than
 * a straight edge: it peaks up into the upper row over each cell centre of the
 * lower row, and down into the lower row over each cell centre of the upper.
 * Rows of the same phase meet on a straight edge, so a hand-written grid that
 * only staggers some rows comes out as written instead of being forced.
 *
 * A key wider than one cell is the union of its cells (the spacebar has a
 * zigzag of five peaks along its top); a key that ends half way through a cell
 * is that cell cut down the middle. A half-cell key at the edge of a shifted
 * row is the half hexagon that squares the board's sides off.
 *
 * ## Hit testing
 *
 * A touch belongs to the cell whose centre is nearest, measured in the
 * hexagon's own axial space, where the cells are regular: [slotAt]. That space
 * is the screen with the vertical axis scaled, which turns "nearest centre"
 * into exactly the zigzag above, with the depth [hexAmplitude] gives it, so
 * the cell a finger lands in is the cell drawn under it. There are no dead
 * corners: the zigzags of two rows are the same line.
 *
 * Everything here is pure arithmetic in grid units and pixels, so the keyboard
 * and its tests measure one geometry.
 */

/** [LayoutSpec.grid] / [LayerSpec.grid] for a honeycomb. */
const val HexGridKind = "hex"

/** [LayerSpec.grid] for a layer of a honeycomb layout that wants plain rows. */
const val RectGridKind = "rect"

/**
 * How deep the zigzag between two interlocking rows is, per cell width: the
 * rise from a hexagon's side corner to its point, for a regular hexagon one
 * cell wide (`1 / (2√3)`). The cells then have the 30° slopes of a real
 * honeycomb whatever the key height, and only grow or shrink in height.
 */
const val HexRise = 0.28867513f

/** Floats here are sums of key widths; anything below this is noise. */
private const val Epsilon = 0.001f

/** Whether a stored grid kind asks for a honeycomb. */
fun isHexGrid(kind: String?): Boolean = kind == HexGridKind

/**
 * The zigzag depth in pixels for cells [cellPx] wide: [HexRise] of a cell,
 * capped at [maxPx] so that a wide, short key in landscape keeps its straight
 * sides. Zero for a degenerate cell.
 */
fun hexAmplitude(cellPx: Float, maxPx: Float): Float {
    if (!cellPx.isFinite() || cellPx <= 0f) return 0f
    val rise = cellPx * HexRise
    return if (maxPx.isFinite() && maxPx >= 0f) minOf(rise, maxPx) else rise
}

/** One key of a honeycomb, placed. Grid units throughout. */
data class HexSlot(
    /** Row of the board, top first. */
    val row: Int,
    /** Index within that row as written, so a caller can map back to the key it edits. */
    val col: Int,
    val key: Key,
    /** Left edge of the key's face, split gap included. */
    val x: Float,
    /** Width of the face, in cells. */
    val width: Float,
    /**
     * Left edge and width of the area that takes the key's touches: the face,
     * grown into a short row's side gap at the board's edge (#530) when edge
     * keys are extended.
     */
    val touchX: Float,
    val touchWidth: Float,
    /**
     * How far the split gap moved this key right: 0 on the left half and on an
     * unsplit board. The key's place in its row's honeycomb is `x - shift`.
     */
    val shift: Float,
) {
    val end: Float get() = x + width
    val touchEnd: Float get() = touchX + touchWidth
}

/** A whole honeycomb, laid out: see [hexBoard]. */
class HexBoard(
    /** Every key, row by row, left to right. */
    val slots: List<HexSlot>,
    /** Rows on the board. */
    val rowCount: Int,
    /** Grid units across the board, the split gap included. */
    val weight: Float,
    /** Each row's [hexRowPhase]: its cell edges sit on `phase + k`. */
    val phases: FloatArray,
    /** Index of each row's first slot, with [slots]' size as the last entry. */
    private val rowStarts: IntArray,
) {
    /** Whether rows [row] and `row + 1` interlock, rather than meeting on a straight edge. */
    fun joinedBelow(row: Int): Boolean =
        row in 0 until rowCount - 1 && abs(abs(phases[row] - phases[row + 1]) - 0.5f) < Epsilon

    /** Whether rows `row - 1` and [row] interlock. */
    fun joinedAbove(row: Int): Boolean = joinedBelow(row - 1)

    /** The slots of [row], in order. */
    fun slotsIn(row: Int): List<HexSlot> =
        if (row in 0 until rowCount) slots.subList(rowStarts[row], rowStarts[row + 1]) else emptyList()

    /**
     * Where [slot]'s left edge sits in its row's honeycomb, as a fraction of a
     * cell past the nearest cell edge on its left: 0 on a cell edge, 0.5 at a
     * cell's centre. What a face's outline starts its zigzag from.
     */
    fun startPhase(slot: HexSlot): Float = latticeFraction(slot.x - slot.shift - phases[slot.row])

    /**
     * How far [x] (grid units) is from the nearest cell centre of [row]'s
     * honeycomb, in cells: 0 on a centre, 0.5 on a cell edge. Read through the
     * key under [x], so a split board's right half is measured in its own
     * honeycomb.
     */
    fun centreDistance(row: Int, x: Float): Float {
        val slot = slotIndexInRow(row, x).takeIf { it >= 0 }?.let { slots[it] }
        val shift = slot?.shift ?: 0f
        return abs(latticeFraction(x - shift - phases[row]) - 0.5f)
    }

    /**
     * The slot a touch at ([x], [y]) belongs to, or -1 on an empty board.
     *
     * [x] and [y] are pixels from the board's top-left corner; [rowTops] holds
     * each row's top in pixels and the board's bottom as its last entry;
     * [cellPx] is the width of one grid unit in pixels; [amplitude] is the
     * zigzag depth ([hexAmplitude]). An amplitude of 0 answers for the plain
     * rectangles the keys' own touch areas are, which is how a caller tells
     * whether the honeycomb moved a touch to another key.
     *
     * A touch above the first row or below the last belongs to that row, and
     * one off the end of a row to the nearest key in it, so the board has no
     * dead edge either.
     */
    fun slotAt(x: Float, y: Float, rowTops: FloatArray, cellPx: Float, amplitude: Float): Int {
        if (rowCount == 0 || slots.isEmpty() || cellPx <= 0f || rowTops.size < rowCount + 1) return -1
        val gx = x / cellPx
        var row = rowCount - 1
        for (r in 0 until rowCount) {
            if (y < rowTops[r + 1]) {
                row = r
                break
            }
        }
        if (amplitude > 0f) {
            val toCentre = centreDistance(row, gx)
            // The zigzag over this row's top: up at its own cell centres.
            val top = rowTops[row] - amplitude / 2f + 2f * amplitude * toCentre
            val bottom = rowTops[row + 1] + amplitude / 2f - 2f * amplitude * toCentre
            if (joinedAbove(row) && y < top) {
                row--
            } else if (joinedBelow(row) && y >= bottom) {
                row++
            }
        }
        return slotIndexInRow(row, gx)
    }

    /** The slot of [row] whose touch area holds [x], else the nearest one; -1 for an empty row. */
    private fun slotIndexInRow(row: Int, x: Float): Int {
        if (row !in 0 until rowCount) return -1
        val from = rowStarts[row]
        val to = rowStarts[row + 1]
        var best = -1
        var bestGap = Float.MAX_VALUE
        for (i in from until to) {
            val slot = slots[i]
            if (x >= slot.touchX && x < slot.touchEnd) return i
            val gap = if (x < slot.touchX) slot.touchX - x else x - slot.touchEnd
            if (gap < bestGap) {
                bestGap = gap
                best = i
            }
        }
        return best
    }
}

/** [value]'s position past the whole number below it, in 0 until 1. */
private fun latticeFraction(value: Float): Float {
    val f = value - floor(value)
    return if (f >= 1f - Epsilon) 0f else f
}

/**
 * Where [row]'s cell edges sit, laid out from [start]: 0 when its keys meet on
 * whole cells, 0.5 when they meet on half cells.
 *
 * Read from the boundaries *between* keys, by majority, because those are what
 * the honeycomb is made of: the outer edges of a row may be a half-cell Shift
 * or a gap. A row with no inner boundary to read (one key, or none), or a tie,
 * takes the phase that interlocks with [previous], so a lone spacebar row
 * still zigzags with the row above it. The first such row takes 0.
 */
fun hexRowPhase(row: List<Key>, start: Float, previous: Float?): Float {
    var whole = 0
    var half = 0
    var x = start
    for (i in 0 until row.size - 1) {
        x += row[i].width
        // Twice the position, rounded: even is a whole cell, odd a half.
        if ((x * 2f).roundToInt() % 2 == 0) whole++ else half++
    }
    return when {
        whole > half -> 0f
        half > whole -> 0.5f
        previous == null -> 0f
        else -> if (previous < 0.25f) 0.5f else 0f
    }
}

/**
 * [rows] laid out as a honeycomb [gridWeight] cells wide.
 *
 * Each row is centred the way an ordinary row is ([sidePadFor]) and given its
 * [hexRowPhase]. [Key.rowSpan] is not honoured: a honeycomb's rows interlock,
 * and a key reaching down through the next row has nowhere to go.
 *
 * With [splitGap] above zero the board is cut for split mode: each row at the
 * key edge nearest the board's middle (ties to the right, as [Key]s are cut on
 * an ordinary split board), and everything right of the cut moved over by the
 * gap. Rows of different phase are cut at different places, so the two halves
 * come out as two honeycombs with a stepped channel between them. A spacebar
 * across the middle is cut in two, the left half losing its label, or, with
 * [bridgeSpace], kept whole and widened over the gap (#399).
 *
 * [extendEdges] lets the first and last key of a short row take its side gap
 * as touch area (#530); their faces stay where the centring put them.
 */
fun hexBoard(
    rows: List<List<Key>>,
    gridWeight: Float,
    splitGap: Float = 0f,
    bridgeSpace: Boolean = false,
    extendEdges: Boolean = true,
): HexBoard {
    val gap = if (splitGap.isFinite() && splitGap > Epsilon) splitGap else 0f
    val mid = gridWeight / 2f
    val phases = FloatArray(rows.size)
    val slots = ArrayList<HexSlot>()
    val rowStarts = IntArray(rows.size + 1)
    var widest = gridWeight + gap
    var previous: Float? = null
    for ((r, row) in rows.withIndex()) {
        rowStarts[r] = slots.size
        val rowWidth = row.sumOf { it.width.toDouble() }.toFloat()
        val start = ((gridWeight - rowWidth) / 2f).coerceAtLeast(0f)
        val phase = hexRowPhase(row, start, previous)
        phases[r] = phase
        previous = phase
        val placed = placeRow(r, row, start, gap, mid, bridgeSpace)
        if (placed.isEmpty()) continue
        val rowEnd = placed.last().end
        if (rowEnd > widest) widest = rowEnd
        val boardEnd = gridWeight + gap
        placed.forEachIndexed { i, slot ->
            var touchX = slot.x
            var touchEnd = slot.end
            if (extendEdges && i == 0 && touchX > Epsilon) touchX = 0f
            if (extendEdges && i == placed.lastIndex && touchEnd < boardEnd - Epsilon) touchEnd = boardEnd
            slots += slot.copy(touchX = touchX, touchWidth = touchEnd - touchX)
        }
    }
    rowStarts[rows.size] = slots.size
    return HexBoard(slots, rows.size, widest, phases, rowStarts)
}

/** One row's keys placed from [start], cut and moved for a split board when [gap] is set. */
private fun placeRow(
    row: Int,
    keys: List<Key>,
    start: Float,
    gap: Float,
    mid: Float,
    bridgeSpace: Boolean,
): List<HexSlot> {
    val lefts = FloatArray(keys.size)
    var x = start
    for (i in keys.indices) {
        lefts[i] = x
        x += keys[i].width
    }
    fun slot(col: Int, key: Key, left: Float, width: Float, shift: Float) =
        HexSlot(row, col, key, left + shift, width, left + shift, width, shift)
    if (gap <= 0f || keys.size < 2) {
        return keys.mapIndexed { i, key -> slot(i, key, lefts[i], key.width, 0f) }
    }
    // A spacebar across the middle: cut in two, or bridged over the gap.
    val across = keys.indices.firstOrNull { i ->
        keys[i].action == KeyAction.Space && lefts[i] < mid - 0.01f && lefts[i] + keys[i].width > mid + 0.01f
    }
    if (across != null) {
        val out = ArrayList<HexSlot>(keys.size + 1)
        for (i in keys.indices) {
            val key = keys[i]
            when {
                i < across -> out += slot(i, key, lefts[i], key.width, 0f)
                i > across -> out += slot(i, key, lefts[i], key.width, gap)
                bridgeSpace -> out += slot(i, key.copy(width = key.width + gap), lefts[i], key.width + gap, 0f)
                else -> {
                    val leftPart = mid - lefts[i]
                    val rightPart = key.width - leftPart
                    out += slot(i, key.copy(label = "", width = leftPart), lefts[i], leftPart, 0f)
                    out += slot(i, key.copy(width = rightPart), mid, rightPart, gap)
                }
            }
        }
        // A bridged spacebar ends where the keys after it now start, a gap
        // further on, so the row stays edge to edge across the middle.
        return out
    }
    // Otherwise the key edge nearest the middle, ties to the right.
    var cut = 1
    for (b in 2 until keys.size) {
        if (abs(lefts[b] - mid) <= abs(lefts[cut] - mid) + Epsilon) cut = b
    }
    return keys.mapIndexed { i, key -> slot(i, key, lefts[i], key.width, if (i >= cut) gap else 0f) }
}

/**
 * The outline of a key's face in a honeycomb, as `x, y` pairs in the face's
 * own pixels, clockwise from its top-left corner.
 *
 * The face is the key's cell less the key gap: [faceWidth] by [faceHeight],
 * [gapPx] in from the cell's sides. Its top edge zigzags when [zigTop] (the row
 * above interlocks with this one), its bottom when [zigBottom]; an edge that
 * does not stays straight, which is also how the board's top and bottom edges
 * are squared off. The zigzag is [amplitude] deep and centred on the face's
 * straight edge, so it reaches [amplitude] / 2 past it into the gap and the
 * neighbouring row, where that row's face has the matching notch.
 *
 * [widthCells] is the face's width in cells and [startPhase] where its cell
 * starts in the honeycomb ([HexBoard.startPhase]); together they put a point
 * over every cell centre the key covers and a notch at every cell edge.
 *
 * [convex] drops the notches between the points, leaving the outline's convex
 * hull: what a platform shadow can be cast from on Android 9 and older, which
 * only shadows convex shapes. A one-cell key is convex already and comes back
 * the same.
 */
fun hexFaceOutline(
    widthCells: Float,
    startPhase: Float,
    zigTop: Boolean,
    zigBottom: Boolean,
    faceWidth: Float,
    faceHeight: Float,
    gapPx: Float,
    amplitude: Float,
    convex: Boolean = false,
): FloatArray {
    val w = faceWidth.coerceAtLeast(0f)
    val h = faceHeight.coerceAtLeast(0f)
    val cell = if (widthCells > Epsilon) (w + gapPx * 2f) / widthCells else 0f
    val amp = if (cell > 0f) amplitude.coerceIn(0f, h) else 0f
    if (amp <= 0f || (!zigTop && !zigBottom)) {
        return floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    }
    // Every x where the zigzag turns: cell edges and cell centres, which are
    // half a cell apart, plus the face's own two sides.
    val xs = ArrayList<Float>()
    xs += 0f
    val phaseAtLeft = startPhase + gapPx / cell
    var step = floor(phaseAtLeft * 2f) + 1f
    while (true) {
        val at = (step / 2f - phaseAtLeft) * cell
        if (at >= w - Epsilon) break
        if (at > Epsilon) xs += at
        step += 1f
    }
    xs += w
    fun toCentre(x: Float): Float = abs(latticeFraction(phaseAtLeft + x / cell) - 0.5f)
    // A notch is a turning point away from the centre line's far side; the
    // hull keeps the sides and the points and lets the edge run straight.
    fun kept(k: Int): Boolean = !convex || k == 0 || k == xs.lastIndex || toCentre(xs[k]) < 0.25f
    val out = ArrayList<Float>(xs.size * 4)
    for (k in xs.indices) {
        if (zigTop && !kept(k)) continue
        out += xs[k]
        out += if (zigTop) -amp / 2f + 2f * amp * toCentre(xs[k]) else 0f
    }
    for (k in xs.indices.reversed()) {
        if (zigBottom && !kept(k)) continue
        out += xs[k]
        out += if (zigBottom) h + amp / 2f - 2f * amp * toCentre(xs[k]) else h
    }
    return out.toFloatArray()
}
