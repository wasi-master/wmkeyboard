package com.wasimaster.wmkeyboard.core.engine

import com.wasimaster.wmkeyboard.core.text.StemMatch

/**
 * The stems the text searches compare: a text's words lowercased, stemmed
 * by the engine's light stemmers ([NativeEngine.stemText]) and joined by
 * single spaces: "Running late" is "run late", "two chats" is "two chat".
 *
 * The language is the text's: the keyboard language's tag (`en-GB`, `bn`,
 * `pt-BR`), or null when none applies. A word whose script is not that
 * language's is stemmed by its own script, so English words in a Bengali
 * clip still meet their other forms, and with a null tag every word goes by
 * its script alone.
 *
 * A search stems its query once and each candidate once: candidates are
 * cached here by their text, so typing into a search box stems the clips,
 * notes or snippets on the first letter and only the query after. Nothing
 * here is on the key-press path of the typing itself.
 *
 * Null everywhere when the native library did not load; a search then
 * matches only what was typed.
 */
object SearchStems {

    /** [text]'s stems in [languageTag], or null without the library. */
    fun stem(languageTag: String?, text: String): String? {
        if (!NativeEngine.loaded || text.isBlank()) return if (text.isBlank()) "" else null
        return try {
            NativeEngine.stemText(languageTag.orEmpty(), text)
        } catch (_: UnsatisfiedLinkError) {
            // A library built before the stemmers.
            null
        }
    }

    /**
     * [texts]' stems in [languageTag], from the cache where it has them and
     * in one crossing for the rest; null without the library.
     */
    fun stemAll(languageTag: String?, texts: List<String>): List<String>? {
        if (texts.isEmpty()) return emptyList()
        if (!NativeEngine.loaded) return null
        val tag = languageTag.orEmpty()
        val out = arrayOfNulls<String>(texts.size)
        val missing = ArrayList<Int>()
        synchronized(cache) {
            for ((i, text) in texts.withIndex()) {
                val hit = cache[Key(tag, text)]
                if (hit != null) out[i] = hit else missing.add(i)
            }
        }
        if (missing.isNotEmpty()) {
            val stems = try {
                NativeEngine.stemTexts(tag, Array(missing.size) { texts[missing[it]] })
            } catch (_: UnsatisfiedLinkError) {
                return null
            }
            synchronized(cache) {
                for ((j, i) in missing.withIndex()) {
                    out[i] = stems[j]
                    remember(Key(tag, texts[i]), stems[j])
                }
            }
        }
        return out.map { it.orEmpty() }
    }

    /** [text]'s stems, cached; for a candidate a search will see again. */
    fun cached(languageTag: String?, text: String): String? {
        val key = Key(languageTag.orEmpty(), text)
        synchronized(cache) { cache[key]?.let { return it } }
        val stems = stem(languageTag, text) ?: return null
        synchronized(cache) { remember(key, stems) }
        return stems
    }

    /**
     * A matcher for [query] over candidates' texts: true when every stem of
     * the query is a stem of the candidate's text. Null when the query has
     * no words or the library is missing, which [StemMatch.literalFirst]
     * reads as "match what was typed only".
     */
    fun <T> matcher(languageTag: String?, query: String, texts: (T) -> String): ((T) -> Boolean)? {
        val queryStems = stem(languageTag, query.trim())
        if (queryStems.isNullOrEmpty()) return null
        return { item -> cached(languageTag, texts(item))?.let { StemMatch.containsAll(queryStems, it) } == true }
    }

    /** Drops every cached stem, for tests. */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            cachedChars = 0
        }
    }

    private data class Key(val tag: String, val text: String)

    /**
     * The cache's size bound, in characters of cached text and stems
     * together: about the clipboard history, the notes and the snippets of
     * a heavy user at once, a few megabytes of heap at most.
     */
    private const val BUDGET_CHARS = 1_500_000

    private var cachedChars = 0

    /** Least recently used first, so the eldest go when the budget is met. */
    private val cache = LinkedHashMap<Key, String>(256, 0.75f, true)

    private fun remember(key: Key, stems: String) {
        val previous = cache.put(key, stems)
        if (previous != null) cachedChars -= key.text.length + previous.length
        cachedChars += key.text.length + stems.length
        val it = cache.entries.iterator()
        while (cachedChars > BUDGET_CHARS && it.hasNext()) {
            val eldest = it.next()
            cachedChars -= eldest.key.text.length + eldest.value.length
            it.remove()
        }
    }
}
