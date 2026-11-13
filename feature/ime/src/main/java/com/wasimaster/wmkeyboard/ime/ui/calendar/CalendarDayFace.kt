package com.wasimaster.wmkeyboard.ime.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EventRepeat
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.swipeTabs
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The selected day as a timeline (#1210): the hours down the side, each timed
 * event as a block from its start to its end, side by side where they
 * overlap, and the day's all-day events as a row of chips above. A tap opens
 * an event; holding one and dragging it up or down moves it, a quarter hour
 * at a time, and the lift saves the move (a repeating event asks which
 * occurrences first). Holding an empty hour starts an event there. A swipe
 * across moves a day.
 */
@Composable
internal fun DayFace(state: KeyboardUiState, ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val tool = state.settings.calendarTool
    val altCalendars = listOf(tool.altOne, tool.altTwo).filter { it != AltCalendar.NONE }.distinct()
    val events = ui.dayEvents
    val allDay = events.orEmpty().filter { it.allDay }
    val timed = events.orEmpty().filterNot { it.allDay }
    val day = ui.selected

    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 3, columns = 3) { index ->
        when (index) {
            0 -> act(CalendarAction.SelectDay(day.plusDays(-1)))
            1 -> act(CalendarAction.Today)
            2 -> act(CalendarAction.SelectDay(day.plusDays(1)))
        }
    }
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = timed.size, columns = 1) { index ->
        timed.getOrNull(index)?.let { act(CalendarAction.OpenEvent(it)) }
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)?.let { timed.getOrNull(it) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        DaySummary(ui, altCalendars, tool.hijriAdjustDays)
        ui.error?.let { e ->
            Banner(errorText(e), stringResource(R.string.ime_calendar_done)) { act(CalendarAction.ErrorDismiss) }
        }
        if (allDay.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                itemsIndexed(allDay) { _, event -> AllDayChip(event) { act(CalendarAction.OpenEvent(event)) } }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .swipeTabs(index = 1, count = 3) { act(CalendarAction.SelectDay(day.plusDays(if (it == 0) -1L else 1L))) },
        ) {
            when {
                !ui.canRead -> EmptyState(
                    Icons.Outlined.Event,
                    stringResource(R.string.ime_calendar_agenda_allow_title),
                    stringResource(R.string.ime_calendar_permission_info),
                    action = {
                        PillButton(stringResource(R.string.ime_calendar_allow_action), primary = true) { act(CalendarAction.AskRead) }
                    },
                )
                events == null -> Unit
                else -> Timeline(ui, day, timed, focused, act)
            }
        }
    }
}

/** An all-day event as a small chip in its colour. */
@Composable
private fun AllDayChip(event: DeviceCalendarEvent, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val colour = if (event.color != 0) Color(event.color) else kb.accent
    val shape = kb.cardShape()
    Row(
        Modifier
            .height(26.dp)
            .clip(shape)
            .background(colour.copy(alpha = 0.28f))
            .chipBorder(kb, shape)
            .clickable {
                feedback()
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AllDayBadge(colour)
        Text(
            event.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
            color = kb.suggestionText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 6.dp)
                .width(140.dp),
        )
    }
}

/** A timed event placed on the day: the minutes it covers there, and its column among the ones it overlaps. */
private data class Placed(val event: DeviceCalendarEvent, val start: Int, val end: Int, val column: Int, val columns: Int)

/**
 * [events] as minutes of [day], clipped to it (one running past midnight
 * stops at the bottom), with overlapping events given columns side by side:
 * each takes the first column free at its start, and a run of overlaps shares
 * the widest count any of them needed.
 */
private fun place(events: List<DeviceCalendarEvent>, day: EventDate, zone: TimeZone): List<Placed> {
    val dayStart = EventTimes.millis(day, 0, zone)
    val dayEnd = EventTimes.millis(day.plusDays(1), 0, zone)
    val spans = events
        // The day's own events: a list still showing the day before, while
        // this one loads, places nothing.
        .filter { e -> e.begin < dayEnd && (e.end > dayStart || e.begin >= dayStart) }
        .map { e ->
            val start = ((maxOf(e.begin, dayStart) - dayStart) / MINUTE_MS).toInt()
            val end = ((minOf(maxOf(e.end, e.begin + MIN_BLOCK_MINUTES * MINUTE_MS), dayEnd) - dayStart) / MINUTE_MS).toInt()
            Triple(e, start.coerceIn(0, MINUTES_PER_DAY), end.coerceIn(0, MINUTES_PER_DAY))
        }
        .sortedWith(compareBy({ it.second }, { -it.third }))
    val out = ArrayList<Placed>(spans.size)
    var group = ArrayList<Pair<Triple<DeviceCalendarEvent, Int, Int>, Int>>()
    var groupEnd = -1
    fun flush() {
        val width = (group.maxOfOrNull { it.second } ?: -1) + 1
        group.forEach { (span, column) -> out += Placed(span.first, span.second, span.third, column, width) }
        group = ArrayList()
    }
    for (span in spans) {
        if (span.second >= groupEnd) {
            flush()
            groupEnd = -1
        }
        val taken = group.filter { it.first.third > span.second }.map { it.second }.toSet()
        val column = generateSequence(0) { it + 1 }.first { it !in taken }
        group += span to column
        groupEnd = maxOf(groupEnd, span.third)
    }
    flush()
    return out
}

/**
 * The hours of the day, scrolled on opening to the first event (or to the hour
 * before now on today), with the blocks laid over them and a line at the
 * current time on today.
 */
@Composable
private fun Timeline(ui: CalendarUi, day: EventDate, timed: List<DeviceCalendarEvent>, focused: DeviceCalendarEvent?, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val zone = remember { TimeZone.getDefault() }
    val placed = remember(timed, day) { place(timed, day, zone) }
    val scroll = rememberScrollState()
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    val isToday = day == ui.today
    val nowMinute = if (isToday) EventTimes.minuteOfDay(System.currentTimeMillis(), zone) else -1
    LaunchedEffect(day) {
        val firstMinute = placed.minOfOrNull { it.start } ?: if (isToday) nowMinute else DEFAULT_SCROLL_MINUTE
        val target = (((firstMinute / 60) - 1).coerceAtLeast(0) * hourPx).roundToInt()
        scroll.scrollTo(target)
    }
    // A drag lifted and saved: the block stays where it was dropped until the
    // reloaded day draws it there itself.
    var settled by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(ui.dayEvents) { settled = null }
    val newAt = rememberUpdatedState { minute: Int -> act(CalendarAction.NewEvent(day, minute)) }

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(HOUR_HEIGHT * 24)
                .pointerInput(day) {
                    // Holding an empty hour starts an event at its quarter.
                    detectTapGestures(onLongPress = { at ->
                        val minute = ((at.y / hourPx) * 60f).roundToInt()
                        newAt.value(snap(minute).coerceIn(0, MINUTES_PER_DAY - SNAP_MINUTES))
                    })
                },
        ) {
            val lineColor = kb.secondaryText.copy(alpha = 0.18f)
            val labelColor = kb.secondaryText
            // The hour lines, drawn once behind everything.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val left = LABEL_WIDTH.toPx()
                        for (h in 0..24) {
                            val y = h * hourPx
                            drawLine(lineColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                        if (nowMinute >= 0) {
                            val y = nowMinute / 60f * hourPx
                            drawLine(kb.accent, Offset(left - 4.dp.toPx(), y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                            drawCircle(kb.accent, 4.dp.toPx(), Offset(left - 4.dp.toPx(), y))
                        }
                    },
            )
            for (h in 0 until 24) {
                Text(
                    CalendarText.time(h * 60, ui.hour24),
                    color = labelColor,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(y = HOUR_HEIGHT * h - 5.dp)
                        .width(LABEL_WIDTH - 4.dp),
                )
            }
            val laneWidth = maxWidth - LABEL_WIDTH
            placed.forEach { p ->
                val shift = settled?.takeIf { it.first == p.event.id }?.second ?: 0
                EventBlock(
                    placed = p,
                    hour24 = ui.hour24,
                    laneWidth = laneWidth,
                    hourPx = hourPx,
                    restingShift = shift,
                    focused = focused == p.event,
                    onOpen = { act(CalendarAction.OpenEvent(p.event)) },
                    onMove = { minutes ->
                        settled = p.event.id to minutes
                        act(CalendarAction.MoveEvent(p.event, minutes))
                    },
                )
            }
        }
    }
}

/**
 * One event on the timeline. A hold lifts it; dragging moves it in quarter
 * hours, with the new time shown on it and a tick at each step; the lift
 * hands the move on. An event the user may not change only opens.
 */
@Composable
private fun EventBlock(
    placed: Placed,
    hour24: Boolean,
    laneWidth: Dp,
    hourPx: Float,
    restingShift: Int,
    focused: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val event = placed.event
    val colour = if (event.color != 0) Color(event.color) else kb.accent
    val shape = kb.cardShape()
    var dragY by remember(event.id, event.begin) { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    var steps by remember { mutableIntStateOf(0) }
    val minutePx = hourPx / 60f
    val dragMinutes = if (lifted) snap((dragY / minutePx).roundToInt()) else restingShift
    val start = placed.start + dragMinutes
    val length = (placed.end - placed.start).coerceAtLeast(MIN_BLOCK_MINUTES)
    val width = laneWidth / placed.columns
    val onMoveNow by rememberUpdatedState(onMove)
    Column(
        Modifier
            .offset(
                x = LABEL_WIDTH + width * placed.column,
                y = with(LocalDensity.current) { (start * minutePx).toDp() },
            )
            .width(width - 2.dp)
            .height(with(LocalDensity.current) { (length * minutePx).toDp() } - 2.dp)
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                if (lifted) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 8.dp.toPx()
                }
            }
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.85f))
            .background(colour.copy(alpha = if (lifted) 0.42f else 0.26f))
            .chipBorder(kb, shape)
            .drawBehind { drawRect(colour, size = Size(3.dp.toPx(), size.height)) }
            .then(
                if (event.canModify && !event.allDay) {
                    Modifier.pointerInput(event.id, event.begin) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                lifted = true
                                dragY = 0f
                                steps = 0
                                feedback()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragY += amount.y
                                val now = snap((dragY / minutePx).roundToInt()) / SNAP_MINUTES
                                if (now != steps) {
                                    steps = now
                                    feedback()
                                }
                            },
                            onDragEnd = {
                                val minutes = snap((dragY / minutePx).roundToInt())
                                lifted = false
                                dragY = 0f
                                if (minutes != 0) onMoveNow(minutes)
                            },
                            onDragCancel = {
                                lifted = false
                                dragY = 0f
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                feedback()
                onOpen()
            }
            .padding(start = 7.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
                color = kb.suggestionText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (event.recurring) {
                Icon(
                    Icons.Outlined.EventRepeat,
                    contentDescription = null,
                    tint = kb.secondaryText,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(10.dp),
                )
            }
        }
        if (length >= TIME_LINE_MINUTES || lifted) {
            val end = start + (placed.end - placed.start)
            Text(
                "${CalendarText.time(Math.floorMod(start, MINUTES_PER_DAY), hour24)} – ${CalendarText.time(Math.floorMod(end, MINUTES_PER_DAY), hour24)}",
                color = if (lifted) kb.suggestionText else kb.secondaryText,
                fontSize = 10.sp,
                fontWeight = if (lifted) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [minutes] to the nearest quarter hour. */
private fun snap(minutes: Int): Int = (minutes.toFloat() / SNAP_MINUTES).roundToInt() * SNAP_MINUTES

private val HOUR_HEIGHT = 48.dp
private val LABEL_WIDTH = 40.dp
private const val MINUTES_PER_DAY = 24 * 60
private const val MINUTE_MS = 60_000L
private const val SNAP_MINUTES = 15

/** The shortest a block is drawn, so a zero-length event can still be pressed. */
private const val MIN_BLOCK_MINUTES = 20

/** A block this long has room for its times under the title. */
private const val TIME_LINE_MINUTES = 40

/** Where an empty day opens, other than today: the morning. */
private const val DEFAULT_SCROLL_MINUTE = 8 * 60
