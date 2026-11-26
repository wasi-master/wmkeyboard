package com.wasimaster.wmkeyboard.core.tools

/**
 * Reads a model's answer, or a note, into blocks a reader can draw:
 * paragraphs, headings (ATX and setext), list items, task items, quotes,
 * tables, images, fenced code, footnotes and LaTeX maths ([LatexMath]).
 *
 * [AiMarkdown] answers a different question. It removes the markers, because a
 * text field cannot draw them. A chat bubble can, and a fenced block there is
 * worth more than its text: it is the part of an answer the user most often
 * wants to copy alone, so the bubble has to know where one starts and stops.
 *
 * No Compose: the drawing is the caller's, so this runs on the JVM under test.
 * The emphasis rules are the conservative ones [AiMarkdown] uses, for the same
 * reason: `2 * 3 * 4` and `file_name` are not emphasis.
 *
 * The answer arrives as a stream, so every rule has to read a half-written
 * text sensibly. A fence that has not closed yet is a code block that runs to
 * the end ([Block.Code.closed] is false), and an emphasis marker without its
 * partner is plain text until the partner arrives.
 *
 * Every block carries the index of the source line it starts on ([Block.line],
 * into `text.lines()`). The Notes tool (#560) needs it twice: a tap on a task
 * item rewrites that one line to tick it, and a tap on any other block opens
 * the editor with the caret at that line.
 */
object AiMarkdownBlocks {

    /**
     * [MATH] is a formula inside a sentence (#811), written out by [LatexMath.inline].
     * [FORMULA] marks one that needs drawing in two dimensions: its [Span.url]
     * is the LaTeX source, and the text under it is the plain version.
     */
    enum class Style { BOLD, ITALIC, CODE, STRIKE, UNDERLINE, HIGHLIGHT, SUPERSCRIPT, SUBSCRIPT, LINK, MATH, FORMULA }

    /** [style] over `[start, end)` of [Inline.text]. [url] is set on a [Style.LINK] or [Style.FORMULA] span only. */
    data class Span(val start: Int, val end: Int, val style: Style, val url: String? = null)

    /** One run of text with its markers removed and turned into [spans]. */
    data class Inline(val text: String, val spans: List<Span> = emptyList())

    enum class Align { START, CENTER, END }

    sealed interface Block {
        /** Index of the first source line of this block, into the parsed text's `lines()`. */
        val line: Int

        data class Paragraph(val inline: Inline, override val line: Int = 0) : Block
        data class Heading(val level: Int, val inline: Inline, override val line: Int = 0) : Block

        /** [marker] is what the bubble draws in front: a dot, or `3.` */
        data class Bullet(val indent: Int, val marker: String, val inline: Inline, override val line: Int = 0) : Block

        /**
         * A task-list item, `- [ ] text` or `- [x] text`. Its own block rather
         * than a bullet whose text starts with a bracket, so a reader can draw a
         * box and, through [line], tick it by rewriting the source.
         */
        data class Task(val indent: Int, val checked: Boolean, val inline: Inline, override val line: Int = 0) : Block

        /** Consecutive `>` lines at the same [depth], joined. `>> x` is depth 2. */
        data class Quote(val inline: Inline, override val line: Int = 0, val depth: Int = 1) : Block

        /** [code] has no fences and no trailing newline. */
        data class Code(val language: String, val code: String, val closed: Boolean, override val line: Int = 0) : Block
        data class Rule(override val line: Int = 0) : Block

        /**
         * A display formula (#811): `$$…$$`, `\[…\]`, a bare `\begin{align}`
         * or a ```` ```math ```` fence. [latex] is the source without its
         * delimiters, for [LatexMath.parse]; [closed] is false while it streams.
         */
        data class Math(val latex: String, val closed: Boolean, override val line: Int = 0) : Block

        /** A GFM pipe table. Every row has exactly as many cells as [header]. */
        data class Table(
            val header: List<Inline>,
            val align: List<Align>,
            val rows: List<List<Inline>>,
            override val line: Int = 0,
        ) : Block

        /**
         * An image standing on a line of its own. [link] is where a linked
         * image (`[![alt](src)](href)`) points, null for a bare one.
         */
        data class Image(val alt: String, val url: String, val link: String?, override val line: Int = 0) : Block

        /**
         * One footnote, listed at the end in the order the text first refers
         * to them. [number] is what the reference in the text shows.
         */
        data class Footnote(val number: Int, val inline: Inline, override val line: Int = 0) : Block
    }

    /**
     * A task line taken apart: what sits before the box, the box, and the text
     * after it. [indent] and [marker] are kept verbatim so a rewrite puts the
     * line back exactly as it was, with only the box changed.
     */
    data class TaskLine(val indent: String, val marker: Char, val checked: Boolean, val text: String)

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 176 KB across this file's patterns.
    private val FENCE by lazy { Regex("""^ {0,3}(```|~~~)\s*([\w+#.-]*).*$""") }
    private val HEADING by lazy { Regex("""^ {0,3}(#{1,6}) +(.*?)( +#+ *)?$""") }
    private val BULLET by lazy { Regex("""^( *)[-*+] +(.*)$""") }
    private val ORDERED by lazy { Regex("""^( *)(\d{1,3})[.)] +(.*)$""") }
    private val QUOTE by lazy { Regex("""^ {0,3}((?:> ?)+)(.*)$""") }
    private val RULE by lazy { Regex("""^ {0,3}([-*_])( *\1){2,} *$""") }
    private val SETEXT_1 by lazy { Regex("""^ {0,3}=+ *$""") }
    private val SETEXT_2 by lazy { Regex("""^ {0,3}-+ *$""") }
    private val TABLE_DELIMITER by lazy { Regex("""^ *\|? *:?-+:? *(\| *:?-+:? *)*\|? *$""") }

    /** `[id]: url "title"`. The id may not start with `^`, which is a footnote. */
    private val LINK_DEFINITION by lazy { Regex("""^ {0,3}\[([^\]^\n][^\]\n]*)]: *<?([^\s>]+)>?(?: +(?:"[^"]*"|'[^']*'|\([^)]*\)))? *$""") }
    private val FOOTNOTE_DEFINITION by lazy { Regex("""^ {0,3}\[\^([^\]\s]+)]: ?(.*)$""") }

    /** An image, linked or not, with nothing else on its line. */
    private val IMAGE_LINE by lazy {
        Regex("""^ {0,3}(?:\[!\[([^\]\n]*)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)|!\[([^\]\n]*)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)) *$""")
    }

    /**
     * `- [ ] text`, `- [x] text`, or a bare `- [ ]` somebody has only just typed.
     * One to four spaces between the marker and the box, as CommonMark allows.
     */
    private val TASK by lazy { Regex("""^( *)([-*+]) {1,4}\[([ xX])\](?: +(.*)|)$""") }

    // Inline rules. Order matters where two can start at the same place: the
    // first in [INLINE_RULES] wins, so a linked image beats the link around it.
    private val CODE by lazy { Regex("""(`+)(.+?)\1(?!`)""") }
    private val AUTOLINK by lazy { Regex("""<((?:https?|ftp)://[^\s<>]+|mailto:[^\s<>]+|[\w.+-]+@[\w-]+(?:\.[\w-]+)+)>""") }
    private val HTML_PAIR by lazy {
        Regex("""<(b|strong|i|em|del|s|strike|u|ins|code|kbd|mark|sub|sup)>(.+?)</\1>""", RegexOption.IGNORE_CASE)
    }
    private val HTML_BREAK by lazy { Regex("""<br *\/?>""", RegexOption.IGNORE_CASE) }
    private val LINKED_IMAGE by lazy {
        Regex("""\[!\[([^\]\n]*)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)""")
    }
    private val IMAGE by lazy { Regex("""!\[([^\]\n]*)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*"|\s+'[^']*')?\s*\)""") }
    private val LINK by lazy { Regex("""\[([^\]\n]*)]\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*"|\s+'[^']*')?\s*\)""") }
    private val REF_IMAGE by lazy { Regex("""!\[([^\]\n]*)]\[([^\]\n]*)]""") }
    private val INLINE_FOOTNOTE by lazy { Regex("""\^\[([^\]\n]+)]""") }
    private val FOOTNOTE_REF by lazy { Regex("""\[\^([^\]\s]+)](?!:)""") }
    private val REF_LINK by lazy { Regex("""\[([^\]\n]+)](?:\[([^\]\n]*)])?""") }
    private val BOLD by lazy { Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""") }
    private val STRIKE by lazy { Regex("""~~(?=\S)(.+?)(?<=\S)~~""") }
    // The very pattern AiMarkdown.ITALIC compiles, so the two share one copy.
    private val ITALIC: Regex get() = AiMarkdown.ITALIC
    private val BARE_URL by lazy { Regex("""(?<![\w/@.:])(?:https?://|www\.)[^\s<>()\[\]]*[^\s<>()\[\].,:;!?'"*_~]""") }

    private enum class Kind {
        CODE, MATH, AUTOLINK, HTML_PAIR, HTML_BREAK, LINKED_IMAGE, IMAGE, LINK, REF_IMAGE,
        INLINE_FOOTNOTE, FOOTNOTE_REF, REF_LINK, BOLD, STRIKE, ITALIC, BARE_URL,
    }

    private val INLINE_RULES by lazy {
        listOf(
            Kind.CODE to CODE,
            Kind.MATH to LatexMath.INLINE,
            Kind.AUTOLINK to AUTOLINK,
            Kind.HTML_PAIR to HTML_PAIR,
            Kind.HTML_BREAK to HTML_BREAK,
            Kind.LINKED_IMAGE to LINKED_IMAGE,
            Kind.IMAGE to IMAGE,
            Kind.LINK to LINK,
            Kind.REF_IMAGE to REF_IMAGE,
            Kind.INLINE_FOOTNOTE to INLINE_FOOTNOTE,
            Kind.FOOTNOTE_REF to FOOTNOTE_REF,
            Kind.REF_LINK to REF_LINK,
            Kind.BOLD to BOLD,
            Kind.STRIKE to STRIKE,
            Kind.ITALIC to ITALIC,
            Kind.BARE_URL to BARE_URL,
        )
    }

    /** CommonMark's escapable characters: a backslash before one makes it literal. */
    private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /**
     * What the inline rules need from the rest of the text: the link
     * definitions, and the footnotes, numbered as the text first refers to them.
     */
    class Context internal constructor(
        internal val links: Map<String, String> = emptyMap(),
        internal val footnotes: Map<String, Pair<String, Int>> = emptyMap(),
    ) {
        /** Footnote id to its number, in order of first reference. */
        internal val numbers = LinkedHashMap<String, Int>()

        /** Inline footnotes (`^[text]`) met so far: number to its text and the line it was met on. */
        internal val inlineNotes = LinkedHashMap<Int, Pair<String, Int>>()

        /** The line the block being read starts on; an inline footnote is listed against it. */
        internal var line = 0

        internal fun number(id: String): Int = numbers.getOrPut(id) { numbers.size + inlineNotes.size + 1 }
        internal fun inlineNote(text: String): Int {
            val n = numbers.size + inlineNotes.size + 1
            inlineNotes[n] = text to line
            return n
        }
    }

    /** [line] read as a task item, or null when it is not one. The same rule [parse] uses. */
    fun task(line: String): TaskLine? {
        val match = TASK.matchEntire(line) ?: return null
        return TaskLine(
            indent = match.groupValues[1],
            marker = match.groupValues[2][0],
            checked = match.groupValues[3] != " ",
            text = match.groupValues[4],
        )
    }

    private val WHITESPACE by lazy { Regex("""\s+""") }

    private fun refKey(label: String) = label.trim().replace(WHITESPACE, " ").lowercase()

    fun parse(text: String): List<Block> {
        val lines = text.lines()
        val ctx = collectDefinitions(lines)
        val skip = HashSet<Int>()
        // Definitions are not drawn where they stand: links resolve through
        // them, footnotes are listed at the end.
        run {
            var inFence = false
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (FENCE.matches(line)) inFence = !inFence
                if (!inFence) {
                    if (LINK_DEFINITION.matches(line)) skip += i
                    if (FOOTNOTE_DEFINITION.matches(line)) {
                        skip += i
                        val end = footnoteEnd(lines, i)
                        for (j in i + 1 until end) skip += j
                        i = end
                        continue
                    }
                }
                i++
            }
        }

        fun inl(s: String, line: Int): Inline {
            ctx.line = line
            return inline(s, ctx)
        }

        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        var paragraphStart = 0
        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += Block.Paragraph(inl(paragraph.joinToString("\n"), paragraphStart), paragraphStart)
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val lineIndex = i
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val mark = fence.groupValues[1]
                val code = ArrayList<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(mark) && lines[i].trim().all { it == mark[0] }) {
                        closed = true
                        i++
                        break
                    }
                    code += lines[i]
                    i++
                }
                blocks += if (fence.groupValues[2].equals("math", ignoreCase = true)) {
                    Block.Math(code.joinToString("\n"), closed, lineIndex)
                } else {
                    Block.Code(fence.groupValues[2], code.joinToString("\n"), closed, lineIndex)
                }
                continue
            }
            if (lineIndex !in skip) {
                val math = LatexMath.display(lines, lineIndex)
                if (math != null) {
                    flush()
                    blocks += Block.Math(math.latex, math.closed, lineIndex)
                    i = math.end
                    continue
                }
            }
            i++
            if (lineIndex in skip) {
                flush()
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            // A line of `=` or `-` under a paragraph makes it a heading, and that
            // is checked before the rule: `Title\n---` is a heading, not a rule.
            if (paragraph.isNotEmpty() && (SETEXT_1.matches(line) || SETEXT_2.matches(line))) {
                val level = if (SETEXT_1.matches(line)) 1 else 2
                blocks += Block.Heading(level, inl(paragraph.joinToString(" ") { it.trim() }, paragraphStart), paragraphStart)
                paragraph.clear()
                continue
            }
            // Before the bullet rule: `* * *` is a rule, not a list of stars.
            if (RULE.matches(line)) {
                flush()
                blocks += Block.Rule(lineIndex)
                continue
            }
            // A table is a row of cells over a delimiter row.
            if ('|' in line && i < lines.size && TABLE_DELIMITER.matches(lines[i]) && '|' in (line + lines[i]).replace("\\|", "")) {
                val header = cells(line)
                val delimiter = cells(lines[i])
                if (header.size == delimiter.size) {
                    flush()
                    val align = delimiter.map {
                        val d = it.trim()
                        when {
                            d.startsWith(':') && d.endsWith(':') -> Align.CENTER
                            d.endsWith(':') -> Align.END
                            else -> Align.START
                        }
                    }
                    i++
                    val rows = ArrayList<List<Inline>>()
                    while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i] && i !in skip) {
                        val row = cells(lines[i])
                        rows += List(header.size) { c -> inl(row.getOrElse(c) { "" }, i) }
                        i++
                    }
                    blocks += Block.Table(header.map { inl(it, lineIndex) }, align, rows, lineIndex)
                    continue
                }
            }
            val image = IMAGE_LINE.matchEntire(line)
            if (image != null) {
                flush()
                val g = image.groupValues
                blocks += if (image.groups[5] == null) {
                    Block.Image(g[1], g[2], g[3].ifEmpty { null }, lineIndex)
                } else {
                    Block.Image(g[4], g[5], null, lineIndex)
                }
                continue
            }
            val heading = HEADING.matchEntire(line)
            // Before the bullet rule too: a task is a bullet with a box in front.
            val task = TASK.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, inl(heading.groupValues[2], lineIndex), lineIndex)
                }
                task != null -> {
                    flush()
                    blocks += Block.Task(
                        task.groupValues[1].length / 2,
                        task.groupValues[3] != " ",
                        inl(task.groupValues[4], lineIndex),
                        lineIndex,
                    )
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Bullet(bullet.groupValues[1].length / 2, "•", inl(bullet.groupValues[2], lineIndex), lineIndex)
                }
                ordered != null -> {
                    flush()
                    blocks += Block.Bullet(
                        ordered.groupValues[1].length / 2,
                        ordered.groupValues[2] + ".",
                        inl(ordered.groupValues[3], lineIndex),
                        lineIndex,
                    )
                }
                quote != null -> {
                    flush()
                    // Lines at the same depth are one quote; a deeper or
                    // shallower line starts the next.
                    val depth = quote.groupValues[1].count { it == '>' }
                    val body = arrayListOf(quote.groupValues[2])
                    while (i < lines.size && i !in skip) {
                        val next = QUOTE.matchEntire(lines[i]) ?: break
                        if (next.groupValues[1].count { it == '>' } != depth) break
                        body += next.groupValues[2]
                        i++
                    }
                    blocks += Block.Quote(inl(body.joinToString("\n"), lineIndex), lineIndex, depth)
                }
                else -> {
                    if (paragraph.isEmpty()) paragraphStart = lineIndex
                    paragraph += line.trimEnd()
                }
            }
        }
        flush()

        // Footnotes, in the order the text first referred to them. A footnote's
        // own text can refer to another, so the list can grow while it is read.
        val notes = ArrayList<Block.Footnote>()
        val done = HashSet<Int>()
        while (true) {
            val pending = ctx.numbers.entries.map { it.value to it.key }.filter { it.first !in done } +
                ctx.inlineNotes.keys.filter { it !in done }.map { it to null }
            if (pending.isEmpty()) break
            for ((number, id) in pending) {
                done += number
                if (id != null) {
                    val (body, line) = ctx.footnotes[id] ?: continue
                    notes += Block.Footnote(number, inl(body, line), line)
                } else {
                    val (body, line) = ctx.inlineNotes.getValue(number)
                    notes += Block.Footnote(number, inl(body, line), line)
                }
            }
        }
        if (notes.isNotEmpty()) {
            notes.sortBy { it.number }
            blocks += Block.Rule(notes.first().line)
            blocks += notes
        }
        return blocks
    }

    /** Link definitions and footnote bodies, read ahead so a reference can come before its definition. */
    private fun collectDefinitions(lines: List<String>): Context {
        val links = HashMap<String, String>()
        val footnotes = HashMap<String, Pair<String, Int>>()
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.matches(line)) inFence = !inFence
            if (!inFence) {
                LINK_DEFINITION.matchEntire(line)?.let { m ->
                    links.putIfAbsent(refKey(m.groupValues[1]), m.groupValues[2])
                }
                val note = FOOTNOTE_DEFINITION.matchEntire(line)
                if (note != null) {
                    val end = footnoteEnd(lines, i)
                    val body = (listOf(note.groupValues[2]) + lines.subList(i + 1, end).map { it.trim() })
                        .joinToString("\n")
                        .replace(Regex("""\n{2,}"""), "\n")
                        .trim()
                    footnotes.putIfAbsent(note.groupValues[1], body to i)
                    i = end
                    continue
                }
            }
            i++
        }
        return Context(links, footnotes)
    }

    /** The line after a footnote that starts at [start]: its body runs on through indented lines. */
    private fun footnoteEnd(lines: List<String>, start: Int): Int {
        var j = start + 1
        var end = j
        while (j < lines.size) {
            val l = lines[j]
            when {
                l.isBlank() -> j++
                l.startsWith("    ") || l.startsWith("\t") -> {
                    j++
                    end = j
                }
                else -> break
            }
        }
        return end
    }

    /** A table row split on its unescaped pipes, the outer pipes dropped. */
    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') {
                cell.append('|')
                k += 2
                continue
            }
            if (c == '|') {
                out += cell.toString().trim()
                cell.clear()
            } else {
                cell.append(c)
            }
            k++
        }
        out += cell.toString().trim()
        return out
    }

    /** [text] with its inline markers turned into spans. */
    fun inline(text: String): Inline = inline(text, Context())

    private fun inline(text: String, ctx: Context): Inline {
        val raw = escape(text)
        val parsed = inlineRaw(raw, ctx)
        return Inline(unescape(parsed.text), parsed.spans)
    }

    /**
     * `\*` becomes one stand-in character, so span offsets survive [unescape].
     * Inline maths is copied as it is: `\{` and `\_` mean something there.
     */
    private fun escape(text: String): String {
        if ('\\' !in text) return text
        val math = LatexMath.INLINE.findAll(text).iterator()
        var nextMath = if (math.hasNext()) math.next().range else null
        val out = StringBuilder(text.length)
        var k = 0
        while (k < text.length) {
            if (nextMath != null && k == nextMath.first) {
                out.append(text, nextMath.first, nextMath.last + 1)
                k = nextMath.last + 1
                nextMath = if (math.hasNext()) math.next().range else null
                continue
            }
            // An escape that runs over the start of a formula: that formula was never one.
            while (nextMath != null && k > nextMath.first) nextMath = if (math.hasNext()) math.next().range else null
            val c = text[k]
            if (c == '\\' && k + 1 < text.length && text[k + 1] in ESCAPABLE) {
                out.append(stand(text[k + 1]))
                k += 2
            } else {
                out.append(c)
                k++
            }
        }
        return out.toString()
    }

    /**
     * A private-use stand-in for an escaped ASCII character, so `\*` can never
     * open emphasis while the inline rules run. One UTF-16 unit, so span
     * offsets hold when it is swapped back.
     */
    private fun stand(c: Char): Char = (0xE000 + c.code).toChar()

    private fun isStand(c: Char) = c.code in 0xE000 until 0xE080

    private fun unescape(text: String): String =
        if (text.none(::isStand)) text else buildString(text.length) { for (c in text) append(if (isStand(c)) (c.code - 0xE000).toChar() else c) }

    /** Inside code a backslash is literal, so a stand-in goes back to `\` and its character. */
    private fun literal(text: String): String =
        if (text.none(::isStand)) text else buildString { for (c in text) if (isStand(c)) append('\\').append((c.code - 0xE000).toChar()) else append(c) }

    private fun inlineRaw(text: String, ctx: Context): Inline {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        fun nested(s: String, style: Style?, url: String? = null) {
            val start = out.length
            val inner = inlineRaw(s, ctx)
            out.append(inner.text)
            inner.spans.mapTo(spans) { it.copy(start = it.start + start, end = it.end + start) }
            if (style != null && out.length > start) spans += Span(start, out.length, style, url)
        }
        fun plain(s: String, style: Style, url: String? = null) {
            val start = out.length
            out.append(s)
            if (out.length > start) spans += Span(start, out.length, style, url)
        }

        var at = 0
        while (at < text.length) {
            var best: Pair<Kind, MatchResult>? = null
            for ((kind, regex) in INLINE_RULES) {
                val m = find(kind, regex, text, at, ctx) ?: continue
                if (best == null || m.range.first < best.second.range.first) best = kind to m
            }
            if (best == null) break
            val (kind, m) = best
            out.append(text, at, m.range.first)
            val g = m.groupValues
            when (kind) {
                Kind.CODE -> {
                    val code = g[2].let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }
                    plain(literal(code), Style.CODE)
                }
                Kind.MATH -> {
                    val math = LatexMath.inline(literal(LatexMath.source(m)))
                    val start = out.length
                    out.append(math.text)
                    math.spans.mapTo(spans) { it.copy(start = it.start + start, end = it.end + start) }
                }
                Kind.AUTOLINK -> {
                    val target = g[1]
                    val url = if ('@' in target && !target.contains("://") && !target.startsWith("mailto:")) "mailto:$target" else target
                    plain(target.removePrefix("mailto:"), Style.LINK, url)
                }
                Kind.HTML_PAIR -> nested(
                    g[2],
                    when (g[1].lowercase()) {
                        "b", "strong" -> Style.BOLD
                        "i", "em" -> Style.ITALIC
                        "del", "s", "strike" -> Style.STRIKE
                        "u", "ins" -> Style.UNDERLINE
                        "code", "kbd" -> Style.CODE
                        "mark" -> Style.HIGHLIGHT
                        "sub" -> Style.SUBSCRIPT
                        else -> Style.SUPERSCRIPT
                    },
                )
                Kind.HTML_BREAK -> out.append('\n')
                Kind.LINKED_IMAGE -> plain(g[1].ifBlank { g[3] }, Style.LINK, g[3].ifEmpty { g[2] })
                Kind.IMAGE -> plain(g[1].ifBlank { g[2] }, Style.LINK, g[2])
                Kind.LINK -> when {
                    g[2].isBlank() -> nested(g[1], null)
                    g[1].isBlank() -> plain(g[2], Style.LINK, g[2])
                    else -> nested(g[1], Style.LINK, g[2])
                }
                Kind.REF_IMAGE -> {
                    val url = ctx.links.getValue(refKey(g[2].ifEmpty { g[1] }))
                    plain(g[1].ifBlank { url }, Style.LINK, url)
                }
                Kind.INLINE_FOOTNOTE -> plain(ctx.inlineNote(g[1]).toString(), Style.SUPERSCRIPT)
                Kind.FOOTNOTE_REF -> plain(ctx.number(g[1]).toString(), Style.SUPERSCRIPT)
                Kind.REF_LINK -> nested(g[1], Style.LINK, ctx.links.getValue(refKey(g[2].ifEmpty { g[1] })))
                Kind.BOLD -> nested(g[2], Style.BOLD)
                Kind.STRIKE -> nested(g[1], Style.STRIKE)
                Kind.ITALIC -> nested(g[2], Style.ITALIC)
                Kind.BARE_URL -> {
                    val url = m.value
                    plain(url, Style.LINK, if (url.startsWith("www.")) "https://$url" else url)
                }
            }
            at = m.range.last + 1
        }
        out.append(text, at, text.length)
        return Inline(out.toString(), spans)
    }

    /**
     * The first match of [regex] at or after [at] that is really a [kind]:
     * a reference only counts when its definition exists, otherwise its
     * brackets are plain text, and the search goes on past them.
     */
    private fun find(kind: Kind, regex: Regex, text: String, at: Int, ctx: Context): MatchResult? {
        val valid: (MatchResult) -> Boolean = when (kind) {
            Kind.REF_LINK, Kind.REF_IMAGE -> { m -> refKey(m.groupValues[2].ifEmpty { m.groupValues[1] }) in ctx.links }
            Kind.FOOTNOTE_REF -> { m -> m.groupValues[1] in ctx.footnotes }
            else -> return regex.find(text, at)
        }
        var from = at
        while (from < text.length) {
            val m = regex.find(text, from) ?: return null
            if (valid(m)) return m
            from = m.range.first + 1
        }
        return null
    }
}
