package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.wasimaster.wmkeyboard.core.tools.CodeSniff

/*
 * A clip that is code or Markdown, drawn as one (see CodeSniff): code in a
 * monospace face, coloured when its language is known; Markdown formatted.
 * The card shows a few lines of it as one Text, so a clip costs the same to
 * lay out whatever it holds; the full-text popup draws Markdown whole with
 * ChatMarkdown and can switch to the source.
 */

/** Past this length a Markdown clip's popup shows its source only: ChatMarkdown lays the whole of it out. */
internal const val ClipMarkdownRenderMax = 60_000

/** [text] as a code clip's card or popup draws it: coloured when [language] is known, plain otherwise. */
internal fun clipCodeText(text: String, language: String?, dark: Boolean): AnnotatedString =
    if (language == null) AnnotatedString(text) else highlight(text, language, dark)

/** What a code or Markdown clip is called over its text in the popup. */
internal fun clipShapeName(shape: CodeSniff.Shape, markdown: String, code: String): String = when (shape) {
    CodeSniff.Markdown -> markdown
    is CodeSniff.Code -> shape.language?.let(CodeSniff::displayName) ?: code
}

/**
 * Markdown flattened to one styled string for a clip card: the marks go and
 * what they mean stays. Headings bold, emphasis set, code in [codeFont] on
 * [codeBackground], list markers as bullets, task boxes as ☐ and ☑, quotes in
 * [dim] italics, a link's text in [accent], table rules dropped and cells
 * spaced apart. Nothing is clickable: a tap on the card pastes the clip.
 */
internal fun markdownPreview(
    md: String,
    accent: Color,
    dim: Color,
    codeBackground: Color,
    codeFont: FontFamily = FontFamily.Monospace,
): AnnotatedString = buildAnnotatedString {
    val code = SpanStyle(fontFamily = codeFont, background = codeBackground)
    var fence = false
    var started = false
    for (raw in md.lineSequence()) {
        if (raw.trimStart().startsWith("```")) {
            fence = !fence
            continue
        }
        if (!fence && MdTableRule.matches(raw)) continue
        if (started) append('\n')
        started = true
        if (fence) {
            withStyle(code) { append(raw) }
            continue
        }
        val heading = MdHeading.matchEntire(raw)
        val task = MdTask.matchEntire(raw)
        val bullet = MdBullet.matchEntire(raw)
        val quote = MdQuote.matchEntire(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(heading.groupValues[1], accent, dim, code)
            }
            task != null -> {
                append(task.groupValues[1])
                append(if (task.groupValues[2].isBlank()) "☐ " else "☑ ")
                appendInline(task.groupValues[3], accent, dim, code)
            }
            bullet != null -> {
                append(bullet.groupValues[1])
                append("• ")
                appendInline(bullet.groupValues[2], accent, dim, code)
            }
            quote != null -> withStyle(SpanStyle(color = dim, fontStyle = FontStyle.Italic)) {
                appendInline(quote.groupValues[1], accent, dim, code)
            }
            raw.trimStart().startsWith('|') ->
                appendInline(raw.trim().trim('|').split('|').joinToString("   ") { it.trim() }, accent, dim, code)
            else -> appendInline(raw, accent, dim, code)
        }
    }
}

/** One line's inline marks: images, links, code spans, bold, strikethrough, italics. */
private fun AnnotatedString.Builder.appendInline(line: String, accent: Color, dim: Color, code: SpanStyle) {
    var at = 0
    for (m in MdInline.findAll(line)) {
        append(line, at, m.range.first)
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(color = dim)) { append(g[1]!!.value.ifEmpty { "🖼" }) }
            g[2] != null -> withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[2]!!.value) }
            g[3] != null -> withStyle(code) { append(g[3]!!.value) }
            g[5] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[5]!!.value) }
            g[6] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]!!.value) }
            g[7] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7]!!.value) }
            g[8] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8]!!.value) }
            else -> append(m.value)
        }
        at = m.range.last + 1
    }
    append(line, at, line.length)
}

private val MdHeading = Regex("""^#{1,6} (.*)$""")
private val MdTask = Regex("""^(\s*)[-*+] \[([ xX])\] (.*)$""")
private val MdBullet = Regex("""^(\s*)[-*+] (.*)$""")
private val MdQuote = Regex("""^> ?(.*)$""")
private val MdTableRule = Regex("""^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$""")

/** Groups: 1 image alt, 2 link text, 3 code, 4 bold mark, 5 bold, 6 strikethrough, 7 and 8 italics. */
private val MdInline = Regex(
    """!\[([^\]\n]*)\]\([^)\n]*\)""" +
        """|\[([^\]\n]+)\]\([^)\n]*\)""" +
        """|`([^`\n]+)`""" +
        """|(\*\*|__)(?=\S)(.+?)(?<=\S)\4""" +
        """|~~(?=\S)(.+?)(?<=\S)~~""" +
        """|(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?!\*)""" +
        """|(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""",
)
