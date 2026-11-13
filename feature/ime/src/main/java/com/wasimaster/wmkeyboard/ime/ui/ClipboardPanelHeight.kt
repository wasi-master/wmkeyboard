package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.settings.PanelOverKeysExtraHeightRange
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The clipboard panel's height (#414): a bar on top of the panel that drags
 * it taller than the keyboard, so there is less to scroll, and back down. What
 * the drag lands on is [ClipboardSettings.panelExtraHeightDp], the same value
 * the settings screen's slider sets.
 *
 * The panel grows the IME window, and a window that changes height on every
 * frame of a drag re-lays out the app under it each time and moves the bar
 * under the finger — the resize tool's jitter ([resizeHeadroom]). So for the
 * length of a drag the frame holds the window at the tallest the panel can
 * get, through the bar rows' own [RowRevealHeadroom]: [hold] is short of that
 * by exactly what the panel is short of it, so the window changes height once
 * when the drag starts and once when it ends, and the panel grows and shrinks
 * inside a frame that holds still.
 *
 * The app does not move with the window. What the hold covered when the drag
 * began is marked as window ([RowReveal.windowPx]), so the app stays laid out
 * down to the panel's starting top for the whole drag, the panel growing over
 * it, and moves once, to the height the drag lands on, when it ends. Counted
 * as keyboard, as it was, the app jumped up by the whole hold the moment a
 * drag started (issue #643).
 *
 * The composition half is [liveDp] and [pendingDp]; the rest is scratch the
 * gesture and the panel's measure write, and nothing composes from it (the
 * floating frame's `FloatingGesture` trick).
 */
@Stable
internal class ClipPanelResize(private val headroom: RowRevealHeadroom?) {
    /** The height being dragged to, in dp over the keyboard's; null between drags. */
    var liveDp by mutableStateOf<Float?>(null)

    /**
     * What the last drag wrote, shown until the setting comes back with it, so
     * letting go never flashes the panel at its old height for the DataStore's
     * round trip.
     */
    var pendingDp by mutableStateOf<Int?>(null)

    /** The frame's hold while a drag is on, in the frame's headroom. */
    val hold = RowReveal()
    private var held = false
    private var startDp = 0f
    private var travelDp = 0f

    /** The panel's height as last measured, and at its tallest, in px. */
    var panelPx = 0
    var maxPanelPx = 0

    fun shownDp(setting: Int): Float = liveDp ?: pendingDp?.toFloat() ?: setting.toFloat()

    fun begin(fromDp: Float) {
        startDp = fromDp
        travelDp = 0f
        liveDp = fromDp
        if (headroom != null && !held) {
            hold.shortfallPx = (maxPanelPx - panelPx).coerceAtLeast(0)
            hold.windowPx = hold.shortfallPx
            headroom.track(hold)
            held = true
        }
    }

    /** [dyDp] as the finger moved it: up, negative, makes the panel taller. */
    fun drag(dyDp: Float, minDp: Float, maxDp: Float) {
        travelDp += dyDp
        liveDp = (startDp - travelDp).coerceIn(minDp, maxDp)
    }

    fun end(onCommit: (Int) -> Unit) {
        val dp = liveDp ?: return release()
        val landed = dp.roundToInt()
        pendingDp = landed
        liveDp = null
        onCommit(landed)
        release()
    }

    /** Lets go of the window: it settles at whatever the panel is now, in one resize. */
    fun release() {
        if (!held) return
        headroom?.untrack(hold)
        hold.shortfallPx = 0
        hold.windowPx = 0
        held = false
    }

    /** The panel's measure, reporting its height and, mid-drag, what the hold has to cover. */
    fun measured(heightPx: Int) {
        panelPx = heightPx
        if (held) hold.shortfallPx = (maxPanelPx - heightPx).coerceAtLeast(0)
    }
}

@Composable
internal fun rememberClipPanelResize(setting: Int): ClipPanelResize {
    val headroom = LocalRowRevealHeadroom.current
    val resize = remember(headroom) { ClipPanelResize(headroom) }
    // The setting came back from the drag's write, or was changed on the
    // settings screen: either way it is the truth again.
    LaunchedEffect(resize, setting) { resize.pendingDp = null }
    // A panel closed mid-drag must not leave the window held open.
    DisposableEffect(resize) { onDispose { resize.release() } }
    return resize
}

/**
 * The panel's own measure, for [ClipPanelResize.measured]. Remember it: a
 * `layout {}` modifier built in composition is a new modifier each time, and
 * Compose re-measures the node for it.
 */
internal fun Modifier.clipPanelHeightProbe(resize: ClipPanelResize): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    resize.measured(placeable.height)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The bar itself: a short pill across the top of the panel, the whole band
 * its hit target. [extra] is the height over the keyboard's the panel has now,
 * [maxExtra] the most the screen allows and [minExtra] the least, 0 for every
 * panel that cannot go below its opening height; a screen reader gets Taller
 * and Shorter, a row's worth at a time, in place of the drag. The GIF and
 * sticker panels wear the same bar for their own height, [description] naming it.
 */
@Composable
internal fun ClipPanelHeightBar(
    resize: ClipPanelResize,
    extra: Dp,
    maxExtra: Dp,
    onCommit: (Int) -> Unit,
    description: String = stringResource(R.string.ime_clipboard_height_desc),
    minExtra: Dp = 0.dp,
) {
    val kb = LocalKbTheme.current
    val current by rememberUpdatedState(extra.value)
    val max by rememberUpdatedState(maxExtra.value)
    val min by rememberUpdatedState(minExtra.value)
    val commit by rememberUpdatedState(onCommit)
    val taller = stringResource(R.string.ime_clipboard_height_taller)
    val shorter = stringResource(R.string.ime_clipboard_height_shorter)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ClipPanelBarHeight)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(taller) {
                        commit((current + ClipPanelHeightStepDp).coerceAtMost(max).roundToInt()); true
                    },
                    CustomAccessibilityAction(shorter) {
                        commit((current - ClipPanelHeightStepDp).coerceAtLeast(min).roundToInt()); true
                    },
                )
            }
            .pointerInput(resize) {
                detectVerticalDragGestures(
                    onDragStart = { resize.begin(current.coerceIn(min, max)) },
                    onDragEnd = { resize.end(commit) },
                    onDragCancel = { resize.end(commit) },
                ) { change, dy ->
                    change.consume()
                    resize.drag(dy.toDp().value, min, max)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(kb.secondaryText.copy(alpha = 0.5f)),
        )
    }
}

/** The bar's band, taken out of the panel's height rather than added to it. */
internal val ClipPanelBarHeight = 16.dp

/** How far a screen reader's Taller or Shorter moves the panel. */
private const val ClipPanelHeightStepDp = 48f

/**
 * Where the bar on a panel over the keys commits what it lands on (#615): how
 * many dp taller than its compact height such a panel opens. A local rather
 * than a parameter, because [FullBleedTool] draws the bar and none of its
 * dozen callers should have to carry the callback to it.
 */
internal val LocalPanelOverKeysHeight = staticCompositionLocalOf<(Int) -> Unit> { {} }

/**
 * A panel stacked over the live keys, sized: its [height] with the bar, the
 * [bar] itself (null where the screen leaves no room to grow), and the [probe]
 * its outermost node wears so a drag can hold the window still.
 */
@Immutable
internal class PanelOverKeysSize(
    val height: Dp,
    val bar: (@Composable () -> Unit)?,
    val probe: Modifier,
)

/**
 * The height of a panel over the live keys (#615): the [wanted] compact height
 * it has always had, plus the shared setting the bar on top drags, fitted to
 * the screen with the keys underneath counted, and never below [floor].
 *
 * Before this a panel over the keys had a fixed height, and with a strip or a
 * tab row of its own on top of the header (the clipboard's) the list under
 * them was left a single line. The bar is the same one the clipboard and media
 * panels grow the keyboard with; its band is added to the panel, not taken
 * out of it, since a panel collapsed to its header has nothing to give it.
 */
@Composable
internal fun panelOverKeysSize(state: KeyboardUiState, wanted: Dp, floor: Dp): PanelOverKeysSize {
    val setting = state.settings.layoutBehavior.panelOverKeysExtraHeightDp
    val resize = rememberClipPanelResize(setting)
    val base = toolPanelHeight(state, wanted = wanted + ClipPanelBarHeight, floor = floor + ClipPanelBarHeight)
    val maxPanel = toolPanelHeight(
        state,
        wanted = wanted + ClipPanelBarHeight + PanelOverKeysExtraHeightRange.last.dp,
        floor = base,
    )
    val maxExtra = (maxPanel - base).coerceAtLeast(0.dp)
    val probe = remember(resize) { Modifier.clipPanelHeightProbe(resize) }
    if (maxExtra < 1.dp) return PanelOverKeysSize(base - ClipPanelBarHeight, null, probe)
    val extra = resize.shownDp(setting).dp.coerceIn(0.dp, maxExtra)
    val maxPanelPx = with(LocalDensity.current) { maxPanel.roundToPx() }
    SideEffect { resize.maxPanelPx = maxPanelPx }
    val onCommit = LocalPanelOverKeysHeight.current
    val description = stringResource(R.string.ime_panel_over_keys_height_desc)
    return PanelOverKeysSize(
        height = base + extra,
        bar = { ClipPanelHeightBar(resize, extra, maxExtra, onCommit, description) },
        probe = probe,
    )
}

/**
 * Where the bar on a full-bleed tool commits what it lands on: how many dp
 * taller than its own opening height such a tool opens. A local for the
 * reason [LocalPanelOverKeysHeight] is one.
 */
internal val LocalFullBleedHeight = staticCompositionLocalOf<(Int) -> Unit> { {} }

/**
 * The height of a full-bleed tool — AI, AI reply, translate and the rest —
 * with the bar on top that drags it taller or shorter: the [wanted] height it
 * has always opened at, plus the shared
 * [LayoutBehaviorSettings.fullBleedExtraHeightDp], fitted to the screen.
 *
 * [floor] holds for the opening height only. Dragged shorter, a tool goes
 * below it (and below the keyboard's own height, so opening one then shrinks
 * the window) down to [FullBleedMinHeight]: the header, the bar and a strip of
 * the tool. Before, the bar only ever added height, and a drag down on a tool
 * nobody had made taller did nothing at all.
 *
 * The bar's band comes out of the panel rather than being added to it, so a
 * tool that was never dragged opens exactly as tall as it always did, and
 * the window does not grow by a band nobody asked for.
 */
@Composable
internal fun fullBleedSize(state: KeyboardUiState, wanted: Dp, floor: Dp): PanelOverKeysSize {
    val setting = state.settings.layoutBehavior.fullBleedExtraHeightDp
    val resize = rememberClipPanelResize(setting)
    val base = toolPanelHeight(state, wanted = wanted, floor = floor)
    val maxPanel = toolPanelHeight(
        state,
        wanted = wanted + PanelOverKeysExtraHeightRange.last.dp,
        floor = base,
    )
    val maxExtra = (maxPanel - base).coerceAtLeast(0.dp)
    val minExtra = (minOf(base, FullBleedMinHeight) - base).coerceAtMost(0.dp)
    val probe = remember(resize) { Modifier.clipPanelHeightProbe(resize) }
    if (maxExtra < 1.dp && minExtra > (-1).dp) return PanelOverKeysSize(base, null, probe)
    val extra = resize.shownDp(setting).dp.coerceIn(minExtra, maxExtra)
    val maxPanelPx = with(LocalDensity.current) { maxPanel.roundToPx() }
    SideEffect { resize.maxPanelPx = maxPanelPx }
    val onCommit = LocalFullBleedHeight.current
    val description = stringResource(R.string.ime_full_bleed_height_desc)
    return PanelOverKeysSize(
        height = base + extra,
        bar = { ClipPanelHeightBar(resize, extra, maxExtra, onCommit, description, minExtra) },
        probe = probe,
    )
}

/**
 * The shortest a full-bleed tool can be dragged: its header, the bar over it
 * and room for a line or two of the tool under them.
 */
internal val FullBleedMinHeight = 140.dp
