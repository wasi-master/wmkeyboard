package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mouse
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenWith
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.TrackpadSettings
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.MouseRelease
import com.wasimaster.wmkeyboard.ime.PointerMotion
import com.wasimaster.wmkeyboard.ime.TrackpadAxis
import com.wasimaster.wmkeyboard.ime.TrackpadRelease
import com.wasimaster.wmkeyboard.ime.TrackpadTapCounter
import com.wasimaster.wmkeyboard.ime.classifyMouseRelease
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
 *
 * In mouse mode (#1411) the same surface drives a pointer over the app
 * instead ([mouseGestures]); the chip in its corner switches between the two.
 */
@Composable
internal fun TrackpadField(settings: TrackpadSettings, callbacks: TrackpadFieldCallbacks) {
    val kb = LocalKbTheme.current
    val pointer = callbacks.mouse.pointer
    // Off until turned on in the tool's settings. Then read once per
    // composition: the lock screen, the channel and a docked board decide it,
    // and the panel recomposes whenever any of those can change.
    val mouseOffered = settings.mouseEnabled && pointer != null && pointer.offered()
    val mouseOn = mouseOffered && settings.mouseMode
    if (pointer != null) {
        DisposableEffect(pointer, mouseOn) {
            if (mouseOn) pointer.begin()
            onDispose { if (mouseOn) pointer.end() }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.modifierKey.copy(alpha = 0.35f), kb.keyShape()),
    ) {
        if (mouseOn && pointer != null) {
            TrackpadMouseSurface(settings, pointer)
        } else {
            TrackpadCaretSurface(settings, callbacks)
        }
        if (mouseOffered) {
            TrackpadModeChip(
                mouse = mouseOn,
                onMode = callbacks.mouse.onMode,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        if (mouseOn && pointer?.door == com.wasimaster.wmkeyboard.ime.pointer.PointerDoor.NONE) {
            TrackpadMouseSetupHint(
                onSetup = callbacks.mouse.onSetup,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

/** The caret trackpad: today's surface, unchanged by mouse mode. */
@Composable
private fun TrackpadCaretSurface(settings: TrackpadSettings, callbacks: TrackpadFieldCallbacks) {
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
                    // Whether this press put the magnifier up, for the same reason.
                    // A drag or a hold does; a tap never gets that far.
                    var dragging = false
                    fun startDrag() {
                        if (dragging) return
                        dragging = true
                        callbacks.onCaretDrag(true)
                    }
                    touching.value = true
                    if (settings.trail) trail.push(downPos.x, downPos.y)
                    val timer = scope.launch {
                        delay(longPressMs)
                        if (!moved && maxFingers == 1) {
                            longPressed = true
                            selecting = true
                            tick()
                            callbacks.onSelectionHold(true)
                            startDrag()
                        }
                    }
                    // Whether the last finger came up. A gesture cut short (the
                    // loop restarted under it) is not a release: it must not
                    // type the two-finger tap's space or count as a tap.
                    var lifted = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                lifted = true
                                break
                            }
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
                                startDrag()
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
                        if (dragging) callbacks.onCaretDrag(false)
                        val release = if (lifted) {
                            classifyRelease(moved, longPressed, maxFingers)
                        } else {
                            TrackpadRelease.NONE
                        }
                        when (release) {
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
            .trailDrawing(trail, kb.modifierKeyText),
        contentAlignment = Alignment.Center,
    ) {
        TrackpadHint(touching, Icons.Outlined.OpenWith, stringResource(R.string.ime_trackpad_hint), accent)
    }
}

/**
 * The mouse trackpad (#1411): the same surface as [TrackpadCaretSurface],
 * driving [pointer] instead of the caret.
 *
 * - one finger drags: move the pointer, faster the faster the flick
 * - tap: click; two quick taps: a double click, as the app counts them
 * - two fingers tap: right click
 * - two fingers drag: scroll
 * - hold, then drag: drag and drop, the button held until the finger lifts
 */
@Composable
private fun TrackpadMouseSurface(settings: TrackpadSettings, pointer: com.wasimaster.wmkeyboard.ime.pointer.PointerController) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tick = LocalHapticFeedback.current
    val trail = remember { TrailBuffer() }
    val touching = remember { mutableStateOf(false) }
    val accent = kb.modifierKeyText
    val description = stringResource(R.string.ime_trackpad_mouse_surface_desc)
    val speed = settings.pointerSpeed / 100f * MOUSE_BASE_GAIN
    val scroll = settings.scrollSpeed / 100f * MOUSE_SCROLL_GAIN * if (settings.naturalScroll) 1f else -1f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .pointerInput(pointer, speed, scroll, settings.pointerAcceleration, settings.haptics, settings.trail) {
                mouseGestures(
                    pointer = pointer,
                    motion = PointerMotion(speed, settings.pointerAcceleration, density.density),
                    scroll = scroll,
                    haptics = settings.haptics,
                    tick = tick,
                    trail = trail.takeIf { settings.trail },
                    touching = touching,
                    scope = scope,
                )
            }
            .trailDrawing(trail, accent),
        contentAlignment = Alignment.Center,
    ) {
        TrackpadHint(touching, Icons.Outlined.Mouse, stringResource(R.string.ime_trackpad_mouse_hint), accent)
    }
}

/** The mouse's base gain: the pad is a third of the screen, so a pointer at 1:1 crawls. */
private const val MOUSE_BASE_GAIN = 1.6f

/** The scroll's gain over finger travel, for the same reason. */
private const val MOUSE_SCROLL_GAIN = 2f

/** The gesture loop of [TrackpadMouseSurface]; see there for the vocabulary. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mouseGestures(
    pointer: com.wasimaster.wmkeyboard.ime.pointer.PointerController,
    motion: PointerMotion,
    scroll: Float,
    haptics: Boolean,
    tick: () -> Unit,
    trail: TrailBuffer?,
    touching: androidx.compose.runtime.MutableState<Boolean>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val downPos = down.position
        var lastCentroid = downPos
        var lastTime = down.uptimeMillis
        var lastFingers = 1
        var maxFingers = 1
        var moved = false
        var longPressed = false
        // Whether this press holds the button down, so the release lets go of
        // exactly what the press took, and whether two fingers scrolled.
        var holding = false
        var scrolled = false
        touching.value = true
        trail?.push(downPos.x, downPos.y)
        val timer = scope.launch {
            delay(longPressMs)
            if (!moved && maxFingers == 1) {
                longPressed = true
                holding = true
                if (haptics) tick()
                pointer.press()
            }
        }
        var lifted = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    lifted = true
                    break
                }
                for (change in event.changes) change.consume()
                val fingers = pressed.size
                if (fingers > maxFingers) maxFingers = fingers
                var cx = 0f
                var cy = 0f
                var now = 0L
                for (change in pressed) {
                    cx += change.position.x
                    cy += change.position.y
                    if (change.uptimeMillis > now) now = change.uptimeMillis
                }
                cx /= fingers
                cy /= fingers
                if (fingers != lastFingers) {
                    // A finger arriving or leaving jumps the centroid; that
                    // jump is not travel. Re-anchor and carry on.
                    lastFingers = fingers
                    lastCentroid = Offset(cx, cy)
                    lastTime = now
                    if (fingers >= 2 && !longPressed) timer.cancel()
                    continue
                }
                val dx = cx - lastCentroid.x
                val dy = cy - lastCentroid.y
                val dt = now - lastTime
                lastCentroid = Offset(cx, cy)
                lastTime = now
                if (!moved) {
                    if ((lastCentroid - downPos).getDistance() <= slop) continue
                    moved = true
                    if (!longPressed) timer.cancel()
                }
                trail?.push(cx, cy)
                if (maxFingers >= 2 && !holding) {
                    scrolled = true
                    pointer.scrollBy(dx * scroll, dy * scroll)
                } else {
                    val gain = motion.gain(dx, dy, dt)
                    pointer.moveBy(dx * gain, dy * gain)
                }
            }
        } finally {
            timer.cancel()
            touching.value = false
            trail?.clear()
            if (holding) pointer.release()
            if (scrolled) pointer.scrollEnd()
            val release = if (lifted) classifyMouseRelease(moved, longPressed, maxFingers) else MouseRelease.NONE
            when (release) {
                MouseRelease.NONE -> Unit
                MouseRelease.CLICK -> {
                    if (haptics) tick()
                    pointer.click()
                }
                MouseRelease.RIGHT_CLICK -> {
                    if (haptics) tick()
                    pointer.rightClick()
                }
            }
        }
    }
}

/**
 * The finger's trail and a crosshair where it is now, drawn behind the
 * surface's content. The only read of [trail]: a push invalidates this draw
 * and nothing else.
 */
private fun Modifier.trailDrawing(trail: TrailBuffer, accent: androidx.compose.ui.graphics.Color): Modifier =
    drawWithCache {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val cross = 14.dp.toPx()
        val trailColor = accent.copy(alpha = 0.5f)
        val crossColor = accent.copy(alpha = 0.8f)
        onDrawBehind {
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
    }

/** The idle hint in the middle of the surface, faded while a finger is down, read in the layer. */
@Composable
private fun TrackpadHint(
    touching: androidx.compose.runtime.State<Boolean>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { alpha = if (touching.value) 0.25f else 0.6f },
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        Text(text, color = accent, fontSize = 12.sp)
    }
}

/**
 * Cursor or Mouse, in the surface's corner (#1411). A sibling of the surface,
 * not a child, so a tap on it never reaches the surface's gesture loop as a
 * click.
 */
@Composable
private fun TrackpadModeChip(mouse: Boolean, onMode: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(kb.modifierKey.copy(alpha = 0.8f), shape)
            .selectableGroup(),
    ) {
        TrackpadModeSegment(Icons.Outlined.TextFields, stringResource(R.string.ime_trackpad_mode_cursor), !mouse) { onMode(false) }
        TrackpadModeSegment(Icons.Outlined.Mouse, stringResource(R.string.ime_trackpad_mode_mouse), mouse) { onMode(true) }
    }
}

@Composable
private fun TrackpadModeSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val tint = if (selected) kb.modifierKey else kb.modifierKeyText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (selected) Modifier.background(kb.modifierKeyText.copy(alpha = 0.9f), RoundedCornerShape(50)) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Mouse mode with no door open for clicks: one line, and a tap to the touch mouse service's disclosure. */
@Composable
private fun TrackpadMouseSetupHint(onSetup: () -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(12.dp)
    Text(
        stringResource(R.string.ime_trackpad_mouse_setup),
        color = kb.modifierKeyText,
        fontSize = 12.sp,
        modifier = modifier
            .clip(shape)
            .background(kb.modifierKey.copy(alpha = 0.9f), shape)
            .clickable(role = Role.Button, onClick = onSetup)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
