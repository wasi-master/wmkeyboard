package com.wasimaster.wmkeyboard.core.script

import java.text.BreakIterator
import java.util.Locale

/**
 * One Fancy Text style: a per-letter map onto a Unicode "font"
 * (mathematical alphanumerics, enclosed letters, combining marks).
 *
 * [lower]/[upper] are keyed by the plain ASCII letter; values are
 * strings because a styled letter can be an astral pair (\uD835…)
 * or a base letter plus a combining mark. A style with no form for
 * a letter maps it to itself (superscript q), and small caps has no
 * capitals at all, so its two maps carry the same glyphs.
 */
data class FancyStyle(
    val id: String,
    /** Plain-text name, for TalkBack and anywhere astral glyphs can't go. */
    val name: String,
    /** The name written in the style itself — the WYSIWYG chip label. */
    val sample: String,
    val lower: Map<Char, String>,
    val upper: Map<Char, String>,
    /** How the mapped letters are put back together. Nearly always in order. */
    val assembly: FancyAssembly = FancyAssembly.IN_ORDER,
    /**
     * The style's own 0–9, keyed by the plain digit. Empty for a style
     * Unicode gives no digits (italic, script, fraktur…), whose numbers
     * then stay plain; a digit missing from the map (parenthesized 0) does
     * the same.
     */
    val digits: Map<Char, String> = emptyMap(),
    /**
     * How tall [FancyAssembly.ZALGO] stacks its marks, [FancyStyles.ZALGO_LEVELS];
     * 0 for every other style.
     */
    val zalgoLevel: Int = 0,
)

/**
 * How [FancyStyles.transform] reassembles the letters a style has mapped.
 *
 * A per-letter table cannot express the odd ones out, because none is a
 * property of a letter: one is about the order of the line, two about what
 * sits between its letters, and one draws something new every time.
 */
enum class FancyAssembly {
    /** Straight concatenation. Every style but the ones below. */
    IN_ORDER,

    /**
     * Concatenation back to front. Rotating a line of text turns its last
     * letter into its first, so upside-down glyphs only read as upside-down
     * text once the order is reversed too — a table of rotated glyphs alone
     * spells the word backwards to anyone who turns the phone round.
     *
     * Reversed by mapped unit rather than by char, so an astral form (𐐒 for
     * B) is not torn into two orphaned surrogates on the way.
     */
    REVERSED,

    /**
     * A zero-width space between every pair of visible characters (an emoji
     * or a letter with its marks counts as one, and is never split). Reads
     * identically and matches nothing that looks for whole words — a link
     * scanner, a spoiler tag, a filter — since no two letters are adjacent
     * any more. Whitespace is left alone: the separator is only needed
     * where the text has no break of its own.
     */
    ZERO_WIDTH_SEPARATED,

    /**
     * [ZERO_WIDTH_SEPARATED]'s rules with a visible gap instead: a four-per-em
     * space (U+2005) between every pair of visible characters, for the spaced
     * "vaporwave" look. That space is a character nothing else types, so
     * reading the text back can take every one out without touching the
     * user's own spaces.
     */
    SPACED,

    /**
     * Random combining marks stacked above, below and (from level 4) through
     * every ASCII letter and digit: "zalgo", as tall as the style's
     * [FancyStyle.zalgoLevel]. The marks are drawn per letter from a seed of
     * the letter, its place in the text and a number picked once per process,
     * so the word being typed keeps its marks from one keystroke to the next
     * rather than reshuffling under the finger, and the same word looks
     * different after the keyboard restarts.
     *
     * A letter that already carries a mark is left alone, so styling styled
     * text adds nothing (everything committed is styled, #950).
     *
     * Zalgo styles are not in [FancyStyles.builtIn]: the user keeps a list of
     * them, each at its own level (see [FancyStyles.useZalgo]).
     */
    ZALGO,
}

/**
 * The Fancy Text styles: [NORMAL_ID] first, then the 22 Unicode styles in
 * the order the old per-style layouts shipped, then the ones added since.
 * The first 22 were generated from those layouts' key labels, so the glyphs
 * are exactly what each style always typed; the batch from Subscript on was
 * generated from code points by a script that also checked every form
 * survives NFC unchanged. Do not retype by hand: several values are
 * surrogate pairs or letter+combining-mark sequences that editors love to
 * normalise.
 *
 * Order is append-only. The stored setting names a style by id, so moving
 * one would not break it, but the settings list is drawn in this order and
 * users learn where the one they use sits.
 */
object FancyStyles {

    /**
     * The plain style: every letter maps to itself. It is a real entry
     * rather than a null so the strip can offer "back to normal" as a chip
     * without leaving the fancy keyboard, and so every reader of the table
     * keeps treating a style as "a transform", not "maybe a transform".
     */
    const val NORMAL_ID = "normal"

    const val DEFAULT_ID = "bold"

    /**
     * The language id the fancy layout carries. Everything that has to ask
     * "is this the fancy keyboard?" reads it from here rather than repeating
     * the string, since a style only applies on that one language.
     */
    const val LANG_ID = "fancy"

    /**
     * What every user-made style's id starts with. The settings app mints
     * `custom_<random>` ids, so a font the user made can never take the id of
     * a built-in style added in a later release.
     */
    const val CUSTOM_PREFIX = "custom_"

    /**
     * Every styled set of 0–9 Unicode has, each a run of code points rather
     * than typed glyphs (astral digits are surrogate pairs, see the note on
     * the table). Several sets lack a zero, or keep it somewhere else: those
     * are built with [digitRun]'s `zero`.
     */
    private val BOLD_DIGITS = digitRun(0x1D7CE)
    private val DOUBLE_STRUCK_DIGITS = digitRun(0x1D7D8)
    private val SANS_DIGITS = digitRun(0x1D7E2)
    private val SANS_BOLD_DIGITS = digitRun(0x1D7EC)
    private val MONOSPACE_DIGITS = digitRun(0x1D7F6)
    private val FULLWIDTH_DIGITS = digitRun(0xFF10)
    private val CIRCLED_DIGITS = digitRun(0x245F, zero = 0x24EA)
    private val NEGATIVE_CIRCLED_DIGITS = digitRun(0x2775, zero = 0x24FF)
    private val SANS_CIRCLED_DIGITS = digitRun(0x277F, zero = 0x1F10B)
    private val PARENTHESIZED_DIGITS = digitRun(0x2473, zero = null)
    private val FULL_STOP_DIGITS = digitRun(0x2487, zero = 0x1F100)
    private val SUBSCRIPT_DIGITS = digitRun(0x2080)
    private val SEGMENTED_DIGITS = digitRun(0x1FBF0)

    // ¹²³ are Latin-1 leftovers; only ⁰ and ⁴–⁹ sit in the superscripts block.
    private val SUPERSCRIPT_DIGITS = ('0'..'9').zip(
        listOf(0x2070, 0xB9, 0xB2, 0xB3, 0x2074, 0x2075, 0x2076, 0x2077, 0x2078, 0x2079),
    ).associate { (d, cp) -> d to String(Character.toChars(cp)) }

    /**
     * Which digits each style types. A style with no digits of its own
     * borrows the nearest set that keeps its weight and family — bold italic
     * types bold digits, sans italic sans ones — since a bold word next to
     * plain numbers reads as a mistake. Italic, script and fraktur have no
     * near neighbour and keep plain digits.
     */
    private val DIGITS_BY_STYLE: Map<String, Map<Char, String>> = mapOf(
        "bold" to BOLD_DIGITS,
        "bold_italic" to BOLD_DIGITS,
        "bold_script" to BOLD_DIGITS,
        "bold_fraktur" to BOLD_DIGITS,
        "double_struck" to DOUBLE_STRUCK_DIGITS,
        "sans" to SANS_DIGITS,
        "sans_italic" to SANS_DIGITS,
        "sans_bold" to SANS_BOLD_DIGITS,
        "sans_bold_italic" to SANS_BOLD_DIGITS,
        "monospace" to MONOSPACE_DIGITS,
        "fullwidth" to FULLWIDTH_DIGITS,
        "circled" to CIRCLED_DIGITS,
        "circled_filled" to NEGATIVE_CIRCLED_DIGITS,
        "superscript" to SUPERSCRIPT_DIGITS,
        "parenthesized" to PARENTHESIZED_DIGITS,
        "subscript" to SUBSCRIPT_DIGITS,
        "vaporwave" to FULLWIDTH_DIGITS,
        "bracketed" to wrappedDigits("\u3010", "\u3011"),
        "lenticular" to wrappedDigits("\u300E", "\u300F"),
        "boxed" to markedDigits('\u20DE'),
        "ringed" to markedDigits('\u20DD'),
    )

    /**
     * The marks [FancyAssembly.ZALGO] draws from, by where they sit. None is a
     * mark a Latin-script language writes with once decomposed (acute, grave,
     * caron, cedilla, comma below, horn…), none is one a combining style uses,
     * and none composes with a plain letter or digit under NFC, so an app
     * that normalises what it stores keeps them as typed. Above are all
     * class 230 and below all 220, so the order they are appended in (through,
     * below, above) is already canonical.
     */
    private val ZALGO_ABOVE = codePoints(
        0x030D, 0x030E, 0x0310, 0x0312, 0x0313, 0x0314, 0x033E, 0x033F, 0x0342, 0x0346, 0x034A,
        0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357, 0x035B, 0x0363, 0x0364, 0x0365, 0x0366,
        0x0367, 0x0368, 0x0369, 0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F,
    )
    private val ZALGO_BELOW = codePoints(
        0x0316, 0x0317, 0x0318, 0x0319, 0x031C, 0x031D, 0x031E, 0x031F, 0x0320, 0x0329, 0x032A,
        0x032B, 0x032C, 0x032F, 0x0339, 0x033A, 0x033B, 0x0347, 0x0348, 0x0349, 0x034D, 0x0353,
        0x0354, 0x0355, 0x0356, 0x0359, 0x035A,
    )
    private val ZALGO_THROUGH = codePoints(0x0335, 0x0337)

    /** Every zalgo mark, for [unstyle] to take back out. */
    private val ZALGO_MARKS: Set<Char> = (ZALGO_ABOVE + ZALGO_BELOW + ZALGO_THROUGH).toHashSet()

    /** Picked once per process; see [FancyAssembly.ZALGO]. */
    private val ZALGO_SALT: Long = kotlin.random.Random.nextLong()

    // Both read while [table] is first built, so they sit above it.
    /** How tall a zalgo style can stack: 1 is a mark or so a side, 10 a tower. */
    val ZALGO_LEVELS = 1..10

    /**
     * The zalgo styles an install starts with, until the user changes the
     * list: one light, one heavy.
     */
    val DEFAULT_ZALGO: List<Pair<String, Int>> = listOf("zalgo" to 3, "zalgo_heavy" to 7)

    /**
     * Every set, in the order a digit key's long press lists them: the
     * mathematical ones first, then the enclosed, then the small and the odd.
     * Includes the sets no style types (sans circled, full stop, subscript,
     * segmented) — the long press is the only way to reach those.
     */
    private val ALL_DIGIT_SETS = listOf(
        BOLD_DIGITS, DOUBLE_STRUCK_DIGITS, SANS_DIGITS, SANS_BOLD_DIGITS, MONOSPACE_DIGITS,
        FULLWIDTH_DIGITS, CIRCLED_DIGITS, NEGATIVE_CIRCLED_DIGITS, SANS_CIRCLED_DIGITS,
        PARENTHESIZED_DIGITS, FULL_STOP_DIGITS, SUPERSCRIPT_DIGITS, SUBSCRIPT_DIGITS,
        SEGMENTED_DIGITS,
    )

    /** Every styled form of [digit], for its key's long press; empty for a non-digit. */
    fun digitForms(digit: Char): List<String> =
        if (digit in '0'..'9') ALL_DIGIT_SETS.mapNotNull { it[digit] } else emptyList()

    /** The styles that ship with the app, in strip order. [all] adds the user's after them. */
    val builtIn: List<FancyStyle> = listOf(
        style(
            NORMAL_ID, "Normal", "Normal",
            ('a'..'z').map { it.toString() },
            ('A'..'Z').map { it.toString() },
        ),
        style(
            "bold", "Bold", "𝐁𝐨𝐥𝐝",
            listOf(
                "𝐚", "𝐛", "𝐜", "𝐝", "𝐞", "𝐟", "𝐠", "𝐡", "𝐢",
                "𝐣", "𝐤", "𝐥", "𝐦", "𝐧", "𝐨", "𝐩", "𝐪", "𝐫",
                "𝐬", "𝐭", "𝐮", "𝐯", "𝐰", "𝐱", "𝐲", "𝐳",
            ),
            listOf(
                "𝐀", "𝐁", "𝐂", "𝐃", "𝐄", "𝐅", "𝐆", "𝐇", "𝐈",
                "𝐉", "𝐊", "𝐋", "𝐌", "𝐍", "𝐎", "𝐏", "𝐐", "𝐑",
                "𝐒", "𝐓", "𝐔", "𝐕", "𝐖", "𝐗", "𝐘", "𝐙",
            ),
        ),
        style(
            "italic", "Italic", "𝐼𝑡𝑎𝑙𝑖𝑐",
            listOf(
                "𝑎", "𝑏", "𝑐", "𝑑", "𝑒", "𝑓", "𝑔", "ℎ", "𝑖",
                "𝑗", "𝑘", "𝑙", "𝑚", "𝑛", "𝑜", "𝑝", "𝑞", "𝑟",
                "𝑠", "𝑡", "𝑢", "𝑣", "𝑤", "𝑥", "𝑦", "𝑧",
            ),
            listOf(
                "𝐴", "𝐵", "𝐶", "𝐷", "𝐸", "𝐹", "𝐺", "𝐻", "𝐼",
                "𝐽", "𝐾", "𝐿", "𝑀", "𝑁", "𝑂", "𝑃", "𝑄", "𝑅",
                "𝑆", "𝑇", "𝑈", "𝑉", "𝑊", "𝑋", "𝑌", "𝑍",
            ),
        ),
        style(
            "bold_italic", "Bold Italic", "𝑩𝒐𝒍𝒅 𝑰𝒕𝒂𝒍𝒊𝒄",
            listOf(
                "𝒂", "𝒃", "𝒄", "𝒅", "𝒆", "𝒇", "𝒈", "𝒉", "𝒊",
                "𝒋", "𝒌", "𝒍", "𝒎", "𝒏", "𝒐", "𝒑", "𝒒", "𝒓",
                "𝒔", "𝒕", "𝒖", "𝒗", "𝒘", "𝒙", "𝒚", "𝒛",
            ),
            listOf(
                "𝑨", "𝑩", "𝑪", "𝑫", "𝑬", "𝑭", "𝑮", "𝑯", "𝑰",
                "𝑱", "𝑲", "𝑳", "𝑴", "𝑵", "𝑶", "𝑷", "𝑸", "𝑹",
                "𝑺", "𝑻", "𝑼", "𝑽", "𝑾", "𝑿", "𝒀", "𝒁",
            ),
        ),
        style(
            "script", "Script", "𝒮𝒸𝓇𝒾𝓅𝓉",
            listOf(
                "𝒶", "𝒷", "𝒸", "𝒹", "ℯ", "𝒻", "ℊ", "𝒽", "𝒾",
                "𝒿", "𝓀", "𝓁", "𝓂", "𝓃", "ℴ", "𝓅", "𝓆", "𝓇",
                "𝓈", "𝓉", "𝓊", "𝓋", "𝓌", "𝓍", "𝓎", "𝓏",
            ),
            listOf(
                "𝒜", "ℬ", "𝒞", "𝒟", "ℰ", "ℱ", "𝒢", "ℋ", "ℐ",
                "𝒥", "𝒦", "ℒ", "ℳ", "𝒩", "𝒪", "𝒫", "𝒬", "ℛ",
                "𝒮", "𝒯", "𝒰", "𝒱", "𝒲", "𝒳", "𝒴", "𝒵",
            ),
        ),
        style(
            "bold_script", "Bold Script", "𝓑𝓸𝓵𝓭 𝓢𝓬𝓻𝓲𝓹𝓽",
            listOf(
                "𝓪", "𝓫", "𝓬", "𝓭", "𝓮", "𝓯", "𝓰", "𝓱", "𝓲",
                "𝓳", "𝓴", "𝓵", "𝓶", "𝓷", "𝓸", "𝓹", "𝓺", "𝓻",
                "𝓼", "𝓽", "𝓾", "𝓿", "𝔀", "𝔁", "𝔂", "𝔃",
            ),
            listOf(
                "𝓐", "𝓑", "𝓒", "𝓓", "𝓔", "𝓕", "𝓖", "𝓗", "𝓘",
                "𝓙", "𝓚", "𝓛", "𝓜", "𝓝", "𝓞", "𝓟", "𝓠", "𝓡",
                "𝓢", "𝓣", "𝓤", "𝓥", "𝓦", "𝓧", "𝓨", "𝓩",
            ),
        ),
        style(
            "fraktur", "Fraktur", "𝔉𝔯𝔞𝔨𝔱𝔲𝔯",
            listOf(
                "𝔞", "𝔟", "𝔠", "𝔡", "𝔢", "𝔣", "𝔤", "𝔥", "𝔦",
                "𝔧", "𝔨", "𝔩", "𝔪", "𝔫", "𝔬", "𝔭", "𝔮", "𝔯",
                "𝔰", "𝔱", "𝔲", "𝔳", "𝔴", "𝔵", "𝔶", "𝔷",
            ),
            listOf(
                "𝔄", "𝔅", "ℭ", "𝔇", "𝔈", "𝔉", "𝔊", "ℌ", "ℑ",
                "𝔍", "𝔎", "𝔏", "𝔐", "𝔑", "𝔒", "𝔓", "𝔔", "ℜ",
                "𝔖", "𝔗", "𝔘", "𝔙", "𝔚", "𝔛", "𝔜", "ℨ",
            ),
        ),
        style(
            "bold_fraktur", "Bold Fraktur", "𝕭𝖔𝖑𝖉 𝕱𝖗𝖆𝖐𝖙𝖚𝖗",
            listOf(
                "𝖆", "𝖇", "𝖈", "𝖉", "𝖊", "𝖋", "𝖌", "𝖍", "𝖎",
                "𝖏", "𝖐", "𝖑", "𝖒", "𝖓", "𝖔", "𝖕", "𝖖", "𝖗",
                "𝖘", "𝖙", "𝖚", "𝖛", "𝖜", "𝖝", "𝖞", "𝖟",
            ),
            listOf(
                "𝕬", "𝕭", "𝕮", "𝕯", "𝕰", "𝕱", "𝕲", "𝕳", "𝕴",
                "𝕵", "𝕶", "𝕷", "𝕸", "𝕹", "𝕺", "𝕻", "𝕼", "𝕽",
                "𝕾", "𝕿", "𝖀", "𝖁", "𝖂", "𝖃", "𝖄", "𝖅",
            ),
        ),
        style(
            "double_struck", "Double-struck", "𝔻𝕠𝕦𝕓𝕝𝕖-𝕤𝕥𝕣𝕦𝕔𝕜",
            listOf(
                "𝕒", "𝕓", "𝕔", "𝕕", "𝕖", "𝕗", "𝕘", "𝕙", "𝕚",
                "𝕛", "𝕜", "𝕝", "𝕞", "𝕟", "𝕠", "𝕡", "𝕢", "𝕣",
                "𝕤", "𝕥", "𝕦", "𝕧", "𝕨", "𝕩", "𝕪", "𝕫",
            ),
            listOf(
                "𝔸", "𝔹", "ℂ", "𝔻", "𝔼", "𝔽", "𝔾", "ℍ", "𝕀",
                "𝕁", "𝕂", "𝕃", "𝕄", "ℕ", "𝕆", "ℙ", "ℚ", "ℝ",
                "𝕊", "𝕋", "𝕌", "𝕍", "𝕎", "𝕏", "𝕐", "ℤ",
            ),
        ),
        style(
            "sans", "Sans-serif", "𝖲𝖺𝗇𝗌-𝗌𝖾𝗋𝗂𝖿",
            listOf(
                "𝖺", "𝖻", "𝖼", "𝖽", "𝖾", "𝖿", "𝗀", "𝗁", "𝗂",
                "𝗃", "𝗄", "𝗅", "𝗆", "𝗇", "𝗈", "𝗉", "𝗊", "𝗋",
                "𝗌", "𝗍", "𝗎", "𝗏", "𝗐", "𝗑", "𝗒", "𝗓",
            ),
            listOf(
                "𝖠", "𝖡", "𝖢", "𝖣", "𝖤", "𝖥", "𝖦", "𝖧", "𝖨",
                "𝖩", "𝖪", "𝖫", "𝖬", "𝖭", "𝖮", "𝖯", "𝖰", "𝖱",
                "𝖲", "𝖳", "𝖴", "𝖵", "𝖶", "𝖷", "𝖸", "𝖹",
            ),
        ),
        style(
            "sans_bold", "Sans Bold", "𝗦𝗮𝗻𝘀 𝗕𝗼𝗹𝗱",
            listOf(
                "𝗮", "𝗯", "𝗰", "𝗱", "𝗲", "𝗳", "𝗴", "𝗵", "𝗶",
                "𝗷", "𝗸", "𝗹", "𝗺", "𝗻", "𝗼", "𝗽", "𝗾", "𝗿",
                "𝘀", "𝘁", "𝘂", "𝘃", "𝘄", "𝘅", "𝘆", "𝘇",
            ),
            listOf(
                "𝗔", "𝗕", "𝗖", "𝗗", "𝗘", "𝗙", "𝗚", "𝗛", "𝗜",
                "𝗝", "𝗞", "𝗟", "𝗠", "𝗡", "𝗢", "𝗣", "𝗤", "𝗥",
                "𝗦", "𝗧", "𝗨", "𝗩", "𝗪", "𝗫", "𝗬", "𝗭",
            ),
        ),
        style(
            "sans_italic", "Sans Italic", "𝘚𝘢𝘯𝘴 𝘐𝘵𝘢𝘭𝘪𝘤",
            listOf(
                "𝘢", "𝘣", "𝘤", "𝘥", "𝘦", "𝘧", "𝘨", "𝘩", "𝘪",
                "𝘫", "𝘬", "𝘭", "𝘮", "𝘯", "𝘰", "𝘱", "𝘲", "𝘳",
                "𝘴", "𝘵", "𝘶", "𝘷", "𝘸", "𝘹", "𝘺", "𝘻",
            ),
            listOf(
                "𝘈", "𝘉", "𝘊", "𝘋", "𝘌", "𝘍", "𝘎", "𝘏", "𝘐",
                "𝘑", "𝘒", "𝘓", "𝘔", "𝘕", "𝘖", "𝘗", "𝘘", "𝘙",
                "𝘚", "𝘛", "𝘜", "𝘝", "𝘞", "𝘟", "𝘠", "𝘡",
            ),
        ),
        style(
            "sans_bold_italic", "Sans Bold Italic", "𝙎𝙖𝙣𝙨 𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘",
            listOf(
                "𝙖", "𝙗", "𝙘", "𝙙", "𝙚", "𝙛", "𝙜", "𝙝", "𝙞",
                "𝙟", "𝙠", "𝙡", "𝙢", "𝙣", "𝙤", "𝙥", "𝙦", "𝙧",
                "𝙨", "𝙩", "𝙪", "𝙫", "𝙬", "𝙭", "𝙮", "𝙯",
            ),
            listOf(
                "𝘼", "𝘽", "𝘾", "𝘿", "𝙀", "𝙁", "𝙂", "𝙃", "𝙄",
                "𝙅", "𝙆", "𝙇", "𝙈", "𝙉", "𝙊", "𝙋", "𝙌", "𝙍",
                "𝙎", "𝙏", "𝙐", "𝙑", "𝙒", "𝙓", "𝙔", "𝙕",
            ),
        ),
        style(
            "monospace", "Monospace", "𝙼𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎",
            listOf(
                "𝚊", "𝚋", "𝚌", "𝚍", "𝚎", "𝚏", "𝚐", "𝚑", "𝚒",
                "𝚓", "𝚔", "𝚕", "𝚖", "𝚗", "𝚘", "𝚙", "𝚚", "𝚛",
                "𝚜", "𝚝", "𝚞", "𝚟", "𝚠", "𝚡", "𝚢", "𝚣",
            ),
            listOf(
                "𝙰", "𝙱", "𝙲", "𝙳", "𝙴", "𝙵", "𝙶", "𝙷", "𝙸",
                "𝙹", "𝙺", "𝙻", "𝙼", "𝙽", "𝙾", "𝙿", "𝚀", "𝚁",
                "𝚂", "𝚃", "𝚄", "𝚅", "𝚆", "𝚇", "𝚈", "𝚉",
            ),
        ),
        style(
            "fullwidth", "Fullwidth", "Ｆｕｌｌｗｉｄｔｈ",
            listOf(
                "ａ", "ｂ", "ｃ", "ｄ", "ｅ", "ｆ", "ｇ", "ｈ", "ｉ",
                "ｊ", "ｋ", "ｌ", "ｍ", "ｎ", "ｏ", "ｐ", "ｑ", "ｒ",
                "ｓ", "ｔ", "ｕ", "ｖ", "ｗ", "ｘ", "ｙ", "ｚ",
            ),
            listOf(
                "Ａ", "Ｂ", "Ｃ", "Ｄ", "Ｅ", "Ｆ", "Ｇ", "Ｈ", "Ｉ",
                "Ｊ", "Ｋ", "Ｌ", "Ｍ", "Ｎ", "Ｏ", "Ｐ", "Ｑ", "Ｒ",
                "Ｓ", "Ｔ", "Ｕ", "Ｖ", "Ｗ", "Ｘ", "Ｙ", "Ｚ",
            ),
        ),
        style(
            "circled", "Circled", "Ⓒⓘⓡⓒⓛⓔⓓ",
            listOf(
                "ⓐ", "ⓑ", "ⓒ", "ⓓ", "ⓔ", "ⓕ", "ⓖ", "ⓗ", "ⓘ",
                "ⓙ", "ⓚ", "ⓛ", "ⓜ", "ⓝ", "ⓞ", "ⓟ", "ⓠ", "ⓡ",
                "ⓢ", "ⓣ", "ⓤ", "ⓥ", "ⓦ", "ⓧ", "ⓨ", "ⓩ",
            ),
            listOf(
                "Ⓐ", "Ⓑ", "Ⓒ", "Ⓓ", "Ⓔ", "Ⓕ", "Ⓖ", "Ⓗ", "Ⓘ",
                "Ⓙ", "Ⓚ", "Ⓛ", "Ⓜ", "Ⓝ", "Ⓞ", "Ⓟ", "Ⓠ", "Ⓡ",
                "Ⓢ", "Ⓣ", "Ⓤ", "Ⓥ", "Ⓦ", "Ⓧ", "Ⓨ", "Ⓩ",
            ),
        ),
        style(
            "circled_filled", "Circled (Filled)", "🅒🅘🅡🅒🅛🅔🅓 (🅕🅘🅛🅛🅔🅓)",
            listOf(
                "🅐", "🅑", "🅒", "🅓", "🅔", "🅕", "🅖", "🅗", "🅘",
                "🅙", "🅚", "🅛", "🅜", "🅝", "🅞", "🅟", "🅠", "🅡",
                "🅢", "🅣", "🅤", "🅥", "🅦", "🅧", "🅨", "🅩",
            ),
            listOf(
                "🅐", "🅑", "🅒", "🅓", "🅔", "🅕", "🅖", "🅗", "🅘",
                "🅙", "🅚", "🅛", "🅜", "🅝", "🅞", "🅟", "🅠", "🅡",
                "🅢", "🅣", "🅤", "🅥", "🅦", "🅧", "🅨", "🅩",
            ),
        ),
        style(
            "squared", "Squared", "🅂🅀🅄🄰🅁🄴🄳",
            listOf(
                "🄰", "🄱", "🄲", "🄳", "🄴", "🄵", "🄶", "🄷", "🄸",
                "🄹", "🄺", "🄻", "🄼", "🄽", "🄾", "🄿", "🅀", "🅁",
                "🅂", "🅃", "🅄", "🅅", "🅆", "🅇", "🅈", "🅉",
            ),
            listOf(
                "🄰", "🄱", "🄲", "🄳", "🄴", "🄵", "🄶", "🄷", "🄸",
                "🄹", "🄺", "🄻", "🄼", "🄽", "🄾", "🄿", "🅀", "🅁",
                "🅂", "🅃", "🅄", "🅅", "🅆", "🅇", "🅈", "🅉",
            ),
        ),
        style(
            "squared_filled", "Squared (Filled)", "🆂🆀🆄🅰🆁🅴🅳 (🅵🅸🅻🅻🅴🅳)",
            listOf(
                "🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸",
                "🅹", "🅺", "🅻", "🅼", "🅽", "🅾", "🅿", "🆀", "🆁",
                "🆂", "🆃", "🆄", "🆅", "🆆", "🆇", "🆈", "🆉",
            ),
            listOf(
                "🅰", "🅱", "🅲", "🅳", "🅴", "🅵", "🅶", "🅷", "🅸",
                "🅹", "🅺", "🅻", "🅼", "🅽", "🅾", "🅿", "🆀", "🆁",
                "🆂", "🆃", "🆄", "🆅", "🆆", "🆇", "🆈", "🆉",
            ),
        ),
        // q is ǫ, as every small-caps generator writes it: the real small
        // capital Q (U+A7AF, Unicode 11) is missing from most phone fonts and
        // showed as nothing.
        style(
            "small_caps", "Small Caps", "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ",
            listOf(
                "ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ", "ɪ",
                "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ", "ǫ", "ʀ",
                "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ",
            ),
            listOf(
                "ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ", "ɪ",
                "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ", "ǫ", "ʀ",
                "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ",
            ),
        ),
        style(
            "superscript", "Superscript", "ˢᵘᵖᵉʳˢᶜʳⁱᵖᵗ",
            listOf(
                "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ⁱ",
                "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "q", "ʳ",
                "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ˣ", "ʸ", "ᶻ",
            ),
            listOf(
                "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ⁱ",
                "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "q", "ʳ",
                "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ˣ", "ʸ", "ᶻ",
            ),
        ),
        style(
            "strikethrough", "Strikethrough", "S̶t̶r̶i̶k̶e̶t̶h̶r̶o̶u̶g̶h̶",
            listOf(
                "a̶", "b̶", "c̶", "d̶", "e̶", "f̶", "g̶", "h̶", "i̶",
                "j̶", "k̶", "l̶", "m̶", "n̶", "o̶", "p̶", "q̶", "r̶",
                "s̶", "t̶", "u̶", "v̶", "w̶", "x̶", "y̶", "z̶",
            ),
            listOf(
                "A̶", "B̶", "C̶", "D̶", "E̶", "F̶", "G̶", "H̶", "I̶",
                "J̶", "K̶", "L̶", "M̶", "N̶", "O̶", "P̶", "Q̶", "R̶",
                "S̶", "T̶", "U̶", "V̶", "W̶", "X̶", "Y̶", "Z̶",
            ),
        ),
        style(
            "underline", "Underline", "U̲n̲d̲e̲r̲l̲i̲n̲e̲",
            listOf(
                "a̲", "b̲", "c̲", "d̲", "e̲", "f̲", "g̲", "h̲", "i̲",
                "j̲", "k̲", "l̲", "m̲", "n̲", "o̲", "p̲", "q̲", "r̲",
                "s̲", "t̲", "u̲", "v̲", "w̲", "x̲", "y̲", "z̲",
            ),
            listOf(
                "A̲", "B̲", "C̲", "D̲", "E̲", "F̲", "G̲", "H̲", "I̲",
                "J̲", "K̲", "L̲", "M̲", "N̲", "O̲", "P̲", "Q̲", "R̲",
                "S̲", "T̲", "U̲", "V̲", "W̲", "X̲", "Y̲", "Z̲",
            ),
        ),
        // Flag letters. Adjacent pairs that spell a country code fuse into that
        // flag — 🇷🇪 for "re" — which is the whole look, not a bug in the table.
        style(
            "regional_indicator", "Regional Indicator", "🇷🇪🇬🇮🇴🇳🇦🇱 🇮🇳🇩🇮🇨🇦🇹🇴🇷",
            listOf(
                "🇦", "🇧", "🇨", "🇩", "🇪", "🇫", "🇬", "🇭", "🇮",
                "🇯", "🇰", "🇱", "🇲", "🇳", "🇴", "🇵", "🇶", "🇷",
                "🇸", "🇹", "🇺", "🇻", "🇼", "🇽", "🇾", "🇿",
            ),
            listOf(
                "🇦", "🇧", "🇨", "🇩", "🇪", "🇫", "🇬", "🇭", "🇮",
                "🇯", "🇰", "🇱", "🇲", "🇳", "🇴", "🇵", "🇶", "🇷",
                "🇸", "🇹", "🇺", "🇻", "🇼", "🇽", "🇾", "🇿",
            ),
        ),
        // U+249C..U+24B5, which Unicode only ever gave a lowercase to: the two maps
        // carry the same glyphs, as small caps does.
        style(
            "parenthesized", "Parenthesized", "⒫⒜⒭⒠⒩⒯⒣⒠⒮⒤⒵⒠⒟",
            listOf(
                "⒜", "⒝", "⒞", "⒟", "⒠", "⒡", "⒢", "⒣", "⒤",
                "⒥", "⒦", "⒧", "⒨", "⒩", "⒪", "⒫", "⒬", "⒭",
                "⒮", "⒯", "⒰", "⒱", "⒲", "⒳", "⒴", "⒵",
            ),
            listOf(
                "⒜", "⒝", "⒞", "⒟", "⒠", "⒡", "⒢", "⒣", "⒤",
                "⒥", "⒦", "⒧", "⒨", "⒩", "⒪", "⒫", "⒬", "⒭",
                "⒮", "⒯", "⒰", "⒱", "⒲", "⒳", "⒴", "⒵",
            ),
        ),
        style(
            "double_underline", "Double Underline", "D̳o̳u̳b̳l̳e̳ U̳n̳d̳e̳r̳l̳i̳n̳e̳",
            listOf(
                "a̳", "b̳", "c̳", "d̳", "e̳", "f̳", "g̳", "h̳", "i̳",
                "j̳", "k̳", "l̳", "m̳", "n̳", "o̳", "p̳", "q̳", "r̳",
                "s̳", "t̳", "u̳", "v̳", "w̳", "x̳", "y̳", "z̳",
            ),
            listOf(
                "A̳", "B̳", "C̳", "D̳", "E̳", "F̳", "G̳", "H̳", "I̳",
                "J̳", "K̳", "L̳", "M̳", "N̳", "O̳", "P̳", "Q̳", "R̳",
                "S̳", "T̳", "U̳", "V̳", "W̳", "X̳", "Y̳", "Z̳",
            ),
        ),
        style(
            "overline", "Overline", "O̅v̅e̅r̅l̅i̅n̅e̅",
            listOf(
                "a̅", "b̅", "c̅", "d̅", "e̅", "f̅", "g̅", "h̅", "i̅",
                "j̅", "k̅", "l̅", "m̅", "n̅", "o̅", "p̅", "q̅", "r̅",
                "s̅", "t̅", "u̅", "v̅", "w̅", "x̅", "y̅", "z̅",
            ),
            listOf(
                "A̅", "B̅", "C̅", "D̅", "E̅", "F̅", "G̅", "H̅", "I̅",
                "J̅", "K̅", "L̅", "M̅", "N̅", "O̅", "P̅", "Q̅", "R̅",
                "S̅", "T̅", "U̅", "V̅", "W̅", "X̅", "Y̅", "Z̅",
            ),
        ),
        style(
            "slashthrough", "Slashthrough", "S̸l̸a̸s̸h̸t̸h̸r̸o̸u̸g̸h̸",
            listOf(
                "a̸", "b̸", "c̸", "d̸", "e̸", "f̸", "g̸", "h̸", "i̸",
                "j̸", "k̸", "l̸", "m̸", "n̸", "o̸", "p̸", "q̸", "r̸",
                "s̸", "t̸", "u̸", "v̸", "w̸", "x̸", "y̸", "z̸",
            ),
            listOf(
                "A̸", "B̸", "C̸", "D̸", "E̸", "F̸", "G̸", "H̸", "I̸",
                "J̸", "K̸", "L̸", "M̸", "N̸", "O̸", "P̸", "Q̸", "R̸",
                "S̸", "T̸", "U̸", "V̸", "W̸", "X̸", "Y̸", "Z̸",
            ),
        ),
        style(
            "wavy_underline", "Wavy Underline", "W̰a̰v̰y̰ Ṵn̰d̰ḛr̰l̰ḭn̰ḛ",
            listOf(
                "a̰", "b̰", "c̰", "d̰", "ḛ", "f̰", "g̰", "h̰", "ḭ",
                "j̰", "k̰", "l̰", "m̰", "n̰", "o̰", "p̰", "q̰", "r̰",
                "s̰", "t̰", "ṵ", "v̰", "w̰", "x̰", "y̰", "z̰",
            ),
            listOf(
                "A̰", "B̰", "C̰", "D̰", "Ḛ", "F̰", "G̰", "H̰", "Ḭ",
                "J̰", "K̰", "L̰", "M̰", "N̰", "O̰", "P̰", "Q̰", "R̰",
                "S̰", "T̰", "Ṵ", "V̰", "W̰", "X̰", "Y̰", "Z̰",
            ),
        ),
        style(
            "dotted", "Dotted", "Ḋȯṫṫėḋ",
            listOf(
                "ȧ", "ḃ", "ċ", "ḋ", "ė", "ḟ", "ġ", "ḣ", "i̇",
                "j̇", "k̇", "l̇", "ṁ", "ṅ", "ȯ", "ṗ", "q̇", "ṙ",
                "ṡ", "ṫ", "u̇", "v̇", "ẇ", "ẋ", "ẏ", "ż",
            ),
            listOf(
                "Ȧ", "Ḃ", "Ċ", "Ḋ", "Ė", "Ḟ", "Ġ", "Ḣ", "İ",
                "J̇", "K̇", "L̇", "Ṁ", "Ṅ", "Ȯ", "Ṗ", "Q̇", "Ṙ",
                "Ṡ", "Ṫ", "U̇", "V̇", "Ẇ", "Ẋ", "Ẏ", "Ż",
            ),
        ),
        // Currency and other symbol glyphs that look like the letters
        // (Telegram group). Single-case. Letters a language writes with (ł,
        // Ø, Đ) are left out, so reading a styled field back never turns a
        // Polish or Danish word into plain letters: Ɨ, Ꝋ and ₫ stand in.
        style(
            "currency", "Currency", "₵ɄⱤⱤɆ₦₵Ɏ",
            listOf(
                "₳", "฿", "₵", "₫", "Ɇ", "₣", "₲", "Ⱨ", "Ɨ",
                "J", "₭", "Ⱡ", "₥", "₦", "Ꝋ", "₱", "Q", "Ɽ",
                "₴", "₮", "Ʉ", "V", "₩", "Ӿ", "Ɏ", "Ⱬ",
            ),
            listOf(
                "₳", "฿", "₵", "₫", "Ɇ", "₣", "₲", "Ⱨ", "Ɨ",
                "J", "₭", "Ⱡ", "₥", "₦", "Ꝋ", "₱", "Q", "Ɽ",
                "₴", "₮", "Ʉ", "V", "₩", "Ӿ", "Ɏ", "Ⱬ",
            ),
        ),
        // Rotated glyphs laid out back to front — see [FancyAssembly.REVERSED].
        style(
            "upside_down", "Upside Down", "uʍoᗡ ǝpısd∩",
            listOf(
                "ɐ", "q", "ɔ", "p", "ǝ", "ɟ", "ƃ", "ɥ", "ı",
                "ɾ", "ʞ", "l", "ɯ", "u", "o", "d", "b", "ɹ",
                "s", "ʇ", "n", "ʌ", "ʍ", "x", "ʎ", "z",
            ),
            listOf(
                "∀", "𐐒", "Ɔ", "ᗡ", "Ǝ", "Ⅎ", "⅁", "H", "I",
                "ſ", "⋊", "⅂", "W", "N", "O", "Ԁ", "Ò", "ᴚ",
                "S", "⊥", "∩", "Ʌ", "M", "X", "⅄", "Z",
            ),
            assembly = FancyAssembly.REVERSED,
        ),
        // Every visible character held apart by a zero-width space — see
        // [FancyAssembly.ZERO_WIDTH_SEPARATED].
        style(
            "bypass", "Bypass", "B\u200By\u200Bp\u200Ba\u200Bs\u200Bs",
            listOf(
                "a", "b", "c", "d", "e", "f", "g", "h", "i",
                "j", "k", "l", "m", "n", "o", "p", "q", "r",
                "s", "t", "u", "v", "w", "x", "y", "z",
            ),
            listOf(
                "A", "B", "C", "D", "E", "F", "G", "H", "I",
                "J", "K", "L", "M", "N", "O", "P", "Q", "R",
                "S", "T", "U", "V", "W", "X", "Y", "Z",
            ),
            assembly = FancyAssembly.ZERO_WIDTH_SEPARATED,
        ),
        // Unicode has subscripts for only 17 letters; the rest type plain, and
        // capitals type the small forms, as superscript's do.
        style(
            "subscript", "Subscript", "ₛᵤbₛcᵣᵢₚₜ",
            listOf(
                "ₐ", "b", "c", "d", "ₑ", "f", "g", "ₕ", "ᵢ",
                "ⱼ", "ₖ", "ₗ", "ₘ", "ₙ", "ₒ", "ₚ", "q", "ᵣ",
                "ₛ", "ₜ", "ᵤ", "ᵥ", "w", "ₓ", "y", "z",
            ),
            listOf(
                "ₐ", "b", "c", "d", "ₑ", "f", "g", "ₕ", "ᵢ",
                "ⱼ", "ₖ", "ₗ", "ₘ", "ₙ", "ₒ", "ₚ", "q", "ᵣ",
                "ₛ", "ₜ", "ᵤ", "ᵥ", "w", "ₓ", "y", "z",
            ),
        ),
        // Flipped glyphs laid out back to front: a mirrored line reads right to
        // left, so it needs the reversal upside down does. Letters that are
        // their own mirror image stay plain.
        style(
            "mirror", "Mirror", "ɿoɿɿiM",
            listOf(
                "ɒ", "d", "ɔ", "b", "ɘ", "ʇ", "ϱ", "ʜ", "i",
                "į", "ʞ", "l", "m", "n", "o", "q", "p", "ɿ",
                "ƨ", "ƚ", "υ", "v", "w", "x", "γ", "z",
            ),
            listOf(
                "A", "ꓭ", "Ɔ", "ꓷ", "Ǝ", "ꟻ", "Ꭾ", "H", "I",
                "Ⴑ", "ꓘ", "⅃", "M", "И", "O", "ꟼ", "Ϙ", "Я",
                "Ƨ", "T", "U", "V", "W", "X", "Y", "Ƹ",
            ),
            assembly = FancyAssembly.REVERSED,
        ),
        // Look-alike scripts. They borrow real letters of other alphabets, which
        // Currency avoids; that is safe here because unstyle only ever runs
        // while the fancy keyboard is the one typing.
        style(
            "faux_cyrillic", "Faux Cyrillic", "Fацх Ҁугіllіс",
            listOf(
                "а", "в", "с", "ԁ", "э", "f", "б", "ђ", "і",
                "ј", "к", "l", "м", "и", "о", "р", "q", "г",
                "ѕ", "т", "ц", "v", "ш", "х", "у", "z",
            ),
            listOf(
                "Д", "Б", "Ҁ", "ↁ", "Є", "F", "Ԍ", "Н", "І",
                "Ј", "Ќ", "L", "М", "И", "Ф", "Р", "Q", "Я",
                "Ѕ", "Г", "Ц", "V", "Щ", "Ж", "Ч", "Z",
            ),
        ),
        style(
            "faux_greek", "Faux Greek", "ϜΛЦΞ GЯΣΣƘ",
            listOf(
                "Λ", "β", "Ͼ", "D", "Σ", "Ϝ", "G", "Ή", "Ϊ",
                "J", "Ƙ", "L", "M", "П", "Ө", "Ρ", "Q", "Я",
                "Ƨ", "Ƭ", "Ц", "V", "Щ", "Ξ", "Ψ", "Z",
            ),
            listOf(
                "Λ", "β", "Ͼ", "D", "Σ", "Ϝ", "G", "Ή", "Ϊ",
                "J", "Ƙ", "L", "M", "П", "Ө", "Ρ", "Q", "Я",
                "Ƨ", "Ƭ", "Ц", "V", "Щ", "Ξ", "Ψ", "Z",
            ),
        ),
        style(
            "syllabics", "Syllabics", "ᔕYᒪᒪᗩᗷIᑕᔕ",
            listOf(
                "ᗩ", "ᗷ", "ᑕ", "ᗪ", "E", "ᖴ", "G", "ᕼ", "I",
                "ᒍ", "K", "ᒪ", "ᗰ", "ᑎ", "O", "ᑭ", "ᑫ", "ᖇ",
                "ᔕ", "T", "ᑌ", "ᐯ", "ᗯ", "᙭", "Y", "ᘔ",
            ),
            listOf(
                "ᗩ", "ᗷ", "ᑕ", "ᗪ", "E", "ᖴ", "G", "ᕼ", "I",
                "ᒍ", "K", "ᒪ", "ᗰ", "ᑎ", "O", "ᑭ", "ᑫ", "ᖇ",
                "ᔕ", "T", "ᑌ", "ᐯ", "ᗯ", "᙭", "Y", "ᘔ",
            ),
        ),
        style(
            "thai_look", "Thai Look", "Շђคเ ɭ๏๏к",
            listOf(
                "ค", "๒", "ς", "๔", "є", "Ŧ", "ﻮ", "ђ", "เ",
                "ן", "к", "ɭ", "๓", "ภ", "๏", "ק", "ɋ", "г",
                "ร", "Շ", "ย", "ש", "ฬ", "א", "ץ", "չ",
            ),
            listOf(
                "ค", "๒", "ς", "๔", "є", "Ŧ", "ﻮ", "ђ", "เ",
                "ן", "к", "ɭ", "๓", "ภ", "๏", "ק", "ɋ", "г",
                "ร", "Շ", "ย", "ש", "ฬ", "א", "ץ", "չ",
            ),
        ),
        style(
            "kanji_look", "Kanji Look", "Ҝ卂几ﾌ丨 ㄥㄖㄖҜ",
            listOf(
                "卂", "乃", "匚", "ᗪ", "乇", "千", "Ꮆ", "卄", "丨",
                "ﾌ", "Ҝ", "ㄥ", "爪", "几", "ㄖ", "卩", "Ɋ", "尺",
                "丂", "ㄒ", "ㄩ", "ᐯ", "山", "乂", "ㄚ", "乙",
            ),
            listOf(
                "卂", "乃", "匚", "ᗪ", "乇", "千", "Ꮆ", "卄", "丨",
                "ﾌ", "Ҝ", "ㄥ", "爪", "几", "ㄖ", "卩", "Ɋ", "尺",
                "丂", "ㄒ", "ㄩ", "ᐯ", "山", "乂", "ㄚ", "乙",
            ),
        ),
        style(
            "ethiopic_look", "Ethiopic Look", "ቿፕዘጎዐየጎር ረዐዐጕ",
            listOf(
                "ል", "ገ", "ር", "ዕ", "ቿ", "ቻ", "ኗ", "ዘ", "ጎ",
                "ጋ", "ጕ", "ረ", "ጠ", "ክ", "ዐ", "የ", "ዒ", "ዪ",
                "ነ", "ፕ", "ሁ", "ሀ", "ሠ", "ሸ", "ሃ", "ጊ",
            ),
            listOf(
                "ል", "ገ", "ር", "ዕ", "ቿ", "ቻ", "ኗ", "ዘ", "ጎ",
                "ጋ", "ጕ", "ረ", "ጠ", "ክ", "ዐ", "የ", "ዒ", "ዪ",
                "ነ", "ፕ", "ሁ", "ሀ", "ሠ", "ሸ", "ሃ", "ጊ",
            ),
        ),
        style(
            "bracketed", "Bracketed", "【B】【r】【a】【c】【k】【e】【t】【e】【d】",
            listOf(
                "【a】", "【b】", "【c】", "【d】", "【e】", "【f】", "【g】", "【h】", "【i】",
                "【j】", "【k】", "【l】", "【m】", "【n】", "【o】", "【p】", "【q】", "【r】",
                "【s】", "【t】", "【u】", "【v】", "【w】", "【x】", "【y】", "【z】",
            ),
            listOf(
                "【A】", "【B】", "【C】", "【D】", "【E】", "【F】", "【G】", "【H】", "【I】",
                "【J】", "【K】", "【L】", "【M】", "【N】", "【O】", "【P】", "【Q】", "【R】",
                "【S】", "【T】", "【U】", "【V】", "【W】", "【X】", "【Y】", "【Z】",
            ),
        ),
        style(
            "lenticular", "Lenticular", "『L』『e』『n』『t』『i』『c』『u』『l』『a』『r』",
            listOf(
                "『a』", "『b』", "『c』", "『d』", "『e』", "『f』", "『g』", "『h』", "『i』",
                "『j』", "『k』", "『l』", "『m』", "『n』", "『o』", "『p』", "『q』", "『r』",
                "『s』", "『t』", "『u』", "『v』", "『w』", "『x』", "『y』", "『z』",
            ),
            listOf(
                "『A』", "『B』", "『C』", "『D』", "『E』", "『F』", "『G』", "『H』", "『I』",
                "『J』", "『K』", "『L』", "『M』", "『N』", "『O』", "『P』", "『Q』", "『R』",
                "『S』", "『T』", "『U』", "『V』", "『W』", "『X』", "『Y』", "『Z』",
            ),
        ),
        style(
            "tilde_strike", "Tilde Strike", "T̴i̴l̴d̴e̴ S̴t̴r̴i̴k̴e̴",
            listOf(
                "a̴", "b̴", "c̴", "d̴", "e̴", "f̴", "g̴", "h̴", "i̴",
                "j̴", "k̴", "l̴", "m̴", "n̴", "o̴", "p̴", "q̴", "r̴",
                "s̴", "t̴", "u̴", "v̴", "w̴", "x̴", "y̴", "z̴",
            ),
            listOf(
                "A̴", "B̴", "C̴", "D̴", "E̴", "F̴", "G̴", "H̴", "I̴",
                "J̴", "K̴", "L̴", "M̴", "N̴", "O̴", "P̴", "Q̴", "R̴",
                "S̴", "T̴", "U̴", "V̴", "W̴", "X̴", "Y̴", "Z̴",
            ),
        ),
        style(
            "cross_above", "Cross Above", "C̽r̽o̽s̽s̽ A̽b̽o̽v̽e̽",
            listOf(
                "a̽", "b̽", "c̽", "d̽", "e̽", "f̽", "g̽", "h̽", "i̽",
                "j̽", "k̽", "l̽", "m̽", "n̽", "o̽", "p̽", "q̽", "r̽",
                "s̽", "t̽", "u̽", "v̽", "w̽", "x̽", "y̽", "z̽",
            ),
            listOf(
                "A̽", "B̽", "C̽", "D̽", "E̽", "F̽", "G̽", "H̽", "I̽",
                "J̽", "K̽", "L̽", "M̽", "N̽", "O̽", "P̽", "Q̽", "R̽",
                "S̽", "T̽", "U̽", "V̽", "W̽", "X̽", "Y̽", "Z̽",
            ),
        ),
        style(
            "seagull_below", "Seagull Below", "S̼e̼a̼g̼u̼l̼l̼ B̼e̼l̼o̼w̼",
            listOf(
                "a̼", "b̼", "c̼", "d̼", "e̼", "f̼", "g̼", "h̼", "i̼",
                "j̼", "k̼", "l̼", "m̼", "n̼", "o̼", "p̼", "q̼", "r̼",
                "s̼", "t̼", "u̼", "v̼", "w̼", "x̼", "y̼", "z̼",
            ),
            listOf(
                "A̼", "B̼", "C̼", "D̼", "E̼", "F̼", "G̼", "H̼", "I̼",
                "J̼", "K̼", "L̼", "M̼", "N̼", "O̼", "P̼", "Q̼", "R̼",
                "S̼", "T̼", "U̼", "V̼", "W̼", "X̼", "Y̼", "Z̼",
            ),
        ),
        style(
            "arrow_below", "Arrow Below", "A͎r͎r͎o͎w͎ B͎e͎l͎o͎w͎",
            listOf(
                "a͎", "b͎", "c͎", "d͎", "e͎", "f͎", "g͎", "h͎", "i͎",
                "j͎", "k͎", "l͎", "m͎", "n͎", "o͎", "p͎", "q͎", "r͎",
                "s͎", "t͎", "u͎", "v͎", "w͎", "x͎", "y͎", "z͎",
            ),
            listOf(
                "A͎", "B͎", "C͎", "D͎", "E͎", "F͎", "G͎", "H͎", "I͎",
                "J͎", "K͎", "L͎", "M͎", "N͎", "O͎", "P͎", "Q͎", "R͎",
                "S͎", "T͎", "U͎", "V͎", "W͎", "X͎", "Y͎", "Z͎",
            ),
        ),
        style(
            "boxed", "Boxed", "B⃞o⃞x⃞e⃞d⃞",
            listOf(
                "a⃞", "b⃞", "c⃞", "d⃞", "e⃞", "f⃞", "g⃞", "h⃞", "i⃞",
                "j⃞", "k⃞", "l⃞", "m⃞", "n⃞", "o⃞", "p⃞", "q⃞", "r⃞",
                "s⃞", "t⃞", "u⃞", "v⃞", "w⃞", "x⃞", "y⃞", "z⃞",
            ),
            listOf(
                "A⃞", "B⃞", "C⃞", "D⃞", "E⃞", "F⃞", "G⃞", "H⃞", "I⃞",
                "J⃞", "K⃞", "L⃞", "M⃞", "N⃞", "O⃞", "P⃞", "Q⃞", "R⃞",
                "S⃞", "T⃞", "U⃞", "V⃞", "W⃞", "X⃞", "Y⃞", "Z⃞",
            ),
        ),
        style(
            "ringed", "Ringed", "R⃝i⃝n⃝g⃝e⃝d⃝",
            listOf(
                "a⃝", "b⃝", "c⃝", "d⃝", "e⃝", "f⃝", "g⃝", "h⃝", "i⃝",
                "j⃝", "k⃝", "l⃝", "m⃝", "n⃝", "o⃝", "p⃝", "q⃝", "r⃝",
                "s⃝", "t⃝", "u⃝", "v⃝", "w⃝", "x⃝", "y⃝", "z⃝",
            ),
            listOf(
                "A⃝", "B⃝", "C⃝", "D⃝", "E⃝", "F⃝", "G⃝", "H⃝", "I⃝",
                "J⃝", "K⃝", "L⃝", "M⃝", "N⃝", "O⃝", "P⃝", "Q⃝", "R⃝",
                "S⃝", "T⃝", "U⃝", "V⃝", "W⃝", "X⃝", "Y⃝", "Z⃝",
            ),
        ),
        // The same few marks on a letter every time, so it reads back like any
        // other style. Zalgo below is the random one.
        style(
            "glitch", "Glitch", "G̜͋̿l̘͒̍i̹ͪͮt̺͆ͫc̫͑ͪh̩ͣͤ",
            listOf(
                "a̗̍̒", "b̞̿͑", "c̫͑ͪ", "d̻ͦ̔", "e͓ͭ͗", "f͚̓ͬ", "g̜͋̿", "h̩ͣͤ", "i̹ͪͮ",
                "j͉̎͆", "k͖͂ͥ", "l̘͒̍", "m̟ͧ͋", "n̬ͮͧ", "o͇̔̐", "p͔͌͐", "q̖ͤͩ", "r̝ͫ̓",
                "s̪̐͒", "t̺͆ͫ", "u͍͗̾", "v͙ͨ͛", "w̙ͯͭ", "x̠̾͂", "y̯͐ͤ", "z͈ͥͯ",
            ),
            listOf(
                "A̗̍̒", "B̞̿͑", "C̫͑ͪ", "D̻ͦ̔", "E͓ͭ͗", "F͚̓ͬ", "G̜͋̿", "H̩ͣͤ", "I̹ͪͮ",
                "J͉̎͆", "K͖͂ͥ", "L̘͒̍", "M̟ͧ͋", "N̬ͮͧ", "O͇̔̐", "P͔͌͐", "Q̖ͤͩ", "R̝ͫ̓",
                "S̪̐͒", "T̺͆ͫ", "U͍͗̾", "V͙ͨ͛", "W̙ͯͭ", "X̠̾͂", "Y̯͐ͤ", "Z͈ͥͯ",
            ),
        ),
        // Fullwidth letters held apart by a four-per-em space; see
        // [FancyAssembly.SPACED].
        style(
            "vaporwave", "Vaporwave", "Ｖ\u2005ａ\u2005ｐ\u2005ｏ\u2005ｒ\u2005ｗ\u2005ａ\u2005ｖ\u2005ｅ",
            listOf(
                "ａ", "ｂ", "ｃ", "ｄ", "ｅ", "ｆ", "ｇ", "ｈ", "ｉ",
                "ｊ", "ｋ", "ｌ", "ｍ", "ｎ", "ｏ", "ｐ", "ｑ", "ｒ",
                "ｓ", "ｔ", "ｕ", "ｖ", "ｗ", "ｘ", "ｙ", "ｚ",
            ),
            listOf(
                "Ａ", "Ｂ", "Ｃ", "Ｄ", "Ｅ", "Ｆ", "Ｇ", "Ｈ", "Ｉ",
                "Ｊ", "Ｋ", "Ｌ", "Ｍ", "Ｎ", "Ｏ", "Ｐ", "Ｑ", "Ｒ",
                "Ｓ", "Ｔ", "Ｕ", "Ｖ", "Ｗ", "Ｘ", "Ｙ", "Ｚ",
            ),
            assembly = FancyAssembly.SPACED,
        ),
    )

    /**
     * The table in force: [builtIn] then the user's own styles, with the
     * lookups built from it. Swapped whole by [useCustom] rather than edited,
     * so a reader on the keyboard's thread never sees half an update.
     */
    private class Table(val zalgo: List<FancyStyle>, val custom: List<FancyStyle>) {
        val all: List<FancyStyle> = builtIn + zalgo + custom
        val index: Map<String, FancyStyle> = all.associateBy { it.id }

        /**
         * Every styled form of every style, back to the plain letter or digit
         * it stands for. Lowercase first, so a style whose capitals are its
         * small letters (small caps) reads back lowercase. Reversed styles are
         * left out: upside-down borrows plain letters (n for u), which would
         * rewrite every plain word read through here.
         */
        val plainOf: Map<String, Char> by lazy {
            buildMap {
                for (style in all) {
                    if (style.assembly == FancyAssembly.REVERSED) continue
                    for (map in listOf(style.lower, style.upper, style.digits)) {
                        for ((plain, form) in map) {
                            val ascii = form.length == 1 && form[0] < '\u0080'
                            if (form != plain.toString() && !ascii) putIfAbsent(form, plain)
                        }
                    }
                }
            }
        }

        val longestForm: Int by lazy { plainOf.keys.maxOf { it.length } }
    }

    @Volatile
    private var table = Table(DEFAULT_ZALGO.map { (id, level) -> zalgo(id, level) }, emptyList())

    /**
     * Every style: the built-in ones, then the fonts the user made, in the
     * order they made them.
     */
    val all: List<FancyStyle> get() = table.all

    /** The fonts the user made, as last handed to [useCustom]. */
    val custom: List<FancyStyle> get() = table.custom

    fun byId(id: String): FancyStyle? = table.index[id]

    /**
     * The styles as the user arranged them: the ids in [order] first, in that
     * order, then every style [order] does not name in table order (so a
     * style added by a later release, or a font the user just made, still
     * turns up, at the end), less the ones in [hidden]. Ids of styles that no
     * longer exist are skipped.
     *
     * [keep] (the style in use) is never hidden, so the strip always has a
     * chip to show as selected; and hiding everything shows everything,
     * rather than an empty strip with no way back to it from the keyboard.
     */
    fun arranged(order: List<String>, hidden: Set<String>, keep: String? = null): List<FancyStyle> {
        val table = table
        val ordered = LinkedHashSet<FancyStyle>(table.all.size)
        for (id in order) table.index[id]?.let(ordered::add)
        ordered.addAll(table.all)
        if (hidden.isEmpty()) return ordered.toList()
        return ordered.filter { it.id !in hidden || it.id == keep }.ifEmpty { ordered.toList() }
    }

    /**
     * Installs the user's own styles after the built-in ones. Called with
     * every settings read, so the keyboard, the settings app and the
     * selection actions all see a font the moment it is saved; an unchanged
     * list is a no-op, so that costs nothing on the reads that change
     * something else.
     */
    fun useCustom(styles: List<FancyStyle>) {
        if (styles == table.custom) return
        table = Table(table.zalgo, styles.filter { isFreeId(it.id) })
    }

    /**
     * Installs the user's zalgo styles, in their order, between the built-in
     * styles and the user's fonts. Like [useCustom], called with every
     * settings read and a no-op when nothing changed. A style is rebuilt
     * only when its id, name or level did, so its chip keeps the stack it
     * was drawn with.
     */
    fun useZalgo(styles: List<FancyStyle>) {
        if (styles.map { Triple(it.id, it.name, it.zalgoLevel) } ==
            table.zalgo.map { Triple(it.id, it.name, it.zalgoLevel) }
        ) {
            return
        }
        table = Table(styles.filter { isFreeId(it.id) && it.assembly == FancyAssembly.ZALGO }, table.custom)
    }

    /** The zalgo styles in force, as last handed to [useZalgo]. */
    val zalgoStyles: List<FancyStyle> get() = table.zalgo


    /** What every zalgo style's id starts with. */
    const val ZALGO_PREFIX = "zalgo"


    /**
     * A zalgo style at [level], named [name], or "Zalgo" and the level when
     * the user gave it no name. Its chip shows this process's draw of the
     * name, since a random style has no fixed spelling to write down.
     */
    fun zalgo(id: String, level: Int, name: String = ""): FancyStyle {
        val shown = name.trim().ifEmpty { "Zalgo ${level.coerceIn(ZALGO_LEVELS)}" }
        val draft = FancyStyle(
            id = id,
            name = shown,
            sample = shown,
            lower = ('a'..'z').associateWith { it.toString() },
            upper = ('A'..'Z').associateWith { it.toString() },
            assembly = FancyAssembly.ZALGO,
            zalgoLevel = level.coerceIn(ZALGO_LEVELS),
        )
        return draft.copy(sample = transform(shown, draft))
    }

    /** A user style may not shadow a built-in one. */
    private fun isFreeId(id: String): Boolean = builtIn.none { it.id == id }

    /**
     * A style from the alphabets a user pasted: [lower] spells a to z,
     * [upper] A to Z and [digits] 0 to 9, each read by [splitAlphabet]. An
     * alphabet can stop short: the letters it does not reach stay plain. An
     * empty [upper] types the small forms for capitals too, the way small
     * caps does, since many of the fonts people copy from the web are
     * single-case. [reversed] writes the line back to front, for an
     * upside-down font.
     */
    fun fromAlphabets(
        id: String,
        name: String,
        lower: String,
        upper: String = "",
        digits: String = "",
        reversed: Boolean = false,
    ): FancyStyle {
        val small = splitAlphabet(lower)
        val capitals = splitAlphabet(upper).ifEmpty { small }
        val lowerMap = ('a'..'z').zip(small).toMap()
        val upperMap = ('A'..'Z').zip(capitals).toMap()
        val digitMap = ('0'..'9').zip(splitAlphabet(digits)).toMap()
        val assembly = if (reversed) FancyAssembly.REVERSED else FancyAssembly.IN_ORDER
        val draft = FancyStyle(id, name, name, lowerMap, upperMap, assembly, digitMap)
        return draft.copy(sample = transform(name, draft))
    }

    /**
     * The forms an alphabet field holds, in order. With whitespace in it, the
     * field is a list and each whitespace-separated item is one form, so a
     * letter can be written as several characters (`(a) (b) (c)`). Without
     * any, each user-perceived character is one form: a surrogate pair, or a
     * letter with the marks stacked on it, counts once.
     */
    fun splitAlphabet(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.any { it.isWhitespace() }) return trimmed.split(WHITESPACE)
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(trimmed)
        val out = ArrayList<String>(trimmed.length)
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            out.add(trimmed.substring(start, end))
            start = end
            end = breaks.next()
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * [text] with every mapped letter and digit swapped for its styled
     * form; everything else (punctuation, other scripts, digits the style
     * has no form for) passes through untouched.
     */
    fun transform(text: String, style: FancyStyle): String {
        if (text.isEmpty()) return text
        // The path nearly every style takes: one builder, no intermediate
        // list, nothing to reverse, interleave or stack.
        if (style.assembly == FancyAssembly.IN_ORDER) {
            val out = StringBuilder(text.length * 2)
            for (ch in text) {
                out.append(style.lower[ch] ?: style.upper[ch] ?: style.digits[ch] ?: ch)
            }
            return out.toString()
        }
        // Mapped forms, kept whole: the remaining assemblies all work on
        // letters rather than on chars, and a mapped form can be longer than
        // one char (an astral pair, a letter plus a combining mark).
        val units = ArrayList<String>(text.length)
        for (ch in text) {
            units.add(style.lower[ch] ?: style.upper[ch] ?: style.digits[ch] ?: ch.toString())
        }
        val out = StringBuilder(text.length * 3)
        when (style.assembly) {
            FancyAssembly.REVERSED -> for (i in units.indices.reversed()) out.append(units[i])
            // One unit per char of [text], so text[i] is what units[i] came from.
            FancyAssembly.ZERO_WIDTH_SEPARATED, FancyAssembly.SPACED -> {
                val separator = if (style.assembly == FancyAssembly.SPACED) SPACED_SEPARATOR else ZERO_WIDTH_SPACE
                for (i in units.indices) {
                    if (i > 0 && startsCharacter(text[i]) && endsCharacter(text[i - 1])) out.append(separator)
                    out.append(units[i])
                }
            }
            FancyAssembly.ZALGO -> for (i in units.indices) {
                out.append(units[i])
                val c = text[i]
                val plain = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
                val marked = i + 1 < text.length && isMark(text[i + 1])
                if (plain && !marked) appendZalgo(out, c, i, style.zalgoLevel.coerceIn(ZALGO_LEVELS))
            }
            FancyAssembly.IN_ORDER -> for (unit in units) out.append(unit)
        }
        return out.toString()
    }

    private const val ZERO_WIDTH_SPACE = '\u200B'

    /** What [FancyAssembly.SPACED] puts between characters: a four-per-em space. */
    private const val SPACED_SEPARATOR = '\u2005'

    /** A combining mark, of any kind: something drawn on the character before it. */
    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    /**
     * The zalgo stack for [c] at [index] at [level]: maybe a mark through it
     * (from level 4, likelier as the level rises), then between half the
     * level and the level's worth of marks below, and as many above. Drawn
     * from a splitmix64 stream seeded by [ZALGO_SALT], the place and the
     * letter, so the same letter in the same place of the word being typed
     * gets the same stack every time the word is drawn again.
     */
    private fun appendZalgo(out: StringBuilder, c: Char, index: Int, level: Int) {
        var state = ZALGO_SALT xor (index.toLong() shl 20) xor c.code.toLong()
        fun next(bound: Int): Int {
            state += GOLDEN_GAMMA
            var z = state
            z = (z xor (z ushr 30)) * MIX_1
            z = (z xor (z ushr 27)) * MIX_2
            z = z xor (z ushr 31)
            return ((z ushr 1) % bound).toInt()
        }
        val fewest = (level + 1) / 2
        val through = if (level > ZALGO_THROUGH_FROM && next(ZALGO_LEVELS.last) < level - ZALGO_THROUGH_FROM) 1 else 0
        val below = fewest + next(level - fewest + 1)
        val above = fewest + next(level - fewest + 1)
        repeat(through) { out.append(ZALGO_THROUGH[next(ZALGO_THROUGH.size)]) }
        repeat(below) { out.append(ZALGO_BELOW[next(ZALGO_BELOW.size)]) }
        repeat(above) { out.append(ZALGO_ABOVE[next(ZALGO_ABOVE.size)]) }
    }

    /** Levels at or below this never strike through. */
    private const val ZALGO_THROUGH_FROM = 3

    // splitmix64's constants.
    private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    private const val MIX_1 = -0x40a7b892e31b1a47L
    private const val MIX_2 = -0x6b2fb644ecceee15L

    /**
     * Whether [c] starts a character of its own, which a separator may go in
     * front of. Not whitespace, which breaks the text already; not a mark,
     * a joiner or a selector, which belong to the character before them; not
     * half of a surrogate pair, so an emoji is never torn in two; and not a
     * separator already there, so styling styled text changes nothing.
     */
    private fun startsCharacter(c: Char): Boolean =
        !c.isWhitespace() && !c.isSurrogate() && Character.getType(c).toByte() !in ATTACHED_TYPES

    /** Whether a separator may follow [c]; see [startsCharacter]. */
    private fun endsCharacter(c: Char): Boolean =
        !c.isWhitespace() && !c.isSurrogate() && c != ZERO_WIDTH_SPACE && c != '\u200D'

    /** Marks and format characters (ZWJ, the separator itself): part of what they follow. */
    private val ATTACHED_TYPES = setOf(
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.FORMAT,
    )

    /**
     * [text] with every styled letter and digit put back to plain, and the
     * separators [FancyAssembly.ZERO_WIDTH_SEPARATED] and
     * [FancyAssembly.SPACED] add and the marks [FancyAssembly.ZALGO] stacks
     * taken out: the words a styled field holds, for anything that reads them
     * as words.
     * Reversed text stays in the order it is written.
     */
    fun unstyle(text: CharSequence): String {
        val table = table
        val plainOf = table.plainOf
        val longestForm = table.longestForm
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            // No styled form starts with one of these, so none can be the
            // first char of a form the lookup below would have matched.
            val c = text[i]
            if (c == ZERO_WIDTH_SPACE || c == SPACED_SEPARATOR || c in ZALGO_MARKS) {
                i++
                continue
            }
            var matched = false
            for (length in minOf(longestForm, text.length - i) downTo 1) {
                val plain = plainOf[text.substring(i, i + length)] ?: continue
                out.append(plain)
                i += length
                matched = true
                break
            }
            if (!matched) out.append(text[i++])
        }
        return out.toString()
    }

    /** The 26 letters in a-z order, zipped into the two maps. */
    private fun style(
        id: String,
        name: String,
        sample: String,
        lower: List<String>,
        upper: List<String>,
        assembly: FancyAssembly = FancyAssembly.IN_ORDER,
    ): FancyStyle = FancyStyle(
        id = id,
        name = name,
        sample = sample,
        lower = ('a'..'z').zip(lower).toMap(),
        upper = ('A'..'Z').zip(upper).toMap(),
        assembly = assembly,
        digits = DIGITS_BY_STYLE[id].orEmpty(),
    )

    /** 0–9, each written between [open] and [close]: 【1】. */
    private fun wrappedDigits(open: String, close: String): Map<Char, String> =
        ('0'..'9').associateWith { open + it + close }

    /** 0–9, each with [mark] drawn on it: an enclosing square or circle. */
    private fun markedDigits(mark: Char): Map<Char, String> =
        ('0'..'9').associateWith { "$it$mark" }

    private fun codePoints(vararg points: Int): CharArray = CharArray(points.size) { points[it].toChar() }

    /**
     * 0–9 from a run of consecutive code points: [base] is the 0 when [zero]
     * is left alone, otherwise the code point *before* 1 (the run's 1–9) and
     * [zero] is the 0 kept elsewhere, or null for a set with none.
     */
    private fun digitRun(base: Int, zero: Int? = base): Map<Char, String> = buildMap {
        if (zero != null) put('0', String(Character.toChars(zero)))
        for (d in 1..9) put('0' + d, String(Character.toChars(base + d)))
    }
}
