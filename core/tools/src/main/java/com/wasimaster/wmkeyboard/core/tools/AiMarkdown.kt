package com.wasimaster.wmkeyboard.core.tools

/**
 * Detects and removes markdown syntax from AI output. Models answer in
 * markdown whether or not the prompt asks for it, and a text field almost
 * never renders it — so a result with markers in it gets a "plain text"
 * toggle on the panel, on by default.
 *
 * Detection is deliberately conservative: only markers that are unambiguous
 * in ordinary prose count, so a lone asterisk or an underscore inside a
 * file_name never trips the toggle into existence.
 */
object AiMarkdown {

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 58 KB across this file's patterns.
    private val HEADING by lazy { Regex("""^ {0,3}#{1,6} +\S""", RegexOption.MULTILINE) }
    private val BULLET by lazy { Regex("""^ {0,3}[-*+] +\S""", RegexOption.MULTILINE) }
    private val QUOTE by lazy { Regex("""^ {0,3}> +""", RegexOption.MULTILINE) }
    private val FENCE by lazy { Regex("""^ {0,3}(```|~~~)""", RegexOption.MULTILINE) }
    // Bold/italic require non-space just inside the markers, so "2 * 3 * 4"
    // and "a_b_c" stay plain text.
    private val BOLD by lazy { Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""", RegexOption.DOT_MATCHES_ALL) }
    /** Shared with [AiMarkdownBlocks], which reads emphasis the same way: one compiled copy, 21 KB of ICU sets. */
    internal val ITALIC by lazy { Regex("""(?<![\w*_])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![\w*_])""") }
    private val CODE by lazy { Regex("""`([^`\n]+)`""") }
    private val LINK by lazy { Regex("""\[([^\]\n]*)]\((\S*?)\)""") }

    private val HEADING_PREFIX by lazy { Regex("""^ {0,3}#{1,6} +""") }
    private val HEADING_SUFFIX by lazy { Regex(""" +#+ *$""") }
    private val QUOTE_PREFIX by lazy { Regex("""^ {0,3}(> ?)+""") }
    private val BULLET_PREFIX by lazy { Regex("""^( *)[-*+] +""") }
    private val FENCE_LINE by lazy { Regex("""^ {0,3}(```|~~~).*$""") }
    private val RULE_LINE by lazy { Regex("""^ {0,3}([-*_])( *\1){2,} *$""") }
    private val TABLE_ROW by lazy { Regex("""^ {0,3}\|.*\| *$""") }
    private val TABLE_DIVIDER by lazy { Regex("""^ {0,3}\|? *:?-{3,}:? *(\| *:?-{3,}:? *)*\|? *$""") }
    private val TABLE by lazy { Regex("""^ {0,3}\|.*\| *\n {0,3}\|? *:?-{3,}:? *\|""", RegexOption.MULTILINE) }

    /** A table row's cells, the bars at its ends taken off; a `\|` inside a cell stays a bar. */
    private fun tableCells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split(Regex("""(?<!\\)\|"""))
            .map { it.replace("\\|", "|").trim() }

    /** True when [text] contains syntax worth stripping. */
    fun hasMarkdown(text: String): Boolean =
        HEADING.containsMatchIn(text) ||
            BULLET.containsMatchIn(text) ||
            QUOTE.containsMatchIn(text) ||
            FENCE.containsMatchIn(text) ||
            BOLD.containsMatchIn(text) ||
            ITALIC.containsMatchIn(text) ||
            CODE.containsMatchIn(text) ||
            LINK.containsMatchIn(text) ||
            TABLE.containsMatchIn(text) ||
            LatexMath.contains(text)

    /**
     * Markdown markers removed, content kept. Fenced code keeps its lines but
     * loses the fences, bullets become "• ", and a link becomes its text with
     * the URL in parentheses (dropping the URL entirely would lose the only
     * part that can't be retyped). LaTeX maths (#811) is written out in
     * Unicode, `$x^2$` as `x²`, since a field draws no formula either. A
     * table keeps one line per row, its cells between bars, and loses the
     * dashes under its header.
     */
    fun strip(text: String): String {
        val out = StringBuilder()
        var inFence = false
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            if (FENCE_LINE.matches(line)) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                out.appendLine(line)
                continue
            }
            val math = LatexMath.display(lines, i - 1)
            if (math != null) {
                out.appendLine(LatexMath.plain(math.latex, lines = true))
                i = math.end
                continue
            }
            if (RULE_LINE.matches(line)) continue
            // A table's row keeps its cells on one line, between bars, and the
            // dashes under its header go: a field draws no grid either.
            if (TABLE_DIVIDER.matches(line)) continue
            if (TABLE_ROW.matches(line)) {
                out.appendLine(tableCells(line).joinToString(" | ") { stripInline(it) })
                continue
            }
            // A trailing "###" is only a marker on a line that opened with one.
            val heading = HEADING_PREFIX.containsMatchIn(line)
            var l = line.replace(HEADING_PREFIX, "")
            if (heading) l = l.replace(HEADING_SUFFIX, "")
            l = l.replace(QUOTE_PREFIX, "")
            l = BULLET_PREFIX.replace(l) { m -> m.groupValues[1] + "• " }
            out.appendLine(stripInline(l))
        }
        return out.toString().trim()
    }

    /** Maths is written out on its own, so the markdown rules never see a `*` or `_` inside it. */
    private fun stripInline(line: String): String {
        if ('$' !in line && '\\' !in line) return stripMarkers(line)
        val out = StringBuilder()
        var at = 0
        for (m in LatexMath.INLINE.findAll(line)) {
            out.append(stripMarkers(line.substring(at, m.range.first)))
            out.append(LatexMath.plain(LatexMath.source(m)))
            at = m.range.last + 1
        }
        out.append(stripMarkers(line.substring(at)))
        return out.toString()
    }

    private fun stripMarkers(line: String): String {
        var l = LINK.replace(line) { m ->
            val (label, url) = m.destructured
            when {
                label.isBlank() -> url
                url.isBlank() || url == label -> label
                else -> "$label ($url)"
            }
        }
        l = BOLD.replace(l) { it.groupValues[2] }
        l = ITALIC.replace(l) { it.groupValues[2] }
        l = CODE.replace(l) { it.groupValues[1] }
        return l
    }
}
