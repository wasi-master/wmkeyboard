package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockTab
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockCallbacks
import com.wasimaster.wmkeyboard.ime.clock.ClockTextEntry
import com.wasimaster.wmkeyboard.ime.clock.ClockTextField
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.clock.ClockUndo
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.swipeTabs
import kotlinx.coroutines.delay
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringGentle
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Clock tool: world clocks, alarms, timers, a stopwatch, a meeting
 * planner, date maths and a history, in seven tabs.
 *
 * Everything that ticks reads [rememberNowState] rather than the controller,
 * so a running stopwatch redraws its own digits and nothing else. The tab row
 * is the header, its selected fill sliding from chip to chip as the tabs
 * change; the tab last open is the one the panel opens on next time. Each
 * tab lays out in the language the other revamped panels share: hero digits
 * or a hero ring, tinted cards and stat tiles, and one bar of actions along
 * the bottom.
 */
@Composable
internal fun ClockPanel(
    state: KeyboardUiState,
    callbacks: ClockCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.clockText
    val tabs = ClockTab.entries

    PanelFocusTarget(
        panel = PanelMode.CLOCK,
        region = FocusRegion.CHIPS,
        count = tabs.size,
        columns = tabs.size,
    ) { index -> tabs.getOrNull(index)?.let { act(ClockAction.Tab(it)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = 36.dp,
        compact = typing != null,
        compactHeight = 150.dp,
        headerActions = {
            if (typing != null) {
                TypingHeader(typing, act)
            } else {
                TabsHeader(state, ui, act)
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // A sideways swipe steps through the tabs, not while the keys type into one.
                .swipeTabs(ui.tab.ordinal, tabs.size, enabled = typing == null) { act(ClockAction.Tab(tabs[it])) },
        ) {
            val reduce = state.settings.reduceMotion
            AnimatedContent(
                targetState = ui.tab,
                transitionSpec = {
                    if (reduce) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(TAB_MS)) { it / 6 * dir } + fadeIn(tween(TAB_MS))) togetherWith
                            (slideOutHorizontally(tween(TAB_MS)) { -it / 6 * dir } + fadeOut(tween(TAB_MS / 2)))
                    }
                },
                label = "clockTab",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                when (tab) {
                    ClockTab.CLOCKS -> ClocksTab(state, ui, act)
                    ClockTab.ALARMS -> AlarmsTab(state, ui, act)
                    ClockTab.TIMERS -> TimersTab(state, ui, act)
                    ClockTab.STOPWATCH -> StopwatchTab(state, ui, act)
                    ClockTab.PLANNER -> PlannerTab(state, ui, act)
                    ClockTab.DATES -> DatesTab(state, ui, act)
                    ClockTab.HISTORY -> HistoryTab(state, ui, act)
                }
            }
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let { undoText(it) }.orEmpty(),
                visible = undo != null,
                key = undo,
                reduceMotion = reduce,
                onUndo = { act(ClockAction.Undo) },
                onDismiss = { act(ClockAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun undoText(undo: ClockUndo): String = when (undo) {
    is ClockUndo.AlarmRemoved -> stringResource(R.string.ime_clock_undo_alarm)
    is ClockUndo.HistoryCleared -> stringResource(R.string.ime_clock_undo_history)
    is ClockUndo.ZoneRemoved -> stringResource(R.string.ime_clock_undo_zone)
}

@Composable
private fun RowScope.TabsHeader(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val tabs = ClockTab.entries
    val selected = ui.tab.ordinal
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    val shape = kb.chipShape()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Where each chip landed in the row, in px. The grounds under the chips
    // and the selected fill are drawn from these, in layers under the words,
    // so the fill can slide from one chip to the next over the grounds.
    val slots = remember { mutableStateMapOf<Int, TabSlot>() }
    val sources = remember { List(tabs.size) { MutableInteractionSource() } }
    val target = slots[selected]
    val fillX = remember { Animatable(0f) }
    val fillW = remember { Animatable(0f) }
    LaunchedEffect(target, animate) {
        val t = target ?: return@LaunchedEffect
        if (animate && fillW.value > 0f) {
            launch { fillX.animateTo(t.x, kitSpringGentle()) }
            fillW.animateTo(t.width, kitSpringGentle())
        } else {
            fillX.snapTo(t.x)
            fillW.snapTo(t.width)
        }
    }
    // The tab swiped to comes on screen, with a little of its neighbour.
    LaunchedEffect(selected, target) {
        val t = target ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val peek = with(density) { TAB_PEEK.toPx() }
        val left = (t.x - peek).toInt().coerceAtLeast(0)
        val right = (t.x + t.width + peek - viewport).toInt()
        val to = when {
            left < scroll.value -> left
            right > scroll.value -> right
            else -> return@LaunchedEffect
        }
        if (animate) scroll.animateScrollTo(to) else scroll.scrollTo(to)
    }
    Box(
        Modifier
            .weight(1f)
            .horizontalScroll(scroll),
    ) {
        // The grounds: each chip's own fill, under everything.
        for (i in tabs.indices) {
            val slot = slots[i] ?: continue
            key(i) {
                Box(
                    Modifier
                        .absoluteOffset { IntOffset(slot.x.roundToInt(), slot.y.roundToInt()) }
                        .size(with(density) { slot.width.toDp() }, with(density) { slot.height.toDp() })
                        .pressScale(sources[i], animate)
                        .clip(shape)
                        .frostGlass(kb, shape, FrostSurface.CHIPS)
                        .background(kb.chip)
                        .chipBorder(kb, shape),
                )
            }
        }
        // The selected fill, sliding between neighbours.
        if (target != null) {
            Box(
                Modifier
                    .absoluteOffset { IntOffset(fillX.value.roundToInt(), target.y.roundToInt()) }
                    .size(with(density) { fillW.value.toDp() }, with(density) { target.height.toDp() })
                    .pressScale(sources[selected], animate)
                    .clip(shape)
                    .background(kb.chipActive),
            )
        }
        // The chips themselves: clear, with their words, dot and count.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.width(2.dp))
            tabs.forEachIndexed { i, tab ->
                val badge = when (tab) {
                    ClockTab.TIMERS -> ui.data.timers.size
                    ClockTab.ALARMS -> ui.data.alarms.count { it.enabled }
                    else -> 0
                }
                val live = (tab == ClockTab.STOPWATCH && ui.data.stopwatch.running) ||
                    (tab == ClockTab.TIMERS && ui.data.timers.any { !it.paused })
                TabChip(
                    label = stringResource(tabLabel(tab)),
                    selected = ui.tab == tab,
                    badge = badge,
                    live = live,
                    focused = focused == i,
                    // Its own fill only until the row knows where it sits.
                    ground = slots[i] == null,
                    source = sources[i],
                    animate = animate,
                    onPlaced = { slot -> if (slots[i] != slot) slots[i] = slot },
                    onClick = { act(ClockAction.Tab(tab)) },
                )
            }
            Spacer(Modifier.width(2.dp))
        }
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_clock_settings_desc),
        active = false,
    ) { act(ClockAction.OpenSettings) }
}

/** Where a tab chip sits in its row, in px. */
private data class TabSlot(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * A tab chip with a count and a dot that says "something is running here".
 * Its fill is drawn by [TabsHeader] under it (see [ground]); the chip itself
 * carries the words, which fade between the two inks as the fill arrives.
 */
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    badge: Int,
    live: Boolean,
    focused: Boolean,
    ground: Boolean,
    source: MutableInteractionSource,
    animate: Boolean,
    onPlaced: (TabSlot) -> Unit,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val fg by animateColorAsState(
        if (selected) kb.chipActiveText else kb.chipText,
        if (animate) tween(TAB_MS) else snap(),
        label = "clock-tab-ink",
    )
    Row(
        modifier = Modifier
            .onPlaced { c ->
                val p = c.positionInParent()
                onPlaced(TabSlot(p.x, p.y, c.size.width.toFloat(), c.size.height.toFloat()))
            }
            .pressScale(source, animate)
            .clip(shape)
            .background(if (!ground) Color.Transparent else if (selected) kb.chipActive else kb.chip)
            .focusRing(focused, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) fg else kb.accent),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (badge > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                badge.toString(),
                color = fg.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** While the keys type into the panel, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: ClockTextEntry, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val placeholder = stringResource(
        when (entry.field) {
            ClockTextField.SEARCH -> R.string.ime_clock_search_hint
            ClockTextField.ALARM_LABEL,
            ClockTextField.TIMER_LABEL,
            -> R.string.ime_clock_label_hint
            ClockTextField.COUNTDOWN_TITLE -> R.string.ime_clock_countdown_title_hint
            ClockTextField.ZONE_LABEL -> R.string.ime_clock_zone_label_hint
            ClockTextField.DATE -> R.string.ime_clock_date_hint
        },
    )
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = entry.text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    ToolPanelChip(stringResource(R.string.ime_clock_done), selected = true) {
        act(if (entry.field == ClockTextField.SEARCH) ClockAction.SearchClose else ClockAction.TextDone)
    }
    Spacer(Modifier.width(4.dp))
}

private fun tabLabel(tab: ClockTab): Int = when (tab) {
    ClockTab.CLOCKS -> R.string.ime_clock_tab_clocks
    ClockTab.ALARMS -> R.string.ime_clock_tab_alarms
    ClockTab.TIMERS -> R.string.ime_clock_tab_timers
    ClockTab.STOPWATCH -> R.string.ime_clock_tab_stopwatch
    ClockTab.PLANNER -> R.string.ime_clock_tab_planner
    ClockTab.DATES -> R.string.ime_clock_tab_dates
    ClockTab.HISTORY -> R.string.ime_clock_tab_history
}

private const val TAB_MS = 220

/** How much of the next chip shows when the row scrolls to the one swiped to. */
private val TAB_PEEK = 24.dp

// ---- Shared pieces --------------------------------------------------------

/**
 * The wall clock as a state, re-read every [periodMs] and aligned to the
 * period, so a seconds hand ticks on the second rather than drifting across
 * it. Read it where the digits are drawn and nowhere wider, so a tick
 * redraws the digits and not the tab around them.
 */
@Composable
internal fun rememberNowState(periodMs: Long = 1000L): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay((periodMs - now.longValue % periodMs).coerceAtLeast(16))
        }
    }
    return now
}

/** [rememberNowState] read at once, for a scope that is small already. */
@Composable
internal fun rememberNow(periodMs: Long = 1000L): Long = rememberNowState(periodMs).value

/** Digits that do not shuffle as they change: tabular figures. */
internal fun digits(size: TextUnit, weight: FontWeight = FontWeight.Light): TextStyle =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum")

/** A rounded card in the theme's chip colour, glass where the theme is glass. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClockCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    focused: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(if (highlighted) kb.accent.copy(alpha = 0.16f) else kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(padding),
        content = content,
    )
}

/** A pill button. [primary] fills it with the accent. */
@Composable
internal fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = false,
    height: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val bg = if (primary) kb.chipActive else kb.chip
    val fg = if (primary) kb.chipActiveText else kb.chipText
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg.copy(alpha = fg.alpha * alpha), modifier = Modifier.size(18.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                color = fg.copy(alpha = fg.alpha * alpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A round icon button with a hold-to-name hint. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    size: Dp = 36.dp,
    focused: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // The theme's chip shape rather than a fixed circle: a theme with square
    // chips gets square buttons here too.
    val shape = kb.chipShape()
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clockChip(selected = primary, shape = shape)
                .focusRing(focused, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = tint ?: if (primary) kb.chipActiveText else kb.chipText,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** A one-line notice with an action, for the grants an alarm wants. */
@Composable
internal fun Banner(text: String, action: String, onAction: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.accent.copy(alpha = 0.14f))
            .chipBorder(kb, shape)
            .clickable(onClick = onAction)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = kb.suggestionText,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(action, color = kb.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** An empty tab: an icon in a soft circle, a line of title, a line of help, and the one thing to do. */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val kb = LocalKbTheme.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = kb.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(body, color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}

/** A small caps label over a group. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Text(
        text.uppercase(),
        color = kb.secondaryText,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** A two-way switch drawn in the theme's colours. */
@Composable
internal fun ClockSwitch(on: Boolean, description: String, onChange: (Boolean) -> Unit) {
    val kb = LocalKbTheme.current
    // The selected-chip pair when on and the plain chip pair when off, so the
    // switch reads in the theme's own colours (and its glass and outline).
    val knob = if (on) kb.chipActiveText else kb.chipText
    val knobOffset by animateDpAsState(
        targetValue = if (on) 18.dp else 2.dp,
        animationSpec = if (kb.reduceMotion) tween(0) else tween(SWITCH_MS),
        label = "switch",
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clockChip(selected = on, shape = CircleShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .padding(start = knobOffset, top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

private const val SWITCH_MS = 160

/**
 * How every chip-like surface in the panel dresses: the theme's chip shape
 * (or [shape]), glass where the theme makes chips glass, the chip fill (or the
 * selected-chip fill), and the theme's chip outline. One place, so a theme
 * reaches all of them, the way [ToolPanelChip] dresses the other panels.
 */
@Composable
internal fun Modifier.clockChip(selected: Boolean = false, shape: Shape? = null): Modifier {
    val kb = LocalKbTheme.current
    val s = shape ?: kb.chipShape()
    return this
        .clip(s)
        .frostGlass(kb, s, FrostSurface.CHIPS)
        .background(if (selected) kb.chipActive else kb.chip)
        .chipBorder(kb, s)
}
