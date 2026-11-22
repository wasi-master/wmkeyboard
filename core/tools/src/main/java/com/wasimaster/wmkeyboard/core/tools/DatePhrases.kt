package com.wasimaster.wmkeyboard.core.tools

import java.text.Normalizer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Date phrases in every language the phone's ICU knows, for no data of our
 * own: "morgen", "nächsten Freitag", "dans 3 jours", "14 de agosto",
 * "через 5 дней", "明後日", "8月14日", "আগামীকাল", "خلال يومين".
 *
 * Each language's table is built the first time it is asked for, from what
 * Android already ships:
 * - `android.icu.text.RelativeDateTimeFormatter` (API 24) says the language's
 *   own "tomorrow", "day after tomorrow", "next week", "next month", "next
 *   Friday" and "this Friday", in its long, short and narrow styles, and the
 *   "in N days / weeks / months" templates, read back by formatting sample
 *   counts. The backward forms ("yesterday", "last Friday") go in too, so a
 *   phrase that points back silences the match instead of being half-read.
 * - `java.text.DateFormatSymbols` and `android.icu.text.DateFormatSymbols`
 *   give the weekday and month names, wide and abbreviated, in their format
 *   and stand-alone forms. `java.text` is there for the JVM, whose own CLDR
 *   data answers the same question when `android.icu` is a stub.
 * - `android.icu.text.DateTimePatternGenerator` gives the order a day and a
 *   month name are written in ("d. MMMM", "d 'de' MMMM", "M月d日").
 *
 * A short list of hand entries ([EXTRAS]) adds what ICU does not say: the
 * Bengali spellings and ordinal endings people type, and the few words that
 * turn a phrase into something else ("guten Morgen" is not tomorrow).
 *
 * Matching is case- and accent-insensitive ([fold]): marks are taken off,
 * digits of any script read as ASCII, so "ubermorgen", "manana" and "১৪
 * আগস্ট" all count. Only forward dates resolve, except "today", which a note
 * may want and [DateSuggest] drops.
 */
object DatePhrases {

    /** What a phrase says about the day it means, so an annotation can add only the rest. */
    enum class Names { NOTHING, WEEKDAY, DATE }

    /**
     * One phrase found in a text: it spans [start] until [end] of the text as
     * given, means the day [jdn], and was read in [language].
     */
    data class Match(val start: Int, val end: Int, val jdn: Long, val names: Names, val language: String)

    /** What a fixed phrase means, counted from today. */
    internal sealed interface Meaning {
        data class Days(val days: Int) : Meaning
        data class Weekday(val dow: Int) : Meaning

        /** A weekday of a given week: "来週の金曜日" is next week's Friday, not the soonest one. */
        data class WeekdayOfWeek(val weeks: Int, val dow: Int) : Meaning
        data class Months(val months: Int) : Meaning
        data object Backward : Meaning
    }

    internal enum class CountUnit { DAY, WEEK, MONTH }

    internal class Counted(val regex: Regex, val unit: CountUnit)

    internal class DayMonth(val regex: Regex, val dayGroup: Int, val monthGroup: Int, val yearGroup: Int, val named: Boolean)

    internal class Table(
        val language: String,
        val locale: Locale,
        val fixed: Map<String, Meaning>,
        val counted: List<Counted>,
        val months: Map<String, Int>,
        val dayMonth: List<DayMonth>,
        val guards: Map<String, Set<String>>,
        val unspaced: Boolean,
    ) {
        /** Every fixed phrase, longest first, as one alternation; null when there are none. */
        val fixedRegex: Regex? = fixed.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            val alternatives = keys.sortedByDescending { it.length }.joinToString("|") { literal(it) }
            Regex(before(unspaced) + "($alternatives)" + after(unspaced))
        }
    }

    /** The languages whose words run on without spaces, so a phrase may sit right against a letter. */
    private val UNSPACED = setOf("ja", "zh", "yue", "wuu", "th", "lo", "km", "my", "bo")

    private const val MAX_DAYS = 366
    private const val MAX_WEEKS = 52
    private const val MAX_MONTHS = 24
    private const val DAYS_IN_WEEK = 7
    private const val MONTHS_IN_YEAR = 12

    /** Counts formatted to read back the "in N days" templates; enough to meet every plural form. */
    private val SAMPLE_COUNTS = intArrayOf(1, 2, 3, 4, 5, 6, 10, 11, 12, 20, 21, 22, 25, 100, 101, 102)

    /**
     * What ICU leaves out (see the class comment). [fixed] phrases and
     * [monthAliases] are written as people type them and folded on use;
     * [nextWords] go before a weekday to mean the coming one; [daySuffix] is
     * a regex after a day number; [guards] are words that, right before a
     * phrase, make it mean something else, keyed by the folded phrase or `*`
     * for every relative phrase and bare weekday.
     */
    private class Extra(
        val fixed: Map<String, Meaning> = emptyMap(),
        val nextWords: List<String> = emptyList(),
        val monthAliases: Map<String, Int> = emptyMap(),
        val daySuffix: String = "",
        val guards: Map<String, Set<String>> = emptyMap(),
    )

    private val EXTRAS: Map<String, Extra> = mapOf(
        // The English hand rules in DateSuggest run first; these keep ICU's
        // English table from reading what those rules turned down.
        "en" to Extra(
            daySuffix = "(?:st|nd|rd|th)?",
            guards = mapOf("*" to setOf("last", "every", "each", "since", "past")),
        ),
        // ICU says "আগামী পরশু" and "পরের সপ্তাহ"; people also write the bare
        // পরশু and আগামী সপ্তাহ, the dirgho-i month spellings and ১লা, ২০শে.
        "bn" to Extra(
            fixed = mapOf(
                "পরশু" to Meaning.Days(2),
                "আজকে" to Meaning.Days(0),
                "আগামী সপ্তাহ" to Meaning.Days(DAYS_IN_WEEK),
                "আগামী মাস" to Meaning.Months(1),
            ),
            nextWords = listOf("আগামী"),
            monthAliases = mapOf(
                "জানুয়ারি" to 1, "জানুয়ারী" to 1, "ফেব্রুয়ারি" to 2, "ফেব্রুয়ারী" to 2,
                "আগষ্ট" to 8, "অগাস্ট" to 8,
            ),
            daySuffix = "(?:লা|রা|ঠা|ই|শে)?",
        ),
        // "morgen" is also the morning: "guten Morgen", "heute Morgen".
        "de" to Extra(
            guards = mapOf(
                "morgen" to setOf("guten", "heute", "am", "jeden", "den", "der", "dem", "diesen", "vom", "bis"),
                "*" to setOf("jeden", "seit", "letzten", "vergangenen"),
            ),
        ),
        // "mañana" is also the morning: "por la mañana", "esta mañana".
        "es" to Extra(
            guards = mapOf(
                "manana" to setOf("la", "esta", "cada", "por", "de", "una"),
                "*" to setOf("cada", "los", "desde"),
            ),
        ),
        "pt" to Extra(guards = mapOf("*" to setOf("cada", "desde", "todas", "todos"))),
        "fr" to Extra(guards = mapOf("*" to setOf("chaque", "depuis", "les", "tous"))),
        "it" to Extra(guards = mapOf("*" to setOf("ogni", "da"))),
        // "среда" is also the environment.
        "ru" to Extra(guards = mapOf("среда" to setOf("окружающая", "рабочая", "социальная", "языковая"))),
    )

    private val tables = ConcurrentHashMap<String, Table>()

    /**
     * The phrase that ends [text], or null: the longest one any of
     * [languages] (or the text's script) reads there. A phrase pointing back
     * ("letzten Freitag") ends the search with null, and a guard word in
     * front of a phrase ("guten Morgen") rules it out.
     */
    fun findAtEnd(text: String, todayJdn: Long, languages: List<String>): Match? {
        if (todayJdn <= 0 || text.isBlank()) return null
        val folded = fold(text)
        var best: Pair<Candidate, Table>? = null
        for (table in tablesFor(languages, text)) {
            val candidate = candidates(folded.text, table, atEnd = true).minByOrNull { it.start } ?: continue
            if (best == null || candidate.start < best.first.start) best = candidate to table
        }
        val (candidate, table) = best ?: return null
        val jdn = resolve(candidate, todayJdn, table) ?: return null
        return Match(folded.start(candidate.start), folded.end(candidate.end), jdn, candidate.names, table.language)
    }

    /**
     * Every phrase in [text], in order and without overlaps, read in each
     * of [languages] and in the language of any script the text is written
     * in. Backward phrases take their span and give nothing.
     */
    fun findAll(text: String, todayJdn: Long, languages: List<String>): List<Match> {
        if (todayJdn <= 0 || text.isBlank()) return emptyList()
        val folded = fold(text)
        val found = ArrayList<Pair<Candidate, Table>>()
        for (table in tablesFor(languages, text)) {
            for (candidate in candidates(folded.text, table, atEnd = false)) found += candidate to table
        }
        // Earliest first, the longest of those that start together.
        found.sortWith(compareBy<Pair<Candidate, Table>> { it.first.start }.thenByDescending { it.first.end })
        val out = ArrayList<Match>()
        var claimedTo = -1
        for ((candidate, table) in found) {
            if (candidate.start < claimedTo) continue
            claimedTo = candidate.end
            val jdn = resolve(candidate, todayJdn, table) ?: continue
            out += Match(folded.start(candidate.start), folded.end(candidate.end), jdn, candidate.names, table.language)
        }
        return out
    }

    /**
     * [jdn] for a chip in [language]'s own words: "Fr., 14. Aug." for the
     * display, and the annotation a tap adds, which says what the phrase did
     * not — the date after a weekday, the weekday after a date, both after
     * "morgen". The year shows only when it is not this year's.
     */
    fun describe(jdn: Long, todayJdn: Long, language: String, names: Names): Pair<String, String> {
        val locale = localeOf(language)
        val date = CalendarSystems.jdnToGregorian(jdn)
        val sameYear = date.year == CalendarSystems.jdnToGregorian(todayJdn).year
        val annotation = when (names) {
            Names.WEEKDAY -> format(jdn, locale, if (sameYear) "MMMd" else "yMMMd", "d MMM")
            Names.DATE -> format(jdn, locale, "EEEE", "EEEE")
            Names.NOTHING -> format(jdn, locale, if (sameYear) "EEEMMMd" else "EEEyMMMd", "EEE d MMM")
        }
        val display = format(jdn, locale, if (sameYear) "EEEMMMd" else "EEEyMMMd", if (sameYear) "EEE, d MMM" else "EEE, d MMM yyyy")
        return annotation to display
    }

    // ---- matching ----

    private class Candidate(val start: Int, val end: Int, val names: Names, val resolve: (Long, Table) -> Long?)

    private fun candidates(text: String, table: Table, atEnd: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        fun scan(regex: Regex): Sequence<MatchResult> =
            if (atEnd) {
                anchored(regex).find(text)?.let { sequenceOf(it) } ?: emptySequence()
            } else {
                regex.findAll(text)
            }

        table.fixedRegex?.let { regex ->
            for (match in scan(regex)) {
                val key = match.groupValues[1]
                val meaning = table.fixed[key] ?: continue
                val start = match.groups[1]!!.range.first
                val end = match.groups[1]!!.range.last + 1
                if (meaning != Meaning.Backward && guarded(text, start, key, table)) continue
                out += Candidate(
                    start, end,
                    names = if (meaning is Meaning.Weekday || meaning is Meaning.WeekdayOfWeek) Names.WEEKDAY else Names.NOTHING,
                ) { today, t -> fixedDay(meaning, today, t) }
            }
        }
        for (counted in table.counted) {
            for (match in scan(counted.regex)) {
                val n = match.groupValues[1].toIntOrNull() ?: continue
                val limit = when (counted.unit) {
                    CountUnit.DAY -> MAX_DAYS
                    CountUnit.WEEK -> MAX_WEEKS
                    CountUnit.MONTH -> MAX_MONTHS
                }
                if (n !in 1..limit) continue
                val range = match.groups[0]!!.range
                out += Candidate(range.first, range.last + 1, Names.NOTHING) { today, _ ->
                    when (counted.unit) {
                        CountUnit.DAY -> today + n
                        CountUnit.WEEK -> today + DAYS_IN_WEEK.toLong() * n
                        CountUnit.MONTH -> addMonths(today, n)
                    }
                }
            }
        }
        for (shape in table.dayMonth) {
            for (match in scan(shape.regex)) {
                val day = match.groupValues[shape.dayGroup].toIntOrNull() ?: continue
                val monthText = match.groupValues[shape.monthGroup]
                val month = if (shape.named) {
                    table.months[monthText] ?: table.months[monthText.trimEnd('.')] ?: continue
                } else {
                    monthText.toIntOrNull() ?: continue
                }
                val year = if (shape.yearGroup > 0) match.groupValues[shape.yearGroup].toIntOrNull() else null
                val range = match.groups[0]!!.range
                out += Candidate(range.first, range.last + 1, Names.DATE) { today, _ -> monthDay(month, day, year, today) }
            }
        }
        return out
    }

    private val anchoredCache = ConcurrentHashMap<Regex, Regex>()

    private fun anchored(regex: Regex): Regex = anchoredCache.getOrPut(regex) { Regex("(?:" + regex.pattern + ")$") }

    /** Whether the word right before [start] is one of the table's guard words for [key]. */
    private fun guarded(text: String, start: Int, key: String, table: Table): Boolean {
        if (table.guards.isEmpty()) return false
        var end = start
        while (end > 0 && text[end - 1] == ' ') end--
        var begin = end
        while (begin > 0 && isWordChar(text[begin - 1])) begin--
        if (begin == end) return false
        val word = text.substring(begin, end)
        return word in table.guards["*"].orEmpty() || word in table.guards[key].orEmpty()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun resolve(candidate: Candidate, todayJdn: Long, table: Table): Long? =
        candidate.resolve(todayJdn, table)?.takeIf { it >= todayJdn }

    private fun fixedDay(meaning: Meaning, today: Long, table: Table): Long? = when (meaning) {
        is Meaning.Days -> today + meaning.days
        is Meaning.Weekday -> {
            // The soonest one ahead, as DateSuggest reads "next friday".
            val delta = (meaning.dow - CalendarSystems.dayOfWeek(today) + DAYS_IN_WEEK) % DAYS_IN_WEEK
            today + if (delta == 0) DAYS_IN_WEEK else delta
        }
        is Meaning.WeekdayOfWeek -> {
            val first = (firstDayOfWeek(table.locale) - 1).coerceIn(0, DAYS_IN_WEEK - 1)
            val weekStart = today - (CalendarSystems.dayOfWeek(today) - first + DAYS_IN_WEEK) % DAYS_IN_WEEK
            (weekStart + DAYS_IN_WEEK.toLong() * meaning.weeks + (meaning.dow - first + DAYS_IN_WEEK) % DAYS_IN_WEEK)
                .takeIf { it > today }
        }
        is Meaning.Months -> addMonths(today, meaning.months)
        Meaning.Backward -> null
    }

    private fun firstDayOfWeek(locale: Locale): Int =
        runCatching { Calendar.getInstance(locale).firstDayOfWeek }.getOrDefault(Calendar.SUNDAY)

    /** [months] on from [jdn], the day kept where the shorter month allows. */
    private fun addMonths(jdn: Long, months: Int): Long {
        val today = CalendarSystems.jdnToGregorian(jdn)
        val index = today.year * MONTHS_IN_YEAR + (today.month - 1) + months
        val year = index / MONTHS_IN_YEAR
        val month = index % MONTHS_IN_YEAR + 1
        val day = today.day.coerceAtMost(CalendarSystems.gregorianMonthLength(year, month))
        return CalendarSystems.gregorianToJdn(year, month, day)
    }

    /** A day and month: in [year] when one was written, else this year while still ahead, else next. */
    private fun monthDay(month: Int, day: Int, year: Int?, todayJdn: Long): Long? {
        if (month !in 1..MONTHS_IN_YEAR) return null
        if (year != null) {
            if (day !in 1..CalendarSystems.gregorianMonthLength(year, month)) return null
            return CalendarSystems.gregorianToJdn(year, month, day)
        }
        val thisYear = CalendarSystems.jdnToGregorian(todayJdn).year
        for (y in thisYear..thisYear + 1) {
            if (day !in 1..CalendarSystems.gregorianMonthLength(y, month)) continue
            val jdn = CalendarSystems.gregorianToJdn(y, month, day)
            if (jdn >= todayJdn) return jdn
        }
        return null
    }

    // ---- tables ----

    private fun tablesFor(languages: List<String>, text: String): List<Table> =
        (languages + scriptLanguages(text)).map { primaryKey(it) }.filter { it.isNotEmpty() }.distinct().map(::table)

    /** "de-AT" and "de_AT" read as German; the region does not change the words. */
    private fun primaryKey(tag: String): String =
        tag.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT).takeIf { it.length in 2..3 }.orEmpty()

    private fun localeOf(language: String): Locale = Locale.forLanguageTag(language)

    internal fun table(language: String): Table = tables.getOrPut(language) { build(language) }

    private fun build(language: String): Table {
        val locale = localeOf(language)
        val extra = EXTRAS[language] ?: Extra()
        val fixed = LinkedHashMap<String, Meaning>()
        val backward = HashSet<String>()
        fun put(raw: String?, meaning: Meaning) {
            val key = raw?.let { fold(it).text.trim() } ?: return
            // A phrase with no letters is ICU's numeric fallback ("+1 d"), not a word.
            if (key.isEmpty() || key.none { it.isLetter() } || key.any { it in '0'..'9' }) return
            if (meaning == Meaning.Backward) backward += key else fixed.putIfAbsent(key, meaning)
        }

        val weekdays = weekdayNames(locale)
        weekdays.forEachIndexed { dow, names -> names.forEach { put(it, Meaning.Weekday(dow)) } }
        IcuRelative.phrases(locale).forEach { (phrase, meaning) -> put(phrase, meaning) }
        IcuRelative.fixedCounts(locale).forEach { (phrase, meaning) -> put(phrase, meaning) }
        for ((phrase, meaning) in extra.fixed) put(phrase, meaning)
        for (word in extra.nextWords) {
            weekdays.forEachIndexed { dow, names -> names.forEach { put("$word $it", Meaning.Weekday(dow)) } }
        }
        // A phrase that also points back — Hindi's कल is both yesterday and
        // tomorrow — is no use to a chip that has to pick one.
        for (key in backward) fixed[key] = Meaning.Backward

        val unspaced = language in UNSPACED
        val counted = IcuRelative.counted(locale).map { (prefix, suffix, unit) ->
            val pattern = literal(prefix) + (if (prefix.isEmpty()) "" else "\\s?") + "(\\d{1,3})" +
                (if (suffix.isEmpty()) "" else "\\s?") + literal(suffix)
            Counted(Regex(before(unspaced) + pattern + after(unspaced)), unit)
        }

        val months = HashMap<String, Int>()
        monthNames(locale).forEachIndexed { index, names ->
            for (name in names) {
                val key = fold(name).text.trim()
                if (key.isEmpty()) continue
                months.putIfAbsent(key, index + 1)
                months.putIfAbsent(key.trimEnd('.'), index + 1)
            }
        }
        for ((name, month) in extra.monthAliases) months.putIfAbsent(fold(name).text.trim(), month)
        val monthAlternation = months.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .joinToString("|") { literal(it) }
        val dayMonth = if (monthAlternation.isEmpty()) {
            emptyList()
        } else {
            dayMonthPatterns(locale).mapNotNull { dayMonthShape(it, monthAlternation, extra.daySuffix, unspaced) }
        }

        val guards = extra.guards.mapKeys { (key, _) -> if (key == "*") key else fold(key).text }
            .mapValues { (_, words) -> words.map { fold(it).text }.toSet() }
        return Table(language, locale, fixed, counted, months, dayMonth, guards, unspaced)
    }

    /** Seven lists, Sunday first, of each weekday's full names. */
    private fun weekdayNames(locale: Locale): List<Set<String>> {
        val out = List(DAYS_IN_WEEK) { LinkedHashSet<String>() }
        runCatching { java.text.DateFormatSymbols.getInstance(locale).weekdays }.getOrNull()?.let { names ->
            for (dow in 0 until DAYS_IN_WEEK) names.getOrNull(dow + 1)?.takeIf { it.isNotBlank() }?.let { out[dow] += it }
        }
        IcuNames.weekdays(locale)?.forEach { names ->
            for (dow in 0 until DAYS_IN_WEEK) names.getOrNull(dow + 1)?.takeIf { it.isNotBlank() }?.let { out[dow] += it }
        }
        return out
    }

    /** Twelve lists, January first, of each month's names in every form. */
    private fun monthNames(locale: Locale): List<Set<String>> {
        val out = List(MONTHS_IN_YEAR) { LinkedHashSet<String>() }
        runCatching { java.text.DateFormatSymbols.getInstance(locale) }.getOrNull()?.let { symbols ->
            for (names in listOf(symbols.months, symbols.shortMonths)) {
                for (m in 0 until MONTHS_IN_YEAR) names.getOrNull(m)?.takeIf { it.isNotBlank() }?.let { out[m] += it }
            }
        }
        IcuNames.months(locale)?.forEach { names ->
            for (m in 0 until MONTHS_IN_YEAR) names.getOrNull(m)?.takeIf { it.isNotBlank() }?.let { out[m] += it }
        }
        return out
    }

    /**
     * The orders a day and a month are written in; both common ones when ICU
     * cannot say (the dot is optional, so "d. MMMM" also reads "14 August").
     */
    private fun dayMonthPatterns(locale: Locale): List<String> =
        IcuNames.dayMonthPatterns(locale)?.takeIf { it.isNotEmpty() } ?: listOf("d. MMMM", "MMMM d")

    /**
     * A `SimpleDateFormat` day-month [pattern] as a regex over folded text:
     * the day a 1–2 digit number with [daySuffix] after it, a named month
     * the [monthAlternation], literal letters kept, punctuation optional so
     * "14 August" reads like "14. August". An optional year may follow.
     * Null for a pattern with fields other than day and month, or one that
     * is all digits (`d/M`), which reads as a fraction as often as a date.
     */
    private fun dayMonthShape(pattern: String, monthAlternation: String, daySuffix: String, unspaced: Boolean): DayMonth? {
        val out = StringBuilder()
        var group = 0
        var dayGroup = -1
        var monthGroup = -1
        var named = false
        var letters = false
        fun literalText(text: String) {
            // Folded piece by piece: fold() drops a space with nothing before it.
            if (text.isNotEmpty() && text.all { it.isWhitespace() || it == '\u00A0' || it == '\u202F' }) {
                out.append("\\s?")
                return
            }
            for (c in fold(text).text) {
                when {
                    c == ' ' -> out.append("\\s?")
                    c.isLetter() -> {
                        letters = true
                        out.append(Regex.escape(c.toString()))
                    }
                    else -> out.append(Regex.escape(c.toString())).append('?')
                }
            }
        }
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> {
                    val close = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                    literalText(if (close == i + 1) "'" else pattern.substring(i + 1, close))
                    i = close + 1
                    continue
                }
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    var j = i
                    while (j < pattern.length && pattern[j] == c) j++
                    when (c) {
                        'd' -> {
                            dayGroup = ++group
                            out.append("(\\d{1,2})").append(daySuffix)
                        }
                        'M', 'L' -> {
                            monthGroup = ++group
                            if (j - i >= 3) {
                                named = true
                                out.append("($monthAlternation)")
                            } else {
                                out.append("(\\d{1,2})")
                            }
                        }
                        else -> return null
                    }
                    i = j
                    continue
                }
                else -> literalText(c.toString())
            }
            i++
        }
        if (dayGroup < 0 || monthGroup < 0 || (!named && !letters)) return null
        val yearGroup = ++group
        out.append("(?:,?\\s?(\\d{4})(?!\\d))?")
        return DayMonth(Regex(before(unspaced) + out + after(unspaced)), dayGroup, monthGroup, yearGroup, named)
    }

    // ---- folding ----

    /**
     * [text] lowercased, marks taken off, digits as ASCII and runs of space
     * as one, with each folded character's place in the original so a match
     * maps back to the text as typed.
     */
    internal class Folded(val text: String, private val starts: IntArray, private val ends: IntArray, private val length: Int) {
        fun start(index: Int): Int = if (index < starts.size) starts[index] else length
        fun end(index: Int): Int = if (index > 0) ends[index - 1] else 0
    }

    internal fun fold(raw: String): Folded {
        val out = StringBuilder(raw.length)
        val starts = ArrayList<Int>(raw.length)
        val ends = ArrayList<Int>(raw.length)
        fun add(c: Char, from: Int, to: Int) {
            out.append(c)
            starts += from
            ends += to
        }
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val next = i + Character.charCount(cp)
            when {
                Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ->
                    add('0' + Character.digit(cp, 10).coerceAtLeast(0), i, next)
                Character.isWhitespace(cp) || cp == 0x00A0 || cp == 0x202F || cp == 0x2007 -> {
                    if (out.isNotEmpty() && out.last() != ' ') add(' ', i, next)
                }
                cp in 0xAC00..0xD7A3 -> add(cp.toChar(), i, next)
                else -> {
                    val lower = String(Character.toChars(Character.toLowerCase(cp)))
                    for (c in Normalizer.normalize(lower, Normalizer.Form.NFD)) {
                        if (dropped(c)) continue
                        add(
                            when (c) {
                                'ς' -> 'σ'
                                '’', 'ʼ' -> '\''
                                else -> c
                            },
                            i, next,
                        )
                    }
                }
            }
            i = next
        }
        return Folded(out.toString(), starts.toIntArray(), ends.toIntArray(), raw.length)
    }

    /** Accents, Arabic vowel marks, Indic nuktas and invisible direction marks: things typed or not. */
    private fun dropped(c: Char): Boolean = when (c.code) {
        in 0x0300..0x036F, in 0x0591..0x05C7, in 0x064B..0x0652, 0x0640, 0x0670,
        0x093C, 0x09BC, 0x0A3C, 0x0ABC, 0x0B3C,
        0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x061C, 0x2060, 0xFEFF,
        -> true
        else -> false
    }

    /** A folded phrase as a regex: spaces optional-width, everything else literal. */
    private fun literal(folded: String): String =
        folded.split(' ').filter { it.isNotEmpty() }.joinToString("\\s+") { Regex.escape(it) }

    private fun before(unspaced: Boolean): String = if (unspaced) "(?<!\\p{N})" else "(?<![\\p{L}\\p{M}\\p{N}])"

    private fun after(unspaced: Boolean): String = if (unspaced) "(?!\\p{N})" else "(?![\\p{L}\\p{M}\\p{N}])"

    // ---- scripts ----

    /**
     * The language a script most likely means, for text written in it
     * whatever the keyboard's language: a Bengali note is read in Bengali
     * on an English phone. Latin says nothing, so it adds nothing.
     */
    internal fun scriptLanguages(text: String): List<String> {
        var kana = false
        val scripts = LinkedHashSet<Character.UnicodeScript>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull()
                if (script != null && script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON) {
                    scripts += script
                    if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) kana = true
                }
            }
            i += Character.charCount(cp)
        }
        return scripts.mapNotNull { script ->
            when (script) {
                Character.UnicodeScript.HAN -> if (kana) "ja" else "zh"
                else -> SCRIPT_LANGUAGE[script]
            }
        }.distinct()
    }

    private val SCRIPT_LANGUAGE: Map<Character.UnicodeScript, String> = mapOf(
        Character.UnicodeScript.BENGALI to "bn",
        Character.UnicodeScript.DEVANAGARI to "hi",
        Character.UnicodeScript.ARABIC to "ar",
        Character.UnicodeScript.CYRILLIC to "ru",
        Character.UnicodeScript.HIRAGANA to "ja",
        Character.UnicodeScript.KATAKANA to "ja",
        Character.UnicodeScript.HANGUL to "ko",
        Character.UnicodeScript.GREEK to "el",
        Character.UnicodeScript.HEBREW to "he",
        Character.UnicodeScript.THAI to "th",
        Character.UnicodeScript.TAMIL to "ta",
        Character.UnicodeScript.TELUGU to "te",
        Character.UnicodeScript.KANNADA to "kn",
        Character.UnicodeScript.MALAYALAM to "ml",
        Character.UnicodeScript.GUJARATI to "gu",
        Character.UnicodeScript.GURMUKHI to "pa",
        Character.UnicodeScript.ORIYA to "or",
        Character.UnicodeScript.SINHALA to "si",
        Character.UnicodeScript.GEORGIAN to "ka",
        Character.UnicodeScript.ARMENIAN to "hy",
        Character.UnicodeScript.MYANMAR to "my",
        Character.UnicodeScript.KHMER to "km",
        Character.UnicodeScript.LAO to "lo",
        Character.UnicodeScript.ETHIOPIC to "am",
    )

    // ---- formatting ----

    private fun format(jdn: Long, locale: Locale, skeleton: String, fallback: String): String {
        val date = CalendarSystems.jdnToGregorian(jdn)
        val day = GregorianCalendar(date.year, date.month - 1, date.day, 12, 0)
        return IcuNames.format(day, locale, skeleton)
            ?: runCatching { java.text.SimpleDateFormat(fallback, locale).format(day.time) }.getOrDefault("")
    }

    /** The `android.icu` names and patterns; every call is null where the platform has no ICU (the JVM). */
    private object IcuNames {
        fun weekdays(locale: Locale): List<Array<String>>? = runCatching {
            val symbols = android.icu.text.DateFormatSymbols(locale)
            listOf(android.icu.text.DateFormatSymbols.FORMAT, android.icu.text.DateFormatSymbols.STANDALONE).map {
                symbols.getWeekdays(it, android.icu.text.DateFormatSymbols.WIDE)
            }
        }.getOrNull()?.filterNotNull()

        fun months(locale: Locale): List<Array<String>>? = runCatching {
            val symbols = android.icu.text.DateFormatSymbols(locale)
            val contexts = listOf(android.icu.text.DateFormatSymbols.FORMAT, android.icu.text.DateFormatSymbols.STANDALONE)
            val widths = listOf(android.icu.text.DateFormatSymbols.WIDE, android.icu.text.DateFormatSymbols.ABBREVIATED)
            contexts.flatMap { context -> widths.map { width -> symbols.getMonths(context, width) } }
        }.getOrNull()?.filterNotNull()

        fun dayMonthPatterns(locale: Locale): List<String>? = runCatching {
            val generator = android.icu.text.DateTimePatternGenerator.getInstance(android.icu.util.ULocale.forLocale(locale))
            listOf("MMMMd", "MMMd").mapNotNull { generator.getBestPattern(it) }.distinct()
        }.getOrNull()

        fun format(day: Calendar, locale: Locale, skeleton: String): String? = runCatching {
            android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale).format(day.time)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** `android.icu.text.RelativeDateTimeFormatter`'s phrases; empty where the platform has no ICU. */
    private object IcuRelative {
        private val WEEKDAYS = listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")

        private fun formatters(locale: Locale): List<android.icu.text.RelativeDateTimeFormatter> =
            android.icu.text.RelativeDateTimeFormatter.Style.values().mapNotNull { style ->
                runCatching {
                    android.icu.text.RelativeDateTimeFormatter.getInstance(
                        android.icu.util.ULocale.forLocale(locale),
                        null,
                        style,
                        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
                    )
                }.getOrNull()
            }

        fun phrases(locale: Locale): List<Pair<String, Meaning>> = runCatching {
            val out = ArrayList<Pair<String, Meaning>>()
            for (f in formatters(locale)) out += phrasesOf(f)
            out
        }.getOrDefault(emptyList())

        private fun phrasesOf(f: android.icu.text.RelativeDateTimeFormatter): List<Pair<String, Meaning>> {
            val d = android.icu.text.RelativeDateTimeFormatter.Direction::class.java.enumConstants!!.associateBy { it.name }
            val unit = android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit::class.java.enumConstants!!.associateBy { it.name }
            fun say(direction: String, absolute: String): String? = runCatching {
                f.format(d.getValue(direction), unit.getValue(absolute))
            }.getOrNull()
            val out = ArrayList<Pair<String, Meaning>>()
            fun add(text: String?, meaning: Meaning) {
                if (!text.isNullOrBlank()) out += text to meaning
            }
            add(say("THIS", "DAY"), Meaning.Days(0))
            add(say("NEXT", "DAY"), Meaning.Days(1))
            add(say("NEXT_2", "DAY"), Meaning.Days(2))
            add(say("NEXT", "WEEK"), Meaning.Days(DAYS_IN_WEEK))
            add(say("NEXT", "MONTH"), Meaning.Months(1))
            for (direction in listOf("LAST", "LAST_2")) {
                for (absolute in listOf("DAY", "WEEK", "MONTH")) add(say(direction, absolute), Meaning.Backward)
            }
            val nextWeek = say("NEXT", "WEEK")?.let { fold(it).text.trim() }.orEmpty()
            val thisWeek = say("THIS", "WEEK")?.let { fold(it).text.trim() }.orEmpty()
            WEEKDAYS.forEachIndexed { dow, absolute ->
                add(say("LAST", absolute), Meaning.Backward)
                // Where the language says "next Friday" as "Friday of next
                // week" (来週の金曜日, 下周五), it means that week's Friday.
                say("NEXT", absolute)?.let { phrase ->
                    val folded = fold(phrase).text
                    add(phrase, if (nextWeek.isNotEmpty() && folded.contains(nextWeek)) Meaning.WeekdayOfWeek(1, dow) else Meaning.Weekday(dow))
                }
                say("THIS", absolute)?.let { phrase ->
                    val folded = fold(phrase).text
                    add(phrase, if (thisWeek.isNotEmpty() && folded.contains(thisWeek)) Meaning.WeekdayOfWeek(0, dow) else Meaning.Weekday(dow))
                }
            }
            return out
        }

        /**
         * The "in N days / weeks / months" templates as (prefix, suffix,
         * unit), folded: each sample count is formatted and the number found
         * in the output again. An output without the number ("خلال يومين",
         * in two days) is a fixed phrase instead, which [fixedCounts] gives.
         */
        fun counted(locale: Locale): List<Triple<String, String, CountUnit>> = runCatching {
            val numbers = android.icu.text.NumberFormat.getInstance(locale)
            val units = mapOf(
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS to CountUnit.DAY,
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS to CountUnit.WEEK,
                android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS to CountUnit.MONTH,
            )
            val out = LinkedHashSet<Triple<String, String, CountUnit>>()
            for (f in formatters(locale)) {
                for ((relative, unit) in units) {
                    for (n in SAMPLE_COUNTS) {
                        val text = runCatching {
                            f.format(n.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, relative)
                        }.getOrNull() ?: continue
                        val folded = fold(text).text.trim()
                        val number = fold(numbers.format(n.toLong())).text
                        val at = folded.indexOf(number)
                        if (at < 0) continue
                        val prefix = folded.substring(0, at).trim()
                        val suffix = folded.substring(at + number.length).trim()
                        // "+3 d" is arithmetic as often as a date: a template needs a word.
                        if ((prefix + suffix).none { it.isLetter() }) continue
                        out += Triple(prefix, suffix, unit)
                    }
                }
            }
            out.toList()
        }.getOrDefault(emptyList())

        /** The counted phrases that come out with no number in them, as fixed phrases. */
        fun fixedCounts(locale: Locale): List<Pair<String, Meaning>> = runCatching {
            val numbers = android.icu.text.NumberFormat.getInstance(locale)
            val out = ArrayList<Pair<String, Meaning>>()
            for (f in formatters(locale)) {
                for (n in 1..2) {
                    val days = runCatching {
                        f.format(n.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS)
                    }.getOrNull()
                    val weeks = runCatching {
                        f.format(n.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS)
                    }.getOrNull()
                    val number = fold(numbers.format(n.toLong())).text
                    if (days != null && !fold(days).text.contains(number)) out += days to Meaning.Days(n)
                    if (weeks != null && !fold(weeks).text.contains(number)) out += weeks to Meaning.Days(n * DAYS_IN_WEEK)
                }
            }
            out
        }.getOrDefault(emptyList())
    }
}
