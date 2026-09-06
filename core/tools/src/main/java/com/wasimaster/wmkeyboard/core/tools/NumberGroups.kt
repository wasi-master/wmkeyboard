package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import java.util.Locale

/**
 * Digit grouping for the number chip: "1234567" read back as "1,234,567" or,
 * where people count in lakh and crore, "12,34,567".
 *
 * Everything here works on the digit string rather than on a number, so a
 * fifteen-digit amount groups exactly instead of going through the rounding a
 * `Double` would impose on it.
 */
object NumberGroups {

    /**
     * Languages whose writers count in lakh and crore. Region tags cover the
     * rest ("en-IN"), but a language is the stronger signal here — someone
     * typing Bangla groups the South Asian way wherever their phone thinks it
     * is.
     */
    private val SOUTH_ASIAN_LANGUAGES = setOf(
        "as", "bho", "bn", "brx", "doi", "dv", "gom", "gu", "hi", "kn", "kok", "ks",
        "mai", "ml", "mni", "mr", "ne", "or", "pa", "sa", "sat", "sd", "si", "ta",
        "te", "ur",
    )

    /** Where the lakh/crore grouping is the everyday one. */
    private val SOUTH_ASIAN_REGIONS = setOf("BD", "BT", "IN", "LK", "NP", "PK")

    /**
     * The grouping to actually use: whatever was chosen, or — on
     * [NumberGrouping.AUTO] — the one that matches [localeTag], the language
     * being typed in.
     */
    fun styleFor(setting: NumberGrouping, localeTag: String): NumberGrouping = when {
        setting != NumberGrouping.AUTO -> setting
        isSouthAsian(localeTag) -> NumberGrouping.SOUTH_ASIAN
        else -> NumberGrouping.WESTERN
    }

    private fun isSouthAsian(localeTag: String): Boolean {
        if (localeTag.isEmpty()) return false
        val parts = localeTag.split('-', '_')
        if (parts[0].lowercase(Locale.ROOT) in SOUTH_ASIAN_LANGUAGES) return true
        return parts.drop(1).any { it.uppercase(Locale.ROOT) in SOUTH_ASIAN_REGIONS }
    }

    /**
     * [digits] — a run of ASCII digits — with [separator] between its groups.
     * The rightmost group is always three digits; the ones left of it are
     * three ([NumberGrouping.WESTERN]) or two ([NumberGrouping.SOUTH_ASIAN]).
     * [NumberGrouping.AUTO] has no grouping of its own and is read as Western;
     * resolve it with [styleFor] first.
     */
    fun group(
        digits: String,
        style: NumberGrouping,
        separator: Char = ',',
    ): String {
        if (digits.length <= 3) return digits
        val step = if (style == NumberGrouping.SOUTH_ASIAN) 2 else 3
        val groups = ArrayDeque<String>()
        var index = digits.length - 3
        groups.addFirst(digits.substring(index))
        while (index > 0) {
            val start = (index - step).coerceAtLeast(0)
            groups.addFirst(digits.substring(start, index))
            index = start
        }
        return groups.joinToString(separator.toString())
    }
}
