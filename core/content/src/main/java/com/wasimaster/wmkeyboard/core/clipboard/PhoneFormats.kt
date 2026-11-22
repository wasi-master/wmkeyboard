package com.wasimaster.wmkeyboard.core.clipboard

import com.wasimaster.wmkeyboard.core.phone.PhoneNumbers

/**
 * One phone-number shape, parsed out of a mask the user wrote.
 *
 * [countryCode] is the literal dial code a mask spelled with a leading `+`, and
 * null for a mask written the national way. [national] is the rest, one
 * character per digit: a literal digit that has to match, or [PhoneFormats.ANY]
 * for any digit at all. Separators are not kept — they are decoration in the
 * mask exactly as they are noise in a clip.
 */
data class PhoneMask(
    val countryCode: String?,
    val national: String,
    /**
     * The mask as written, separators and all, which [PhoneFormats.render]
     * copies into the number it writes. Empty for a mask built in code.
     */
    val raw: String = "",
)

/**
 * Phone-number masks: what a number a user actually copies looks like.
 *
 * The plain detector in [ClipEntities] has to guess from shape alone, so it
 * offers any 7-to-15 digit run that is not obviously a date or an order number.
 * That is right for someone who copies numbers from three countries and wrong
 * for everyone else, whose false positives all look the same: an invoice total,
 * a tracking id, a row of figures out of a spreadsheet.
 *
 * A mask is the fix. `+880 1XXX-XXXXXX` says "eleven digits, and the national
 * part starts with a 1"; anything else stops being a phone number. The leeway
 * is deliberate and goes in one direction only:
 *
 *  - Separators are ignored on both sides, so `01712-345678`, `01712 345678`
 *    and `(01712) 345678` are the same number to a mask, whatever the mask
 *    itself was written with.
 *  - A mask with a country code also matches the number without it, and with a
 *    leading trunk `0` in its place. One mask covers `+8801712345678`,
 *    `8801712345678`, `01712345678` and `1712345678`.
 *  - A clip that carries an explicit `+` or `00` has to match a country code,
 *    because it named one. That is what keeps a foreign number out.
 *
 * Masks are a filter, never a source: a number still has to be found by
 * [ClipEntities] first, and every other guard there still applies.
 */
object PhoneFormats {

    /** The mask character for "any digit". */
    const val ANY = 'X'

    /** Also accepted when a mask is written by hand, and folded to [ANY]. */
    private const val ANY_ALIASES = "xX#"

    /** Punctuation people write numbers with, ignored everywhere. */
    private const val SEPARATORS = " -().[]/"

    /** Below this a mask stops naming a number and starts matching digits. */
    private const val MIN_DIGITS = 4

    /** E.164 caps a number at 15 digits. */
    private const val MAX_DIGITS = 15

    /** The national prefix people write in front of a number instead of a dial code. */
    private const val TRUNK = "0"

    /**
     * The two-digit dial codes. E.164 gives zone 1 (North America) and zone 7
     * (Russia and Kazakhstan) one digit, hands two digits to the countries
     * below, and gives everything else three, so these three rules cover every
     * dial code without a 200-row table.
     */
    private val TWO_DIGIT_CODES = setOf(
        20, 27,
        30, 31, 32, 33, 34, 36, 39,
        40, 41, 43, 44, 45, 46, 47, 48, 49,
        51, 52, 53, 54, 55, 56, 57, 58,
        60, 61, 62, 63, 64, 65, 66,
        81, 82, 84, 86,
        90, 91, 92, 93, 94, 95, 98,
    )

    /**
     * The mask [raw] describes, or null when it is not a mask: no digits at
     * all, a character that is neither a digit nor a separator, a length no
     * number has, or a country code written as `X` (a dial code that matches
     * anything is not a dial code).
     */
    fun parse(raw: String): PhoneMask? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val international = trimmed.startsWith("+")
        val body = if (international) trimmed.substring(1) else trimmed
        val template = StringBuilder()
        // Where the country code ends, if the mask separated it off itself.
        var codeEnd = -1
        for (ch in body) {
            when {
                ch.isDigit() -> template.append(ch)
                ch in ANY_ALIASES -> template.append(ANY)
                ch in SEPARATORS ->
                    if (international && codeEnd < 0 && template.isNotEmpty()) {
                        codeEnd = template.length
                    }
                else -> return null
            }
        }
        if (template.length !in MIN_DIGITS..MAX_DIGITS) return null
        val digits = template.toString()
        if (!international) return PhoneMask(null, digits, trimmed)
        val split = if (codeEnd > 0) codeEnd else dialCodeLength(digits)
        val code = digits.take(split)
        if (code.length >= digits.length) return null
        // A dial code spelled `+XX` names no country, so the mask keeps only
        // what follows it and reads as a national one. Nothing renders through
        // it: the `+XX` would have no digits to fill it.
        if (code.any { it == ANY }) return PhoneMask(null, digits.drop(split))
        return PhoneMask(code, digits.drop(split), trimmed)
    }

    /** True when [raw] is a mask this can use. */
    fun isValid(raw: String): Boolean = parse(raw) != null

    /**
     * [raw] as it is stored and shown: trimmed, with `x` and `#` written as
     * [ANY] so two spellings of one mask cannot both sit in the list. The
     * separators are the user's and stay as they typed them. Null when [raw] is
     * not a mask.
     */
    fun canonical(raw: String): String? {
        val trimmed = raw.trim()
        if (!isValid(trimmed)) return null
        return buildString {
            for (ch in trimmed) append(if (ch in ANY_ALIASES) ANY else ch)
        }
    }

    /** The masks in [raw] that parse; the rest are settings from a newer build. */
    fun parseAll(raw: Collection<String>): List<PhoneMask> = raw.mapNotNull(::parse)

    /**
     * Turns a number the user recognises into a mask: the dial code stays
     * literal, every other digit becomes [ANY], and the separators are kept so
     * the mask still looks like the number it came from.
     *
     * Returns null when [example] is not a number. The result is a starting
     * point that the user can edit — typing a digit back into it pins that
     * position, which is how `+880 1XXX-XXXXXX` gets its 1.
     */
    fun fromExample(example: String): String? {
        val trimmed = example.trim()
        if (trimmed.isEmpty()) return null
        val international = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val codeLength = if (international) dialCodeLength(digits) else 0
        val mask = StringBuilder()
        var seen = 0
        for (ch in trimmed) {
            when {
                ch.isDigit() -> {
                    mask.append(if (seen < codeLength) ch else ANY)
                    seen++
                }
                ch == '+' && mask.isEmpty() -> mask.append('+')
                ch in SEPARATORS -> mask.append(ch)
                // A letter or a currency sign says this was never a number.
                else -> return null
            }
        }
        val result = mask.toString()
        return if (isValid(result)) result else null
    }

    /**
     * Whether [raw] — a digit run as it was found in a clip, separators and
     * all — is a number the user said they copy.
     *
     * An empty [masks] means the user set no formats, so every number passes.
     */
    fun matches(raw: String, masks: List<PhoneMask>): Boolean {
        if (masks.isEmpty()) return true
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        // `00` is the same statement as `+`: what follows names a country.
        val hasZeroZero = trimmed.startsWith("00") && digits.length >= MIN_DIGITS + 2
        val international = trimmed.startsWith("+") || hasZeroZero
        val full = if (hasZeroZero) digits.substring(2) else digits
        return masks.any { mask -> matches(digits, full, international, mask) }
    }

    private fun matches(
        digits: String,
        full: String,
        international: Boolean,
        mask: PhoneMask,
    ): Boolean {
        if (international) {
            // The clip named a country, so the mask has to agree with it.
            return if (mask.countryCode != null) {
                fits(full, mask.countryCode + mask.national)
            } else {
                // A national mask has nothing to say about dial codes, so the
                // clip's own code is taken off and the rest is compared.
                fitsNational(full.drop(dialCodeLength(full)), mask.national)
            }
        }
        if (fitsNational(digits, mask.national)) return true
        // A number pasted with its dial code but no `+`.
        return mask.countryCode != null && fits(digits, mask.countryCode + mask.national)
    }

    /**
     * The national part, allowing for the trunk prefix that people write in
     * front of a number or leave off. Both spellings are the same number, and
     * a mask written either way accepts both.
     *
     * The zero is only ever added to the side that does not have it. Adding it
     * to both would make a mask of nine wildcards accept ten digits as well,
     * which is the vagueness this whole file exists to remove.
     */
    private fun fitsNational(value: String, template: String): Boolean {
        if (fits(value, template)) return true
        return if (template.startsWith(TRUNK)) {
            fits(TRUNK + value, template)
        } else {
            value.startsWith(TRUNK) && fits(value.drop(TRUNK.length), template)
        }
    }

    /** Whether a run of digits fills a template of literals and [ANY]. */
    private fun fits(value: String, template: String): Boolean {
        if (value.length != template.length) return false
        for (index in template.indices) {
            val expected = template[index]
            if (expected != ANY && expected != value[index]) return false
        }
        return true
    }

    /**
     * The mask a phone in [regionCode] starts out with, or null for a region
     * this has nothing to say about.
     *
     * A mask is only worth having when it is the user's own country, so the
     * first one is worked out from the device's region the same way the
     * calendar tool picks its alternate calendars. It is a starting point and
     * nothing else: the user can edit it, delete it, or add the second country
     * they are always copying numbers from, and once they touch the list this
     * never runs again.
     *
     * The table below is mobile numbers, because those are what people copy.
     * A region whose landlines are shorter or longer than its mobiles will drop
     * them, and a region whose numbers have no one length at all — Germany and
     * Austria, where the subscriber part runs from three digits to nine — is
     * left out entirely rather than given a mask that is wrong more often than
     * right. Every one of those cases ends the same way: the user deletes the
     * seeded mask and the detector goes back to offering every number it sees.
     */
    fun forRegion(regionCode: String?): String? = when (regionCode?.uppercase()) {
        // North America. One plan, one shape, twenty-odd territories.
        "US", "CA", "AG", "AI", "BB", "BM", "BS", "DM", "DO", "GD", "JM", "KN",
        "KY", "LC", "MS", "PR", "SX", "TC", "TT", "VC", "VG", "VI",
        -> "+1 XXX-XXX-XXXX"
        // South Asia.
        "BD" -> "+880 XXXX-XXXXXX"
        "IN" -> "+91 XXXXX-XXXXX"
        "PK" -> "+92 XXX-XXXXXXX"
        "NP" -> "+977 XXX-XXXXXXX"
        "LK" -> "+94 XX-XXXXXXX"
        "BT" -> "+975 XX-XXXXXX"
        "MV" -> "+960 XXX-XXXX"
        "AF" -> "+93 XX-XXXXXXX"
        // East and South-East Asia.
        "CN" -> "+86 XXX-XXXX-XXXX"
        "JP" -> "+81 XX-XXXX-XXXX"
        "KR" -> "+82 XX-XXXX-XXXX"
        "TW" -> "+886 XXX-XXX-XXX"
        "HK" -> "+852 XXXX-XXXX"
        "MO" -> "+853 XXXX-XXXX"
        "SG" -> "+65 XXXX-XXXX"
        "MY" -> "+60 XX-XXX-XXXX"
        "TH" -> "+66 XX-XXX-XXXX"
        "VN" -> "+84 XXX-XXX-XXX"
        "PH" -> "+63 XXX-XXX-XXXX"
        "KH" -> "+855 XX-XXX-XXX"
        "MM" -> "+95 XX-XXX-XXXX"
        // Europe.
        "GB" -> "+44 XXXX-XXXXXX"
        "IE" -> "+353 XX-XXX-XXXX"
        "FR" -> "+33 X-XX-XX-XX-XX"
        "ES" -> "+34 XXX-XX-XX-XX"
        "PT" -> "+351 XXX-XXX-XXX"
        "IT" -> "+39 XXX-XXX-XXXX"
        "NL" -> "+31 X-XXXX-XXXX"
        "BE" -> "+32 XXX-XX-XX-XX"
        "CH" -> "+41 XX-XXX-XX-XX"
        "SE" -> "+46 XX-XXX-XX-XX"
        "NO" -> "+47 XXX-XX-XXX"
        "DK" -> "+45 XX-XX-XX-XX"
        "FI" -> "+358 XX-XXX-XXXX"
        "PL" -> "+48 XXX-XXX-XXX"
        "CZ" -> "+420 XXX-XXX-XXX"
        "SK" -> "+421 XXX-XXX-XXX"
        "HU" -> "+36 XX-XXX-XXXX"
        "RO" -> "+40 XXX-XXX-XXX"
        "BG" -> "+359 XXX-XXX-XXX"
        "GR" -> "+30 XXX-XXX-XXXX"
        "HR" -> "+385 XX-XXX-XXXX"
        "RS" -> "+381 XX-XXX-XXXX"
        "UA" -> "+380 XX-XXX-XX-XX"
        "RU", "KZ" -> "+7 XXX-XXX-XX-XX"
        "TR" -> "+90 XXX-XXX-XX-XX"
        // Middle East.
        "SA" -> "+966 XX-XXX-XXXX"
        "AE" -> "+971 XX-XXX-XXXX"
        "QA" -> "+974 XXXX-XXXX"
        "KW" -> "+965 XXXX-XXXX"
        "BH" -> "+973 XXXX-XXXX"
        "OM" -> "+968 XXXX-XXXX"
        "JO" -> "+962 X-XXXX-XXXX"
        "LB" -> "+961 XX-XXX-XXX"
        "IL" -> "+972 XX-XXX-XXXX"
        "IQ" -> "+964 XXX-XXX-XXXX"
        "IR" -> "+98 XXX-XXX-XXXX"
        // Africa.
        "EG" -> "+20 XX-XXXX-XXXX"
        "NG" -> "+234 XXX-XXX-XXXX"
        "KE" -> "+254 XXX-XXXXXX"
        "TZ" -> "+255 XXX-XXX-XXX"
        "UG" -> "+256 XXX-XXXXXX"
        "GH" -> "+233 XX-XXX-XXXX"
        "ZA" -> "+27 XX-XXX-XXXX"
        "MA" -> "+212 XXX-XXXXXX"
        "DZ" -> "+213 XXX-XX-XX-XX"
        "TN" -> "+216 XX-XXX-XXX"
        "ET" -> "+251 XX-XXX-XXXX"
        // Latin America.
        "BR" -> "+55 XX-XXXXX-XXXX"
        "MX" -> "+52 XXX-XXX-XXXX"
        "AR" -> "+54 XXX-XXX-XXXX"
        "CL" -> "+56 X-XXXX-XXXX"
        "CO" -> "+57 XXX-XXX-XXXX"
        "PE" -> "+51 XXX-XXX-XXX"
        "VE" -> "+58 XXX-XXX-XXXX"
        "EC" -> "+593 XX-XXX-XXXX"
        "UY" -> "+598 X-XXX-XXXX"
        "PY" -> "+595 XXX-XXX-XXX"
        "BO" -> "+591 X-XXX-XXXX"
        // Oceania.
        "AU" -> "+61 XXX-XXX-XXX"
        "NZ" -> "+64 XX-XXX-XXXX"
        else -> null
    }

    /**
     * [number] written in the first of [masks] it matches: the mask's
     * separators copied across and its digit slots filled, with the mask's
     * dial code added back when the number was written without one. Null when
     * no mask matches, or the one that does cannot be rendered.
     *
     * The user's masks win over every other formatting: where one matches,
     * the number is shown the way they wrote the mask, not the OS's way.
     */
    fun render(number: String, masks: List<PhoneMask>): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val mask = masks.firstOrNull { it.raw.isNotEmpty() && matches(number, listOf(it)) } ?: return null
        val national = digits.takeLast(mask.national.length)
        if (national.length < mask.national.length) return null
        return fill(mask.raw, mask.countryCode.orEmpty() + national)
    }

    /**
     * [number] as a chip or a list shows it: in the user's mask where one
     * matches ([render]), the way the OS writes numbers for the phone's
     * country otherwise ([PhoneNumbers.format]), and as it was written when
     * neither can say.
     */
    fun display(number: String, masks: List<PhoneMask>): String =
        render(number, masks) ?: PhoneNumbers.format(number) ?: number.trim()

    /**
     * Fills [mask]'s digit slots with [digits], copying everything else
     * across. A slot is a digit or an [ANY] (or one of its aliases); a
     * separator, a bracket or the leading `+` is decoration. Null when the
     * two do not line up.
     */
    private fun fill(mask: String, digits: String): String? {
        val out = StringBuilder(mask.length)
        var next = 0
        for (ch in mask.trim()) {
            if (ch.isDigit() || ch == ANY || ch in ANY_ALIASES) {
                if (next >= digits.length) return null
                out.append(digits[next])
                next++
            } else {
                out.append(ch)
            }
        }
        return if (next == digits.length) out.toString() else null
    }

    /** How many of these digits are the dial code. See [TWO_DIGIT_CODES]. */
    private fun dialCodeLength(digits: String): Int {
        val first = digits.firstOrNull() ?: return 0
        if (first == '1' || first == '7') return 1
        val two = digits.take(2).toIntOrNull() ?: return minOf(3, digits.length)
        return if (two in TWO_DIGIT_CODES) 2 else 3
    }
}
