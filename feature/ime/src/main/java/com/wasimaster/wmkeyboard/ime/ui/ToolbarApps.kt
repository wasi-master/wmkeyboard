package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What an app's toolbar button shows (#1159): its name and its icon, when the icon could be read. */
class ToolbarAppFace(val label: String, val icon: ImageBitmap?)

/**
 * The apps on the toolbar (#1159), handed down from the service with the
 * other toolbar callbacks. [face] reads an app by its launcher key through
 * the app launcher's list and icon cache, null for an app no longer installed;
 * [onOpen] opens it the way the launcher panel's Open apps setting says;
 * [onMove] puts it at a place among the pinned tools (#1258), as a count of
 * the drawn tools before it.
 */
@Immutable
class ToolbarAppCallbacks(
    val face: suspend (String) -> ToolbarAppFace? = { null },
    val onOpen: (String) -> Unit = {},
    val onMove: (key: String, slot: Int) -> Unit = { _, _ -> },
)

/** The apps the toolbar shows after its tools and folders, in the user's order (#1159). */
internal fun toolbarApps(state: KeyboardUiState): List<String> = state.settings.launcher.toolbarApps

/**
 * Where each app goes among [tools] (#1258): drawn tool index → the apps
 * that sit just before that tool, in the user's order. An index of
 * `tools.size` is after the last tool. Apps never moved are not in it: they
 * keep their place after the folders.
 */
internal fun toolbarAppsBySlot(state: KeyboardUiState, toolCount: Int): Map<Int, List<String>> {
    val slots = state.settings.launcher.toolbarAppSlots
    if (slots.isEmpty()) return emptyMap()
    return toolbarApps(state)
        .filter { it in slots }
        .groupBy { (slots[it] ?: 0).coerceIn(0, toolCount) }
}

/** The apps never moved along the bar (#1258), which keep their place after the folders. */
internal fun toolbarAppsTrailing(state: KeyboardUiState): List<String> {
    val slots = state.settings.launcher.toolbarAppSlots
    return toolbarApps(state).filter { it !in slots }
}

/**
 * How many of [tools] come before [at] along the bar, by their laid-out
 * centres (#1258): the slot an app let go at [at] takes. Down a column the
 * centres are heights; along a row that reads right to left, a tool is before
 * the finger when its centre is to the right.
 */
internal fun appSlotAt(
    at: Offset,
    tools: List<ToolbarTool>,
    centres: Map<ToolbarTool, Float>,
    vertical: Boolean,
    rtl: Boolean,
): Int = when {
    vertical -> tools.count { (centres[it] ?: Float.MAX_VALUE) < at.y }
    rtl -> tools.count { (centres[it] ?: -Float.MAX_VALUE) > at.x }
    else -> tools.count { (centres[it] ?: Float.MAX_VALUE) < at.x }
}

/**
 * One app on the toolbar (#1159), dressed as a tool button (#1258): the
 * theme's tool shape and fill, at the Tool button width and height, with the
 * app's icon where a tool's glyph goes and its name under it when the bar
 * shows names. A tap opens the app. A hold that then travels picks the app up
 * and lets it go among the tools: [slotAt] says where, and
 * [ToolbarAppCallbacks.onMove] stores it. An app that is gone draws nothing;
 * it comes off the bar from the launcher's hold menu or the tool's settings.
 */
@Composable
internal fun PinnedAppButton(
    key: String,
    state: KeyboardUiState,
    callbacks: ToolbarAppCallbacks,
    modifier: Modifier = Modifier,
    /** The slot under a root position, or null where the app cannot be moved. */
    slotAt: ((Offset) -> Int)? = null,
) {
    // The icon pack and this app's picked icon (#1275) are keys too: changing
    // either reads the face again.
    val iconPack = state.settings.launcher.iconPack
    val iconPick = state.settings.launcher.appIcons[key.substringBefore('@')]
    val face by produceState<ToolbarAppFace?>(null, key, callbacks, iconPack, iconPick) {
        value = callbacks.face(key)
    }
    val app = face ?: return
    val kb = LocalKbTheme.current
    val description = stringResource(R.string.ime_toolbar_app_desc, app.label)
    val labels = state.settings.toolbarLabels
    val shape = kb.toolShape()
    val background = if (kb.toolRadiusDp > 0) kb.toolCircle else Color.Transparent
    val outline = if (kb.toolBorder != null && kb.toolBorderWidthDp > 0f) {
        Modifier.border(kb.toolBorderWidthDp.dp, kb.toolBorder, shape)
    } else {
        Modifier
    }
    val iconSize = if (labels) {
        (kb.toolIconSizeDp - 2).coerceAtMost(26) + TOOLBAR_APP_ICON_EXTRA_DP
    } else {
        kb.toolIconSizeDp + TOOLBAR_APP_ICON_EXTRA_DP
    }
    val feedback = LocalKeyPressFeedback.current
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState<() -> Unit>({ callbacks.onOpen(key) })
    val slotOf by rememberUpdatedState(slotAt)
    val holdDelayMs = state.settings.toolbarBehavior.holdDelayMs
    val dragEnabled = state.settings.toolbarBehavior.dragToRearrange
    var origin by remember { mutableStateOf(Offset.Zero) }
    // How far the app has been carried, drawn as an offset while it is held.
    var carried by remember { mutableStateOf<Offset?>(null) }
    val gesture = Modifier
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(key, holdDelayMs, dragEnabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var held = false
                var moved = false
                var released = false
                var scrolled = false
                var at = down.position
                val timer = scope.launch {
                    delay(if (holdDelayMs > 0) holdDelayMs.toLong() else viewConfiguration.longPressTimeoutMillis)
                    feedback()
                    held = true
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            if (held) change.consume()
                            break
                        }
                        at = change.position
                        val travel = (at - down.position).getDistance()
                        if (!held) {
                            // Drift before the hold is a scroll of the bar: hand it back.
                            if (travel > viewConfiguration.touchSlop) {
                                scrolled = true
                                break
                            }
                        } else if (dragEnabled && slotOf != null) {
                            change.consume()
                            if (travel > viewConfiguration.touchSlop) moved = true
                            if (moved) carried = at - down.position
                        }
                    }
                } finally {
                    timer.cancel()
                    carried = null
                    when {
                        !held -> if (released && !scrolled) open()
                        moved -> slotOf?.invoke(origin + at)?.let { slot -> callbacks.onMove(key, slot) }
                    }
                }
            }
        }
        .semantics {
            contentDescription = description
            onClick { open(); true }
        }
    // The carry is drawn by a layer inside the gesture, never around it: the
    // finger's positions stay in the button's resting place, so the offset
    // they measure is the finger's own travel rather than chasing itself.
    val lift = Modifier.graphicsLayer {
        val by = carried
        translationX = by?.x ?: 0f
        translationY = by?.y ?: 0f
        val grown = if (by != null) 1.08f else 1f
        scaleX = grown
        scaleY = grown
    }
    val icon: @Composable () -> Unit = {
        val bitmap = app.icon
        val iconShape = launcherIconShape(state.settings.launcher.iconShape)
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize.dp)
                    .then(if (iconShape != null) Modifier.clip(iconShape) else Modifier),
            )
        } else {
            // An icon that would not decode: the name's first letter keeps the slot recognisable.
            Text(app.label.take(1), color = kb.toolbarIcon, fontSize = 14.sp)
        }
    }
    if (labels) {
        // As a labelled tool is: the button a size down, the name under it.
        Column(
            modifier = modifier.then(gesture).then(lift),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(width = (kb.toolWidthDp - 8).coerceAtLeast(30).dp, height = 30.dp)
                    .clip(shape)
                    .then(if (kb.toolRadiusDp > 0) Modifier.frostGlass(kb, shape, FrostSurface.TOOLS) else Modifier)
                    .background(background, shape)
                    .then(outline),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(
                app.label,
                fontSize = state.settings.toolbarLabelSize.sp,
                lineHeight = (state.settings.toolbarLabelSize + 1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = kb.toolbarIcon,
                modifier = Modifier.widthIn(max = 72.dp).padding(top = 1.dp, start = 1.dp, end = 1.dp),
            )
        }
    } else {
        Box(
            modifier = modifier
                .then(gesture)
                .then(lift)
                .size(width = kb.toolWidthDp.dp, height = kb.toolHeightDp.dp)
                .clip(shape)
                .then(if (kb.toolRadiusDp > 0) Modifier.frostGlass(kb, shape, FrostSurface.TOOLS) else Modifier)
                .background(background, shape)
                .then(outline),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

/** An app icon is a filled picture, a tool icon a glyph with air round it: a little more size evens them out. */
private const val TOOLBAR_APP_ICON_EXTRA_DP = 4
