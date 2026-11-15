package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.icu.util.MeasureUnit
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.clock.ClockConfig
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.DateMath
import com.wasimaster.wmkeyboard.core.clock.DayPart
import com.wasimaster.wmkeyboard.core.clock.DiscordStyle
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.clock.MeetingPlanner
import com.wasimaster.wmkeyboard.core.clock.PlannerDay
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import java.util.TimeZone
import com.wasimaster.wmkeyboard.ime.R as ImeR

/*
 * The Clock screen's meeting planner and date maths: the same two the
 * keyboard panel has, drawn with the app's own parts. The panel types its
 * answers into the field; here there is no field, so they are copied.
 */

// ---- Meeting planner ----------------------------------------------------------

@Composable
internal fun PlannerSection(config: ClockConfig, hour24: Boolean) {
    val context = LocalContext.current
    if (config.zones.isEmpty()) {
        CaptionText(stringResource(ImeR.string.ime_clock_planner_empty_body))
        return
    }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val now = remember { System.currentTimeMillis() }
    val home = TimeZone.getDefault()
    val zones = remember(config.zones) { listOf(home) + config.zones.map { TimeZone.getTimeZone(it.zoneId) } }
    val names = remember(config.zones) {
        listOf(IcuZoneNames().city(home.id) ?: ZoneCatalog.fallbackCity(home.id)) +
            config.zones.map { z -> z.label.ifEmpty { IcuZoneNames().city(z.zoneId) ?: ZoneCatalog.fallbackCity(z.zoneId) } }
    }
    val dayStart = now + dayOffset * DAY_MS
    val plan = remember(dayOffset, zones, config.workStartMinutes, config.workEndMinutes) {
        MeetingPlanner.plan(dayStart, home, zones, workStart = config.workStartMinutes, workEnd = config.workEndMinutes)
    }
    val current = if (dayOffset == 0) ((now - plan.slots.first()) / PlannerDay.SLOT_MS).toInt() else null
    val slot = (picked ?: current ?: plan.best ?: 0).coerceIn(0, PlannerDay.SLOTS - 1)
    val at = plan.slots[slot]
    val overlap = remember(plan) { plan.overlap.toSet() }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            dayOffset -= 1
            picked = null
        }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(ImeR.string.ime_clock_previous_day_desc)) }
        Text(
            when (dayOffset) {
                0 -> stringResource(ImeR.string.ime_clock_today)
                1 -> stringResource(ImeR.string.ime_clock_tomorrow)
                -1 -> stringResource(ImeR.string.ime_clock_yesterday)
                else -> ClockFormats.longDate(dayStart, home)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {
            dayOffset += 1
            picked = null
        }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(ImeR.string.ime_clock_next_day_desc)) }
    }
    CaptionText(
        if (overlap.isEmpty()) {
            stringResource(ImeR.string.ime_clock_planner_no_overlap)
        } else {
            stringResource(ImeR.string.ime_clock_planner_overlap, ClockText.duration(overlap.size * PlannerDay.SLOT_MS))
        },
    )
    SettingsGroup {
        zones.forEachIndexed { row, zone ->
            item(key = "planner-$row") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            names.getOrElse(row) { "" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            ClockFormats.time(at, zone, hour24) + dayMark(at, zone, home),
                            style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                        )
                    }
                    PlannerStrip(
                        parts = plan.parts[row],
                        overlap = overlap,
                        cursor = slot,
                        onSlot = { picked = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(STRIP_HEIGHT),
                    )
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { plan.best?.let { picked = it } }) { Text(stringResource(ImeR.string.ime_clock_best_time)) }
        OutlinedButton(onClick = {
            copy(context, ClockFormats.conversionLine(at, names.zip(zones), hour24))
        }) { Text(stringResource(R.string.clock_copy_times)) }
        OutlinedButton(onClick = {
            copy(context, ClockFormats.discord(at, DiscordStyle.of(config.discordStyle) ?: DiscordStyle.LONG_DATE_TIME))
        }) { Text(stringResource(R.string.clock_copy_discord)) }
    }
}

/** One place's day in half hours, shaded work, awake and night; a tap or a drag picks the time. */
@Composable
private fun PlannerStrip(parts: List<DayPart>, overlap: Set<Int>, cursor: Int, onSlot: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val night = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
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
    val description = stringResource(ImeR.string.ime_clock_planner_strip_desc)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures { move(it.x, size.width) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { move(it.x, size.width) }) { change, _ -> move(change.position.x, size.width) }
            },
    ) {
        val cell = size.width / PlannerDay.SLOTS
        val gap = 0.6.dp.toPx()
        parts.forEachIndexed { i, part ->
            val color = when (part) {
                DayPart.WORK -> accent.copy(alpha = if (i in overlap) 0.95f else 0.5f)
                DayPart.AWAKE -> accent.copy(alpha = 0.18f)
                DayPart.NIGHT -> night.copy(alpha = 0.12f)
            }
            drawRoundRect(color, Offset(i * cell + gap / 2, 0f), Size(cell - gap, size.height), CornerRadius(2.dp.toPx()))
        }
        val x = cursor * cell + cell / 2
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

private fun dayMark(at: Long, zone: TimeZone, home: TimeZone): String =
    when (ClockFormats.dayShift(at, zone, home)) {
        1 -> " +1"
        -1 -> " −1"
        else -> ""
    }

// ---- Date maths ---------------------------------------------------------------

@Composable
internal fun DateMathsSection() {
    val context = LocalContext.current
    val zone = TimeZone.getDefault()
    val today = remember { LocalDay.of(System.currentTimeMillis(), zone) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(today.epochDay) }
    var to by rememberSaveable { mutableStateOf(today.epochDay) }
    var base by rememberSaveable { mutableStateOf(today.epochDay) }
    var amount by rememberSaveable { mutableIntStateOf(0) }
    var unit by rememberSaveable { mutableStateOf(DateMath.Unit.DAYS) }

    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        listOf(ImeR.string.ime_clock_dates_between, ImeR.string.ime_clock_dates_add).forEachIndexed { i, label ->
            SegmentedButton(
                selected = adding == (i == 1),
                onClick = { adding = i == 1 },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(stringResource(label)) }
        }
    }
    if (!adding) {
        val a = LocalDay.ofEpochDay(from)
        val b = LocalDay.ofEpochDay(to)
        val d = DateMath.between(a, b)
        SettingsGroup {
            item(key = "from") { DateStepperRow(stringResource(ImeR.string.ime_clock_from), a) { from = it.epochDay } }
            item(key = "swap") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = {
                        val t = from
                        from = to
                        to = t
                    }) { Icon(Icons.Outlined.SwapVert, stringResource(ImeR.string.ime_clock_swap_desc)) }
                }
            }
            item(key = "to") { DateStepperRow(stringResource(ImeR.string.ime_clock_to), b) { to = it.epochDay } }
        }
        val days = kotlin.math.abs(d.days)
        val breakdown = listOfNotNull(
            d.years.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.YEAR) },
            d.months.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.MONTH) },
            d.restDays.takeIf { it > 0 && (d.years > 0 || d.months > 0) }?.let { ClockText.amount(it.toLong(), MeasureUnit.DAY) },
        ).joinToString(", ")
        AnswerRows(
            listOf(
                stringResource(ImeR.string.ime_clock_tile_days) to listOf(days.toString(), breakdown).filter { it.isNotEmpty() }.joinToString(" · "),
                stringResource(ImeR.string.ime_clock_tile_weeks) to
                    (kotlin.math.abs(d.weeks).toString() + (kotlin.math.abs(d.weekRest).takeIf { it > 0 }?.let { " + " + ClockText.amount(it, MeasureUnit.DAY) } ?: "")),
                stringResource(ImeR.string.ime_clock_tile_workdays) to "${kotlin.math.abs(d.workdays)} (${stringResource(ImeR.string.ime_clock_tile_mon_fri)})",
            ),
        )
        val text = context.resources.getQuantityString(
            ImeR.plurals.ime_clock_dates_between_text, days.toInt(),
            ClockFormats.longDate(a.startMillis(zone), zone),
            ClockFormats.longDate(b.startMillis(zone), zone),
            days.toInt(),
        )
        CopyRow { copy(context, text) }
    } else {
        val start = LocalDay.ofEpochDay(base)
        val result = DateMath.add(start, amount.toLong(), unit)
        SettingsGroup {
            item(key = "base") { DateStepperRow(stringResource(ImeR.string.ime_clock_start_date), start) { base = it.epochDay } }
            item(key = "amount") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { amount -= 10 }) { Icon(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(ImeR.string.ime_clock_less_ten_desc)) }
                        IconButton(onClick = { amount -= 1 }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(ImeR.string.ime_clock_less_one_desc)) }
                        Text(
                            (if (amount > 0) "+" else "") + amount,
                            style = MaterialTheme.typography.headlineSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        IconButton(onClick = { amount += 1 }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(ImeR.string.ime_clock_more_one_desc)) }
                        IconButton(onClick = { amount += 10 }) { Icon(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(ImeR.string.ime_clock_more_ten_desc)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DateMath.Unit.entries.forEach { u ->
                            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(stringResource(unitLabel(u))) })
                        }
                    }
                }
            }
        }
        val answer = ClockFormats.longDate(result.startMillis(zone), zone)
        AnswerRows(
            listOf(
                answer to ClockFormats.weekday(result.startMillis(zone), zone),
                stringResource(ImeR.string.ime_clock_tile_week) to "${result.isoWeek} ${stringResource(ImeR.string.ime_clock_tile_of_year)}",
                stringResource(ImeR.string.ime_clock_tile_day) to "${result.dayOfYear} ${stringResource(ImeR.string.ime_clock_tile_of_year)}",
            ),
        )
        CopyRow { copy(context, answer) }
    }
}

/** A date with steppers: a month back, a day back, the date (a tap goes to today), a day on, a month on. */
@Composable
private fun DateStepperRow(label: String, day: LocalDay, onChange: (LocalDay) -> Unit) {
    val zone = TimeZone.getDefault()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp).padding(start = 8.dp), maxLines = 1)
        IconButton(onClick = { onChange(day.plusMonths(-1)) }) { Icon(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(ImeR.string.ime_clock_month_back_desc)) }
        IconButton(onClick = { onChange(day.plusDays(-1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(ImeR.string.ime_clock_day_back_desc)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ClockFormats.shortDate(day.startMillis(zone), zone), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(day.year.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onChange(day.plusDays(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(ImeR.string.ime_clock_day_on_desc)) }
        IconButton(onClick = { onChange(day.plusMonths(1)) }) { Icon(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(ImeR.string.ime_clock_month_on_desc)) }
    }
}

@Composable
private fun AnswerRows(rows: List<Pair<String, String>>) {
    SettingsGroup {
        rows.forEachIndexed { i, (title, value) ->
            item(key = "answer-$i") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CopyRow(onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilledTonalButton(onClick = onCopy) { Text(stringResource(R.string.clock_copy_answer)) }
    }
}

private fun unitLabel(unit: DateMath.Unit): Int = when (unit) {
    DateMath.Unit.DAYS -> ImeR.string.ime_clock_unit_days
    DateMath.Unit.WEEKS -> ImeR.string.ime_clock_unit_weeks
    DateMath.Unit.MONTHS -> ImeR.string.ime_clock_unit_months
    DateMath.Unit.YEARS -> ImeR.string.ime_clock_unit_years
    DateMath.Unit.WORKDAYS -> ImeR.string.ime_clock_unit_workdays
}

private fun copy(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("", text))
    // Android 13 and later show their own copy confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.clock_copied, Toast.LENGTH_SHORT).show()
    }
}

private const val DAY_MS = 86_400_000L
private val STRIP_HEIGHT = 20.dp
