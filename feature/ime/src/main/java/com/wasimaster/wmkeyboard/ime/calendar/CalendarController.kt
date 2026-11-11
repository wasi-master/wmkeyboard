package com.wasimaster.wmkeyboard.ime.calendar

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.settings.CalendarSaveMode
import com.wasimaster.wmkeyboard.core.settings.CalendarToolSettings
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarInfo
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarWrite
import com.wasimaster.wmkeyboard.core.tools.EventWeatherRules
import com.wasimaster.wmkeyboard.core.tools.StoredEvent
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import com.wasimaster.wmkeyboard.core.tools.calendar.CalendarOp
import com.wasimaster.wmkeyboard.core.tools.calendar.EditScope
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDraft
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.core.tools.calendar.EventWrites
import com.wasimaster.wmkeyboard.core.tools.calendar.Guest
import com.wasimaster.wmkeyboard.core.tools.calendar.RRule
import com.wasimaster.wmkeyboard.core.tools.calendar.SeriesInfo
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Formatter
import java.util.TimeZone

/**
 * The keyboard half of the Calendar tool: the month view's data, the event
 * detail sheet, and the composer that adds and changes events.
 *
 * Reads and writes go through [DeviceCalendar] and [DeviceCalendarWrite] on
 * the IO dispatcher; what to write is decided by the pure [EventWrites], so
 * splitting a series is the tested code path, not this one. Owned by the
 * service and built on first use; nothing queries the provider until the
 * panel opens.
 */
@Suppress("detekt.LargeClass", "detekt.TooManyFunctions")
internal class CalendarController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> CalendarToolSettings,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
    /** Starts one of the permission trampolines; the answer comes back through [onKeyboardShown]. */
    private val launch: (Intent) -> Unit,
    /** Hands the keys to the composer's box, with what it should start with. */
    private val beginText: (CalendarTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the composer's box right now; it lives in the keyboard's state. */
    private val currentText: () -> CalendarTextEntry?,
    /** What the weather on event cards may do right now (#1212). */
    private val weatherConfig: () -> EventWeatherConfig = { EventWeatherConfig() },
    /** Switches the keyboard to the Weather panel, which draws [forecast]. */
    private val openWeather: () -> Unit = {},
    /** Switches back to this panel, on the given day. */
    private val openCalendarDay: (EventDate) -> Unit = {},
) {

    private val app = context.applicationContext
    private val _state = MutableStateFlow(CalendarUi(today = today()))
    val state: StateFlow<CalendarUi> = _state

    /** The hourly forecast the Weather panel shows for one event; null when it shows its own. */
    private val _forecast = MutableStateFlow<EventForecastUi?>(null)
    val forecast: StateFlow<EventForecastUi?> = _forecast

    val callbacks = CalendarCallbacks(state = state, forecast = forecast, onAction = ::dispatch)

    private val weatherSource = EventWeatherSource()
    private var weatherJob: Job? = null
    private var forecastJob: Job? = null

    private var open = false
    private var monthJob: Job? = null
    private var dayJob: Job? = null
    private var conflictJob: Job? = null
    private var guestJob: Job? = null
    private var observer: ContentObserver? = null

    /** A write waiting on the grant the trampoline is asking for. */
    private var pending: Pending? = null

    private sealed interface Pending {
        data object Save : Pending
        data class Delete(val scope: EditScope?) : Pending
    }

    // ---- Lifecycle -------------------------------------------------------

    fun onPanelOpen(prefill: ToolPrefill?) {
        open = true
        val today = today()
        _state.update {
            it.copy(
                today = today,
                hour24 = android.text.format.DateFormat.is24HourFormat(app),
                resumable = it.draft != null && pending == null,
                view = if (it.draft != null && pending != null) CalendarView.COMPOSE else CalendarView.MONTH,
                detail = null,
                scopePrompt = null,
                undo = null,
            )
        }
        refreshGrants()
        watch()
        when (prefill) {
            is ToolPrefill.Calendar -> showDay(EventDate(prefill.year, prefill.month, prefill.day))
            is ToolPrefill.CalendarEvent -> {
                showDay(EventDate.of(prefill.start, TimeZone.getTimeZone(prefill.zoneId)), reload = false)
                startDraft(draftFromPrefill(prefill))
            }
            // Like any tool, it opens on today.
            else -> showDay(today, reload = false)
        }
        reloadMonth()
        reloadDay()
        finishPending()
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        unwatch()
        _state.update { it.copy(sheet = null, scopePrompt = null, undo = null, guestSuggestions = emptyList()) }
    }

    /** Back from a trampoline: a grant may have changed, and a write may be waiting on it. */
    fun onKeyboardShown() {
        if (!open) return
        refreshGrants()
        reloadMonth()
        reloadDay()
        finishPending()
    }

    private fun refreshGrants() {
        val read = DeviceCalendar.canRead(app)
        _state.update {
            it.copy(
                canRead = read,
                canWrite = DeviceCalendar.canWrite(app),
                contactsAllowed = ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_CONTACTS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }
        if (read && _state.value.calendars.isEmpty()) loadCalendars()
    }

    private fun finishPending() {
        val waiting = pending ?: return
        if (!DeviceCalendar.canWrite(app)) {
            pending = null
            _state.update { it.copy(error = CalendarError.NO_ACCESS) }
            return
        }
        pending = null
        when (waiting) {
            Pending.Save -> save()
            is Pending.Delete -> delete(waiting.scope)
        }
    }

    /** Outside changes (a sync, the calendar app) show while the panel is open. */
    private fun watch() {
        if (observer != null || !DeviceCalendar.canRead(app)) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadMonth()
                reloadDay()
            }
        }
        observer = o
        runCatching { app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o) }
    }

    private fun unwatch() {
        observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    // ---- The composer's text box ---------------------------------------

    private var textField: CalendarTextField? = null

    private fun startText(field: CalendarTextField) {
        val d = _state.value.draft ?: return
        textField = field
        _state.update { it.copy(sheet = null) }
        beginText(
            CalendarTextEntry(
                field,
                when (field) {
                    CalendarTextField.TITLE -> d.title
                    CalendarTextField.LOCATION -> d.location
                    CalendarTextField.NOTES -> d.notes
                    CalendarTextField.GUEST -> ""
                },
            ),
        )
        if (field == CalendarTextField.GUEST) suggestGuests("")
    }

    private fun endTextIfAny() {
        if (textField != null) {
            textField = null
            endText()
            _state.update { it.copy(guestSuggestions = emptyList()) }
        }
    }

    /** Called by the service as the box is typed into. */
    fun onText(entry: CalendarTextEntry) {
        when (entry.field) {
            CalendarTextField.TITLE -> updateDraft { it.copy(title = entry.text.take(TITLE_MAX)) }
            CalendarTextField.LOCATION -> updateDraft { it.copy(location = entry.text.take(TITLE_MAX)) }
            CalendarTextField.NOTES -> updateDraft { it.copy(notes = entry.text.take(NOTES_MAX)) }
            CalendarTextField.GUEST -> suggestGuests(entry.text)
        }
    }

    /**
     * Enter in the box. A title moves on to the place; a guest's address is
     * added and the box stays open for the next; the rest are kept.
     */
    fun onTextEnter(entry: CalendarTextEntry) {
        when (entry.field) {
            CalendarTextField.TITLE -> {
                onText(entry)
                endTextIfAny()
                startText(CalendarTextField.LOCATION)
                return
            }
            CalendarTextField.GUEST -> {
                val typed = entry.text.trim()
                val guest = when {
                    looksLikeEmail(typed) -> Guest(typed)
                    else -> _state.value.guestSuggestions.firstOrNull()
                }
                if (guest != null) {
                    addGuest(guest)
                    beginText(CalendarTextEntry(CalendarTextField.GUEST))
                    suggestGuests("")
                    return
                }
            }
            else -> onText(entry)
        }
        endTextIfAny()
    }

    private fun suggestGuests(query: String) {
        guestJob?.cancel()
        if (!_state.value.contactsAllowed || query.isBlank()) {
            _state.update { it.copy(guestSuggestions = emptyList()) }
            return
        }
        guestJob = scope.launch {
            delay(GUEST_DEBOUNCE_MS)
            val found = withContext(Dispatchers.IO) { DeviceCalendarWrite.guestSuggestions(app, query) }
            val have = _state.value.draft?.guests.orEmpty().map { it.email.lowercase() }.toSet()
            _state.update { it.copy(guestSuggestions = found.filterNot { g -> g.email.lowercase() in have }) }
        }
    }

    // ---- Actions -------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: CalendarAction) {
        when (action) {
            is CalendarAction.Month -> {
                val s = _state.value
                val first = EventDate(s.shownYear, s.shownMonth, 1).plusMonths(action.by)
                _state.update { it.copy(shownYear = first.year, shownMonth = first.month, busyDays = emptySet()) }
                reloadMonth()
            }
            CalendarAction.Today -> showDay(today())
            is CalendarAction.SelectDay -> showDay(action.date)
            is CalendarAction.NewEvent -> {
                val day = action.date ?: _state.value.selected
                if (action.date != null) showDay(day)
                startDraft(newDraft(day))
            }
            CalendarAction.ResumeDraft -> _state.update { it.copy(view = CalendarView.COMPOSE, resumable = false) }
            CalendarAction.DiscardDraft -> _state.update { it.copy(draft = null, draftBase = null, resumable = false, series = null) }
            is CalendarAction.OpenEvent -> openEvent(action.event)
            is CalendarAction.OpenWeather -> openForecast(action.event)
            CalendarAction.ForecastRefresh -> fetchForecast(force = true)
            CalendarAction.ForecastBack -> {
                val day = _forecast.value?.day
                closeForecast()
                day?.let(openCalendarDay)
            }
            CalendarAction.AskRead -> launch(trampoline(com.wasimaster.wmkeyboard.app.CalendarPermissionActivity::class.java))
            CalendarAction.OpenSettings -> openToolSettings()

            CalendarAction.CloseDetail -> _state.update { it.copy(view = CalendarView.MONTH, detail = null, series = null) }
            CalendarAction.EditEvent -> {
                val d = _state.value.detail ?: return
                if (!d.canModify) return
                val draft = DeviceCalendarWrite.draftOf(d, _state.value.series)
                startDraft(draft, keepSeries = true)
            }
            CalendarAction.DeleteEvent -> {
                val d = _state.value.detail ?: return
                if (!d.canModify) return
                if (_state.value.series != null) {
                    _state.update { it.copy(scopePrompt = ScopePrompt(delete = true, scopes = EditScope.entries)) }
                } else {
                    delete(null)
                }
            }
            CalendarAction.InsertEvent -> _state.value.detail?.let { insertText(describe(it)) }
            CalendarAction.OpenInApp -> _state.value.detail?.let { openInApp(it) }

            is CalendarAction.Draft -> updateDraft { action.draft }
            is CalendarAction.Sheet -> {
                endTextIfAny()
                _state.update { it.copy(sheet = if (it.sheet == action.sheet) null else action.sheet) }
            }
            is CalendarAction.Type -> {
                endTextIfAny()
                startText(action.field)
            }
            is CalendarAction.AddGuest -> {
                addGuest(action.guest)
                beginText(CalendarTextEntry(CalendarTextField.GUEST))
                suggestGuests("")
            }
            is CalendarAction.RemoveGuest -> updateDraft { d -> d.copy(guests = d.guests.filterNot { it.email.equals(action.guest.email, true) }) }
            CalendarAction.AskContacts -> launch(trampoline(com.wasimaster.wmkeyboard.app.ContactGuestsPermissionActivity::class.java))
            CalendarAction.Save -> {
                endTextIfAny()
                save()
            }
            CalendarAction.CancelCompose -> {
                endTextIfAny()
                val back = if (_state.value.detail != null && _state.value.editing) CalendarView.DETAIL else CalendarView.MONTH
                _state.update {
                    it.copy(
                        view = back,
                        draft = null,
                        draftBase = null,
                        sheet = null,
                        conflicts = emptyList(),
                        series = if (back == CalendarView.DETAIL) it.series else null,
                    )
                }
            }
            CalendarAction.InsertDraft -> _state.value.draft?.let { insertText(describe(it)) }
            CalendarAction.DraftInApp -> _state.value.draft?.let { d ->
                if (openDraftInApp(d)) {
                    endTextIfAny()
                    _state.update { it.copy(view = CalendarView.MONTH, draft = null, draftBase = null, sheet = null, error = null) }
                }
            }

            is CalendarAction.Scope -> {
                val prompt = _state.value.scopePrompt ?: return
                _state.update { it.copy(scopePrompt = null) }
                if (prompt.delete) delete(action.scope) else write(action.scope)
            }
            CalendarAction.CancelScope -> _state.update { it.copy(scopePrompt = null) }

            CalendarAction.TextDone -> {
                currentText()?.let { entry ->
                    if (entry.field == CalendarTextField.GUEST) {
                        entry.text.trim().takeIf { looksLikeEmail(it) }?.let { addGuest(Guest(it)) }
                    } else {
                        onText(entry)
                    }
                }
                endTextIfAny()
            }
            CalendarAction.Undo -> undo()
            CalendarAction.UndoDismiss -> _state.update { it.copy(undo = null) }
            CalendarAction.ErrorDismiss -> _state.update { it.copy(error = null) }
        }
    }

    // ---- Month and day -----------------------------------------------

    private fun showDay(date: EventDate, reload: Boolean = true) {
        val s = _state.value
        val monthChanged = date.year != s.shownYear || date.month != s.shownMonth
        _state.update {
            it.copy(
                selected = date,
                shownYear = date.year,
                shownMonth = date.month,
                busyDays = if (monthChanged) emptySet() else it.busyDays,
            )
        }
        if (reload) {
            if (monthChanged) reloadMonth()
            reloadDay()
        }
    }

    private fun reloadMonth() {
        if (!DeviceCalendar.canRead(app)) return
        val s = _state.value
        monthJob?.cancel()
        monthJob = scope.launch {
            val days = withContext(Dispatchers.IO) { DeviceCalendar.busyDays(app, s.shownYear, s.shownMonth) }
            if (_state.value.shownYear == s.shownYear && _state.value.shownMonth == s.shownMonth) {
                _state.update { it.copy(busyDays = days) }
            }
        }
    }

    private fun reloadDay() {
        if (!DeviceCalendar.canRead(app)) {
            _state.update { it.copy(dayEvents = emptyList()) }
            return
        }
        val day = _state.value.selected
        dayJob?.cancel()
        dayJob = scope.launch {
            val events = withContext(Dispatchers.IO) { DeviceCalendar.eventsForDay(app, day.year, day.month, day.day) }
            if (_state.value.selected == day) {
                _state.update { it.copy(dayEvents = events) }
                loadWeather(events)
            }
        }
    }

    // ---- Weather on events (#1212) ------------------------------------

    /**
     * The weather beside each of [events] that is still to come and inside
     * the forecast. Kept forecasts are used while fresh; a stale or missing one
     * is fetched only while the network is up and data saving is not holding
     * it back, so the cards never reach the network on a metered connection by
     * themselves.
     */
    private fun loadWeather(events: List<DeviceCalendarEvent>) {
        weatherJob?.cancel()
        val cfg = weatherConfig()
        if (!cfg.enabled) {
            if (_state.value.weather.isNotEmpty()) _state.update { it.copy(weather = emptyMap()) }
            return
        }
        val now = System.currentTimeMillis()
        val wanted = events.mapNotNull { e -> EventWeatherRules.targetMillis(e.begin, e.end, e.allDay, now)?.let { e to it } }
        if (wanted.isEmpty()) return
        weatherJob = scope.launch {
            for ((event, at) in wanted) {
                val chip = chipFor(event, at, cfg) ?: continue
                _state.update { s ->
                    val next = s.weather + (weatherKey(event) to chip)
                    s.copy(weather = if (next.size > MAX_WEATHER_CHIPS) next.entries.drop(next.size - MAX_WEATHER_CHIPS).associate { it.key to it.value } else next)
                }
            }
        }
    }

    /**
     * Where to forecast [event]: the place its location searches to, the
     * user's weather location when it has none (or one no search finds), and
     * nothing when its place has not been searched yet.
     */
    private suspend fun placeFor(event: DeviceCalendarEvent, cfg: EventWeatherConfig): ForecastPlace? {
        val text = event.location?.trim().orEmpty()
        if (text.isEmpty()) return cfg.home
        return when (val hit = weatherSource.place(text, cfg.mayFetchUnasked)) {
            is EventWeatherSource.PlaceHit.Found -> ForecastPlace(hit.place.name, hit.place.latitude, hit.place.longitude, fromEvent = true)
            EventWeatherSource.PlaceHit.NotFound -> cfg.home
            EventWeatherSource.PlaceHit.Unknown -> null
        }
    }

    private suspend fun chipFor(event: DeviceCalendarEvent, at: Long, cfg: EventWeatherConfig): EventWeatherChip? {
        val place = placeFor(event, cfg) ?: return null
        val forecast = weatherSource.forecast(place.latitude, place.longitude, cfg.staleAfterMs, cfg.mayFetchUnasked) ?: return null
        val hour = forecast.hourAt(at) ?: return null
        return EventWeatherChip(
            weatherCode = hour.weatherCode,
            isDay = hour.isDay,
            temperatureC = hour.temperatureC,
            precipProbabilityPercent = hour.precipProbabilityPercent,
            place = place,
            focusMillis = at,
            fahrenheit = cfg.fahrenheit,
        )
    }

    /** The weather on [event]'s card was tapped: the Weather panel opens on that day, at that hour. */
    private fun openForecast(event: DeviceCalendarEvent) {
        val chip = _state.value.weather[weatherKey(event)] ?: return
        val cfg = weatherConfig()
        val zone = TimeZone.getDefault()
        val at = chip.focusMillis
        val day = EventDate.of(at, zone)
        haptic()
        forecastJob?.cancel()
        _forecast.value = EventForecastUi(
            title = event.title,
            place = chip.place,
            day = day,
            dayStart = EventTimes.millis(day, 0, zone),
            dayEnd = EventTimes.millis(day.plusDays(1), 0, zone),
            focusMillis = at,
            fahrenheit = cfg.fahrenheit,
            hour24 = _state.value.hour24,
        )
        openWeather()
        fetchForecast(force = false)
    }

    /**
     * The forecast for the open hourly view. The user asked for it, so data
     * saving does not hold it back (the Weather panel's own fetch does not
     * wait on it either); only being offline does.
     */
    private fun fetchForecast(force: Boolean) {
        val shown = _forecast.value ?: return
        val cfg = weatherConfig()
        forecastJob?.cancel()
        _forecast.update { it?.copy(loading = true, failed = false) }
        forecastJob = scope.launch {
            val got = weatherSource.forecast(
                shown.place.latitude,
                shown.place.longitude,
                staleAfterMs = if (force) 0L else cfg.staleAfterMs,
                mayFetch = cfg.online,
            )
            _forecast.update { cur ->
                if (cur == null || cur.place != shown.place || cur.focusMillis != shown.focusMillis) {
                    cur
                } else {
                    cur.copy(forecast = got ?: cur.forecast, loading = false, failed = got == null)
                }
            }
        }
    }

    /** The Weather panel goes back to its own place: opened from the toolbar, or left by its back arrow. */
    fun closeForecast() {
        forecastJob?.cancel()
        _forecast.value = null
    }

    private fun loadCalendars() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { DeviceCalendar.writableCalendars(app) }
            _state.update { s ->
                // A draft started before the list arrived gets its calendar now.
                val d = s.draft?.takeIf { it.calendarId < 0 }?.copy(calendarId = defaultCalendar(list)?.id ?: -1L)
                s.copy(calendars = list, draft = d ?: s.draft)
            }
        }
    }

    private fun openEvent(event: DeviceCalendarEvent) {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { DeviceCalendarWrite.load(app, event.id, event.begin, event.end) } ?: return@launch
            val series = withContext(Dispatchers.IO) { DeviceCalendarWrite.series(app, stored) }
            _state.update { it.copy(view = CalendarView.DETAIL, detail = stored, series = series, undo = null) }
        }
    }

    // ---- Drafts ----------------------------------------------------------

    private fun newDraft(day: EventDate): EventDraft {
        val cfg = settings()
        val now = Calendar.getInstance()
        val start = if (day == today()) {
            // The next whole hour, so "now" is never already in the past.
            ((now.get(Calendar.HOUR_OF_DAY) + 1) * 60).coerceAtMost(EventDraft.MINUTES_PER_DAY - cfg.defaultDurationMinutes.coerceAtMost(60))
        } else {
            DEFAULT_START_MINUTE
        }
        return EventDraft(
            calendarId = defaultCalendar(_state.value.calendars)?.id ?: -1L,
            startDate = day,
            startMinute = start,
            reminders = if (cfg.defaultReminderMinutes >= 0) listOf(cfg.defaultReminderMinutes) else emptyList(),
        ).withLength(cfg.defaultDurationMinutes)
    }

    private fun draftFromPrefill(p: ToolPrefill.CalendarEvent): EventDraft {
        val zone = TimeZone.getTimeZone(p.zoneId)
        val base = newDraft(EventDate.of(p.start, zone)).copy(title = p.title.take(TITLE_MAX), zoneId = zone.id)
        return if (p.allDay) {
            val last = EventDate.of(p.end - 1, zone)
            base.withAllDay(true).copy(endDate = if (last < base.startDate) base.startDate else last)
        } else {
            val withStart = base.withStart(base.startDate, EventTimes.minuteOfDay(p.start, zone))
            if (p.end > p.start) {
                withStart.withEnd(EventDate.of(p.end, zone), EventTimes.minuteOfDay(p.end, zone))
            } else {
                withStart
            }
        }
    }

    private fun startDraft(draft: EventDraft, keepSeries: Boolean = false) {
        _state.update {
            it.copy(
                view = CalendarView.COMPOSE,
                draft = draft,
                draftBase = draft,
                sheet = null,
                error = null,
                resumable = false,
                undo = null,
                series = if (keepSeries) it.series else null,
                conflicts = emptyList(),
            )
        }
        if (_state.value.calendars.isEmpty() && DeviceCalendar.canRead(app)) loadCalendars()
        checkConflicts()
        // A new event starts with its title box open, so the keys can type straight away.
        if (draft.eventId == null && draft.title.isEmpty()) startText(CalendarTextField.TITLE)
    }

    private fun updateDraft(change: (EventDraft) -> EventDraft) {
        val before = _state.value.draft ?: return
        val after = change(before)
        if (after == before) return
        _state.update { it.copy(draft = after, error = if (it.error == CalendarError.END_BEFORE_START) null else it.error) }
        if (after.startMillis != before.startMillis || after.endMillis != before.endMillis || after.allDay != before.allDay) {
            checkConflicts()
        }
    }

    private fun addGuest(guest: Guest) {
        updateDraft { d ->
            if (d.guests.any { it.email.equals(guest.email, ignoreCase = true) }) d else d.copy(guests = d.guests + guest)
        }
    }

    /** Other busy, timed events in the draft's slot, so a clash shows before Save. */
    private fun checkConflicts() {
        conflictJob?.cancel()
        val d = _state.value.draft
        if (d == null || d.allDay || !DeviceCalendar.canRead(app) || d.endsBeforeStart) {
            _state.update { it.copy(conflicts = emptyList()) }
            return
        }
        conflictJob = scope.launch {
            delay(CONFLICT_DEBOUNCE_MS)
            val found = withContext(Dispatchers.IO) { DeviceCalendar.eventsBetween(app, d.startMillis, d.endMillis) }
                .filter { e ->
                    !e.allDay && e.begin < d.endMillis && e.end > d.startMillis &&
                        e.id != d.eventId && e.id != _state.value.series?.masterId
                }
            _state.update { it.copy(conflicts = found) }
        }
    }

    // ---- Writing -------------------------------------------------------

    private fun save() {
        val d = _state.value.draft ?: return
        if (d.endsBeforeStart) {
            _state.update { it.copy(error = CalendarError.END_BEFORE_START) }
            return
        }
        if (d.eventId == null && settings().saveMode == CalendarSaveMode.CALENDAR_APP) {
            if (openDraftInApp(d)) _state.update { it.copy(view = CalendarView.MONTH, draft = null, draftBase = null) }
            return
        }
        if (!DeviceCalendar.canWrite(app)) {
            pending = Pending.Save
            launch(trampoline(com.wasimaster.wmkeyboard.app.CalendarWritePermissionActivity::class.java))
            return
        }
        val calendars = _state.value.calendars.ifEmpty { DeviceCalendar.writableCalendars(app) }
        val calendarId = d.calendarId.takeIf { id -> calendars.any { it.id == id } } ?: defaultCalendar(calendars)?.id
        if (calendarId == null) {
            _state.update { it.copy(error = CalendarError.NO_CALENDAR, calendars = calendars) }
            return
        }
        val ready = d.copy(calendarId = calendarId)
        _state.update { it.copy(draft = ready, calendars = calendars) }
        val series = _state.value.series
        if (ready.eventId != null && series != null) {
            // An exception row cannot repeat, so a changed rule rules out "only this one".
            val ruleChanged = ready.recurrence != ready.originalRecurrence
            val scopes = if (ruleChanged) listOf(EditScope.FOLLOWING, EditScope.ALL) else EditScope.entries
            _state.update { it.copy(scopePrompt = ScopePrompt(delete = false, scopes = scopes)) }
            return
        }
        write(null)
    }

    private fun write(editScope: EditScope?) {
        val d = _state.value.draft ?: return
        val series = _state.value.series
        val base = _state.value.draftBase
        _state.update { it.copy(busy = true) }
        scope.launch {
            val (result, undoOps) = withContext(Dispatchers.IO) {
                val ops = if (d.eventId == null) {
                    EventWrites.create(d)
                } else {
                    val s = editScope ?: EditScope.ALL
                    val before = if (s == EditScope.FOLLOWING && series != null && RRule.count(series.rule) != null) {
                        DeviceCalendarWrite.occurrencesBefore(app, series.masterId, series.masterStart, series.originalInstanceTime)
                    } else {
                        0
                    }
                    EventWrites.edit(d, series, s, before)
                }
                val result = DeviceCalendarWrite.apply(app, ops)
                result to undoFor(d, base, series, editScope, ops, result.insertedId)
            }
            haptic()
            if (result.ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        view = CalendarView.MONTH,
                        draft = null,
                        draftBase = null,
                        detail = null,
                        series = null,
                        sheet = null,
                        conflicts = emptyList(),
                        undo = undoOps,
                    )
                }
                showDay(d.startDate)
                reloadMonth()
            } else {
                _state.update { it.copy(busy = false, error = CalendarError.WRITE_FAILED) }
            }
        }
    }

    private fun delete(editScope: EditScope?) {
        val event = _state.value.detail ?: return
        if (!DeviceCalendar.canWrite(app)) {
            pending = Pending.Delete(editScope)
            launch(trampoline(com.wasimaster.wmkeyboard.app.CalendarWritePermissionActivity::class.java))
            return
        }
        val series = _state.value.series
        _state.update { it.copy(busy = true) }
        scope.launch {
            val (result, undoOps) = withContext(Dispatchers.IO) {
                val ops = EventWrites.delete(event.id, series, editScope ?: EditScope.ALL)
                val draft = DeviceCalendarWrite.draftOf(event, series)
                val result = DeviceCalendarWrite.apply(app, ops)
                result to deleteUndo(event, draft, series, editScope, result.insertedId)
            }
            haptic()
            if (result.ok) {
                _state.update { it.copy(busy = false, view = CalendarView.MONTH, detail = null, series = null, undo = undoOps) }
                reloadMonth()
                reloadDay()
            } else {
                _state.update { it.copy(busy = false, error = CalendarError.WRITE_FAILED) }
            }
        }
    }

    /**
     * The writes that take a save back, where there is a clean way to: a new
     * event deleted, a new exception dropped, a cut series given its old rule
     * and its new half removed, a rewrite written back as it was.
     */
    private fun undoFor(
        draft: EventDraft,
        base: EventDraft?,
        series: SeriesInfo?,
        editScope: EditScope?,
        ops: List<CalendarOp>,
        inserted: Long?,
    ): CalendarUndo? {
        if (draft.eventId == null) {
            return inserted?.let { CalendarUndo(CalendarUndo.Kind.ADDED, listOf(CalendarOp.Delete(it))) }
        }
        base ?: return null
        val op = ops.firstOrNull() ?: return null
        val back = when {
            op is CalendarOp.InsertException && inserted != null -> listOf(CalendarOp.Delete(inserted))
            op is CalendarOp.SetRule && series != null && inserted != null ->
                listOf(CalendarOp.Delete(inserted), CalendarOp.SetRule(series.masterId, series.rule))
            op is CalendarOp.Update && series == null -> EventWrites.edit(base, null, EditScope.ALL)
            op is CalendarOp.Update && editScope == EditScope.THIS -> listOf(op.copy(row = EventWrites.row(base, single = true), reminders = base.reminders, guests = base.guests))
            else -> return null
        }
        return CalendarUndo(CalendarUndo.Kind.CHANGED, back)
    }

    private fun deleteUndo(event: StoredEvent, draft: EventDraft, series: SeriesInfo?, editScope: EditScope?, inserted: Long?): CalendarUndo? {
        val back = when {
            series == null -> EventWrites.create(draft.copy(eventId = null))
            editScope == EditScope.THIS && inserted != null -> listOf(CalendarOp.Delete(inserted))
            editScope == EditScope.FOLLOWING && series != null && series.originalInstanceTime > series.masterStart ->
                listOf(CalendarOp.SetRule(series.masterId, series.rule))
            editScope == EditScope.ALL || editScope == EditScope.FOLLOWING -> {
                // The whole series back from its own row, as a new event.
                val master = if (event.originalId == null) event else return null
                val whole = DeviceCalendarWrite.draftOf(master.copy(instanceBegin = master.dtStart, instanceEnd = 0), series)
                EventWrites.create(whole.copy(eventId = null))
            }
            else -> return null
        }
        return CalendarUndo(CalendarUndo.Kind.DELETED, back)
    }

    private fun undo() {
        val u = _state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        scope.launch {
            withContext(Dispatchers.IO) { DeviceCalendarWrite.apply(app, u.ops) }
            reloadMonth()
            reloadDay()
        }
    }

    // ---- Out of the keyboard ---------------------------------------------

    private fun openInApp(event: StoredEvent) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.instanceBegin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.instanceEnd)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(intent) }.isFailure) _state.update { it.copy(error = CalendarError.NO_APP) }
    }

    /** The calendar app's own editor, filled in from [d]; false when no app takes it. */
    private fun openDraftInApp(d: EventDraft): Boolean {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, if (d.allDay) localMidnight(d.startDate) else d.startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (d.allDay) localMidnight(d.endDate.plusDays(1)) else d.endMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, d.allDay)
            .putExtra(CalendarContract.Events.TITLE, d.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (d.location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, d.location)
        if (d.notes.isNotBlank()) intent.putExtra(CalendarContract.Events.DESCRIPTION, d.notes)
        EventWrites.row(d).rrule?.let { intent.putExtra(CalendarContract.Events.RRULE, it) }
        if (d.guests.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, d.guests.joinToString(",") { it.email })
        if (!d.allDay && d.zoneId != TimeZone.getDefault().id) intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, d.zoneId)
        val ok = runCatching { app.startActivity(intent) }.isSuccess
        if (!ok) _state.update { it.copy(error = CalendarError.NO_APP) }
        return ok
    }

    private fun trampoline(activity: Class<*>): Intent = Intent(app, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- Text the tool types ---------------------------------------------

    /** "Standup, Mon 19 Oct, 9:00 – 10:00 AM · Room 4", in the user's own date format. */
    fun describe(event: StoredEvent): String {
        val end = event.instanceEnd.takeIf { it > event.instanceBegin }
            ?: event.dtEnd
            ?: (event.instanceBegin + (EventTimes.parseDuration(event.duration) ?: HOUR_MS))
        return line(event.title, event.instanceBegin, end, event.allDay, event.location, event.zone)
    }

    fun describe(d: EventDraft): String = line(d.title, d.startMillis, d.endMillis, d.allDay, d.location, d.zone)

    private fun line(title: String, begin: Long, end: Long, allDay: Boolean, location: String, zone: TimeZone): String {
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
        if (!allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
        if (_state.value.hour24) flags = flags or DateUtils.FORMAT_24HOUR
        // An all-day end is the midnight after the last day; the range is inclusive.
        val shownEnd = if (allDay) (end - 1).coerceAtLeast(begin) else end
        val zoneId = if (allDay) "UTC" else zone.id
        val range = DateUtils.formatDateRange(app, Formatter(StringBuilder(), java.util.Locale.getDefault()), begin, shownEnd, flags, zoneId).toString()
        val head = title.ifBlank { app.getString(R.string.ime_calendar_untitled) }
        val zoneNote = if (!allDay && zone.id != TimeZone.getDefault().id) " " + zone.getDisplayName(zone.inDaylightTime(java.util.Date(begin)), TimeZone.SHORT) else ""
        return buildString {
            append(head).append(", ").append(range).append(zoneNote)
            if (location.isNotBlank()) append(" · ").append(location.trim())
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun defaultCalendar(list: List<DeviceCalendarInfo>): DeviceCalendarInfo? {
        val wanted = settings().defaultCalendarId
        return list.firstOrNull { it.id == wanted } ?: list.firstOrNull()
    }

    private fun localMidnight(date: EventDate): Long = EventTimes.millis(date, 0, TimeZone.getDefault())

    companion object {
        const val TITLE_MAX = 200
        const val NOTES_MAX = 4000
        private const val DEFAULT_START_MINUTE = 9 * 60
        private const val HOUR_MS = 3_600_000L
        private const val CONFLICT_DEBOUNCE_MS = 250L
        private const val GUEST_DEBOUNCE_MS = 150L
        private const val MAX_WEATHER_CHIPS = 200

        fun today(): EventDate = EventDate.of(System.currentTimeMillis(), TimeZone.getDefault())

        /** One `@` with something either side and a dot after it: enough to try, not a validator. */
        fun looksLikeEmail(text: String): Boolean {
            val at = text.indexOf('@')
            return at > 0 && at == text.lastIndexOf('@') && text.indexOf('.', at) > at + 1 &&
                !text.endsWith('.') && text.none { it.isWhitespace() }
        }
    }
}

/** What the Calendar panel hands back; one bundle, see `ToolHoldCallbacks.calendar`. */
data class CalendarCallbacks(
    val state: StateFlow<CalendarUi> = MutableStateFlow(CalendarUi(today = CalendarController.today())),
    /** The hourly forecast for one event, which the Weather panel draws in place of its own (#1212). */
    val forecast: StateFlow<EventForecastUi?> = MutableStateFlow(null),
    val onAction: (CalendarAction) -> Unit = {},
)
