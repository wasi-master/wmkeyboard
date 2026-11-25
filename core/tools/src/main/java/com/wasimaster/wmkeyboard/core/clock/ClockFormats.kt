package com.wasimaster.wmkeyboard.core.clock

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The ways a time can go into the field from the clock tool. */
enum class InsertFormat(val id: String) {
    /** "14:05" or "2:05 PM". */
    TIME("time"),

    /** "Wed 14 Oct, 14:05". */
    DATE_TIME("date_time"),

    /** "Wednesday, 14 October 2026". */
    DATE_LONG("date_long"),

    /** "3 Jumada I 1448 AH": the date in the Hijri (Umm al-Qura) calendar (Telegram 12043). */
    HIJRI("hijri"),

    /** "14:05 Tokyo (UTC+9)": the time with the place it belongs to. */
    TIME_PLACE("time_place"),

    /** "2026-10-14T14:05:00+09:00". */
    ISO("iso"),

    /** "in 3 hours", "2 days ago". */
    RELATIVE("relative"),

    /**
     * `<t:1791000000:F>`, which Discord shows in each reader's own time zone,
     * in the [DiscordStyle] the user last held.
     */
    DISCORD("discord"),

    /** Seconds since 1970, for logs and code. */
    UNIX("unix"),
    ;

    companion object {
        fun of(id: String?): InsertFormat? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Discord's timestamp styles, by the letter after the second colon of
 * `<t:seconds:style>`. Discord draws each in the reader's own zone and
 * language; the examples are what an English reader sees.
 */
enum class DiscordStyle(val code: Char) {
    /** "10:23 PM". */
    SHORT_TIME('t'),

    /** "10:23:18 PM". */
    LONG_TIME('T'),

    /** "10/18/2026". */
    SHORT_DATE('d'),

    /** "October 18, 2026". */
    LONG_DATE('D'),

    /** "October 18, 2026 at 10:23 PM". */
    SHORT_DATE_TIME('f'),

    /** "Sunday, October 18, 2026 at 10:23 PM", and what a bare `<t:seconds>` shows. */
    LONG_DATE_TIME('F'),

    /** "in 2 hours", "3 days ago", kept current as the message ages. */
    RELATIVE('R'),

    /** "10/18/2026, 10:23 PM". */
    NUMERIC('s'),

    /** "10/18/2026, 10:23:18 PM". */
    NUMERIC_SECONDS('S'),
    ;

    companion object {
        fun of(code: String?): DiscordStyle? = entries.firstOrNull { it.code.toString() == code }
    }
}

/**
 * Turns instants into text for the field. Locale-aware through the platform's
 * own date formats; the one piece of phrasing that needs words, the relative
 * form, comes in from the caller (Android's `DateUtils` has it in every
 * language).
 */
object ClockFormats {

    /** Hour and minute, 24 h or 12 h, in [zone]. */
    fun time(at: Long, zone: TimeZone, hour24: Boolean, locale: Locale = Locale.getDefault(), seconds: Boolean = false): String {
        val skeleton = when {
            hour24 && seconds -> "Hms"
            hour24 -> "Hm"
            seconds -> "hms"
            else -> "hm"
        }
        return fmt(bestPattern(locale, skeleton), locale, zone).format(Date(at))
    }

    /** Hour and minute without the AM/PM, for a big readout that draws the marker itself. */
    fun timeDigits(at: Long, zone: TimeZone, hour24: Boolean, locale: Locale = Locale.getDefault()): String =
        fmt(if (hour24) "HH:mm" else "h:mm", locale, zone).format(Date(at))

    /** "AM"/"PM" in the user's language, or empty in 24-hour mode. */
    fun amPm(at: Long, zone: TimeZone, hour24: Boolean, locale: Locale = Locale.getDefault()): String =
        if (hour24) "" else fmt("a", locale, zone).format(Date(at))

    fun shortDate(at: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String =
        fmt(bestPattern(locale, "EEEdMMM"), locale, zone).format(Date(at))

    fun longDate(at: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateInstance(DateFormat.FULL, locale).apply { timeZone = zone }.format(Date(at))

    /**
     * The date at [at] in [zone] in the Hijri calendar, Umm al-Qura's tables
     * as most phones show it, in the locale's long style. The snippets'
     * `{date@hijri:…}` reads the same calendar. Empty off the device, where
     * there is no ICU.
     */
    fun hijriDate(at: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String = runCatching {
        val hijri = android.icu.util.ULocale.forLocale(locale).setKeywordValue("calendar", "islamic-umalqura")
        val calendar = android.icu.util.Calendar.getInstance(android.icu.util.TimeZone.getTimeZone(zone.id), hijri)
        android.icu.text.DateFormat.getDateInstance(calendar, android.icu.text.DateFormat.LONG, hijri).format(Date(at))
    }.getOrDefault("")

    fun weekday(at: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): String =
        fmt("EEE", locale, zone).format(Date(at))

    fun iso(at: Long, zone: TimeZone): String {
        val base = fmt("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT, zone).format(Date(at))
        val off = zone.getOffset(at) / 60_000
        if (off == 0) return base + "Z"
        val sign = if (off > 0) '+' else '-'
        val a = kotlin.math.abs(off)
        return "$base$sign${(a / 60).toString().padStart(2, '0')}:${(a % 60).toString().padStart(2, '0')}"
    }

    /** The markup Discord turns into a time in each reader's own zone. */
    fun discord(at: Long, style: DiscordStyle): String = "<t:${at / 1000}:${style.code}>"

    /**
     * Roughly what Discord will draw for [discord] to a reader in [zone], for a
     * chip to show before it is typed. Discord uses the reader's settings, so
     * the words and the 12/24 hours may differ on their screen.
     */
    @Suppress("detekt.LongParameterList")
    fun discordPreview(
        at: Long,
        style: DiscordStyle,
        zone: TimeZone,
        hour24: Boolean,
        relative: (Long) -> String,
        locale: Locale = Locale.getDefault(),
    ): String {
        val date = { kind: Int -> DateFormat.getDateInstance(kind, locale).apply { timeZone = zone }.format(Date(at)) }
        return when (style) {
            DiscordStyle.SHORT_TIME -> time(at, zone, hour24, locale)
            DiscordStyle.LONG_TIME -> time(at, zone, hour24, locale, seconds = true)
            DiscordStyle.SHORT_DATE -> date(DateFormat.SHORT)
            DiscordStyle.LONG_DATE -> date(DateFormat.LONG)
            DiscordStyle.SHORT_DATE_TIME -> "${date(DateFormat.LONG)}, ${time(at, zone, hour24, locale)}"
            DiscordStyle.LONG_DATE_TIME -> "${date(DateFormat.FULL)}, ${time(at, zone, hour24, locale)}"
            DiscordStyle.RELATIVE -> relative(at)
            DiscordStyle.NUMERIC -> "${date(DateFormat.SHORT)}, ${time(at, zone, hour24, locale)}"
            DiscordStyle.NUMERIC_SECONDS -> "${date(DateFormat.SHORT)}, ${time(at, zone, hour24, locale, seconds = true)}"
        }
    }

    /**
     * The instant [dayOffset] days from the date at [now] in [zone], at
     * [hour]:[minute] on that zone's clock: "tomorrow at 12:00 in Kolkata".
     */
    fun atLocal(now: Long, zone: TimeZone, dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * One instant as text in [format].
     *
     * [place] names the zone for [InsertFormat.TIME_PLACE]; [relative] phrases
     * a distance from now for [InsertFormat.RELATIVE]; [discordStyle] is the
     * style [InsertFormat.DISCORD] asks for.
     */
    @Suppress("detekt.LongParameterList")
    fun format(
        format: InsertFormat,
        at: Long,
        zone: TimeZone,
        hour24: Boolean,
        place: String,
        relative: (Long) -> String,
        locale: Locale = Locale.getDefault(),
        discordStyle: DiscordStyle = DiscordStyle.LONG_DATE_TIME,
    ): String = when (format) {
        InsertFormat.TIME -> time(at, zone, hour24, locale)
        InsertFormat.DATE_TIME -> "${shortDate(at, zone, locale)}, ${time(at, zone, hour24, locale)}"
        InsertFormat.DATE_LONG -> longDate(at, zone, locale)
        InsertFormat.HIJRI -> hijriDate(at, zone, locale)
        InsertFormat.TIME_PLACE ->
            "${time(at, zone, hour24, locale)} $place (${utcOffsetLabel(zone.getOffset(at) / 60_000)})"
        InsertFormat.ISO -> iso(at, zone)
        InsertFormat.RELATIVE -> relative(at)
        InsertFormat.DISCORD -> discord(at, discordStyle)
        InsertFormat.UNIX -> (at / 1000).toString()
    }

    /**
     * The same instant in several places, for a message to people spread
     * across them: "15:00 London = 10:00 New York = 19:30 Kolkata". A place
     * whose calendar day differs from the first one's gets its weekday, so
     * nobody turns up a day early.
     */
    fun conversionLine(
        at: Long,
        places: List<Pair<String, TimeZone>>,
        hour24: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (places.isEmpty()) return ""
        val firstDay = dayKey(at, places.first().second)
        return places.joinToString(" = ") { (name, zone) ->
            val t = time(at, zone, hour24, locale)
            val day = if (dayKey(at, zone) != firstDay) " (${weekday(at, zone, locale)})" else ""
            "$t $name$day"
        }
    }

    /**
     * Days between the calendar date at [at] in [zone] and in [reference]:
     * +1 when it is already tomorrow there, -1 when it is still yesterday.
     */
    fun dayShift(at: Long, zone: TimeZone, reference: TimeZone): Int =
        (dayKey(at, zone) - dayKey(at, reference)).toInt().coerceIn(-1, 1)

    /** Minutes [zone] is ahead of [reference] at [at]. */
    fun offsetDelta(at: Long, zone: TimeZone, reference: TimeZone): Int =
        (zone.getOffset(at) - reference.getOffset(at)) / 60_000

    /** A day count that orders days: local midnight's epoch day. */
    fun dayKey(at: Long, zone: TimeZone): Long = Math.floorDiv(at + zone.getOffset(at), 86_400_000L)

    /** "+5:30", "−3", "±0" for an offset delta, with a real minus sign. */
    fun deltaLabel(minutes: Int): String {
        if (minutes == 0) return "±0"
        val sign = if (minutes > 0) "+" else "−"
        val a = kotlin.math.abs(minutes)
        return if (a % 60 == 0) "$sign${a / 60}" else "$sign${a / 60}:${(a % 60).toString().padStart(2, '0')}"
    }

    /** Stopwatch reading: "1:02:03.45", "02:03.45". */
    fun stopwatch(ms: Long, centis: Boolean = true): String {
        val s = Span.of(ms)
        val totalHours = s.days * 24 + s.hours
        val cs = ((ms.coerceAtLeast(0) % 1000) / 10).toInt()
        val mmss = "${two(s.minutes)}:${two(s.seconds)}"
        val head = if (totalHours > 0) "$totalHours:$mmss" else mmss
        return if (centis) "$head.${two(cs)}" else head
    }

    /** Timer reading: "1:02:03", "2:03", rounded up so it reads 0:00 only at the end. */
    fun countdown(ms: Long): String {
        val up = ((ms.coerceAtLeast(0) + 999) / 1000) * 1000
        val s = Span.of(up)
        val totalHours = s.days * 24 + s.hours
        return if (totalHours > 0) "$totalHours:${two(s.minutes)}:${two(s.seconds)}" else "${s.minutes}:${two(s.seconds)}"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun fmt(pattern: String, locale: Locale, zone: TimeZone) =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }

    /**
     * The locale's own pattern for a skeleton ("EEEdMMM" -> "EEE, d MMM" in
     * English, "d MMM EEE" elsewhere), falling back to the skeleton itself off
     * the device.
     */
    private fun bestPattern(locale: Locale, skeleton: String): String =
        runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: FALLBACKS[skeleton]
            ?: skeleton

    private val FALLBACKS = mapOf(
        "EEEdMMM" to "EEE d MMM",
        "Hms" to "HH:mm:ss",
        "Hm" to "HH:mm",
        "hms" to "h:mm:ss a",
        "hm" to "h:mm a",
    )
}
