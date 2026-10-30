package com.wasimaster.wmkeyboard.ime.ui.calendar

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.core.tools.calendar.Frequency
import com.wasimaster.wmkeyboard.core.tools.calendar.NthWeekday
import com.wasimaster.wmkeyboard.core.tools.calendar.Recurrence
import com.wasimaster.wmkeyboard.core.tools.calendar.RepeatEnd
import com.wasimaster.wmkeyboard.ime.R
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Formatter
import java.util.Locale
import java.util.TimeZone

/** Words for the calendar panel: dates, times, repeat rules, reminders. */
internal object CalendarText {

    /** "Mon, 19 Oct", with the year when it is not this one, in the user's own order. */
    fun date(date: EventDate, today: EventDate): String {
        val skeleton = if (date.year == today.year) "EEEdMMM" else "EEEdMMMy"
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(EventTimes.millis(date, 0, UTC)))
    }

    /** "Monday, 19 October 2026", for the selected day. */
    fun longDate(date: EventDate): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMy")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(EventTimes.millis(date, 0, UTC)))
    }

    /** "19 Oct", for a yearly rule. */
    fun dayMonth(date: EventDate): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(EventTimes.millis(date, 0, UTC)))
    }

    /** "October 2026". */
    fun month(year: Int, month: Int): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMy")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(EventTimes.millis(EventDate(year, month, 1), 0, UTC)))
    }

    /** "Oct 2026": the month for a header that has five controls beside it. */
    fun monthShort(year: Int, month: Int): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMy")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(EventTimes.millis(EventDate(year, month, 1), 0, UTC)))
    }

    /** "9:30 AM" or "09:30", as the user's clock is set. */
    fun time(minute: Int, hour24: Boolean): String {
        val h = minute / 60
        val m = minute % 60
        if (hour24) return "%02d:%02d".format(Locale.getDefault(), h, m)
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "hmma")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = UTC }
            .format(Date(h * HOUR_MS + m * MINUTE_MS))
    }

    /** An event's span, "Mon, 19 Oct, 9:00 – 10:00 AM", or the days of an all-day one. */
    fun range(context: Context, begin: Long, end: Long, allDay: Boolean, zone: TimeZone, hour24: Boolean): String {
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
        if (!allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
        if (hour24) flags = flags or DateUtils.FORMAT_24HOUR
        val shownEnd = if (allDay) (end - 1).coerceAtLeast(begin) else end
        return DateUtils.formatDateRange(
            context, Formatter(StringBuilder(), Locale.getDefault()), begin, shownEnd, flags,
            if (allDay) "UTC" else zone.id,
        ).toString()
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L
}

/** Short weekday names, Sunday first, in the user's language. */
internal fun weekdayShortNames(): List<String> =
    DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays.drop(1)

/** Full weekday names, Sunday first. */
internal fun weekdayFullNames(): List<String> =
    DateFormatSymbols.getInstance(Locale.getDefault()).weekdays.drop(1)

/** "second Tuesday", "last Friday". */
@Composable
internal fun nthWeekdayText(nth: NthWeekday): String {
    val ordinals = stringArrayResource(R.array.ime_calendar_ordinals)
    val ordinal = if (nth.ordinal < 0) stringResource(R.string.ime_calendar_ordinal_last) else ordinals.getOrElse(nth.ordinal - 1) { "" }
    return stringResource(R.string.ime_calendar_nth_weekday, ordinal, weekdayFullNames()[nth.weekday])
}

/** A rule in words: "Every 2 weeks on Mon, Wed, until 3 Dec". */
@Composable
internal fun recurrenceText(r: Recurrence?, start: EventDate, today: EventDate): String {
    if (r == null) return stringResource(R.string.ime_calendar_repeat_none)
    if (r.isCustom) return stringResource(R.string.ime_calendar_repeat_custom)
    val n = r.interval
    val head = when (r.frequency) {
        Frequency.DAILY -> pluralStringResource(R.plurals.ime_calendar_repeat_daily, n, n)
        Frequency.WEEKLY -> if (r.isWorkdays) {
            stringResource(R.string.ime_calendar_repeat_workdays)
        } else {
            val names = weekdayShortNames()
            val days = (0 until 7).filter { Recurrence.has(r.weekdays, it) }.joinToString(", ") { names[it] }
            pluralStringResource(R.plurals.ime_calendar_repeat_weekly, n, n, days)
        }
        Frequency.MONTHLY -> r.nth?.let { pluralStringResource(R.plurals.ime_calendar_repeat_monthly_nth, n, n, nthWeekdayText(it)) }
            ?: pluralStringResource(R.plurals.ime_calendar_repeat_monthly_day, n, n, start.day)
        Frequency.YEARLY -> pluralStringResource(R.plurals.ime_calendar_repeat_yearly, n, n, CalendarText.dayMonth(start))
    }
    val tail = when (val end = r.end) {
        RepeatEnd.Never -> ""
        is RepeatEnd.Until -> stringResource(R.string.ime_calendar_repeat_until, CalendarText.date(end.date, today))
        is RepeatEnd.Count -> pluralStringResource(R.plurals.ime_calendar_repeat_count, end.times, end.times)
    }
    return head + tail
}

/** "10 minutes before", "At the start". */
@Composable
internal fun reminderText(minutes: Int): String = when {
    minutes < 0 -> stringResource(R.string.ime_calendar_reminder_none)
    minutes == 0 -> stringResource(R.string.ime_calendar_reminder_at_start)
    minutes % 1440 == 0 -> pluralStringResource(R.plurals.ime_calendar_reminder_days, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.ime_calendar_reminder_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.ime_calendar_reminder_minutes, minutes, minutes)
}

/** "30 min", "1 h", "1 h 30 min". */
@Composable
internal fun lengthText(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.ime_calendar_minutes_short, minutes)
    minutes % 60 == 0 -> stringResource(R.string.ime_calendar_hours_short, minutes / 60)
    else -> stringResource(R.string.ime_calendar_hours_minutes_short, minutes / 60, minutes % 60)
}

@Composable
internal fun rangeText(begin: Long, end: Long, allDay: Boolean, zone: TimeZone, hour24: Boolean): String =
    CalendarText.range(LocalContext.current, begin, end, allDay, zone, hour24)
