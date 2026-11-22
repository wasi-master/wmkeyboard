package com.wasimaster.wmkeyboard.core.addons

/**
 * Which languages of a localized addon a one-tap install should fetch.
 *
 * The catalogue card's download arrow has no room to ask, so it guesses the way
 * a person would: one list for every keyboard language the user has turned on
 * that the addon speaks, and within a language the region their phone is set
 * to. Someone typing English and Spanish on a phone set to `es-MX` gets the
 * English list and the Latin American Spanish one. The addon's own page lists
 * every language for anything the guess got wrong.
 */
object AddonLocales {

    /**
     * Countries whose Spanish or Portuguese is grouped under UN M.49 code 419,
     * "Latin America and the Caribbean". `US` is included because Android ships
     * Latin American Spanish as `es-US`; it only matters for a language that
     * has a `-419` list in the first place.
     */
    private val LATIN_AMERICA = setOf(
        "AR", "BO", "BR", "BZ", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN",
        "MX", "NI", "PA", "PE", "PR", "PY", "SV", "US", "UY", "VE",
    )

    /**
     * The variants to install for [entry].
     *
     * [enabledLangIds] are the keyboard's languages in switch order and
     * [deviceLocales] the phone's locales as BCP 47 tags (`es-MX`), most
     * preferred first. Falls back to the base list alone when the addon speaks
     * none of the user's languages, because tapping the arrow should install
     * something.
     */
    fun pick(entry: AddonEntry, enabledLangIds: List<String>, deviceLocales: List<String>): List<AddonEntry> {
        val variants = entry.installables
        if (variants.size == 1) return variants
        val picked = enabledLangIds.distinct().mapNotNull { lang ->
            bestForLanguage(variants.filter { it.langId == lang }, lang, deviceLocales)
        }
        return picked.ifEmpty { listOf(entry) }
    }

    /** One variant out of [candidates], all in [lang], by the phone's region for it. */
    private fun bestForLanguage(
        candidates: List<AddonEntry>,
        lang: String,
        deviceLocales: List<String>,
    ): AddonEntry? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        val region = deviceLocales
            .firstOrNull { it.substringBefore('-').equals(lang, ignoreCase = true) }
            ?.let(::regionOf)
            .orEmpty()
        fun withRegion(value: String) = candidates.firstOrNull { regionOf(it.locale) == value }
        return region.takeIf { it.isNotEmpty() }?.let(::withRegion)
            ?: withRegion("419").takeIf { region in LATIN_AMERICA }
            ?: withRegion("")
            ?: candidates.first()
    }

    /**
     * The region of a BCP 47 tag, upper-cased, or blank. Skips a script subtag
     * (`zh-Hant-TW` → `TW`): a region is two letters or three digits.
     */
    fun regionOf(tag: String): String = tag.split('-').drop(1)
        .firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }
        ?.uppercase()
        .orEmpty()
}
