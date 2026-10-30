package com.wasimaster.wmkeyboard.ime.ui.clock

import android.icu.util.MeasureUnit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Countdown
import com.wasimaster.wmkeyboard.core.clock.DateMath
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.clock.DateField
import com.wasimaster.wmkeyboard.ime.clock.DatesMode
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.SegmentedPill
import com.wasimaster.wmkeyboard.ime.ui.kit.StatTile
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import java.util.TimeZone

/**
 * Date maths without typing a date: steppers move a day or a month at a
 * time, and the answer updates as they move, as tiles. Between two dates, a
 * date plus or minus some days, weeks, months or working days, and
 * countdowns to the days that matter as a grid of cards. The three sit on
 * one pill, and the bar along the bottom types the answer.
 */
@Composable
internal fun DatesTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNow(60_000L)
    val today = ClockController.today(now)
    val mode = ui.dates.mode
    val adding = ui.dates.newCountdown != null
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SegmentedPill(
                labels = listOf(
                    stringResource(R.string.ime_clock_dates_between),
                    stringResource(R.string.ime_clock_dates_add),
                    if (ui.data.countdowns.isEmpty()) {
                        stringResource(R.string.ime_clock_dates_countdowns)
                    } else {
                        stringResource(R.string.ime_clock_dates_countdowns_count, ui.data.countdowns.size)
                    },
                ),
                selected = mode.ordinal,
                onSelect = { act(ClockAction.DatesModeSet(DatesMode.entries[it])) },
                segmentWidth = SEGMENT_WIDTH,
            )
        }
        Spacer(Modifier.height(6.dp))
        when (mode) {
            DatesMode.BETWEEN -> Between(ui, today, animate, act, Modifier.weight(1f))
            DatesMode.ADD -> AddDays(ui, today, animate, act, Modifier.weight(1f))
            DatesMode.COUNTDOWNS -> Countdowns(ui, today, animate, act, Modifier.weight(1f))
        }
        ActionBar(
            when {
                mode != DatesMode.COUNTDOWNS -> listOf(
                    KitAction(stringResource(R.string.ime_clock_insert), Icons.Outlined.ContentPaste, primary = true) { act(ClockAction.DatesInsert) },
                )
                adding -> listOf(
                    KitAction(stringResource(R.string.ime_clock_save), Icons.Outlined.Check, primary = true) { act(ClockAction.SaveCountdown) },
                    KitAction(stringResource(R.string.ime_clock_cancel), Icons.Outlined.Close) { act(ClockAction.CancelCountdown) },
                )
                else -> listOf(
                    KitAction(stringResource(R.string.ime_clock_new_countdown), Icons.Outlined.Add, primary = true) { act(ClockAction.NewCountdown) },
                )
            },
            modifier = Modifier.padding(vertical = 4.dp),
            height = 36.dp,
        )
    }
}

@Composable
private fun Between(ui: ClockUi, today: LocalDay, animate: Boolean, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val from = ui.dates.from ?: today
    val to = ui.dates.to ?: today
    val d = DateMath.between(from, to)
    Column(modifier.verticalScroll(rememberScrollState())) {
        DateStepper(stringResource(R.string.ime_clock_from), from, DateField.FROM, act)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.SwapVert, stringResource(R.string.ime_clock_swap_desc), size = 26.dp) { act(ClockAction.DatesSwap) }
            Spacer(Modifier.weight(1f))
        }
        DateStepper(stringResource(R.string.ime_clock_to), to, DateField.TO, act)
        Spacer(Modifier.height(8.dp))
        val breakdown = listOfNotNull(
            d.years.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.YEAR) },
            d.months.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.MONTH) },
            d.restDays.takeIf { it > 0 && (d.years > 0 || d.months > 0) }?.let { ClockText.amount(it.toLong(), MeasureUnit.DAY) },
        ).joinToString(", ")
        val days = kotlin.math.abs(d.days)
        val weeks = kotlin.math.abs(d.weeks)
        val restDays = days - weeks * 7
        // The answer pops in again whenever a date moves.
        val key = from.epochDay * 31 + to.epochDay
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(
                label = stringResource(R.string.ime_clock_tile_days),
                value = days.toString(),
                unit = breakdown,
                modifier = Modifier.weight(1f),
                index = 0,
                animate = animate,
                popKey = key,
            )
            StatTile(
                label = stringResource(R.string.ime_clock_tile_weeks),
                value = weeks.toString(),
                unit = if (restDays > 0) "+ " + ClockText.amount(restDays, MeasureUnit.DAY) else "",
                modifier = Modifier.weight(1f),
                index = 1,
                animate = animate,
                popKey = key,
            )
            StatTile(
                label = stringResource(R.string.ime_clock_tile_workdays),
                value = kotlin.math.abs(d.workdays).toString(),
                unit = stringResource(R.string.ime_clock_tile_mon_fri),
                modifier = Modifier.weight(1f),
                index = 2,
                animate = animate,
                popKey = key,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AddDays(ui: ClockUi, today: LocalDay, animate: Boolean, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val base = ui.dates.base ?: today
    val result = DateMath.add(base, ui.dates.amount.toLong(), ui.dates.unit)
    val zone = TimeZone.getDefault()
    Column(modifier.verticalScroll(rememberScrollState())) {
        DateStepper(stringResource(R.string.ime_clock_start_date), base, DateField.BASE, act)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(R.string.ime_clock_less_ten_desc), size = 28.dp) { act(ClockAction.AddAmount(-10)) }
            Spacer(Modifier.width(4.dp))
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_less_one_desc), size = 28.dp) { act(ClockAction.AddAmount(-1)) }
            Text(
                (if (ui.dates.amount > 0) "+" else "") + ui.dates.amount,
                color = kb.suggestionText,
                style = digits(20.sp, FontWeight.Medium),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_more_one_desc), size = 28.dp) { act(ClockAction.AddAmount(1)) }
            Spacer(Modifier.width(4.dp))
            RoundButton(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(R.string.ime_clock_more_ten_desc), size = 28.dp) { act(ClockAction.AddAmount(10)) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DateMath.Unit.entries.forEach { unit ->
                    ToolPanelChip(stringResource(unitLabel(unit)), selected = ui.dates.unit == unit) { act(ClockAction.AddUnit(unit)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val key = result.epochDay
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ClockCard(
                Modifier
                    .weight(2f)
                    .popIn(0, animate, key),
                padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    ClockFormats.longDate(result.startMillis(zone), zone),
                    color = kb.suggestionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(ClockFormats.weekday(result.startMillis(zone), zone), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
            }
            StatTile(
                label = stringResource(R.string.ime_clock_tile_week),
                value = result.isoWeek.toString(),
                unit = stringResource(R.string.ime_clock_tile_of_year),
                modifier = Modifier.weight(1f),
                index = 1,
                animate = animate,
                popKey = key,
            )
            StatTile(
                label = stringResource(R.string.ime_clock_tile_day),
                value = result.dayOfYear.toString(),
                unit = stringResource(R.string.ime_clock_tile_of_year),
                modifier = Modifier.weight(1f),
                index = 2,
                animate = animate,
                popKey = key,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Countdowns(ui: ClockUi, today: LocalDay, animate: Boolean, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val adding = ui.dates.newCountdown
    if (adding != null) {
        Column(modifier) {
            DateStepper(stringResource(R.string.ime_clock_countdown_date), adding, DateField.COUNTDOWN, act)
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(
                    R.plurals.ime_clock_days_from_today,
                    (adding.epochDay - today.epochDay).toInt(),
                    (adding.epochDay - today.epochDay).toInt(),
                ),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        return
    }
    if (ui.data.countdowns.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Event,
            title = stringResource(R.string.ime_clock_countdowns_empty_title),
            body = stringResource(R.string.ime_clock_countdowns_empty_body),
            modifier = modifier,
        )
        return
    }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(ui.data.countdowns, key = { _, c -> c.id }) { i, c ->
            CountdownCard(
                c = c,
                today = today,
                menuOpen = menuFor == c.id,
                index = i,
                animate = animate,
                onHold = { menuFor = if (menuFor == c.id) null else c.id },
                act = act,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** A day counted towards: its name, the days left large, the date; a hold offers Delete. */
@Composable
private fun CountdownCard(
    c: Countdown,
    today: LocalDay,
    menuOpen: Boolean,
    index: Int,
    animate: Boolean,
    onHold: () -> Unit,
    act: (ClockAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val day = LocalDay.of(c.target, zone)
    val days = (day.epochDay - today.epochDay).toInt()
    ClockCard(
        modifier = modifier
            .fillMaxWidth()
            .popIn(index, animate, key = c.id),
        highlighted = menuOpen,
        padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        onClick = { act(ClockAction.InsertCountdown(c.id)) },
        onLongClick = onHold,
    ) {
        Text(c.title, color = kb.suggestionText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                kotlin.math.abs(days).toString(),
                color = if (days == 0) kb.accent else kb.suggestionText,
                style = digits(26.sp, FontWeight.Medium),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                when {
                    days > 0 -> pluralStringResource(R.plurals.ime_clock_days_left, days)
                    days < 0 -> pluralStringResource(R.plurals.ime_clock_days_ago, -days)
                    else -> stringResource(R.string.ime_clock_today)
                },
                color = kb.secondaryText,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Text(ClockFormats.longDate(c.target, zone), color = kb.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AnimatedVisibility(visible = menuOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                PillButton(stringResource(R.string.ime_clock_delete), icon = Icons.Outlined.Delete, height = 28.dp) {
                    act(ClockAction.DeleteCountdown(c.id))
                }
            }
        }
    }
}

/**
 * One date with steppers: a month back, a day back, the date (tap for
 * today, press and hold to type one), a day on, a month on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateStepper(label: String, day: LocalDay, field: DateField, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.width(54.dp), maxLines = 1)
        RoundButton(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(R.string.ime_clock_month_back_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 0, -1))
        }
        Spacer(Modifier.width(2.dp))
        RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_day_back_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, -1))
        }
        Column(
            Modifier
                .weight(1f)
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.ime_clock_date_type_desc),
                    onLongClick = { act(ClockAction.DateType(field)) },
                ) { act(ClockAction.DateToday(field)) }
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ClockFormats.shortDate(day.startMillis(zone), zone),
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(day.year.toString(), color = kb.secondaryText, fontSize = 10.sp)
        }
        RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_day_on_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 1))
        }
        Spacer(Modifier.width(2.dp))
        RoundButton(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(R.string.ime_clock_month_on_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 0, 1))
        }
    }
}

private fun unitLabel(unit: DateMath.Unit): Int = when (unit) {
    DateMath.Unit.DAYS -> R.string.ime_clock_unit_days
    DateMath.Unit.WEEKS -> R.string.ime_clock_unit_weeks
    DateMath.Unit.MONTHS -> R.string.ime_clock_unit_months
    DateMath.Unit.YEARS -> R.string.ime_clock_unit_years
    DateMath.Unit.WORKDAYS -> R.string.ime_clock_unit_workdays
}

/** A segment of the Between | Add | Countdowns pill; three of them fit a phone with room over. */
private val SEGMENT_WIDTH = 108.dp
