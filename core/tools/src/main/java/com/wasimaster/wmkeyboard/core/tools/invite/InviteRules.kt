package com.wasimaster.wmkeyboard.core.tools.invite

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The pure half of publishing a calendar event as an online invitation
 * (#1274): what the event's times, place and notes become on Mobilizon and
 * Pretix. No network and no Android here, so the tests can pin it.
 */
object InviteRules {

    /**
     * The moments an invitation runs between, as wall-clock times in [zone].
     * An all-day event's calendar times are UTC midnights (the end being the
     * midnight after its last day); here they become the local midnight its
     * first day starts at and the last minute of its last day, so the event
     * page names the right days wherever its viewers are.
     */
    data class Span(val begin: Long, val end: Long, val zone: TimeZone, val allDay: Boolean)

    fun span(begin: Long, end: Long, allDay: Boolean, eventZone: TimeZone, localZone: TimeZone): Span {
        if (!allDay) return Span(begin, end.coerceAtLeast(begin), eventZone, false)
        val utc = TimeZone.getTimeZone("UTC")
        val first = fields(begin, utc)
        val lastDay = fields((end - 1).coerceAtLeast(begin), utc)
        val start = Calendar.getInstance(localZone).apply {
            clear()
            set(first[0], first[1], first[2], 0, 0, 0)
        }.timeInMillis
        val stop = Calendar.getInstance(localZone).apply {
            clear()
            set(lastDay[0], lastDay[1], lastDay[2], 23, 59, 0)
        }.timeInMillis
        return Span(start, stop, localZone, true)
    }

    private fun fields(millis: Long, zone: TimeZone): IntArray = Calendar.getInstance(zone).let {
        it.timeInMillis = millis
        intArrayOf(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH))
    }

    /** `2026-11-20T18:30:00+06:00`: ISO 8601 with the zone's offset at that moment, as both services take it. */
    fun iso(millis: Long, zone: TimeZone): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = zone }
        val offsetMin = zone.getOffset(millis) / 60_000
        val sign = if (offsetMin < 0) '-' else '+'
        val abs = kotlin.math.abs(offsetMin)
        return f.format(millis) + String.format(Locale.ROOT, "%c%02d:%02d", sign, abs / 60, abs % 60)
    }

    /** A place that is a link (a call, a stream) rather than somewhere to go. */
    fun isLink(place: String): Boolean {
        val t = place.trim()
        return (t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) &&
            t.none { it.isWhitespace() } && t.length > "https://".length + 2
    }

    /**
     * The first link in [text], for an event whose place is a room but whose
     * notes carry the call: Mobilizon shows it as the event's online address.
     */
    fun firstLink(text: String): String? =
        LINK.find(text)?.value?.trimEnd('.', ',', ')', ']', ';', ':', '!', '?')

    private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /**
     * Plain notes as the HTML Mobilizon's description holds: escaped, a
     * paragraph per blank-line block, line breaks kept inside one.
     */
    fun html(text: String): String {
        val blocks = text.trim().replace("\r\n", "\n").split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        return blocks.joinToString("") { block -> "<p>" + block.lines().joinToString("<br>") { escape(it) } + "</p>" }
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /**
     * A Pretix event slug for [title]: lower-case ASCII letters, digits and
     * hyphens, starting with a letter or digit, ending in [suffix] so two
     * invitations to "Dinner" do not collide. Pretix allows at most 50.
     */
    fun slug(title: String, suffix: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        val base = ascii.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        val tail = suffix.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }.ifEmpty { "event" }
        val room = SLUG_MAX - tail.length - 1
        val head = base.take(room).trimEnd('-')
        return if (head.isEmpty()) tail else "$head-$tail"
    }

    /** A random slug suffix: six letters and digits, from [random] so a test can fix it. */
    fun suffix(random: java.util.Random = java.util.Random()): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        return String(CharArray(SUFFIX_LENGTH) { chars[random.nextInt(chars.length)] })
    }

    /**
     * The invitation the user types into a chat: the title, when, and the
     * sign-up link, one per line.
     */
    fun message(title: String, whenText: String, link: String): String = buildString {
        if (title.isNotBlank()) append(title.trim()).append('\n')
        if (whenText.isNotBlank()) append(whenText.trim()).append('\n')
        append(link.trim())
    }

    /** A currency code Pretix will take: three letters, else [fallback]. */
    fun currency(typed: String, fallback: String): String {
        val t = typed.trim().uppercase(Locale.ROOT)
        return if (t.length == 3 && t.all { it in 'A'..'Z' }) t else fallback
    }

    const val SLUG_MAX = 50
    private const val SUFFIX_LENGTH = 6
}
