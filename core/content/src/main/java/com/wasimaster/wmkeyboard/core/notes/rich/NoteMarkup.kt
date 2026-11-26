package com.wasimaster.wmkeyboard.core.notes.rich

import java.util.Locale

/**
 * A note's Markdown read the way the rich editor draws it (#1586), and the
 * way back.
 *
 * The rich editor never converts a note into another form. The text it edits
 * is the stored Markdown itself, drawn with its markers hidden: `**`, `_`,
 * `~~`, backticks, a list's `- `, a heading's `# `, a task's `- [ ] `, a
 * quote's `> `, a link's brackets and address, and the `<u>`, `<mark>` and
 * `<span style>` tags that carry what Markdown has no syntax for. [read]
 * says, in offsets of the source, which characters are such markers
 * ([Hidden]) and which marks cover which text ([Range]); the editor draws the
 * hidden ones at no width and styles the rest. Because offsets are never
 * translated, the caret, the selection and every edit stay in the source,
 * and a note nobody formats is never rewritten: what opens is what was
 * saved, byte for byte.
 *
 * Only when a line is formatted, or an edit cuts through its markers, is
 * that line written again from what it shows ([writeInline]). The writer
 * prefers Markdown, checks its own output by reading it back, and falls back
 * to escapes, then to `<b>`/`<i>`/`<s>`/`<code>` tags, so what it writes
 * always reads back as the same text with the same marks.
 *
 * Everything outside the inline subset (code fences, tables, image lines,
 * footnotes, `<kbd>` and any other tag) is shown as the text it is, so
 * nothing in an existing note can be lost by editing it here.
 *
 * Pure Kotlin, no Android: the keyboard, the app and the tests share it.
 */
object NoteMarkup {

    /** What a source line is, as the editor draws it. */
    enum class LineKind {
        PARAGRAPH, HEADING, BULLET, NUMBERED, TODO, QUOTE,

        /** `***`, `---` or `___` alone: drawn as a rule, its characters hidden. */
        DIVIDER,

        /** One or more images and nothing else: pictures, side by side when there are several. */
        IMAGES,

        /** A row of a pipe table, its delimiter row included. */
        TABLE,

        /** A fenced code block's lines, fences included. */
        CODE,
        ;

        /** Whether the line's text after its prefix is read for marks. */
        val inline: Boolean get() = ordinal <= QUOTE.ordinal
    }

    /**
     * One source line, without its line break. [contentStart] is where the
     * text after its prefix begins (the line's start when it has none).
     * [level] is a heading's level; [indent] a list item's nesting.
     */
    data class Line(
        val start: Int,
        val end: Int,
        val kind: LineKind,
        val contentStart: Int = start,
        val level: Int = 0,
        val indent: Int = 0,
        val checked: Boolean = false,
    ) {
        val prefixLength: Int get() = contentStart - start
    }

    /**
     * Why a hidden run is hidden: a line's block prefix, a mark's opening or
     * closing marker, a backslash escape, or a whole line drawn as something
     * else (a divider).
     */
    enum class Role { PREFIX, OPEN, CLOSE, ESCAPE, WHOLE }

    data class Hidden(val start: Int, val end: Int, val role: Role)

    /** [mark] over `[start, end)`, in whichever offsets the holder says. */
    data class Range(val start: Int, val end: Int, val mark: NoteMark)

    /** A text as the editor reads it. Every list is in source order. */
    class Read(val source: String, val lines: List<Line>, val hidden: List<Hidden>, val marks: List<Range>) {

        /** The index of the line holding [offset]; a line's end counts as its own. */
        fun lineAt(offset: Int): Int {
            var lo = 0
            var hi = lines.lastIndex
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lines[mid].start <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** The hidden run [offset] sits strictly inside, or null. */
        fun hiddenAround(offset: Int): Hidden? = hidden.firstOrNull { offset > it.start && offset < it.end }

        /** The hidden runs inside `[start, end)` of the source. */
        fun hiddenIn(start: Int, end: Int): List<Hidden> = hidden.filter { it.start < end && it.end > start }

        /** Whether the source character at [offset] is hidden. */
        fun isHidden(offset: Int): Boolean = hidden.any { offset >= it.start && offset < it.end }
    }

    /**
     * A line's text as it shows: [text] with no markers, [marks] in its own
     * offsets, and for each of its characters the source offset it came from.
     */
    class Visible(val text: String, val marks: List<Range>, val source: IntArray)

    // Built on first use, as everywhere in the notes code.
    private val FENCE by lazy { Regex("""^ {0,3}(```|~~~)""") }
    private val HEADING by lazy { Regex("""^ {0,3}(#{1,6})(?: +|$)""") }
    private val TASK by lazy { Regex("""^( *)([-*+]) {1,4}\[([ xX])\](?: +|$)""") }
    private val BULLET by lazy { Regex("""^( *)[-*+] +""") }
    private val ORDERED by lazy { Regex("""^( *)(\d{1,9})[.)](?: +|$)""") }
    private val QUOTE by lazy { Regex("""^ {0,3}> ?""") }
    private val RULE by lazy { Regex("""^ {0,3}([-*_])( *\1){2,} *$""") }
    private val TABLE_DELIMITER by lazy { Regex("""^ *\|? *:?-+:? *(\| *:?-+:? *)*\|? *$""") }
    private val IMAGES_LINE by lazy { Regex("""^ {0,3}(?:!\[[^\]\n]*]\(\s*<?[^\s)>]+>?(?:\s+"[^"]*")?\s*\) *)+$""") }
    private val IMAGE by lazy { Regex("""!\[([^\]\n]*)]\(\s*<?([^\s)>]+)>?(?:\s+"[^"]*")?\s*\)""") }
    private val TAG by lazy { Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)((?:\s[^<>]*)?)>""") }
    private val ATTRIBUTE by lazy { Regex("""([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""") }
    private val BLOCK_START by lazy { Regex("""^ {0,3}(?:#{1,6}(?: |$)|[-*+] |\d{1,9}[.)](?: |$)|>|\|)""") }

    /** The subset's tags, for the readers that want the text without them ([strip], [blank]). */
    private val STYLE_TAGS by lazy { Regex("""</?(?:u|ins|mark|span|font)(?:\s[^<>]*)?>""", RegexOption.IGNORE_CASE) }
    private val ATTACHMENT_IMAGE by lazy { Regex("""!\[([^\]\n]*)]\(\s*<?attachments/[^\s)>]*>?\s*\)""") }

    /** CommonMark's escapable characters. */
    private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Tags read as marks; any other tag is text. */
    private val KNOWN_TAGS = setOf("b", "strong", "i", "em", "s", "del", "strike", "u", "ins", "mark", "span", "font", "code")

    // ---- reading ----

    fun read(source: String): Read {
        val lines = ArrayList<Line>()
        val hidden = ArrayList<Hidden>()
        val marks = ArrayList<Range>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var at = 0
        while (true) {
            val nl = source.indexOf('\n', at)
            starts += at
            ends += if (nl < 0) source.length else nl
            if (nl < 0) break
            at = nl + 1
        }
        val n = starts.size
        val kinds = arrayOfNulls<LineKind>(n)
        // Fences and tables first: both are runs of lines.
        var i = 0
        var fenceOpen = false
        while (i < n) {
            val text = source.substring(starts[i], ends[i])
            if (fenceOpen) {
                kinds[i] = LineKind.CODE
                if (FENCE.containsMatchIn(text)) fenceOpen = false
                i++
                continue
            }
            if (FENCE.containsMatchIn(text)) {
                kinds[i] = LineKind.CODE
                fenceOpen = true
                i++
                continue
            }
            if ('|' in text && i + 1 < n && TABLE_DELIMITER.matches(source.substring(starts[i + 1], ends[i + 1])) &&
                '|' in (text + source.substring(starts[i + 1], ends[i + 1])).replace("\\|", "")
            ) {
                kinds[i] = LineKind.TABLE
                kinds[i + 1] = LineKind.TABLE
                i += 2
                while (i < n) {
                    val row = source.substring(starts[i], ends[i])
                    if (row.isBlank() || '|' !in row) break
                    kinds[i] = LineKind.TABLE
                    i++
                }
                continue
            }
            i++
        }
        for (k in 0 until n) {
            val start = starts[k]
            val end = ends[k]
            val runKind = kinds[k]
            if (runKind != null) {
                lines += Line(start, end, runKind)
                continue
            }
            val text = source.substring(start, end)
            val line = classify(text, start, end)
            lines += line
            when (line.kind) {
                LineKind.DIVIDER -> hidden += Hidden(start, end, Role.WHOLE)
                LineKind.IMAGES, LineKind.TABLE, LineKind.CODE -> Unit
                else -> {
                    if (line.kind != LineKind.NUMBERED && line.contentStart > start) hidden += Hidden(start, line.contentStart, Role.PREFIX)
                    InlineReader(source, hidden, marks).run(line.contentStart, end)
                }
            }
        }
        hidden.sortBy { it.start }
        marks.sortBy { it.start }
        return Read(source, lines, hidden, marks)
    }

    /** One line that is not part of a fence or a table. */
    private fun classify(text: String, start: Int, end: Int): Line {
        if (text.isBlank()) return Line(start, end, LineKind.PARAGRAPH)
        if (RULE.matches(text)) return Line(start, end, LineKind.DIVIDER)
        if (IMAGES_LINE.matches(text)) return Line(start, end, LineKind.IMAGES)
        HEADING.find(text)?.let { m ->
            return Line(start, end, LineKind.HEADING, start + m.value.length, level = m.groupValues[1].length)
        }
        TASK.find(text)?.let { m ->
            return Line(
                start,
                end,
                LineKind.TODO,
                start + m.value.length,
                indent = m.groupValues[1].length / 2,
                checked = m.groupValues[3] != " ",
            )
        }
        BULLET.find(text)?.let { m ->
            return Line(start, end, LineKind.BULLET, start + m.value.length, indent = m.groupValues[1].length / 2)
        }
        ORDERED.find(text)?.let { m ->
            return Line(start, end, LineKind.NUMBERED, start + m.value.length, indent = m.groupValues[1].length / 2)
        }
        QUOTE.find(text)?.let { m -> return Line(start, end, LineKind.QUOTE, start + m.value.length) }
        return Line(start, end, LineKind.PARAGRAPH)
    }

    /** The inline rules over one line's text, in source offsets. */
    private class InlineReader(
        val s: String,
        val hidden: MutableList<Hidden>,
        val marks: MutableList<Range>,
    ) {
        fun run(from: Int, to: Int) {
            var i = from
            while (i < to) {
                i = when (s[i]) {
                    '\\' -> if (i + 1 < to && s[i + 1] in ESCAPABLE) {
                        hidden += Hidden(i, i + 1, Role.ESCAPE)
                        i + 2
                    } else {
                        i + 1
                    }
                    '`' -> code(i, to)
                    '*', '_' -> emphasis(i, to)
                    '~' -> strike(i, to)
                    '[' -> link(i, to)
                    '!' -> image(i, to)
                    '<' -> tag(i, to)
                    else -> i + 1
                }
            }
        }

        private fun runLength(at: Int, to: Int, c: Char): Int {
            var j = at
            while (j < to && s[j] == c) j++
            return j - at
        }

        /** Where a run of exactly [length] backticks starts at or after [from], or -1. */
        private fun backtickRun(from: Int, to: Int, length: Int): Int {
            var j = from
            while (j < to) {
                if (s[j] == '`') {
                    val r = runLength(j, to, '`')
                    if (r == length) return j
                    j += r
                } else {
                    j++
                }
            }
            return -1
        }

        private fun code(i: Int, to: Int): Int {
            val r = runLength(i, to, '`')
            val close = backtickRun(i + r, to, r)
            if (close < 0 || close == i + r) return i + r
            var open = i + r
            var shut = close
            // One space each side is padding when both are there (CommonMark).
            if (shut - open > 2 && s[open] == ' ' && s[shut - 1] == ' ' && s.substring(open, shut).isNotBlank()) {
                open++
                shut--
            }
            hidden += Hidden(i, open, Role.OPEN)
            marks += Range(open, shut, NoteMark.CODE)
            hidden += Hidden(shut, close + r, Role.CLOSE)
            return close + r
        }

        private fun emphasis(i: Int, to: Int): Int {
            val ch = s[i]
            val length = runLength(i, to, ch)
            val before = if (i > 0) s[i - 1] else ' '
            val after = if (i + length < to) s[i + length] else ' '
            if (after.isWhitespace()) return i + length
            if (ch == '_' && before.isLetterOrDigit()) return i + length
            var t = minOf(length, 3)
            while (t >= 1) {
                val open = i + length - t
                val close = closer(ch, t, open + t, to)
                if (close > open + t) {
                    hidden += Hidden(open, open + t, Role.OPEN)
                    if (t != 1) marks += Range(open + t, close, NoteMark.BOLD)
                    if (t != 2) marks += Range(open + t, close, NoteMark.ITALIC)
                    run(open + t, close)
                    hidden += Hidden(close, close + t, Role.CLOSE)
                    return close + t
                }
                t--
            }
            return i + length
        }

        /** Where the closing run for an opener of [t] [ch]s starts, searching `[from, to)`; -1 when there is none. */
        private fun closer(ch: Char, t: Int, from: Int, to: Int): Int {
            var j = from
            while (j < to) {
                val c = s[j]
                if (c == '\\' && j + 1 < to) {
                    j += 2
                    continue
                }
                if (c == '`') {
                    val r = runLength(j, to, '`')
                    val end = backtickRun(j + r, to, r)
                    j = if (end >= 0) end + r else j + r
                    continue
                }
                if (c == ch) {
                    val r = runLength(j, to, ch)
                    val okBefore = j > 0 && !s[j - 1].isWhitespace()
                    val afterRun = if (j + r < to) s[j + r] else ' '
                    val okAfter = ch != '_' || !afterRun.isLetterOrDigit()
                    if (okBefore && okAfter) {
                        val at = when {
                            t == 1 && r % 2 == 1 -> j + r - 1
                            t == 2 && r >= 2 -> j + r - 2
                            t == 3 && r >= 3 -> j + r - 3
                            else -> -1
                        }
                        // A closer right against the opener holds nothing: not a pair.
                        if (at > from) return at
                    }
                    j += r
                    continue
                }
                j++
            }
            return -1
        }

        private fun strike(i: Int, to: Int): Int {
            val r = runLength(i, to, '~')
            if (r != 2 || i + 2 >= to || s[i + 2].isWhitespace()) return i + r
            var j = i + 2
            while (j < to) {
                val c = s[j]
                if (c == '\\' && j + 1 < to) {
                    j += 2
                    continue
                }
                if (c == '~') {
                    val len = runLength(j, to, '~')
                    if (len == 2 && !s[j - 1].isWhitespace() && j > i + 2) {
                        hidden += Hidden(i, i + 2, Role.OPEN)
                        marks += Range(i + 2, j, NoteMark.STRIKE)
                        run(i + 2, j)
                        hidden += Hidden(j, j + 2, Role.CLOSE)
                        return j + 2
                    }
                    j += len
                    continue
                }
                j++
            }
            return i + r
        }

        private fun link(i: Int, to: Int): Int {
            var depth = 0
            var k = i
            while (k < to) {
                val c = s[k]
                if (c == '\\') {
                    k += 2
                    continue
                }
                // Code spans first, as in CommonMark: a bracket inside one is text.
                if (c == '`') {
                    val r = runLength(k, to, '`')
                    val end = backtickRun(k + r, to, r)
                    k = if (end >= 0) end + r else k + r
                    continue
                }
                if (c == '[') depth++
                if (c == ']') {
                    depth--
                    if (depth == 0) break
                }
                k++
            }
            if (k >= to || k + 1 >= to || s[k + 1] != '(' || k == i + 1) return i + 1
            var e = k + 2
            while (e < to && s[e] != ')' && !s[e].isWhitespace()) e++
            if (e >= to || s[e] != ')' || e == k + 2) return i + 1
            val url = s.substring(k + 2, e)
            hidden += Hidden(i, i + 1, Role.OPEN)
            marks += Range(i + 1, k, NoteMark.link(url))
            run(i + 1, k)
            hidden += Hidden(k, e + 1, Role.CLOSE)
            return e + 1
        }

        /** An image inside a sentence is text: its brackets must not read as a link. */
        private fun image(i: Int, to: Int): Int {
            if (i + 1 >= to || s[i + 1] != '[') return i + 1
            val m = IMAGE.matchAt(s, i) ?: return i + 1
            return if (m.range.last < to) m.range.last + 1 else i + 1
        }

        private fun tag(i: Int, to: Int): Int {
            val m = TAG.matchAt(s, i) ?: return i + 1
            val openEnd = m.range.last + 1
            if (openEnd > to || m.groupValues[1].isNotEmpty()) return i + 1
            val name = m.groupValues[2].lowercase(Locale.ROOT)
            if (name !in KNOWN_TAGS) return openEnd
            // The matching close, counting nested tags of the same name.
            val same = Regex("""</?$name(?:\s[^<>]*)?>""", RegexOption.IGNORE_CASE)
            var depth = 1
            var closeStart = -1
            var closeEnd = -1
            for (t in same.findAll(s.substring(0, to), openEnd)) {
                if (t.value.startsWith("</")) depth-- else if (!t.value.endsWith("/>")) depth++
                if (depth == 0) {
                    closeStart = t.range.first
                    closeEnd = t.range.last + 1
                    break
                }
            }
            if (closeStart < 0) return openEnd
            hidden += Hidden(i, openEnd, Role.OPEN)
            for (mark in tagMarks(name, m.groupValues[3])) marks += Range(openEnd, closeStart, mark)
            if (name != "code") run(openEnd, closeStart)
            hidden += Hidden(closeStart, closeEnd, Role.CLOSE)
            return closeEnd
        }
    }

    /** The marks a tag named [name] with [attributes] puts on its text. */
    internal fun tagMarks(name: String, attributes: String): List<NoteMark> {
        val attrs = HashMap<String, String>()
        for (a in ATTRIBUTE.findAll(attributes)) {
            val value = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: ""
            attrs[a.groupValues[1].lowercase(Locale.ROOT)] = value
        }
        val css = attrs["style"]?.let(NoteCss::declarations).orEmpty()
        val out = ArrayList<NoteMark>()
        var underlineStyle: String? = null
        for ((prop, value) in css) {
            val v = value.lowercase(Locale.ROOT)
            when (prop) {
                "text-decoration-style" -> underlineStyle = v
                "text-decoration", "text-decoration-line" -> {
                    val words = v.split(' ', ',').filter { it.isNotEmpty() }
                    words.firstOrNull { it in UNDERLINE_STYLES }?.let { underlineStyle = it }
                }
            }
        }
        when (name) {
            "b", "strong" -> out += NoteMark.BOLD
            "i", "em" -> out += NoteMark.ITALIC
            "s", "del", "strike" -> out += NoteMark.STRIKE
            "code" -> out += NoteMark.CODE
            "u", "ins" -> out += NoteMark(NoteMarkKind.UNDERLINE, underlineStyle?.takeIf { it in UNDERLINE_STYLES && it != "solid" })
            "mark" -> {
                val bg = css.firstOrNull { it.first == "background" || it.first == "background-color" }?.second
                out += NoteMark(NoteMarkKind.HIGHLIGHT, bg?.let { NoteCss.colour(it) ?: it.lowercase(Locale.ROOT) })
            }
            "font" -> {
                attrs["color"]?.let { out += NoteMark(NoteMarkKind.COLOR, NoteCss.colour(it) ?: it.lowercase(Locale.ROOT)) }
                attrs["face"]?.let { face -> NoteFont.of(face.split(',').first())?.let { out += NoteMark.font(it) } }
            }
        }
        for ((prop, value) in css) {
            val v = value.lowercase(Locale.ROOT)
            when (prop) {
                "color" -> out += NoteMark(NoteMarkKind.COLOR, NoteCss.colour(v) ?: v)
                "background", "background-color" -> if (name != "mark") out += NoteMark(NoteMarkKind.HIGHLIGHT, NoteCss.colour(v) ?: v)
                "font-family" -> {
                    val family = v.split(',').first().trim().trim('"', '\'')
                    out += NoteMark(NoteMarkKind.FONT, NoteFont.of(family)?.css ?: family)
                }
                "font-size" -> NoteCss.size(v)?.let { out += NoteMark(NoteMarkKind.SIZE, it) }
                "font-weight" -> if (v == "bold" || v == "bolder" || (v.toIntOrNull() ?: 0) >= 600) out += NoteMark.BOLD
                "font-style" -> if (v == "italic" || v == "oblique") out += NoteMark.ITALIC
                "text-decoration", "text-decoration-line" -> {
                    if ("line-through" in v) out += NoteMark.STRIKE
                    if ("underline" in v && name != "u" && name != "ins") {
                        out += NoteMark(NoteMarkKind.UNDERLINE, underlineStyle?.takeIf { it in UNDERLINE_STYLES && it != "solid" })
                    }
                }
            }
        }
        return out.distinct()
    }

    private val UNDERLINE_STYLES = setOf("solid", "wavy", "dotted", "dashed", "double")

    // ---- the visible text of a line ----

    /** [line]'s text as it shows. A line drawn as something else (a table row, an image) is its source. */
    fun visible(read: Read, line: Line): Visible {
        val from = if (line.kind.inline) line.contentStart else line.start
        if (!line.kind.inline) {
            val text = read.source.substring(from, line.end)
            return Visible(text, emptyList(), IntArray(text.length) { from + it })
        }
        return visibleOf(read.source, from, line.end, read.hidden, read.marks)
    }

    /** `[from, to)` of [source] with [hidden] taken out and [marks] moved to the visible text's offsets. */
    private fun visibleOf(source: String, from: Int, to: Int, hidden: List<Hidden>, marks: List<Range>): Visible {
        val text = StringBuilder()
        val map = ArrayList<Int>()
        // How many visible characters come before each source offset in the span.
        val before = IntArray(to - from + 1)
        val inSpan = hidden.filter { it.start < to && it.end > from }
        var h = 0
        var k = from
        while (k < to) {
            before[k - from] = text.length
            while (h < inSpan.size && inSpan[h].end <= k) h++
            val skip = h < inSpan.size && inSpan[h].start <= k && k < inSpan[h].end
            if (!skip) {
                text.append(source[k])
                map += k
            }
            k++
        }
        before[to - from] = text.length
        val out = ArrayList<Range>()
        for (r in marks) {
            if (r.end <= from || r.start >= to) continue
            val s = before[(r.start - from).coerceIn(0, to - from)]
            val e = before[(r.end - from).coerceIn(0, to - from)]
            if (e > s) out += Range(s, e, r.mark)
        }
        return Visible(text.toString(), out, map.toIntArray())
    }

    /** [content] read as one line's inline text, with nothing before it: its visible text and marks. */
    fun readInline(content: String): Visible {
        val hidden = ArrayList<Hidden>()
        val marks = ArrayList<Range>()
        InlineReader(content, hidden, marks).run(0, content.length)
        hidden.sortBy { it.start }
        return visibleOf(content, 0, content.length, hidden, marks)
    }

    // ---- writing ----

    /**
     * [marks] (in [text]'s offsets) merged where they touch, cut to the text,
     * empty ones dropped, in a fixed order: what two equal mark sets compare as.
     */
    fun normalize(length: Int, marks: List<Range>): List<Range> {
        val out = ArrayList<Range>()
        for ((mark, ranges) in marks.groupBy { it.mark }) {
            var cur: Range? = null
            for (r in ranges.map { Range(it.start.coerceIn(0, length), it.end.coerceIn(0, length), it.mark) }.sortedBy { it.start }) {
                if (r.end <= r.start) continue
                val c = cur
                cur = when {
                    c == null -> r
                    r.start <= c.end -> Range(c.start, maxOf(c.end, r.end), mark)
                    else -> {
                        out += c
                        r
                    }
                }
            }
            cur?.let { out += it }
        }
        return out.sortedWith(compareBy<Range>({ it.start }, { -it.end }, { it.mark.kind.ordinal }, { it.mark.value.orEmpty() }))
    }

    /**
     * One line's text with [marks] as Markdown and the tag subset. Read back
     * with [readInline], the result shows [text] with the same marks: plain
     * Markdown when that reads back right, else with escapes, else with tags.
     */
    fun writeInline(text: String, marks: List<Range>): String {
        val wanted = normalize(text.length, marks)
        for (mode in 0..2) {
            val out = emit(text, wanted, escapeAll = mode >= 1, html = mode >= 2)
            if (mode == 2) return out
            val back = readInline(out)
            if (back.text == text && normalize(text.length, back.marks) == wanted) return out
        }
        return text
    }

    /** [writeInline] for a paragraph: a start that would read as a list, heading or quote gets a backslash. */
    fun writeParagraph(text: String, marks: List<Range>): String {
        val out = writeInline(text, marks)
        val m = BLOCK_START.find(out) ?: return guardRule(out)
        val at = m.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return out.substring(0, at) + "\\" + out.substring(at)
    }

    private fun guardRule(out: String): String {
        if (!RULE.matches(out) && !IMAGES_LINE.matches(out)) return out
        val at = out.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return out.substring(0, at) + "\\" + out.substring(at)
    }

    private class Open(val range: Range, val closer: String)

    private fun emit(text: String, marks: List<Range>, escapeAll: Boolean, html: Boolean): String {
        val bounds = sortedSetOf(0, text.length)
        for (r in marks) {
            bounds += r.start
            bounds += r.end
        }
        val points = bounds.toList()
        val sb = StringBuilder()
        val open = ArrayList<Open>()
        for ((k, p) in points.withIndex()) {
            val idx = open.indexOfFirst { it.range.end <= p }
            if (idx >= 0) {
                val reopen = open.subList(idx + 1, open.size).map { it.range }.filter { it.end > p }
                for (q in open.lastIndex downTo idx) sb.append(open[q].closer)
                while (open.size > idx) open.removeAt(open.lastIndex)
                for (r in reopen) open += opener(r, text, sb, html)
            }
            if (p >= text.length) break
            var starting = marks.filter { it.start == p }
            if (starting.isNotEmpty() && open.lastOrNull()?.range?.mark?.kind == NoteMarkKind.CODE) {
                // Code is always innermost: it closes and opens again inside the new marks.
                val code = open.removeAt(open.lastIndex)
                sb.append(code.closer)
                starting = starting + code.range
            }
            val ordered = starting.sortedWith(compareBy<Range>({ if (it.mark.kind == NoteMarkKind.CODE) 1 else 0 }, { -it.end }))
            for (r in ordered) open += opener(r, text, sb, html)
            val next = points[k + 1]
            val inCode = open.any { it.range.mark.kind == NoteMarkKind.CODE }
            sb.append(escape(text.substring(p, next), escapeAll && !inCode))
        }
        for (q in open.lastIndex downTo 0) sb.append(open[q].closer)
        return sb.toString()
    }

    /** Writes [r]'s opening marker into [sb] and returns what will close it. */
    private fun opener(r: Range, text: String, sb: StringBuilder, html: Boolean): Open {
        val v = r.mark.value?.let(::attributeSafe)
        val (open, close) = when (r.mark.kind) {
            NoteMarkKind.BOLD -> if (html) "<b>" to "</b>" else "**" to "**"
            NoteMarkKind.ITALIC -> when {
                html -> "<i>" to "</i>"
                else -> {
                    val before = sb.lastOrNull() ?: ' '
                    val after = text.getOrNull(r.end) ?: ' '
                    if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) "_" to "_" else "*" to "*"
                }
            }
            NoteMarkKind.STRIKE -> if (html) "<s>" to "</s>" else "~~" to "~~"
            // Backticks even in the tag form: inside `<code>` a bracket or a
            // star would still be read, inside backticks nothing is.
            NoteMarkKind.CODE -> run {
                val content = text.substring(r.start, r.end)
                var longest = 0
                var streak = 0
                for (c in content) {
                    streak = if (c == '`') streak + 1 else 0
                    longest = maxOf(longest, streak)
                }
                val fence = "`".repeat(longest + 1)
                val padded = content.length > 2 && content.startsWith(' ') && content.endsWith(' ') && content.isNotBlank()
                val pad = if (content.startsWith('`') || content.endsWith('`') || padded) " " else ""
                (fence + pad) to (pad + fence)
            }
            NoteMarkKind.UNDERLINE -> (if (v == null) "<u>" else "<u style=\"text-decoration-style:$v\">") to "</u>"
            NoteMarkKind.COLOR -> "<span style=\"color:$v\">" to "</span>"
            NoteMarkKind.HIGHLIGHT -> (if (v == null) "<mark>" else "<mark style=\"background:$v\">") to "</mark>"
            NoteMarkKind.FONT -> "<span style=\"font-family:$v\">" to "</span>"
            NoteMarkKind.SIZE -> "<span style=\"font-size:$v\">" to "</span>"
            NoteMarkKind.LINK -> "[" to "](" + linkSafe(r.mark.value.orEmpty()) + ")"
        }
        sb.append(open)
        return Open(r, close)
    }

    /**
     * The markers that put [mark] round text typed after a format was picked
     * with nothing selected: the Markdown ones, or with [html] the tags,
     * for a place where the Markdown would not read back.
     */
    internal fun markers(mark: NoteMark, html: Boolean): Pair<String, String> {
        val v = mark.value?.let(::attributeSafe)
        return when (mark.kind) {
            NoteMarkKind.BOLD -> if (html) "<b>" to "</b>" else "**" to "**"
            NoteMarkKind.ITALIC -> if (html) "<i>" to "</i>" else "*" to "*"
            NoteMarkKind.STRIKE -> if (html) "<s>" to "</s>" else "~~" to "~~"
            NoteMarkKind.CODE -> if (html) "<code>" to "</code>" else "`" to "`"
            NoteMarkKind.UNDERLINE -> (if (v == null) "<u>" else "<u style=\"text-decoration-style:$v\">") to "</u>"
            NoteMarkKind.COLOR -> "<span style=\"color:$v\">" to "</span>"
            NoteMarkKind.HIGHLIGHT -> (if (v == null) "<mark>" else "<mark style=\"background:$v\">") to "</mark>"
            NoteMarkKind.FONT -> "<span style=\"font-family:$v\">" to "</span>"
            NoteMarkKind.SIZE -> "<span style=\"font-size:$v\">" to "</span>"
            NoteMarkKind.LINK -> "[" to "](" + linkSafe(mark.value.orEmpty()) + ")"
        }
    }

    private fun attributeSafe(value: String): String = value.filter { it.isLetterOrDigit() || it in "#.%-" }

    private fun linkSafe(url: String): String =
        url.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("\n", "")

    private fun escape(text: String, all: Boolean): String {
        if (!all) return text
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            if (c in "\\*_~`[]<>!") sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    // ---- for readers that want words, not markup ----

    /**
     * [text] without the tag subset and with each picture of the note's own
     * reduced to its description: what a search hit, a typed passage, a
     * shared `.txt` or a copied note should hold. Markdown's own markers stay,
     * as they always have.
     */
    fun strip(text: String): String {
        if ('<' !in text && "](attachments/" !in text) return text
        return text.replace(ATTACHMENT_IMAGE) { it.groupValues[1] }.replace(STYLE_TAGS, "")
    }

    /**
     * [text] with the tag subset and the note's own pictures turned into
     * spaces, so every offset into it still points at the same word: for the
     * readers that report where in the note they found something.
     */
    fun blank(text: String): String {
        if ('<' !in text && "](attachments/" !in text) return text
        val chars = text.toCharArray()
        for (regex in listOf(STYLE_TAGS, ATTACHMENT_IMAGE)) {
            for (m in regex.findAll(text)) for (k in m.range) if (chars[k] != '\n') chars[k] = ' '
        }
        return String(chars)
    }

    /**
     * [strip] with a map back: for each offset of [text] (and its end), the
     * offset in the stripped text, so a found range can be carried across.
     */
    fun stripMapped(text: String): Pair<String, IntArray> {
        val cuts = ArrayList<IntRange>()
        if ('<' in text || "](attachments/" in text) {
            for (m in ATTACHMENT_IMAGE.findAll(text)) cuts += m.range
            for (m in STYLE_TAGS.findAll(text)) if (cuts.none { it.first <= m.range.first && m.range.last <= it.last }) cuts += m.range
        }
        if (cuts.isEmpty()) return text to IntArray(text.length + 1) { it }
        cuts.sortBy { it.first }
        val out = StringBuilder(text.length)
        val map = IntArray(text.length + 1)
        var c = 0
        var k = 0
        while (k < text.length) {
            map[k] = out.length
            val cut = cuts.getOrNull(c)
            if (cut != null && k == cut.first) {
                // An image keeps its description: the text between the brackets.
                val m = ATTACHMENT_IMAGE.matchAt(text, k)
                if (m != null && m.range == cut) {
                    val alt = m.groups[1]!!
                    for (j in k..cut.last) map[j] = out.length + (j - alt.range.first).coerceIn(0, alt.value.length)
                    out.append(alt.value)
                }
                k = cut.last + 1
                c++
                continue
            }
            out.append(text[k])
            k++
        }
        map[text.length] = out.length
        return out.toString() to map
    }

    /** The images on an [LineKind.IMAGES] line's text: each one's description and address. */
    fun images(lineText: String): List<Pair<String, String>> =
        IMAGE.findAll(lineText).map { it.groupValues[1] to it.groupValues[2] }.toList()

    /** One line of pictures, side by side when there are several. */
    fun imageLine(urls: List<String>): String = urls.joinToString(" ") { "![](${linkSafe(it)})" }
}
