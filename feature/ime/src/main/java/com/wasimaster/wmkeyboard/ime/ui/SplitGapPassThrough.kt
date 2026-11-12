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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect

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
 * Only the key rows: the toolbar, the suggestion strip and an open panel keep
 * the whole width.
 */
internal class SplitGapCutout {
    /** Each spacer's hole in window pixels, keyed by the spacer. */
    val gaps = mutableStateMapOf<Any, Rect>()

    /** Where the board's top-left corner sits in the window, so a hole can be drawn in board space. */
    var boardOrigin by mutableStateOf(Offset.Zero)

    fun put(key: Any, rect: Rect) {
        if (gaps[key] != rect) gaps[key] = rect
    }
}

/** The cutout the docked frame keeps while the setting is on; null draws every gap solid. */
internal val LocalSplitGapCutout = staticCompositionLocalOf<SplitGapCutout?> { null }

/**
 * Where the service hears about the holes, as window rectangles, so it can
 * leave them out of the touchable region. An empty list puts the whole board
 * back. A no-op outside the IME (the settings previews).
 */
val LocalSplitGapReport = staticCompositionLocalOf<(List<IntRect>) -> Unit> { {} }

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
                val holes = cutout.gaps.values
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
            report(emptyList())
            return@LaunchedEffect
        }
        snapshotFlow {
            cutout.gaps.values
                .map { IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
                .filter { it.width > 0 && it.height > 0 }
                .sortedWith(compareBy({ it.top }, { it.left }))
        }.collect { report(it) }
    }
    DisposableEffect(report) {
        onDispose { report(emptyList()) }
    }
}
