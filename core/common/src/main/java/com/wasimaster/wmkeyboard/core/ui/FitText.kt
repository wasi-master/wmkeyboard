package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/**
 * How a one-line label is drawn in less width than it wants.
 *
 * [scaleX] condenses the glyphs horizontally; [fontScale] multiplies the font
 * size on top of that. `TextFit(1f, 1f)` means it fits as it is.
 */
data class TextFit(val fontScale: Float, val scaleX: Float) {
    /** True once the glyphs are squeezed, not merely shrunk. */
    val condensed: Boolean get() = scaleX < 1f

    companion object {
        val NONE = TextFit(1f, 1f)
    }
}

/**
 * How far a label may be squeezed horizontally. At 0.7 a word still reads
 * easily, about as narrow as a condensed typeface; much below that the round
 * letters turn into ovals.
 */
const val MIN_TEXT_SCALE_X = 0.7f

/** How small the font may go, as a fraction of its size: 16sp down to about 11sp. */
const val MIN_TEXT_FONT_SCALE = 0.68f

/** What a word too long for its space does before it is cut short with "…". */
enum class LongWordFit {
    /** The letters narrow first, then the text gets smaller. Gboard's way, and the default. */
    NARROW_THEN_SHRINK,

    /** The text gets smaller first, then the letters narrow. */
    SHRINK_THEN_NARROW,

    /** Only the letters narrow; the size stays. */
    NARROW_ONLY,

    /** Only the size drops; the letters keep their width. */
    SHRINK_ONLY,

    /** Neither: the word is cut short with "…" straight away. */
    CUT_OFF,
}

/**
 * How labels are fitted: [mode], and how far each step may go. [minScaleX] is
 * the narrowest the letters may get as a share of their width, [minFontScale]
 * the smallest the text may get as a share of its size.
 */
data class TextFitting(
    val mode: LongWordFit = LongWordFit.NARROW_THEN_SHRINK,
    val minScaleX: Float = MIN_TEXT_SCALE_X,
    val minFontScale: Float = MIN_TEXT_FONT_SCALE,
) {
    companion object {
        val DEFAULT = TextFitting()
    }
}

/**
 * The fitting [FitText] uses unless told otherwise. The keyboard provides the
 * user's choice (Appearance › Toolbar › Long words) around its panels, so
 * plugin buttons fit their labels the way the suggestion strip fits words.
 */
val LocalTextFitting = compositionLocalOf { TextFitting.DEFAULT }

/**
 * How to fit a label of [measuredWidthPx] into [availableWidthPx] under
 * [fitting]. Text width is near enough linear in both the letter width and the
 * font size, so the ratio of the two widths is the scale needed, and the first
 * step takes all of it it may before the second takes the rest. With both
 * steps at their defaults a label about twice the width still fits; past what
 * the steps allow, the caller's ellipsis takes over.
 */
fun fitTextWidth(
    measuredWidthPx: Float,
    availableWidthPx: Float,
    fitting: TextFitting = TextFitting.DEFAULT,
): TextFit {
    if (availableWidthPx <= 0f || measuredWidthPx <= 0f || measuredWidthPx <= availableWidthPx) return TextFit.NONE
    val needed = availableWidthPx / measuredWidthPx
    val narrowest = fitting.minScaleX.coerceIn(0f, 1f)
    val smallest = fitting.minFontScale.coerceIn(0f, 1f)
    return when (fitting.mode) {
        LongWordFit.NARROW_THEN_SHRINK -> {
            val scaleX = needed.coerceIn(narrowest, 1f)
            TextFit(fontScale = (needed / scaleX).coerceIn(smallest, 1f), scaleX = scaleX)
        }
        LongWordFit.SHRINK_THEN_NARROW -> {
            val fontScale = needed.coerceIn(smallest, 1f)
            TextFit(fontScale = fontScale, scaleX = (needed / fontScale).coerceIn(narrowest, 1f))
        }
        LongWordFit.NARROW_ONLY -> TextFit(fontScale = 1f, scaleX = needed.coerceIn(narrowest, 1f))
        LongWordFit.SHRINK_ONLY -> TextFit(fontScale = needed.coerceIn(smallest, 1f), scaleX = 1f)
        LongWordFit.CUT_OFF -> TextFit.NONE
    }
}

/** [style] drawn with [fit] applied: condensed glyphs lose their tracking too, which is dead width by then. */
fun TextStyle.fitted(fit: TextFit): TextStyle = if (fit == TextFit.NONE) {
    this
} else {
    copy(
        fontSize = fontSize * fit.fontScale,
        letterSpacing = if (fit.condensed) 0.sp else letterSpacing,
        textGeometricTransform = if (fit.condensed) TextGeometricTransform(scaleX = fit.scaleX) else textGeometricTransform,
    )
}

/**
 * One line of text that squeezes, then shrinks, before it ellipsizes, so a
 * short label such as "Open" in a narrow button reads whole instead of "Ope…".
 * [style] needs a font size for the shrinking step. [fitting] says which
 * steps run and how far; the keyboard sets it from the user's choice.
 *
 * A plain [Layout] rather than BoxWithConstraints: it answers intrinsic
 * measurements (a subcompose layout throws on them) and costs no subcomposition.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fitting: TextFitting = LocalTextFitting.current,
) {
    val measurer = rememberTextMeasurer()
    // Written by measure, read by draw: a new layout at the same size still redraws.
    val drawn = remember { mutableStateOf<TextLayoutResult?>(null) }
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { drawn.value?.let { drawText(it, color = ink) } },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val natural = measurer.measure(text, style, maxLines = 1, softWrap = false)
                val fit = if (constraints.hasBoundedWidth) {
                    fitTextWidth(natural.size.width.toFloat(), constraints.maxWidth.toFloat(), fitting)
                } else {
                    TextFit.NONE
                }
                val result = if (fit == TextFit.NONE && natural.size.width <= constraints.maxWidth) {
                    natural
                } else {
                    measurer.measure(
                        text,
                        style.fitted(fit),
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = constraints.maxWidth),
                    )
                }
                drawn.value = result
                val width = result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
                val height = result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
                return layout(width, height) {}
            }

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
                measurer.measure(text, style, maxLines = 1, softWrap = false).size.width

            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
                (maxIntrinsicWidth(measurables, height) * fitting.minScaleX * fitting.minFontScale).toInt()

            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                measurer.measure(text, style, maxLines = 1, softWrap = false).size.height

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                minIntrinsicHeight(measurables, width)
        },
    )
}

/**
 * A label of exactly [lines] lines that breaks only between words. A word too
 * wide for one line on its own, "Appearance" in a narrow toolbox cell, is
 * fitted the way [FitText] fits a label (narrowed, then smaller) instead of
 * being split into "Appeara" and "nce". The height is always [lines] lines, so
 * cells in a grid stay one size whatever their names; [onTextLayout] gets the
 * layout as drawn.
 */
@Composable
fun FitWordsText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    lines: Int = 2,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    fitting: TextFitting = LocalTextFitting.current,
) {
    val measurer = rememberTextMeasurer()
    val drawn = remember { mutableStateOf<TextLayoutResult?>(null) }
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val aligned = if (textAlign == TextAlign.Unspecified) style else style.copy(textAlign = textAlign)
    val words = remember(text) { text.split(WORD_GAP).filter { it.isNotEmpty() } }
    val placeholder = remember(lines) { List(lines.coerceAtLeast(1)) { "A" }.joinToString("\n") }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { drawn.value?.let { drawText(it, color = ink) } },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val fit = if (constraints.hasBoundedWidth && words.isNotEmpty()) {
                    val widest = words.maxOf { measurer.measure(it, aligned, maxLines = 1, softWrap = false).size.width }
                    fitTextWidth(widest.toFloat(), constraints.maxWidth.toFloat(), fitting)
                } else {
                    TextFit.NONE
                }
                val fitted = aligned.fitted(fit)
                val result = measurer.measure(
                    text,
                    fitted,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = lines,
                    constraints = if (constraints.hasBoundedWidth) {
                        Constraints(minWidth = constraints.minWidth, maxWidth = constraints.maxWidth)
                    } else {
                        Constraints()
                    },
                )
                drawn.value = result
                onTextLayout(result)
                // Measured at the unfitted size: a narrowed name keeps the cell's height.
                val tall = measurer.measure(placeholder, aligned, maxLines = lines).size.height
                val width = result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
                val height = maxOf(result.size.height, tall).coerceIn(constraints.minHeight, constraints.maxHeight)
                return layout(width, height) {}
            }

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
                measurer.measure(text, aligned, maxLines = 1, softWrap = false).size.width

            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
                val widest = words.maxOfOrNull { measurer.measure(it, aligned, maxLines = 1, softWrap = false).size.width } ?: 0
                return (widest * fitting.minScaleX * fitting.minFontScale).toInt()
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                measurer.measure(placeholder, aligned, maxLines = lines).size.height

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                minIntrinsicHeight(measurables, width)
        },
    )
}

private val WORD_GAP = Regex("\\s+")
