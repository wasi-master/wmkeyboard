package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex

/**
 * A romanized selection into its own script and back, for a whole selection:
 * Banglish to Bangla, Hinglish to Hindi — whichever [scheme] it is built for.
 *
 * Forward, each Latin word is resolved the way the strip would have
 * committed it: the spelling map first (tmr → তোমার, keyboard → কিবোর্ড),
 * then the phonetic index, which folds the spelling so dialect and habit
 * variants (asi, achi, achhi) all land on the dictionary word, and last the
 * literal transliteration. The index only beats the literal when it is
 * clearly the commoner word, the same confidence rule `SuggestionEngine`
 * applies, so a correctly spelled word is not swapped for a near tie. Only
 * letter runs are converted: digits and punctuation stay as they were, so
 * `2024` is not turned into native figures and a full stop stays a full
 * stop.
 *
 * Backward, a known word takes the most readable spelling the map has for
 * it (see [pickSpelling]), then the caller's own spelling when it has one ([toRoman]'s
 * `respelled`, a neural model's for romanized dictation), and anything else
 * goes through the scheme's romanizer. The inverted map
 * is built once, lazily, on whichever thread first asks; the service asks
 * from a background one.
 */
class RomanizedConverter(
    private val scheme: PhoneticScheme,
    private val spellings: SpellingMap,
    private val phonetic: PhoneticIndex,
) {

    /** [text] with every Latin word in the scheme's script, or null when nothing changed. */
    fun toNative(text: String): String? {
        val out = StringBuilder(text.length * 2)
        var changed = false
        var i = 0
        while (i < text.length) {
            if (isLatinLetter(text[i])) {
                var j = i
                while (j < text.length && isLatinLetter(text[j])) j++
                val word = text.substring(i, j)
                val native = wordToNative(word)
                if (native != word) changed = true
                out.append(native)
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return if (changed) out.toString() else null
    }

    /**
     * [text] with every word of the scheme's script in Latin letters, or null
     * when nothing changed. A word the maps do not know takes its spelling
     * from [respelled] (keyed as [unmapped] gives them) before the rules.
     */
    fun toRoman(text: String, respelled: Map<String, String> = emptyMap()): String? {
        val norm = scheme.normalize(text)
        val out = StringBuilder(norm.length)
        var changed = false
        var i = 0
        while (i < norm.length) {
            val c = norm[i]
            when {
                scheme.isNative(c) -> {
                    var j = i
                    while (j < norm.length && scheme.isNative(norm[j])) j++
                    val word = norm.substring(i, j)
                    out.append(inverted[word] ?: respelled[word] ?: scheme.romanizeWord(word))
                    changed = true
                    i = j
                }
                // The danda is one code point for every Indic script, and it
                // sits outside the run but still wants a full stop.
                c == '।' -> { out.append('.'); changed = true; i++ }
                c == '॥' -> { out.append(".."); changed = true; i++ }
                else -> { out.append(c); i++ }
            }
        }
        return if (changed) out.toString() else null
    }

    /**
     * The words of the scheme's script in [text] the maps have no spelling
     * for, as [toRoman] sees them (normalised): the ones the rules would
     * spell, which a caller may [toRoman]'s `respelled` instead.
     */
    fun unmapped(text: String): List<String> {
        val norm = scheme.normalize(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < norm.length) {
            if (scheme.isNative(norm[i])) {
                var j = i
                while (j < norm.length && scheme.isNative(norm[j])) j++
                val word = norm.substring(i, j)
                if (word !in inverted) out += word
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /**
     * Builds the inverted map now rather than on the first [toRoman], so a
     * caller on the main thread does not pay for it.
     */
    fun prepare() {
        inverted
    }

    private fun wordToNative(word: String): String {
        val norm = foldCase(word)
        spellings.lookup(norm).firstOrNull()?.let { return it }
        val literal = scheme.transliterate(norm)
        val top = phonetic.lookup(norm).firstOrNull() ?: return literal
        val literalFrequency = phonetic.frequencyOf(literal)
        val topFrequency = phonetic.frequencyOf(top)
        return if (literalFrequency > 0 && topFrequency < literalFrequency * SIBLING_CONFIDENCE) literal else top
    }

    /**
     * A capital that is only the start of a sentence, or a word shouted in
     * capitals, means the same word; mixed case inside a word is a deliberate
     * spelling (Avro's nodI, Hindi's roTi) and stays.
     */
    private fun foldCase(word: String): String = when {
        word.length > 1 && word[0].isUpperCase() && word.substring(1).all { it.isLowerCase() } -> word.lowercase()
        word.all { it.isUpperCase() } -> word.lowercase()
        else -> word
    }

    private fun isLatinLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** Native word → the spelling to write it with. */
    private val inverted: Map<String, String> by lazy { buildInverted() }

    private fun buildInverted(): Map<String, String> {
        // Every listed spelling of each word, in the lists' order.
        val listed = LinkedHashMap<String, MutableList<String>>()
        for (spelling in spellings.spellings) {
            if (!spelling.all { it in 'a'..'z' }) continue
            if (TRIPLED.containsMatchIn(spelling)) continue
            for (form in spellings.lookup(spelling)) {
                if (form.any { it.isWhitespace() }) continue
                listed.getOrPut(scheme.normalize(form)) { ArrayList() }.add(spelling)
            }
        }
        val out = HashMap<String, String>(listed.size * 2)
        for ((word, candidates) in listed) pickSpelling(word, candidates)?.let { out[word] = it }
        return out
    }

    /**
     * The spelling [word] is written back with, out of the map's [candidates]
     * (in list order), or null to leave it to the rules.
     *
     * A loanword's own spelling first: ফোন is "phone", not "phon". Otherwise
     * the readable ones — not chat shorthand ([isShorthand]: tmi, amr, kmn) and
     * not an elongation of another listed spelling (amarr, chaii, boloo) — and
     * of those the nearest to the rules' reading, earlier in the list on a tie.
     * That keeps the spelling a reader expects (ami bhalo achhi, kotha, hobe)
     * where the old pick, the longest spelling, wrote amie, ashche, khotha and
     * hobee, and it lets a short word (ami, ki, na) come from the map at all.
     */
    private fun pickSpelling(word: String, candidates: List<String>): String? {
        candidates.firstOrNull(spellings::isLoanword)?.let { return it }
        val readable = candidates.filter { spelling ->
            !isShorthand(spelling) &&
                !(spelling.length >= 3 && spelling.last() == spelling[spelling.length - 2] && spelling.dropLast(1) in candidates)
        }
        if (readable.isEmpty()) return null
        val rules = scheme.romanizeWord(word)
        return readable.withIndex()
            .minWith(compareBy<IndexedValue<String>>({ EditOps.distance(it.value, rules) }, { it.index }))
            .value
    }

    /**
     * Chat shorthand that drops the vowels a reader needs: no vowel at all
     * (kmn, llb), or two consonants at the start or the end that no word is
     * written with (tmi, tmr; amr). The pairs a romanization does write — kh,
     * th and the other aspirates, tr, st, nd, rk — pass.
     */
    private fun isShorthand(spelling: String): Boolean {
        if (spelling.none { it in VOWELS }) return true
        if (spelling.length < 3) return false
        fun cluster(pair: String) = pair[0] !in VOWELS && pair[1] !in VOWELS && pair[1] != 'y'
        val start = spelling.take(2)
        if (cluster(start) && start !in ONSETS) return true
        val end = spelling.takeLast(2)
        return cluster(end) && end !in CODAS
    }

    private companion object {
        /** Same rule and constant as SuggestionEngine's phonetic strip. */
        const val SIBLING_CONFIDENCE = 2.0
        const val VOWELS = "aeiou"

        /** Two consonants a romanized word may start with. */
        val ONSETS = (
            "bl br by cl cr dr dw fl fr gl gr gy kl kr kw ky pl pr py sc sk sl sm sn sp st sw tr tw vr " +
                "ph wh sh ch th kh gh dh bh jh rh zh"
            ).split(' ').toSet()

        /** Two consonants a romanized word may end with. */
        val CODAS = (
            "nd nt nk ng ns nc nj rd rt rk rm rn rs rb rp rl rg rj rc rv rf st sk sp ft ct pt kt lt ld lk " +
                "lp lm lf mp ms ts ks ds ps xt ll ss ff tt zz ck sh ch th kh gh dh bh ph jh ny"
            ).split(' ').toSet()
        /** An elongation (valoooo) is not a spelling either. */
        val TRIPLED = Regex("""(.)\1\1""")
    }
}
