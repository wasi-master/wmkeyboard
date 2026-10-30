package com.wasimaster.wmkeyboard.core.selection

/**
 * Rewrites a selection in a language's other alphabet, whichever way it is written
 * now: Serbian Cyrillic and Gaj's Latin, Uzbek Cyrillic and the official Latin of
 * 1995. Both are published tables, so nothing here needs a dictionary, and only a few
 * Serbian words where a Latin digraph is really two letters need an exception.
 */
object ScriptSwitch {

    /** The languages this can rewrite, by `LanguageDef.id`. */
    val languages: Set<String> = setOf("sr", "uz")

    /** [text] in the other alphabet of [languageId], going by which alphabet most of it is in. */
    fun convert(languageId: String, text: String): String {
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        val latin = text.count { it.isLatinLetter() }
        return when (languageId) {
            "sr" -> if (cyrillic >= latin) serbianToLatin(text) else eachWord(text, ::serbianWordToCyrillic)
            "uz" -> if (cyrillic >= latin) uzbekToLatin(text) else eachWord(text, ::uzbekWordToCyrillic)
            else -> text
        }
    }

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z' || this in 'À'..'ɏ'

    /** Leaves alone what is not a word of the language: an address, or a word with a letter its alphabet doesn't have. */
    private fun eachWord(text: String, word: (String) -> String): String = buildString(text.length) {
        val token = StringBuilder()
        fun flush() {
            if (token.isEmpty()) return
            val t = token.toString()
            val foreign = "://" in t || '@' in t || t.startsWith("www.", ignoreCase = true) || t.any { it.isDigit() }
            append(if (foreign) t else word(t))
            token.clear()
        }
        for (c in text) {
            if (c.isWhitespace()) {
                flush()
                append(c)
            } else {
                token.append(c)
            }
        }
        flush()
    }

    /** A cased pair of [lower] for the letter [source] at [at]: all capitals when a neighbour is a capital too. */
    private fun cased(lower: String, source: String, at: Int): String {
        if (!source[at].isUpperCase()) return lower
        val shouting = source.getOrNull(at + 1)?.isUpperCase() == true || source.getOrNull(at - 1)?.isUpperCase() == true
        return if (shouting) lower.uppercase() else lower.replaceFirstChar { it.uppercase() }
    }

    // Serbian

    private val SERBIAN_TO_LATIN = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ", 'е' to "e", 'ж' to "ž", 'з' to "z", 'и' to "i",
        'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "č", 'џ' to "dž", 'ш' to "š",
    )

    private val SERBIAN_TO_CYRILLIC = SERBIAN_TO_LATIN.entries.filter { it.value.length == 1 }.associate { (k, v) -> v[0] to k }

    /** Stems where lj, nj or dž is two letters: инјекција, not ињекција. */
    private val SERBIAN_TWO_LETTERS = listOf("inje", "konjug", "konjunk", "tanjug", "vanjezi", "nadž", "podž", "odžal", "odžive", "predžel")

    private fun serbianToLatin(text: String): String = buildString(text.length) {
        text.forEachIndexed { i, c ->
            val latin = SERBIAN_TO_LATIN[c.lowercaseChar()]
            if (latin == null) append(c) else append(cased(latin, text, i))
        }
    }

    private fun serbianWordToCyrillic(word: String): String {
        // A letter Serbian's alphabet doesn't have means another language's word.
        if (word.any { it in "qwxyQWXY" }) return word
        val lower = word.lowercase()
        val apart = SERBIAN_TWO_LETTERS.firstOrNull { lower.startsWith(it) }
        return buildString(word.length) {
            var i = 0
            while (i < word.length) {
                val pair = lower.substring(i, minOf(i + 2, lower.length))
                val digraph = when (pair) {
                    "lj" -> 'љ'
                    "nj" -> 'њ'
                    "dž" -> 'џ'
                    else -> null
                }
                val keptApart = apart != null && i + 2 <= apart.length && pair == apart.substring(i, i + 2)
                if (digraph != null && !keptApart) {
                    append(if (word[i].isUpperCase()) digraph.uppercaseChar() else digraph)
                    i += 2
                    continue
                }
                val mapped = SERBIAN_TO_CYRILLIC[word[i].lowercaseChar()]
                append(if (mapped == null) word[i] else if (word[i].isUpperCase()) mapped.uppercaseChar() else mapped)
                i++
            }
        }
    }

    // Uzbek

    private const val UZBEK_VOWELS_CYRILLIC = "аеёиоуэюяў"
    private const val UZBEK_VOWELS_LATIN = "aeiou"
    private const val APOSTROPHES = "ʻʼ'’‘`"

    private val UZBEK_TO_LATIN = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ё' to "yo", 'ж' to "j", 'з' to "z", 'и' to "i", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "x", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "ʼ", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'ў' to "oʻ", 'қ' to "q", 'ғ' to "gʻ", 'ҳ' to "h",
    )

    private val UZBEK_TO_CYRILLIC = mapOf(
        'a' to 'а', 'b' to 'б', 'd' to 'д', 'f' to 'ф', 'g' to 'г', 'h' to 'ҳ', 'i' to 'и', 'j' to 'ж', 'k' to 'к', 'l' to 'л',
        'm' to 'м', 'n' to 'н', 'o' to 'о', 'p' to 'п', 'q' to 'қ', 'r' to 'р', 's' to 'с', 't' to 'т', 'u' to 'у', 'v' to 'в',
        'x' to 'х', 'y' to 'й', 'z' to 'з',
    )

    private val UZBEK_DIGRAPHS = mapOf("sh" to 'ш', "ch" to 'ч', "yo" to 'ё', "yu" to 'ю', "ya" to 'я', "ye" to 'е', "ts" to 'ц')

    private fun uzbekToLatin(text: String): String = buildString(text.length + text.length / 8) {
        text.forEachIndexed { i, c ->
            val lower = c.lowercaseChar()
            val before = text.getOrNull(i - 1)?.lowercaseChar()
            val startsWord = before == null || !before.isLetter()
            val latin = when (lower) {
                // Е is ye where it starts a syllable, ц is ts between vowels: the two the table can't say alone.
                'е' -> if (startsWord || before in UZBEK_VOWELS_CYRILLIC || before == 'ъ' || before == 'ь') "ye" else "e"
                'ц' -> if (!startsWord && before in UZBEK_VOWELS_CYRILLIC) "ts" else "s"
                else -> UZBEK_TO_LATIN[lower]
            }
            if (latin == null) append(c) else append(cased(latin, text, i))
        }
    }

    private fun uzbekWordToCyrillic(word: String): String {
        if (word.any { it in "cwCW" }) return word
        val lower = word.lowercase()
        return buildString(word.length) {
            var i = 0
            while (i < word.length) {
                val c = lower[i]
                val capital = word[i].isUpperCase()
                val next = lower.getOrNull(i + 1)
                val out: Char
                var used = 1
                if ((c == 'o' || c == 'g') && next != null && next in APOSTROPHES) {
                    out = if (c == 'o') 'ў' else 'ғ'
                    used = 2
                } else if (c in APOSTROPHES) {
                    // Between letters it is the tutuq belgisi; around a word it is a quotation mark.
                    val inWord = i > 0 && next != null && next.isLetter()
                    out = if (inWord) 'ъ' else word[i]
                } else {
                    val digraph = next?.let { UZBEK_DIGRAPHS["$c$it"] }
                    if (digraph != null) {
                        out = digraph
                        used = 2
                    } else if (c == 'e') {
                        val starts = i == 0 || !lower[i - 1].isLetter() || lower[i - 1] in UZBEK_VOWELS_LATIN
                        out = if (starts) 'э' else 'е'
                    } else {
                        out = UZBEK_TO_CYRILLIC[c] ?: word[i]
                    }
                }
                append(if (capital) out.uppercaseChar() else out)
                i += used
            }
        }
    }
}
