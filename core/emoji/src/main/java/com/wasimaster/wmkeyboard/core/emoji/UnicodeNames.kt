package com.wasimaster.wmkeyboard.core.emoji

import java.util.Locale

/**
 * Every named character up to the end of plane 3, searchable by the words of
 * its Unicode name (#385): "latin small letter a with ogonek", "greek capital
 * letter omega", "em dash", "copyright sign", "musical symbol g clef".
 *
 * Built once, on first use and off the main thread, from [Character.getName]:
 * some 44,000 names, which is why it is not built until a search asks for it.
 * Names Unicode makes up from the code point ("CJK UNIFIED IDEOGRAPH-4E00",
 * "TANGUT IDEOGRAPH-17000") are left out: no word in them names the
 * character, and the ideographs alone would more than double the index. Emoji
 * are deliberately not what this is for — the catalogue knows them by better
 * words than their names — but nothing excludes them, so a query that hits
 * one finds the same thing twice, and the caller drops the duplicate.
 *
 * The suggestion strip asks a stricter question, [forTyped] (#1152): which
 * characters are called exactly what was just typed.
 *
 * Both tables are packed into byte arrays rather than held as objects. Unicode
 * names are spelled in A-Z, 0-9, space and hyphen alone (the Unicode
 * Standard's name character set), so a name is one byte a character, and the
 * search's table is about 1.1 MB and the strip's 1.2 MB. As a [String], a
 * lowercased copy and a list of split words per character the search's was
 * about 15 MB of Java heap, and the strip's, a `HashMap<String,
 * List<String>>` built over it, another 3.2 MB (measured on a desktop JVM
 * over its 43,600 names, 2026-11-26): both held for as long as the strip
 * kept asking, since the strip's table needed the search's to build.
 */
object UnicodeNames {

    /** The category every character found here reports, so a caller can tell them from the catalogue's emoji. */
    const val CATEGORY = "unicode"

    private const val FIRST = 0x00A1
    // The end of plane 3. Plane 14 holds only tags and variation selectors,
    // and the planes past it are private use.
    private const val LAST = 0x3FFFF

    /**
     * Strings packed back to back: string `i` is `bytes[start[i] until
     * start[i + 1]]`, one Latin-1 byte a character, and [codePoints] holds
     * what it names. [values] is null when every string names one character
     * ([codePoints] parallel to the strings); otherwise string `i` names
     * `codePoints[values[i] until values[i + 1]]`.
     */
    private class Packed(
        val bytes: ByteArray,
        val start: IntArray,
        val codePoints: IntArray,
        val values: IntArray?,
    ) {
        val size: Int get() = start.size - 1

        fun length(i: Int): Int = start[i + 1] - start[i]

        fun text(i: Int): String = String(bytes, start[i], length(i), Charsets.ISO_8859_1)

        /** Compares string [i] with [other] the way [String.compareTo] would. */
        fun compare(i: Int, other: String): Int {
            val from = start[i]
            val len = length(i)
            val n = minOf(len, other.length)
            for (k in 0 until n) {
                val a = bytes[from + k].toInt() and 0xFF
                val b = other[k].code
                if (a != b) return a - b
            }
            return len - other.length
        }

        /** Compares strings [i] and [j] the way [String.compareTo] would. */
        fun compare(i: Int, j: Int): Int {
            val a0 = start[i]
            val b0 = start[j]
            val la = length(i)
            val lb = length(j)
            val n = minOf(la, lb)
            for (k in 0 until n) {
                val a = bytes[a0 + k].toInt() and 0xFF
                val b = bytes[b0 + k].toInt() and 0xFF
                if (a != b) return a - b
            }
            return la - lb
        }

        /** The slot holding exactly [key], or -1. */
        fun find(key: String): Int {
            var low = 0
            var high = size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cmp = compare(mid, key)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
    }

    /** Growable byte and int buffers for building a [Packed] without boxing. */
    private class Builder(capacity: Int) {
        var bytes = ByteArray(capacity * AVERAGE_NAME_LENGTH)
        var byteCount = 0
        var start = IntArray(capacity + 1)
        var codePoints = IntArray(capacity)
        var count = 0

        /** Appends [text] (Latin-1 only, see [isPackable]) naming [codePoint]. */
        fun add(text: String, codePoint: Int) {
            if (byteCount + text.length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, byteCount + text.length))
            if (count + 2 > start.size) start = start.copyOf(start.size * 2)
            if (count + 1 > codePoints.size) codePoints = codePoints.copyOf(codePoints.size * 2)
            start[count] = byteCount
            for (c in text) bytes[byteCount++] = c.code.toByte()
            codePoints[count++] = codePoint
            start[count] = byteCount
        }

        fun build(): Packed = Packed(bytes.copyOf(byteCount), start.copyOf(count + 1), codePoints.copyOf(count), null)
    }

    /** Every name, lowercased, in code point order: what [search] scans. */
    @Volatile
    private var index: Packed? = null

    /** What [forTyped] looks a phrase up in: phrases sorted, each with its characters in code point order. */
    @Volatile
    private var aliases: Packed? = null

    private fun entries(): Packed = index ?: synchronized(this) {
        index ?: buildIndex().also { index = it }
    }

    /** Calls [action] with every name worth indexing, lowercased, in code point order. */
    private inline fun forEachName(action: (Int, String) -> Unit) {
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
            if (!isPackable(lower)) continue
            action(cp, lower)
        }
    }

    private fun buildIndex(): Packed {
        val out = Builder(NAME_CAPACITY)
        forEachName { cp, name -> out.add(name, cp) }
        return out.build()
    }

    /**
     * Whether [text] fits one byte a character. Every Unicode name does; the
     * check only keeps a platform that answered otherwise from corrupting the
     * table.
     */
    private fun isPackable(text: String): Boolean = text.all { it.code in 1..0xFF }

    /**
     * Whether Unicode spelled [name] out of the code point itself, as it does
     * for the ideographs and a few other large scripts: the name ends in the
     * code point's hex digits and holds nothing else to search by.
     *
     * Android's ICU writes those as `CJK UNIFIED IDEOGRAPH-4E00`. A desktop
     * JDK, where the unit tests run, falls back to the block name and a space
     * for every character UnicodeData does not name (`CJK UNIFIED IDEOGRAPHS
     * 4E00`, `HANGUL SYLLABLES AC00`), so both spellings count.
     *
     * Public rather than internal so `UnicodeNamesTest` can reach it: that
     * test compiles in `:app`, a different Kotlin module from this one.
     */
    fun isMadeUpName(cp: Int, name: String): Boolean {
        val hex = Integer.toHexString(cp).uppercase(Locale.ROOT)
        return name.endsWith("-$hex") || name.endsWith(" $hex")
    }

    /** Builds the index ahead of the first search, off whatever thread calls it. */
    fun warm() {
        entries()
    }

    /**
     * Drops both tables. The next search, or the next strip lookup, rebuilds
     * the one it needs.
     *
     * Worth a release of its own even packed: about 1.1 MB for the names and
     * 1.2 MB for the strip's phrases, built the first time somebody searches
     * the emoji panel for a character by name or turns on the strip's
     * character suggestions. A keyboard is the wrong process to keep a Unicode
     * database in.
     *
     * Safe against a lookup in flight: [search] and [forTyped] read their
     * table once into a local, so a release mid-search leaves that search
     * working on the table it already has and only costs the next one a
     * rebuild.
     */
    fun release() {
        index = null
        aliases = null
    }

    /**
     * Drops the search's table alone, about 1.1 MB, and keeps the strip's
     * phrases: what a panel that has closed no longer needs while plain
     * typing, whose strip may still be asking [forTyped] on every word.
     */
    fun releaseSearch() {
        index = null
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
        val names = entries()
        val matched = ArrayList<Int>()
        val scores = ArrayList<Int>()
        for (i in 0 until names.size) {
            var score = 0
            for (token in tokens) {
                val hit = wordMatch(names, i, token)
                if (hit == NO_MATCH) {
                    score = -1
                    break
                }
                score += hit
            }
            if (score > 0) {
                matched += i
                scores += score
            }
        }
        val order = matched.indices.sortedWith { a, b ->
            val byScore = scores[b].compareTo(scores[a])
            val x = matched[a]
            val y = matched[b]
            val byLength = names.length(x).compareTo(names.length(y))
            when {
                byScore != 0 -> byScore
                byLength != 0 -> byLength
                else -> names.compare(x, y)
            }
        }
        return order.take(limit).map { slot ->
            val i = matched[slot]
            val name = names.text(i)
            EmojiEntry(
                emoji = String(Character.toChars(names.codePoints[i])),
                category = CATEGORY,
                keywords = name.split(' ', '-').filter { it.isNotEmpty() },
                name = name.replaceFirstChar { it.titlecase(Locale.ROOT) },
            )
        }
    }

    /**
     * How name [i] answers [token]: [WHOLE_WORD] when one of its words (split
     * at spaces and hyphens) is [token], [WORD_START] when one starts with
     * it, [NO_MATCH] otherwise.
     */
    private fun wordMatch(names: Packed, i: Int, token: String): Int {
        val bytes = names.bytes
        val from = names.start[i]
        val end = names.start[i + 1]
        var best = NO_MATCH
        var w = from
        while (w < end) {
            // Skip separators to the next word's first byte.
            while (w < end && isSeparator(bytes[w])) w++
            if (w >= end) break
            var wordEnd = w
            while (wordEnd < end && !isSeparator(bytes[wordEnd])) wordEnd++
            val len = wordEnd - w
            if (len >= token.length && startsWith(bytes, w, token)) {
                if (len == token.length) return WHOLE_WORD
                best = WORD_START
            }
            w = wordEnd
        }
        return best
    }

    private fun isSeparator(b: Byte): Boolean = b == SPACE || b == HYPHEN

    private fun startsWith(bytes: ByteArray, at: Int, token: String): Boolean {
        for (k in token.indices) {
            if ((bytes[at + k].toInt() and 0xFF) != token[k].code) return false
        }
        return true
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
            val slot = table.find(phrase)
            if (slot >= 0) {
                val values = table.values ?: continue
                for (v in values[slot] until values[slot + 1]) {
                    found.putIfAbsent(String(Character.toChars(table.codePoints[v])), n)
                }
            }
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

    private fun aliasTable(): Packed = aliases ?: synchronized(this) {
        aliases ?: buildAliases().also { aliases = it }
    }

    /**
     * The strip's phrase table. Read straight from the names rather than
     * from [index], so a strip that only ever asks this question never holds
     * the search's table too.
     */
    private fun buildAliases(): Packed {
        // Every (phrase, character) pair in code point order, the phrases
        // themselves only until they are packed.
        val phrases = ArrayList<String>(NAME_CAPACITY + NAME_CAPACITY / 8)
        var cps = IntArray(NAME_CAPACITY + NAME_CAPACITY / 8)
        fun put(key: String, cp: Int) {
            if (phrases.size == cps.size) cps = cps.copyOf(cps.size * 2)
            cps[phrases.size] = cp
            phrases += key
        }
        forEachName { cp, lower ->
            val name = lower.replace('-', ' ')
            put(name, cp)
            for (suffix in DROPPABLE_SUFFIXES) {
                if (name.endsWith(suffix) && name.length > suffix.length) put(name.removeSuffix(suffix), cp)
            }
            when {
                name.startsWith(GREEK_SMALL) && ' ' !in name.substring(GREEK_SMALL.length) ->
                    put(name.substring(GREEK_SMALL.length), cp)
                name.startsWith(GREEK_CAPITAL) && ' ' !in name.substring(GREEK_CAPITAL.length) ->
                    put("capital " + name.substring(GREEK_CAPITAL.length), cp)
            }
        }
        // Stable, so the characters one phrase names stay in code point order.
        val order = phrases.indices.sortedWith { a, b -> phrases[a].compareTo(phrases[b]) }
        val keys = Builder(phrases.size)
        val values = IntArray(phrases.size + 1)
        val chars = IntArray(phrases.size)
        var charCount = 0
        var previous: String? = null
        for (slot in order) {
            val phrase = phrases[slot]
            val cp = cps[slot]
            if (phrase != previous) {
                values[keys.count] = charCount
                keys.add(phrase, cp)
                previous = phrase
            } else if (chars[charCount - 1] == cp) {
                // The same character under the same phrase twice; one
                // character's pairs are adjacent after the stable sort.
                continue
            }
            chars[charCount++] = cp
        }
        values[keys.count] = charCount
        return Packed(
            bytes = keys.bytes.copyOf(keys.byteCount),
            start = keys.start.copyOf(keys.count + 1),
            codePoints = chars.copyOf(charCount),
            values = values.copyOf(keys.count + 1),
        )
    }

    /**
     * Characters [buildIndex] expects to find, to size its buffers once:
     * about 43,600 on Unicode 15 with the made-up names left out.
     */
    private const val NAME_CAPACITY = 48_000

    /** Bytes [Builder] reserves a name; the names average about 25. */
    private const val AVERAGE_NAME_LENGTH = 26

    private const val SPACE = ' '.code.toByte()
    private const val HYPHEN = '-'.code.toByte()

    private const val MAX_WORDS_BEFORE = 3
    private val DROPPABLE_SUFFIXES = listOf(" sign", " symbol")
    private const val GREEK_SMALL = "greek small letter "
    private const val GREEK_CAPITAL = "greek capital letter "

    private const val WHOLE_WORD = 10
    private const val WORD_START = 4
    private const val NO_MATCH = 0
}
