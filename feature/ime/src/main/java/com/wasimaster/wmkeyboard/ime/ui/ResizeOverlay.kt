package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.SizingAction
import kotlin.math.roundToInt

/**
 * The stored-space values the inline resize tool drives. "Stored space"
 * means the numbers as the settings hold them, before the screen variant's
 * `keyboardScale` is folded in — what a drag produces here is exactly what
 * Done hands the repository, with no lossy round trip through rendered sizes.
 *
 * [sidePadLeft] / [sidePadRight] are the two edge pads of issue #82, as
 * fractions of the window width (see `LayoutBehaviorSettings.sidePadLeftScale`);
 * no scale applies to them, so stored and rendered are the same number.
 */
@Immutable
internal data class ResizeValues(
    val keyHeightDp: Int,
    val numberRowHeightDp: Int,
    val bottomPaddingDp: Int,
    val sidePadLeft: Float = 0f,
    val sidePadRight: Float = 0f,
)

/**
 * One open resize session, built by [KeyboardScreen] where the screen variant
 * and the raw settings are known. A bundle rather than parameters for the
 * usual reason: everything reaching the keyboard body has to squeeze past the
 * 64K method ceiling of KeyboardScreen's caller.
 *
 * [entry] is what the values were when the tool opened; Reset returns to it by
 * clearing [preview] back to null. [onCommit] gets the final values on Done
 * (null when nothing was dragged).
 *
 * [headroomDp] is how much taller than [entry] the keyboard can possibly get
 * in this session (every height at its ceiling, padding at its ceiling). The
 * docked frame reserves that much extra on entry — see [resizeHeadroom] — and
 * [reservedPx] latches the resulting frame height on the first layout, so the
 * keyboard grows and shrinks inside a frame that never moves.
 */
@Immutable
internal class ResizeSession(
    val entry: ResizeValues,
    val keyboardScale: Float,
    val maxBottomPaddingDp: Int,
    val headroomDp: Int,
    val preview: MutableState<ResizeValues?>,
    val onCommit: (ResizeValues?) -> Unit,
) {
    val reservedPx: MutableIntState = mutableIntStateOf(0)
}

/**
 * Heights after a top-handle drag: [frac] is "how tall the scalable region is
 * now" over "how tall it was at drag start", applied to the drag-start values
 * so both heights keep their ratio and re-derive from one clean baseline every
 * frame (accumulating per-frame deltas drifts).
 */
internal fun resizeScaledHeights(start: ResizeValues, frac: Float): ResizeValues = start.copy(
    keyHeightDp = (start.keyHeightDp * frac).roundToInt()
        .coerceIn(SettingsRepository.KEY_HEIGHT_MIN_DP, SettingsRepository.KEY_HEIGHT_MAX_DP),
    numberRowHeightDp = (start.numberRowHeightDp * frac).roundToInt()
        .coerceIn(SettingsRepository.KEY_HEIGHT_MIN_DP, SettingsRepository.KEY_HEIGHT_MAX_DP),
)

/**
 * Padding after a move-button drag of [dyDp] (screen coordinates: positive =
 * finger moved down = keyboard sinks). The whole keyboard slides; its height
 * is untouched.
 */
internal fun resizePaddedBy(start: ResizeValues, dyDp: Int, maxPad: Int): ResizeValues =
    start.copy(bottomPaddingDp = (start.bottomPaddingDp - dyDp).coerceIn(0, maxPad))

/**
 * Values after a bottom-handle drag of [dyDp] (positive = down): the bottom
 * edge follows the finger and the top edge stays where it is, which is what a
 * handle on the bottom edge is expected to do. So the keys grow by what the
 * padding gives up, and vice versa.
 *
 * The travel is capped first by the padding bounds (the edge cannot sink below
 * the screen's floor or rise above the padding ceiling), then the heights are
 * scaled from [baseGridDp] — the rendered key grid at drag start — and clamp on
 * their own. The padding is finally set from the grid height the rounded,
 * clamped heights *actually* produce ([gridDp]) rather than from the raw
 * travel, so the top edge holds still to the dp even when a height pins.
 */
internal fun resizeBottomEdgeBy(
    start: ResizeValues,
    dyDp: Float,
    baseGridDp: Float,
    maxPad: Int,
    gridDp: (ResizeValues) -> Float,
): ResizeValues {
    if (baseGridDp <= 0f) return start
    val travel = dyDp.coerceIn(
        (start.bottomPaddingDp - maxPad).toFloat(),
        start.bottomPaddingDp.toFloat(),
    )
    val scaled = resizeScaledHeights(start, (baseGridDp + travel) / baseGridDp)
    val grown = gridDp(scaled) - baseGridDp
    return scaled.copy(
        bottomPaddingDp = (start.bottomPaddingDp - grown).roundToInt().coerceIn(0, maxPad),
    )
}

/**
 * Left pad after a left-handle drag of [dxFrac] (finger travel as a fraction
 * of the window width, positive = right = more pad). Clamped to the same
 * range the settings slider allows.
 */
internal fun resizeLeftPaddedBy(start: ResizeValues, dxFrac: Float): ResizeValues =
    start.copy(sidePadLeft = (start.sidePadLeft + dxFrac).coerceIn(SidePadScaleRange))

/** Right pad after a right-handle drag: finger moving right *shrinks* it. */
internal fun resizeRightPaddedBy(start: ResizeValues, dxFrac: Float): ResizeValues =
    start.copy(sidePadRight = (start.sidePadRight - dxFrac).coerceIn(SidePadScaleRange))

/** The top handle is pinned: the key height can go no further either way. */
internal fun resizeHeightAtLimit(values: ResizeValues): Boolean =
    values.keyHeightDp <= SettingsRepository.KEY_HEIGHT_MIN_DP ||
        values.keyHeightDp >= SettingsRepository.KEY_HEIGHT_MAX_DP

/** The move button is pinned against a padding bound. */
internal fun resizePadAtLimit(values: ResizeValues, maxPad: Int): Boolean =
    values.bottomPaddingDp <= 0 || values.bottomPaddingDp >= maxPad

/**
 * The bottom handle is pinned: it moves the padding and the heights together,
 * so either one at its bound stops it.
 */
internal fun resizeBottomEdgeAtLimit(values: ResizeValues, maxPad: Int): Boolean =
    resizeHeightAtLimit(values) || resizePadAtLimit(values, maxPad)

/** A side pad sits at one end of its slider range. */
internal fun resizeSidePadAtLimit(pad: Float): Boolean =
    pad <= SidePadScaleRange.start || pad >= SidePadScaleRange.endInclusive

/**
 * What Done sends the service. Fields that match [entry] go as null — "was
 * not changed, write nothing" — which is what keeps a padding-only session
 * from freezing the key height and losing the tablet defaults (see
 * `keyHeightUntouched` in the settings repository).
 */
internal fun resizeCommitAction(
    variant: ScreenVariant,
    entry: ResizeValues,
    result: ResizeValues?,
): SizingAction = if (result == null) {
    SizingAction.ResizeCancel
} else {
    SizingAction.ResizeCommit(
        variant = variant,
        keyHeightDp = result.keyHeightDp.takeIf { it != entry.keyHeightDp },
        numberRowHeightDp = result.numberRowHeightDp.takeIf { it != entry.numberRowHeightDp },
        bottomPaddingDp = result.bottomPaddingDp.takeIf { it != entry.bottomPaddingDp },
        sidePadLeftScale = result.sidePadLeft.takeIf { it != entry.sidePadLeft },
        sidePadRightScale = result.sidePadRight.takeIf { it != entry.sidePadRight },
    )
}

/**
 * Per-gesture scratch, deliberately not snapshot state (the same trick as the
 * floating frame's FloatingGesture): the pointer handlers write it every
 * frame and nothing composes from it, so touching it invalidates nothing.
 * [baseScalablePx] / [baseGridDp] / [baseWidthPx] are measured once at drag
 * start — re-reading them per frame feeds the drag's own effect back into the
 * mapping and oscillates.
 */
private class ResizeGesture {
    var start: ResizeValues? = null
    var baseScalablePx = 0f
    var baseGridDp = 0f
    var baseWidthPx = 0
    var acc = Offset.Zero
}

/**
 * Sizes that the gesture handlers read but nothing composes from: the outline
 * Row's width (what a side drag's travel is a fraction of). Plain fields, for
 * the same reason as [ResizeGesture].
 */
private class ResizeFrameMetrics {
    var widthPx = 0
}

/**
 * Reserves the resize mode's headroom on the docked frame: a minimum height
 * latched on the session's first layout ([ResizeSession.reservedPx]), so the
 * frame — and with it the IME window's content inset and the host app's
 * layout — is settled once on entry and then holds still while the keyboard
 * grows and shrinks inside it.
 *
 * This is what makes a drag smooth. Without it every previewed dp re-laid
 * out the host app and moved the input view under the finger, and a drag
 * that moves its own origin every frame is a drag that fights itself.
 *
 * A measure-scope read of the latch: a change re-measures this node and
 * recomposes nothing.
 */
internal fun Modifier.resizeHeadroom(session: ResizeSession): Modifier = layout { measurable, constraints ->
    val reserved = session.reservedPx.intValue
    val minHeight = maxOf(constraints.minHeight, reserved).coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = minHeight))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Latches the frame's reserved height from the keyboard's own height on the
 * session's first layout: the keyboard as it stood on entry plus every dp it
 * could still grow, capped at [maxPx] so a tall keyboard on a short screen
 * leaves the host app some room.
 */
internal fun ResizeSession.latchReserved(keyboardHeightPx: Int, headroomPx: Int, maxPx: Int) {
    if (reservedPx.intValue != 0) return
    reservedPx.intValue = (keyboardHeightPx + headroomPx)
        .coerceAtMost(maxPx)
        .coerceAtLeast(keyboardHeightPx)
}

/**
 * The dimmed, touch-eating band that fills the reserved headroom above the
 * keyboard. Drawn *under* the keyboard box (which is opaque), so it only ever
 * shows in the space the keyboard has not grown into yet.
 */
@Composable
internal fun BoxScope.ResizeHeadroomScrim() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(ResizeScrimColor)
            .pointerInputSwallowAll(),
    )
}

private val ResizeScrimColor = Color.Black.copy(alpha = 0.45f)

/**
 * The inline resize mode, drawn over the keyboard box: the keyboard dims
 * behind a scrim that eats every touch, an accent outline hugs the keyboard's
 * rectangle, and five drags drive the geometry — the top handle scales the
 * key heights, the bottom handle moves the bottom edge with the top edge
 * pinned, the left and right handles set the edge pads (issue #82), and the
 * centre move button slides the whole keyboard up and down. Reset returns to
 * the values the tool opened with; Done persists and leaves. Handles turn the
 * mistake red when pinned at a limit.
 *
 * Everything previews through [ResizeSession.preview] and nothing touches the
 * DataStore until Done — see the session doc for why that ordering is the
 * whole design.
 */
@Composable
internal fun BoxScope.ResizeOverlay(
    session: ResizeSession,
    state: KeyboardUiState,
) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val current = session.preview.value ?: session.entry
    val arrangement = dockedWidthArrangement(state.settings)
    // What the height drags measure against: the key grid as it stands right
    // now. Captured into the gesture at drag start (not per frame) via
    // rememberUpdatedState so the pointerInput lambdas never go stale across
    // recompositions.
    val scalableDp = keyRowsHeight(state)
    val latestScalableDp = rememberUpdatedState(scalableDp)
    val latestValues = rememberUpdatedState(current)
    val latestState = rememberUpdatedState(state)
    val metrics = remember { ResizeFrameMetrics() }

    // The rendered key-grid height a set of stored values would produce: the
    // bottom handle derives its padding from this so the top edge holds
    // still to the dp (see resizeBottomEdgeBy).
    val gridDpFor: (ResizeValues) -> Float = { values ->
        val shown = latestState.value
        keyRowsHeight(
            shown.copy(
                settings = shown.settings.copy(
                    keyHeightDp = (values.keyHeightDp * session.keyboardScale).roundToInt(),
                    numberRowHeightDp =
                        (values.numberRowHeightDp * session.keyboardScale).roundToInt(),
                ),
            ),
        ).value
    }

    val maxPad = session.maxBottomPaddingDp
    val heightAtLimit = resizeHeightAtLimit(current)
    val padAtLimit = resizePadAtLimit(current, maxPad)
    val bottomAtLimit = resizeBottomEdgeAtLimit(current, maxPad)
    val leftAtLimit = resizeSidePadAtLimit(current.sidePadLeft)
    val rightAtLimit = resizeSidePadAtLimit(current.sidePadRight)
    val limitColor = errorLimitColor(kb)
    fun handleColor(atLimit: Boolean) = if (atLimit) limitColor else kb.accent

    // Scrim first: it spans toolbar, keys and padding alike, and it consumes
    // every gesture that no handle claimed — the resize mode must never type.
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(ResizeScrimColor)
            .pointerInputSwallowAll(),
    )
    // The keyboard-hugging frame: after the same insets the keyboard Row
    // takes, what is left of the keyboard box is exactly the rectangle the
    // keys are laid out in — so the outline tracks every drag for free.
    Row(
        modifier = Modifier
            .matchParentSize()
            .onSizeChanged { metrics.widthPx = it.width }
            .navigationBarsPadding()
            .padding(bottom = state.settings.bottomPaddingDp.dp),
    ) {
        if (arrangement.leftSlack > 0.001f) {
            Spacer(modifier = Modifier.weight(arrangement.leftSlack))
        }
        Box(
            modifier = Modifier
                .weight(arrangement.widthFraction)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.dp, kb.accent, RoundedCornerShape(16.dp)),
            )
            // Top: scale the heights, bottom edge pinned by the padding.
            ResizeHandleBar(
                color = handleColor(heightAtLimit),
                description = stringResource(R.string.ime_resize_height_handle_desc),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .resizeDrag(
                        session = session,
                        latestValues = latestValues,
                        onStart = { g ->
                            g.baseScalablePx = with(density) { latestScalableDp.value.toPx() }
                        },
                    ) { g, start ->
                        val base = g.baseScalablePx
                        if (base <= 0f) return@resizeDrag null
                        val frac = (base - g.acc.y) / base
                        resizeScaledHeights(start, frac) to { v -> resizeHeightAtLimit(v) }
                    },
            )
            // Bottom: move the bottom edge, top edge pinned.
            ResizeHandleBar(
                color = handleColor(bottomAtLimit),
                description = stringResource(R.string.ime_resize_bottom_handle_desc),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .resizeDrag(
                        session = session,
                        latestValues = latestValues,
                        onStart = { g -> g.baseGridDp = latestScalableDp.value.value },
                    ) { g, start ->
                        val dyDp = with(density) { g.acc.y.toDp() }.value
                        val next = resizeBottomEdgeBy(start, dyDp, g.baseGridDp, maxPad, gridDpFor)
                        next to { v -> resizeBottomEdgeAtLimit(v, maxPad) }
                    },
            )
            // Left / right: the edge pads, as a share of the window width.
            ResizeHandleBar(
                color = handleColor(leftAtLimit),
                description = stringResource(R.string.ime_resize_left_handle_desc),
                vertical = true,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .resizeDrag(
                        session = session,
                        latestValues = latestValues,
                        onStart = { g -> g.baseWidthPx = metrics.widthPx },
                    ) { g, start ->
                        if (g.baseWidthPx <= 0) return@resizeDrag null
                        val next = resizeLeftPaddedBy(start, g.acc.x / g.baseWidthPx)
                        next to { v -> resizeSidePadAtLimit(v.sidePadLeft) }
                    },
            )
            ResizeHandleBar(
                color = handleColor(rightAtLimit),
                description = stringResource(R.string.ime_resize_right_handle_desc),
                vertical = true,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .resizeDrag(
                        session = session,
                        latestValues = latestValues,
                        onStart = { g -> g.baseWidthPx = metrics.widthPx },
                    ) { g, start ->
                        if (g.baseWidthPx <= 0) return@resizeDrag null
                        val next = resizeRightPaddedBy(start, g.acc.x / g.baseWidthPx)
                        next to { v -> resizeSidePadAtLimit(v.sidePadRight) }
                    },
            )
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ResizePill(
                    icon = Icons.Outlined.RestartAlt,
                    label = stringResource(R.string.ime_resize_reset),
                    onClick = {
                        haptic()
                        session.preview.value = null
                    },
                )
                // Centre: slide the whole keyboard, height untouched.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(kb.popup)
                        .border(1.dp, handleColor(padAtLimit), CircleShape)
                        .resizeDrag(session = session, latestValues = latestValues) { g, start ->
                            val dyDp = with(density) { g.acc.y.toDp() }.value.roundToInt()
                            resizePaddedBy(start, dyDp, maxPad) to { v -> resizePadAtLimit(v, maxPad) }
                        },
                ) {
                    Icon(
                        Icons.Outlined.OpenWith,
                        contentDescription = stringResource(R.string.ime_resize_move_desc),
                        tint = if (padAtLimit) limitColor else kb.popupText,
                        modifier = Modifier.size(24.dp),
                    )
                }
                ResizePill(
                    icon = Icons.Outlined.Check,
                    label = stringResource(R.string.ime_resize_done),
                    onClick = {
                        haptic()
                        session.onCommit(session.preview.value)
                    },
                )
            }
        }
        if (arrangement.rightSlack > 0.001f) {
            Spacer(modifier = Modifier.weight(arrangement.rightSlack))
        }
    }
}

/**
 * One handle's drag: snapshots the values and whatever [onStart] measures at
 * the first touch, accumulates the finger's travel, and asks [map] for the
 * next values plus the limit predicate that decides the haptic tick. [map]
 * returns null when the drag cannot be mapped yet (a base size of zero).
 *
 * Deltas, not positions: the outline re-lays out under the finger every
 * frame, and Compose already expresses each change relative to the handle's
 * *current* place, so a moving handle costs nothing here.
 */
@Composable
private fun Modifier.resizeDrag(
    session: ResizeSession,
    latestValues: State<ResizeValues>,
    onStart: (ResizeGesture) -> Unit = {},
    map: (ResizeGesture, ResizeValues) -> Pair<ResizeValues, (ResizeValues) -> Boolean>?,
): Modifier {
    val gesture = remember { ResizeGesture() }
    val haptic = LocalHapticFeedback.current
    return pointerInput(session) {
        detectDragGestures(
            onDragStart = {
                gesture.start = latestValues.value
                gesture.acc = Offset.Zero
                onStart(gesture)
            },
            onDragEnd = { gesture.start = null },
            onDragCancel = { gesture.start = null },
        ) { change, dragAmount ->
            change.consume()
            val start = gesture.start ?: return@detectDragGestures
            gesture.acc += dragAmount
            val (next, atLimit) = map(gesture, start) ?: return@detectDragGestures
            publishPreview(session, next, haptic, atLimit)
        }
    }
}

/**
 * Pushes [next] into the live preview if it moved, with one haptic tick the
 * moment a drag first pins against its limit (and none while it stays there).
 */
private inline fun publishPreview(
    session: ResizeSession,
    next: ResizeValues,
    haptic: () -> Unit,
    atLimit: (ResizeValues) -> Boolean,
) {
    val previous = session.preview.value ?: session.entry
    if (next == previous) return
    if (atLimit(next) && !atLimit(previous)) haptic()
    session.preview.value = next
}

/**
 * A drag handle: a short pill drawn on the outline's edge, wrapped in a much
 * larger invisible hit target so a thumb finds it without looking. [vertical]
 * turns it on its side for the left and right edges.
 */
@Composable
private fun ResizeHandleBar(
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val hitLong = 96.dp
    val hitShort = 32.dp
    val barLong = 52.dp
    val barShort = 5.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(if (vertical) hitShort else hitLong)
            .height(if (vertical) hitLong else hitShort)
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .width(if (vertical) barShort else barLong)
                .height(if (vertical) barLong else barShort)
                .clip(RoundedCornerShape(2.5.dp))
                .background(color),
        )
    }
}

/** Reset / Done: the tool's two buttons, in the keyboard's popup styling. */
@Composable
private fun ResizePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(kb.popup)
            .border(1.dp, kb.accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText, modifier = Modifier.size(16.dp))
        Text(
            label,
            color = kb.popupText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Mistake red for a handle pinned at its limit. Same pair as the typing
 * test's error colour: lighter on dark boards so it stays legible.
 */
private fun errorLimitColor(kb: KbTheme): Color =
    if (kb.dark) Color(0xFFEF7070) else Color(0xFFD64545)

/** Consumes every pointer event outright: the scrim types nothing, ever. */
private fun Modifier.pointerInputSwallowAll(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
