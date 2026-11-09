package com.wasimaster.wmkeyboard.core.voice

/**
 * Spoken-punctuation post-processing for dictated text: "comma" becomes
 * ",", "দাঁড়ি" becomes "।", "new line" becomes a newline. Applied to
 * final results only (partials stay raw — they are transient anyway).
 *
 * The trade-off is deliberate and Gboard-shaped: a sentence that really
 * contains the word "period" gets a "." instead. The whole pass sits
 * behind a per-tool setting for exactly that reason.
 *
 * The marks beyond the sentence ones keep that trade-off small by their
 * names: a symbol whose plain name is an everyday word is only reachable by
 * the name nobody says by accident ("percent sign", "plus sign", "greater
 * than sign"), so "fifty percent" and "greater than ten" stay words.
 */
object VoicePunctuation {

    /** Which side of a mark the neighbouring words sit tight against. */
    private enum class Join {
        /** Closes up to the word before it: `word, next`. */
        AFTER,

        /** Closes up to the word after it: `word (next`. */
        BEFORE,

        /** Closes up on both sides: `well-known`, and a line break. */
        BOTH,

        /** Stands apart from both: `this & that`. */
        APART,
    }

    private class Rule(phrase: String, val symbol: String, val join: Join) {
        val words: List<String> = phrase.split(' ')
    }

    private fun rules(vararg entries: Triple<String, String, Join>): List<Rule> =
        entries.map { (phrase, symbol, join) -> Rule(phrase, symbol, join) }
            // Longest phrases first, so "question mark" wins over any one-word rule.
            .sortedByDescending { it.words.size }

    private infix fun Pair<String, String>.joined(join: Join) = Triple(first, second, join)

    /**
     * Every way people name the two halves of a pair: "open paren", "left
     * parenthesis", "end parentheses". Recognizers spell the singular and the
     * plural interchangeably, so both are listed.
     */
    private fun paired(
        open: String,
        close: String,
        names: List<String>,
        opens: List<String> = listOf("open", "left", "begin", "start"),
        closes: List<String> = listOf("close", "right", "end"),
    ): Array<Triple<String, String, Join>> =
        (names.flatMap { name -> opens.map { "$it $name" to open joined Join.BEFORE } } +
            names.flatMap { name -> closes.map { "$it $name" to close joined Join.AFTER } }).toTypedArray()

    private val ENGLISH = rules(
        "new paragraph" to "\n\n" joined Join.BOTH,
        "new line" to "\n" joined Join.BOTH,
        "newline" to "\n" joined Join.BOTH,
        "question mark" to "?" joined Join.AFTER,
        "exclamation mark" to "!" joined Join.AFTER,
        "exclamation point" to "!" joined Join.AFTER,
        "full stop" to "." joined Join.AFTER,
        "period" to "." joined Join.AFTER,
        "comma" to "," joined Join.AFTER,
        "colon" to ":" joined Join.AFTER,
        "semicolon" to ";" joined Join.AFTER,
        "ellipsis" to "…" joined Join.AFTER,
        "dot dot dot" to "…" joined Join.AFTER,
        "apostrophe" to "'" joined Join.BOTH,
        "hyphen" to "-" joined Join.BOTH,
        "underscore" to "_" joined Join.BOTH,
        "dash" to "-" joined Join.APART,
        "en dash" to "–" joined Join.BOTH,
        "em dash" to "—" joined Join.BOTH,
        "forward slash" to "/" joined Join.BOTH,
        "slash" to "/" joined Join.BOTH,
        "backslash" to "\\" joined Join.BOTH,
        "back slash" to "\\" joined Join.BOTH,
        "at sign" to "@" joined Join.BOTH,
        "hashtag" to "#" joined Join.BEFORE,
        "hash sign" to "#" joined Join.BEFORE,
        "pound sign" to "#" joined Join.BEFORE,
        "dollar sign" to "\$" joined Join.BEFORE,
        "percent sign" to "%" joined Join.AFTER,
        "degree sign" to "°" joined Join.AFTER,
        "ampersand" to "&" joined Join.APART,
        "asterisk" to "*" joined Join.AFTER,
        "plus sign" to "+" joined Join.APART,
        "equals sign" to "=" joined Join.APART,
        "greater than sign" to ">" joined Join.APART,
        "less than sign" to "<" joined Join.APART,
        "vertical bar" to "|" joined Join.APART,
        "tilde" to "~" joined Join.BEFORE,
        "caret" to "^" joined Join.BOTH,
        "copyright sign" to "©" joined Join.AFTER,
        "registered sign" to "®" joined Join.AFTER,
        "trademark sign" to "™" joined Join.AFTER,
        *paired("\"", "\"", listOf("quote", "quotes"), opens = listOf("open", "begin", "start"), closes = listOf("close", "end")),
        "unquote" to "\"" joined Join.AFTER,
        *paired("(", ")", listOf("parenthesis", "parentheses", "paren")),
        *paired("[", "]", listOf("bracket", "brackets", "square bracket", "square brackets")),
        *paired("{", "}", listOf("brace", "braces", "curly brace", "curly braces", "curly bracket", "curly brackets")),
    )

    private val BENGALI = rules(
        "নতুন প্যারা" to "\n\n" joined Join.BOTH,
        "নতুন লাইন" to "\n" joined Join.BOTH,
        "প্রশ্নবোধক চিহ্ন" to "?" joined Join.AFTER,
        "প্রশ্নবোধক" to "?" joined Join.AFTER,
        "বিস্ময়সূচক চিহ্ন" to "!" joined Join.AFTER,
        "বিস্ময়বোধক" to "!" joined Join.AFTER,
        "দাঁড়ি" to "।" joined Join.AFTER,
        "কমা" to "," joined Join.AFTER,
        "কোলন" to ":" joined Join.AFTER,
        "সেমিকোলন" to ";" joined Join.AFTER,
    )

    // The names Gboard answers to in Portuguese and Spanish. A bare "ponto"
    // or "punto" is left out: both are everyday words ("ponto de ônibus").
    private val PORTUGUESE = rules(
        "novo parágrafo" to "\n\n" joined Join.BOTH,
        "nova linha" to "\n" joined Join.BOTH,
        "ponto de interrogação" to "?" joined Join.AFTER,
        "interrogação" to "?" joined Join.AFTER,
        "ponto de exclamação" to "!" joined Join.AFTER,
        "exclamação" to "!" joined Join.AFTER,
        "ponto final" to "." joined Join.AFTER,
        "ponto e vírgula" to ";" joined Join.AFTER,
        "vírgula" to "," joined Join.AFTER,
        "dois pontos" to ":" joined Join.AFTER,
        "reticências" to "…" joined Join.AFTER,
        "abre aspas" to "\"" joined Join.BEFORE,
        "fecha aspas" to "\"" joined Join.AFTER,
        "abre parênteses" to "(" joined Join.BEFORE,
        "fecha parênteses" to ")" joined Join.AFTER,
        "hífen" to "-" joined Join.BOTH,
        "travessão" to "—" joined Join.APART,
        "arroba" to "@" joined Join.BOTH,
    )

    private val SPANISH = rules(
        "nuevo párrafo" to "\n\n" joined Join.BOTH,
        "nueva línea" to "\n" joined Join.BOTH,
        "signo de interrogación" to "?" joined Join.AFTER,
        "signo de exclamación" to "!" joined Join.AFTER,
        "punto final" to "." joined Join.AFTER,
        "punto y coma" to ";" joined Join.AFTER,
        "coma" to "," joined Join.AFTER,
        "dos puntos" to ":" joined Join.AFTER,
        "puntos suspensivos" to "…" joined Join.AFTER,
        "abrir comillas" to "\"" joined Join.BEFORE,
        "cerrar comillas" to "\"" joined Join.AFTER,
        "abrir paréntesis" to "(" joined Join.BEFORE,
        "cerrar paréntesis" to ")" joined Join.AFTER,
        "guion" to "-" joined Join.BOTH,
        "arroba" to "@" joined Join.BOTH,
    )

    /** The spoken names this pass knows for [languageTag]; none for a language it has no table for. */
    private fun tableFor(languageTag: String): List<Rule>? = when {
        languageTag.startsWith("en") -> ENGLISH
        languageTag.startsWith("bn") -> BENGALI
        languageTag.startsWith("pt") -> PORTUGUESE
        languageTag.startsWith("es") -> SPANISH
        else -> null
    }

    /**
     * Marks a recognizer that punctuates on its own may hang on a word, or put
     * in front of one (Spanish). "Period." and "comma," still name a mark.
     */
    private const val TRAILING = ".,!?;:…"
    private const val LEADING = "¿¡"

    /** The marks that end or split a clause, which replace a recognizer's guess just before them. */
    private val SENTENCE_MARKS = setOf(".", ",", "?", "!", ":", ";", "…", "।")

    private fun core(word: String): String = word.trimStart { it in LEADING }.trimEnd { it in TRAILING }

    fun apply(text: String, languageTag: String): String {
        val table = tableFor(languageTag) ?: return text
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val cores = words.map(::core)
        val sb = StringBuilder()
        // Set by a mark that closes up to whatever follows it (and at the very
        // start — the caller handles the leading space).
        var tight = true
        // Where the last plain word's own trailing marks start in [sb], or -1:
        // "hello, comma" means one comma, not two.
        var guessAt = -1
        var i = 0
        outer@ while (i < words.size) {
            for (rule in table) {
                val phrase = rule.words
                if (i + phrase.size <= words.size &&
                    phrase.indices.all { cores[i + it].equals(phrase[it], ignoreCase = true) }
                ) {
                    if (guessAt >= 0 && rule.symbol in SENTENCE_MARKS) sb.setLength(guessAt)
                    val spaceBefore = rule.join == Join.BEFORE || rule.join == Join.APART
                    if (spaceBefore && !tight) sb.append(' ')
                    sb.append(rule.symbol)
                    tight = rule.join == Join.BEFORE || rule.join == Join.BOTH
                    guessAt = -1
                    i += phrase.size
                    continue@outer
                }
            }
            if (!tight) sb.append(' ')
            val word = words[i]
            val tail = word.length - word.trimEnd { it in TRAILING }.length
            sb.append(word)
            guessAt = if (tail in 1 until word.length) sb.length - tail else -1
            tight = false
            i++
        }
        return sb.toString()
    }
}
