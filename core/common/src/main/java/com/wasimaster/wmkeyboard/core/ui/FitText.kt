package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
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

/**
 * How to fit a label of [measuredWidthPx] into [availableWidthPx], the way
 * Gboard does: condense the glyphs first, which keeps the letters as tall as
 * their neighbours, and only once that reaches [minScaleX] shrink the font.
 * Text width is near enough linear in both, so the ratio of the two widths is
 * the scale needed. The floors together fit a label about twice the width;
 * past that the caller's ellipsis takes over.
 */
fun fitTextWidth(
    measuredWidthPx: Float,
    availableWidthPx: Float,
    minScaleX: Float = MIN_TEXT_SCALE_X,
    minFontScale: Float = MIN_TEXT_FONT_SCALE,
): TextFit {
    if (availableWidthPx <= 0f || measuredWidthPx <= 0f || measuredWidthPx <= availableWidthPx) return TextFit.NONE
    val needed = availableWidthPx / measuredWidthPx
    val scaleX = needed.coerceIn(minScaleX, 1f)
    val fontScale = (needed / scaleX).coerceIn(minFontScale, 1f)
    return TextFit(fontScale, scaleX)
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
 * [style] needs a font size for the shrinking step.
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
    minScaleX: Float = MIN_TEXT_SCALE_X,
    minFontScale: Float = MIN_TEXT_FONT_SCALE,
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
                    fitTextWidth(natural.size.width.toFloat(), constraints.maxWidth.toFloat(), minScaleX, minFontScale)
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
                (maxIntrinsicWidth(measurables, height) * minScaleX * minFontScale).toInt()

            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                measurer.measure(text, style, maxLines = 1, softWrap = false).size.height

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                minIntrinsicHeight(measurables, width)
        },
    )
}
