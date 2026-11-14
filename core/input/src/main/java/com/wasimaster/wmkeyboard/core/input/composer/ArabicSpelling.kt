package com.wasimaster.wmkeyboard.core.input.composer

// Outside the object: its own members are not in scope in its supertype's arguments.
private const val AR_PACK_ID = "hunspell_ar"

/**
 * The Hunspell dictionary that checks Arabic by its rules: Ayaspell (hunspell-ar), as
 * LibreOffice ships it, the [PACK_ID] pack, 1.5 MB, 7.3 MB unpacked. Its 466,000 stems
 * take prefixes (و، ف، ب، ال) and suffixes in many combinations, far past the 600,000
 * forms an import unfolds, which is why it comes as a pack for the native library. On a
 * Mac it opened in 0.3 seconds and took 26 MB, and suggestions took 50-75 ms. Its `.aff`
 * ignores tashkeel, so a word with its marks is checked as the word without them.
 */
object ArabicSpelling : HunspellRulesPack(AR_PACK_ID, "ar", "ar.aff", "ar.dic") {
    const val PACK_ID = AR_PACK_ID
}
