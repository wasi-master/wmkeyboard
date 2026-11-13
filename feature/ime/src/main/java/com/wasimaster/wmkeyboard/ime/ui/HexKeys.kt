package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * zigzag for the short, wide keys of a landscape board.
 */
@Immutable
internal data class HexFaceShape(
    val widthCells: Float,
    val startPhase: Float,
    val zigTop: Boolean,
    val zigBottom: Boolean,
    val gapDp: Float,
    val maxAmplitudeDp: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val gap = gapDp * density.density
        val cell = if (widthCells > 0f) (size.width + gap * 2f) / widthCells else 0f
        val points = hexFaceOutline(
            widthCells = widthCells,
            startPhase = startPhase,
            zigTop = zigTop,
            zigBottom = zigBottom,
            faceWidth = size.width,
            faceHeight = size.height,
            gapPx = gap,
            amplitude = hexAmplitude(cell, maxAmplitudeDp * density.density),
        )
        // A right-to-left board is placed mirrored, so its faces are too.
        val mirror = layoutDirection == LayoutDirection.Rtl
        val path = Path()
        for (i in 0 until points.size / 2) {
            val x = if (mirror) size.width - points[i * 2] else points[i * 2]
            val y = points[i * 2 + 1]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

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
): Modifier = pointerInput(board, rowHeightsDp, gapV, maxAmplitudeDp, rtl) {
    val tops = FloatArray(rowHeightsDp.size + 1)
    for (i in rowHeightsDp.indices) {
        tops[i + 1] = tops[i] + (rowHeightsDp[i].dp + gapV * 2).roundToPx()
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
                val y = change.position.y
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
