package com.wasimaster.wmkeyboard.core.emoji

import java.util.Locale

/**
 * Every named character up to the end of plane 3, searchable by the words of
 * its Unicode name (#385): "latin small letter a with ogonek", "greek capital
 * letter omega", "em dash", "copyright sign", "musical symbol g clef".
 *
 * Built once, on first use and off the main thread, from [Character.getName]:
 * tens of thousands of names, a few megabytes, which is why it is not built
 * until a search asks for it. Names Unicode makes up from the code point
 * ("CJK UNIFIED IDEOGRAPH-4E00", "TANGUT IDEOGRAPH-17000") are left out: no
 * word in them names the character, and the ideographs alone would more than
 * double the index. Emoji are deliberately not what this is for — the
 * catalogue knows them by better words than their names — but nothing
 * excludes them, so a query that hits one finds the same thing twice, and the
 * caller drops the duplicate.
 *
 * The suggestion strip asks a stricter question, [forTyped] (#1152): which
 * characters are called exactly what was just typed.
 */
object UnicodeNames {

    /** The category every character found here reports, so a caller can tell them from the catalogue's emoji. */
    const val CATEGORY = "unicode"

    private const val FIRST = 0x00A1
    // The end of plane 3. Plane 14 holds only tags and variation selectors,
    // and the planes past it are private use.
    private const val LAST = 0x3FFFF

    private class Named(val text: String, val name: String, val words: List<String>)

    @Volatile
    private var index: List<Named>? = null

    /** What [forTyped] looks a phrase up in; built from [index] on its first call. */
    @Volatile
    private var aliases: Map<String, List<String>>? = null

    private fun entries(): List<Named> = index ?: synchronized(this) {
        index ?: buildIndex().also { index = it }
    }

    private fun buildIndex(): List<Named> {
        val out = ArrayList<Named>(60_000)
        for (cp in FIRST..LAST) {
            when (Character.getType(cp).toByte()) {
                Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE,
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR,
                -> continue
            }
            val name = Character.getName(cp) ?: continue
            if (isMadeUpName(cp, name)) continue
            val lower = name.lowercase(Locale.ROOT)
            out += Named(String(Character.toChars(cp)), lower, lower.split(' ', '-').filter { it.isNotEmpty() })
        }
        return out
    }

    /**
     * Whether Unicode spelled [name] out of the code point itself, as it does
     * for the ideographs and a few other large scripts: the name ends in the
     * code point's hex digits and holds nothing else to search by.
     *
     * Public rather than internal so `UnicodeNamesTest` can reach it: that
     * test compiles in `:app`, a different Kotlin module from this one.
     */
    fun isMadeUpName(cp: Int, name: String): Boolean =
        name.endsWith("-" + Integer.toHexString(cp).uppercase(Locale.ROOT))

    /** Builds the index ahead of the first search, off whatever thread calls it. */
    fun warm() {
        entries()
    }

    /**
     * Drops the index. The next search rebuilds it.
     *
     * Worth a release of its own because of what this costs to hold: every
     * assigned character from [FIRST] to [LAST] that is not a control, format,
     * separator, surrogate or private-use one — some fifty thousand of them,
     * the CJK ideographs and the eleven thousand Hangul syllables included —
     * each with a [Named] of its own, its own copy of the lowercased name, the
     * character as a `String`, and a list of the name's words split out for
     * matching. That is tens of megabytes, built the first time somebody
     * searches the emoji panel for a character by name, and until this existed
     * it stayed for the life of the process whether or not they ever searched
     * again. A keyboard is the wrong process to keep a Unicode database in.
     *
     * Nothing else holds a [Named], so dropping the list is the whole of it.
     * Safe against a search in flight: [search] reads [entries] once into a
     * local, so a release mid-search leaves that search working on the list it
     * already has and only costs the next one a rebuild.
     */
    fun release() {
        index = null
        aliases = null
    }

    /**
     * Characters whose name holds every word of [query], best first, at most
     * [limit]. A query word matches a name word whole or as its start, whole
     * counting for more; ties go to the shorter name, so "dash" puts EM DASH
     * ahead of WAVE DASH ahead of the longer compounds.
     */
    fun search(query: String, limit: Int = 20): List<EmojiEntry> {
        // Unicode spells the Greek letter LAMDA; people type lambda.
        val tokens = query.lowercase(Locale.ROOT).split(Regex("[\\s_]+")).filter { it.isNotEmpty() }
            .map { it.replace("lambd", "lamd") }
        if (tokens.isEmpty() || tokens.all { it.length < 2 }) return emptyList()
        val scored = ArrayList<Pair<Named, Int>>()
        for (entry in entries()) {
            var score = 0
            for (token in tokens) {
                score += when {
                    token in entry.words -> WHOLE_WORD
                    entry.words.any { it.startsWith(token) } -> WORD_START
                    else -> {
                        score = -1
                        break
                    }
                }
            }
            if (score > 0) scored += entry to score
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Named, Int>> { it.second }
                    .thenBy { it.first.name.length }
                    .thenBy { it.first.name },
            )
            .take(limit)
            .map { (entry, _) ->
                EmojiEntry(
                    emoji = entry.text,
                    category = CATEGORY,
                    keywords = entry.words,
                    name = entry.name.replaceFirstChar { it.titlecase(Locale.ROOT) },
                )
            }
    }

    /**
     * A character the strip offers for what was just typed, and how many of
     * the words committed before the one being typed its name took up: one
     * for "em" when "em dash" is the name and "dash" is still being typed.
     */
    data class StripMatch(val text: String, val wordsBefore: Int)

    /**
     * The characters named exactly [typed], read together with up to three
     * of the words before it ([before], nearest first), longest phrase first
     * and at most [limit] (#1152). Exact rather than by word starts, because
     * the strip asks on every key and must stay quiet for ordinary prose:
     * "snowman" is ☃, "em dash" is —, but "dash" alone is nothing.
     *
     * A name is also known without a trailing "sign" or "symbol" ("degree" for
     * DEGREE SIGN, "euro" for EURO SIGN), and a Greek letter by its own name
     * ("lambda", or "capital lambda" for the capital).
     */
    fun forTyped(typed: String, before: List<String>, limit: Int = 3): List<StripMatch> {
        val word = normalize(typed)
        if (word.length < 2 || ' ' in word) return emptyList()
        val table = aliasTable()
        val found = LinkedHashMap<String, Int>()
        for (n in minOf(MAX_WORDS_BEFORE, before.size) downTo 0) {
            val words = before.take(n).reversed().map(::normalize)
            if (words.any { it.isEmpty() || ' ' in it }) continue
            val phrase = (words + word).joinToString(" ")
            table[phrase]?.forEach { found.putIfAbsent(it, n) }
            if (found.size >= limit) break
        }
        return found.entries.take(limit).map { StripMatch(it.key, it.value) }
    }

    /**
     * How many characters at the end of [text] the [words] take up, with the
     * spaces after the last of them: what to delete so a character can stand
     * in for its name. Zero when the end of [text] is not those words, so a
     * caret moved somewhere else since never deletes the wrong thing.
     */
    fun trailingWordsLength(text: CharSequence, words: List<String>): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        var start = end
        for (i in words.indices.reversed()) {
            var wordStart = start
            while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) wordStart--
            if (wordStart == start) return 0
            val word = text.subSequence(wordStart, start).toString()
            if (normalize(word) != normalize(words[i])) return 0
            start = wordStart
            // The space in front of the first word is the one the character
            // goes after, so it stays.
            if (i > 0) {
                while (start > 0 && text[start - 1].isWhitespace()) start--
            }
        }
        return text.length - start
    }

    // Unicode spells the Greek letter LAMDA, as [search] knows too.
    private fun normalize(word: String): String =
        word.lowercase(Locale.ROOT).replace('-', ' ').trim().replace(Regex("\\s+"), " ")
            .replace("lambd", "lamd")

    private fun aliasTable(): Map<String, List<String>> = aliases ?: synchronized(this) {
        aliases ?: buildAliases(entries()).also { aliases = it }
    }

    private fun buildAliases(entries: List<Named>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>(entries.size * 2)
        fun put(key: String, text: String) {
            val list = out.getOrPut(key) { ArrayList(1) }
            if (text !in list) list += text
        }
        for (entry in entries) {
            val name = entry.name.replace('-', ' ')
            put(name, entry.text)
            for (suffix in DROPPABLE_SUFFIXES) {
                if (name.endsWith(suffix) && name.length > suffix.length) put(name.removeSuffix(suffix), entry.text)
            }
            when {
                name.startsWith(GREEK_SMALL) && ' ' !in name.substring(GREEK_SMALL.length) ->
                    put(name.substring(GREEK_SMALL.length), entry.text)
                name.startsWith(GREEK_CAPITAL) && ' ' !in name.substring(GREEK_CAPITAL.length) ->
                    put("capital " + name.substring(GREEK_CAPITAL.length), entry.text)
            }
        }
        return out
    }

    private const val MAX_WORDS_BEFORE = 3
    private val DROPPABLE_SUFFIXES = listOf(" sign", " symbol")
    private const val GREEK_SMALL = "greek small letter "
    private const val GREEK_CAPITAL = "greek capital letter "

    private const val WHOLE_WORD = 10
    private const val WORD_START = 4
}
