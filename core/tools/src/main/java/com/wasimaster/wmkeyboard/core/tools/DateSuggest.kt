package com.wasimaster.wmkeyboard.core.tools

import java.util.Locale

/**
 * Recognises a forward-pointing date phrase at the end of typed text —
 * "tomorrow", "next friday", "aug 14", "the 15th" — and resolves it against
 * today, so the smart chip can annotate a plan with the date it lands on.
 *
 * Pure arithmetic through [CalendarSystems]'s Julian Day Numbers (minSdk 24,
 * no java.time). Today comes in as a JDN so [find] stays deterministic and
 * testable; only forward dates resolve, because "last friday" needs no
 * annotating and a calendar opened on the past helps nobody.
 */
object DateSuggest {

    /**
     * One recognised phrase. [start] is its offset inside the searched text,
     * so the chip's replace span is `text.length - start`. [annotation] is
     * what a tap appends after the phrase — the piece the phrase itself does
     * not say: "tomorrow (Tue 11 Aug)" but "aug 14 (Friday)" — and [display]
     * is the full resolved date the chip shows.
     */
    data class Hit(
        val phrase: String,
        val start: Int,
        val jdn: Long,
        val annotation: String,
        val display: String,
    )

    private val weekdayShort = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val weekdayFull =
        listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val monthShort =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Every spelling of a weekday → 0 (Sunday) .. 6, matching [CalendarSystems.dayOfWeek]. */
    private val weekdayNames: Map<String, Int> = buildMap {
        fun day(index: Int, vararg names: String) = names.forEach { put(it, index) }
        day(0, "sunday", "sun", "রবিবার")
        day(1, "monday", "mon", "সোমবার")
        day(2, "tuesday", "tue", "tues", "মঙ্গলবার")
        day(3, "wednesday", "wed", "বুধবার")
        day(4, "thursday", "thu", "thur", "thurs", "বৃহস্পতিবার")
        day(5, "friday", "fri", "শুক্রবার")
        day(6, "saturday", "sat", "শনিবার")
    }

    private val monthNames: Map<String, Int> = buildMap {
        fun month(index: Int, vararg names: String) = names.forEach { put(it, index) }
        month(1, "january", "jan")
        month(2, "february", "feb")
        month(3, "march", "mar")
        month(4, "april", "apr")
        month(5, "may")
        month(6, "june", "jun")
        month(7, "july", "jul")
        month(8, "august", "aug")
        month(9, "september", "sep", "sept")
        month(10, "october", "oct")
        month(11, "november", "nov")
        month(12, "december", "dec")
    }

    /** Fixed relative phrases → days from today. Longest first so "day after tomorrow" wins. */
    private val relativeDays: List<Pair<String, Int>> = listOf(
        "day after tomorrow" to 2,
        "আগামীকাল" to 1,
        "পরশু" to 2,
        "tomorrow" to 1,
        "tmrw" to 1,
        "tmr" to 1,
        "next week" to 7,
    )

    private val WEEKDAY_NAMES = weekdayNames.keys.sortedByDescending { it.length }.joinToString("|")
    private val MONTH_NAMES = monthNames.keys.sortedByDescending { it.length }.joinToString("|")

    /**
     * "friday", "next friday", "on fri". The word before the prefix decides
     * whether the phrase points forward at all: "last friday", "every
     * friday" and their kin are talking about something other than one
     * upcoming day, so they get no chip.
     */
    private val WEEKDAY_TAIL = Regex(
        """(?<![\p{L}])(?:(next|this|coming|on)\s+)?($WEEKDAY_NAMES)$""",
        RegexOption.IGNORE_CASE,
    )
    private val WEEKDAY_GUARDS = setOf("last", "every", "each", "since", "past")

    /**
     * The abbreviations are everyday words too — "sun", "wed", "sat" — so
     * they only count with a prefix in front ("on fri", "next sat"); the
     * full names are distinctive enough to stand alone.
     */
    private val shortWeekdayForms =
        setOf("sun", "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat")

    /** "aug 14", "august 14th" / "14 aug", "14th of august". */
    private val MONTH_DAY_TAIL = Regex(
        """(?<![\p{L}])($MONTH_NAMES)\s+(\d{1,2})(?:st|nd|rd|th)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_MONTH_TAIL = Regex(
        """(?<![\w.,])(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_NAMES)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "the 15th" — the next month that has such a day. */
    private val ORDINAL_TAIL = Regex(
        """(?<![\p{L}])the\s+(\d{1,2})(?:st|nd|rd|th)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "next month" keeps the day where the shorter month allows it. */
    private val NEXT_MONTH_TAIL = Regex("""(?<![\p{L}])next\s+month$""", RegexOption.IGNORE_CASE)

    fun find(text: String, todayJdn: Long): Hit? {
        if (todayJdn <= 0 || text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)

        for ((phrase, days) in relativeDays) {
            if (!lower.endsWith(phrase)) continue
            val start = text.length - phrase.length
            // "yesterday" ends in a phrase of its own; any letter glued to
            // the front means the match is the tail of a longer word.
            if (start > 0 && text[start - 1].isLetter()) continue
            val jdn = todayJdn + days
            return hit(text, start, jdn, annotation = dowDate(jdn), todayJdn = todayJdn)
        }

        NEXT_MONTH_TAIL.find(text)?.let { match ->
            val today = CalendarSystems.jdnToGregorian(todayJdn)
            val year = if (today.month == 12) today.year + 1 else today.year
            val month = if (today.month == 12) 1 else today.month + 1
            val day = today.day.coerceAtMost(CalendarSystems.gregorianMonthLength(year, month))
            val jdn = CalendarSystems.gregorianToJdn(year, month, day)
            return hit(text, match.range.first, jdn, annotation = dowDate(jdn), todayJdn = todayJdn)
        }

        WEEKDAY_TAIL.find(text)?.let { match ->
            val before = precedingWord(lower, match.range.first)
            if (before in WEEKDAY_GUARDS) return@let
            val name = match.groupValues[2].lowercase(Locale.ROOT)
            if (name in shortWeekdayForms && match.groupValues[1].isEmpty()) return@let
            val target = weekdayNames.getValue(name)
            // Always the soonest future occurrence — deliberately for "next
            // friday" too, where English speakers themselves disagree on the
            // week; the chip shows the date it chose, which is the answer to
            // exactly that ambiguity.
            val delta = ((target - CalendarSystems.dayOfWeek(todayJdn)) + 7).let {
                if (it % 7 == 0) 7 else it % 7
            }
            val jdn = todayJdn + delta
            val date = CalendarSystems.jdnToGregorian(jdn)
            return hit(
                text, match.range.first, jdn,
                annotation = "${date.day} ${monthShort[date.month - 1]}",
                todayJdn = todayJdn,
            )
        }

        val monthDay = MONTH_DAY_TAIL.find(text)?.let { match ->
            Triple(match.range.first, match.groupValues[1], match.groupValues[2])
        } ?: DAY_MONTH_TAIL.find(text)?.let { match ->
            Triple(match.range.first, match.groupValues[2], match.groupValues[1])
        }
        monthDay?.let { (startIndex, monthName, dayText) ->
            val month = monthNames.getValue(monthName.lowercase(Locale.ROOT))
            val day = dayText.toIntOrNull() ?: return@let
            val today = CalendarSystems.jdnToGregorian(todayJdn)
            // This year if the date is still ahead (today included), else next.
            val year = today.year.let {
                if (day in 1..CalendarSystems.gregorianMonthLength(it, month) &&
                    CalendarSystems.gregorianToJdn(it, month, day) >= todayJdn
                ) {
                    it
                } else {
                    it + 1
                }
            }
            if (day !in 1..CalendarSystems.gregorianMonthLength(year, month)) return@let
            val jdn = CalendarSystems.gregorianToJdn(year, month, day)
            return hit(
                text, startIndex, jdn,
                annotation = weekdayFull[CalendarSystems.dayOfWeek(jdn)],
                todayJdn = todayJdn,
            )
        }

        ORDINAL_TAIL.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            if (day !in 1..31) return@let
            val today = CalendarSystems.jdnToGregorian(todayJdn)
            var year = today.year
            var month = today.month
            // The next month that actually has this day, today's included.
            repeat(3) {
                if (day <= CalendarSystems.gregorianMonthLength(year, month) &&
                    CalendarSystems.gregorianToJdn(year, month, day) >= todayJdn
                ) {
                    val jdn = CalendarSystems.gregorianToJdn(year, month, day)
                    return hit(text, match.range.first, jdn, annotation = dowDate(jdn), todayJdn = todayJdn)
                }
                if (month == 12) { month = 1; year++ } else month++
            }
        }
        return null
    }

    private fun hit(text: String, start: Int, jdn: Long, annotation: String, todayJdn: Long): Hit {
        val date = CalendarSystems.jdnToGregorian(jdn)
        val today = CalendarSystems.jdnToGregorian(todayJdn)
        val display = buildString {
            append(weekdayShort[CalendarSystems.dayOfWeek(jdn)])
            append(", ")
            append(date.day)
            append(' ')
            append(monthShort[date.month - 1])
            // The year only when it is not this year — "Fri, 2 Jan 2027".
            if (date.year != today.year) {
                append(' ')
                append(date.year)
            }
        }
        return Hit(text.substring(start), start, jdn, annotation, display)
    }

    /**
     * The day [jdn] written with [pattern], a `SimpleDateFormat` pattern such
     * as "yyyy-MM-dd" (#1089), in the phone's language; null when the
     * pattern does not read, so the chip falls back to its own wording.
     */
    fun format(jdn: Long, pattern: String): String? {
        val date = CalendarSystems.jdnToGregorian(jdn)
        return runCatching {
            val day = java.util.GregorianCalendar(date.year, date.month - 1, date.day)
            java.text.SimpleDateFormat(pattern, Locale.getDefault()).apply { calendar = day }.format(day.time)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** "Tue 11 Aug" — for phrases that name neither the weekday nor the date. */
    private fun dowDate(jdn: Long): String {
        val date = CalendarSystems.jdnToGregorian(jdn)
        return "${weekdayShort[CalendarSystems.dayOfWeek(jdn)]} ${date.day} ${monthShort[date.month - 1]}"
    }

    private fun precedingWord(lower: String, start: Int): String {
        var end = start
        while (end > 0 && lower[end - 1] == ' ') end--
        var begin = end
        while (begin > 0 && lower[begin - 1].isLetter()) begin--
        return lower.substring(begin, end)
    }
}
