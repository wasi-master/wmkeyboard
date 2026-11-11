package com.wasimaster.wmkeyboard.core.prediction

/**
 * Read-only union of several [WordSource]s — used where a language has both a
 * downloaded dictionary and imported custom lists. A word present in more than
 * one source keeps its highest frequency, matching how [PackedTrie.of] folds
 * duplicate entries.
 *
 * Every source is searched alike. [own] is the part of them that is the
 * language's own list, which is only a distinction for [ownWalkers].
 */
class CompositeWordSource private constructor(
    private val sources: List<WordSource>,
    private val own: List<WordSource> = sources,
) : WordSource {

    override fun complete(prefix: String, limit: Int): List<Suggestion> {
        if (sources.size == 1) return sources[0].complete(prefix, limit)
        val best = HashMap<String, Int>()
        for (source in sources) {
            for (suggestion in source.complete(prefix, limit)) {
                best.merge(suggestion.word, suggestion.frequency, ::maxOf)
            }
        }
        return best.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Suggestion(it.key, it.value) }
    }

    override fun frequencyOf(word: String): Int =
        sources.maxOfOrNull { it.frequencyOf(word) } ?: 0

    override fun contains(word: String): Boolean = sources.any { it.contains(word) }

    /** The members' counts summed; an overlap may count twice, which is enough for the blend's size class. */
    override val wordCount: Int get() = sources.sumOf { it.wordCount }

    /** The union is expressed as several walks merged by max — the same
     * semantics [frequencyOf]'s maxOf already gives. */
    override fun walkers(): List<TrieWalker> = sources.flatMap { it.walkers() }

    override fun ownWalkers(): List<TrieWalker> = own.flatMap { it.ownWalkers() }

    companion object {
        /** Collapses to the single source (or an empty one) when possible. */
        fun of(sources: List<WordSource>): WordSource = when (sources.size) {
            0 -> PackedTrie.EMPTY
            1 -> sources[0]
            else -> CompositeWordSource(sources)
        }

        /**
         * One language's slot: its [downloaded] list when it has one, and the
         * lists the user [imported] for it.
         *
         * Never collapsed to a bare source, even with no download: an imported
         * list on its own would then read as the language's own
         * ([WordSource.ownWalkers]), and English and Bengali, whose own lists
         * travel another path, are exactly where it sits alone.
         */
        fun ofLanguage(downloaded: WordSource?, imported: WordSource): WordSource =
            CompositeWordSource(listOfNotNull(downloaded, imported), own = listOfNotNull(downloaded))
    }
}
