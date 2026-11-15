package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from a romanized spelling to the dictionary words it
 * could stand for, for a layout whose keys are Latin and whose text is not.
 *
 * Romanized spelling is loose in every language that has one, so an index
 * folds both sides — what was typed, and each dictionary word — to a lenient
 * key where the confusable sounds collapse, and words sharing a key are
 * siblings. How the fold works is the language's own business
 * ([BengaliPhoneticIndex], [HindiPhoneticIndex]); what the suggestion engine
 * needs from it is only this.
 */
interface PhoneticIndex {

    /**
     * Every word of the list in [String.compareTo] order, for a search that
     * walks it as a tree of shared prefixes (desktop Avro's regular-expression
     * search, see `AvroDesktop`). Null where the index keeps no such order.
     */
    val sortedWords: SortedWords? get() = null

    /** Dictionary words phonetically matching the romanized [input], best first. */
    fun lookup(input: String): List<String>

    /**
     * [lookup], widened to the readings a looser search would also allow: what
     * desktop Avro's regular expression finds and the fold alone does not. For
     * the candidate list, which is meant to be long; the strip keeps [lookup].
     * The default is [lookup] itself.
     */
    fun lookupLoose(input: String): List<String> = lookup(input)

    /**
     * The words [input] reaches when it is read as spelled the other ways
     * people romanize the language, best first: for when [lookup] finds
     * nothing at all. Empty by default.
     */
    fun lookupSpelledOtherwise(input: String): List<String> = emptyList()

    /** Dictionary frequency of a native-script [word], 0 when unknown. */
    fun frequencyOf(word: String): Int

    /**
     * [word]'s frequency as [lookup] weighed it for [input]: less for what
     * the typing argues against (Bengali's typed h, a final o), never more.
     */
    fun weightOf(input: String, word: String): Double = frequencyOf(word).toDouble()

    /**
     * The list's native-script words that begin with [prefix], commonest
     * first, at most [limit] of them: what a layout whose keys already spell
     * the word (Khipro) asks to finish it, and what a phonetic strip offers
     * for a word typed partway. Read off [sortedWords], so an index that keeps
     * none answers nothing.
     */
    fun completions(prefix: String, limit: Int): List<String> {
        val words = sortedWords ?: return emptyList()
        return completionsIn(words, prefix, limit)
    }

    /**
     * How good [lookup]'s best answer for [input] is: that word's frequency,
     * divided by whatever the fold's discarded detail disagreed with. 0 when
     * nothing matches. A caller weighing "is this romanization a word of the
     * language at all" wants this rather than [frequencyOf] the head, because a
     * sibling reached only by ignoring what was typed is weak evidence however
     * common it is.
     */
    fun matchStrength(input: String): Int = lookup(input).firstOrNull()?.let(::frequencyOf) ?: 0

    /** The largest frequency in the list, the scale [frequencyOf] is read against. 0 when empty. */
    val maxFrequency: Int get() = 0

    /** Whether there is no word behind this index at all — no list installed. */
    val isEmpty: Boolean

    companion object {
        /**
         * The words of [words] with [prefix], commonest [limit] first. The
         * words are in word order, so those with [prefix] are one contiguous
         * run of them: binary-search the run's start and keep the commonest
         * [limit] while walking it. A one-letter prefix over a big downloaded
         * list is a run of tens of thousands, which is still a linear pass on
         * the suggestion thread, not a sort.
         */
        fun completionsIn(words: SortedWords, prefix: String, limit: Int): List<String> {
            if (prefix.isEmpty() || limit <= 0) return emptyList()
            var low = 0
            var high = words.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (compareWord(words, mid, prefix) < 0) low = mid + 1 else high = mid
            }
            // Min-heap on frequency: the head is the weakest word kept so far.
            val best = java.util.PriorityQueue<Int>(limit + 1, compareByDescending { words.rank(it) })
            var i = low
            while (i < words.size && startsWith(words, i, prefix)) {
                best.add(i)
                if (best.size > limit) best.poll()
                i++
            }
            val out = ArrayList<String>(best.size)
            while (best.isNotEmpty()) out.add(words.word(best.poll()))
            return out.asReversed()
        }

        /** Word [i] of [words] against [word], in [String.compareTo] order. */
        private fun compareWord(words: SortedWords, i: Int, word: String): Int {
            val length = words.length(i)
            for (k in 0 until minOf(length, word.length)) {
                val diff = words.charAt(i, k) - word[k]
                if (diff != 0) return diff
            }
            return length - word.length
        }

        /** Whether word [i] of [words] begins with [prefix]. */
        private fun startsWith(words: SortedWords, i: Int, prefix: String): Boolean {
            if (words.length(i) < prefix.length) return false
            for (k in prefix.indices) if (words.charAt(i, k) != prefix[k]) return false
            return true
        }

        /** No dictionary: every lookup misses, and the rules stand alone. */
        val EMPTY: PhoneticIndex = object : PhoneticIndex {
            override fun lookup(input: String): List<String> = emptyList()
            override fun frequencyOf(word: String): Int = 0
            override val isEmpty: Boolean get() = true
        }
    }
}
