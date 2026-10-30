package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Hanja for a Hangul word, read from [CjkDictionaries.hanja].
 *
 * Korean spells its endings onto the word: 학교에 is 학교 with a particle, and the
 * table lists only 학교. So a word with no entry of its own falls back to its
 * longest prefix of [MIN_PREFIX] syllables or more that has one, with the rest
 * left in Hangul (學校에). A single syllable is not tried as a prefix: nearly every
 * syllable has entries, and they would answer for words they have no part in.
 */
object HanjaConversion {

    private const val MIN_PREFIX = 2

    fun candidates(word: String, limit: Int): List<String> {
        val table = CjkDictionaries.hanja
        if (table.isEmpty || word.isEmpty() || !word.all(::isHangulSyllable)) return emptyList()
        table.exact(word).takeIf { it.isNotEmpty() }?.let { return it.take(limit) }
        for (end in word.length - 1 downTo MIN_PREFIX) {
            val found = table.exact(word.substring(0, end))
            if (found.isNotEmpty()) return found.take(limit).map { it + word.substring(end) }
        }
        return emptyList()
    }

    /** Whether [text] has Han in it, which is what tells a Hanja chip from a Korean word. */
    fun isHanja(text: String): Boolean = text.codePoints().anyMatch { cp ->
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
    }

    private fun isHangulSyllable(c: Char) = c in '가'..'힣'
}
