package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a swipe action looks like: the colour pair its button is drawn in. */
enum class SwipeTone { NEUTRAL, ACCENT, DESTRUCTIVE }

/**
 * One button behind a row, shown when the row is swiped toward the start.
 *
 * Every action here must also be reachable on the row itself (a trailing
 * button, its overflow menu, the editor the row opens). A swipe is invisible
 * to TalkBack and to anyone who has not found it, so it is a shortcut to what
 * the row already offers, never the only way to it.
 */
@Immutable
class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val tone: SwipeTone = SwipeTone.NEUTRAL,
    val onClick: () -> Unit,
)

/**
 * The colour pairs the buttons behind a row are drawn in, one per [SwipeTone].
 * The settings app passes its Material scheme; the keyboard passes its theme,
 * which is not Material's.
 */
@Immutable
class SwipeActionColors(
    val neutral: Color,
    val onNeutral: Color,
    val accent: Color,
    val onAccent: Color,
    val destructive: Color,
    val onDestructive: Color,
) {
    internal fun of(tone: SwipeTone): Pair<Color, Color> = when (tone) {
        SwipeTone.NEUTRAL -> neutral to onNeutral
        SwipeTone.ACCENT -> accent to onAccent
        SwipeTone.DESTRUCTIVE -> destructive to onDestructive
    }
}

/**
 * The one row on screen that is swiped open, if any.
 *
 * Opening a row closes the last one, as every mail app does: two rows standing
 * open is two sets of buttons with nothing saying which row each belongs to.
 * Process-wide rather than per screen, because only one screen takes touches
 * at a time and a token left behind by a screen that has gone is harmless.
 * The keyboard and the settings app run in one process and share it, which is
 * what you want: a row left open in settings closes when the keyboard opens
 * one.
 */
private object OpenSwipeRow {
    var owner by mutableStateOf<Any?>(null)
}

/** Width of one action's slot behind the row. */
private val DefaultSlotWidth = 56.dp

/** The button inside a slot. */
private val DefaultButtonSize = 44.dp

/** Air between the last button and the card's edge. */
private val SwipeEndPadding = 8.dp

private val SwipeSettle = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** A fling faster than this opens or closes the row whatever its position. */
private const val SwipeFlingVelocity = 600f

/**
 * Past this share of the row's width a [fullSwipe] action is armed, and fires
 * when the finger lifts.
 */
private const val FullSwipeFraction = 0.55f

/**
 * A row that slides toward the start to show [actions] behind it, the way a
 * mail app shows archive and delete.
 *
 * The row stays where it is until it is dragged; a drag past half the
 * buttons' width or a fling leaves it open, and a tap on the open row, a drag
 * back, or opening another row closes it. Pressing a button closes the row and
 * runs the action.
 *
 * [fullSwipe], when given, is what a long swipe does without stopping at the
 * buttons: dragged past just over half the row, the strip turns the action's
 * colour, and letting go runs it. Give it only for an action that can be taken
 * back — a delete that offers an undo through [LocalSettingsSnackbar] — since
 * a long swipe is also the easiest one to make by accident.
 *
 * A row in a list the user deletes from must be keyed (`item(key = id) { }`,
 * or `key(id)` in a plain column). The swipe position is remembered per
 * composition slot, and an unkeyed row that slides up into a deleted one's
 * place would inherit it, open or slid away.
 *
 * [colors] paints the buttons; [reduceMotion] snaps instead of springing;
 * [onTick] is the haptic for a long swipe arming and disarming. [buttonSize]
 * and [slotWidth] shrink the buttons for a short row, a keyboard panel's.
 */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeAction>,
    colors: SwipeActionColors,
    modifier: Modifier = Modifier,
    fullSwipe: SwipeAction? = null,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
    onTick: () -> Unit = {},
    buttonSize: Dp = DefaultButtonSize,
    slotWidth: Dp = DefaultSlotWidth,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty() || !enabled) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val tick by rememberUpdatedState(onTick)
    val scope = rememberCoroutineScope()
    val token = remember { Any() }
    val reveal = remember { Animatable(0f) }
    val currentFullSwipe by rememberUpdatedState(fullSwipe)
    val openPx = with(density) { (slotWidth * actions.size + SwipeEndPadding).toPx() }
    var armed by remember { mutableStateOf(false) }

    suspend fun settle(to: Float) {
        if (reduceMotion) reveal.snapTo(to) else reveal.animateTo(to, SwipeSettle)
    }
    fun close() {
        if (OpenSwipeRow.owner === token) OpenSwipeRow.owner = null
        scope.launch { settle(0f) }
    }

    // Another row opening closes this one. Watched from an effect rather than
    // read in composition, so the owner changing does not recompose every
    // swipeable row on the screen.
    LaunchedEffect(token) {
        snapshotFlow { OpenSwipeRow.owner }.collect { owner ->
            if (owner !== token && reveal.targetValue != 0f) settle(0f)
        }
    }
    DisposableEffect(token) {
        onDispose { if (OpenSwipeRow.owner === token) OpenSwipeRow.owner = null }
    }

    // The row's width, read only by the drag. A plain box measured in layout
    // rather than `BoxWithConstraints`, which subcomposed every swipeable row
    // on its own: a list of a hundred words paid a hundred extra compositions
    // to open.
    val width = remember { FloatArray(1) }
    Box(modifier.fillMaxWidth().onSizeChanged { width[0] = it.width.toFloat() }) {
        // Where the finger has put the row, kept apart from the animatable so
        // deltas that arrive before the last snap has landed still add up.
        var dragPos by remember { mutableStateOf(0f) }
        val dragState = rememberDraggableState { delta ->
            val widthPx = width[0]
            val fullAt = if (currentFullSwipe == null) {
                Float.MAX_VALUE
            } else {
                maxOf(widthPx * FullSwipeFraction, openPx * 1.5f)
            }
            val toward = if (rtl) delta else -delta
            val limit = if (currentFullSwipe != null) widthPx else openPx + with(density) { 24.dp.toPx() }
            dragPos = (dragPos + toward).coerceIn(0f, limit)
            val nowArmed = dragPos >= fullAt
            if (nowArmed != armed) {
                armed = nowArmed
                tick()
            }
            scope.launch { reveal.snapTo(dragPos) }
        }
        val open by remember { derivedStateOf { reveal.value > 0.5f } }
        // The buttons are composed only while there is a gap to show them in;
        // a row at rest draws nothing behind it.
        val showing by remember { derivedStateOf { reveal.value > 0f } }

        // The buttons, at the row's end, as wide as the row has moved.
        if (showing || armed) {
            Box(
                Modifier
                    .matchParentSize()
                    .clipToBounds(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SwipeStrip(
                    actions = actions,
                    armedAction = fullSwipe.takeIf { armed },
                    colors = colors,
                    buttonSize = buttonSize,
                    slotWidth = slotWidth,
                    openPx = openPx,
                    reveal = { reveal.value },
                    onAction = { action ->
                        close()
                        action.onClick()
                    },
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(-reveal.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        reveal.stop()
                        dragPos = reveal.value
                        OpenSwipeRow.owner = token
                    },
                    onDragStopped = { velocity ->
                        val toward = if (rtl) velocity else -velocity
                        val fire = currentFullSwipe?.takeIf { armed }
                        // An armed strip stays in the action's colour while
                        // the row slides the rest of the way out.
                        if (fire == null) armed = false
                        when {
                            fire != null -> {
                                settle(width[0])
                                fire.onClick()
                                // A row the action removed is gone by now and
                                // this never runs; one it kept (a dialog
                                // asked first, an undo put it back in place)
                                // comes home rather than staying slid away.
                                delay(600)
                                armed = false
                                close()
                            }
                            toward > SwipeFlingVelocity -> settle(openPx)
                            toward < -SwipeFlingVelocity -> close()
                            dragPos > openPx / 2 -> settle(openPx)
                            else -> close()
                        }
                    },
                ),
        ) {
            content()
            // While the row stands open a tap on it closes it, rather than
            // opening whatever the row opens with the buttons still showing.
            if (open) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { close() } },
                )
            }
        }
    }
}

/**
 * The buttons behind a row: as wide as the row has moved, with the last action
 * against the end edge and the rest growing into view as the gap opens.
 *
 * [reveal] is read only while laying out and drawing, so a drag moves the
 * strip without recomposing it.
 */
@Composable
private fun SwipeStrip(
    actions: List<SwipeAction>,
    armedAction: SwipeAction?,
    colors: SwipeActionColors,
    buttonSize: Dp,
    slotWidth: Dp,
    openPx: Float,
    reveal: () -> Float,
    onAction: (SwipeAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                val w = reveal().roundToInt().coerceIn(0, constraints.maxWidth)
                val placeable = measurable.measure(
                    Constraints.fixed(w, constraints.maxHeight.coerceAtLeast(constraints.minHeight)),
                )
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
            .clipToBounds(),
    ) {
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .wrapContentWidth(align = Alignment.End, unbounded = true)
                .padding(end = SwipeEndPadding)
                .graphicsLayer {
                    val shown = (reveal() / openPx).coerceIn(0f, 1f)
                    alpha = shown
                    scaleX = 0.6f + 0.4f * shown
                    scaleY = scaleX
                },
        ) {
            for (action in actions) {
                Box(Modifier.width(slotWidth), contentAlignment = Alignment.Center) {
                    val (container, onContainer) = colors.of(action.tone)
                    Surface(
                        onClick = { onAction(action) },
                        shape = CircleShape,
                        color = container,
                        contentColor = onContainer,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                action.icon,
                                contentDescription = action.label,
                                modifier = Modifier.size(buttonSize * 0.5f),
                            )
                        }
                    }
                }
            }
        }
        // A long swipe past the point of no return: the whole gap becomes the
        // action, with its icon riding next to the row so the finger can see
        // what letting go will do.
        if (armedAction != null) {
            val (container, onContainer) = colors.of(armedAction.tone)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(container)
                    .padding(start = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(armedAction.icon, contentDescription = null, tint = onContainer)
            }
        }
    }
}
