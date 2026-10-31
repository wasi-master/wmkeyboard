package com.wasimaster.wmkeyboard.core.prediction

import java.io.InputStream

/**
 * Spellings that resolve to a fixed native-script form, for a phonetic input
 * mode — one map per language that has one ([PhoneticScheme.spellingAssets]).
 * The examples below are Avro's, which is where the map began.
 *
 * Two kinds of spelling need this, and neither is reachable from phonetic
 * rules:
 *
 *  - **English loanwords.** "keyboard" is written কিবোর্ড and "chair" চেয়ার by
 *    convention, not by sound-for-sound transliteration. Left to the rules,
 *    "table" would come out তাবলে rather than টেবিল.
 *  - **Romanized Bengali as people actually type it.** Chat spelling drops
 *    vowels wholesale — "tmr" for তোমার, "amk" for আমাকে, "trpr" for তারপর.
 *    No fold recovers a vowel that was never typed, so these are listed
 *    outright. Colloquial and regional inflections ("boltiso" → বলতিছো) are
 *    here for the same reason.
 *
 * The suggestion engine consults this before anything else, so a wrong entry
 * is worse than a missing one: it outranks both the dictionary and the literal
 * transliteration. So a spelling the rules already turn into a word people use
 * lists that word first, and a capital that changes what the rules spell skips
 * the map altogether ([PhoneticBackend.listedForms]).
 *
 * The backing assets ([load]) are two-column TSV (`spelling<TAB>bengali`). A
 * single spelling may map to several Bengali forms (ki → কি / কী); they are
 * returned in file order, most-preferred first. Where two assets disagree the
 * earlier one leads, which is how the hand-written list stays ahead of the
 * generated one.
 */
class SpellingMap private constructor(
    /** Every spelling, in file order. */
    private val order: Array<String>,
    /** Spelling `i`'s forms: `forms[formStart[i] until formStart[i + 1]]`, best first. */
    private val formStart: IntArray,
    private val forms: Array<String>,
    /** Spelling positions sorted by spelling, for [lookup]. */
    private val sorted: IntArray,
    /** Spelling positions that came from a loanword list, sorted. */
    private val loanwords: IntArray,
) {
    // Flat arrays rather than a LinkedHashMap of lists: this map is loaded for
    // as long as a phonetic layout is enabled, and at ~14,000 spellings with
    // one form apiece the map's entries, lists and nodes were most of its
    // 3 MB. The spellings keep their file order, which [spellings] promises.

    private val formList = forms.asList()

    private fun positionOf(spelling: String): Int {
        val key = spelling.trim().lowercase()
        var low = 0
        var high = sorted.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = order[sorted[mid]].compareTo(key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return sorted[mid]
            }
        }
        return -1
    }

    /** Bengali forms for [spelling], best first; empty if unmapped. */
    fun lookup(spelling: String): List<String> {
        val position = positionOf(spelling)
        return if (position < 0) emptyList() else formList.subList(formStart[position], formStart[position + 1])
    }

    /** True when [spelling] has at least one mapped Bengali form. */
    fun contains(spelling: String): Boolean = positionOf(spelling) >= 0

    /**
     * Whether [spelling] came from a loanword list: the spelling is itself a
     * word of another language ("keyboard"), and the mapped form is only how
     * that word is written in this script. A listed romanization ("tmr") says
     * the typist meant this language; a listed loanword does not, which is what
     * a caller deciding between the two scripts needs to know.
     */
    fun isLoanword(spelling: String): Boolean {
        val position = positionOf(spelling)
        return position >= 0 && loanwords.binarySearch(position) >= 0
    }

    val size: Int get() = order.size

    /** Every spelling this map knows, in file order — the curated romanized
     * vocabulary, which is also what a glide over Avro is decoded against. */
    val spellings: List<String> = order.asList()

    companion object {
        /** Empty map, used as the default when no asset is supplied (tests). */
        val EMPTY = SpellingMap(emptyArray(), IntArray(1), emptyArray(), IntArray(0), IntArray(0))

        /**
         * Languages that ship a spelling map, by
         * [com.wasimaster.wmkeyboard.core.script.LanguageDef.id]. Only these
         * get the settings row — a language with no lists behind it would be
         * offering a switch that does nothing.
         */
        val LANGUAGES: Set<String> = PhoneticSchemes.all
            .filter { it.spellingAssets.isNotEmpty() }
            .mapTo(LinkedHashSet()) { it.languageId }

        /**
         * Parses `spelling<TAB>bengali` TSVs. Blank lines and `#` comments are
         * skipped; malformed lines (no tab, empty side) are ignored so the
         * assets survive hand editing. Duplicate keys accumulate their Bengali
         * forms in the order the streams are given, de-duplicated — so passing
         * the curated list first leaves it outranking the generated one.
         *
         * The first [loanwordStreams] of them are loanword lists (see
         * [isLoanword]); [PhoneticScheme.loanwordAssetCount] is how many of a
         * scheme's assets are.
         */
        fun load(vararg streams: InputStream, loanwordStreams: Int = 0): SpellingMap {
            val byWord = LinkedHashMap<String, MutableList<String>>()
            val loanwords = HashSet<String>()
            for ((position, stream) in streams.withIndex()) {
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val tab = trimmed.indexOf('\t')
                        if (tab <= 0) continue
                        val spelling = trimmed.substring(0, tab).trim().lowercase()
                        val bengali = trimmed.substring(tab + 1).trim()
                        if (spelling.isEmpty() || bengali.isEmpty()) continue
                        val forms = byWord.getOrPut(spelling) { mutableListOf() }
                        if (bengali !in forms) forms.add(bengali)
                        if (position < loanwordStreams) loanwords.add(spelling)
                    }
                }
            }
            val order = byWord.keys.toTypedArray()
            val formStart = IntArray(order.size + 1)
            var total = 0
            for ((i, spelling) in order.withIndex()) {
                formStart[i] = total
                total += byWord.getValue(spelling).size
            }
            formStart[order.size] = total
            val forms = arrayOfNulls<String>(total)
            var at = 0
            for (spelling in order) for (form in byWord.getValue(spelling)) forms[at++] = form
            @Suppress("UNCHECKED_CAST")
            return SpellingMap(
                order = order,
                formStart = formStart,
                forms = forms as Array<String>,
                sorted = order.indices.sortedBy { order[it] }.toIntArray(),
                loanwords = order.indices.filter { order[it] in loanwords }.toIntArray(),
            )
        }
    }
}
