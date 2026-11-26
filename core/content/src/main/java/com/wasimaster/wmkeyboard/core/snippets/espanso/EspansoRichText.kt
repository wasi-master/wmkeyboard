package com.wasimaster.wmkeyboard.core.snippets.espanso

/**
 * Espanso's two rich-text bodies, flattened to plain text.
 *
 * A snippet here goes into the field through `InputConnection.commitText`, and
 * what reaches the other side is characters. So a `markdown` or `html` match
 * keeps its words and loses its formatting, and the importer says so.
 *
 * Neither of these is a real parser and neither needs to be. The input is a
 * short snippet somebody wrote to paste a link or a bulleted list, not a
 * document, and the failure mode of getting it slightly wrong is a stray
 * asterisk rather than anything worse.
 */
internal object EspansoRichText {

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 21 KB across this file's patterns.
    private val TAG by lazy { Regex("""<[^>\n]{0,200}>""") }
    private val BLOCK_END by lazy { Regex("""(?i)</(p|div|li|tr|h[1-6]|blockquote)\s*>""") }
    private val LINE_BREAK by lazy { Regex("""(?i)<br\s*/?>""") }
    private val LIST_ITEM by lazy { Regex("""(?i)<li[^>]{0,200}>""") }

    /** HTML with its tags removed and its entities spelled out. */
    fun fromHtml(html: String): String {
        var out = html
        out = LINE_BREAK.replace(out, "\n")
        out = LIST_ITEM.replace(out, "\n- ")
        out = BLOCK_END.replace(out, "\n")
        out = TAG.replace(out, "")
        return unescape(out).trimEnd().trimStart('\n')
    }

    /**
     * Markdown with the marks that only mean formatting taken off.
     *
     * Headings, emphasis and inline code go; list bullets, block quotes and link
     * text stay, because those carry meaning a reader would miss. A link becomes
     * "text (url)", which is what a plain-text field can show.
     */
    fun fromMarkdown(markdown: String): String {
        val out = StringBuilder(markdown.length)
        for ((index, line) in markdown.lines().withIndex()) {
            if (index > 0) out.append('\n')
            out.append(flattenLine(line))
        }
        return out.toString()
    }

    private val HEADING by lazy { Regex("""^\s{0,3}#{1,6}\s+""") }
    private val LINK by lazy { Regex("""\[([^\]\n]{0,200})\]\(([^)\s]{0,400})[^)]{0,100}\)""") }
    private val EMPHASIS by lazy { Regex("""(\*{1,3}|_{1,3}|`{1,3}|~~)(.+?)\1""") }

    private fun flattenLine(line: String): String {
        var out = HEADING.replace(line, "")
        out = LINK.replace(out) { match ->
            val text = match.groupValues[1]
            val url = match.groupValues[2]
            when {
                text.isEmpty() -> url
                url.isEmpty() -> text
                else -> "$text ($url)"
            }
        }
        // Twice, so `**bold _and_ italic**` loses both layers. A third pass buys
        // nothing anybody writes in a snippet.
        out = EMPHASIS.replace(out) { it.groupValues[2] }
        out = EMPHASIS.replace(out) { it.groupValues[2] }
        return out
    }

    /** The five entities XML defines, plus the non-breaking space. */
    private fun unescape(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            // Last, so an escaped ampersand cannot re-open one of the above.
            .replace("&amp;", "&")
    }
}
