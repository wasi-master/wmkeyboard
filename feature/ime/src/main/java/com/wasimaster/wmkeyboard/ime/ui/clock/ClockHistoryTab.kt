package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.HistoryEntry
import com.wasimaster.wmkeyboard.core.clock.HistoryKind
import com.wasimaster.wmkeyboard.core.clock.HistoryOutcome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.kit.StatTile
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import java.util.TimeZone

/**
 * What rang and what was timed, newest first, on a timeline: a rail down the
 * left names each day (Today, Yesterday, then dates) and the day's entries
 * are compact cards beside it, each with its kind, its name, its length and
 * its time. Today's focus and timed totals are tiles on top. A stopwatch
 * run opens to show its laps; any line can be typed into the field.
 */
@Composable
internal fun HistoryTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNow(60_000L)
    val zone = TimeZone.getDefault()
    val filter = ui.historyFilter
    val entries = remember(ui.data.history, filter) { ui.data.history.filter { filter == null || it.kind == filter } }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_clock_all), selected = filter == null) { act(ClockAction.HistoryFilter(null)) }
                HistoryKind.entries.forEach { k ->
                    ToolPanelChip(stringResource(ClockController.kindRes(k)), selected = filter == k) {
                        act(ClockAction.HistoryFilter(if (filter == k) null else k))
                    }
                }
            }
            if (entries.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                RoundButton(Icons.Outlined.DeleteSweep, stringResource(R.string.ime_clock_clear_history), size = 30.dp) {
                    act(ClockAction.HistoryClear)
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.ime_clock_history_empty_title),
                body = stringResource(R.string.ime_clock_history_empty_body),
            )
            return@Column
        }
        TodayTiles(ui.data.history, now, animate)
        val grouped = remember(entries) { entries.groupBy { ClockFormats.dayKey(it.at, zone) } }
        val today = ClockFormats.dayKey(now, zone)
        LazyColumn(
            contentPadding = PaddingValues(top = 4.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            grouped.entries.forEachIndexed { g, (day, rows) ->
                item(key = "day-$day") {
                    Row(
                        Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        DayRail(
                            label = when (day) {
                                today -> stringResource(R.string.ime_clock_today)
                                today - 1 -> stringResource(R.string.ime_clock_yesterday)
                                else -> ClockFormats.shortDate(rows.first().at, zone)
                            },
                            current = day == today,
                            first = g == 0,
                            last = g == grouped.size - 1,
                            modifier = Modifier
                                .width(RAIL_WIDTH)
                                .fillMaxHeight(),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            rows.forEachIndexed { i, e ->
                                HistoryRow(e, ui, open = e.id in ui.historyOpen, index = i.coerceAtMost(MAX_STAGGER), animate = animate, act = act)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Today's focus time and how long was timed, the two numbers people check, as tiles. */
@Composable
private fun TodayTiles(history: List<HistoryEntry>, now: Long, animate: Boolean) {
    val zone = TimeZone.getDefault()
    val today = ClockFormats.dayKey(now, zone)
    val todays = history.filter { ClockFormats.dayKey(it.at, zone) == today }
    val focus = todays.filter { it.kind == HistoryKind.POMODORO && it.outcome == HistoryOutcome.DONE }.sumOf { it.durationMs }
    val timed = todays.filter { it.kind == HistoryKind.TIMER || it.kind == HistoryKind.STOPWATCH }.sumOf { it.durationMs }
    if (focus == 0L && timed == 0L) return
    val unit = stringResource(R.string.ime_clock_today).lowercase()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (focus > 0) {
            StatTile(stringResource(R.string.ime_clock_tile_focus), ClockText.duration(focus), unit, Modifier.weight(1f), index = 0, animate = animate)
        }
        if (timed > 0) {
            StatTile(stringResource(R.string.ime_clock_tile_timed), ClockText.duration(timed), unit, Modifier.weight(1f), index = 1, animate = animate)
        }
    }
}

/**
 * One day on the rail: its name at the top, in the accent for today, and a
 * line down the middle that joins it to the days before and after.
 */
@Composable
private fun DayRail(label: String, current: Boolean, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val line = kb.divider
    val dot = if (current) kb.accent else kb.secondaryText.copy(alpha = 0.5f)
    Box(
        modifier.drawBehind {
            val x = size.width / 2f
            val top = if (first) RAIL_DOT_Y.toPx() else 0f
            val bottom = if (last) size.height * 0.6f else size.height + RAIL_JOIN.toPx()
            drawLine(line, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(dot, 3.dp.toPx(), Offset(x, RAIL_DOT_Y.toPx()))
        },
    ) {
        Text(
            label.uppercase(),
            color = if (current) kb.accent else kb.secondaryText,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = RAIL_DOT_Y + 6.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, ui: ClockUi, open: Boolean, index: Int, animate: Boolean, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val expandable = e.kind == HistoryKind.STOPWATCH && e.laps.isNotEmpty()
    ClockCard(
        modifier = Modifier
            .fillMaxWidth()
            .popIn(index, animate, key = e.id),
        padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        onClick = if (expandable) ({ act(ClockAction.HistoryToggle(e.id)) }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(kb.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(e.kind), contentDescription = null, tint = kb.accent, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.label.ifEmpty { stringResource(ClockController.kindRes(e.kind)) },
                    color = kb.suggestionText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    e.durationMs.takeIf { it > 0 }?.let { ClockText.duration(it, seconds = true) },
                    outcomeLabel(e.outcome),
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        color = if (e.outcome == HistoryOutcome.MISSED) kb.accent else kb.secondaryText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(ClockFormats.time(e.at, zone, ui.hour24), color = kb.secondaryText, style = digits(10.sp, FontWeight.Normal), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Outlined.ContentPaste, stringResource(R.string.ime_clock_insert), size = 26.dp) {
                act(ClockAction.HistoryInsert(e.id))
            }
        }
        AnimatedVisibility(visible = open && expandable) {
            Column(Modifier.padding(start = 34.dp, top = 4.dp)) {
                val lengths = e.laps.mapIndexed { i, at -> at - (if (i == 0) 0 else e.laps[i - 1]) }
                lengths.forEachIndexed { i, len ->
                    Row {
                        Text(stringResource(R.string.ime_clock_lap_number, i + 1), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Text(ClockFormats.stopwatch(len), color = kb.suggestionText, style = digits(11.sp, FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: HistoryOutcome): String? = when (outcome) {
    HistoryOutcome.DONE -> null
    HistoryOutcome.SNOOZED -> stringResource(R.string.ime_clock_outcome_snoozed)
    HistoryOutcome.MISSED -> stringResource(R.string.ime_clock_outcome_missed)
    HistoryOutcome.CANCELLED -> stringResource(R.string.ime_clock_outcome_stopped)
}

private fun kindIcon(kind: HistoryKind): ImageVector = when (kind) {
    HistoryKind.ALARM -> Icons.Outlined.Alarm
    HistoryKind.TIMER -> Icons.Outlined.HourglassBottom
    HistoryKind.STOPWATCH -> Icons.Outlined.Timer
    HistoryKind.POMODORO -> Icons.Outlined.Psychology
}

/** The day rail down the left of the timeline. */
private val RAIL_WIDTH = 58.dp

/** Where the day's dot sits on the rail; the name hangs under it. */
private val RAIL_DOT_Y = 10.dp

/** How far the line runs past a day, into the gap before the next. */
private val RAIL_JOIN = 6.dp

/** Rows further down a day than this pop in with the last beat, not later. */
private const val MAX_STAGGER = 6
