package com.wasimaster.wmkeyboard.core.selection

/**
 * How the selection macro settings are stored, kept apart from the
 * repository so the version rule has a unit test.
 *
 * Both lists are stamped with [SelectionMacros.LIST_VERSION]. A list stored
 * under an older version keeps every choice in it: what the user switched
 * off stays off, what they switched on stays on, and their order stands.
 * What the newer build adds to the shipped set is switched on as well, and
 * "added" is read off the stored order: that list names every action the
 * build that wrote it had (the repository rewrites it in full on every
 * list write), so an action the shipped set has and the stored order does
 * not is new to this user (#1125). Lists from before [MERGE_FROM], or none
 * at all, read as the shipped list. Names this build does not know are
 * dropped rather than failing the whole list.
 */
object SelectionMacroCodec {

    private const val SEPARATOR = "\t"

    /**
     * The oldest list version merged rather than replaced. Version 2 is the
     * build that grew the bar from thirteen actions to forty-five, and its
     * one reset was asked for; lists from before it read as shipped.
     */
    const val MERGE_FROM = 2

    /**
     * The stored on-list, with what the shipped set gained since [storedOrder]
     * was written. [storedOrder] is the encoded order stored beside it.
     */
    fun decodeMacros(storedVersion: Int?, names: Set<String>?, storedOrder: String? = null): Set<SelectionMacro> {
        if (names == null || storedVersion == null || storedVersion < MERGE_FROM) return SelectionMacros.defaultMacros
        val kept = names.mapNotNullTo(mutableSetOf()) { name -> runCatching { SelectionMacro.valueOf(name) }.getOrNull() }
            .filterTo(mutableSetOf()) { it in SelectionMacros.configurable }
        if (storedVersion >= SelectionMacros.LIST_VERSION) return kept
        val known = parseOrder(storedOrder).toSet()
        // Without the order there is no telling new from switched off, and
        // switching back on what the user turned off is the worse mistake.
        if (known.isEmpty()) return kept
        return kept + SelectionMacros.defaultMacros.filter { it in SelectionMacros.defaultOrder && it !in known }
    }

    fun encodeMacros(macros: Set<SelectionMacro>): Set<String> =
        macros.filter { it in SelectionMacros.configurable }.mapTo(mutableSetOf()) { it.name }

    /**
     * The stored order followed by whatever it does not name, in shipped
     * order, so a macro added later has a place instead of vanishing.
     */
    fun decodeOrder(storedVersion: Int?, encoded: String?): List<SelectionMacro> {
        if (storedVersion == null || storedVersion < MERGE_FROM) return SelectionMacros.defaultOrder
        val stored = parseOrder(encoded)
        if (stored.isEmpty()) return SelectionMacros.defaultOrder
        return stored + SelectionMacros.defaultOrder.filter { it !in stored }
    }

    fun encodeOrder(order: List<SelectionMacro>): String = order.joinToString(SEPARATOR) { it.name }

    private fun parseOrder(encoded: String?): List<SelectionMacro> =
        encoded.orEmpty().split(SEPARATOR)
            .mapNotNull { name -> runCatching { SelectionMacro.valueOf(name) }.getOrNull() }
            .filter { it in SelectionMacros.configurable && it !in SelectionMacros.ladderOnly }
            .distinct()
}
