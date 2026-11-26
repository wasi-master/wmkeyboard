package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.notes.rich.NoteCss
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMark
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.LineKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichPalette

/**
 * Drawing a note's rich text (#1586): the colours, fonts and sizes its marks
 * name, the underline styles Compose has no decoration for, and the whole
 * styled source the editors show ([NoteRichStyle.annotate]).
 *
 * Shared by the keyboard's editor, the app's editor and the reader
 * ([ChatMarkdown]), so a note looks the same in all three.
 */
object NoteRichStyle {

    /** A CSS colour as a Compose colour, or null when it is not one this reads. */
    fun color(value: String?): Color? = NoteCss.argb(value)?.let { Color(it) }

    /** A highlight's colour, with the page showing through so the text on it reads on any theme. */
    fun highlight(value: String?): Color =
        (color(value) ?: color(NoteRichPalette.DEFAULT_HIGHLIGHT)!!).copy(alpha = HIGHLIGHT_ALPHA)

    /** A CSS generic family as a font; null for one this does not know. */
    fun font(value: String?): FontFamily? = when (value?.trim()?.lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace", "mono" -> codeFontFamily()
        "cursive", "handwriting" -> FontFamily.Cursive
        "sans-serif", "sans" -> FontFamily.SansSerif
        else -> null
    }

    /** A CSS size as a relative size, or null. */
    fun size(value: String?): TextUnit? = NoteCss.scale(value)?.takeIf { it in 0.3f..6f }?.em

    /** The span style a mark draws with; null for one drawn some other way (a styled underline). */
    fun spanStyle(mark: NoteMark, link: Color, code: Color): SpanStyle? = when (mark.kind) {
        NoteMarkKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        NoteMarkKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        NoteMarkKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        NoteMarkKind.CODE -> SpanStyle(fontFamily = codeFontFamily(), background = code)
        NoteMarkKind.UNDERLINE -> if (mark.value == null) SpanStyle(textDecoration = TextDecoration.Underline) else null
        NoteMarkKind.COLOR -> color(mark.value)?.let { SpanStyle(color = it) }
        NoteMarkKind.HIGHLIGHT -> SpanStyle(background = highlight(mark.value))
        NoteMarkKind.FONT -> font(mark.value)?.let { SpanStyle(fontFamily = it) }
        NoteMarkKind.SIZE -> size(mark.value)?.let { SpanStyle(fontSize = it) }
        NoteMarkKind.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
    }

    /** Where the editors draw a styled underline: `[start, end)` in the source, its style, and its colour if a mark gives one. */
    data class Underline(val start: Int, val end: Int, val style: String, val color: Color?)

    /** One line's decoration in the editor's gutter. */
    data class Gutter(val line: NoteMarkup.Line, val number: String? = null)

    /** A note's source as an editor shows it: [text] styled, and what is drawn around the text. */
    class Styled(
        val text: AnnotatedString,
        val read: NoteMarkup.Read,
        val underlines: List<Underline>,
        val gutters: List<Gutter>,
    )

    /**
     * [source] styled for an editor: markers at no width and no colour,
     * headings large, quotes dimmed, list lines indented with room for the
     * bullet or box the editor draws in the gutter, a divider's characters
     * hidden over the rule the editor draws, a table and code in the code
     * face. [imageLine] says how tall a row of pictures is, as a placeholder
     * id the caller fills; null leaves the line's text showing.
     */
    fun annotate(
        source: String,
        fontSize: TextUnit,
        text: Color,
        dim: Color,
        link: Color,
        code: Color,
        imagePlaceholder: ((NoteMarkup.Line) -> String?)? = null,
    ): Styled {
        val read = NoteMarkup.read(source)
        val underlines = ArrayList<Underline>()
        val gutters = ArrayList<Gutter>()
        val annotated = buildAnnotatedString {
            append(source)
            for (line in read.lines) {
                val paraEnd = line.end
                when (line.kind) {
                    LineKind.HEADING -> {
                        val scale = HEADING_SCALE[(line.level - 1).coerceIn(0, HEADING_SCALE.lastIndex)]
                        addStyle(SpanStyle(fontSize = fontSize * scale, fontWeight = FontWeight.SemiBold), line.start, paraEnd)
                    }
                    LineKind.BULLET, LineKind.TODO, LineKind.NUMBERED -> {
                        // The hidden prefix keeps a width: the gutter the bullet or box is drawn in.
                        if (line.kind != LineKind.NUMBERED) addStyle(gutterStyle(line.prefixLength, GUTTER_EM * (line.indent + 1)), line.start, line.contentStart)
                        if (line.kind == LineKind.TODO && line.checked) {
                            addStyle(SpanStyle(color = dim, textDecoration = TextDecoration.LineThrough), line.contentStart, paraEnd)
                        }
                        if (line.kind == LineKind.NUMBERED) addStyle(SpanStyle(color = dim), line.start, line.contentStart)
                        gutters += Gutter(line)
                    }
                    LineKind.QUOTE -> {
                        addStyle(gutterStyle(line.prefixLength, QUOTE_GUTTER_EM), line.start, line.contentStart)
                        addStyle(SpanStyle(color = dim, fontStyle = FontStyle.Italic), line.contentStart, paraEnd)
                        gutters += Gutter(line)
                    }
                    LineKind.DIVIDER -> gutters += Gutter(line)
                    LineKind.TABLE, LineKind.CODE -> addStyle(SpanStyle(fontFamily = codeFontFamily(), fontSize = fontSize * 0.92f, color = dim), line.start, paraEnd)
                    LineKind.IMAGES -> {
                        val id = imagePlaceholder?.invoke(line)
                        if (id != null && paraEnd > line.start) {
                            addStringAnnotation(INLINE_CONTENT_TAG, id, line.start, paraEnd)
                        } else {
                            addStyle(SpanStyle(color = dim), line.start, paraEnd)
                        }
                    }
                    LineKind.PARAGRAPH -> Unit
                }
            }
            for (r in read.marks) {
                val style = spanStyle(r.mark, link, code)
                if (style != null) addStyle(style, r.start, r.end)
                if (r.mark.kind == NoteMarkKind.UNDERLINE && r.mark.value != null) {
                    val markColor = read.marks.firstOrNull { it.mark.kind == NoteMarkKind.COLOR && it.start <= r.start && it.end >= r.end }
                    underlines += Underline(r.start, r.end, r.mark.value!!, markColor?.let { color(it.mark.value) })
                }
            }
            val widePrefixes = read.lines.filter { it.kind == LineKind.BULLET || it.kind == LineKind.TODO || it.kind == LineKind.QUOTE }
                .mapTo(HashSet()) { it.start }
            for (h in read.hidden) {
                if (h.end <= h.start) continue
                if (h.role == NoteMarkup.Role.PREFIX && h.start in widePrefixes) continue
                addStyle(HIDDEN, h.start, h.end)
            }
        }
        return Styled(annotated, read, underlines, gutters)
    }

    /**
     * Invisible characters [count] wide that take up [widthEm] together: the
     * code face, whose every letter is 0.6 em, spaced to the width. Used for a
     * list's hidden prefix, so the text starts past the gutter the bullet is
     * drawn in, and so a caret before the text stands where the text starts.
     */
    private fun gutterStyle(count: Int, widthEm: Float): SpanStyle {
        val n = count.coerceAtLeast(1)
        return SpanStyle(
            fontFamily = codeFontFamily(),
            color = Color.Transparent,
            letterSpacing = (widthEm / n - MONO_ADVANCE_EM).em,
        )
    }

    private const val MONO_ADVANCE_EM = 0.6f
    private const val QUOTE_GUTTER_EM = 0.9f

    /** The tag Compose's inline content goes by; the same one `appendInlineContent` writes. */
    const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    /** Markers: no width, no ink. */
    private val HIDDEN = SpanStyle(fontSize = 0.sp, color = Color.Transparent, letterSpacing = 0.sp)

    /** The gutter a list line is indented by, in ems of the body size. */
    const val GUTTER_EM = 1.5f

    private val HEADING_SCALE = floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 0.95f)

    private const val HIGHLIGHT_ALPHA = 0.45f

    /**
     * The bullets, task boxes, quote bars and divider rules of [styled], in
     * the gutters its prefixes leave, and its styled underlines. [layout]
     * must be of [styled]'s text.
     */
    fun DrawScope.drawDecorations(styled: Styled, layout: TextLayoutResult, ink: Color, dim: Color, accent: Color) {
        val length = layout.layoutInput.text.length
        for (g in styled.gutters) {
            val line = g.line
            if (line.start > length) continue
            val visual = layout.getLineForOffset(line.start.coerceAtMost(length))
            val top = layout.getLineTop(visual)
            val bottom = layout.getLineBottom(visual)
            val mid = (top + bottom) / 2
            when (line.kind) {
                LineKind.DIVIDER -> drawLine(
                    dim.copy(alpha = dim.alpha * 0.6f),
                    Offset(0f, mid),
                    Offset(size.width, mid),
                    strokeWidth = 1.dp.toPx(),
                )
                LineKind.QUOTE -> {
                    val lastVisual = layout.getLineForOffset(line.end.coerceAtMost(length))
                    val x = layout.getHorizontalPosition(line.start.coerceAtMost(length), true) + 2.dp.toPx()
                    drawLine(dim, Offset(x, top + 2.dp.toPx()), Offset(x, layout.getLineBottom(lastVisual) - 2.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                LineKind.BULLET, LineKind.TODO -> {
                    val left = layout.getHorizontalPosition(line.start.coerceAtMost(length), true)
                    val right = layout.getHorizontalPosition(line.contentStart.coerceAtMost(length), true)
                    val unit = (right - left) / (line.indent + 1)
                    val cx = right - unit / 2
                    if (line.kind == LineKind.BULLET) {
                        drawCircle(ink, radius = 2.5.dp.toPx(), center = Offset(cx, mid))
                    } else {
                        val box = minOf(16.dp.toPx(), bottom - top - 2.dp.toPx())
                        val tl = Offset(cx - box / 2, mid - box / 2)
                        if (line.checked) {
                            drawRoundRect(accent, topLeft = tl, size = androidx.compose.ui.geometry.Size(box, box), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                            val tick = Path().apply {
                                moveTo(tl.x + box * 0.22f, tl.y + box * 0.52f)
                                lineTo(tl.x + box * 0.42f, tl.y + box * 0.72f)
                                lineTo(tl.x + box * 0.78f, tl.y + box * 0.3f)
                            }
                            drawPath(tick, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        } else {
                            drawRoundRect(
                                ink.copy(alpha = ink.alpha * 0.7f), topLeft = tl, size = androidx.compose.ui.geometry.Size(box, box),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
        for (u in styled.underlines) drawStyledUnderline(layout, u.start, u.end, u.style, u.color ?: ink)
    }

    /** The task line whose box is under [position], as its index into [Styled.read]'s lines, or null. */
    fun taskAt(styled: Styled, layout: TextLayoutResult, position: Offset): Int? {
        val length = layout.layoutInput.text.length
        for ((index, line) in styled.read.lines.withIndex()) {
            if (line.kind != LineKind.TODO || line.start > length) continue
            val visual = layout.getLineForOffset(line.start)
            if (position.y < layout.getLineTop(visual) || position.y > layout.getLineBottom(visual)) continue
            val left = layout.getHorizontalPosition(line.start, true)
            val right = layout.getHorizontalPosition(line.contentStart.coerceAtMost(length), true)
            if (position.x >= minOf(left, right) - 8f && position.x <= maxOf(left, right) + 4f) return index
        }
        return null
    }

    /**
     * A styled underline under `[start, end)` of [layout]'s text: a wave, dots,
     * dashes or two lines, one stroke per line of text the range wraps over.
     */
    fun DrawScope.drawStyledUnderline(layout: TextLayoutResult, start: Int, end: Int, style: String, color: Color) {
        val length = layout.layoutInput.text.length
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        if (e <= s) return
        val firstLine = layout.getLineForOffset(s)
        val lastLine = layout.getLineForOffset((e - 1).coerceAtLeast(s))
        val stroke = 1.dp.toPx()
        for (line in firstLine..lastLine) {
            val from = maxOf(s, layout.getLineStart(line))
            val to = minOf(e, layout.getLineEnd(line, visibleEnd = true))
            if (to <= from) continue
            val x1 = layout.getHorizontalPosition(from, usePrimaryDirection = true)
            val x2 = layout.getHorizontalPosition(to, usePrimaryDirection = true)
            val left = minOf(x1, x2)
            val right = maxOf(x1, x2)
            if (right - left < 1f) continue
            val y = layout.getLineBaseline(line) + 2.5.dp.toPx()
            when (style) {
                "wavy" -> {
                    val amp = 1.5.dp.toPx()
                    val wave = 4.dp.toPx()
                    val path = Path().apply {
                        moveTo(left, y)
                        var x = left
                        var up = true
                        while (x < right) {
                            val nx = minOf(x + wave / 2, right)
                            quadraticTo((x + nx) / 2, if (up) y - amp else y + amp, nx, y)
                            x = nx
                            up = !up
                        }
                    }
                    drawPath(path, color, style = Stroke(width = stroke))
                }
                "dotted" -> drawLine(
                    color, Offset(left, y), Offset(right, y), strokeWidth = stroke * 1.4f, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 3.dp.toPx())),
                )
                "dashed" -> drawLine(
                    color, Offset(left, y), Offset(right, y), strokeWidth = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())),
                )
                "double" -> {
                    drawLine(color, Offset(left, y - stroke), Offset(right, y - stroke), strokeWidth = stroke)
                    drawLine(color, Offset(left, y + stroke * 1.5f), Offset(right, y + stroke * 1.5f), strokeWidth = stroke)
                }
                else -> drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = stroke)
            }
        }
    }
}
