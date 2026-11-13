package com.wasimaster.wmkeyboard.core.notes.assist

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A fixed zone without summer time, a day-first locale, and Friday 13 November 2026, 10:00 there. */
internal object NoteAssistTestClock {
    val zone: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")
    val locale: Locale = Locale.UK
    val now: Long = at(2026, 11, 13, 10)

    fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    fun find(text: String, anchor: Long = now) = NoteHighlights.find(text, anchor, zone, locale)
}
