package com.wasimaster.wmkeyboard.app.updates

/**
 * The markdown a release note is written in, turned into blocks a composable
 * can draw.
 *
 * Deliberately not a markdown implementation. It covers what
 * `release-notes/<versionName>.md` actually uses — headings, bullets, bold,
 * italic, inline code and links — plus fenced code and rules in case a future
 * note reaches for them. Anything else is left as the literal text it was
 * written as, which is the right answer for a changelog: a reader who sees a
 * stray marker loses nothing, a reader who sees a blank dialog loses the
 * changelog.
 *
 * Pure Kotlin, and in `src/main`, because `src/test` is compiled in every
 * build channel and a test that named a class from `src/github/java` would
 * break `check` on a Play build.
 *
 * The stripper in [com.wasimaster.wmkeyboard.core.tools.AiMarkdown] answers a
 * different question: it takes markdown *out* of text on its way into a field
 * that cannot render it. This one keeps it, to draw.
 */
internal object ReleaseNotesMarkdown {

    /** How deep a heading may go before it stops being worth a size of its own. */
    private const val MAX_HEADING = 6

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 40 KB across this file's patterns.
    private val HEADING by lazy { Regex("""^ {0,3}(#{1,6}) +(.*?)(?: +#+)? *$""") }
    private val BULLET by lazy { Regex("""^( *)[-*+] +(.*)$""") }
    private val FENCE by lazy { Regex("""^ {0,3}(```|~~~).*$""") }
    private val RULE by lazy { Regex("""^ {0,3}([-*_])( *\1){2,} *$""") }

    // Bold before italic, and both requiring a non-space just inside the
    // markers, so "2 * 3" and "a_b_c" are text. Same rule as AiMarkdown, for
    // the same reason: a changelog is prose with file names in it.
    private val BOLD by lazy { Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""", RegexOption.DOT_MATCHES_ALL) }
    private val ITALIC by lazy { Regex("""(?<![\w*_])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![\w*_])""") }
    private val CODE by lazy { Regex("""`([^`\n]+)`""") }
    private val LINK by lazy { Regex("""\[([^\]\n]*)]\((\S*?)\)""") }

    /** [text] as blocks, in the order they were written. */
    fun parse(text: String): List<NotesBlock> {
        val blocks = mutableListOf<NotesBlock>()
        // A paragraph and a bullet both continue over wrapped lines, so each
        // is gathered until something ends it.
        val pending = StringBuilder()
        var pendingIsBullet = false
        val fence = mutableListOf<String>()
        var inFence = false

        fun flush() {
            val raw = pending.toString().trim()
            pending.setLength(0)
            if (raw.isEmpty()) return
            val spans = inline(raw)
            blocks += if (pendingIsBullet) NotesBlock.Bullet(spans) else NotesBlock.Paragraph(spans)
            pendingIsBullet = false
        }

        for (line in text.lines()) {
            if (FENCE.matches(line)) {
                if (inFence) {
                    blocks += NotesBlock.Code(fence.joinToString("\n"))
                    fence.clear()
                } else {
                    flush()
                }
                inFence = !inFence
                continue
            }
            if (inFence) {
                fence += line
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            // A rule is checked before a bullet: "---" matches both, and only
            // one of them is what anybody wrote.
            if (RULE.matches(line)) {
                flush()
                blocks += NotesBlock.Rule
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flush()
                val (hashes, body) = heading.destructured
                blocks += NotesBlock.Heading(hashes.length.coerceAtMost(MAX_HEADING), inline(body))
                continue
            }
            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                flush()
                pendingIsBullet = true
                pending.append(bullet.groupValues[2])
                continue
            }
            // Anything else continues what came before it, bullet or
            // paragraph. The newline becomes a space: these files are hard
            // wrapped at 80 columns and a phone is not 80 columns wide.
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(line.trim())
        }
        if (inFence && fence.isNotEmpty()) blocks += NotesBlock.Code(fence.joinToString("\n"))
        flush()
        return blocks
    }

    /**
     * One line of prose as styled runs.
     *
     * Done by finding whichever marker starts earliest and recursing on what
     * is inside it, so `**bold [link](url)**` keeps both. Left to right rather
     * than one pass per marker, which is what stops a link's URL from being
     * read as italics.
     */
    private fun inline(text: String): List<NotesSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<NotesSpan>()
        appendInline(text, NotesStyle(), spans)
        return spans
    }

    private fun appendInline(text: String, style: NotesStyle, out: MutableList<NotesSpan>) {
        if (text.isEmpty()) return
        // Code first: a backtick run is literal, so a marker inside one is not
        // a marker. A link beats bold only when it starts earlier.
        val matches = listOfNotNull(
            CODE.find(text)?.let { it to Marker.CODE },
            LINK.find(text)?.let { it to Marker.LINK },
            BOLD.find(text)?.let { it to Marker.BOLD },
            ITALIC.find(text)?.let { it to Marker.ITALIC },
        )
        val (match, marker) = matches.minByOrNull { it.first.range.first } ?: run {
            out += NotesSpan(text, style.bold, style.italic, style.code, style.link)
            return
        }
        val before = text.substring(0, match.range.first)
        if (before.isNotEmpty()) {
            out += NotesSpan(before, style.bold, style.italic, style.code, style.link)
        }
        when (marker) {
            // A code run and a link label are both taken whole: nothing inside
            // a code run is a marker, and a label with markers in it is not
            // something these notes write.
            Marker.CODE -> out += NotesSpan(
                match.groupValues[1],
                style.bold,
                style.italic,
                code = true,
                link = style.link,
            )

            Marker.LINK -> {
                val label = match.groupValues[1]
                val url = match.groupValues[2]
                out += NotesSpan(
                    label.ifBlank { url },
                    style.bold,
                    style.italic,
                    style.code,
                    link = url.ifBlank { null } ?: style.link,
                )
            }

            Marker.BOLD -> appendInline(match.groupValues[2], style.copy(bold = true), out)
            Marker.ITALIC -> appendInline(match.groupValues[2], style.copy(italic = true), out)
        }
        appendInline(text.substring(match.range.last + 1), style, out)
    }

    private enum class Marker { CODE, LINK, BOLD, ITALIC }

    private data class NotesStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )
}

/** A run of text in a release note, and how it is set. */
internal data class NotesSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** One drawable piece of a release note. */
internal sealed interface NotesBlock {

    /** `#` to `######`. [level] is how many hashes were written. */
    data class Heading(val level: Int, val spans: List<NotesSpan>) : NotesBlock

    data class Paragraph(val spans: List<NotesSpan>) : NotesBlock

    /** One `-` item, however many wrapped lines it was written over. */
    data class Bullet(val spans: List<NotesSpan>) : NotesBlock

    /** A fenced block, kept line for line. */
    data class Code(val text: String) : NotesBlock

    /** A `---` divider. */
    data object Rule : NotesBlock
}
