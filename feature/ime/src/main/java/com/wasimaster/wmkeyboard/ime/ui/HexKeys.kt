package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.layout.HexBoard
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.hexAmplitude
import com.wasimaster.wmkeyboard.core.layout.hexFaceOutline

/**
 * The face of a key on a honeycomb layout ([com.wasimaster.wmkeyboard.core.layout.KeyboardLayout.hex]),
 * standing in for the theme's key shape: the key's cells as hexagons, see
 * [hexFaceOutline]. The theme still paints it, since every fill, texture,
 * border and press of the face is drawn through whatever shape the key has.
 *
 * The outline reaches past the face's own bounds, up and down by half the
 * zigzag, into the gap and the neighbouring row's notches. The face is drawn
 * in a layer of its own that does not clip, the way a leaning theme shape
 * already spills sideways into the key gap.
 *
 * Everything is in cells and dp here and resolved against the face's size at
 * draw time, so one shape serves every screen width; [maxAmplitudeDp] caps the
 * zigzag for the short, wide keys of a landscape board. Public because the
 * layout editor's preview, in the app module, draws its honeycombs with it.
 */
@Immutable
data class HexFaceShape(
    val widthCells: Float,
    val startPhase: Float,
    val zigTop: Boolean,
    val zigBottom: Boolean,
    val gapDp: Float,
    val maxAmplitudeDp: Float,
    /** The outline's convex hull instead; see [forShadow]. */
    val convex: Boolean = false,
    /**
     * How far each corner is rounded off, in dp (#1392): the theme's key
     * radius, 0 for a Sharp theme's pointed hexagons. Held under half of
     * each edge it rounds, so a short slanted edge stays an edge.
     */
    val cornerRadiusDp: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = points(size, density.density)
        // A right-to-left board is placed mirrored, so its faces are too.
        if (layoutDirection == LayoutDirection.Rtl) {
            for (i in 0 until points.size / 2) points[i * 2] = size.width - points[i * 2]
        }
        val radius = cornerRadiusDp * density.density
        val path = if (radius > 0f) roundedPolygon(points, radius) else straightPolygon(points)
        return Outline.Generic(path)
    }

    /** The outline's corners, as [hexFaceOutline] gives them, for a face of [size]. */
    private fun points(size: Size, density: Float): FloatArray {
        val gap = gapDp * density
        val cell = if (widthCells > 0f) (size.width + gap * 2f) / widthCells else 0f
        return hexFaceOutline(
            widthCells = widthCells,
            startPhase = startPhase,
            zigTop = zigTop,
            zigBottom = zigBottom,
            faceWidth = size.width,
            faceHeight = size.height,
            gapPx = gap,
            amplitude = hexAmplitude(cell, maxAmplitudeDp * density),
            convex = convex,
        )
    }

    /**
     * How far the top edge dips below the face's top at its two corners, in
     * pixels, the deeper of the two: where a corner falls on a notch, the
     * face starts that much lower, and a hint drawn in the corner would sit
     * across the slanted edge and out over the key gap.
     */
    fun cornerDropPx(size: Size, density: Float): Float {
        if (!zigTop) return 0f
        val points = points(size, density)
        // The top edge runs from the first point to the first at the far side.
        var end = 1
        while (end < points.size / 2 && points[end * 2] < size.width) end++
        val right = points.getOrElse(end * 2 + 1) { 0f }
        return maxOf(0f, points[1], right)
    }

    /**
     * This shape as a platform shadow can be cast from: on Android 9 and
     * older a shadow needs a convex outline and casts nothing at all from
     * any other, which a key two cells wide is.
     */
    fun forShadow(): HexFaceShape =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !convex) copy(convex = true) else this
}

/** A closed path through the corners in [points] (x, y pairs). */
private fun straightPolygon(points: FloatArray): Path {
    val path = Path()
    for (i in 0 until points.size / 2) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * A closed path through [points] (x, y pairs) with every corner rounded:
 * each edge is cut back from both of its corners by [radius], or by half its
 * length where that is shorter, and the corner is a curve through those two
 * cut points bent toward the corner itself. A point where the outline runs
 * straight on is no corner and is passed through as it is.
 */
private fun roundedPolygon(points: FloatArray, radius: Float): Path {
    val n = points.size / 2
    if (n < 3) return straightPolygon(points)
    fun x(i: Int) = points[((i % n + n) % n) * 2]
    fun y(i: Int) = points[((i % n + n) % n) * 2 + 1]
    // Where a corner's rounding starts (towards the previous point) and ends
    // (towards the next one), or the corner itself where it is not one.
    val startX = FloatArray(n)
    val startY = FloatArray(n)
    val endX = FloatArray(n)
    val endY = FloatArray(n)
    val round = BooleanArray(n)
    for (i in 0 until n) {
        val inX = x(i) - x(i - 1)
        val inY = y(i) - y(i - 1)
        val outX = x(i + 1) - x(i)
        val outY = y(i + 1) - y(i)
        val inLen = kotlin.math.hypot(inX, inY)
        val outLen = kotlin.math.hypot(outX, outY)
        val cross = inX * outY - inY * outX
        val straight = inLen < 0.01f || outLen < 0.01f || kotlin.math.abs(cross) < 0.001f * inLen * outLen
        if (straight) {
            startX[i] = x(i); startY[i] = y(i); endX[i] = x(i); endY[i] = y(i)
            continue
        }
        round[i] = true
        val cutIn = minOf(radius, inLen / 2f)
        val cutOut = minOf(radius, outLen / 2f)
        startX[i] = x(i) - inX / inLen * cutIn
        startY[i] = y(i) - inY / inLen * cutIn
        endX[i] = x(i) + outX / outLen * cutOut
        endY[i] = y(i) + outY / outLen * cutOut
    }
    val path = Path()
    path.moveTo(endX[0], endY[0])
    for (k in 1..n) {
        val i = k % n
        path.lineTo(startX[i], startY[i])
        if (round[i]) path.quadraticTo(x(i), y(i), endX[i], endY[i])
    }
    path.close()
    return path
}

/**
 * The key's border drawn inside its honeycomb face (#1391): the outline
 * stroked at twice the width and clipped to the face, so the whole width
 * lands inside it, the way `Modifier.border` lays a rounded key's. That
 * modifier draws a free-form outline through a cached mask cut out with a
 * path operation, and on these faces, whose points reach past the key's
 * own box, the mask ate stretches of the border.
 */
internal fun Modifier.hexFaceBorder(width: Dp, color: androidx.compose.ui.graphics.Color, shape: Shape): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val stroke = Stroke(width = width.toPx() * 2f, join = StrokeJoin.Round)
        onDrawWithContent {
            drawContent()
            clipPath(path) { drawPath(path, color, style = stroke) }
        }
    }

/** [this] as a platform shadow can be cast from; see [HexFaceShape.forShadow]. */
internal fun Shape.forShadow(): Shape = (this as? HexFaceShape)?.forShadow() ?: this

/**
 * Moves a corner hint down out of the slant of a honeycomb key's top edge
 * ([HexFaceShape.cornerDropPx]), measured against the face it is laid out in:
 * the hint is a child of the face, so its constraints are the face's size.
 * Nothing on any other key.
 */
internal fun Modifier.clearHexCorner(shape: Shape?): Modifier {
    val hex = shape as? HexFaceShape ?: return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val drop = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val face = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            hex.cornerDropPx(face, density).roundToInt()
        } else {
            0
        }
        // Its own size kept, so its alignment in the face is unchanged; only
        // where it is drawn moves down.
        layout(placeable.width, placeable.height) { placeable.place(0, drop) }
    }
}

/**
 * Honeycomb outlines for overlays that repaint every frame (the key lighting),
 * made once per key and size rather than once per frame. Keyed by the key's
 * cell, which is what those overlays walk.
 */
internal class FaceOutlines {
    private class Made(val shape: Shape, val size: Size, val outline: Outline)

    private val made = HashMap<Rect, Made>()

    fun of(cell: Rect, shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        made[cell]?.let { if (it.shape == shape && it.size == size) return it.outline }
        // Cells move with the board, so old ones are dropped rather than kept.
        if (made.size > MaxFaceOutlines) made.clear()
        return shape.createOutline(size, layoutDirection, density).also { made[cell] = Made(shape, size, it) }
    }
}

/** More cells than any layer has keys, so the table is only cleared on a move. */
private const val MaxFaceOutlines = 256

/**
 * Settles which key a touch on a honeycomb belongs to, as the finger goes down.
 *
 * The keys take their touches the way every key does, in the rectangle each is
 * laid out in: Compose hands a touch to the node it lands in and has no way to
 * hand it to a hexagon. The rectangles and the hexagons share their centres
 * and differ only in the corners, where a hexagon's point reaches into the row
 * above or below. So this watches each down before the keys see it (the
 * Initial pass, never consuming), asks the board which cell's centre the touch
 * is nearest ([HexBoard.slotAt]), and when that is not the rectangle's key it
 * files the honeycomb's key under the pointer in [owners]. The key that owns
 * the touch reads it when it commits, through [hexResolve].
 *
 * [rowHeightsDp] and [gapV] are what the band lays its rows out with, so the
 * row tops here are the ones the keys were placed at.
 */
internal fun Modifier.hexTouchOwners(
    board: HexBoard,
    rowHeightsDp: List<Int>,
    gapV: Dp,
    maxAmplitudeDp: Float,
    rtl: Boolean,
    owners: HashMap<PointerId, Key>,
    /** The octopus's lane over each row, which the band lays out too (#1384). */
    lane: Dp = 0.dp,
): Modifier = pointerInput(board, rowHeightsDp, gapV, maxAmplitudeDp, rtl, lane) {
    val tops = FloatArray(rowHeightsDp.size + 1)
    for (i in rowHeightsDp.indices) {
        tops[i + 1] = tops[i] + (rowHeightsDp[i].dp + gapV * 2).roundToPx()
    }
    // The band's own row tops, lanes included, to take a touch back into the
    // lane-free rows the board is laid out in.
    val lanePx = lane.roundToPx().toFloat()
    val laned = FloatArray(tops.size) { it * lanePx + tops[it] }
    fun boardY(y: Float): Float {
        var row = 0
        while (row < rowHeightsDp.size - 1 && y >= laned[row + 1]) row++
        return (y - lanePx * (row + 1)).coerceAtLeast(tops[row])
    }
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (!change.changedToDownIgnoreConsumed()) continue
                // Ids only grow, so nothing is overwritten; pruned wholesale
                // rather than on the lift, which the owning key reads after.
                if (owners.size > MaxHexOwners) owners.clear()
                val width = size.width.toFloat()
                val cell = if (board.weight > 0f) width / board.weight else 0f
                val x = if (rtl) width - change.position.x else change.position.x
                val y = boardY(change.position.y)
                val amplitude = hexAmplitude(cell, maxAmplitudeDp * density)
                val hex = board.slotAt(x, y, tops, cell, amplitude)
                val plain = board.slotAt(x, y, tops, cell, 0f)
                if (hex >= 0 && hex != plain) {
                    owners[change.id] = board.slots[hex].key
                } else {
                    owners.remove(change.id)
                }
            }
        }
    }
}

/** More pointers than any hand puts down at once; see [hexTouchOwners]. */
private const val MaxHexOwners = 32

/**
 * [then] with the honeycomb's answer from [owners] in place of the key that
 * took the touch, where the two differ.
 *
 * Only between keys that type: a letter's point reaching into Shift's
 * rectangle still types the letter, but a press that Shift or Delete took
 * stays theirs, because their own gestures (a held Shift, a repeating Delete)
 * have already run by the time anything commits. The hexagons' corners
 * between an action key and a letter therefore answer as rectangles do.
 */
internal fun hexResolve(
    owners: HashMap<PointerId, Key>,
    then: (Key, PointerId) -> Key,
): (Key, PointerId) -> Key = { key, id ->
    val owner = owners[id]?.takeIf {
        it !== key && it.action == KeyAction.Text && key.action == KeyAction.Text
    }
    then(owner ?: key, id)
}
