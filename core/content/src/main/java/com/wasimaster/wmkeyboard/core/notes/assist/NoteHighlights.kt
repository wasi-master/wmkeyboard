package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.selection.DateTimes
import java.util.Locale
import java.util.TimeZone

/**
 * What a highlight is (#1293), in the order a note's "Key details" row lists
 * them: the time-critical ones first.
 */
enum class NoteHighlightKind {
    /** A date with "due", "deadline", "submit by" or their kin in front of it. */
    DEADLINE,

    /** A date on a line that names a birthday or an anniversary: it comes round every year. */
    BIRTHDAY,

    /** Any other date or date and time. */
    DATE,

    /** A sum of money: a currency sign or code beside a number. */
    AMOUNT,
    PHONE,
    EMAIL,
    LINK,
    PLACE,
    ;

    /** Whether a reminder can be set for it. */
    val dated: Boolean get() = this == DEADLINE || this == BIRTHDAY || this == DATE
}

/**
 * One important bit of a note or a pinned clip (#1293).
 *
 * [start] and [end] index the text that was scanned. [line] is the line it
 * sits on with its Markdown markers taken off, which is what a reminder is
 * called. A dated kind carries [whenMillis], the moment it names in [zoneId],
 * and [hasTime] when the text gave a clock time as well as a day.
 */
data class NoteHighlight(
    val kind: NoteHighlightKind,
    val text: String,
    val start: Int,
    val end: Int,
    val line: String,
    val whenMillis: Long? = null,
    val hasTime: Boolean = false,
    val zoneId: String? = null,
) {
    /** Identity for de-duplicating the same detail found twice. */
    val key: String get() = "${kind.name}:${text.lowercase(Locale.ROOT)}"
}

/**
 * Finds the bits of a note worth picking out (#1293): deadlines, birthdays,
 * dates, amounts, and the phone numbers, email addresses, links and places
 * the clipboard's fragment chips already find ([ClipEntities]). All of it is
 * rules, no model: it works with nothing downloaded and costs a few regexes,
 * which is why the AI features use it first and the model only for what
 * rules cannot do (a heading, a summary).
 *
 * Dates are found by shape here and read by [DateTimes], the parser the
 * selection bar's "Add to calendar" uses, so a date means the same thing in
 * both places. A phrase without a year, or a relative one ("tomorrow"),
 * resolves against [find]'s `anchorMillis`: for a note, when it was last
 * edited, because "tomorrow" in a note written last week meant last week's
 * tomorrow.
 */
object NoteHighlights {

    /** Characters read; a longer note's important bits are rarely past this. */
    const val MAX_SCANNED_CHARS = 20_000

    /** The most highlights one text gives. */
    const val MAX_HIGHLIGHTS = 24

    /** How far ahead of a date its deadline word may sit, in characters, on the same line. */
    private const val KEYWORD_REACH = 48

    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val SHORT_WEEKDAYS = "mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"

    /** A clock time that may follow a date: "at 5pm", ", 14:30". */
    private const val TIME_TAIL = """(?:,?\s*(?:at\s+|@\s*)?(?:\d{1,2}(?::\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.)|\d{1,2}:\d{2}))?"""

    private val DATE_SHAPES: List<Regex> = listOf(
        // 2026-11-20
        """(?<![\d/.-])\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?![\d/.-])""",
        // 20/11/2026, 11-20-26
        """(?<![\d/.-])\d{1,2}([/.\-])\d{1,2}\1(?:\d{4}|\d{2})(?![\d/.-])""",
        // 20 Nov, 20th of November 2026
        """(?<![\p{L}\d])\d{1,2}(?:st|nd|rd|th)?\s*(?:of\s+)?(?:$MONTHS)\.?(?![\p{L}])(?:,?\s+\d{4}(?!\d))?""",
        // Nov 20, November 20th, 2026
        """(?<![\p{L}])(?:$MONTHS)\.?\s+\d{1,2}(?:st|nd|rd|th)?(?![\d\p{L}])(?:,?\s+\d{4}(?!\d))?""",
        // tomorrow, day after tomorrow, next week, next friday, this sat, friday
        """(?<![\p{L}])(?:day after tomorrow|tomorrow|tonight|today|next week|next month|""" +
            """(?:next|this|coming)\s+(?:$WEEKDAYS|$SHORT_WEEKDAYS)|(?:$WEEKDAYS))(?![\p{L}])""",
    ).map { Regex(it + TIME_TAIL, RegexOption.IGNORE_CASE) }

    /**
     * The words that make a date a deadline, read on the date's own line
     * before it. "by" and "before" are deliberately in: "pay by Friday" is
     * the commonest deadline there is.
     */
    private val DEADLINE_WORDS = Regex(
        """(?<![\p{L}])(?:due|deadline|deadlines|submit|submission|submissions|apply|application|applications|""" +
            """last date|last day|closing date|closes|close|closing|expires|expire|expiry|expiring|renew|renewal|""" +
            """pay|payment|by|before|until|till|no later than|ends|ending|cutoff|cut-off|register|registration)(?![\p{L}])""" +
            """|শেষ তারিখ|জমা|মেয়াদ""",
        RegexOption.IGNORE_CASE,
    )

    /** Words that make every date on their line a birthday or an anniversary. */
    private val BIRTHDAY_WORDS = Regex(
        """(?<![\p{L}])(?:birthday|birthdays|bday|b'day|b-day|anniversary|born on|dob|date of birth)(?![\p{L}])""" +
            """|জন্মদিন|বার্ষিকী|জন্ম তারিখ""",
        RegexOption.IGNORE_CASE,
    )

    private const val CURRENCY_CODES = "usd|eur|gbp|inr|bdt|pkr|npr|lkr|jpy|cny|rmb|aud|cad|chf|sgd|aed|sar|myr|idr|php|thb|krw|try|rub|ngn|zar|brl|mxn"
    private const val CURRENCY_WORDS = "dollars|dollar|euros|euro|pounds|pound|rupees|rupee|taka|tk|yen|yuan|riyal|ringgit|baht|won|lira|rand|naira|pesos|peso"

    private val AMOUNT = Regex(
        // £12, $1,200.50, ৳ 500, Rs. 300, USD 40, 2.5k
        """(?:[$€£¥₹৳₽₩₺₦₱]|(?<![\p{L}])(?:$CURRENCY_CODES|rs\.?|tk\.?)\s?)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|bn|million|billion|lakh|lakhs|crore|crores)(?![\p{L}]))?""" +
            // 40 USD, 1,200 taka, 300 rupees
            """|(?<![\d.,\p{L}])\d[\d,]*(?:\.\d+)?\s?(?:$CURRENCY_CODES|$CURRENCY_WORDS)(?![\p{L}])""" +
            // 500 টাকা
            """|(?<![\d.,])[\d০-৯][\d০-৯,]*(?:\.[\d০-৯]+)?\s?টাকা""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Every highlight in [text], de-duplicated, ordered by [NoteHighlightKind]
     * then by where it sits, at most [limit]. Dates resolve against
     * [anchorMillis] in [zone]; numeric dates like `04/05/2026` are read in
     * [locale]'s order.
     */
    fun find(
        text: String,
        anchorMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        limit: Int = MAX_HIGHLIGHTS,
    ): List<NoteHighlight> {
        val scanned = if (text.length > MAX_SCANNED_CHARS) text.substring(0, MAX_SCANNED_CHARS) else text
        if (scanned.isBlank()) return emptyList()
        val claimed = ArrayList<IntRange>()
        val found = ArrayList<NoteHighlight>()
        fun free(start: Int, end: Int) = claimed.none { start < it.last + 1 && it.first < end }
        fun claim(highlight: NoteHighlight) {
            claimed += highlight.start until highlight.end
            found += highlight
        }

        // Links, addresses and places first, as the clipboard does: a date
        // inside a link's path is the link's. Phone numbers wait until the
        // dates have had their pick, since a numeric date reads as one.
        val entities = ClipEntities.extract(scanned)
        for (entity in entities) {
            val kind = when (entity.kind) {
                ClipEntityKind.URL -> NoteHighlightKind.LINK
                ClipEntityKind.EMAIL -> NoteHighlightKind.EMAIL
                ClipEntityKind.PLACE -> NoteHighlightKind.PLACE
                // A note is not where codes arrive; a six-digit number there is an order or a room.
                ClipEntityKind.OTP, ClipEntityKind.PHONE -> continue
            }
            claim(NoteHighlight(kind, entity.value, entity.start, entity.end, lineAt(scanned, entity.start)))
        }

        for (date in dates(scanned, anchorMillis, zone, locale)) {
            if (free(date.start, date.end)) claim(date)
        }

        for (entity in entities) {
            if (entity.kind != ClipEntityKind.PHONE || !free(entity.start, entity.end)) continue
            claim(NoteHighlight(NoteHighlightKind.PHONE, entity.value, entity.start, entity.end, lineAt(scanned, entity.start)))
        }

        for (match in AMOUNT.findAll(scanned)) {
            val value = match.value.trim()
            val start = match.range.first + (match.value.length - match.value.trimStart().length)
            val end = start + value.length
            if (value.none { it.isDigit() } || !free(start, end)) continue
            claim(NoteHighlight(NoteHighlightKind.AMOUNT, value, start, end, lineAt(scanned, start)))
        }

        return found
            .distinctBy { it.key }
            .sortedWith(compareBy<NoteHighlight> { it.kind.ordinal }.thenBy { it.start })
            .take(limit)
    }

    /** The dated highlights of [text]; what [find] uses, and what a reminder needs. */
    internal fun dates(text: String, anchorMillis: Long, zone: TimeZone, locale: Locale): List<NoteHighlight> {
        val spans = ArrayList<NoteHighlight>()
        for (shape in DATE_SHAPES) {
            for (match in shape.findAll(text)) {
                val raw = match.value.trimEnd()
                val start = match.range.first
                val end = start + raw.length
                if (spans.any { start < it.end && it.start < end }) continue
                if (raw.length > DateTimes.MAX_LENGTH) continue
                val hit = DateTimes.parse(raw, anchorMillis, zone, locale) ?: continue
                if (!hit.hasDate) continue
                val line = lineAt(text, start)
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                val kind = when {
                    BIRTHDAY_WORDS.containsMatchIn(lineText(text, start)) -> NoteHighlightKind.BIRTHDAY
                    deadlineBefore(text, lineStart, start) -> NoteHighlightKind.DEADLINE
                    else -> NoteHighlightKind.DATE
                }
                spans += NoteHighlight(
                    kind = kind,
                    text = raw,
                    start = start,
                    end = end,
                    line = line,
                    whenMillis = hit.startMillis,
                    hasTime = hit.hasTime,
                    zoneId = hit.zoneId,
                )
            }
        }
        return spans.sortedBy { it.start }
    }

    /** Whether a deadline word sits on the line before [start], close enough to be about this date. */
    private fun deadlineBefore(text: String, lineStart: Int, start: Int): Boolean {
        val from = maxOf(lineStart, start - KEYWORD_REACH)
        return DEADLINE_WORDS.containsMatchIn(text.substring(from, start))
    }

    /** The raw line holding [index]. */
    private fun lineText(text: String, index: Int): String {
        val from = text.lastIndexOf('\n', index - 1) + 1
        val to = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(from, to)
    }

    /** The line holding [index] as a title: markers off, cut to length. */
    private fun lineAt(text: String, index: Int): String = Note.cleanTitleLine(lineText(text, index))
}
