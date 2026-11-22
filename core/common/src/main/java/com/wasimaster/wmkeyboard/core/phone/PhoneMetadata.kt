package com.wasimaster.wmkeyboard.core.phone

/**
 * What kind of line a number is, as far as its country's numbering plan says.
 *
 * [FIXED_LINE_OR_MOBILE] is the North American case and a few others, where
 * the same ranges hold both and the number alone cannot tell them apart.
 * [OTHER] is a real number of some other type: toll-free, premium, shared
 * cost, VoIP, personal, pager, UAN or voicemail. [UNKNOWN] is not a number
 * the plan has, which is what [PhoneNumbers.isValid] turns down.
 */
enum class PhoneType {
    MOBILE,
    FIXED_LINE,
    FIXED_LINE_OR_MOBILE,
    OTHER,
    UNKNOWN;

    /** Whether a text message can reach it: what people copy a number for. */
    val canBeMobile: Boolean get() = this == MOBILE || this == FIXED_LINE_OR_MOBILE
}

/**
 * One region's numbering plan, cut down to what validity needs. A row of
 * `phone/phone_meta.tsv`, built by `tools/phonemeta/build_phone_meta.py` from
 * libphonenumber's metadata; that script's docstring is the format.
 *
 * Patterns are compiled on first use, so the 245 regions cost one string each
 * until a number from one of them is actually looked at.
 */
class PhoneRegionPlan internal constructor(
    /** CLDR region code, upper case: `BD`, `US`. */
    val region: String,
    /** The dial code without its `+`: `880`, `1`. */
    val countryCode: String,
    private val internationalPrefix: String,
    /** The trunk prefix written in front of a national number: `0` mostly, `1` in NANP, `8` in Russia. */
    val nationalPrefix: String,
    private val prefixForParsing: String,
    private val prefixTransform: String,
    private val leadingDigits: String,
    /** The region a shared dial code means when nothing else decides: `US` for `+1`, `GB` for `+44`. */
    val isMainForCode: Boolean,
    private val lengths: Long,
    private val general: String,
    private val fixed: String,
    private val mobile: String,
    private val other: String,
) {
    private val generalRegex by lazy { Regex(general) }
    private val fixedRegex by lazy { fixed.takeIf { it.isNotEmpty() }?.let(::Regex) }
    private val mobileRegex by lazy {
        when (mobile) {
            "" -> null
            SAME_AS_FIXED -> fixedRegex
            else -> Regex(mobile)
        }
    }
    private val otherRegex by lazy { other.takeIf { it.isNotEmpty() }?.let(::Regex) }
    private val leadingRegex by lazy { leadingDigits.takeIf { it.isNotEmpty() }?.let { Regex("^(?:$it)") } }
    private val internationalRegex by lazy {
        internationalPrefix.takeIf { it.isNotEmpty() }?.let { Regex("^(?:$it)") }
    }
    private val nationalPrefixRegex by lazy {
        when {
            prefixForParsing.isNotEmpty() -> Regex("^(?:$prefixForParsing)")
            nationalPrefix.isNotEmpty() -> Regex("^" + Regex.escape(nationalPrefix))
            else -> null
        }
    }

    /** Whether a national number has a length any of this region's numbers have. */
    fun isPossibleLength(national: String): Boolean =
        national.length in 1..MAX_LENGTH && lengths and (1L shl national.length) != 0L

    /** Whether [national] fits the region's general pattern: the loose test, before any type. */
    fun matchesGeneral(national: String): Boolean = generalRegex.matches(national)

    /**
     * The type of [national], a number already stripped of dial code and
     * trunk prefix. [PhoneType.UNKNOWN] when it has no possible length, or
     * fits no type the plan names.
     */
    fun typeOf(national: String): PhoneType {
        if (!isPossibleLength(national) || !matchesGeneral(national)) return PhoneType.UNKNOWN
        val isFixed = fixedRegex?.matches(national) == true
        val isMobile = mobileRegex?.matches(national) == true
        return when {
            isFixed && isMobile -> PhoneType.FIXED_LINE_OR_MOBILE
            isFixed -> PhoneType.FIXED_LINE
            isMobile -> PhoneType.MOBILE
            otherRegex?.matches(national) == true -> PhoneType.OTHER
            else -> PhoneType.UNKNOWN
        }
    }

    /** Whether this region claims [national] by its leading digits, for a dial code it shares. */
    internal fun claimsByLeadingDigits(national: String): Boolean? =
        leadingRegex?.let { it.find(national) != null }

    /**
     * [digits] with this region's international prefix taken off the front
     * (`00`, `011`, `810`), or null when it does not start with one.
     * libphonenumber's rule: what follows a prefix never starts with `0`.
     */
    internal fun stripInternationalPrefix(digits: String): String? {
        val match = internationalRegex?.find(digits) ?: return null
        val rest = digits.substring(match.range.last + 1)
        return rest.takeIf { it.isNotEmpty() && it[0] != '0' }
    }

    /**
     * [number] with the trunk prefix taken off the way libphonenumber's
     * `maybeStripNationalPrefixAndCarrierCode` does it: through the region's
     * parsing rule and its transform where it has one (Argentina's `15`,
     * Mexico's old `1`), and only when what is left still fits the plan, or
     * the whole thing never did. Anything else comes back unchanged.
     */
    internal fun stripNationalPrefix(number: String): String {
        val match = nationalPrefixRegex?.find(number) ?: return number
        val viable = matchesGeneral(number)
        val rest = number.substring(match.range.last + 1)
        val lastGroup = match.groups[match.groups.size - 1]
        val stripped = if (prefixTransform.isEmpty() || lastGroup == null) {
            rest
        } else {
            expand(prefixTransform, match) + rest
        }
        if (stripped.isEmpty()) return number
        if (viable && !matchesGeneral(stripped)) return number
        // Too short for any number here: the prefix was part of the number.
        if (stripped.length < shortestLength) return number
        return stripped
    }

    private val shortestLength: Int by lazy {
        (1..MAX_LENGTH).firstOrNull { lengths and (1L shl it) != 0L } ?: 0
    }

    override fun toString(): String = "PhoneRegionPlan($region +$countryCode)"

    internal companion object {
        /** E.164's ceiling, and more than any national number needs. */
        const val MAX_LENGTH = 17

        const val SAME_AS_FIXED = "="

        /** `$1`…`$9` in a transform rule, filled from the prefix match's groups. */
        fun expand(rule: String, match: MatchResult): String = buildString {
            var i = 0
            while (i < rule.length) {
                val ch = rule[i]
                if (ch == '$' && i + 1 < rule.length && rule[i + 1].isDigit()) {
                    append(match.groups[rule[i + 1] - '0']?.value.orEmpty())
                    i += 2
                } else {
                    append(ch)
                    i++
                }
            }
        }

        /** `6-10,12` → a bit per length. */
        fun lengthBits(spec: String): Long {
            var bits = 0L
            for (part in spec.split(',')) {
                if (part.isBlank()) continue
                val dash = part.indexOf('-')
                val low = (if (dash < 0) part else part.substring(0, dash)).trim().toIntOrNull() ?: continue
                val high = (if (dash < 0) part else part.substring(dash + 1)).trim().toIntOrNull() ?: continue
                for (length in low..high) if (length in 1..MAX_LENGTH) bits = bits or (1L shl length)
            }
            return bits
        }
    }
}

/**
 * Every region's numbering plan, read from `phone/phone_meta.tsv`.
 *
 * The data is libphonenumber's (Apache-2.0; release and notice in the file's
 * header), trimmed to validity: no formatting rules, no geocoding, carriers or
 * time zones. Formatting is the OS's own copy of libphonenumber, through
 * [PhoneNumbers.format]; this table answers the one question the OS does not
 * expose, whether a number is real in a region and which kind it is.
 */
class PhoneMetadata private constructor(
    private val byRegion: Map<String, PhoneRegionPlan>,
    private val byCode: Map<String, List<PhoneRegionPlan>>,
) {
    /** How many regions the table holds. */
    val size: Int get() = byRegion.size

    /** Every region code the table knows. */
    val regions: Set<String> get() = byRegion.keys

    /** The plan for [region], any case, or null for one the table does not hold. */
    fun plan(region: String?): PhoneRegionPlan? = region?.let { byRegion[it.uppercase()] }

    /** The regions sharing a dial code, the main one first; empty for a code no one uses. */
    fun plansFor(countryCode: String): List<PhoneRegionPlan> = byCode[countryCode].orEmpty()

    /**
     * Which region a number under a shared dial code belongs to, by
     * libphonenumber's rule: a region with leading digits claims what starts
     * with them, any other claims what is a real number in its plan, main
     * region first. Null when none does, which makes the number invalid.
     */
    fun regionFor(countryCode: String, national: String): PhoneRegionPlan? {
        val plans = plansFor(countryCode)
        if (plans.size == 1) return plans.single()
        for (plan in plans) {
            val claimed = plan.claimsByLeadingDigits(national)
            if (claimed == true) return plan
            if (claimed == null && plan.typeOf(national) != PhoneType.UNKNOWN) return plan
        }
        return null
    }

    companion object {
        /** Where the table lives in the APK's assets. */
        const val ASSET = "phone/phone_meta.tsv"

        private const val COLUMNS = 14

        /**
         * Reads the table. Comment lines start with `#`; a row with too few
         * cells (a newer file with a column this build does not know is fine,
         * fewer is not) is skipped rather than failing the whole table.
         */
        fun parse(text: String): PhoneMetadata {
            val byRegion = LinkedHashMap<String, PhoneRegionPlan>()
            for (line in text.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = line.split('\t')
                if (cells.size < COLUMNS) continue
                val plan = PhoneRegionPlan(
                    region = cells[0],
                    countryCode = cells[1],
                    internationalPrefix = cells[2],
                    nationalPrefix = cells[3],
                    prefixForParsing = cells[4],
                    prefixTransform = cells[5],
                    leadingDigits = cells[6],
                    isMainForCode = cells[7] == "1",
                    lengths = PhoneRegionPlan.lengthBits(cells[8]),
                    general = cells[10],
                    fixed = cells[11],
                    mobile = cells[12],
                    other = cells[13],
                )
                byRegion[plan.region] = plan
            }
            val byCode = byRegion.values
                .groupBy { it.countryCode }
                .mapValues { (_, plans) -> plans.sortedByDescending { it.isMainForCode } }
            return PhoneMetadata(byRegion, byCode)
        }
    }
}
