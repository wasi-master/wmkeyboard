package com.wasimaster.wmkeyboard.ime.clock

import android.content.Context
import android.text.format.DateUtils
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.SpecialAccessActivity
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockActions
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockNotifications
import com.wasimaster.wmkeyboard.core.clock.ClockPrefs
import com.wasimaster.wmkeyboard.core.clock.ClockStore
import com.wasimaster.wmkeyboard.core.clock.ClockTab
import com.wasimaster.wmkeyboard.core.clock.DateMath
import com.wasimaster.wmkeyboard.core.clock.HistoryEntry
import com.wasimaster.wmkeyboard.core.clock.HistoryKind
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.InsertFormat
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.clock.MeetingPlanner
import com.wasimaster.wmkeyboard.core.clock.PlannerDay
import com.wasimaster.wmkeyboard.core.clock.Span
import com.wasimaster.wmkeyboard.core.clock.ZoneEntry
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The keyboard half of the clock tool.
 *
 * Holds the panel's [ClockUi], turns the panel's [ClockAction]s into changes
 * to the shared [ClockStore] (through [ClockEngine], so alarms re-arm and the
 * shade follows), and types into the field. Owned by the service and built on
 * first use; nothing here runs until the Clock panel is opened.
 */
internal class ClockController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
    private val openRoute: (String) -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (ClockTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now; it lives in the keyboard's state. */
    private val currentText: () -> ClockTextEntry?,
) {

    private val app = context.applicationContext
    private val _state = MutableStateFlow(ClockUi())
    val state: StateFlow<ClockUi> = _state

    val callbacks = ClockCallbacks(state = state, onAction = ::dispatch)

    private var watch: Job? = null

    /** The zone list, built on the first search: about 420 zones, a few ms. */
    private val catalog: ZoneCatalog by lazy { ZoneCatalog(IcuZoneNames()) }

    fun onPanelOpen() {
        if (watch == null) {
            watch = scope.launch {
                combine(ClockStore.get(app).state, ClockPrefs.state(app)) { d, c -> d to c }
                    .collect { (d, c) -> _state.update { it.copy(data = d, config = c) } }
            }
        }
        ClockEngine.prune(app)
        val config = ClockPrefs.get(app)
        _state.update {
            it.copy(
                tab = ClockTab.of(config.lastTab),
                systemHour24 = android.text.format.DateFormat.is24HourFormat(app),
                insertZone = null,
                undo = null,
            )
        }
        refreshGrants()
    }

    fun onPanelClose() {
        endTextIfAny()
        _state.update { it.copy(search = null, insertZone = null) }
    }

    /** Back from a system screen (a grant), so what it changed shows. */
    fun onKeyboardShown() {
        if (watch != null) refreshGrants()
    }

    private fun refreshGrants() {
        _state.update {
            it.copy(
                exactAllowed = ClockEngine.canScheduleExact(app),
                // A build without the permission (Play) has nothing to offer here.
                fullScreenAllowed = !ChannelFeatures.CLOCK_FULL_SCREEN || ClockNotifications.canUseFullScreen(app),
                notificationsAllowed = WmNotifications.canPost(app),
            )
        }
    }

    // ---- The panel's text box ------------------------------------------

    private var textField: ClockTextField? = null

    private fun startText(entry: ClockTextEntry) {
        textField = entry.field
        beginText(entry)
    }

    private fun endTextIfAny() {
        if (textField != null) {
            textField = null
            endText()
        }
    }

    /** Called by the service as the box is typed into. */
    fun onText(entry: ClockTextEntry) {
        when (entry.field) {
            ClockTextField.SEARCH -> _state.update { it.copy(search = catalog.search(entry.text)) }
            ClockTextField.ALARM_LABEL ->
                _state.update { s -> s.copy(alarmEdit = s.alarmEdit?.copy(label = entry.text.take(LABEL_MAX))) }
            ClockTextField.TIMER_LABEL -> _state.update { it.copy(timerLabel = entry.text.take(LABEL_MAX)) }
            ClockTextField.COUNTDOWN_TITLE, ClockTextField.ZONE_LABEL, ClockTextField.DATE -> Unit
        }
    }

    /**
     * Enter in the box. Search adds the best match; a label is kept; a
     * countdown title saves the countdown. Returns whether the keys go back
     * to the field.
     */
    fun onTextEnter(entry: ClockTextEntry) {
        when (entry.field) {
            ClockTextField.SEARCH -> _state.value.search?.firstOrNull()?.let { addZone(it) }
            ClockTextField.ALARM_LABEL, ClockTextField.TIMER_LABEL -> onText(entry)
            ClockTextField.COUNTDOWN_TITLE -> saveCountdown(entry.text)
            ClockTextField.ZONE_LABEL -> renameZone(entry.ref, entry.text)
            ClockTextField.DATE -> typedDate(entry)
        }
        endTextIfAny()
        if (entry.field == ClockTextField.SEARCH) _state.update { it.copy(search = null) }
    }

    /**
     * A date typed into the box (Telegram group): a birthday twenty years
     * back was a long run of taps on the steppers. A text that is no date
     * leaves the date as it was.
     */
    private fun typedDate(entry: ClockTextEntry) {
        val field = runCatching { DateField.valueOf(entry.ref) }.getOrNull() ?: return
        val monthFirst = java.util.Locale.getDefault().country in MONTH_FIRST_COUNTRIES
        val day = LocalDay.parse(entry.text, monthFirst) ?: return
        _state.update { s -> s.copy(dates = setDate(s.dates, field, day)) }
    }

    // ---- Actions -------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: ClockAction) {
        val now = System.currentTimeMillis()
        when (action) {
            is ClockAction.Tab -> {
                endTextIfAny()
                _state.update { it.copy(tab = action.tab, search = null, insertZone = null, undo = null) }
                ClockPrefs.update(app) { it.copy(lastTab = action.tab.id) }
            }
            ClockAction.OpenSettings -> openToolSettings()
            ClockAction.OpenApp -> openRoute(ClockEngine.ROUTE)

            ClockAction.SearchOpen -> {
                _state.update { it.copy(search = catalog.search(""), insertZone = null) }
                startText(ClockTextEntry(ClockTextField.SEARCH))
            }
            ClockAction.SearchClose -> {
                endTextIfAny()
                _state.update { it.copy(search = null) }
            }
            is ClockAction.AddZone -> addZone(action.zone)
            is ClockAction.RemoveZone -> {
                val zones = ClockPrefs.get(app).zones
                val index = zones.indexOfFirst { it.zoneId == action.zoneId }
                if (index >= 0) {
                    haptic()
                    ClockPrefs.update(app) { c -> c.copy(zones = c.zones.filterNot { it.zoneId == action.zoneId }) }
                    _state.update { it.copy(undo = ClockUndo.ZoneRemoved(index, zones[index]), insertZone = null) }
                }
            }
            is ClockAction.MoveZone -> ClockPrefs.update(app) { c ->
                val i = c.zones.indexOfFirst { it.zoneId == action.zoneId }
                val j = i + action.by
                if (i < 0 || j !in c.zones.indices) {
                    c
                } else {
                    c.copy(zones = c.zones.toMutableList().apply { add(j, removeAt(i)) })
                }
            }
            is ClockAction.RenameZone -> {
                val current = ClockPrefs.get(app).zones.firstOrNull { it.zoneId == action.zoneId }?.label.orEmpty()
                _state.update { it.copy(insertZone = null) }
                startText(ClockTextEntry(ClockTextField.ZONE_LABEL, current, action.zoneId))
            }
            is ClockAction.InsertSheet -> _state.update {
                it.copy(insertZone = if (it.insertZone == action.zoneId) null else action.zoneId, insertWhen = null)
            }
            is ClockAction.Insert -> {
                insertText(formatFor(action.zoneId, action.format, action.at ?: insertAt(action.zoneId, now)))
                _state.update { it.copy(insertZone = null, insertWhen = null) }
            }
            is ClockAction.InsertDiscord -> {
                insertText(ClockFormats.discord(insertAt(action.zoneId, now), action.style))
                _state.update { it.copy(insertZone = null, insertWhen = null) }
            }
            is ClockAction.SetDefaultDiscord -> {
                haptic()
                ClockPrefs.update(app) { it.copy(insertFormat = InsertFormat.DISCORD.id, discordStyle = action.style.code.toString()) }
            }
            is ClockAction.SetInsertWhen -> _state.update { it.copy(insertWhen = action.at) }
            is ClockAction.InsertDefault -> insertText(formatFor(action.zoneId, _state.value.insertFormat, now))
            is ClockAction.SetDefaultFormat -> {
                haptic()
                ClockPrefs.update(app) { it.copy(insertFormat = action.format.id) }
            }
            ClockAction.InsertAllClocks -> insertText(conversionLine(now))

            ClockAction.NewAlarm -> {
                val (h, m) = AlarmMath.inMinutes(now, 60)
                _state.update { it.copy(alarmEdit = AlarmEdit(null, h, m - m % 5)) }
            }
            is ClockAction.QuickAlarm -> {
                val (h, m) = AlarmMath.inMinutes(now, action.minutesFromNow)
                ClockEngine.update(app) { ClockActions.addAlarm(it, com.wasimaster.wmkeyboard.core.clock.Alarm(0, h, m)).second }
                haptic()
            }
            is ClockAction.EditAlarm -> _state.value.data.alarm(action.id)?.let { a ->
                _state.update { it.copy(alarmEdit = AlarmEdit.of(a)) }
            }
            is ClockAction.AlarmDraft -> _state.update { it.copy(alarmEdit = action.edit) }
            ClockAction.AlarmLabel -> _state.value.alarmEdit?.let {
                startText(ClockTextEntry(ClockTextField.ALARM_LABEL, it.label))
            }
            ClockAction.SaveAlarm -> saveAlarm()
            ClockAction.CancelAlarmEdit -> {
                endTextIfAny()
                _state.update { it.copy(alarmEdit = null) }
            }
            is ClockAction.ToggleAlarm -> {
                haptic()
                ClockEngine.update(app) { ClockActions.setAlarmEnabled(it, action.id, action.on) }
            }
            is ClockAction.SkipAlarm -> ClockEngine.update(app) {
                ClockActions.toggleSkip(it, action.id, now, TimeZone.getDefault())
            }
            is ClockAction.DeleteAlarm -> {
                val alarm = _state.value.data.alarm(action.id) ?: return
                haptic()
                ClockEngine.update(app) { ClockActions.removeAlarm(it, action.id) }
                _state.update { it.copy(undo = ClockUndo.AlarmRemoved(alarm), alarmEdit = null) }
            }
            is ClockAction.MoveAlarmToPhone -> {
                val alarm = _state.value.data.alarm(action.id) ?: return
                if (!PhoneClock.setAlarm(app, alarm)) {
                    PhoneClock.missing(app)
                    return
                }
                haptic()
                ClockEngine.update(app) { ClockActions.removeAlarm(it, action.id) }
                _state.update { it.copy(undo = ClockUndo.AlarmRemoved(alarm), alarmEdit = null) }
            }
            ClockAction.PhoneAlarms -> if (!PhoneClock.showAlarms(app)) PhoneClock.missing(app)
            ClockAction.AskExact -> launchAccess(SpecialAccess.EXACT_ALARMS)
            ClockAction.AskFullScreen -> launchAccess(SpecialAccess.FULL_SCREEN)

            is ClockAction.TimerDigit -> _state.update { s ->
                if (s.timerDigits.length >= TIMER_DIGITS || (s.timerDigits.isEmpty() && action.digit == '0')) {
                    s
                } else {
                    s.copy(timerDigits = s.timerDigits + action.digit)
                }
            }
            ClockAction.TimerBackspace -> _state.update { it.copy(timerDigits = it.timerDigits.dropLast(1)) }
            ClockAction.TimerClear -> _state.update { it.copy(timerDigits = "") }
            is ClockAction.TimerAdd -> _state.update { s ->
                s.copy(timerDigits = digitsOf((msOfDigits(s.timerDigits) + action.ms).coerceAtMost(MAX_TIMER_MS)))
            }
            ClockAction.TimerLabel -> startText(ClockTextEntry(ClockTextField.TIMER_LABEL, _state.value.timerLabel))
            ClockAction.StartTimer -> {
                val ms = msOfDigits(_state.value.timerDigits)
                if (ms > 0) {
                    endTextIfAny()
                    haptic()
                    val label = _state.value.timerLabel
                    ClockEngine.update(app) { ClockActions.startTimer(it, label, ms, now).second }
                    _state.update { it.copy(timerDigits = "", timerLabel = "") }
                }
            }
            is ClockAction.StartPreset -> _state.value.data.presets.firstOrNull { it.id == action.id }?.let { p ->
                haptic()
                ClockEngine.update(app) { ClockActions.startTimer(it, p.label, p.durationMs, now).second }
            }
            ClockAction.SavePreset -> {
                val ms = msOfDigits(_state.value.timerDigits)
                if (ms > 0) {
                    haptic()
                    val label = _state.value.timerLabel
                    ClockStore.get(app).update { ClockActions.savePreset(it, label, ms) }
                }
            }
            is ClockAction.DeletePreset -> {
                haptic()
                ClockStore.get(app).update { ClockActions.removePreset(it, action.id) }
            }
            is ClockAction.PomodoroMode -> _state.update { it.copy(pomodoroMode = action.on) }
            ClockAction.StartPomodoro -> {
                haptic()
                val plan = ClockPrefs.get(app).pomodoro
                val label = _state.value.timerLabel
                ClockEngine.update(app) { ClockActions.startPomodoro(it, plan, label, now) }
            }
            is ClockAction.PauseTimer -> ClockEngine.update(app) { ClockActions.pauseTimer(it, action.id, now) }
            is ClockAction.ResumeTimer -> ClockEngine.update(app) { ClockActions.resumeTimer(it, action.id, now) }
            is ClockAction.ExtendTimer -> ClockEngine.update(app) { ClockActions.extendTimer(it, action.id, action.ms) }
            is ClockAction.ResetTimer -> ClockEngine.update(app) { ClockActions.resetTimer(it, action.id) }
            is ClockAction.CancelTimer -> {
                haptic()
                ClockEngine.update(app) { ClockActions.cancelTimer(it, action.id, now) }
            }

            ClockAction.StopwatchToggle -> {
                haptic()
                ClockEngine.update(app) {
                    if (it.stopwatch.running) ClockActions.stopwatchPause(it, now) else ClockActions.stopwatchStart(it, now)
                }
            }
            ClockAction.StopwatchLap -> {
                haptic()
                ClockEngine.update(app) { ClockActions.stopwatchLap(it, now) }
            }
            ClockAction.StopwatchReset -> ClockEngine.update(app) { ClockActions.stopwatchReset(it, now) }
            ClockAction.StopwatchInsert -> {
                val s = _state.value.data.stopwatch
                insertText(lapsText(s.elapsedAt(now), s.lapLengths))
            }

            is ClockAction.PlannerDay -> _state.update {
                it.copy(planner = it.planner.copy(dayOffset = (it.planner.dayOffset + action.by).coerceIn(-MAX_PLANNER_DAYS, MAX_PLANNER_DAYS)))
            }
            is ClockAction.PlannerSlot -> _state.update { it.copy(planner = it.planner.copy(slot = action.slot)) }
            ClockAction.PlannerBest -> plannerDay(now).best?.let { best ->
                haptic()
                _state.update { it.copy(planner = it.planner.copy(slot = best)) }
            }
            ClockAction.PlannerInsert -> {
                val day = plannerDay(now)
                val slot = _state.value.planner.slot
                val at = slot?.let { day.slots.getOrNull(it) } ?: now
                insertText(conversionLine(at))
            }
            ClockAction.PlannerDiscord -> {
                val day = plannerDay(now)
                val at = _state.value.planner.slot?.let { day.slots.getOrNull(it) } ?: now
                insertText(ClockFormats.discord(at, _state.value.discordStyle))
            }

            is ClockAction.DatesModeSet -> {
                endTextIfAny()
                _state.update { it.copy(dates = it.dates.copy(mode = action.mode, newCountdown = null)) }
            }
            is ClockAction.DateStep -> _state.update { s -> s.copy(dates = stepDate(s.dates, action, today(now))) }
            is ClockAction.DateToday -> _state.update { s -> s.copy(dates = setDate(s.dates, action.field, null)) }
            is ClockAction.DateType -> startText(ClockTextEntry(ClockTextField.DATE, "", action.field.name))
            ClockAction.DatesSwap -> _state.update { s ->
                s.copy(dates = s.dates.copy(from = s.dates.to ?: today(now), to = s.dates.from ?: today(now)))
            }
            is ClockAction.AddAmount -> _state.update { s ->
                s.copy(dates = s.dates.copy(amount = (s.dates.amount + action.by).coerceIn(-MAX_ADD, MAX_ADD)))
            }
            is ClockAction.AddUnit -> _state.update { s -> s.copy(dates = s.dates.copy(unit = action.unit)) }
            ClockAction.DatesInsert -> insertText(datesText(now))
            ClockAction.NewCountdown -> {
                val start = today(now).plusDays(DEFAULT_COUNTDOWN_DAYS)
                _state.update { s -> s.copy(dates = s.dates.copy(newCountdown = start)) }
                startText(ClockTextEntry(ClockTextField.COUNTDOWN_TITLE))
            }
            ClockAction.CountdownTitle -> startText(ClockTextEntry(ClockTextField.COUNTDOWN_TITLE))
            ClockAction.SaveCountdown -> {
                saveCountdown(currentText()?.takeIf { it.field == ClockTextField.COUNTDOWN_TITLE }?.text.orEmpty())
                endTextIfAny()
            }
            ClockAction.CancelCountdown -> {
                endTextIfAny()
                _state.update { s -> s.copy(dates = s.dates.copy(newCountdown = null)) }
            }
            is ClockAction.DeleteCountdown -> {
                haptic()
                ClockStore.get(app).update { ClockActions.removeCountdown(it, action.id) }
            }
            is ClockAction.InsertCountdown -> _state.value.data.countdowns.firstOrNull { it.id == action.id }?.let { c ->
                insertText(countdownText(c.title, c.target, now))
            }

            is ClockAction.HistoryFilter -> _state.update { it.copy(historyFilter = action.kind) }
            is ClockAction.HistoryToggle -> _state.update { s ->
                s.copy(historyOpen = if (action.id in s.historyOpen) s.historyOpen - action.id else s.historyOpen + action.id)
            }
            is ClockAction.HistoryInsert -> _state.value.data.history.firstOrNull { it.id == action.id }?.let {
                insertText(historyText(it))
            }
            is ClockAction.HistoryDelete -> ClockStore.get(app).update { ClockActions.removeHistory(it, setOf(action.id)) }
            ClockAction.HistoryClear -> {
                val filter = _state.value.historyFilter
                val gone = _state.value.data.history.filter { filter == null || it.kind == filter }
                if (gone.isNotEmpty()) {
                    haptic()
                    ClockStore.get(app).update { ClockActions.removeHistory(it, gone.map { e -> e.id }.toSet()) }
                    _state.update { it.copy(undo = ClockUndo.HistoryCleared(gone)) }
                }
            }

            ClockAction.TextDone -> currentText()?.let(::onTextEnter) ?: endTextIfAny()
            ClockAction.Undo -> undo()
            ClockAction.UndoDismiss -> _state.update { it.copy(undo = null) }
        }
    }

    private fun undo() {
        when (val u = _state.value.undo) {
            is ClockUndo.AlarmRemoved -> ClockEngine.update(app) { d ->
                if (d.alarm(u.alarm.id) != null) d else d.copy(alarms = (d.alarms + u.alarm).sortedWith(ClockActions.AlarmOrder))
            }
            is ClockUndo.HistoryCleared -> ClockStore.get(app).update { d ->
                d.copy(history = (d.history + u.entries).distinctBy { it.id }.sortedByDescending { it.at })
            }
            is ClockUndo.ZoneRemoved -> ClockPrefs.update(app) { c ->
                if (c.zones.any { it.zoneId == u.zone.zoneId }) {
                    c
                } else {
                    c.copy(zones = c.zones.toMutableList().apply { add(u.index.coerceIn(0, size), u.zone) })
                }
            }
            null -> Unit
        }
        _state.update { it.copy(undo = null) }
    }

    private fun addZone(entry: ZoneEntry) {
        haptic()
        ClockPrefs.update(app) { c ->
            if (c.zones.any { it.zoneId == entry.id } || c.zones.size >= MAX_ZONES) c else c.copy(zones = c.zones + entry.toWorldZone())
        }
        endTextIfAny()
        _state.update { it.copy(search = null) }
    }

    private fun renameZone(zoneId: String, label: String) {
        ClockPrefs.update(app) { c ->
            c.copy(zones = c.zones.map { if (it.zoneId == zoneId) it.copy(label = label.trim().take(LABEL_MAX)) else it })
        }
    }

    private fun saveAlarm() {
        val edit = _state.value.alarmEdit ?: return
        endTextIfAny()
        haptic()
        ClockEngine.update(app) { d ->
            val base = edit.id?.let { d.alarm(it) }
            if (base == null) {
                ClockActions.addAlarm(d, edit.toAlarm(null)).second
            } else {
                ClockActions.putAlarm(d, edit.toAlarm(base).copy(enabled = true, snoozedUntil = 0, skipUntil = 0))
            }
        }
        _state.update { it.copy(alarmEdit = null) }
        refreshGrants()
    }

    private fun saveCountdown(title: String) {
        val day = _state.value.dates.newCountdown ?: return
        val text = title.trim().ifEmpty { context.getString(R.string.ime_clock_countdown_untitled) }
        ClockStore.get(app).update {
            ClockActions.addCountdown(it, text.take(LABEL_MAX), day.startMillis(TimeZone.getDefault()), allDay = true)
        }
        _state.update { s -> s.copy(dates = s.dates.copy(newCountdown = null)) }
    }

    private fun launchAccess(access: SpecialAccess) {
        runCatching {
            context.startActivity(
                SpecialAccessActivity.intent(context, access).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- Text the tool types ---------------------------------------------

    private fun zoneFor(zoneId: String): TimeZone =
        if (zoneId.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(zoneId)

    /** The name a clock goes by: the user's label, the city, or the phone's own zone. */
    fun placeName(zoneId: String): String {
        if (zoneId.isEmpty()) {
            val local = TimeZone.getDefault().id
            return IcuZoneNames().city(local) ?: ZoneCatalog.fallbackCity(local)
        }
        val label = _state.value.config.zones.firstOrNull { it.zoneId == zoneId }?.label.orEmpty()
        return label.ifEmpty { IcuZoneNames().city(zoneId) ?: ZoneCatalog.fallbackCity(zoneId) }
    }

    fun formatFor(zoneId: String, format: InsertFormat, at: Long): String =
        ClockFormats.format(
            format, at, zoneFor(zoneId), _state.value.hour24, placeName(zoneId),
            relative = { t ->
                DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            },
            discordStyle = _state.value.discordStyle,
        )

    /** The moment the insert sheet picked on [zoneId]'s clock, or [now]. */
    private fun insertAt(zoneId: String, now: Long): Long =
        _state.value.insertWhen?.let { w -> ClockFormats.atLocal(now, zoneFor(zoneId), w.dayOffset, w.hour, w.minute) } ?: now

    /** Home first, then every clock on the list. */
    private fun conversionLine(at: Long): String {
        val places = listOf(placeName("") to TimeZone.getDefault()) +
            _state.value.config.shownZones(at).map { placeName(it.zoneId) to TimeZone.getTimeZone(it.zoneId) }
        return ClockFormats.conversionLine(at, places, _state.value.hour24)
    }

    fun plannerDay(now: Long): PlannerDay {
        val c = _state.value.config
        val home = TimeZone.getDefault()
        val day = now + _state.value.planner.dayOffset * DateUtils.DAY_IN_MILLIS
        return MeetingPlanner.plan(
            day, home, listOf(home) + c.zones.map { TimeZone.getTimeZone(it.zoneId) },
            workStart = c.workStartMinutes, workEnd = c.workEndMinutes,
        )
    }

    private fun lapsText(total: Long, laps: List<Long>): String = buildString {
        laps.forEachIndexed { i, ms ->
            appendLine(context.getString(R.string.ime_clock_lap_line, i + 1, ClockFormats.stopwatch(ms)))
        }
        append(context.getString(R.string.ime_clock_total_line, ClockFormats.stopwatch(total)))
    }

    private fun countdownText(title: String, target: Long, now: Long): String {
        val days = (LocalDay.of(target, TimeZone.getDefault()).epochDay - LocalDay.of(now, TimeZone.getDefault()).epochDay).toInt()
        return when {
            days > 0 -> context.resources.getQuantityString(R.plurals.ime_clock_countdown_until, days, days, title)
            days < 0 -> context.resources.getQuantityString(R.plurals.ime_clock_countdown_since, -days, -days, title)
            else -> context.getString(R.string.ime_clock_countdown_today, title)
        }
    }

    private fun datesText(now: Long): String {
        val d = _state.value.dates
        val zone = TimeZone.getDefault()
        val t = today(now)
        return when (d.mode) {
            DatesMode.BETWEEN -> {
                val from = d.from ?: t
                val to = d.to ?: t
                val dist = DateMath.between(from, to)
                context.resources.getQuantityString(
                    R.plurals.ime_clock_dates_between_text, kotlin.math.abs(dist.days).toInt(),
                    ClockFormats.longDate(from.startMillis(zone), zone),
                    ClockFormats.longDate(to.startMillis(zone), zone),
                    kotlin.math.abs(dist.days).toInt(),
                )
            }
            DatesMode.ADD -> {
                val base = d.base ?: t
                ClockFormats.longDate(DateMath.add(base, d.amount.toLong(), d.unit).startMillis(zone), zone)
            }
            DatesMode.COUNTDOWNS -> _state.value.data.countdowns.joinToString("\n") { countdownText(it.title, it.target, now) }
        }
    }

    private fun historyText(e: HistoryEntry): String {
        val zone = TimeZone.getDefault()
        val head = "${ClockFormats.shortDate(e.at, zone)} ${ClockFormats.time(e.at, zone, _state.value.hour24)}"
        val what = e.label.ifEmpty { context.getString(kindRes(e.kind)) }
        return if (e.kind == HistoryKind.STOPWATCH && e.laps.isNotEmpty()) {
            val lengths = e.laps.mapIndexed { i, at -> at - (if (i == 0) 0 else e.laps[i - 1]) }
            "$head, $what\n" + lapsText(e.durationMs, lengths)
        } else {
            "$head, $what, ${durationText(e.durationMs)}"
        }
    }

    private fun durationText(ms: Long): String = ClockText.duration(ms, seconds = true)

    companion object {
        const val LABEL_MAX = 60
        const val MAX_ZONES = 24
        private const val TIMER_DIGITS = 6
        private const val MAX_TIMER_MS = (99L * 3600 + 59 * 60 + 59) * 1000
        private const val MAX_PLANNER_DAYS = 366
        private const val MAX_ADD = 9999
        private const val DEFAULT_COUNTDOWN_DAYS = 30L

        fun today(now: Long): LocalDay = LocalDay.of(now, TimeZone.getDefault())

        /** "130" is 1 min 30 s, "10000" is 1 h: digits fill from the right, like a microwave. */
        fun msOfDigits(digits: String): Long {
            val padded = digits.padStart(TIMER_DIGITS, '0').takeLast(TIMER_DIGITS)
            val h = padded.substring(0, 2).toLong()
            val m = padded.substring(2, 4).toLong()
            val s = padded.substring(4, 6).toLong()
            return (h * 3600 + m * 60 + s) * 1000
        }

        fun digitsOf(ms: Long): String {
            val sp = Span.of(ms)
            val h = (sp.days * 24 + sp.hours).coerceAtMost(99)
            val text = "%02d%02d%02d".format(java.util.Locale.ROOT, h, sp.minutes, sp.seconds)
            return text.trimStart('0')
        }

        fun kindRes(kind: HistoryKind): Int = when (kind) {
            HistoryKind.ALARM -> R.string.ime_clock_kind_alarm
            HistoryKind.TIMER -> R.string.ime_clock_kind_timer
            HistoryKind.STOPWATCH -> R.string.ime_clock_kind_stopwatch
            HistoryKind.POMODORO -> R.string.ime_clock_kind_pomodoro
        }

        private fun stepDate(d: DatesUi, step: ClockAction.DateStep, today: LocalDay): DatesUi {
            val current = when (step.field) {
                DateField.FROM -> d.from
                DateField.TO -> d.to
                DateField.BASE -> d.base
                DateField.COUNTDOWN -> d.newCountdown
            } ?: today
            return setDate(d, step.field, current.plusMonths(step.months).plusDays(step.days))
        }

        /** Where a date is written month first: the US and the places that follow it. */
        private val MONTH_FIRST_COUNTRIES = setOf("US", "PH", "FM", "MH", "PW", "BZ")

        private fun setDate(d: DatesUi, field: DateField, day: LocalDay?): DatesUi = when (field) {
            DateField.FROM -> d.copy(from = day)
            DateField.TO -> d.copy(to = day)
            DateField.BASE -> d.copy(base = day)
            DateField.COUNTDOWN -> d.copy(newCountdown = day)
        }
    }
}

/** What the Clock panel hands back; one bundle, see `ToolHoldCallbacks.clock`. */
data class ClockCallbacks(
    val state: StateFlow<ClockUi> = MutableStateFlow(ClockUi()),
    val onAction: (ClockAction) -> Unit = {},
)
