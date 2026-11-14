package com.wasimaster.wmkeyboard.core.input.composer

// Outside the object: its own members are not in scope in its supertype's arguments.
private const val TR_PACK_ID = "hunspell_tr"

/**
 * The Hunspell dictionary that lets Turkish be checked by its rules rather than its word
 * list, since no list holds every suffix a Turkish word can take: hunspell-tr from Turkish
 * Data Depository (MPL-2.0), the [PACK_ID] pack, 14.5 MB, 38 MB unpacked. On a Mac it
 * opened in 0.6 seconds and took 32 MB.
 */
object TurkishSpelling : HunspellRulesPack(TR_PACK_ID, "tr", "tr_TR.aff", "tr_TR.dic") {
    const val PACK_ID = TR_PACK_ID
}
