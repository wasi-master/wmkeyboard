package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlin.math.abs

/**
 * Fitting typed text into a template's text layers.
 *
 * Two jobs. [splitLines] lays a message's words over a slot's lines (one
 * text layer each) so every line's character count sits inside its rule;
 * a message that cannot be laid out that way is not offered in that template
 * at all. [fit] then picks the font size each layer is drawn at, the way
 * Skia's player does for documents that ask for it (`rs` with `mf`/`xf`):
 * the largest size in range at which the text, wrapped at word boundaries,
 * fits the layer's box. Lottie ignores those fields, so this is what keeps a
 * long message inside the picture when Lottie draws it.
 *
 * Measuring is behind [TextMeasurer], so this file is plain Kotlin: the
 * renderer passes one backed by a Paint, tests a fixed-width fake.
 */
object TextFit {

    /** Measures text in a font. */
    fun interface TextMeasurer {
        /**
         * The advance width of [text] drawn in [font] (a family name) at
         * [sizePx], with [tracking] in thousandths of an em between letters.
         */
        fun width(text: String, font: String, sizePx: Float, tracking: Float): Float

        /** How far a capital rises above the baseline in [font] at [sizePx]. */
        fun capHeight(font: String, sizePx: Float): Float = sizePx * CAP_SHARE
    }

    /**
     * [words] laid over [lineCount] lines in order, each line's character
     * count (spaces included) inside [rules]'s range for it, or null when no
     * split does that. Among the splits that work, the one whose lines are
     * closest to the middle of their ranges wins, so "I LOVE YOU" over two
     * lines of 2..10 becomes "I LOVE" / "YOU" and not "I" / "LOVE YOU"
     * (whose "I" is under the minimum anyway).
     *
     * With one line, the whole text is that line and only its maximum is
     * checked against the text's length; the layer's box wraps it.
     */
    fun splitLines(words: List<String>, lineCount: Int, rules: (Int) -> LineRule): List<String>? {
        if (words.isEmpty() || lineCount <= 0) return null
        if (lineCount == 1) {
            val line = words.joinToString(" ")
            val rule = rules(0)
            return if (line.length in rule.minChars..maxOf(rule.minChars, rule.maxChars)) listOf(line) else null
        }
        if (words.size < lineCount) return null
        var best: List<String>? = null
        var bestScore = Float.MAX_VALUE
        // Cut points between words: choose lineCount-1 of words.size-1.
        val cuts = IntArray(lineCount - 1)
        // Even lines read best: the split whose lines differ least in length,
        // then the one that fills each line's range most evenly.
        fun score(lines: List<String>): Float {
            val longest = lines.maxOf { it.length }
            val shortest = lines.minOf { it.length }
            var fill = 0f
            lines.forEachIndexed { i, line ->
                val rule = rules(i)
                val span = (rule.maxChars - rule.minChars).coerceAtLeast(1)
                fill += abs((line.length - rule.minChars).toFloat() / span - HALF)
            }
            // On a tie the longer first line wins: "MISS YOU / LOTS" over "MISS / YOU LOTS".
            return (longest - shortest) + fill * EVENNESS_WEIGHT - lines[0].length * TIE_BREAK
        }

        fun visit(index: Int, from: Int) {
            if (index == cuts.size) {
                val lines = ArrayList<String>(lineCount)
                var start = 0
                for (cut in cuts) {
                    lines += words.subList(start, cut).joinToString(" ")
                    start = cut
                }
                lines += words.subList(start, words.size).joinToString(" ")
                for (i in lines.indices) {
                    val rule = rules(i)
                    if (lines[i].length < rule.minChars || lines[i].length > rule.maxChars) return
                }
                val s = score(lines)
                if (s < bestScore) {
                    bestScore = s
                    best = lines
                }
                return
            }
            // Leave at least one word for each line still to come.
            val last = words.size - (cuts.size - index)
            for (cut in from..last) {
                cuts[index] = cut
                visit(index + 1, cut + 1)
            }
        }
        if (words.size <= MAX_SPLIT_WORDS) visit(0, 1)
        return best
    }

    /** What [fit] decided for one layer: its size, and its text broken into the lines it is drawn in. */
    data class Fitted(val size: Float, val lines: List<String>) {
        val text: String get() = lines.joinToString("\r")
    }

    /**
     * The size [text] is drawn at in a layer whose original document is
     * [doc], and the lines it wraps into, under [rule]'s font bounds.
     *
     * - A layer with a box: the largest size in range at which the wrapped
     *   text fits the box's width, and its lines (at the document's line
     *   height scaled with the size) its height, within `ml` lines.
     * - A layer without one: the largest size at which the text is no wider
     *   than the template's own text was, so the words take the space the
     *   artist gave them.
     *
     * The range is the rule's bounds, else the document's `mf`/`xf`, else
     * a quarter of the document's size up to its size; `rs` 1 lets the size
     * grow past the document's own, anything else only shrinks it.
     */
    fun fit(
        text: String,
        doc: LottieDoc.TextDoc,
        rule: LineRule,
        measurer: TextMeasurer,
        font: String = doc.font,
    ): Fitted {
        val base = doc.size.coerceAtLeast(1f)
        val grows = doc.resize == 1
        val lower = rule.minFont.takeIf { it > 0f } ?: doc.minFont?.takeIf { it > 0f } ?: base * MIN_SCALE
        var upper = rule.maxFont.takeIf { it > 0f } ?: doc.maxFont?.takeIf { it > 0f } ?: base
        if (!grows) upper = minOf(upper, base)
        val min = minOf(lower, upper)
        val max = maxOf(lower, upper)
        val explicit = LottieDoc.splitLines(text).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }

        if (!doc.hasBox) {
            val reference = LottieDoc.splitLines(doc.text).maxOfOrNull { measurer.width(it, font, base, doc.tracking) }
                ?.takeIf { it > 0f } ?: (base * explicit.maxOf { it.length } * FALLBACK_ADVANCE)
            val size = search(min, max) { size ->
                explicit.all { measurer.width(it, font, size, doc.tracking) <= reference * POINT_TEXT_SLACK }
            }
            return Fitted(size, explicit)
        }

        val boxW = doc.boxWidth ?: 0f
        val boxH = doc.boxHeight ?: 0f
        val lineRatio = doc.lineHeight / base
        val maxLines = doc.maxLines ?: Int.MAX_VALUE
        val size = search(min, max) { size ->
            val wrapped = wrap(explicit, font, size, doc.tracking, boxW, measurer)
            // The first line is about one size tall; each further one adds a line height.
            wrapped != null && wrapped.size <= maxLines &&
                size * (1f + (wrapped.size - 1) * lineRatio) <= boxH * HEIGHT_SLACK
        }
        // The search ends on a size that fits, or on the minimum when none does;
        // wrap at the size chosen so the lines and the size agree.
        val lines = wrap(explicit, font, size, doc.tracking, boxW, measurer)
            ?: hardWrap(explicit, font, size, doc.tracking, boxW, measurer)
        return Fitted(size, lines)
    }

    /**
     * [paragraphs] wrapped at word boundaries to [width], or null when some
     * single word is wider than the line on its own (the size is too big).
     */
    fun wrap(
        paragraphs: List<String>,
        font: String,
        size: Float,
        tracking: Float,
        width: Float,
        measurer: TextMeasurer,
    ): List<String>? {
        val out = ArrayList<String>()
        for (paragraph in paragraphs) {
            val words = paragraph.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out += ""
                continue
            }
            var line = StringBuilder()
            for (word in words) {
                if (measurer.width(word, font, size, tracking) > width) return null
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || measurer.width(candidate, font, size, tracking) <= width) {
                    line = StringBuilder(candidate)
                } else {
                    out += line.toString()
                    line = StringBuilder(word)
                }
            }
            out += line.toString()
        }
        return out
    }

    /** Like [wrap], but a word too wide for a line is broken between characters rather than refused. */
    private fun hardWrap(
        paragraphs: List<String>,
        font: String,
        size: Float,
        tracking: Float,
        width: Float,
        measurer: TextMeasurer,
    ): List<String> {
        val out = ArrayList<String>()
        for (paragraph in paragraphs) {
            val line = StringBuilder()
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val trial = if (line.isEmpty()) word else "$line $word"
                if (measurer.width(trial, font, size, tracking) <= width) {
                    line.setLength(0)
                    line.append(trial)
                    continue
                }
                if (line.isNotEmpty()) {
                    out += line.toString()
                    line.setLength(0)
                }
                // Break the word itself, a character at a time.
                for (ch in word) {
                    if (line.isNotEmpty() && measurer.width("$line$ch", font, size, tracking) > width) {
                        out += line.toString()
                        line.setLength(0)
                    }
                    line.append(ch)
                }
            }
            out += line.toString()
        }
        return out
    }

    /**
     * The largest size in [min]..[max] for which [fits] holds, to about a
     * pixel, assuming smaller always fits when larger does. [min] when nothing
     * does: the text is then drawn small rather than not at all.
     */
    private fun search(min: Float, max: Float, fits: (Float) -> Boolean): Float {
        if (fits(max)) return max
        if (!fits(min)) return min
        var lo = min
        var hi = max
        var steps = 0
        while (hi - lo >= SEARCH_PRECISION && steps++ < SEARCH_STEPS) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
    }

    /** Far more words than any template has lines for; past it no split is tried (it would be slow and useless). */
    private const val MAX_SPLIT_WORDS = 24

    private const val EVENNESS_WEIGHT = 0.25f
    private const val HALF = 0.5f
    private const val TIE_BREAK = 0.001f
    private const val MIN_SCALE = 0.25f
    private const val SEARCH_STEPS = 14
    private const val SEARCH_PRECISION = 0.5f

    /** Point text may run this much wider than the artist's own words before it shrinks. */
    private const val POINT_TEXT_SLACK = 1.15f

    /** Lines may overhang the box's height by this much; descenders sit below the last line's box. */
    private const val HEIGHT_SLACK = 1.05f

    /** Average advance as a share of the size, for a document with no text to measure against. */
    private const val FALLBACK_ADVANCE = 0.6f

    /** A capital's height as a share of the font size, for a measurer that cannot tell. */
    const val CAP_SHARE = 0.72f
}
