package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.SortedWords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.GZIPInputStream

/**
 * Desktop Avro's candidate list, built the way ibus-avro builds it and out of
 * ibus-avro's own data, so every word desktop Avro offers for a spelling is
 * offered here too (#516).
 *
 * The data ships as `assets/avro/` under the Mozilla Public License 1.1, as
 * OmicronLab published it; this file is the keyboard's own reader for it:
 *
 *  - `phonetic.json`: Avro's transliteration grammar, which gives the reading
 *    the list ends with and the one it is sorted against.
 *  - `regex.json`: the looser grammar Avro turns a spelling into a regular
 *    expression with, to find every dictionary word the spelling could be.
 *  - `search.json`: which tables a romanized first letter searches, and the
 *    first letters each table holds.
 *
 * Avro's word list itself, in its tables by first letter, is downloaded
 * (`AvroDictionaryDownloads`, from the data repo) rather than shipped: it is
 * most of the weight and only the candidate list wants it. Until it is here
 * the search runs over the keyboard's own list alone.
 *  - `suffix.json`: the endings read off a word to find its base.
 *  - `autocorrect.json`: Avro's fixed spellings, the list's first entry.
 *
 * The keyboard's own word list is searched with the same expression, so a word
 * Avro's list lacks and ours has is found too; the list is Avro's plus that.
 */
class AvroDesktop private constructor(
    private val phonetic: AvroGrammar,
    private val regex: AvroGrammar,
    private val suffixes: Map<String, String>,
    private val autocorrect: Map<String, String>,
    private val letters: Map<Char, List<String>>,
    private val tables: Map<String, FlatWords>,
    /** First letters of each table's words, for finding them in another list. */
    private val tableInitials: Map<String, Set<Char>>,
) {

    /** What [suggest] found, in Avro's order. */
    class Suggestion(
        /** Avro's fixed spelling for what was typed, first in its list. */
        val autocorrect: String?,
        /** Dictionary words and suffixed bases, nearest to [phonetic] first. */
        val words: List<String>,
        /** Avro's letter-for-letter reading, last in its list. */
        val phonetic: String,
    )

    /** Avro's reading of [roman], letter for letter. */
    fun transliterate(roman: String): String = phonetic.parse(roman)

    /**
     * Avro's list for [typed], with [ours] searched alongside Avro's own word
     * list and [extra] (words the caller found by other means) sorted in with
     * the dictionary words.
     *
     * Avro fills a cache as each letter is typed and joins suffixes onto the
     * bases it has cached; a base is in that cache exactly when the user typed
     * it on the way to the word, which is every prefix of it, so here every
     * prefix with a known suffix after it counts.
     */
    fun suggest(typed: String, ours: SortedWords?, extra: List<String> = emptyList()): Suggestion {
        val middle = typed.trim { it in PADDING }
        // Punctuation either side is read on its own (`.` is the danda) and
        // put back round every word, as Avro does; an exact autocorrect aside.
        val lead = phonetic.parse(typed.takeWhile { it in PADDING })
        val trail = phonetic.parse(typed.substring(typed.length - typed.takeLastWhile { it in PADDING }.length)
            .takeIf { middle.isNotEmpty() }.orEmpty())
        if (lead.isNotEmpty() || trail.isNotEmpty()) {
            val inner = suggest(middle, ours, extra)
            val auto = autocorrectOf(typed, middle)
            return Suggestion(
                autocorrect = auto?.let { if (it.exact) it.text else lead + it.text + trail },
                words = inner.words.map { lead + it + trail },
                phonetic = lead + inner.phonetic + trail,
            )
        }
        val reading = phonetic.parse(middle)
        val auto = autocorrectOf(typed, middle)
        val key = middle.lowercase()
        val pool = ArrayList<String>()
        pool += baseWords(key, middle, ours)
        if (key.length >= 2) {
            for (split in 1 until key.length) {
                val suffix = suffixes[key.substring(split)] ?: continue
                for (base in baseWords(key.substring(0, split), middle.substring(0, split), ours)) {
                    pool += joinSuffix(base, suffix)
                }
            }
        }
        pool += extra
        val seen = HashSet<String>()
        val distinct = pool.filter { seen.add(precomposed(it)) }
        val target = reading
        // Stable, so words the same distance away keep the order they were
        // found in: Avro's tables in Avro's order, then the keyboard's words.
        val sorted = distinct.sortedBy { damerauLevenshtein(target, precomposed(it)) }
        return Suggestion(auto?.text, sorted, reading)
    }

    /** The cache entry Avro would hold for [key]: its dictionary words, then a non-exact autocorrect. */
    private fun baseWords(key: String, typed: String, ours: SortedWords?): List<String> {
        val words = search(key, ours)
        val auto = autocorrectOf(typed, typed)
        return if (auto != null && !auto.exact) words + auto.text else words
    }

    private class Autocorrect(val text: String, val exact: Boolean)

    private fun autocorrectOf(word: String, middle: String): Autocorrect? {
        autocorrect[word]?.let { value ->
            // An entry mapped to itself (the smileys) is typed as it is.
            return if (value == word) Autocorrect(word, true) else Autocorrect(phonetic.parse(value), false)
        }
        return autocorrect[phonetic.caseFixed(middle)]?.let { Autocorrect(phonetic.parse(it), false) }
    }

    /** The keyboard's list [searchCache] was filled from. */
    private var cachedFor: SortedWords? = null

    private val searchCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > SEARCH_CACHE
    }

    /**
     * Every word of Avro's tables for [key]'s first letter, then every word of
     * [ours] with the same first letters, that [key]'s expression matches whole.
     */
    private fun search(key: String, ours: SortedWords?): List<String> {
        synchronized(searchCache) {
            // A word list downloaded or imported since is a new list to search.
            if (ours !== cachedFor) {
                searchCache.clear()
                cachedFor = ours
            }
            searchCache[key]?.let { return it }
        }
        val names = key.firstOrNull()?.let(letters::get).orEmpty()
        val compiled = regex.compile(key)
        val out = ArrayList<String>()
        if (compiled != null && names.isNotEmpty()) {
            val matcher = compiled.matcher("")
            val seen = HashSet<String>()
            for (name in names) {
                val table = tables[name] ?: continue
                val hits = ArrayList<Int>()
                walkAll(table, matcher) { hits += it }
                hits.sortBy(table::rank)
                for (i in hits) table.word(i).let { if (seen.add(it)) out += it }
            }
            if (ours != null) {
                val initials = HashSet<Char>()
                for (name in names) tableInitials[name]?.let(initials::addAll)
                // A nukta letter is two characters in a list kept in NFC.
                for (c in initials.toList()) NUKTA_BASE[c]?.let(initials::add)
                val hits = ArrayList<Int>()
                for (c in initials) {
                    val from = lowerBound(ours, 0, ours.size, 0, c)
                    val to = lowerBound(ours, from, ours.size, 0, c + 1)
                    if (from < to) walk(ours, from, to, 1, StringBuilder().append(c), matcher) { hits += it }
                }
                hits.sortBy(ours::rank)
                for (i in hits) ours.word(i).let { if (seen.add(precomposed(it))) out += it }
            }
        }
        synchronized(searchCache) { if (ours === cachedFor) searchCache[key] = out }
        return out
    }

    private fun walkAll(words: SortedWords, matcher: Matcher, emit: (Int) -> Unit) {
        var i = 0
        while (i < words.size) {
            val c = words.charAt(i, 0)
            val j = lowerBound(words, i, words.size, 0, c + 1)
            walk(words, i, j, 1, StringBuilder().append(c), matcher, emit)
            i = j
        }
    }

    /**
     * The words of `[lo, hi)`, which all begin with [prefix], that [matcher]'s
     * expression matches whole: the list walked as a tree of prefixes, and a
     * branch left as soon as no ending could make its prefix match.
     */
    private fun walk(
        words: SortedWords,
        lo: Int,
        hi: Int,
        depth: Int,
        prefix: StringBuilder,
        matcher: Matcher,
        emit: (Int) -> Unit,
    ) {
        matcher.reset(precomposed(prefix.toString()))
        val full = matcher.matches()
        // hitEnd false: no longer input could change the verdict. Except at a
        // nukta's base letter, which a list in NFC writes before its dot.
        val open = matcher.hitEnd() || prefix.last() in NUKTA_BASE.values
        if (!full && !open) return
        var i = lo
        while (i < hi && words.length(i) == depth) {
            if (full) emit(i)
            i++
        }
        if (!open) return
        while (i < hi) {
            val c = words.charAt(i, depth)
            val j = lowerBound(words, i, hi, depth, c + 1)
            prefix.append(c)
            walk(words, i, j, depth + 1, prefix, matcher, emit)
            prefix.setLength(depth)
            i = j
        }
    }

    /** First index in `[lo, hi)` whose character at [at] is not below [c]; all are longer than [at]. */
    private fun lowerBound(words: SortedWords, lo: Int, hi: Int, at: Int, c: Char): Int {
        var low = lo
        var high = hi
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words.length(mid) <= at || words.charAt(mid, at) < c) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * [base] with [suffix] after it, joined as Avro joins them: a য় between a
     * vowel and a vowel sign, ৎ opened back to ত and ং to ঙ before an ending.
     */
    private fun joinSuffix(base: String, suffix: String): String {
        val word = precomposed(base)
        val last = word.lastOrNull() ?: return suffix
        return when {
            last in AVRO_VOWELS && suffix.firstOrNull()?.let { it in AVRO_KARS } == true -> word + 'য়' + suffix
            last == 'ৎ' -> word.dropLast(1) + 'ত' + suffix
            last == 'ং' -> word.dropLast(1) + 'ঙ' + suffix
            else -> word + suffix
        }
    }

    companion object {
        /** Spellings whose dictionary search is kept, most recent first. */
        private const val SEARCH_CACHE = 256

        /** Punctuation Avro peels off either end of a word before reading it. */
        private const val PADDING = "-]~!@#%&*()_=+[{}'\";<>/?|.,`:"

        private const val AVRO_VOWELS = "অআইঈউঊঋএঐওঔঌৡািীুূৃেৈোৌ"
        private const val AVRO_KARS = "ািীুূৃেৈোৌৄ"

        /** Each nukta letter's base: the first of the two characters NFC writes it as. */
        private val NUKTA_BASE = mapOf('ড়' to 'ড', 'ঢ়' to 'ঢ', 'য়' to 'য')

        /** The decomposed nukta pairs as the precomposed letters Avro's data is written in. */
        fun precomposed(word: String): String {
            if (word.indexOf('়') < 0) return word
            return word
                .replace("ড়", "ড়")
                .replace("ঢ়", "ঢ়")
                .replace("য়", "য়")
        }

        /**
         * Edit distance with adjacent transpositions, as Avro sorts its list by:
         * the optimal-string-alignment form, over UTF-16 units.
         */
        fun damerauLevenshtein(a: String, b: String): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        v = minOf(v, d[i - 2][j - 2] + cost)
                    }
                    d[i][j] = v
                }
            }
            return d[a.length][b.length]
        }

        /**
         * Desktop Avro's endings alone, romanized ending to its Bengali, out
         * of `suffix.json` ([input]): what the main strip joins onto a known
         * stem without loading the rest of the data.
         */
        fun readSuffixes(input: InputStream): Map<String, String> =
            Json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
                .getValue("suffixes").jsonObject.mapValues { it.value.jsonPrimitive.content }

        /**
         * Reads the five files [open] names (`phonetic.json` and the rest of
         * `assets/avro/`) and, when there is one, the downloaded [dictionary].
         */
        fun load(open: (String) -> InputStream, dictionary: (() -> InputStream)? = null): AvroDesktop {
            fun json(name: String): JsonObject =
                open(name).use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            val phonetic = AvroGrammar.of(json("phonetic.json"), regexMode = false)
            val regex = AvroGrammar.of(json("regex.json"), regexMode = true)
            val suffixes = json("suffix.json").getValue("suffixes").jsonObject
                .mapValues { it.value.jsonPrimitive.content }
            val autocorrect = json("autocorrect.json").getValue("words").jsonObject
                .mapValues { it.value.jsonPrimitive.content }
            val search = json("search.json")
            val letters = search.getValue("letters").jsonObject.entries.associate { (letter, names) ->
                letter[0] to names.jsonArray.map { it.jsonPrimitive.content }
            }
            val initials = search.getValue("initials").jsonObject.entries.associate { (name, chars) ->
                name to chars.jsonPrimitive.content.toSet()
            }
            val grouped = LinkedHashMap<String, MutableList<String>>()
            dictionary?.let { GZIPInputStream(it()) }?.bufferedReader()?.useLines { lines ->
                var current: MutableList<String>? = null
                for (line in lines) {
                    when {
                        line.isEmpty() || line.startsWith('#') -> Unit
                        line.startsWith('@') -> current = grouped.getOrPut(line.substring(1)) { ArrayList() }
                        else -> current?.add(line)
                    }
                }
            }
            val tables = grouped.entries.associate { (name, words) -> name to FlatWords(words) }
            return AvroDesktop(phonetic, regex, suffixes, autocorrect, letters, tables, initials)
        }
    }
}

/**
 * One of Avro's tables, sorted for the prefix walk, each word ranked by its
 * place in the table so the matches come back in Avro's order.
 */
private class FlatWords(words: List<String>) : SortedWords {
    private val order: IntArray = words.indices.sortedWith { a, b -> words[a].compareTo(words[b]) }.toIntArray()
    private val chars: CharArray
    private val start: IntArray = IntArray(words.size + 1)

    init {
        var length = 0
        for (word in words) length += word.length
        chars = CharArray(length)
        var at = 0
        for ((i, index) in order.withIndex()) {
            start[i] = at
            val word = words[index]
            word.toCharArray(chars, at)
            at += word.length
        }
        start[words.size] = at
    }

    override val size: Int get() = order.size
    override fun length(i: Int): Int = start[i + 1] - start[i]
    override fun charAt(i: Int, at: Int): Char = chars[start[i] + at]
    override fun word(i: Int): String = String(chars, start[i], start[i + 1] - start[i])
    override fun rank(i: Int): Long = order[i].toLong()
}

/**
 * One of Avro's two grammars: patterns tried longest first at each position,
 * each with rules on what may stand before or after it. In regex mode every
 * replacement is a fragment of a regular expression, followed by the optional
 * ফলা, hasant and sign Avro allows after any letter.
 */
internal class AvroGrammar private constructor(
    private val byFirst: Map<Char, List<Entry>>,
    private val vowel: String,
    private val consonant: String,
    private val caseSensitive: String,
    private val ignore: String,
    private val regexMode: Boolean,
) {
    private class Match(val suffix: Boolean, val scope: String, val negative: Boolean, val value: String)
    private class Rule(val matches: List<Match>, val replace: String)
    private class Entry(val find: String, val replace: String, val rules: List<Rule>)

    /** [input] as Avro reads it before applying the patterns. */
    fun caseFixed(input: String): String = buildString {
        for (c in input) {
            when {
                regexMode && ignore.indexOf(c.lowercaseChar()) >= 0 -> Unit
                regexMode -> append(c.lowercaseChar())
                caseSensitive.indexOf(c.lowercaseChar()) >= 0 -> append(c)
                else -> append(c.lowercaseChar())
            }
        }
    }

    fun parse(input: String): String {
        val fixed = caseFixed(input)
        val out = StringBuilder()
        var cur = 0
        while (cur < fixed.length) {
            val start = cur
            var replaced: String? = null
            var end = cur + 1
            for (entry in byFirst[fixed[cur]].orEmpty()) {
                end = cur + entry.find.length
                if (end > fixed.length || !fixed.regionMatches(start, entry.find, 0, entry.find.length)) continue
                replaced = entry.rules.firstOrNull { rule -> rule.matches.all { holds(it, fixed, start, end) } }
                    ?.replace ?: entry.replace
                break
            }
            if (replaced == null) {
                out.append(fixed[cur])
                cur++
            } else {
                out.append(replaced)
                if (regexMode) out.append(REGEX_TAIL)
                cur = end
            }
        }
        return out.toString()
    }

    /** [roman] as the anchored expression Avro searches its dictionary with, or null if it does not compile. */
    fun compile(roman: String): Pattern? = try {
        Pattern.compile("^" + parse(roman) + "$")
    } catch (_: PatternSyntaxException) {
        null
    }

    private fun holds(match: Match, fixed: String, start: Int, end: Int): Boolean {
        val chk = if (match.suffix) end else start - 1
        val result = when (match.scope) {
            "punctuation" -> (chk < 0 && !match.suffix) || (chk >= fixed.length && match.suffix) ||
                isPunctuation(fixed, chk)
            "vowel" -> ((chk >= 0 && !match.suffix) || (chk < fixed.length && match.suffix)) && isVowel(fixed, chk)
            "consonant" -> ((chk >= 0 && !match.suffix) || (chk < fixed.length && match.suffix)) &&
                isConsonant(fixed, chk)
            "exact" -> {
                val s = if (match.suffix) end else start - match.value.length
                val e = if (match.suffix) end + match.value.length else start
                // Avro's own bound, `end < length`, kept as it is: an exact
                // match reaching the last letter does not count there either.
                return (s >= 0 && e < fixed.length && fixed.substring(s, e) == match.value) xor match.negative
            }
            else -> true
        }
        return result xor match.negative
    }

    // Out of range is "" in Avro's JavaScript, which every indexOf finds.
    private fun isVowel(s: String, i: Int): Boolean = i !in s.indices || vowel.indexOf(s[i].lowercaseChar()) >= 0
    private fun isConsonant(s: String, i: Int): Boolean =
        i !in s.indices || consonant.indexOf(s[i].lowercaseChar()) >= 0
    private fun isPunctuation(s: String, i: Int): Boolean = !(isVowel(s, i) || isConsonant(s, i))

    companion object {
        private const val REGEX_TAIL = "(্[যবম])?(্?)([ঃঁ]?)"

        fun of(json: JsonObject, regexMode: Boolean): AvroGrammar {
            val entries = (json.getValue("patterns") as JsonArray).map { element ->
                val p = element.jsonObject
                val rules = (p["rules"] as? JsonArray).orEmpty().map { r ->
                    val rule = r.jsonObject
                    Rule(
                        matches = rule.getValue("matches").jsonArray.map { m ->
                            val match = m.jsonObject
                            val scope = match.getValue("scope").jsonPrimitive.content
                            Match(
                                suffix = match.getValue("type").jsonPrimitive.content == "suffix",
                                scope = scope.removePrefix("!"),
                                negative = scope.startsWith("!"),
                                value = match["value"]?.jsonPrimitive?.content.orEmpty(),
                            )
                        },
                        replace = rule.getValue("replace").jsonPrimitive.content,
                    )
                }
                Entry(p.getValue("find").jsonPrimitive.content, p.getValue("replace").jsonPrimitive.content, rules)
            }
            val byFirst = LinkedHashMap<Char, MutableList<Entry>>()
            for (entry in entries) {
                if (entry.find.isEmpty()) continue
                byFirst.getOrPut(entry.find[0]) { ArrayList() } += entry
            }
            fun text(name: String) = json[name]?.jsonPrimitive?.content.orEmpty()
            return AvroGrammar(byFirst, text("vowel"), text("consonant"), text("casesensitive"), text("ignore"), regexMode)
        }
    }
}
