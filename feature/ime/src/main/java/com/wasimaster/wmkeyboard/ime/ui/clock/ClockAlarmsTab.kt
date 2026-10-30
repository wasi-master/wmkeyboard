package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Weekdays
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm as AlarmIcon
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipNext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.AlarmEdit
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alarms: a card per alarm with its time large, its label, the days it
 * repeats as seven pips and its switch; the one that rings next says how
 * long until it does. The quick ones ("In 20 min") are a chip row above the
 * bar, and New alarm is the bar's primary. A tap on a card opens the editor
 * in place; a hold offers Skip next and Delete.
 */
@Composable
internal fun AlarmsTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val edit = ui.alarmEdit
    if (edit != null) {
        AlarmEditor(edit, ui, act)
        return
    }
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNow(30_000L)
    val alarms = ui.data.alarms
    val next = remember(alarms, now) { AlarmMath.nextOfAll(alarms, now) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (alarms.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.AlarmIcon,
                title = stringResource(R.string.ime_clock_no_alarms_title),
                body = stringResource(R.string.ime_clock_no_alarms_body),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!ui.exactAllowed) {
                    item(key = "exact") {
                        Banner(stringResource(R.string.ime_clock_exact_banner), stringResource(R.string.ime_clock_allow)) {
                            act(ClockAction.AskExact)
                        }
                    }
                } else if (!ui.fullScreenAllowed) {
                    item(key = "full") {
                        Banner(stringResource(R.string.ime_clock_full_screen_banner), stringResource(R.string.ime_clock_allow)) {
                            act(ClockAction.AskFullScreen)
                        }
                    }
                } else if (!ui.notificationsAllowed) {
                    item(key = "notify") {
                        Banner(stringResource(R.string.ime_clock_notifications_banner), stringResource(R.string.ime_clock_open_app)) {
                            act(ClockAction.OpenApp)
                        }
                    }
                }
                itemsIndexed(alarms, key = { _, a -> a.id }) { i, alarm ->
                    AlarmCard(
                        alarm = alarm,
                        now = now,
                        ui = ui,
                        ringsAt = next?.takeIf { it.first.id == alarm.id }?.second,
                        menuOpen = menuFor == alarm.id,
                        index = i,
                        animate = animate,
                        onTap = {
                            menuFor = null
                            act(ClockAction.EditAlarm(alarm.id))
                        },
                        onHold = { menuFor = if (menuFor == alarm.id) null else alarm.id },
                        act = act,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        // The quick ones: an alarm some minutes from now, set with one tap.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QUICK_MINUTES.forEach { m ->
                ToolPanelChip(quickLabel(m)) { act(ClockAction.QuickAlarm(m)) }
            }
        }
        ActionBar(
            listOf(
                KitAction(stringResource(R.string.ime_clock_new_alarm), Icons.Outlined.Add, primary = true) { act(ClockAction.NewAlarm) },
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            height = 36.dp,
        )
    }
}

@Composable
private fun quickLabel(minutes: Int): String =
    stringResource(R.string.ime_clock_quick_in, ClockText.duration(minutes * 60_000L))

/**
 * One alarm: the time large (the hours in the text ink, am/pm small), the
 * pips and the label under it, the switch at the right. [ringsAt] is set on
 * the alarm that rings next, which then says how long until it does.
 */
@Composable
private fun AlarmCard(
    alarm: Alarm,
    now: Long,
    ui: ClockUi,
    ringsAt: Long?,
    menuOpen: Boolean,
    index: Int,
    animate: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    act: (ClockAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val at = remember(alarm.hour, alarm.minute) {
        Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
        }.timeInMillis
    }
    val dim = if (alarm.enabled) 1f else 0.5f
    val skipping = AlarmMath.isSkipping(alarm, now)
    val snoozed = alarm.snoozedUntil > now
    ClockCard(
        modifier = modifier
            .fillMaxWidth()
            .popIn(index, animate, key = alarm.id),
        highlighted = menuOpen,
        padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        onClick = onTap,
        onLongClick = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .alpha(dim),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ClockFormats.timeDigits(at, zone, ui.hour24), color = kb.suggestionText, style = digits(32.sp))
                    val ampm = ClockFormats.amPm(at, zone, ui.hour24)
                    if (ampm.isNotEmpty()) {
                        Text(ampm, color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    DayPips(alarm.days)
                    if (alarm.label.isNotEmpty()) {
                        if (alarm.days != 0) Spacer(Modifier.width(8.dp))
                        Text(
                            alarm.label,
                            color = kb.secondaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val note = when {
                    snoozed -> stringResource(R.string.ime_clock_snoozed_until, ClockFormats.time(alarm.snoozedUntil, zone, ui.hour24))
                    skipping -> stringResource(R.string.ime_clock_skipping_next)
                    !alarm.repeats && alarm.enabled -> AlarmMath.nextFire(alarm, now)?.let { onceLabel(it, now) }.orEmpty()
                    else -> ""
                }
                if (note.isNotEmpty()) {
                    Text(note, color = if (snoozed || skipping) kb.accent else kb.secondaryText, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            ClockSwitch(
                on = alarm.enabled,
                description = stringResource(R.string.ime_clock_alarm_switch_desc),
            ) { act(ClockAction.ToggleAlarm(alarm.id, it)) }
        }
        if (ringsAt != null && alarm.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(kb.accent),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.ime_clock_rings_in, ClockText.duration(ringsAt - now)),
                    color = kb.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        AnimatedVisibility(visible = menuOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                if (alarm.repeats && alarm.enabled) {
                    PillButton(
                        stringResource(if (skipping) R.string.ime_clock_unskip else R.string.ime_clock_skip_next),
                        icon = Icons.Outlined.SkipNext,
                        height = 32.dp,
                    ) { act(ClockAction.SkipAlarm(alarm.id)) }
                }
                PillButton(stringResource(R.string.ime_clock_delete), icon = Icons.Outlined.Delete, height = 32.dp) {
                    act(ClockAction.DeleteAlarm(alarm.id))
                }
            }
        }
    }
}

/** Seven round pips, Monday first, filled where the alarm rings; nothing for a one-off. */
@Composable
private fun DayPips(days: Int) {
    val kb = LocalKbTheme.current
    if (days == 0) return
    val letters = remember { weekdayLetters() }
    val description = daysDescription(days)
    val onInk = maxContrastOn(kb.accent)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        for (i in 0 until 7) {
            val on = Weekdays.has(days, i)
            Box(
                Modifier
                    .size(PIP)
                    .clip(CircleShape)
                    .background(if (on) kb.accent else kb.secondaryText.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letters[i],
                    color = if (on) onInk else kb.secondaryText.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun onceLabel(at: Long, now: Long): String {
    val zone = TimeZone.getDefault()
    val days = ClockFormats.dayKey(at, zone) - ClockFormats.dayKey(now, zone)
    return stringResource(
        when (days) {
            0L -> R.string.ime_clock_once_today
            1L -> R.string.ime_clock_once_tomorrow
            else -> R.string.ime_clock_once
        },
    )
}

/** Seven small letters, the ringing days lit; nothing for a one-off. */
@Composable
private fun DayDots(days: Int) {
    val kb = LocalKbTheme.current
    if (days == 0) return
    val letters = remember { weekdayLetters() }
    val description = daysDescription(days)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        for (i in 0 until 7) {
            val on = Weekdays.has(days, i)
            Text(
                letters[i],
                color = if (on) kb.accent else kb.secondaryText.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun daysDescription(days: Int): String = when (days) {
    Weekdays.ALL -> stringResource(R.string.ime_clock_every_day)
    Weekdays.WORKDAYS -> stringResource(R.string.ime_clock_weekdays)
    Weekdays.WEEKEND -> stringResource(R.string.ime_clock_weekends)
    else -> {
        val names = remember { weekdayShort() }
        (0 until 7).filter { Weekdays.has(days, it) }.joinToString(", ") { names[it] }
    }
}

/**
 * The editor, top to bottom so each part gets the panel's full width: the
 * time drums, when that rings, the seven days, then one scrolling row of day
 * shortcuts, label, vibration and snooze. Delete, Cancel and Save stay pinned
 * under it however far the rest scrolls.
 */
@Composable
private fun AlarmEditor(edit: AlarmEdit, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val now = rememberNow(30_000L)
    val letters = remember { weekdayLetters() }
    val vibrateDescription = stringResource(R.string.ime_clock_vibrate_desc)
    val preview = AlarmMath.nextFire(edit.toAlarm(null).copy(enabled = true), now)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TimeWheel(
                hour = edit.hour,
                minute = edit.minute,
                hour24 = ui.hour24,
                amLabel = remember { DateFormatSymbols.getInstance().amPmStrings[0] },
                pmLabel = remember { DateFormatSymbols.getInstance().amPmStrings[1] },
                hourDescription = stringResource(R.string.ime_clock_hour_desc),
                minuteDescription = stringResource(R.string.ime_clock_minute_desc),
                onChange = { h, m -> act(ClockAction.AlarmDraft(edit.copy(hour = h, minute = m))) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                preview?.let { stringResource(R.string.ime_clock_rings_in, ClockText.duration(it - now)) }.orEmpty(),
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (i in 0 until 7) {
                    val on = Weekdays.has(edit.days, i)
                    Box(
                        Modifier
                            .size(32.dp)
                            .clockChip(selected = on, shape = CircleShape)
                            .clickable { act(ClockAction.AlarmDraft(edit.copy(days = Weekdays.toggle(edit.days, i)))) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letters[i], color = if (on) kb.chipActiveText else kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolPanelChip(stringResource(R.string.ime_clock_weekdays), selected = edit.days == Weekdays.WORKDAYS) {
                    act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.WORKDAYS) 0 else Weekdays.WORKDAYS)))
                }
                ToolPanelChip(stringResource(R.string.ime_clock_weekends), selected = edit.days == Weekdays.WEEKEND) {
                    act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.WEEKEND) 0 else Weekdays.WEEKEND)))
                }
                ToolPanelChip(stringResource(R.string.ime_clock_every_day), selected = edit.days == Weekdays.ALL) {
                    act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.ALL) 0 else Weekdays.ALL)))
                }
                PillButton(
                    edit.label.ifEmpty { stringResource(R.string.ime_clock_add_label) },
                    icon = Icons.Outlined.Label,
                    height = 28.dp,
                ) { act(ClockAction.AlarmLabel) }
                // Only the ring service can vibrate per alarm; see ChannelFeatures.
                if (ChannelFeatures.CLOCK_RING_SERVICE) {
                    PillButton(
                        "",
                        icon = Icons.Outlined.Vibration,
                        primary = edit.vibrate,
                        height = 28.dp,
                        modifier = Modifier.semantics { contentDescription = vibrateDescription },
                    ) { act(ClockAction.AlarmDraft(edit.copy(vibrate = !edit.vibrate))) }
                }
                val snooze = edit.snoozeMinutes.takeIf { it > 0 } ?: ui.config.snoozeMinutes
                ToolPanelChip(
                    pluralStringResource(R.plurals.ime_clock_snooze_minutes, snooze, snooze),
                    selected = edit.snoozeMinutes > 0,
                ) {
                    val i = SNOOZE_STEPS.indexOf(edit.snoozeMinutes)
                    act(ClockAction.AlarmDraft(edit.copy(snoozeMinutes = SNOOZE_STEPS[(i + 1) % SNOOZE_STEPS.size])))
                }
            }
        }
        ActionBar(
            buildList {
                add(KitAction(stringResource(R.string.ime_clock_save), Icons.Outlined.Check, primary = true) { act(ClockAction.SaveAlarm) })
                add(KitAction(stringResource(R.string.ime_clock_cancel), Icons.Outlined.Close) { act(ClockAction.CancelAlarmEdit) })
                if (edit.id != null) {
                    add(KitAction(stringResource(R.string.ime_clock_delete), Icons.Outlined.Delete) { act(ClockAction.DeleteAlarm(edit.id)) })
                }
            },
            modifier = Modifier.padding(vertical = 4.dp),
            height = 36.dp,
        )
    }
}

/** The narrow weekday names in the user's language, Monday first. */
private fun weekdayLetters(): List<String> {
    val names = DateFormatSymbols.getInstance(Locale.getDefault())
    val narrow = runCatching {
        val icu = android.icu.text.DateFormatSymbols.getInstance(Locale.getDefault())
        icu.getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.NARROW)
    }.getOrNull() ?: names.shortWeekdays.map { it.take(1) }.toTypedArray()
    return (0 until 7).map { narrow[Weekdays.calendarDay(it)] }
}

private fun weekdayShort(): List<String> {
    val names = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
    return (0 until 7).map { names[Weekdays.calendarDay(it)] }
}

private val QUICK_MINUTES = listOf(10, 20, 30, 60, 90)
private val SNOOZE_STEPS = listOf(0, 5, 10, 15, 20, 30)

/** A weekday pip on an alarm card. */
private val PIP = 16.dp
