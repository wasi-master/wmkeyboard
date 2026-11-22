package com.wasimaster.wmkeyboard.core.text

/**
 * The rule every text search follows once it can match other forms of a
 * word ("running" finds "run", "chats" finds "chat"): a result the query
 * matches as typed comes first, in the order the search would show it
 * anyway, and a result only a stem matches comes after.
 *
 * The stems themselves come from the engine's light stemmers
 * (`SearchStems` in core:language, `native/wm-engine/src/stem`): a text's
 * words lowercased, stemmed in its language and joined by single spaces.
 * This file only compares them, so it has no native code to load and the
 * ranking rule is tested on its own.
 */
object StemMatch {

    /** The words of a stemmed text. */
    fun words(stems: String): List<String> = if (stems.isBlank()) emptyList() else stems.split(' ')

    /**
     * Whether every word of [queryStems] is a word of [textStems]: the query
     * "running shoes" (stems "run shoe") matches "a shoe to run in". A query
     * with no words matches nothing here; the literal search answers it.
     */
    fun containsAll(queryStems: String, textStems: String): Boolean {
        val query = words(queryStems)
        if (query.isEmpty()) return false
        val text = words(textStems)
        if (text.isEmpty()) return false
        val set = if (text.size > SMALL) text.toHashSet() else text
        return query.all { it in set }
    }

    /**
     * [items] filtered: first those [literal] accepts, then those only
     * [stemmed] accepts, each in the order [items] holds them. [stemmed] is
     * asked only about items [literal] turned down. With no [stemmed] it is
     * the plain filter.
     */
    fun <T> literalFirst(items: List<T>, literal: (T) -> Boolean, stemmed: ((T) -> Boolean)?): List<T> {
        if (stemmed == null) return items.filter(literal)
        val exact = ArrayList<T>()
        val forms = ArrayList<T>()
        for (item in items) {
            when {
                literal(item) -> exact.add(item)
                stemmed(item) -> forms.add(item)
            }
        }
        return if (forms.isEmpty()) exact else exact + forms
    }

    /** Past this many words a set beats scanning the list for each query word. */
    private const val SMALL = 8
}
