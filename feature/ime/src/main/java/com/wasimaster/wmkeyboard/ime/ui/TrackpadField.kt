package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.TrackpadSettings
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.TrackpadAxis
import com.wasimaster.wmkeyboard.ime.TrackpadRelease
import com.wasimaster.wmkeyboard.ime.TrackpadTapCounter
import com.wasimaster.wmkeyboard.ime.classifyRelease
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * The trackpad tool's surface (issue #39): the key area as a pointing device.
 *
 * Relative, like a laptop's pad. The finger lands anywhere and the caret moves
 * by how far it travels from there, a character per [TrackpadSettings.stepXDp]
 * sideways and a line per [TrackpadSettings.stepYDp] up or down. The whole
 * gesture vocabulary:
 *
 * - one finger drags: move the caret
 * - hold, then drag: select (selection mode for as long as the finger stays)
 * - two taps: select the word at the caret; three: the line
 * - two fingers drag: move a word at a time
 * - two fingers tap: a space
 *
 * Every move is a [TextEditAction] through the same handler the text-editing
 * panel's keys use, so it is a real key event to the field and honours
 * whatever selection mode is armed, from wherever it was armed. Nothing here
 * reads touch state at composition scope: the trail is a ring of plain floats
 * with a version the draw lambda alone observes, and the hint's fade is a
 * `graphicsLayer` read.
 */
@Composable
internal fun TrackpadField(settings: TrackpadSettings, callbacks: TrackpadFieldCallbacks) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tick = LocalHapticFeedback.current
    val trail = remember { TrailBuffer() }
    val touching = remember { mutableStateOf(false) }
    val stepXPx = with(density) { settings.stepXDp.dp.toPx() }
    val stepYPx = with(density) { settings.stepYDp.dp.toPx() }
    val accent = kb.modifierKeyText
    val description = stringResource(R.string.ime_trackpad_surface_desc)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.modifierKey.copy(alpha = 0.35f), kb.keyShape())
            .semantics { contentDescription = description }
            // Keyed on the settings it reads, not on the callbacks: those are
            // built once per KeyboardBody and a new object mid-gesture would
            // restart this handler under the finger.
            .pointerInput(stepXPx, stepYPx, settings.multiTap, settings.haptics, settings.trail) {
                val xAxis = TrackpadAxis(stepXPx)
                val yAxis = TrackpadAxis(stepYPx)
                // Words are bigger jumps, so a word costs twice a character's travel.
                val wordAxis = TrackpadAxis(stepXPx * 2)
                val slop = viewConfiguration.touchSlop
                val taps = TrackpadTapCounter(viewConfiguration.doubleTapTimeoutMillis, slop * 2)
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                fun step(steps: Int, backward: TextEditAction, forward: TextEditAction) {
                    repeat(abs(steps)) {
                        callbacks.onEdit(if (steps < 0) backward else forward)
                        if (settings.haptics) tick()
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    xAxis.reset()
                    yAxis.reset()
                    wordAxis.reset()
                    val downPos = down.position
                    var lastCentroid = downPos
                    var lastFingers = 1
                    var maxFingers = 1
                    var moved = false
                    var longPressed = false
                    // Whether selection mode is on because of *this* hold, so
                    // the release turns off exactly what the press turned on.
                    var selecting = false
                    touching.value = true
                    if (settings.trail) trail.push(downPos.x, downPos.y)
                    val timer = scope.launch {
                        delay(longPressMs)
                        if (!moved && maxFingers == 1) {
                            longPressed = true
                            selecting = true
                            tick()
                            callbacks.onSelectionHold(true)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            for (change in event.changes) change.consume()
                            val fingers = pressed.size
                            if (fingers > maxFingers) maxFingers = fingers
                            var cx = 0f
                            var cy = 0f
                            for (change in pressed) {
                                cx += change.position.x
                                cy += change.position.y
                            }
                            cx /= fingers
                            cy /= fingers
                            if (fingers != lastFingers) {
                                // A finger arriving or leaving jumps the centroid;
                                // that jump is not travel. Re-anchor and carry on.
                                lastFingers = fingers
                                lastCentroid = Offset(cx, cy)
                                xAxis.reset()
                                yAxis.reset()
                                wordAxis.reset()
                                // Two fingers are never a hold.
                                if (fingers >= 2 && !longPressed) timer.cancel()
                                continue
                            }
                            val dx = cx - lastCentroid.x
                            val dy = cy - lastCentroid.y
                            lastCentroid = Offset(cx, cy)
                            if (!moved) {
                                if ((lastCentroid - downPos).getDistance() <= slop) continue
                                moved = true
                                // Travel before the hold registered is a drag,
                                // not a hold; after it, the hold stands and the
                                // drag selects.
                                if (!longPressed) timer.cancel()
                            }
                            if (settings.trail) trail.push(cx, cy)
                            if (maxFingers >= 2) {
                                step(wordAxis.advance(dx), TextEditAction.WORD_LEFT, TextEditAction.WORD_RIGHT)
                            } else {
                                step(xAxis.advance(dx), TextEditAction.LEFT, TextEditAction.RIGHT)
                                step(yAxis.advance(dy), TextEditAction.UP, TextEditAction.DOWN)
                            }
                        }
                    } finally {
                        timer.cancel()
                        touching.value = false
                        trail.clear()
                        // Whatever ended the gesture, the mode this press armed
                        // ends with it. The selection itself stays: it lives in
                        // the editor and nothing here collapses it.
                        if (selecting) callbacks.onSelectionHold(false)
                        when (classifyRelease(moved, longPressed, maxFingers)) {
                            TrackpadRelease.NONE -> taps.reset()
                            TrackpadRelease.TWO_FINGER_TAP -> {
                                taps.reset()
                                callbacks.onSpace()
                            }
                            TrackpadRelease.TAP -> if (settings.multiTap) {
                                when (taps.tap(down.uptimeMillis, downPos.x, downPos.y)) {
                                    2 -> callbacks.onEdit(TextEditAction.SELECT_WORD)
                                    3 -> callbacks.onEdit(TextEditAction.SELECT_LINE)
                                }
                            }
                        }
                    }
                }
            }
            .drawWithCache {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val cross = 14.dp.toPx()
                val trailColor = accent.copy(alpha = 0.5f)
                val crossColor = accent.copy(alpha = 0.8f)
                onDrawBehind {
                    // The only read of the trail: a push invalidates this draw
                    // and nothing else.
                    trail.version.intValue
                    val n = trail.count
                    if (n == 0) return@onDrawBehind
                    var prevX = Float.NaN
                    var prevY = Float.NaN
                    for (i in 0 until n) {
                        val x = trail.xAt(i)
                        val y = trail.yAt(i)
                        if (!prevX.isNaN()) {
                            drawLine(
                                trailColor.copy(alpha = trailColor.alpha * (i + 1) / n),
                                Offset(prevX, prevY), Offset(x, y),
                                strokeWidth = stroke.width, cap = stroke.cap,
                            )
                        }
                        prevX = x
                        prevY = y
                    }
                    drawLine(crossColor, Offset(prevX - cross, prevY), Offset(prevX + cross, prevY), stroke.width, stroke.cap)
                    drawLine(crossColor, Offset(prevX, prevY - cross), Offset(prevX, prevY + cross), stroke.width, stroke.cap)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Fades while a finger is down, read in the layer so a touch never
            // recomposes the hint.
            modifier = Modifier.graphicsLayer { alpha = if (touching.value) 0.25f else 0.6f },
        ) {
            Icon(
                Icons.Outlined.OpenWith,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(R.string.ime_trackpad_hint),
                color = accent,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * The last few finger positions of a drag, in plain arrays Compose cannot
 * observe, plus one counter the draw lambda watches. Oldest first through
 * [xAt]/[yAt].
 */
private class TrailBuffer(private val capacity: Int = 24) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var next = 0
    var count = 0
        private set
    val version = mutableIntStateOf(0)

    fun push(x: Float, y: Float) {
        xs[next] = x
        ys[next] = y
        next = (next + 1) % capacity
        count = min(count + 1, capacity)
        version.intValue++
    }

    fun clear() {
        count = 0
        next = 0
        version.intValue++
    }

    private fun slot(i: Int) = (next - count + i + capacity) % capacity
    fun xAt(i: Int) = xs[slot(i)]
    fun yAt(i: Int) = ys[slot(i)]
}
