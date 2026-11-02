package com.wasimaster.wmkeyboard.core.tools

import java.util.Locale

/** Finding a character by its Unicode name or its code, for the symbols tool's Unicode tab (#1038). */
object UnicodeSearch {

    /** A word like `arrow` matches hundreds; the grid is for picking from, not for reading through. */
    const val MAX_RESULTS = 200

    private val CODE = Regex("(?i)(?:u\\+|0x)?([0-9a-f]{4,6})")
    private val PREFIXED = Regex("(?i)(?:u\\+|0x)([0-9a-f]{1,6})")

    /** The code point [query] reads as: `U+41`, `0x2192`, or a bare `2192`. Null when it does not. */
    fun codePoint(query: String): Int? {
        val text = query.trim()
        val digits = (PREFIXED.matchEntire(text) ?: CODE.matchEntire(text))?.groupValues?.get(1) ?: return null
        return digits.toInt(16).takeIf { it <= Character.MAX_CODE_POINT && Character.isDefined(it) }
    }

    /**
     * The characters out of [points] whose name holds every word of [query],
     * with the one it spells as a code first. [check] runs for every character
     * and may throw, to give up on a query that has been typed past.
     */
    fun search(query: String, points: IntArray, check: () -> Unit = {}): List<Int> {
        val words = query.trim().uppercase(Locale.ROOT).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        val coded = codePoint(query)
        coded?.let(out::add)
        for (cp in points) {
            check()
            if (out.size >= MAX_RESULTS) break
            if (cp == coded || !hasRealName(cp)) continue
            val name = Character.getName(cp) ?: continue
            if (words.all { it in name }) out.add(cp)
        }
        return out
    }

    /**
     * False for the ideographs and Hangul syllables, whose names are built from
     * their codes: `CJK UNIFIED IDEOGRAPHS-4E00` says nothing, and there are
     * a hundred thousand of them to get through.
     */
    private fun hasRealName(cp: Int): Boolean = !Character.isIdeographic(cp) && cp !in 0xAC00..0xD7A3

    /** What a held character is: `U+2192 RIGHTWARDS ARROW`. */
    fun caption(cp: Int): String =
        String.format(Locale.ROOT, "U+%04X %s", cp, Character.getName(cp).orEmpty()).trim()
}
