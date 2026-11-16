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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Where an app on the toolbar is carried while it is held and dragged
 * (#1387). The bar's drag controller is one: it draws the app under the
 * finger over the whole keyboard, so nothing the bar clips (its scroll, its
 * edges) stops it, and the bar draws a ghost in the slot it will take.
 */
@Stable
internal interface AppCarry {
    /** The app in flight, by its launcher key, or null. Snapshot state. */
    val carrying: String?

    /** Picks [key] up at root position [at]. */
    fun startCarry(key: String, face: ToolbarAppFace, at: Offset)

    /** The finger carrying it moved to root position [at]. */
    fun moveCarry(at: Offset)

    /** Lets go: the slot among the drawn tools it lands in, or null where it goes back. */
    fun endCarry(): Int?
}

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
internal fun <T> appSlotAt(
    at: Offset,
    tools: List<T>,
    centres: Map<T, Float>,
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
 * and lets it go among the tools: [carry] draws it under the finger and says
 * where it lands, and [ToolbarAppCallbacks.onMove] stores it. An app that is
 * gone draws nothing;
 * it comes off the bar from the launcher's hold menu or the tool's settings.
 */
@Composable
internal fun PinnedAppButton(
    key: String,
    state: KeyboardUiState,
    callbacks: ToolbarAppCallbacks,
    modifier: Modifier = Modifier,
    /** What carries the app along the bar, or null where it cannot be moved. */
    carry: AppCarry? = null,
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
    val iconSize = toolbarAppIconSize(kb.toolIconSizeDp, labels)
    val feedback = LocalKeyPressFeedback.current
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState<() -> Unit>({ callbacks.onOpen(key) })
    val carrier by rememberUpdatedState(carry)
    val shown by rememberUpdatedState(app)
    val holdDelayMs = state.settings.toolbarBehavior.holdDelayMs
    val dragEnabled = state.settings.toolbarBehavior.dragToRearrange
    // Where the button is, read when the finger moves rather than written on
    // every frame the bar scrolls (#1393): a placed node's coordinates follow
    // the scroll by themselves.
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun origin(): Offset = placed[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
    val gesture = Modifier
        .onPlaced { placed[0] = it }
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
                        } else if (dragEnabled && carrier != null) {
                            change.consume()
                            val root = origin() + at
                            if (!moved && travel > viewConfiguration.touchSlop) {
                                moved = true
                                carrier?.startCarry(key, shown, root)
                            }
                            if (moved) carrier?.moveCarry(root)
                        }
                    }
                } finally {
                    timer.cancel()
                    when {
                        !held -> if (released && !scrolled) open()
                        // A drop off the bar puts it back where it was.
                        moved -> carrier?.endCarry()?.let { slot -> callbacks.onMove(key, slot) }
                    }
                }
            }
        }
        .semantics {
            contentDescription = description
            onClick { open(); true }
        }
    // The carried app is drawn under the finger by the keyboard, over
    // everything; its own button stays where it was, washed out until the bar
    // folds its cell away for the ghost in the slot it is heading for.
    val lift = Modifier.graphicsLayer { alpha = if (carry?.carrying == key) 0.35f else 1f }
    val icon: @Composable () -> Unit = { ToolbarAppIcon(app, state, iconSize) }
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

/** An app's icon as its toolbar button draws it, or its name's first letter when the icon would not decode. */
@Composable
internal fun ToolbarAppIcon(app: ToolbarAppFace, state: KeyboardUiState, sizeDp: Int, alpha: Float = 1f) {
    val bitmap = app.icon
    val iconShape = launcherIconShape(state.settings.launcher.iconShape)
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            alpha = alpha,
            modifier = Modifier
                .size(sizeDp.dp)
                .then(if (iconShape != null) Modifier.clip(iconShape) else Modifier),
        )
    } else {
        Text(app.label.take(1), color = LocalKbTheme.current.toolbarIcon.copy(alpha = alpha), fontSize = 14.sp)
    }
}

/** The glyph size of an app on a toolbar button: [toolIconSizeDp] plus a little, as [PinnedAppButton] draws it. */
internal fun toolbarAppIconSize(toolIconSizeDp: Int, labels: Boolean): Int = if (labels) {
    (toolIconSizeDp - 2).coerceAtMost(26) + TOOLBAR_APP_ICON_EXTRA_DP
} else {
    toolIconSizeDp + TOOLBAR_APP_ICON_EXTRA_DP
}

/**
 * The slot a carried app will take on the bar (#1387): the ghost a dragged
 * tool leaves where it will land, in the app button's footprint, with the
 * app's icon washed out in it.
 */
@Composable
internal fun ToolbarAppGhost(app: ToolbarAppFace, state: KeyboardUiState, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.toolShape()
    val labels = state.settings.toolbarLabels
    Box(
        modifier = modifier
            .size(
                width = (if (labels) (kb.toolWidthDp - 8).coerceAtLeast(30) else kb.toolWidthDp).dp,
                height = (if (labels) 30 else kb.toolHeightDp).dp,
            )
            .background(kb.toolCircleActive.copy(alpha = 0.22f), shape)
            .border(1.dp, kb.toolbarIcon.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) { ToolbarAppIcon(app, state, toolbarAppIconSize(kb.toolIconSizeDp, labels), alpha = 0.45f) }
}

/** An app icon is a filled picture, a tool icon a glyph with air round it: a little more size evens them out. */
private const val TOOLBAR_APP_ICON_EXTRA_DP = 4
