package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The hardware focus ring: what the arrow keys are pointing at.
 *
 * A 2 dp accent outline *and* a faint accent fill. The fill is what makes it
 * readable rather than decorative — several panels already draw a bare accent
 * border to mean *selected* (the current theme, the active mode), so an outline
 * on its own would say two different things in the same grid.
 */
@Composable
fun Modifier.focusRing(
    active: Boolean,
    shape: Shape = RoundedCornerShape(8.dp),
): Modifier {
    if (!active) return this
    val theme = LocalKbTheme.current
    return this
        .background(theme.accent.copy(alpha = 0.16f), shape)
        .border(2.dp, theme.accent, shape)
}

/**
 * Keeps the ringed item on screen. [scroll] is the panel's own scroll call, so
 * each list type stays responsible for its own arithmetic — index and scroll
 * position are the same number in a grid and emphatically not in the dictionary
 * panel, whose entries each occupy several lazy items.
 *
 * Not animated when the user has asked for less motion; nothing else about the
 * ring animates either.
 */
@Composable
fun ScrollFocusIntoView(index: Int?, scroll: suspend (Int) -> Unit) {
    LaunchedEffect(index) {
        if (index != null) scroll(index)
    }
}

/**
 * Brings grid item [index] into view only as far as it has to: no scroll at
 * all while it is fully on screen, just the overhang when an edge cuts it off,
 * and a jump that puts it at the top only when it is nowhere in sight.
 *
 * For a grid walked one cell at a time with a D-pad. A plain
 * `animateScrollToItem` threw the ringed row to the top on every press, even a
 * press sideways along a row already in view, so on a television remote the
 * whole emoji grid slid under a highlight that never seemed to move.
 */
internal suspend fun LazyGridState.revealItem(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val top = item.offset.y
    val bottom = top + item.size.height
    val delta = when {
        top < info.viewportStartOffset -> top - info.viewportStartOffset
        bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
        else -> return
    }
    animateScrollBy(delta.toFloat())
}

/**
 * How many cells wide a `GridCells.Adaptive` grid turned out to be. There is no
 * way to know before layout, so it is read back from what got placed; 1 until
 * the first frame has been measured, which is why focus is only ever seeded
 * against a grid that has already reported items.
 */
@Composable
fun adaptiveColumns(gridState: LazyGridState): Int {
    val columns by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column }?.plus(1) ?: 1
        }
    }
    return columns
}
