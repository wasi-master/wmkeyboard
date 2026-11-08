package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Material's [Slider], taking only a drag that is more sideways than up or down,
 * so a page of sliders still scrolls under a thumb (issue #153).
 *
 * Material's slider starts a drag once a finger has gone a touch slop sideways
 * and never compares that with how far it has gone vertically: foundation's
 * slop detector reads the drag's own axis only. The column scrolling the page
 * decides the same way on its own axis, but a touch event reaches the innermost
 * node first, so on any event that carries a finger past the slop on both axes
 * the slider wins. One frame of an ordinary flick up the page does exactly that
 * with a little sideways drift, and then the page stays put while the setting
 * under the thumb changes.
 *
 * Material's drawing, semantics (TalkBack's adjust gestures) and horizontal
 * key handling are kept as they are. Its touch handling never runs: a layer
 * over the slider takes every touch instead, because Compose hands a touch to
 * the topmost of overlapping siblings only, and moves the value itself — see
 * [detectSliderTouches]. Up and down leave the row rather than moving the
 * value — see [moveFocusOffSlider].
 *
 * [steps] works as on Material's slider: that many stops between the ends,
 * drawn as dots on the track, and a finger lands on the nearest one. Without it
 * a whole-number setting is a continuous track cut down to an integer
 * afterwards, which reads as dead zones: the thumb snaps back unless dragged
 * all the way to the next number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WmSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
) {
    val interactions = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()
    // The track's ends sit half a thumb in from the slider's edges, so the value
    // under a finger depends on the thumb's width. Measured rather than assumed:
    // the thumb narrows while it is dragged.
    val thumbWidth = remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    // Min constraints reach the slider as they did when the caller's modifier
    // sat on it directly, so a fixed height or a weight sizes it the same.
    Box(
        modifier.onPreviewKeyEvent { event -> moveFocusOffSlider(event, focus) },
        propagateMinConstraints = true,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
            interactionSource = interactions,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactions,
                    modifier = Modifier.onSizeChanged { measured -> thumbWidth.intValue = measured.width },
                    colors = colors,
                    enabled = enabled,
                )
            },
            valueRange = valueRange,
            steps = steps,
        )
        // A disabled slider takes no touches, Material's or this layer's.
        if (enabled) {
            // Read at event time, so a new lambda or range from a recomposition
            // mid-drag is used without restarting the gesture it is part of.
            val range by rememberUpdatedState(valueRange)
            val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
            val stops by rememberUpdatedState(steps)
            val change by rememberUpdatedState(onValueChange)
            val finish by rememberUpdatedState(onValueChangeFinished)
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(interactions) {
                        detectSliderTouches(
                            interactions = interactions,
                            valueAt = { x -> sliderValueAt(x, size.width, thumbWidth.intValue, rtl, range, stops) },
                            onValueChange = { change(it) },
                            onValueChangeFinished = { finish?.invoke() },
                        )
                    },
            )
        }
    }
}

/**
 * Answers a D-pad's up or down on a focused slider by leaving the row, and says
 * whether it took the key.
 *
 * Material's slider reads the vertical arrows as well as the horizontal ones —
 * up raises the value, down lowers it — and consumes both the press and the
 * release. On a television that is the whole of the remote's way down a
 * settings page, so the first slider the focus reaches keeps it: every press
 * down slides the thumb and nothing below the row can be reached again without
 * Back (issue #259). Left and right still slide it, which is the pair a remote
 * has to spare.
 *
 * The key is taken either way, including at the ends of a list where there is
 * nothing to move to: passing it on would hand it straight back to Material.
 */
internal fun moveFocusOffSlider(event: KeyEvent, focus: FocusManager): Boolean {
    val direction = when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return false
    }
    // The release is consumed too, because Material consumes that as well, but
    // only the press moves: a press and its release are one button, one step.
    if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
    return true
}

/**
 * A slider's touches: a tap sets the value where it lands, a drag that is more
 * sideways than vertical slides it, and anything else is left for a scrolling
 * parent to take.
 *
 * A drag moves the thumb to the finger rather than by the finger's travel, so
 * the thumb stays under it (Material's lags a touch slop behind).
 */
private suspend fun PointerInputScope.detectSliderTouches(
    interactions: MutableInteractionSource,
    valueAt: (x: Float) -> Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) = awaitEachGesture {
    // Unconsumed downs only. A column that is still scrolling takes the down in
    // its Initial pass, and the touch that stops a fling is the column's, not a
    // tap on whichever slider the finger happened to land on.
    val down = awaitFirstDown()
    // Material's tap detector consumes it too, which keeps a clickable row
    // around a slider from also being clicked. A scroll does not look.
    down.consume()
    val drag = awaitSliderDrag(down) {
        onValueChange(valueAt(down.position.x))
        onValueChangeFinished()
    } ?: return@awaitEachGesture
    val start = DragInteraction.Start()
    interactions.tryEmit(start)
    var completed = false
    try {
        onValueChange(valueAt(drag.position.x))
        completed = horizontalDrag(drag.id) {
            it.consume()
            onValueChange(valueAt(it.position.x))
        }
    } finally {
        // Also on cancellation, so the thumb never stays drawn mid-drag.
        interactions.tryEmit(if (completed) DragInteraction.Stop(start) else DragInteraction.Cancel(start))
        onValueChangeFinished()
    }
}

/**
 * Waits for the gesture that began with [down] to become a drag of the slider,
 * and returns the change that made it one.
 *
 * Null when it ended any other way: as a tap, which [onTap] has applied, or by
 * going to someone else. A scrolling parent claims its own drag in the Main
 * pass after this node's, so each event is checked again once that has run.
 */
private suspend fun AwaitPointerEventScope.awaitSliderDrag(
    down: PointerInputChange,
    onTap: () -> Unit,
): PointerInputChange? {
    // Compose's own drag detectors use an eighth of the slop for a mouse, which
    // no scrolling column drags with.
    val slop = viewConfiguration.touchSlop * if (down.type == PointerType.Mouse) MOUSE_SLOP_RATIO else 1f
    var pointer = down.id
    var travel = Offset.Zero
    var tap = true
    var result: PointerInputChange? = null
    var decided = false
    while (!decided) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointer }
        when {
            change == null || change.isConsumed -> decided = true
            change.changedToUpIgnoreConsumed() -> {
                val other = event.changes.firstOrNull { it.pressed }
                if (other != null) {
                    // Another finger carries the gesture on, as in Compose's
                    // detectors, but a gesture that changed hands is no tap.
                    pointer = other.id
                    tap = false
                } else {
                    if (tap) {
                        change.consume()
                        onTap()
                    }
                    decided = true
                }
            }
            else -> {
                if (change.isOutOfBounds(size, extendedTouchPadding)) tap = false
                travel += change.position - change.previousPosition
                if (isSliderDrag(travel, slop)) {
                    change.consume()
                    result = change
                    decided = true
                } else {
                    awaitPointerEvent(PointerEventPass.Final)
                    decided = change.isConsumed
                }
            }
        }
    }
    return result
}

/**
 * Whether a finger that has moved [travel] since it touched down is dragging a
 * slider: at least [slop] sideways, and further sideways than up or down.
 *
 * The second half is the one Material leaves out. Without it a mostly vertical
 * flick with some sideways drift reaches the slop on both axes in the same
 * event, and the slider, which sees the event first, takes it from the page.
 */
internal fun isSliderDrag(travel: Offset, slop: Float): Boolean =
    abs(travel.x) >= slop && abs(travel.x) > abs(travel.y)

/**
 * The value under a finger at [x] on a slider [width] pixels wide, by the
 * geometry Material draws it with: the thumb's centre travels from half a
 * [thumbWidth] in from one edge to half a thumb in from the other, starting at
 * the right edge when [rtl]. Clamped to [range] past either end, and moved
 * to the nearest of [steps] evenly spaced stops when there are any.
 */
internal fun sliderValueAt(
    x: Float,
    width: Int,
    thumbWidth: Int,
    rtl: Boolean,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
): Float {
    val along = if (rtl) width - x else x
    val last = max(width - thumbWidth / 2f, 0f)
    val first = min(thumbWidth / 2f, last)
    val raw = if (last == first) 0f else ((along - first) / (last - first)).coerceIn(0f, 1f)
    val fraction = if (steps > 0) (raw * (steps + 1)).roundToInt() / (steps + 1f) else raw
    return range.start + (range.endInclusive - range.start) * fraction
}

/** Compose's `mouseToTouchSlopRatio`, which its drag detectors apply to a mouse. */
private const val MOUSE_SLOP_RATIO = 0.125f
