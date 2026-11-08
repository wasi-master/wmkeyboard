package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import com.wasimaster.wmkeyboard.core.clock.ClockConfig
import kotlin.math.abs
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockActions
import com.wasimaster.wmkeyboard.core.clock.ClockData
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockPrefs
import com.wasimaster.wmkeyboard.core.clock.ClockStore
import com.wasimaster.wmkeyboard.core.clock.HistoryKind
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.clock.RunningTimer
import com.wasimaster.wmkeyboard.core.clock.Weekdays
import com.wasimaster.wmkeyboard.core.clock.WorldZone
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.clock.ZoneEntry
import com.wasimaster.wmkeyboard.core.clock.utcOffsetLabel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import kotlinx.coroutines.delay
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.util.Calendar
import java.util.TimeZone
import com.wasimaster.wmkeyboard.ime.R as ImeR

/** The Clock screen's sections; the keyboard panel has the planner and date maths besides. */
internal enum class ClockSection(val tab: String) {
    ALARMS("alarms"), TIMERS("timers"), STOPWATCH("stopwatch"), CLOCKS("clocks"), COUNTDOWNS("dates"), HISTORY("history");

    companion object {
        fun of(tab: String?): ClockSection = entries.firstOrNull { it.tab == tab } ?: ALARMS
    }
}

/**
 * The settings app's Clock screen: the same alarms, timers, stopwatch, world
 * clocks, countdowns and history the keyboard panel shows, from the same
 * store, with the room a full screen has: a proper time picker, a sound per
 * alarm, real text fields.
 */
@Composable
internal fun ClockScreen(initialTab: String?, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val data by ClockStore.get(context).state.collectAsState()
    val config by ClockPrefs.state(context).collectAsState()
    var section by rememberSaveable { mutableStateOf(ClockSection.of(initialTab)) }
    val hour24 = config.is24(android.text.format.DateFormat.is24HourFormat(context))
    LaunchedEffect(Unit) { ClockEngine.prune(context) }

    // Something still ringing (#970): the way back to the ring screen when its
    // notification is gone, swiped away or never seen.
    data.ringing.firstOrNull()?.let { ring ->
        SettingsGroup {
            item {
                NavRow(
                    R.string.clock_ringing_now_title,
                    ring.label.ifEmpty {
                        stringResource(
                            if (ring.kind == com.wasimaster.wmkeyboard.core.clock.RingKind.TIMER) {
                                R.string.clock_ringing_now_timer
                            } else {
                                R.string.clock_ringing_now_alarm
                            },
                        )
                    },
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent()
                                .setClassName(context, com.wasimaster.wmkeyboard.core.clock.ClockNotifications.RING_ACTIVITY)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClockSection.entries.forEach { s ->
            FilterChip(selected = section == s, onClick = { section = s }, label = { Text(stringResource(sectionLabel(s))) })
        }
    }
    // A swipe across moves between the sections (#970). A row with swipe
    // actions takes the drag first, so those keep working. At least most of a
    // screen tall: a short section (the stopwatch, an empty list) left the
    // swipe only the strip its few rows covered, and a swipe in the empty
    // space under them did nothing.
    val swipeFloor = (LocalConfiguration.current.screenHeightDp * SECTION_SWIPE_MIN_HEIGHT).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = swipeFloor)
            .pointerInput(Unit) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = {
                        if (abs(travel) > SECTION_SWIPE_DP.dp.toPx()) {
                            val step = if (travel < 0) 1 else -1
                            ClockSection.entries.getOrNull(section.ordinal + step)?.let { section = it }
                        }
                    },
                ) { _, amount -> travel += amount }
            },
    ) {
        when (section) {
            ClockSection.ALARMS -> AlarmsSection(data, hour24)
            ClockSection.TIMERS -> TimersSection(data, hour24)
            ClockSection.STOPWATCH -> StopwatchSection(data)
            ClockSection.CLOCKS -> ClocksSection(config.shownZones(), config.zones, hour24)
            ClockSection.COUNTDOWNS -> CountdownsSection(data)
            ClockSection.HISTORY -> HistorySection(data, hour24)
        }
    }
    SettingsGroup {
        item {
            NavRow(R.string.clock_settings_title, stringResource(R.string.clock_settings_subtitle)) {
                onNavigate(toolRoute(com.wasimaster.wmkeyboard.core.settings.ToolbarTool.CLOCK))
            }
        }
    }
}

private const val SECTION_SWIPE_DP = 64

/** The share of the screen's height the section area takes at least, so a swipe has somewhere to land. */
private const val SECTION_SWIPE_MIN_HEIGHT = 0.6f

private fun sectionLabel(s: ClockSection): Int = when (s) {
    ClockSection.ALARMS -> ImeR.string.ime_clock_tab_alarms
    ClockSection.TIMERS -> ImeR.string.ime_clock_tab_timers
    ClockSection.STOPWATCH -> ImeR.string.ime_clock_tab_stopwatch
    ClockSection.CLOCKS -> ImeR.string.ime_clock_tab_clocks
    ClockSection.COUNTDOWNS -> ImeR.string.ime_clock_dates_countdowns
    ClockSection.HISTORY -> ImeR.string.ime_clock_tab_history
}

@Composable
private fun rememberTick(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay((periodMs - now % periodMs).coerceAtLeast(16))
        }
    }
    return now
}

private val Digits = TextStyle(fontFeatureSettings = "tnum")

// ---- Alarms ---------------------------------------------------------------

@Composable
private fun AlarmsSection(data: ClockData, hour24: Boolean) {
    val context = LocalContext.current
    val now = rememberTick(30_000L)
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var creating by remember { mutableStateOf(false) }
    ClockGrantsGroup()
    val next = AlarmMath.nextOfAll(data.alarms, now)
    if (next != null) {
        CaptionText(stringResource(ImeR.string.ime_clock_rings_in, ClockText.duration(next.second - now)))
    }
    if (data.alarms.isEmpty()) {
        CaptionText(stringResource(R.string.clock_no_alarms))
    } else {
        SettingsGroup {
            for (alarm in data.alarms) {
                item(key = alarm.id) {
                    AlarmRow(alarm, now, hour24, onClick = { editing = alarm }) { on ->
                        ClockEngine.update(context) { ClockActions.setAlarmEnabled(it, alarm.id, on) }
                    }
                }
            }
        }
    }
    RegisterAddFab(stringResource(ImeR.string.ime_clock_new_alarm)) { creating = true }
    val target = editing ?: if (creating) Alarm(id = 0, hour = 7, minute = 0) else null
    if (target != null) {
        AlarmDialog(
            alarm = target,
            isNew = editing == null,
            hour24 = hour24,
            onDismiss = {
                editing = null
                creating = false
            },
            onSave = { a ->
                ClockEngine.update(context) { d ->
                    if (editing == null) {
                        ClockActions.addAlarm(d, a).second
                    } else {
                        ClockActions.putAlarm(d, a.copy(enabled = true, snoozedUntil = 0, skipUntil = 0))
                    }
                }
                editing = null
                creating = false
            },
            onDelete = {
                ClockEngine.update(context) { ClockActions.removeAlarm(it, target.id) }
                editing = null
            },
        )
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, now: Long, hour24: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val zone = TimeZone.getDefault()
    val at = Calendar.getInstance(zone).apply {
        set(Calendar.HOUR_OF_DAY, alarm.hour)
        set(Calendar.MINUTE, alarm.minute)
    }.timeInMillis
    val days = when (alarm.days) {
        0 -> stringResource(ImeR.string.ime_clock_once)
        Weekdays.ALL -> stringResource(ImeR.string.ime_clock_every_day)
        Weekdays.WORKDAYS -> stringResource(ImeR.string.ime_clock_weekdays)
        Weekdays.WEEKEND -> stringResource(ImeR.string.ime_clock_weekends)
        else -> {
            val names = DateFormatSymbols.getInstance().shortWeekdays
            (0 until 7).filter { Weekdays.has(alarm.days, it) }.joinToString(", ") { names[Weekdays.calendarDay(it)] }
        }
    }
    val note = when {
        alarm.snoozedUntil > now -> stringResource(ImeR.string.ime_clock_snoozed_until, ClockFormats.time(alarm.snoozedUntil, zone, hour24))
        AlarmMath.isSkipping(alarm, now) -> stringResource(ImeR.string.ime_clock_skipping_next)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ClockFormats.time(at, zone, hour24),
                style = MaterialTheme.typography.headlineMedium.merge(Digits),
                color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(alarm.label.takeIf { it.isNotEmpty() }, days, note).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmDialog(
    alarm: Alarm,
    isNew: Boolean,
    hour24: Boolean,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
) {
    val time = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = hour24)
    var days by remember { mutableStateOf(alarm.days) }
    var label by remember { mutableStateOf(alarm.label) }
    var vibrate by remember { mutableStateOf(alarm.vibrate) }
    var autoSnoozes by remember { mutableStateOf(alarm.autoSnoozes) }
    val letters = remember {
        val names = DateFormatSymbols.getInstance().shortWeekdays
        (0 until 7).map { names[Weekdays.calendarDay(it)].take(2) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    alarm.copy(
                        hour = time.hour, minute = time.minute, days = days, label = label.trim(), vibrate = vibrate,
                        autoSnoozes = autoSnoozes,
                    ),
                )
            }) { Text(stringResource(ImeR.string.ime_clock_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text(stringResource(ImeR.string.ime_clock_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(ImeR.string.ime_clock_cancel)) }
            }
        },
        text = {
            // Scrolls: the time picker alone fills most of a phone's dialog,
            // and the rows under it, this alarm's snooze count among them,
            // were cut off below it with no way down to them (#970).
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TimePicker(state = time)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (i in 0 until 7) {
                        FilterChip(
                            selected = Weekdays.has(days, i),
                            onClick = { days = Weekdays.toggle(days, i) },
                            label = { Text(letters[i]) },
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(LABEL_MAX) },
                    label = { Text(stringResource(ImeR.string.ime_clock_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // This alarm's own count (#970), not every alarm's: blank follows
                // the tool's setting, any number is taken as typed.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = autoSnoozes.takeIf { it in 0 until ClockConfig.SNOOZE_UNTIL_ANSWERED }?.toString().orEmpty(),
                        onValueChange = { typed ->
                            autoSnoozes = typed.filter(Char::isDigit).take(3).toIntOrNull() ?: -1
                        },
                        label = { Text(stringResource(R.string.clock_alarm_auto_snooze_label)) },
                        placeholder = { Text(stringResource(R.string.clock_alarm_auto_snooze_default)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = autoSnoozes == ClockConfig.SNOOZE_UNTIL_ANSWERED,
                        onClick = {
                            autoSnoozes = if (autoSnoozes == ClockConfig.SNOOZE_UNTIL_ANSWERED) -1 else ClockConfig.SNOOZE_UNTIL_ANSWERED
                        },
                        label = { Text(stringResource(R.string.clock_auto_snooze_forever)) },
                    )
                }
                // Only the ring service can vibrate per alarm; see ChannelFeatures.
                if (ChannelFeatures.CLOCK_RING_SERVICE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(ImeR.string.ime_clock_vibrate_desc), modifier = Modifier.weight(1f))
                        Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                    }
                }
            }
        },
    )
}

// ---- Timers ---------------------------------------------------------------

@Composable
private fun TimersSection(data: ClockData, hour24: Boolean) {
    val context = LocalContext.current
    val now = rememberTick(250L)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.presets.forEach { p ->
            FilledTonalButton(onClick = {
                ClockEngine.update(context) { ClockActions.startTimer(it, p.label, p.durationMs, System.currentTimeMillis()).second }
            }) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(listOfNotNull(p.label.takeIf { it.isNotEmpty() }, ClockText.duration(p.durationMs, seconds = true)).joinToString(" · "))
            }
        }
    }
    if (data.timers.isEmpty()) {
        CaptionText(stringResource(R.string.clock_no_timers))
        return
    }
    SettingsGroup {
        for (t in data.timers) {
            item(key = t.id) { TimerRow(t, now, hour24) }
        }
    }
}

@Composable
private fun TimerRow(t: RunningTimer, now: Long, hour24: Boolean) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ClockFormats.countdown(t.remainingAt(now)), style = MaterialTheme.typography.headlineMedium.merge(Digits))
                Text(
                    listOfNotNull(
                        t.label.takeIf { it.isNotEmpty() },
                        if (t.paused) {
                            stringResource(ImeR.string.ime_clock_paused)
                        } else {
                            stringResource(ImeR.string.ime_clock_ends_at, ClockFormats.time(t.endsAt, TimeZone.getDefault(), hour24))
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { ClockEngine.update(context) { ClockActions.extendTimer(it, t.id, MINUTE_MS) } }) {
                Text(stringResource(ImeR.string.ime_clock_plus_minute))
            }
            IconButton(onClick = {
                val n = System.currentTimeMillis()
                ClockEngine.update(context) { if (t.paused) ClockActions.resumeTimer(it, t.id, n) else ClockActions.pauseTimer(it, t.id, n) }
            }) {
                Icon(
                    if (t.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = stringResource(if (t.paused) ImeR.string.ime_clock_resume else ImeR.string.ime_clock_pause),
                )
            }
            IconButton(onClick = { ClockEngine.update(context) { ClockActions.cancelTimer(it, t.id, System.currentTimeMillis()) } }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(ImeR.string.ime_clock_stop))
            }
        }
        LinearProgressIndicator(progress = { 1f - t.progressAt(now) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

// ---- Stopwatch ------------------------------------------------------------

@Composable
private fun StopwatchSection(data: ClockData) {
    val context = LocalContext.current
    val sw = data.stopwatch
    val now = rememberTick(if (sw.running) 33L else 1000L)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ClockFormats.stopwatch(sw.elapsedAt(now)), style = MaterialTheme.typography.displayMedium.merge(Digits), fontWeight = FontWeight.Light)
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = {
                val n = System.currentTimeMillis()
                ClockEngine.update(context) { if (sw.running) ClockActions.stopwatchLap(it, n) else ClockActions.stopwatchReset(it, n) }
            }) { Text(stringResource(if (sw.running) ImeR.string.ime_clock_lap else ImeR.string.ime_clock_reset)) }
            FilledTonalButton(onClick = {
                val n = System.currentTimeMillis()
                ClockEngine.update(context) { if (sw.running) ClockActions.stopwatchPause(it, n) else ClockActions.stopwatchStart(it, n) }
            }) { Text(stringResource(if (sw.running) ImeR.string.ime_clock_pause else ImeR.string.ime_clock_start)) }
        }
    }
    if (sw.laps.isNotEmpty()) {
        val lengths = sw.lapLengths
        SettingsGroup {
            for (i in lengths.indices.reversed()) {
                item(key = i) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(ImeR.string.ime_clock_lap_number, i + 1), modifier = Modifier.weight(1f))
                        Text(ClockFormats.stopwatch(lengths[i]), style = Digits)
                        Spacer(Modifier.width(16.dp))
                        Text(ClockFormats.stopwatch(sw.laps[i]), style = Digits, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---- World clocks -----------------------------------------------------------

@Composable
private fun ClocksSection(zones: List<WorldZone>, stored: List<WorldZone>, hour24: Boolean) {
    val context = LocalContext.current
    val now = rememberTick(15_000L)
    var adding by remember { mutableStateOf(false) }
    // A tap on the bin took the city at once, with nothing to undo it (#970).
    var removing by remember { mutableStateOf<Pair<WorldZone, String>?>(null) }
    val home = TimeZone.getDefault()
    SettingsGroup {
        for (z in zones) {
            item(key = z.zoneId) {
                val tz = TimeZone.getTimeZone(z.zoneId)
                val city = z.label.ifEmpty { IcuZoneNames().city(z.zoneId) ?: ZoneCatalog.fallbackCity(z.zoneId) }
                val removable = stored.any { it.zoneId == z.zoneId }
                SwipeActionsRow(if (removable) listOf(deleteSwipeAction(stringResource(ImeR.string.ime_clock_remove_desc)) { removing = z to city }) else emptyList()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(city, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${ClockFormats.weekday(now, tz)} · ${ClockFormats.deltaLabel(ClockFormats.offsetDelta(now, tz, home))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(ClockFormats.time(now, tz, hour24), style = MaterialTheme.typography.headlineSmall.merge(Digits))
                        if (removable) {
                            IconButton(onClick = { removing = z to city }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(ImeR.string.ime_clock_remove_desc))
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let { (z, city) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.clock_remove_city_title, city)) },
            confirmButton = {
                TextButton(onClick = {
                    ClockPrefs.update(context) { c -> c.copy(zones = c.zones.filterNot { it.zoneId == z.zoneId }) }
                    removing = null
                }) { Text(stringResource(ImeR.string.ime_clock_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(ImeR.string.ime_clock_cancel)) }
            },
        )
    }
    if (zones.isEmpty()) CaptionText(stringResource(R.string.clock_no_zones))
    RegisterAddFab(stringResource(ImeR.string.ime_clock_add_city)) { adding = true }
    if (adding) ZonePickerDialog(onDismiss = { adding = false }) { e ->
        ClockPrefs.update(context) { c -> if (c.zones.any { it.zoneId == e.id }) c else c.copy(zones = c.zones + e.toWorldZone()) }
        adding = false
    }
}

@Composable
private fun ZonePickerDialog(onDismiss: () -> Unit, onPick: (ZoneEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val catalog = remember { ZoneCatalog(IcuZoneNames()) }
    val results = remember(query) { catalog.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(ImeR.string.ime_clock_cancel)) } },
        title = { Text(stringResource(ImeR.string.ime_clock_add_city)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(ImeR.string.ime_clock_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.key }) { e ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(e) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(e.city, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOf(e.region, utcOffsetLabel(e.offsetMinutes)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

// ---- Countdowns -------------------------------------------------------------

@Composable
private fun CountdownsSection(data: ClockData) {
    val context = LocalContext.current
    val now = rememberTick(60_000L)
    val zone = TimeZone.getDefault()
    val today = LocalDay.of(now, zone)
    if (data.countdowns.isEmpty()) {
        CaptionText(stringResource(ImeR.string.ime_clock_countdowns_empty_body))
        return
    }
    SettingsGroup {
        for (c in data.countdowns) {
            item(key = c.id) {
                val days = (LocalDay.of(c.target, zone).epochDay - today.epochDay).toInt()
                val remove = { ClockStore.get(context).update { ClockActions.removeCountdown(it, c.id) } }
                SwipeActionsRow(listOf(deleteSwipeAction(stringResource(ImeR.string.ime_clock_delete)) { remove() })) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text(ClockFormats.longDate(c.target, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            when {
                                days > 0 -> "${NumberFormat.getInstance().format(days)} ${pluralStringResource(ImeR.plurals.ime_clock_days_left, days)}"
                                days < 0 -> "${NumberFormat.getInstance().format(-days)} ${pluralStringResource(ImeR.plurals.ime_clock_days_ago, -days)}"
                                else -> stringResource(ImeR.string.ime_clock_today)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { remove() }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(ImeR.string.ime_clock_delete))
                        }
                    }
                }
            }
        }
    }
}

// ---- History ----------------------------------------------------------------

@Composable
private fun HistorySection(data: ClockData, hour24: Boolean) {
    val context = LocalContext.current
    val zone = TimeZone.getDefault()
    if (data.history.isEmpty()) {
        CaptionText(stringResource(ImeR.string.ime_clock_history_empty_body))
        return
    }
    SettingsGroup(
        action = {
            TextButton(onClick = { ClockStore.get(context).update { it.copy(history = emptyList()) } }) {
                Text(stringResource(ImeR.string.ime_clock_clear_history))
            }
        },
    ) {
        for (e in data.history.take(HISTORY_SHOWN)) {
            item(key = e.id) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.label.ifEmpty { stringResource(kindLabel(e.kind)) }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${ClockFormats.shortDate(e.at, zone)}, ${ClockFormats.time(e.at, zone, hour24)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.durationMs > 0) Text(ClockText.duration(e.durationMs, seconds = true), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun kindLabel(kind: HistoryKind): Int = when (kind) {
    HistoryKind.ALARM -> ImeR.string.ime_clock_kind_alarm
    HistoryKind.TIMER -> ImeR.string.ime_clock_kind_timer
    HistoryKind.STOPWATCH -> ImeR.string.ime_clock_kind_stopwatch
    HistoryKind.POMODORO -> ImeR.string.ime_clock_kind_pomodoro
}

private const val LABEL_MAX = 60
private const val MINUTE_MS = 60_000L
private const val HISTORY_SHOWN = 200
