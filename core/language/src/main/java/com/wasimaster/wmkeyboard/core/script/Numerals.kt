package com.wasimaster.wmkeyboard.core.script

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.language.R

/**
 * The digit glyphs a language draws and types. Layout data always stores ASCII
 * `0`–`9`; the chosen system is applied late — at key-label draw time and at
 * commit time — so the layouts, the number-row auto-detection and the digit
 * long-press data all keep seeing plain ASCII.
 *
 * [digits] is the ten glyphs for `0`..`9` in order, or null for a passthrough
 * (Latin, and [AUTO] before it is resolved against a language). The choice is
 * made per language: [AUTO] (the default for every language) means "follow this
 * language's own [LanguageDef.numeralSystem]"; every other value forces that
 * system while that language is active.
 *
 * Settings offers two of these per language — [AUTO] (that language's own
 * digits) and [LATIN] — because no Bengali writer wants Persian digits. The
 * named systems stay here for [LanguageDef.numeralSystem] to point at, and so a
 * value an older build stored still decodes and still types what it says.
 *
 * [labelRes] is the settings label, resolved where it is drawn so it follows the
 * device language. Every value but [AUTO] labels itself with its own digits, so
 * those resources are marked untranslatable.
 */
enum class NumeralSystem(val digits: String?, @StringRes val labelRes: Int) {
    // Labels stay short: they render as segmented buttons in settings.
    AUTO(null, R.string.core_lang_numeral_system_auto),
    LATIN(null, R.string.core_lang_numeral_system_latin),
    ARABIC_INDIC("٠١٢٣٤٥٦٧٨٩", R.string.core_lang_numeral_system_arabic_indic),
    PERSIAN("۰۱۲۳۴۵۶۷۸۹", R.string.core_lang_numeral_system_persian),
    BENGALI("০১২৩৪৫৬৭৮৯", R.string.core_lang_numeral_system_bengali),
    DEVANAGARI("०१२३४५६७८९", R.string.core_lang_numeral_system_devanagari),
    THAI("๐๑๒๓๔๕๖๗๘๙", R.string.core_lang_numeral_system_thai),
    LAO("໐໑໒໓໔໕໖໗໘໙", R.string.core_lang_numeral_system_lao),
    KHMER("០១២៣៤៥៦៧៨៩", R.string.core_lang_numeral_system_khmer),
    MYANMAR("၀၁၂၃၄၅၆၇၈၉", R.string.core_lang_numeral_system_myanmar),
    TIBETAN("༠༡༢༣༤༥༦༧༨༩", R.string.core_lang_numeral_system_tibetan),
    MEETEI("꯰꯱꯲꯳꯴꯵꯶꯷꯸꯹", R.string.core_lang_numeral_system_meetei),
    OL_CHIKI("᱐᱑᱒᱓᱔᱕᱖᱗᱘᱙", R.string.core_lang_numeral_system_ol_chiki),
    SHAN("႐႑႒႓႔႕႖႗႘႙", R.string.core_lang_numeral_system_shan),

    /**
     * Warang Citi's own digits (U+118E0..118E9), for Ho. Outside the BMP,
     * so each glyph is two chars: read them with [digitGlyph], never by index.
     */
    WARANG_CITI("𑣠𑣡𑣢𑣣𑣤𑣥𑣦𑣧𑣨𑣩", R.string.core_lang_numeral_system_warang_citi),
}

/**
 * The glyph for digit [d] (0..9) in [digits], a [NumeralSystem.digits]
 * string. A system outside the BMP (Warang Citi) spends two chars a glyph,
 * so the glyph is a string and found by code point, not by index.
 */
fun digitGlyph(digits: String, d: Int): String =
    if (digits.length == DIGIT_COUNT) {
        digits[d].toString()
    } else {
        val start = digits.offsetByCodePoints(0, d)
        digits.substring(start, digits.offsetByCodePoints(start, 1))
    }

private const val DIGIT_COUNT = 10

/**
 * Where a non-Latin numeral system rewrites the digits that get committed.
 * Drawing is unaffected — the chosen glyphs always show on the keys; this only
 * governs what text is inserted.
 *
 * [TEXT_ONLY] (default) keeps ASCII in numeric/phone/date/time fields, which the
 * OS and apps expect to be machine-parseable, and types native digits elsewhere.
 * [EVERYWHERE] types native digits in those fields too. [DISPLAY_ONLY] always
 * commits ASCII, so the feature is purely cosmetic.
 */
enum class NumeralCommitScope(@StringRes val labelRes: Int) {
    TEXT_ONLY(R.string.core_lang_numeral_scope_text_fields),
    EVERYWHERE(R.string.core_lang_numeral_scope_everywhere),
    DISPLAY_ONLY(R.string.core_lang_numeral_scope_display_only),
}

/**
 * The glyph set to apply for [setting] — [language]'s own per-language choice —
 * or null when no remap is needed (Latin / an Auto language that stays Latin).
 * [AUTO] defers to the language's own default; anything else forces its digits.
 */
fun resolveNumeralDigits(setting: NumeralSystem, language: LanguageDef): String? =
    if (setting == NumeralSystem.AUTO) language.numeralSystem.digits else setting.digits

/** The ASCII digits in the same shape as [NumeralSystem.digits]. */
const val ASCII_DIGITS = "0123456789"

/**
 * Leads a popup alternate that types exactly what it says, past the numeral
 * system (#309). An ASCII `1` offered under a hold on an Arabic-Indic board is
 * the one digit the commit-time rewrite must not touch, and a popup alternate
 * reaches the service as a bare string with nowhere else to say so.
 *
 * U+2060 WORD JOINER: default-ignorable, so the popup and the corner hint draw
 * the digit alone. The service strips it before anything is typed.
 */
const val VERBATIM_DIGITS = "⁠"

/**
 * Rewrites the ASCII digits in [s] to [digits] (indexed `0`..`9`), leaving every
 * other character untouched. A null [digits] — the common Latin case — returns
 * [s] unchanged, so this is safe to call unconditionally on any label or output.
 */
fun mapDigits(s: String, digits: String?): String {
    if (digits == null || s.isEmpty()) return s
    var i = 0
    while (i < s.length) {
        if (s[i] in '0'..'9') {
            return buildString(s.length) {
                append(s, 0, i)
                for (j in i until s.length) {
                    val c = s[j]
                    if (c in '0'..'9') append(digitGlyph(digits, c - '0')) else append(c)
                }
            }
        }
        i++
    }
    return s
}
