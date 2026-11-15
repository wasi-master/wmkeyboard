package com.wasimaster.wmkeyboard.core.prediction

/**
 * Portuguese pronouns written onto the verb with a hyphen (#215): cortar-se,
 * cortá-lo, vendê-la, parti-los, pô-lo, fazemo-lo, cortam-no. No word list
 * holds them, so cortá-lo was a word no list knew, offered nothing and could
 * be corrected away, and cortá is not a word on its own for the hyphen rule
 * that keeps a compound typed in parts.
 *
 * [readings] takes what was typed, with or without the hyphen and with the
 * pronoun typed in full or begun, and gives each spelling it can be: the
 * verb it hangs on has to be a word of the lists.
 *
 * - After an infinitive the r drops before lo, la, los, las, and the vowel
 *   takes its accent: cortar → cortá-lo, vender → vendê-lo, partir → parti-lo,
 *   pôr → pô-lo. The same lo forms follow a verb ending in s or z, which
 *   drops it: cortas → corta-lo, fazemos → fazemo-lo, fiz → fi-lo.
 * - After a nasal ending (-m, -ão, -õe) o and a become no and na: cortam-no.
 * - Every other pronoun goes on the verb as it is: cortar-se, deu-me, disse-lhe.
 *   The bare o, a, os, as only after a vowel: cortou-o.
 *
 * Mesoclisis (far-se-á) and the joined pairs (lho, mo) are left out.
 */
object PortugueseClitics {

    /** The base language code whose words take these. */
    const val LANGUAGE = "pt"

    /** Pronouns that replace a final r, s or z. */
    private val LO = listOf("lo", "la", "los", "las")

    /** Pronouns after a nasal ending. */
    private val NO = listOf("no", "na", "nos", "nas")

    /** The bare third-person pronouns, after a vowel only. */
    private val BARE = listOf("o", "a", "os", "as")

    /** The rest, which go on any verb form. */
    private val PLAIN = listOf("se", "me", "te", "nos", "vos", "lhe", "lhes")

    private val ALL: List<String> = (PLAIN + LO + NO + BARE).distinct()

    /** Offered right after a bare hyphen, the commonest first. */
    private val AFTER_HYPHEN = listOf("se", "lo", "la", "me", "los", "las", "lhe", "nos")

    /** The fewest letters before the pronoun when no hyphen was typed: `amase` is not `am-ase`. */
    private const val MIN_FORM = 3

    /** One spelling of a verb with a pronoun on it, and the listed word it hangs on. */
    data class Reading(val word: String, val host: String)

    /**
     * Whether [typed] is a verb with its pronoun on, spelled right: the
     * test that keeps cortá-lo out of autocorrect's way.
     */
    fun isEnclitic(typed: String, isWord: (String) -> Boolean): Boolean {
        val cut = typed.lastIndexOf('-')
        if (cut <= 0 || cut == typed.length - 1) return false
        val pronoun = typed.substring(cut + 1)
        if (pronoun !in ALL) return false
        return hosts(typed.substring(0, cut), pronoun, isWord).any { (_, spelled) -> spelled == typed.substring(0, cut) }
    }

    /**
     * Each spelling [typed] can be read as, in the order found. Typed with a
     * hyphen, the part after it is the pronoun or its start (nothing after
     * it offers the common ones). Typed without, it has to end in a whole
     * pronoun and not be a word itself, and the single-letter ones are not
     * read: `cortalo` is cortá-lo, `cortarse` cortar-se.
     */
    fun readings(typed: String, isWord: (String) -> Boolean): List<Reading> {
        val out = LinkedHashMap<String, Reading>()
        val cut = typed.lastIndexOf('-')
        if (cut > 0) {
            val form = typed.substring(0, cut)
            if ('-' in form) return emptyList()
            val begun = typed.substring(cut + 1)
            val pronouns = if (begun.isEmpty()) AFTER_HYPHEN else ALL.filter { it.startsWith(begun) }
            for (pronoun in pronouns) {
                for ((host, spelled) in hosts(form, pronoun, isWord)) {
                    val word = "$spelled-$pronoun"
                    out.getOrPut(word) { Reading(word, host) }
                }
            }
            return out.values.toList()
        }
        if (isWord(typed)) return emptyList()
        for (pronoun in ALL.sortedByDescending { it.length }) {
            if (pronoun.length < 2 || !typed.endsWith(pronoun)) continue
            val form = typed.dropLast(pronoun.length)
            if (form.length < MIN_FORM) continue
            for ((host, spelled) in hosts(form, pronoun, isWord)) {
                val word = "$spelled-$pronoun"
                out.getOrPut(word) { Reading(word, host) }
            }
        }
        return out.values.toList()
    }

    /**
     * The listed words [form] can stand for in front of [pronoun], each with
     * the way [form] is spelled there. [form] may be typed without its accent.
     */
    private fun hosts(form: String, pronoun: String, isWord: (String) -> Boolean): List<Pair<String, String>> {
        if (form.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>(2)
        if (pronoun in LO) {
            val stem = form.dropLast(1)
            when (form.last()) {
                'a', 'á' -> out.addIf(isWord, stem + "ar", stem + "á")
                'e', 'ê' -> out.addIf(isWord, stem + "er", stem + "ê")
                'i', 'í' -> out.addIf(isWord, stem + "ir", stem + "i")
                'o', 'ô' -> {
                    out.addIf(isWord, stem + "ôr", stem + "ô")
                    out.addIf(isWord, stem + "or", stem + "ô")
                }
            }
            if (form.last() in "aeiou") {
                out.addIf(isWord, form + "s", form)
                out.addIf(isWord, form + "z", form)
            }
        }
        if (pronoun in NO && (form.endsWith('m') || form.endsWith("ão") || form.endsWith("õe")) && isWord(form)) {
            out.add(form to form)
        }
        if (pronoun in BARE && form.last() in "aeiouáéíóúâêô" && !form.endsWith("ão") && !form.endsWith("õe") &&
            isWord(form)
        ) {
            out.add(form to form)
        }
        if (pronoun in PLAIN && isWord(form)) out.add(form to form)
        return out.distinct()
    }

    private fun MutableList<Pair<String, String>>.addIf(isWord: (String) -> Boolean, host: String, spelled: String) {
        if (isWord(host)) add(host to spelled)
    }
}
