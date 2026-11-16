package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MediaPanelExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.PanelHeightKind
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.layoutKind
import kotlin.math.abs

/**
 * The switch between the emoji, GIF and sticker panels (issue #366): one
 * segment per panel, the open one lit, so the three read as the tabs of one
 * panel the way they do on most keyboards.
 *
 * Every one of the three can carry it as a component of its layout
 * ([PanelFieldKind.MEDIA_TABS]); a GIF or sticker panel the user has not laid
 * out borrows the emoji panel's row that holds it, or puts the switch beside
 * its search box, as [KeyboardSettings.emoji]'s `mediaSwitcher` says.
 */

/** The panels the switch moves between, in the order it draws them. */
internal val MediaTabPanels: List<PanelMode> =
    listOf(PanelMode.EMOJI, PanelMode.EMOJI_MIX, PanelMode.GIF, PanelMode.STICKER)

/** The tool behind each of [MediaTabPanels]: its icon, its name, and whether it is switched on. */
private fun PanelMode.mediaTool(): ToolbarTool? = when (this) {
    PanelMode.EMOJI -> ToolbarTool.EMOJI
    PanelMode.GIF -> ToolbarTool.GIF
    PanelMode.STICKER -> ToolbarTool.STICKER
    PanelMode.EMOJI_MIX -> ToolbarTool.EMOJI_MIX
    else -> null
}

/**
 * The panels the switch offers. Emoji always: the emoji key opens that panel
 * with the tool off the toolbar. GIFs and stickers only while their tool is
 * switched on, shipped in this build and usable, the tests the toolbar makes
 * before it draws one.
 */
internal fun mediaTabs(settings: KeyboardSettings): List<PanelMode> = MediaTabPanels.filter { panel ->
    val tool = panel.mediaTool() ?: return@filter false
    panel == PanelMode.EMOJI ||
        (tool in settings.enabledTools && isSupportedTool(tool) && isUsableTool(tool, settings))
}

/**
 * Where the switch goes: the setting, or [MediaSwitcher.OFF] while there is
 * only the emoji panel to switch to, since a switch with one segment goes
 * nowhere and would only take room from the space bar.
 */
internal fun mediaSwitcherPlacement(settings: KeyboardSettings): MediaSwitcher =
    if (mediaTabs(settings).size < 2) MediaSwitcher.OFF else settings.emoji.mediaSwitcher

/**
 * The panel the emoji key and the emoji tool open, given the panel [open] now
 * and the one of the three that was open [last].
 *
 * Plain emoji until "open the last used" is on. Then the three are one panel:
 * with none of them up, the key brings back whichever was open last; with one
 * of them up, it names that one, which closes it, the way the key has always
 * closed the emoji panel. A last panel whose tool has since been switched off
 * falls back to emoji.
 */
internal fun mediaOpenerTarget(open: PanelMode, last: PanelMode, settings: KeyboardSettings): PanelMode {
    if (!settings.emoji.rememberMediaTab) return PanelMode.EMOJI
    if (open in MediaTabPanels) return open
    return last.takeIf { it in mediaTabs(settings) } ?: PanelMode.EMOJI
}

/**
 * The GIF or sticker panel the user has not laid out (#538): its browser over
 * a row borrowed from the emoji panel, so the three panels end in the same
 * keys and a bottom row the user rearranged on the emoji panel comes across.
 *
 * The row borrowed is the emoji panel's switch row when that is keys and the
 * switch alone, else the last row of keys it has (abc / space / backspace on
 * the shipped panel with the switch up top), else the shipped row for the
 * switch's [placement]. A row that also holds an emoji component, or that a
 * key from the row above reaches down into, belongs to the emoji panel, and
 * lifted out it would draw a hole. The emoji panel's label size, theme and
 * font ride along for the same reason.
 */
internal fun mediaPanelLayout(kind: PanelKind, emoji: PanelLayoutSpec, placement: MediaSwitcher): PanelLayoutSpec {
    val rows = emoji.grid.rows
    val switchRow = rows.indexOfFirst { row -> row.any { it.isMediaTabs() } }
    val keysRow = rows.indexOfLast { row -> row.none { it.action is KeyAction.Field } }
    val index = when {
        rows.liftable(switchRow) -> switchRow
        rows.liftable(keysRow) -> keysRow
        else -> -1
    }
    val own = rows.getOrNull(index)
    val shipped = if (placement == MediaSwitcher.BOTTOM) BuiltInPanelLayouts.mediaBottomRow else BuiltInPanelLayouts.bottomRow
    val height = own?.let { emoji.grid.rowHeights?.getOrNull(index) } ?: 1f
    val spec = BuiltInPanelLayouts.media(kind, own ?: shipped, height)
    return spec.copy(
        grid = spec.grid.copy(fontScale = emoji.grid.fontScale, themeId = emoji.grid.themeId),
        appearance = emoji.appearance,
    )
}

/** Whether row [index] stands on its own: keys and the switch, nothing reaching in from above. */
private fun List<List<Key>>.liftable(index: Int): Boolean {
    val row = getOrNull(index) ?: return false
    val reachedInto = take(index).withIndex().any { (r, above) -> above.any { key -> r + key.rowSpan > index } }
    return !reachedInto && row.all { key -> key.rowSpan <= 1 && (key.action !is KeyAction.Field || key.isMediaTabs()) }
}

private fun Key.isMediaTabs(): Boolean = (action as? KeyAction.Field)?.kind == PanelFieldKind.MEDIA_TABS

/**
 * The switch as a pill beside the GIF or sticker search box, or null: only
 * while the switch is on and the panel's layout has no switch cell of its
 * own, so a layout that places it never shows two. Never while a search has
 * the keys: they come back under the panel then.
 */
internal fun mediaSwitchPill(
    state: KeyboardUiState,
    spec: PanelLayoutSpec,
    onPanelChange: (PanelMode) -> Unit,
): (@Composable () -> Unit)? {
    if (state.mediaSearchActive || mediaSwitcherPlacement(state.settings) == MediaSwitcher.OFF) return null
    if (spec.grid.rows.any { row -> row.any { it.isMediaTabs() } }) return null
    return { MediaTabsPill(state, onPanelChange) }
}

/**
 * The GIF or sticker panel (#538): the browser and the keys around it, laid
 * out like the emoji panel and inside the full-bleed chrome when that setting
 * is on. A search draws the browser alone, short, with the key rows back
 * underneath it for typing the query.
 *
 * A bar on top drags the panel taller or shorter, the clipboard's bar
 * ([ClipPanelHeightBar]) writing the emoji panel's height setting (#537),
 * which the three panels share.
 *
 * [browser] draws the panel's body; `fullBleed` says the header already holds
 * the search box, `inGrid` that it is filling a layout cell rather than
 * sizing itself. [headerSearch] is the full-bleed header's search box.
 */
@Composable
internal fun MediaPanelHost(
    state: KeyboardUiState,
    callbacks: PanelLayoutCallbacks,
    headerSearch: @Composable RowScope.() -> Unit,
    browser: @Composable (fullBleed: Boolean, inGrid: Boolean, switcher: (@Composable () -> Unit)?) -> Unit,
) {
    val kind = state.panel.layoutKind ?: return
    val onClose = { callbacks.onPanelChange(state.panel) }
    val spec = state.panelLayout(kind)
    val pill = mediaSwitchPill(state, spec, callbacks.onPanelChange)
    val fullBleed = state.settings.mediaFullBleed
    val searching = state.mediaSearchActive
    val fields: @Composable (PanelFieldKind) -> Unit = { field ->
        when (field) {
            PanelFieldKind.MEDIA_TABS -> MediaTabsField(state, callbacks.onPanelChange)
            kind.requiredField -> {
                // A sideways swipe across the GIFs or stickers steps to the
                // panel beside it on the switch; the chip rows keep theirs.
                val tabs = mediaTabs(state.settings)
                Box(
                    Modifier
                        .fillMaxSize()
                        .swipeTabs(tabs.indexOf(state.panel), tabs.size) { callbacks.onPanelChange(tabs[it]) },
                ) {
                    browser(fullBleed, true, if (fullBleed) null else pill)
                }
            }
            else -> Unit
        }
    }
    // A fresh header for each panel: switching from GIFs to stickers shows
    // the new panel's search box and chips, wherever the last one was scrolled.
    val density = LocalDensity.current
    val chrome = remember(state.panel, density) { MediaChrome(with(density) { MediaChromeSlopDp.dp.toPx() }) }
    // A search shows the box, and its results start with the header out.
    LaunchedEffect(chrome, searching) { if (searching) chrome.hidden = false }
    // The height bar: the panel opens this much taller than the keyboard, up
    // to what the screen can give it, the way the clipboard's does (#414).
    val setting = panelExtraHeight(state, PanelHeightKind.MEDIA, state.settings.emoji.panelExtraHeightDp)
    val resize = rememberClipPanelResize(setting)
    val base = if (fullBleed) keyRowsHeight(state) + fullBleedHiddenRows(state, macroRowAtPanelOpen(state)) else keyRowsHeight(state)
    val maxPanel = toolPanelHeight(state, wanted = base + MediaPanelExtraHeightRange.last.dp, floor = base)
    val maxExtra = (maxPanel - base).coerceAtLeast(0.dp)
    val extra = resize.shownDp(setting).dp.coerceIn(0.dp, maxExtra)
    val maxPanelPx = with(density) { maxPanel.roundToPx() }
    SideEffect { resize.maxPanelPx = maxPanelPx }
    val probe = remember(resize) { Modifier.clipPanelHeightProbe(resize) }
    val barDescription = stringResource(R.string.ime_media_height_desc)
    // Not while a search has the keys, and not where the screen leaves no
    // room to grow: the bar would drag nothing.
    val bar: (@Composable () -> Unit)? = if (searching || maxExtra < 1.dp) null else {
        { ClipPanelHeightBar(resize, extra, maxExtra, callbacks.onMediaPanelHeight, barDescription) }
    }
    CompositionLocalProvider(LocalMediaChrome provides chrome) {
        if (fullBleed) {
            Box(modifier = probe) {
                FullBleedTool(
                    state,
                    title = "",
                    onClose = onClose,
                    // Search collapses the panel so the key rows fit below it, keeping
                    // a band of live results up.
                    compact = searching,
                    extraHeight = extra,
                    topHandle = bar,
                    headerHidden = chrome.collapsed(state),
                    headerActions = {
                        headerSearch()
                        pill?.invoke()
                    },
                ) {
                    if (searching) {
                        browser(true, false, null)
                    } else {
                        PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
                    }
                }
            }
        } else if (searching) {
            browser(false, false, null)
        } else {
            Column(
                modifier = probe
                    .fillMaxWidth()
                    // As tall as the emoji panel's setting makes it (#537), or
                    // as the bar is dragging it.
                    .height(toolPanelHeight(state, wanted = base + extra, floor = base)),
            ) {
                bar?.invoke()
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * The switch as a fixed-size pill for a search row: in the full-bleed
 * header, or at the end of the GIF and sticker search bar.
 */
@Composable
internal fun MediaTabsPill(state: KeyboardUiState, onPanelChange: (PanelMode) -> Unit) {
    val count = mediaTabs(state.settings).size
    if (count < 2) return
    MediaTabsField(
        state, onPanelChange,
        Modifier
            .width(MediaPillSegmentWidth * count)
            .height(MediaPillHeight),
    )
}

private val MediaPillSegmentWidth = 36.dp
private val MediaPillHeight = 32.dp

/**
 * The switch itself, filling what it is given. A segment for the open panel
 * does nothing when pressed: the switch changes panels, it never closes one,
 * which is what abc and the back button are for.
 *
 * One lit pill behind the open segment rather than a background per segment,
 * so a switch slides it across instead of blinking it out under one tab and in
 * under the next ([mediaTabIndicator]). The switch is composed afresh with
 * every panel it opens, so the slide starts from the tab [MediaTabMemory]
 * says was lit, and each segment is a [panelOrigin]: the panel it opens grows
 * out of that tab.
 */
@Composable
internal fun MediaTabsField(
    state: KeyboardUiState,
    onPanelChange: (PanelMode) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val tabs = mediaTabs(state.settings)
    if (tabs.size < 2) return
    val kb = LocalKbTheme.current
    val motion = kb.motion.any
    val shape = kb.chipShape()
    val lit = tabs.indexOf(state.panel)
    // The tab lit before this switch was composed: where the pill and the
    // tints start, unless motion is off or there was none.
    val was = remember {
        val before = MediaTabMemory.lit?.let { tabs.indexOf(it) } ?: -1
        if (motion && before >= 0 && lit >= 0) before else lit
    }
    val slide = remember { MediaTabSlide(from = was.toFloat(), to = lit.toFloat(), animate = motion) }
    SideEffect { MediaTabMemory.lit = state.panel }
    LaunchedEffect(slide, lit, motion) {
        val target = lit.toFloat()
        if (!motion || target < 0f || slide.to < 0f) {
            slide.from = target
            slide.to = target
            slide.t.snapTo(1f)
            return@LaunchedEffect
        }
        if (target != slide.to) {
            // From wherever the pill is drawn now, which mid-slide is not a tab.
            slide.from = slide.lead()
            slide.to = target
            slide.t.snapTo(0f)
        }
        if (slide.t.value < 1f) slide.t.animateTo(1f, tween(MediaTabSlideMs, easing = LinearEasing))
    }
    Row(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .mediaTabIndicator(tabs.size, kb.chipActive, shape, slide)
            .chipBorder(kb, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, panel ->
            val tool = panel.mediaTool() ?: return@forEachIndexed
            MediaTab(
                tool = tool,
                selected = index == lit,
                startLit = if (index == was) 1f else 0f,
                motion = motion,
                shape = shape,
                onSelect = { onPanelChange(panel) },
            )
        }
    }
}

/**
 * One segment: the tool's icon, its tint easing between the chip's text colour
 * and the lit one alongside the pill. Its own scope, so a frame of the tint
 * recomposes this segment and nothing around it.
 */
@Composable
private fun RowScope.MediaTab(
    tool: ToolbarTool,
    selected: Boolean,
    startLit: Float,
    motion: Boolean,
    shape: Shape,
    onSelect: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val lit = remember { Animatable(startLit) }
    LaunchedEffect(selected, motion) {
        val to = if (selected) 1f else 0f
        if (motion) lit.animateTo(to, tween(MediaTabSlideMs, easing = FastOutSlowInEasing)) else lit.snapTo(to)
    }
    val tint = when {
        !motion -> if (selected) kb.chipActiveText else kb.chipText
        else -> lerp(kb.chipText, kb.chipActiveText, lit.value.coerceIn(0f, 1f))
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .panelOrigin()
            .clip(shape)
            .selectable(selected = selected, role = Role.Tab) {
                if (!selected) onSelect()
            },
        contentAlignment = Alignment.Center,
    ) {
        SlotIcon(
            IconSlots.forTool(tool),
            contentDescription = toolLabel(tool),
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}

/**
 * The media tab lit last, across panels. Plain: written after a switch is
 * composed and read once when the next one is, neither of which wants a
 * snapshot read.
 */
private object MediaTabMemory {
    var lit: PanelMode? = null
}

/**
 * The lit pill on its way from tab [from] to tab [to]: [t] runs 0..1 linearly
 * and the pill's two edges ease along it at different rates, the leading one
 * first, so it stretches toward the new tab and pulls its tail in after.
 * Positions are tab indices; [t] is read in the draw only.
 */
private class MediaTabSlide(var from: Float, var to: Float, animate: Boolean) {
    val t = Animatable(if (animate && from != to) 0f else 1f)

    fun lead(): Float = from + (to - from) * MediaTabLeadEasing.transform(t.value)

    fun trail(): Float = from + (to - from) * MediaTabTrailEasing.transform(t.value)
}

/**
 * Draws [slide]'s pill behind a row of [count] even tabs: one tab wide at
 * rest, stretched across the tabs between while it moves. After the row's
 * background, before its tabs.
 */
private fun Modifier.mediaTabIndicator(count: Int, color: Color, shape: Shape, slide: MediaTabSlide): Modifier =
    drawBehind {
        if (count <= 0 || slide.to < 0f) return@drawBehind
        val tab = size.width / count
        val a = slide.lead()
        val b = slide.trail()
        val start = minOf(a, b) * tab
        val width = (abs(a - b) + 1f) * tab
        // A right-to-left row lays its first tab out at the right-hand end.
        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
        val outline = shape.createOutline(Size(width, size.height), layoutDirection, this)
        translate(left = left) { drawOutline(outline, color) }
    }

/** The pill's whole move, and the tints' crossfade beside it. */
private const val MediaTabSlideMs = 200

/** The pill's leading edge sets off at once; its tail waits, then catches up. */
private val MediaTabLeadEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val MediaTabTrailEasing = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)
