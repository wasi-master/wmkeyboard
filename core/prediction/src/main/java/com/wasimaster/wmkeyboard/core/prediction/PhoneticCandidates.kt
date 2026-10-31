package com.wasimaster.wmkeyboard.core.prediction

/**
 * Where a phonetic layout shows its candidate list: the words desktop Avro
 * drops down under the word being typed (see [PhoneticCandidates]).
 */
enum class PhoneticCandidateList {
    /** Not shown; the strip is the ordinary one. */
    OFF,

    /**
     * Folded into the strip: the word a space commits keeps the first chip,
     * and the candidate list follows it in its own order.
     */
    STRIP,

    /** A scrollable row of its own above the strip, holding the whole list. */
    BAR,
}

/**
 * The candidate list desktop Avro shows under a word, built the way Avro
 * builds it (OmicronLab's `SuggestionBuilder`), out of this keyboard's own
 * data:
 *
 *  1. The autocorrect entry for the spelling. Avro's autocorrect dictionary
 *     is what the fixed-spelling map's loanwords were imported from, so the
 *     map answers here.
 *  2. Dictionary words that the spelling could stand for, plus the ones made
 *     by reading a known suffix off the end (`bondhuder` → বন্ধু + দের), all
 *     ordered by how few edits separate them from the rules' reading. Avro
 *     finds the first with a loose regular expression over its word list; the
 *     phonetic index's fold is the same idea, and its frequency order is kept
 *     between words the same distance away.
 *  3. The rules' own reading, letter for letter.
 *
 * Pure, so it can be measured without the engine around it.
 */
object PhoneticCandidates {

    /** How many dictionary words one base spelling contributes to suffix joins. */
    private const val SUFFIX_BASES = 5

    /**
     * Builds the list for [composing]. [suppressed] drops the words the user
     * never wants offered; the rules' reading survives it, as it does in the
     * strip, because it is what a space falls back to. With [desktop] loaded,
     * Bangla's list is desktop Avro's own, see [buildDesktop].
     */
    fun build(
        backend: PhoneticBackend,
        composing: String,
        limit: Int,
        desktop: AvroDesktop? = null,
        suppressed: (String) -> Boolean = { false },
    ): List<String> {
        if (composing.isEmpty() || limit <= 0) return emptyList()
        if (desktop != null && backend.scheme.languageId == AVRO_LANGUAGE) {
            return buildDesktop(backend, desktop, composing, limit, suppressed)
        }
        val scheme = backend.scheme
        val reading = scheme.transliterate(composing)
        val listed = backend.listedForms(composing)
        val dictionary = LinkedHashSet<String>()
        // The loose search, as Avro's regex is loose: a typed o in mid-word may
        // be ো, which the strip's fold reads only as the inherent vowel.
        dictionary.addAll(backend.index.lookupLoose(composing))
        dictionary.addAll(withSuffixes(backend, composing))
        val out = LinkedHashSet<String>()
        out.addAll(listed)
        // sortedBy is stable, so words the same distance away keep the
        // index's frequency order.
        out.addAll(dictionary.sortedBy { levenshtein(reading, it) })
        out.add(reading)
        return out.asSequence().filter { it == reading || !suppressed(it) }.take(limit).toList()
    }

    /**
     * Desktop Avro's own list, from Avro's data ([AvroDesktop]), with the
     * keyboard's words added and nothing of Avro's left out: Avro's
     * autocorrect, then the keyboard's fixed spellings, then every dictionary
     * word (Avro's search over both lists, plus the keyboard's own looser fold
     * and suffix joins) nearest to Avro's reading first, then Avro's reading,
     * then the keyboard's own reading where the two differ.
     */
    private fun buildDesktop(
        backend: PhoneticBackend,
        desktop: AvroDesktop,
        composing: String,
        limit: Int,
        suppressed: (String) -> Boolean,
    ): List<String> {
        val extra = LinkedHashSet<String>()
        extra.addAll(backend.index.lookupLoose(composing))
        extra.addAll(withSuffixes(backend, composing))
        val avro = desktop.suggest(composing, backend.index.sortedWords, extra.toList())
        val reading = backend.scheme.transliterate(composing)
        val readings = setOf(AvroDesktop.precomposed(avro.phonetic), AvroDesktop.precomposed(reading))
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(word: String) {
            if (word.isEmpty()) return
            val key = AvroDesktop.precomposed(word)
            if (key !in readings && suppressed(word)) return
            if (seen.add(key)) out += word
        }
        avro.autocorrect?.let(::add)
        backend.listedForms(composing).forEach(::add)
        avro.words.forEach(::add)
        add(avro.phonetic)
        add(reading)
        return out.take(limit)
    }

    /** The language whose phonetic layout is Avro, the one [AvroDesktop] builds for. */
    private const val AVRO_LANGUAGE = "bn"

    /**
     * Every reading of [composing] as a base the dictionary or the map knows
     * followed by one of the language's suffixes, joined the way Avro joins
     * them: a vowel sign after a vowel takes a য় between them (মা + ের →
     * মায়ের), ৎ opens back up to ত before a suffix, and ং to ঙ.
     */
    private fun withSuffixes(backend: PhoneticBackend, composing: String): List<String> {
        val suffixes = SUFFIXES[backend.scheme.languageId] ?: return emptyList()
        if (composing.length < 2) return emptyList()
        val lower = composing.lowercase()
        val out = ArrayList<String>()
        for (split in 1 until composing.length) {
            val suffix = suffixes[lower.substring(split)] ?: continue
            val base = composing.substring(0, split)
            val bases = LinkedHashSet<String>()
            bases.addAll(backend.listedForms(base))
            bases.addAll(backend.index.lookup(base).take(SUFFIX_BASES))
            for (word in bases) {
                if (word.isEmpty()) continue
                out.add(join(word, suffix))
            }
        }
        return out
    }

    private fun join(base: String, suffix: String): String {
        val last = base.last()
        return when {
            isVowel(last) && isVowelSign(suffix.first()) -> base + YA + suffix
            last == KHANDA_TA -> base.dropLast(1) + TA + suffix
            last == ANUSVARA -> base.dropLast(1) + NGA + suffix
            else -> base + suffix
        }
    }

    private fun isVowelSign(c: Char): Boolean = c in "ািীুূৃৄেৈোৌ"

    private fun isVowel(c: Char): Boolean =
        isVowelSign(c) || c in "অআইঈউঊঋঌএঐওঔৡ"

    /** Plain edit distance, the measure Avro sorts its dictionary words by. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private const val YA = 'য়'
    private const val KHANDA_TA = 'ৎ'
    private const val TA = 'ত'
    private const val ANUSVARA = 'ং'
    private const val NGA = 'ঙ'

    /**
     * The endings read off a word to find its base, by language, in the
     * romanization they are typed in. Bengali's case endings, plurals,
     * classifiers and the emphatic and inclusive particles, each with the
     * forms they stack into.
     */
    private val SUFFIXES: Map<String, Map<String, String>> = mapOf(
        "bn" to mapOf(
            "e" to "ে",
            "er" to "ের",
            "r" to "র",
            "ke" to "কে",
            "re" to "রে",
            "te" to "তে",
            "ei" to "েই",
            "eo" to "েও",
            "tei" to "তেই",
            "teo" to "তেও",
            "kei" to "কেই",
            "keo" to "কেও",
            "i" to "ই",
            "o" to "ও",
            "ra" to "রা",
            "rai" to "রাই",
            "rao" to "রাও",
            "der" to "দের",
            "derke" to "দেরকে",
            "gulo" to "গুলো",
            "gula" to "গুলা",
            "guli" to "গুলি",
            "gulor" to "গুলোর",
            "guloke" to "গুলোকে",
            "gulote" to "গুলোতে",
            "ta" to "টা",
            "ti" to "টি",
            "tai" to "টাই",
            "tao" to "টাও",
            "tar" to "টার",
            "tir" to "টির",
            "take" to "টাকে",
            "tike" to "টিকে",
            "khana" to "খানা",
            "khani" to "খানি",
            "jon" to "জন",
        ),
    )
}
