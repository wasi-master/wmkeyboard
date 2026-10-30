package com.wasimaster.wmkeyboard.core.selection

/**
 * Puts back the ё that Russian typing leaves out (еще to ещё), from the safe list of
 * eyo-kernel (e2yo, MIT): only words with no reading in е, so все and совершенный stay.
 * [dictionary] is that list as published: one word a line, endings in brackets, a word
 * that is lower case only starting with an underscore, a comment after `#`.
 */
class RussianYo(dictionary: String) {

    private val words = HashMap<String, String>(1 shl 18)

    init {
        for (line in dictionary.lineSequence()) {
            val word = line.substringBefore('#').trim()
            if (word.isEmpty()) continue
            val open = word.indexOf('(')
            if (open < 0) {
                add(word)
            } else {
                val stem = word.substring(0, open)
                word.substring(open + 1, word.lastIndexOf(')')).split('|').forEach { add(stem + it) }
            }
        }
    }

    private fun add(raw: String) {
        val lowerOnly = raw.startsWith('_')
        val word = raw.removePrefix("_")
        val key = plain(word)
        words[key] = word
        if (!lowerOnly && !word[0].isUpperCase()) words[capitalized(key)] = capitalized(word)
    }

    private fun plain(word: String) = word.replace('Ё', 'Е').replace('ё', 'е')

    private fun capitalized(word: String) = word.replaceFirstChar { it.uppercaseChar() }

    /** [text] with each word it can be sure of restored. An abbreviation (мед. училище) is left alone. */
    fun restore(text: String): String = WORD.replace(text) { match -> words[plain(match.value)] ?: match.value }

    private companion object {
        const val PUNCTUATION = """[{}()|<>=_"'«»„“#$^%&*+:;,?!‑\-\[\]]"""
        val WORD = Regex("""[А-ЯЁа-яё][а-яё]+(?![а-яё]|\.[  \t]+(?:[а-яё]|[А-ЯЁ]{2}|$PUNCTUATION)|\.$PUNCTUATION)""")
    }
}
