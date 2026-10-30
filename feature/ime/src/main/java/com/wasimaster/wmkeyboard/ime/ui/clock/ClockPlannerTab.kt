package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockTab
import com.wasimaster.wmkeyboard.core.clock.DayPart
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.MeetingPlanner
import com.wasimaster.wmkeyboard.core.clock.PlannerDay
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.StatTile
import java.util.TimeZone

/**
 * The meeting planner: the day's arrows and name on top, three tiles with
 * what the day offers (how long everyone is at work, the moment picked, how
 * many are at work then), and one strip per place across the same 24 hours,
 * shaded by working hours, awake hours and night, with the good hours lit
 * as a band down every strip. Drag along the strips and every place's time
 * follows the finger. The bar types the moment in all of them at once, or
 * as a Discord timestamp each reader sees in their own zone, and jumps to
 * the best time.
 */
@Composable
internal fun PlannerTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNow(60_000L)
    val home = TimeZone.getDefault()
    if (ui.config.zones.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Group,
            title = stringResource(R.string.ime_clock_planner_empty_title),
            body = stringResource(R.string.ime_clock_planner_empty_body),
        ) {
            PillButton(stringResource(R.string.ime_clock_add_city), primary = true) {
                act(ClockAction.Tab(ClockTab.CLOCKS))
                act(ClockAction.SearchOpen)
            }
        }
        return
    }
    val dayStart = now + ui.planner.dayOffset * DAY_MS
    val plan = remember(dayStart / SLOT_BUCKET, ui.config) {
        MeetingPlanner.plan(
            dayStart, home,
            listOf(home) + ui.config.zones.map { TimeZone.getTimeZone(it.zoneId) },
            workStart = ui.config.workStartMinutes, workEnd = ui.config.workEndMinutes,
        )
    }
    val currentSlot = if (ui.planner.dayOffset == 0) ((now - plan.slots.first()) / PlannerDay.SLOT_MS).toInt() else null
    val slot = ui.planner.slot ?: currentSlot ?: plan.best ?: 0
    val at = plan.slots.getOrElse(slot) { now }
    val overlap = remember(plan) { plan.overlap.toSet() }
    val names = remember(ui.config.zones) {
        listOf(IcuZoneNames().city(home.id) ?: ZoneCatalog.fallbackCity(home.id)) +
            ui.config.zones.map { z -> z.label.ifEmpty { IcuZoneNames().city(z.zoneId) ?: ZoneCatalog.fallbackCity(z.zoneId) } }
    }
    val zones = remember(ui.config.zones) { listOf(home) + ui.config.zones.map { TimeZone.getTimeZone(it.zoneId) } }
    val atWork = plan.parts.count { it.getOrNull(slot) == DayPart.WORK }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_previous_day_desc), size = 30.dp) {
                act(ClockAction.PlannerDay(-1))
            }
            Text(
                dayTitle(ui.planner.dayOffset, dayStart, home),
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_next_day_desc), size = 30.dp) {
                act(ClockAction.PlannerDay(1))
            }
        }
        Spacer(Modifier.height(4.dp))
        // What the day offers, as tiles; a new day pops them in again.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(
                label = stringResource(R.string.ime_clock_tile_overlap),
                value = if (overlap.isEmpty()) "—" else ClockText.duration(overlap.size * PlannerDay.SLOT_MS),
                unit = stringResource(if (overlap.isEmpty()) R.string.ime_clock_tile_none else R.string.ime_clock_tile_all_at_work),
                modifier = Modifier.weight(1f),
                index = 0,
                animate = animate,
                popKey = ui.planner.dayOffset,
            )
            StatTile(
                label = stringResource(R.string.ime_clock_tile_picked),
                value = ClockFormats.time(at, home, ui.hour24),
                unit = names.firstOrNull().orEmpty(),
                modifier = Modifier.weight(1f),
                index = 1,
                animate = animate,
                popKey = ui.planner.dayOffset,
            )
            StatTile(
                label = stringResource(R.string.ime_clock_tile_at_work),
                value = "$atWork/${plan.parts.size}",
                unit = stringResource(R.string.ime_clock_tile_places),
                modifier = Modifier.weight(1f),
                index = 2,
                animate = animate,
                popKey = ui.planner.dayOffset,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.width(NAME_WIDTH), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                plan.parts.indices.forEach { row ->
                    Column(Modifier.height(ROW_HEIGHT), verticalArrangement = Arrangement.Center) {
                        Text(
                            names.getOrElse(row) { "" },
                            color = kb.suggestionText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ClockFormats.time(at, zones[row], ui.hour24) + dayMark(at, zones[row], home),
                            color = kb.secondaryText,
                            style = digits(11.sp, FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                GoodHours(overlap, Modifier.matchParentSize())
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    plan.parts.forEach { parts ->
                        Box(Modifier.height(ROW_HEIGHT), contentAlignment = Alignment.Center) {
                            Strip(
                                parts = parts,
                                overlap = overlap,
                                cursor = slot,
                                onSlot = { act(ClockAction.PlannerSlot(it)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(22.dp),
                            )
                        }
                    }
                }
            }
        }
        ActionBar(
            listOf(
                KitAction(stringResource(R.string.ime_clock_insert), Icons.Outlined.ContentPaste, primary = true) { act(ClockAction.PlannerInsert) },
                KitAction(stringResource(R.string.ime_clock_format_discord)) { act(ClockAction.PlannerDiscord) },
                KitAction(stringResource(R.string.ime_clock_best_time), Icons.Outlined.AutoAwesome) { act(ClockAction.PlannerBest) },
            ),
            modifier = Modifier.padding(vertical = 4.dp),
            height = 36.dp,
        )
    }
}

/** The good hours: a band of accent at low alpha down every strip, where everyone is at work. */
@Composable
private fun GoodHours(overlap: Set<Int>, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val band = kb.accent.copy(alpha = 0.12f)
    Canvas(modifier) {
        if (overlap.isEmpty()) return@Canvas
        val cell = size.width / PlannerDay.SLOTS
        val bleed = 2.dp.toPx()
        // Neighbouring slots run together into one band.
        var start = -1
        for (i in 0..PlannerDay.SLOTS) {
            val good = i < PlannerDay.SLOTS && i in overlap
            if (good && start < 0) start = i
            if (!good && start >= 0) {
                drawRoundRect(
                    band,
                    topLeft = Offset(start * cell, -bleed),
                    size = Size((i - start) * cell, size.height + bleed * 2),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                start = -1
            }
        }
    }
}

/** Half-hour cells; a drag or a tap moves the cursor, with a tick at each cell. */
@Composable
private fun Strip(
    parts: List<DayPart>,
    overlap: Set<Int>,
    cursor: Int,
    onSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val report by rememberUpdatedState(onSlot)
    val last = remember { intArrayOf(-1) }
    fun move(x: Float, width: Int) {
        val s = (x / width * PlannerDay.SLOTS).toInt().coerceIn(0, PlannerDay.SLOTS - 1)
        if (s != last[0]) {
            last[0] = s
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            report(s)
        }
    }
    val description = stringResource(R.string.ime_clock_planner_strip_desc)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { move(it.x, size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { move(it.x, size.width) }) { change, _ ->
                    move(change.position.x, size.width)
                }
            },
    ) {
        val cell = size.width / PlannerDay.SLOTS
        val gap = 0.6.dp.toPx()
        parts.forEachIndexed { i, part ->
            val color = when (part) {
                DayPart.WORK -> kb.accent.copy(alpha = if (i in overlap) 0.95f else 0.5f)
                DayPart.AWAKE -> kb.accent.copy(alpha = 0.18f)
                DayPart.NIGHT -> kb.secondaryText.copy(alpha = 0.10f)
            }
            drawRoundRect(
                color,
                topLeft = Offset(i * cell + gap / 2, 0f),
                size = Size(cell - gap, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        val x = cursor * cell + cell / 2
        drawLine(kb.suggestionText, Offset(x, -3.dp.toPx()), Offset(x, size.height + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun dayTitle(offset: Int, at: Long, zone: TimeZone): String = when (offset) {
    0 -> stringResource(R.string.ime_clock_today)
    1 -> stringResource(R.string.ime_clock_tomorrow)
    -1 -> stringResource(R.string.ime_clock_yesterday)
    else -> ClockFormats.shortDate(at, zone)
}

/** "+1" or "−1" after a time that falls on another day than at home. */
private fun dayMark(at: Long, zone: TimeZone, home: TimeZone): String =
    when (ClockFormats.dayShift(at, zone, home)) {
        1 -> " +1"
        -1 -> " −1"
        else -> ""
    }

private const val DAY_MS = 86_400_000L

/** Recompute the plan at most once a half hour, not every minute's tick. */
private const val SLOT_BUCKET = 30 * 60_000L

private val NAME_WIDTH = 96.dp
private val ROW_HEIGHT = 32.dp
private val ROW_GAP = 4.dp
