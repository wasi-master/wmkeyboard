package com.wasimaster.wmkeyboard.core.notes.assist

import java.util.Calendar
import java.util.TimeZone

/**
 * A "remind me" the assistant offers for a note or a pinned clip (#1293): a
 * deadline still ahead, or the next time a birthday comes round. Nothing is
 * set until the user taps it; then it becomes a calendar event through the
 * Calendar tool (or the phone's calendar app), with the reminder the user's
 * calendar settings give new events.
 */
data class NoteReminder(
    val kind: NoteHighlightKind,
    /** What the event is called: the line the date was found on. */
    val title: String,
    val startMillis: Long,
    val allDay: Boolean,
    val zoneId: String,
    /** A birthday or an anniversary: the event repeats every year. */
    val yearly: Boolean,
    /** The date as the text wrote it, for the chip. */
    val phrase: String,
) {
    val endMillis: Long get() = startMillis + if (allDay) DAY_MS else HOUR_MS

    /** Identity across refreshes, for a list's keys. */
    val key: String get() = "${kind.name}:$startMillis:$title"

    /** The repeat rule a calendar takes, or null for a one-off. */
    val rrule: String? get() = if (yearly) "FREQ=YEARLY" else null

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}

object NoteReminders {

    /** How far ahead a deadline may be and still be offered, in days. */
    const val HORIZON_DAYS = 120

    /** The most reminders one note offers. */
    const val MAX_REMINDERS = 6

    /**
     * The reminders [highlights] call for, soonest first: each
     * [NoteHighlightKind.DEADLINE] from the start of today up to
     * [horizonDays] ahead, and each [NoteHighlightKind.BIRTHDAY]'s next
     * occurrence, whatever year the text gave (the year a person was born is
     * in the past by definition). Plain dates are not offered: a date in a
     * note is as often a record of the past as a plan.
     */
    fun upcoming(
        highlights: List<NoteHighlight>,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        horizonDays: Int = HORIZON_DAYS,
        limit: Int = MAX_REMINDERS,
    ): List<NoteReminder> {
        val todayStart = dayStart(nowMillis, zone)
        val horizon = todayStart + horizonDays * NoteReminder.DAY_MS
        val out = ArrayList<NoteReminder>()
        for (h in highlights) {
            val at = h.whenMillis ?: continue
            val eventZone = h.zoneId?.let(TimeZone::getTimeZone) ?: zone
            when (h.kind) {
                NoteHighlightKind.DEADLINE -> {
                    if (at < todayStart || at > horizon) continue
                    out += NoteReminder(h.kind, title(h), at, allDay = !h.hasTime, zoneId = eventZone.id, yearly = false, phrase = h.text)
                }
                NoteHighlightKind.BIRTHDAY -> {
                    val next = nextYearly(at, nowMillis, eventZone)
                    out += NoteReminder(h.kind, title(h), next, allDay = true, zoneId = eventZone.id, yearly = true, phrase = h.text)
                }
                else -> continue
            }
        }
        return out
            .distinctBy { it.kind to dayStart(it.startMillis, zone) }
            .sortedBy { it.startMillis }
            .take(limit)
    }

    /**
     * The reminders a set of texts calls for together (#1293: the pinned
     * clips), soonest first. Each text's dates resolve against its own
     * anchor, the moment it was copied.
     */
    fun forTexts(
        texts: List<Pair<String, Long>>,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: java.util.Locale = java.util.Locale.getDefault(),
        limit: Int = MAX_REMINDERS,
    ): List<NoteReminder> =
        texts.flatMap { (text, anchor) -> upcoming(NoteHighlights.find(text, anchor, zone, locale), nowMillis, zone, limit = limit) }
            .distinctBy { it.kind to dayStart(it.startMillis, zone) }
            .sortedBy { it.startMillis }
            .take(limit)

    /**
     * The start of the next day, today included, that falls on [millis]'s
     * month and day in [zone]. 29 February answers 28 February in a year
     * without one, the way people keep a leap-day birthday.
     */
    fun nextYearly(millis: Long, nowMillis: Long, zone: TimeZone): Long {
        val source = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val month = source.get(Calendar.MONTH)
        val day = source.get(Calendar.DAY_OF_MONTH)
        val today = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        val todayStart = dayStart(nowMillis, zone)
        var year = today.get(Calendar.YEAR)
        repeat(2) {
            val candidate = Calendar.getInstance(zone).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.DAY_OF_MONTH, day.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            if (candidate.timeInMillis >= todayStart) return candidate.timeInMillis
            year++
        }
        // Unreachable in practice: next year's date is always ahead.
        return todayStart
    }

    /** The event's name: the line, or the date itself when the line is nothing else. */
    private fun title(h: NoteHighlight): String = h.line.ifBlank { h.text }

    /** Midnight at the start of [millis]'s day in [zone]. */
    fun dayStart(millis: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
