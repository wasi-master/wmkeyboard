package com.wasimaster.wmkeyboard.core.notes.rich

import java.util.Locale

/**
 * What a run of a note's text can wear in the rich editor (#1586).
 *
 * A note is still stored as Markdown. Bold, italic, strikethrough, inline code
 * and links are written the Markdown way; what Markdown has no syntax for
 * (underline and its styles, text colour, highlight colour, font, size) is
 * written as a small set of inline HTML tags that Markdown allows inside a
 * line and that Obsidian, GitHub and the keyboard's own reader understand:
 * `<u>`, `<mark>` and `<span style="…">`. See [NoteMarkup] for both halves.
 *
 * [exclusive] kinds take one value at a time: a word is red or blue, not
 * both, so putting blue on red text replaces the red.
 */
enum class NoteMarkKind(val exclusive: Boolean) {
    BOLD(false),
    ITALIC(false),
    STRIKE(false),
    CODE(false),
    UNDERLINE(true),
    COLOR(true),
    HIGHLIGHT(true),
    FONT(true),
    SIZE(true),
    LINK(true),
}

/**
 * One mark: its kind and, for the kinds that carry one, its value. A colour
 * is `#rrggbb`; a highlight is `#rrggbb` or null for the plain yellow of
 * `<mark>`; an underline's value is its CSS style (`wavy`, `dotted`,
 * `dashed`, `double`) or null for a plain line; a font is a CSS generic
 * family (`serif`, `monospace`, `cursive`); a size is in `em`, as `1.25em`;
 * a link's value is its address.
 */
data class NoteMark(val kind: NoteMarkKind, val value: String? = null) {
    companion object {
        val BOLD = NoteMark(NoteMarkKind.BOLD)
        val ITALIC = NoteMark(NoteMarkKind.ITALIC)
        val STRIKE = NoteMark(NoteMarkKind.STRIKE)
        val CODE = NoteMark(NoteMarkKind.CODE)
        val UNDERLINE = NoteMark(NoteMarkKind.UNDERLINE)

        fun underline(style: NoteUnderline): NoteMark = NoteMark(NoteMarkKind.UNDERLINE, style.css)
        fun color(hex: String): NoteMark = NoteMark(NoteMarkKind.COLOR, NoteCss.colour(hex) ?: hex)
        fun highlight(hex: String?): NoteMark = NoteMark(NoteMarkKind.HIGHLIGHT, hex?.let { NoteCss.colour(it) ?: it })
        fun font(font: NoteFont): NoteMark = NoteMark(NoteMarkKind.FONT, font.css)
        fun size(em: String): NoteMark = NoteMark(NoteMarkKind.SIZE, NoteCss.size(em) ?: em)
        fun link(url: String): NoteMark = NoteMark(NoteMarkKind.LINK, url)
    }
}

/** The underline styles the format sheet offers, by their CSS `text-decoration-style`. */
enum class NoteUnderline(val css: String?) {
    SOLID(null),
    WAVY("wavy"),
    DOTTED("dotted"),
    DASHED("dashed"),
    DOUBLE("double"),
    ;

    companion object {
        fun of(css: String?): NoteUnderline = entries.firstOrNull { it.css == css } ?: SOLID
    }
}

/**
 * The fonts a run of text can be set in, by their CSS generic family. Generic
 * families rather than font files: every reader of the stored note (another
 * Markdown app, a browser, the keyboard on another phone) has a serif, a
 * monospace and a handwriting-like face of its own to put there.
 */
enum class NoteFont(val css: String) {
    SERIF("serif"),
    MONO("monospace"),
    HAND("cursive"),
    ;

    companion object {
        fun of(css: String?): NoteFont? {
            val c = css?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when (c) {
                "serif" -> SERIF
                "monospace", "mono" -> MONO
                "cursive", "handwriting" -> HAND
                else -> null
            }
        }
    }
}

/**
 * The colours the format sheet offers. Mid tones, so the same stored colour
 * reads on a dark theme and a light one; the highlight is drawn with some of
 * the page showing through for the same reason.
 */
object NoteRichPalette {
    /** Text colours: red, orange, amber, green, teal, blue, purple, grey. */
    val TEXT: List<String> = listOf("#e53935", "#f4511e", "#f9a825", "#43a047", "#00897b", "#1e88e5", "#8e24aa", "#757575")

    /** Highlight colours; null is `<mark>`'s own yellow. */
    val HIGHLIGHT: List<String?> = listOf(null, "#66bb6a", "#42a5f5", "#f06292", "#ffa726", "#ab47bc")

    /** The yellow `<mark>` stands for when it carries no colour of its own. */
    const val DEFAULT_HIGHLIGHT = "#fdd835"

    /** Text sizes beside the body's own, in `em`. */
    val SIZES: List<String> = listOf("0.8em", "1.25em", "1.5em", "2em")
}

/**
 * Reading and writing the few CSS values the rich editor uses. Everything is
 * normalised on the way in, so a mark read from `color: #E53935` and one put on
 * from the palette compare equal.
 */
object NoteCss {
    private val HEX = Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val LENGTH = Regex("""^([0-9]*\.?[0-9]+)\s*(px|em|rem|%|pt)?$""")

    /** CSS colour names a hand-written note might use, as `#rrggbb`. */
    private val NAMES = mapOf(
        "red" to "#e53935", "orange" to "#f4511e", "yellow" to "#fdd835", "green" to "#43a047",
        "teal" to "#00897b", "blue" to "#1e88e5", "purple" to "#8e24aa", "grey" to "#757575",
        "gray" to "#757575", "pink" to "#f06292", "brown" to "#6d4c41", "black" to "#000000", "white" to "#ffffff",
    )

    /** [value] as lowercase `#rrggbb` (or `#aarrggbb`), or null when it is not a colour this can read. */
    fun colour(value: String): String? {
        val v = value.trim().lowercase(Locale.ROOT)
        NAMES[v]?.let { return it }
        val m = HEX.matchEntire(v) ?: return null
        val digits = m.groupValues[1]
        return if (digits.length == 3) "#" + digits.map { "$it$it" }.joinToString("") else "#$digits"
    }

    /** The colour as ARGB, or null. */
    fun argb(value: String?): Int? {
        val hex = value?.let(::colour) ?: return null
        val digits = hex.substring(1)
        val n = digits.toLongOrNull(16) ?: return null
        return if (digits.length == 8) n.toInt() else (0xFF000000L or n).toInt()
    }

    /**
     * A font size as `em`, written without trailing zeros: `20px` is `1.25em`
     * (16 px to the em), `150%` is `1.5em`. Null for a size this cannot read.
     */
    fun size(value: String): String? {
        val m = LENGTH.matchEntire(value.trim().lowercase(Locale.ROOT)) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val em = when (m.groupValues[2]) {
            "px" -> n / 16.0
            "pt" -> n / 12.0
            "%" -> n / 100.0
            else -> n
        }
        if (em <= 0.0 || em > 8.0) return null
        return trim(em) + "em"
    }

    /** The scale a size value stands for, or null. */
    fun scale(value: String?): Float? = value?.let(::size)?.removeSuffix("em")?.toFloatOrNull()

    private fun trim(n: Double): String {
        val rounded = Math.round(n * 100.0) / 100.0
        val s = rounded.toString()
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }

    /** `a: b; c: d` as name to value pairs, names lowercased. */
    fun declarations(style: String): List<Pair<String, String>> =
        style.split(';').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = part.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = part.substring(colon + 1).trim().trim('"', '\'')
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
}
