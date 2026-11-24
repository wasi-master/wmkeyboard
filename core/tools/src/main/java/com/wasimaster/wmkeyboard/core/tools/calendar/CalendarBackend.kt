package com.wasimaster.wmkeyboard.core.tools.calendar

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarInfo
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarWrite
import com.wasimaster.wmkeyboard.tools.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.TimeZone

/** A busy stretch of time, epoch millis, [end] exclusive. */
data class BusySpan(val start: Long, val end: Long)

/** What a calendar read in a line of text, as quick add would: times in epoch millis, an all-day start at a UTC midnight. */
data class ParsedEvent(
    val title: String,
    val start: Long?,
    val end: Long?,
    val allDay: Boolean,
    val rrule: String?,
    val location: String?,
    val reminders: List<Int>,
)

/**
 * Where the calendar tool's events come from and where a new one goes: the phone's calendar
 * provider ([DeviceCalendarBackend]), or WM Calendar through its suite bridge
 * ([WmCalendarBackend]). Every call blocks; run it off the main thread. Nothing throws: a
 * failure reads as nothing found, or a null.
 *
 * Changing and deleting an event that is already there stays with the phone's provider
 * ([DeviceCalendarWrite]); a backend whose events cannot be changed in place says so through
 * [DeviceCalendarEvent.canModify] and [editsInPlace].
 */
interface CalendarBackend {
    /** [DEVICE] or [WM_CALENDAR]. */
    val id: String

    /** The keyboard's own edits of existing events (move, change, delete) work on these events. */
    val editsInPlace: Boolean

    fun canRead(context: Context): Boolean

    fun canWrite(context: Context): Boolean

    /** Events touching [from] until [until], all-day first, then by start. */
    fun eventsBetween(context: Context, from: Long, until: Long): List<DeviceCalendarEvent>

    /** The local day [date]'s events. */
    fun eventsForDay(context: Context, date: EventDate): List<DeviceCalendarEvent> {
        val start = EventTimes.millis(date, 0, TimeZone.getDefault())
        return eventsBetween(context, start, EventTimes.millis(date.plusDays(1), 0, TimeZone.getDefault()))
    }

    /** The days of [year]-[month] (1-12) with at least one event, for the dots under the grid. */
    fun busyDays(context: Context, year: Int, month: Int): Set<Int> {
        val first = EventDate(year, month, 1)
        val zone = TimeZone.getDefault()
        val from = EventTimes.millis(first, 0, zone)
        val until = EventTimes.millis(first.plusMonths(1), 0, zone)
        return CalendarDays.busyDays(eventsBetween(context, from, until), from, until, first.monthLength)
    }

    /** The calendars a new event can go in, the default one first. */
    fun calendars(context: Context): List<DeviceCalendarInfo>

    /** Adds [draft] as a new event; its id in this backend, or null when it was refused. */
    fun save(context: Context, draft: EventDraft): Long?

    /** [text] read as the backend's quick add reads it; null when it has no reader. */
    fun parse(context: Context, text: String): ParsedEvent?

    /** The busy timed events between [from] and [until]. */
    fun busy(context: Context, from: Long, until: Long): List<BusySpan>

    companion object {
        const val DEVICE = "device"
        const val WM_CALENDAR = "wmcalendar"
    }
}

/** The month grid's dots, from a list of events. */
object CalendarDays {
    fun busyDays(events: List<DeviceCalendarEvent>, from: Long, until: Long, days: Int): Set<Int> {
        val out = HashSet<Int>()
        for (e in events) {
            val begin = if (e.allDay) DeviceCalendar.localMidnightOf(e.begin) else e.begin
            // An end is exclusive: an event to midnight does not mark the next day.
            val end = (if (e.allDay) DeviceCalendar.localMidnightOf(e.end) else e.end).coerceAtLeast(begin + 1) - 1
            val day = Calendar.getInstance()
            day.timeInMillis = begin.coerceAtLeast(from)
            while (day.timeInMillis <= end && day.timeInMillis < until) {
                out.add(day.get(Calendar.DAY_OF_MONTH))
                if (out.size >= days) return out
                day.add(Calendar.DAY_OF_MONTH, 1)
                day.set(Calendar.HOUR_OF_DAY, 0)
                day.set(Calendar.MINUTE, 0)
                day.set(Calendar.SECOND, 0)
                day.set(Calendar.MILLISECOND, 0)
            }
        }
        return out
    }
}

/** The phone's calendar provider, as the tool has always used it. */
object DeviceCalendarBackend : CalendarBackend {
    override val id = CalendarBackend.DEVICE
    override val editsInPlace = true

    override fun canRead(context: Context) = DeviceCalendar.canRead(context)

    override fun canWrite(context: Context) = DeviceCalendar.canWrite(context)

    override fun eventsBetween(context: Context, from: Long, until: Long) = DeviceCalendar.eventsBetween(context, from, until)

    override fun eventsForDay(context: Context, date: EventDate) = DeviceCalendar.eventsForDay(context, date.year, date.month, date.day)

    override fun busyDays(context: Context, year: Int, month: Int) = DeviceCalendar.busyDays(context, year, month)

    override fun calendars(context: Context) = DeviceCalendar.writableCalendars(context)

    override fun save(context: Context, draft: EventDraft): Long? {
        val result = DeviceCalendarWrite.apply(context, EventWrites.create(draft.copy(eventId = null)))
        return result.insertedId.takeIf { result.ok }
    }

    /** The provider reads no text; the tool's own date chips do that before the panel opens. */
    override fun parse(context: Context, text: String): ParsedEvent? = null

    override fun busy(context: Context, from: Long, until: Long): List<BusySpan> =
        eventsBetween(context, from, until).filter { !it.allDay && it.begin < until && it.end > from }.map { BusySpan(it.begin, it.end) }
}

/**
 * WM Calendar, the family's calendar app, through `content://com.wasimaster.wmcalendar.keyboard`.
 * Its events need no calendar permission here: WM Calendar answers apps signed like itself, and
 * this side asks only a provider that [trusted] vouches for, so no other app that takes the
 * authority sees what is saved. Its events are changed in WM Calendar, not here.
 */
class WmCalendarBackend(private val trusted: (Context) -> Boolean) : CalendarBackend {
    override val id = CalendarBackend.WM_CALENDAR
    override val editsInPlace = false

    /** WM Calendar is installed, answers on its authority, and is signed by the family. */
    fun reachable(context: Context): Boolean {
        val provider = runCatching { context.packageManager.resolveContentProvider(WmCalendarWire.AUTHORITY, 0) }.getOrNull() ?: return false
        return provider.packageName == WmCalendarWire.PACKAGE && trusted(context)
    }

    override fun canRead(context: Context) = reachable(context)

    override fun canWrite(context: Context) = reachable(context)

    override fun eventsBetween(context: Context, from: Long, until: Long): List<DeviceCalendarEvent> {
        val noTitle = context.getString(R.string.core_tools_calendar_event_no_title)
        val out = ArrayList<DeviceCalendarEvent>()
        // At most a quarter per call on WM Calendar's side.
        var start = from
        while (start < until) {
            val end = minOf(until, EventTimes.millis(EventDate.of(start, TimeZone.getDefault()).plusDays(WmCalendarWire.MAX_DAYS), 0, TimeZone.getDefault()))
            val body = call(context, WmCalendarWire.INSTANCES, null, WmCalendarWire.span(start, end)) ?: return out
            out += WmCalendarWire.events(body, start, end, noTitle)
            start = end
        }
        return out.distinctBy { it.id to it.begin }.sortedWith(compareByDescending<DeviceCalendarEvent> { it.allDay }.thenBy { it.begin })
    }

    override fun calendars(context: Context): List<DeviceCalendarInfo> =
        call(context, WmCalendarWire.CALENDARS, null, null)?.let(WmCalendarWire::calendars).orEmpty()

    override fun save(context: Context, draft: EventDraft): Long? =
        call(context, WmCalendarWire.SAVE, WmCalendarWire.draft(draft), null)?.let(WmCalendarWire::savedId)

    override fun parse(context: Context, text: String): ParsedEvent? =
        call(context, WmCalendarWire.PARSE, text, null)?.let(WmCalendarWire::parsed)

    override fun busy(context: Context, from: Long, until: Long): List<BusySpan> =
        call(context, WmCalendarWire.BUSY, null, WmCalendarWire.span(from, until))?.let { WmCalendarWire.busy(it, from, until) }.orEmpty()

    /** The answer's JSON body, or null for no answer, an error or a provider that is not WM Calendar. */
    private fun call(context: Context, method: String, arg: String?, extras: Bundle?): String? {
        if (!reachable(context)) return null
        val reply = runCatching { context.contentResolver.call(Uri.parse("content://${WmCalendarWire.AUTHORITY}"), method, arg, extras) }.getOrNull() ?: return null
        if (reply.getString(WmCalendarWire.STATE) != WmCalendarWire.OK) return null
        return reply.getString(WmCalendarWire.JSON) ?: "null"
    }
}

/**
 * The JSON WM Calendar's suite bridge speaks, version 1 (`BRIDGES.md` in WM Calendar). Times are
 * wall times in the phone's zone, `2026-12-03T10:00`; days are ISO dates. Pure, for the tests.
 */
object WmCalendarWire {
    const val PACKAGE = "com.wasimaster.wmcalendar"
    const val AUTHORITY = "com.wasimaster.wmcalendar.keyboard"

    const val INSTANCES = "instances"
    const val CALENDARS = "calendars"
    const val SAVE = "save"
    const val PARSE = "parse"
    const val BUSY = "busy"

    const val STATE = "state"
    const val OK = "ok"
    const val JSON = "json"

    /** The most days one `instances` call returns. */
    const val MAX_DAYS = 92L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val WALL = Regex("""(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?.*""")

    /** `from` and `to` as WM Calendar takes them: the local days [from] and [until] touch, both included. */
    fun span(from: Long, until: Long): Bundle = Bundle().apply {
        val zone = TimeZone.getDefault()
        putString("from", iso(EventDate.of(from, zone)))
        putString("to", iso(EventDate.of(maxOf(from, until - 1), zone)))
    }

    /** Events touching [from] until [until], in the shape the panel draws; tasks left out. */
    fun events(body: String, from: Long, until: Long, noTitle: String): List<DeviceCalendarEvent> =
        array(body).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.bool("task") == true) return@mapNotNull null
            val allDay = o.bool("allDay") == true
            val begin = millis(o.str("start"), allDay) ?: return@mapNotNull null
            val end = (millis(o.str("end"), allDay) ?: begin).let { if (it > begin) it else begin + if (allDay) EventDraft.DAY_MS else 0 }
            // An all-day event is held at UTC midnights, as the phone's provider holds one.
            val shownBegin = if (allDay) DeviceCalendar.localMidnightOf(begin) else begin
            val shownEnd = if (allDay) DeviceCalendar.localMidnightOf(end) else end
            if (shownEnd <= from || shownBegin >= until) return@mapNotNull null
            DeviceCalendarEvent(
                id = o.long("item") ?: o.long("master") ?: return@mapNotNull null,
                title = o.str("title")?.takeIf { it.isNotBlank() } ?: noTitle,
                begin = begin,
                end = end,
                allDay = allDay,
                location = o.str("location")?.takeIf { it.isNotBlank() },
                color = o.long("color")?.toInt() ?: 0,
                calendarId = o.long("calendar") ?: 0L,
                recurring = o.bool("recurring") == true,
                // Changed in WM Calendar, which keeps its history and its undo.
                canModify = false,
            )
        }

    /** The calendars a new event can go in: writable and shown, WM Calendar's default first. */
    fun calendars(body: String): List<DeviceCalendarInfo> =
        array(body).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.bool("writable") == false || o.bool("visible") == false) return@mapNotNull null
            DeviceCalendarInfo(
                id = o.long("id") ?: return@mapNotNull null,
                name = o.str("title").orEmpty(),
                account = "WM Calendar",
                primary = o.bool("default") == true,
                color = o.long("color")?.toInt() ?: 0,
                accountType = PACKAGE,
            )
        }.sortedWith(compareByDescending<DeviceCalendarInfo> { it.primary }.thenBy { it.name.lowercase() })

    /**
     * [d] as WM Calendar's draft: `{title, start, end, allDay, location?, notes?, calendar?, rrule?, reminders}`.
     * Its times are moved into the phone's zone, which is the only one WM Calendar's bridge takes;
     * an all-day end is the day after the last one. Guests stay in the keyboard: the bridge takes none.
     */
    fun draft(d: EventDraft): String = buildJsonObject {
        put("title", d.title)
        if (d.allDay) {
            put("start", iso(d.startDate) + "T00:00")
            put("end", iso(maxOf(d.endDate, d.startDate).plusDays(1)) + "T00:00")
        } else {
            put("start", wall(d.startMillis))
            put("end", wall(maxOf(d.endMillis, d.startMillis)))
        }
        put("allDay", d.allDay)
        if (d.location.isNotBlank()) put("location", d.location)
        if (d.notes.isNotBlank()) put("notes", d.notes)
        if (d.calendarId >= 0) put("calendar", d.calendarId)
        EventWrites.row(d).rrule?.let { put("rrule", it) }
        put("reminders", buildJsonArray { d.reminders.distinct().forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun savedId(body: String): Long? = (parse(body) as? JsonObject)?.long("id")

    fun parsed(body: String): ParsedEvent? {
        val o = parse(body) as? JsonObject ?: return null
        val allDay = o.bool("allDay") == true
        return ParsedEvent(
            title = o.str("title").orEmpty(),
            start = millis(o.str("start"), allDay),
            end = millis(o.str("end"), allDay),
            allDay = allDay,
            rrule = o.str("rrule")?.takeIf { it.isNotBlank() },
            location = o.str("location")?.takeIf { it.isNotBlank() },
            reminders = (o["reminders"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty(),
        )
    }

    fun busy(body: String, from: Long, until: Long): List<BusySpan> =
        array(body).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = millis(o.str("start"), false) ?: return@mapNotNull null
            val end = millis(o.str("end"), false) ?: return@mapNotNull null
            BusySpan(start, end).takeIf { end > start && end > from && start < until }
        }

    /**
     * A wall time (or a bare day) in the phone's zone as epoch millis; for an all-day value the
     * UTC midnight of its day, as the phone's provider stores one. Null when it does not read.
     */
    fun millis(wall: String?, allDay: Boolean): Long? {
        val m = WALL.matchEntire(wall?.trim().orEmpty()) ?: return null
        val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        val c = Calendar.getInstance(if (allDay) EventDraft.UTC else TimeZone.getDefault())
        c.clear()
        if (allDay) {
            c.set(y, mo - 1, d, 0, 0, 0)
        } else {
            c.set(y, mo - 1, d, m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, m.groupValues[6].toIntOrNull() ?: 0)
        }
        return c.timeInMillis
    }

    /** [millis] as a wall time in the phone's zone, to the minute. */
    fun wall(millis: Long): String {
        val c = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = millis }
        return "%04d-%02d-%02dT%02d:%02d".format(
            java.util.Locale.ROOT,
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    fun iso(date: EventDate): String = "%04d-%02d-%02d".format(java.util.Locale.ROOT, date.year, date.month, date.day)

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()
    private fun array(body: String): JsonArray = parse(body) as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}
