package com.wasimaster.wmkeyboard.core.plugins.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A layer over a running plugin's whole panel, for what must cover the
 * widgets instead of pushing them down: a dropdown's options and a plugin's
 * dialog. Drawn by the panel itself, not in a popup window: apps may refuse
 * touches that pass through a keyboard's popup window.
 */
@Stable
class PluginOverlay {
    internal var menu by mutableStateOf<OverlayMenu?>(null)

    /** Where the layer's top-left sits in the root, to place a menu under its field. */
    internal var origin by mutableStateOf(Offset.Zero)

    fun close() {
        menu = null
    }
}

/** Options shown under [anchor] (root coordinates), as wide as it. */
internal class OverlayMenu(val anchor: Rect, val content: @Composable () -> Unit)

/** The layer the widget being drawn may open a menu in; null outside a [PluginOverlayBox]. */
val LocalPluginOverlay = compositionLocalOf<PluginOverlay?> { null }

/** Whose widgets are being drawn, for what the host remembers per plugin; null in a preview. */
val LocalPluginId = compositionLocalOf<String?> { null }

/**
 * Which page each plugin's tabs last showed, so a plugin opens where it was
 * left rather than on its first tab. Kept across panel openings and restarts;
 * a page number is all it holds. Plugins run only once the phone is unlocked,
 * so the ordinary (credential) storage is the place.
 */
object PluginTabMemory {
    private const val FILE = "plugin_tabs"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(pluginId: String, tabsId: String) = "$pluginId|$tabsId"

    fun get(context: Context, pluginId: String, tabsId: String): Int =
        runCatching { prefs(context).getInt(key(pluginId, tabsId), 0) }.getOrDefault(0)

    fun set(context: Context, pluginId: String, tabsId: String, index: Int) {
        runCatching { prefs(context).edit().putInt(key(pluginId, tabsId), index).apply() }
    }
}

/**
 * Hosts a plugin's widgets with the overlay layer over them: an open
 * dropdown, and any dialog [pluginId] asked for with `ui.dialog`.
 */
@Composable
fun PluginOverlayBox(pluginId: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val overlay = remember { PluginOverlay() }
    val asking by PluginDialogs.current.collectAsState()
    val dialog = asking?.takeIf { pluginId != null && it.pluginId == pluginId }
    Box(modifier.onGloballyPositioned { overlay.origin = it.positionInRoot() }) {
        CompositionLocalProvider(LocalPluginOverlay provides overlay, LocalPluginId provides pluginId) { content() }
        overlay.menu?.let { menu -> MenuLayer(overlay, menu) }
        if (dialog != null) DialogLayer(dialog)
    }
}

/** A scrim that closes the menu on a tap, and the menu under (or over) its field. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MenuLayer(overlay: PluginOverlay, menu: OverlayMenu) {
    val style = LocalPluginPanelStyle.current
    Box(
        Modifier
            .matchParentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { overlay.close() },
    )
    val visible = remember(menu) { MutableTransitionState(false).apply { targetState = true } }
    Layout(
        modifier = Modifier.matchParentSize(),
        content = {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn() + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
            ) {
                Column(
                    modifier = Modifier
                        .shadow(8.dp, style.cardShape)
                        .clip(style.cardShape)
                        .background(style.menuFill)
                        .then(style.cardBorder(Modifier, style.cardShape))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) { menu.content() }
            }
        },
    ) { measurables, constraints ->
        val anchor = menu.anchor.translate(-overlay.origin)
        val gap = 4.dp.roundToPx()
        val below = (constraints.maxHeight - anchor.bottom.toInt() - gap).coerceAtLeast(0)
        val above = (anchor.top.toInt() - gap).coerceAtLeast(0)
        val downwards = below >= MIN_MENU_ROOM.roundToPx() || below >= above
        val room = if (downwards) below else above
        val width = anchor.width.toInt().coerceIn(0, constraints.maxWidth)
        val placeable = measurables.firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = room.coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable?.place(
                anchor.left.toInt().coerceIn(0, (constraints.maxWidth - width).coerceAtLeast(0)),
                if (downwards) anchor.bottom.toInt() + gap else anchor.top.toInt() - gap - placeable.height,
            )
        }
    }
}

/** One option row of a dropdown menu: its words, and a check on the chosen one. */
@Composable
internal fun MenuOption(label: String, chosen: Boolean, onClick: () -> Unit) {
    val style = LocalPluginPanelStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
    ) {
        Text(
            label,
            color = if (chosen) style.accent else style.onSurface,
            fontSize = 15.sp,
            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (chosen) style.icon(ICON_CHECK, style.accent, 20.dp)
    }
}

/** A plugin's dialog, centred over its panel the way Android draws an alert. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DialogLayer(request: PluginDialogRequest) {
    val style = LocalPluginPanelStyle.current
    val visible = remember(request) { MutableTransitionState(false).apply { targetState = true } }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { request.answer(null) },
    ) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 360.dp)
                    .shadow(12.dp, DIALOG_SHAPE)
                    .clip(DIALOG_SHAPE)
                    .background(style.menuFill)
                    // Taps inside the card are not taps on the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
            ) {
                // Whose question it is, always: a plugin cannot pass for the keyboard.
                Text(request.pluginName, color = style.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (request.title.isNotEmpty()) {
                    Text(request.title, color = style.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
                }
                if (!request.text.isNullOrEmpty()) {
                    Text(
                        request.text,
                        color = style.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 8.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // The first button is the answer the plugin offers ("Delete",
                    // then "Cancel"): drawn filled and rightmost, where Android
                    // puts an alert's confirming button, and the rest before it.
                    request.buttons.asReversed().forEach { label ->
                        PluginButton(label, if (label == request.buttons.first()) ButtonKind.FILLED else ButtonKind.TEXT) {
                            request.answer(label)
                        }
                    }
                }
            }
        }
    }
}

/** The fill a menu or dialog wears: the panel's surface made opaque, so the widgets under it don't show through. */
private val PluginPanelStyle.menuFill: Color
    get() = if (surface.alpha >= 1f) surface else lerp(menuBase, surface.copy(alpha = 1f), surface.alpha)

private val PluginPanelStyle.menuBase: Color
    get() = if (text.luminance() > LIGHT_TEXT) Color(0xFF202124) else Color(0xFFF8F9FA)

private val MIN_MENU_ROOM = 160.dp
private val DIALOG_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
private const val SCRIM_ALPHA = 0.32f
private const val LIGHT_TEXT = 0.5f

/** A dialog a plugin asked for with `ui.dialog`, waiting for the user's answer. */
class PluginDialogRequest(
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val text: String?,
    val buttons: List<String>,
) {
    private val done = CountDownLatch(1)

    @Volatile
    private var chosen: String? = null

    /** The user tapped [label], or dismissed the dialog (null). */
    fun answer(label: String?) {
        chosen = label
        done.countDown()
        PluginDialogs.clear(this)
    }

    internal fun await(isActive: () -> Boolean, timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!isActive() || System.currentTimeMillis() > end) return null
        }
        return chosen
    }

    private companion object {
        const val POLL_MS = 200L
    }
}

/**
 * The dialog on screen, at most one: a plugin's thread puts it here and waits;
 * the panel of that plugin draws it and answers.
 */
object PluginDialogs {
    private val shown = MutableStateFlow<PluginDialogRequest?>(null)

    val current: StateFlow<PluginDialogRequest?> = shown.asStateFlow()

    /** Shows [request] and waits for the answer, null when dismissed, timed out or the plugin stopped. */
    fun ask(request: PluginDialogRequest, isActive: () -> Boolean, timeoutMs: Long = TIMEOUT_MS): String? {
        shown.value?.answer(null)
        shown.value = request
        try {
            return request.await(isActive, timeoutMs)
        } finally {
            clear(request)
        }
    }

    internal fun clear(request: PluginDialogRequest) {
        shown.compareAndSet(request, null)
    }

    private const val TIMEOUT_MS = 10 * 60_000L
}
