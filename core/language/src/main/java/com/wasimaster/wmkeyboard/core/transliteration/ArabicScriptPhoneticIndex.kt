package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup for Persian and Arabic: [UrduPhoneticIndex]'s
 * consonant-skeleton fold, which fits every language the Arabic script writes —
 * none of them spells its short vowels — and already folds the Arabic and
 * Persian letter shapes (ي ك ه ة) onto the ones it keys on.
 *
 * What a language adds is how its romanization spells what the fold reads:
 *
 *  - **[readings]**: the other spellings a typed word may stand for, looked up
 *    too and interleaved after the typed one. Persian writes "gh" for both ق and
 *    غ, which the fold files under different keys; Egyptian Arabic writes "g"
 *    for ج.
 *  - **Arabizi digits**, for Arabic: 7 is ح, 5 خ, 6 ط, 9 ص, 8 ق, 3 and 2 the ع
 *    and hamza the fold drops anyway ([arabizi]).
 *
 * The lists are big (Arabic's is 2.5 million words), so only the
 * [boundedEntries] budget is filed.
 */
class ArabicScriptPhoneticIndex(
    table: PhoneticTable,
    private val readings: (String) -> List<String> = { listOf(it) },
) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(
        entries: List<Pair<String, Int>>,
        readings: (String) -> List<String> = { listOf(it) },
    ) : this(table(entries), readings)

    private val inner = UrduPhoneticIndex(table)

    override val isEmpty: Boolean get() = inner.isEmpty

    /** The list's words in order, for [completions]; null with no list. */
    override val sortedWords: SortedWords? get() = inner.sortedWords


    override fun lookup(input: String): List<String> {
        val all = readings(input).map { inner.lookup(it) }
        if (all.size == 1) return all[0]
        // Interleaved, so the typed spelling's best leads and each other
        // reading's best follows close behind rather than after a long tail.
        val out = LinkedHashSet<String>()
        val longest = all.maxOf { it.size }
        for (rank in 0 until longest) for (list in all) list.getOrNull(rank)?.let { out.add(it) }
        return out.toList()
    }

    override fun frequencyOf(word: String): Int = inner.frequencyOf(word)

    override fun matchStrength(input: String): Int = readings(input).maxOf { inner.matchStrength(it) }

    override val maxFrequency: Int get() = inner.maxFrequency

    companion object {

        /** Files [entries], at most the [boundedEntries] budget of them, by the skeleton fold. */
        fun table(entries: List<Pair<String, Int>>): PhoneticTable = UrduPhoneticIndex.table(boundedEntries(entries))

        /** Persian: "gh" is ق as readily as غ. */
        fun persianReadings(input: String): List<String> =
            if ("gh" in input) listOf(input, input.replace("gh", "q")) else listOf(input)

        /**
         * Arabic: the Arabizi digits as the letters the fold reads, and
         * Egyptian "g" as ج beside the ق and غ it otherwise is.
         */
        fun arabicReadings(input: String): List<String> {
            val plain = arabizi(input)
            return if ('g' in plain && "gh" !in plain) listOf(plain, plain.replace('g', 'j')) else listOf(plain)
        }

        /** Arabizi digits spelled as the roman letters the fold keys on. */
        fun arabizi(input: String): String {
            if (input.none { it.isDigit() }) return input
            val out = StringBuilder(input.length + 2)
            var i = 0
            while (i < input.length) {
                val c = input[i]
                val marked = input.getOrNull(i + 1) == '\''
                val letters = when (c) {
                    '2' -> "'"
                    '3' -> if (marked) "gh" else "'"
                    '5' -> "kh"
                    '6' -> if (marked) "Z" else "T"
                    '7' -> "H"
                    '8' -> "q"
                    // ض and ظ are both the fold's variant z.
                    '9' -> if (marked) "Z" else "S"
                    else -> null
                }
                if (letters == null) {
                    out.append(c)
                } else {
                    out.append(letters)
                    if (marked && c in "369") i++
                }
                i++
            }
            return out.toString()
        }
    }
}
