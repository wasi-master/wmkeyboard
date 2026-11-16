package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A row of tags that scrolls sideways and whose tags can be dragged into a
 * new order (#612): on a clip, in the clipboard's filter row and under the
 * tags editor.
 *
 * One gesture per tag, so a press does one thing: a tap is [onTap]; a press
 * held still and let go is [onHold]; held and then moved, the tag follows the
 * finger along the row and [onMove] gets the new order where it is let go. A
 * finger that moves before the hold is the row's scroll, as in any list.
 */
@Composable
internal fun DragTagRow(
    tags: List<String>,
    onMove: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 6.dp,
    onTap: ((String) -> Unit)? = null,
    onHold: ((String) -> Unit)? = null,
    tag: @Composable (String, Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val latestTags by rememberUpdatedState(tags)
    val latestMove by rememberUpdatedState(onMove)
    val latestTap by rememberUpdatedState(onTap)
    val latestHold by rememberUpdatedState(onHold)
    // Where each tag sits along the row, by tag: its left edge and width.
    val places = remember { HashMap<String, Pair<Float, Float>>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var shift by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (name in tags) {
            val lifted = dragging == name
            Box(
                modifier = Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (lifted) shift else 0f
                        if (lifted) {
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    // Not while it is lifted, when its place is the finger's.
                    .onGloballyPositioned { if (dragging != name) places[name] = it.positionInParentX() to it.size.width.toFloat() }
                    .pointerInput(name) {
                        val slop = viewConfiguration.touchSlop
                        val holdMs = viewConfiguration.longPressTimeoutMillis
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var held = true
                            var tapped = false
                            withTimeoutOrNull(holdMs) {
                                var done = false
                                while (!done) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    when {
                                        change == null || change.isConsumed -> { held = false; done = true }
                                        change.changedToUp() -> {
                                            held = false
                                            tapped = true
                                            change.consume()
                                            done = true
                                        }
                                        (change.position - down.position).getDistance() > slop -> { held = false; done = true }
                                    }
                                }
                            }
                            if (tapped) latestTap?.invoke(name)
                            if (!held) return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            var moved = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val travel = change.position.x - down.position.x
                                if (!moved && kotlin.math.abs(travel) > slop) {
                                    moved = true
                                    dragging = name
                                }
                                if (moved) {
                                    change.consume()
                                    shift = travel
                                }
                            }
                            if (!moved) {
                                latestHold?.invoke(name)
                                return@awaitEachGesture
                            }
                            val order = latestTags
                            val from = order.indexOf(name)
                            val own = places[name]
                            dragging = null
                            val travelled = shift
                            shift = 0f
                            if (from < 0 || own == null) return@awaitEachGesture
                            // Where the tag's middle was let go, among the others' middles.
                            val middle = own.first + own.second / 2f + travelled
                            val others = order.filter { it != name }
                            val to = others.count { other -> places[other]?.let { it.first + it.second / 2f < middle } == true }
                            if (to == from) return@awaitEachGesture
                            latestMove(others.toMutableList().apply { add(to.coerceIn(0, size), name) })
                        }
                    },
            ) {
                tag(name, lifted)
            }
        }
    }
}

/** This box's left edge in its parent, in pixels. */
private fun androidx.compose.ui.layout.LayoutCoordinates.positionInParentX(): Float =
    parentLayoutCoordinates?.localPositionOf(this, androidx.compose.ui.geometry.Offset.Zero)?.x ?: 0f
