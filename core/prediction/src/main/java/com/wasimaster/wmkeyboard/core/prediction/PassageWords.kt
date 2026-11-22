package com.wasimaster.wmkeyboard.core.prediction

/**
 * The words of text the user keeps near at hand, recent clipboard items and
 * notes (#1453), so the strip can finish them: a name or a code copied a
 * moment ago completes from its first letters ([complete]), and the words of
 * a passage follow one another on the strip once its first ones are typed
 * ([nextWords]).
 *
 * Unlike [NameIndex], which chains every pair of a short name, this reads
 * long running text, so a word's followers are kept only where they say
 * something: after two words of the passage, or after one word no word list
 * holds. Otherwise "the" in a note would put whatever came after it in the
 * note on the strip after every "the".
 *
 * Built in memory from a snapshot of the texts; nothing is persisted, and the
 * IME builds a new one when the texts change.
 */
class PassageWords private constructor(
    private val words: NameIndex,
    private val afterPair: Map<String, List<String>>,
    private val afterWord: Map<String, List<String>>,
) {

    val isEmpty: Boolean get() = words.isEmpty

    /** True when [word] (lowercase) is one of the passages' words. */
    fun contains(word: String): Boolean = words.contains(word)

    /** Words starting with [prefix] (lowercase), the most used first, in the case they were written. */
    fun complete(prefix: String, limit: Int): List<Suggestion> = words.complete(prefix, limit)

    /**
     * What followed [prev2] [prev] (lowercase) in a passage, then what
     * followed [prev] alone when [isCommon] says no word list holds it.
     */
    fun nextWords(prev2: String?, prev: String, isCommon: (String) -> Boolean): List<String> {
        val out = ArrayList<String>(MAX_FOLLOWERS)
        if (prev2 != null) afterPair["$prev2 $prev"]?.let(out::addAll)
        if (!isCommon(prev)) afterWord[prev]?.forEach { if (it !in out) out += it }
        return if (out.size > MAX_FOLLOWERS) out.subList(0, MAX_FOLLOWERS) else out
    }

    companion object {
        val EMPTY = PassageWords(NameIndex.EMPTY, emptyMap(), emptyMap())

        /** Characters read in all; a phone's notes can run to megabytes, and the index lives in memory. */
        const val MAX_CHARS = 200_000

        /** Followers kept per word or pair. */
        const val MAX_FOLLOWERS = 3

        /** Breaks between which a passage's words run on: a sentence's end, a line's. */
        private val BREAKS = Regex("""[.!?;:\n\r…।。！？]+""")

        private val SPACES = Regex("""[\s,()\[\]{}"“”«»/|]+""")

        /**
         * The index over [texts], newest first, reading no further than
         * [MAX_CHARS] in all. A word is kept when it has a letter and at
         * least two characters. A link breaks into its words, the way
         * [NameIndex] breaks a name.
         */
        fun of(texts: List<String>): PassageWords {
            val sentences = ArrayList<List<String>>()
            var budget = MAX_CHARS
            for (text in texts) {
                if (budget <= 0) break
                val cut = if (text.length > budget) text.substring(0, budget) else text
                budget -= cut.length
                for (sentence in cut.split(BREAKS)) {
                    val parts = sentence.split(SPACES)
                        .map { part -> part.trim { !WordContext.isWordChar(it) && it != '@' } }
                        .filter { part -> part.length >= 2 && part.any(Char::isLetter) }
                    if (parts.isNotEmpty()) sentences += parts
                }
            }
            if (sentences.isEmpty()) return EMPTY
            val pairs = HashMap<String, HashMap<String, Int>>()
            val singles = HashMap<String, HashMap<String, Int>>()
            for (parts in sentences) {
                for (i in parts.indices) {
                    val word = parts[i]
                    if (i >= 1) singles.getOrPut(parts[i - 1].lowercase()) { HashMap() }.merge(word, 1, Int::plus)
                    if (i >= 2) {
                        val key = parts[i - 2].lowercase() + " " + parts[i - 1].lowercase()
                        pairs.getOrPut(key) { HashMap() }.merge(word, 1, Int::plus)
                    }
                }
            }
            return PassageWords(
                words = NameIndex.fromNames(sentences.map { it.joinToString(" ") }),
                afterPair = pairs.mapValues { (_, f) -> ranked(f) },
                afterWord = singles.mapValues { (_, f) -> ranked(f) },
            )
        }

        private fun ranked(followers: Map<String, Int>): List<String> =
            followers.entries.sortedByDescending { it.value }.take(MAX_FOLLOWERS).map { it.key }
    }
}
