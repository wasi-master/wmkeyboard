package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.graphics.Typeface
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.tools.CodeHighlight
import java.io.File
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.ime.R

/**
 * The colours a chat answer is drawn in. Passed in rather than read off a
 * theme because two surfaces draw answers and they are painted by different
 * things: the keyboard's panel by the keyboard theme, the chat screen in the
 * settings app by Material.
 */
data class ChatMarkdownColors(
    val text: Color,
    /** Quotes, list markers and a code block's label. */
    val dim: Color,
    /** Behind inline code and code blocks. */
    val codeBackground: Color,
    /** Links. Unspecified draws them in [text], underlined. */
    val link: Color = Color.Unspecified,
)

/**
 * What a link the caller claims for itself looks like and does, for an
 * address no browser can open: the Settings assistant's `setting:` links.
 * A null [onTap] leaves the text inert; a null [style] draws it plain, which is
 * how a link to something that does not exist should read.
 */
data class ChatLink(val style: SpanStyle?, val onTap: (() -> Unit)?)

/**
 * A model's answer or a note, drawn: paragraphs, headings, lists, quotes,
 * tables, images, footnotes, highlighted code blocks and LaTeX formulas, with
 * bold, italic, strikethrough, inline code, links and inline maths inside them.
 * See [AiMarkdownBlocks] for what is read, and [MathTypesetter] for the maths.
 *
 * A link opens in a new task, since an input method has none of its own. An
 * image is not fetched until it is tapped: a note or an answer must not phone
 * an address home just by being read.
 *
 * [codeActions] fills the right of each code block's header. It is a slot and
 * not a pair of buttons because what a block can do differs by surface: the
 * keyboard can put the code in the field behind it, the app can only copy.
 */
@Composable
fun ChatMarkdown(
    text: String,
    colors: ChatMarkdownColors,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    /**
     * A task item's box was tapped (#560). Null leaves the boxes as drawings:
     * a chat answer's checklist is the model's, not something to tick off.
     */
    onTaskToggle: ((Block.Task) -> Unit)? = null,
    /**
     * A block tapped (#560): the notes read view opens its editor at that
     * block's line. Null leaves the text inert, as a chat answer is.
     */
    onBlockTap: ((Block) -> Unit)? = null,
    /**
     * A long press on a paragraph, heading, list item, quote or footnote
     * selects a word, with handles and the keyboard's Copy, Insert and Ask AI
     * bar, as in Wikipedia (#352). Keyboard only: outside it there is no
     * selection overlay to draw in, and the text stays as it is. [ask] names
     * the text for Ask AI.
     */
    selectable: Boolean = false,
    ask: AskAiSource? = null,
    codeActions: (@Composable RowScope.(Block.Code) -> Unit)? = null,
    /**
     * Claims a link's address before the usual rules see it. Returns null for
     * an address that is not the caller's, which then opens as any link does.
     */
    customLink: ((String) -> ChatLink?)? = null,
) {
    val canSelect = selectable && LocalSelectionOverlay.current != null
    val blocks = remember(text) { AiMarkdownBlocks.parse(text) }
    val lineHeight = fontSize * LINE_HEIGHT
    val context = LocalContext.current
    val onLink: (String) -> Unit = remember(context) { { url -> openToolLink(context, url) } }
    // Most answers hold no maths; they skip the typesetter altogether.
    val hasMath = remember(blocks) { blocks.any { it is Block.Math } || '$' in text || "\\(" in text }
    val math = if (hasMath) rememberMathScope(fontSize) else null
    fun md(inline: AiMarkdownBlocks.Inline) = annotated(inline, colors, onLink, customLink, math != null)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            val tappable = if (onBlockTap != null && block !is Block.Task) {
                Modifier.fillMaxWidth().clickable { onBlockTap(block) }
            } else {
                Modifier
            }
            val tap = onBlockTap?.let { { it(block) } }
            Box(tappable) {
            when (block) {
                is Block.Paragraph -> BlockText(
                    md(block.inline),
                    color = colors.text,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    selectable = canSelect,
                    ask = ask,
                    onTap = tap,
                    math = math,
                )
                is Block.Heading -> BlockText(
                    md(block.inline),
                    // h6 is the one level below body text, so it steps back in colour.
                    color = if (block.level >= 6) colors.dim else colors.text,
                    fontSize = fontSize * HEADING_SCALE[(block.level - 1).coerceIn(0, HEADING_SCALE.lastIndex)],
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = lineHeight * 1.12f,
                    selectable = canSelect,
                    ask = ask,
                    onTap = tap,
                    math = math,
                )
                is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 12).dp)) {
                    Text(block.marker, color = colors.dim, fontSize = fontSize, lineHeight = lineHeight)
                    Spacer(Modifier.width(6.dp))
                    BlockText(
                        md(block.inline),
                        color = colors.text,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        selectable = canSelect,
                        ask = ask,
                        onTap = tap,
                        math = math,
                    )
                }
                is Block.Task -> TaskRow(block, colors, fontSize, lineHeight, onTaskToggle, onLink, customLink, math)
                // One bar per level of nesting, each as tall as the whole quote.
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(block.depth) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(colors.dim),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    BlockText(
                        md(block.inline),
                        color = colors.dim,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        selectable = canSelect,
                        ask = ask,
                        onTap = tap,
                        math = math,
                    )
                }
                is Block.Code -> CodeBlock(block, colors, fontSize, codeActions)
                is Block.Math -> if (math != null && block.latex.isNotBlank()) MathBlock(block.latex, colors.text, math)
                is Block.Table -> TableBlock(block, colors, fontSize, lineHeight, onLink, customLink, math)
                is Block.Image -> ImageBlock(block, colors, fontSize, lineHeight, onLink)
                is Block.Footnote -> Row {
                    Text("${block.number}.", color = colors.dim, fontSize = fontSize * 0.85f, lineHeight = lineHeight)
                    Spacer(Modifier.width(6.dp))
                    BlockText(md(block.inline), colors.dim, fontSize * 0.85f, lineHeight, selectable = canSelect, ask = ask, onTap = tap, math = math)
                }
                is Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .height(1.dp)
                        .background(colors.dim.copy(alpha = colors.dim.alpha * 0.5f)),
                )
            }
            }
        }
    }
}

/** One block's text: [SelectableText] when [selectable], else plain. */
@Composable
private fun BlockText(
    text: Md,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight? = null,
    selectable: Boolean,
    ask: AskAiSource?,
    onTap: (() -> Unit)?,
    math: MathScope?,
) {
    val inline = text.inlineContent(math, color)
    val lines = text.lineHeight(math, fontSize, lineHeight)
    if (selectable) {
        SelectableText(text.text, color, fontSize, lineHeight = lines, fontWeight = fontWeight, ask = ask, onTap = onTap, inlineContent = inline)
    } else {
        Text(text.text, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lines, inlineContent = inline)
    }
}

/** A run of text built for a `Text`, and the LaTeX of the formulas sitting in its placeholders. */
private class Md(val text: AnnotatedString, val formulas: List<String>)

@Composable
private fun Md.inlineContent(math: MathScope?, color: Color): Map<String, InlineTextContent> =
    if (math == null || formulas.isEmpty()) emptyMap() else remember(formulas, math, color) { math.inlineContent(formulas, color) }

/**
 * [lineHeight], or none when a formula in this run is taller than a line:
 * a fixed line height holds every line to it, placeholders included, and the
 * formula would draw into the line above. Only that paragraph loosens.
 */
private fun Md.lineHeight(math: MathScope?, fontSize: TextUnit, lineHeight: TextUnit): TextUnit {
    if (math == null || formulas.isEmpty() || !lineHeight.isSp || !fontSize.isSp) return lineHeight
    return if (math.inlineHeightEm(formulas) > lineHeight.value / fontSize.value) TextUnit.Unspecified else lineHeight
}

/**
 * A task item: a box, then its text struck through once ticked. Tappable only
 * when the caller handed over [onToggle]; the box is then a checkbox to
 * TalkBack, and the whole row is the target rather than the 18 dp glyph.
 */
@Composable
private fun TaskRow(
    block: Block.Task,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onToggle: ((Block.Task) -> Unit)?,
    onLink: (String) -> Unit,
    customLink: ((String) -> ChatLink?)?,
    math: MathScope?,
) {
    val toggleable = if (onToggle != null) {
        Modifier.toggleable(
            value = block.checked,
            role = Role.Checkbox,
            onValueChange = { onToggle(block) },
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.indent * 12).dp)
            .clip(RoundedCornerShape(6.dp))
            .then(toggleable)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (block.checked) colors.dim else colors.text,
            modifier = Modifier
                .padding(top = with(androidx.compose.ui.platform.LocalDensity.current) { ((lineHeight.toDp() - 18.dp) / 2).coerceAtLeast(0.dp) })
                .size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        val md = annotated(block.inline, colors, onLink, customLink, math != null)
        val color = if (block.checked) colors.dim else colors.text
        Text(
            md.text,
            inlineContent = md.inlineContent(math, color),
            color = color,
            fontSize = fontSize,
            lineHeight = md.lineHeight(math, fontSize, lineHeight),
            textDecoration = if (block.checked) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
private fun CodeBlock(
    block: Block.Code,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    codeActions: (@Composable RowScope.(Block.Code) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBackground)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                block.language.ifEmpty { stringResource(R.string.ime_ai_chat_code_label) },
                color = colors.dim,
                fontSize = fontSize * 0.82f,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // Nothing to act on until the block has closed: half a function
            // is not something to paste.
            if (codeActions != null && block.closed) codeActions(block)
        }
        // Code does not wrap, so a long line scrolls rather than breaking where
        // the language never would.
        val dark = colors.text.luminance() > 0.5f
        val highlighted = remember(block.code, block.language, dark) { highlight(block.code, block.language, dark) }
        val scroll = rememberScrollState()
        Text(
            highlighted,
            color = colors.text,
            fontSize = fontSize * 0.92f,
            fontFamily = codeFontFamily(),
            lineHeight = fontSize * 1.3f,
            softWrap = false,
            modifier = Modifier
                .padding(top = 3.dp)
                .scrollHint(scroll, colors.text)
                .horizontalScroll(scroll)
                .padding(bottom = SCROLL_HINT_ROOM),
        )
    }
}

/**
 * A pipe table. Cells do not wrap, so every row is one line tall and a column
 * can be laid out on its own, as wide as its widest cell; a table wider than
 * the reader scrolls sideways, as a code block does.
 */
@Composable
private fun TableBlock(
    block: Block.Table,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onLink: (String) -> Unit,
    customLink: ((String) -> ChatLink?)?,
    math: MathScope?,
) {
    val border = colors.dim.copy(alpha = colors.dim.alpha * 0.4f)
    val scroll = rememberScrollState()
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                drawRect(border, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            .scrollHint(scroll, colors.text)
            .horizontalScroll(scroll),
    ) {
        for (column in block.header.indices) {
            val align = when (block.align[column]) {
                AiMarkdownBlocks.Align.START -> TextAlign.Start
                AiMarkdownBlocks.Align.CENTER -> TextAlign.Center
                AiMarkdownBlocks.Align.END -> TextAlign.End
            }
            Column(Modifier.width(IntrinsicSize.Max)) {
                val cells = listOf(block.header[column]) + block.rows.map { it[column] }
                cells.forEachIndexed { row, cell ->
                    val md = annotated(cell, colors, onLink, customLink, math != null)
                    Text(
                        md.text,
                        inlineContent = md.inlineContent(math, colors.text),
                        color = colors.text,
                        fontSize = fontSize,
                        lineHeight = md.lineHeight(math, fontSize, lineHeight),
                        fontWeight = if (row == 0) FontWeight.SemiBold else null,
                        textAlign = align,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (row == 0) Modifier.background(colors.codeBackground) else Modifier)
                            .drawBehind {
                                val w = 1.dp.toPx()
                                if (row < cells.lastIndex) drawLine(border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                                if (column > 0) drawLine(border, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * An image on a line of its own: its alt text until tapped, then the picture.
 * A tap on a loaded picture follows the link a linked image carries.
 */
@Composable
private fun ImageBlock(
    block: Block.Image,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onLink: (String) -> Unit,
) {
    val loadable = openable(block.url)
    val link = block.link?.takeIf(::openable)
    var load by rememberSaveable(block.url) { mutableStateOf(false) }
    if (load && loadable) {
        AsyncImage(
            model = block.url,
            imageLoader = rememberMediaImageLoader(),
            contentDescription = block.alt,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(if (link != null) Modifier.clickable { onLink(link) } else Modifier),
        )
        return
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.codeBackground)
            .then(if (loadable) Modifier.clickable { load = true } else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Image, contentDescription = null, tint = colors.dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            block.alt.ifBlank { block.url.substringAfterLast('/') },
            color = colors.text,
            fontSize = fontSize,
            lineHeight = lineHeight,
        )
    }
}

/** Only addresses a browser or a mail app can open are made tappable; a relative path goes nowhere. */
private fun openable(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:") || u.startsWith("ftp://")
}

/**
 * [inline] as styled text. With [drawMath], a formula that needs two
 * dimensions becomes a placeholder for [MathTypesetter] to fill, its plain text
 * kept as the placeholder's alternate text so selection and copy still read it.
 */
private fun annotated(
    inline: AiMarkdownBlocks.Inline,
    colors: ChatMarkdownColors,
    onLink: (String) -> Unit,
    customLink: ((String) -> ChatLink?)? = null,
    drawMath: Boolean = false,
): Md {
    val formulas = if (drawMath) {
        inline.spans.filter { it.style == AiMarkdownBlocks.Style.FORMULA && it.url != null }.sortedBy { it.start }
    } else {
        emptyList()
    }
    val text = buildAnnotatedString {
        if (formulas.isEmpty()) {
            append(inline.text)
        } else {
            // Each placeholder's alternate text is the same length as what it
            // stands for, so every other span's offsets still hold.
            var at = 0
            formulas.forEachIndexed { k, f ->
                if (f.start < at) return@forEachIndexed
                append(inline.text, at, f.start)
                appendInlineContent(formulaId(k), inline.text.substring(f.start, f.end))
                at = f.end
            }
            append(inline.text, at, inline.text.length)
        }
        for (span in inline.spans) {
            val style = when (span.style) {
                AiMarkdownBlocks.Style.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                AiMarkdownBlocks.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                AiMarkdownBlocks.Style.CODE -> SpanStyle(
                    fontFamily = codeFontFamily(),
                    background = colors.codeBackground,
                )
                AiMarkdownBlocks.Style.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                AiMarkdownBlocks.Style.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                AiMarkdownBlocks.Style.HIGHLIGHT -> SpanStyle(background = HIGHLIGHT)
                AiMarkdownBlocks.Style.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
                AiMarkdownBlocks.Style.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
                AiMarkdownBlocks.Style.MATH -> SpanStyle(fontFamily = FontFamily.Serif)
                AiMarkdownBlocks.Style.FORMULA -> continue
                AiMarkdownBlocks.Style.LINK -> {
                    val target = span.url
                    val claimed = target?.let { customLink?.invoke(it) }
                    if (target != null && claimed != null) {
                        val tap = claimed.onTap
                        if (tap != null) {
                            addLink(
                                LinkAnnotation.Clickable(target, TextLinkStyles(claimed.style)) { tap() },
                                span.start,
                                span.end,
                            )
                        } else if (claimed.style != null) {
                            addStyle(claimed.style, span.start, span.end)
                        }
                        continue
                    }
                    val look = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                    val url = span.url
                    if (url != null && openable(url)) {
                        addLink(
                            LinkAnnotation.Clickable(url, TextLinkStyles(look)) { onLink(url) },
                            span.start,
                            span.end,
                        )
                        continue
                    }
                    look
                }
            }
            addStyle(style, span.start, span.end)
        }
    }
    return Md(text, formulas.map { it.url.orEmpty() })
}

/**
 * Shows that a block scrolls sideways (#811): the edge with more beyond it
 * fades out, and a thin bar along the bottom gives its place and length.
 * Nothing at all while the content fits. Goes before the [horizontalScroll]
 * it describes, so it draws over the viewport rather than the content; it
 * reads the scroll in the draw phase only, so scrolling recomposes nothing.
 */
internal fun Modifier.scrollHint(state: ScrollState, color: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val w = size.width
        val fade = minOf(28.dp.toPx(), w / 4)
        if (state.value > 0) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.value < max) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = w - fade, endX = w),
                topLeft = Offset(w - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        val h = 3.dp.toPx()
        val y = size.height - h
        val thumb = maxOf(w * w / (w + max), 24.dp.toPx()).coerceAtMost(w)
        val x = (w - thumb) * state.value / max
        drawRoundRect(color.copy(alpha = color.alpha * 0.15f), Offset(0f, y), Size(w, h), CornerRadius(h / 2))
        drawRoundRect(color.copy(alpha = color.alpha * 0.55f), Offset(x, y), Size(thumb, h), CornerRadius(h / 2))
    }

/** Room under a scrolling block's content for [scrollHint]'s bar. */
internal val SCROLL_HINT_ROOM = 6.dp

/** A code block's text with its tokens coloured; [dark] is a light-on-dark reader. */
internal fun highlight(code: String, language: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    append(code)
    val palette = if (dark) SYNTAX_DARK else SYNTAX_LIGHT
    for (token in CodeHighlight.tokens(code, language)) {
        val colour = palette[token.kind.ordinal]
        addStyle(
            if (token.kind == CodeHighlight.Kind.COMMENT) SpanStyle(color = colour, fontStyle = FontStyle.Italic) else SpanStyle(color = colour),
            token.start,
            token.end,
        )
    }
}

/**
 * A monospace face that stays monospace. Some skins (ColorOS among them) point
 * the `monospace` family at their own proportional system font when a font
 * theme is on, so code would lose its columns; the file underneath is still
 * there, so it is loaded directly when it can be.
 */
internal fun codeFontFamily(): FontFamily = CODE_FONT

private val CODE_FONT: FontFamily by lazy {
    runCatching {
        val file = File("/system/fonts/DroidSansMono.ttf")
        if (file.canRead()) FontFamily(Typeface.createFromFile(file)) else null
    }.getOrNull() ?: FontFamily.Monospace
}

private val HEADING_SCALE = floatArrayOf(1.3f, 1.18f, 1.08f, 1f, 0.95f, 0.9f)

private val HIGHLIGHT = Color(0x66FFD54F)

/** Indexed by [CodeHighlight.Kind]: comment, string, number, keyword, type. */
private val SYNTAX_DARK = arrayOf(Color(0xFF8B949E), Color(0xFFA5D6FF), Color(0xFF79C0FF), Color(0xFFFF7B72), Color(0xFFD2A8FF))
private val SYNTAX_LIGHT = arrayOf(Color(0xFF6E7781), Color(0xFF0A3069), Color(0xFF0550AE), Color(0xFFCF222E), Color(0xFF8250DF))

private const val LINE_HEIGHT = 1.38f
