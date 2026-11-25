package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Syntax colouring from a plugin's grammar ([PluginSyntaxSpec]): a few lists
 * and regular expressions the keyboard runs itself, so colouring a file never
 * runs a plugin's Python.
 *
 * ```json
 * {"rules": [{"match": "^\\[[^\\]]+\\]", "kind": "heading", "flags": "m"}],
 *  "keywords": ["true", "false"], "types": [], "constants": [],
 *  "lineComment": ["#", ";"], "blockComment": ["<!--", "-->"],
 *  "strings": ["\"", "'", "\"\"\""], "escape": "\\", "numbers": true,
 *  "calls": false, "caseInsensitive": false}
 * ```
 *
 * At each position the [Grammar.rules] are tried first, in order, then the
 * block and line comments, the strings, a number and a word, which is a
 * keyword, type or constant when one of the lists holds it (or a function
 * name before `(` with `calls`). Nothing else is coloured.
 *
 * Every regex is compiled once per grammar, with Android's ICU engine on a
 * phone, and a grammar with one that does not compile is refused at install.
 * Colouring stops after [budgetMillis] and leaves the rest plain, so a
 * grammar with a slow expression costs a little colour, never a frozen
 * preview.
 */
object PluginSyntax {

    /** What a span is, which the host maps onto its palette. */
    enum class Kind {
        COMMENT, STRING, NUMBER, KEYWORD, TYPE, FUNCTION, OPERATOR, TAG, ATTRIBUTE, HEADING, LINK, EMPHASIS, CONSTANT, PUNCTUATION;

        companion object {
            fun of(name: String?): Kind? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
        }
    }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    const val MAX_RULES = 200
    const val MAX_WORDS = 2000
    private const val MAX_PATTERN = 512
    private const val MAX_MARKER = 8
    private const val CHECK_EVERY = 256
    private const val MILLIS_PER_CHUNK = 50L
    private const val CHUNK = 64 * 1024
    private const val MAX_CACHED = 64

    class Rule(val pattern: Pattern, val kind: Kind)

    /** A compiled grammar. */
    class Grammar(
        val rules: List<Rule>,
        val keywords: Set<String>,
        val types: Set<String>,
        val constants: Set<String>,
        val lineComments: List<String>,
        val blockComments: List<Pair<String, String>>,
        val strings: List<String>,
        val escape: Char?,
        val numbers: Boolean,
        val calls: Boolean,
        val caseInsensitive: Boolean,
    )

    private val cache = ConcurrentHashMap<String, Grammar>()

    /** Why [grammar] would not compile, or null when it would. */
    fun problem(grammar: JsonObject): String? = try {
        compile(grammar)
        null
    } catch (bad: IllegalArgumentException) {
        bad.message ?: "bad grammar"
    }

    /** The compiled grammar of [ref], built once per plugin version. Null when it is broken. */
    fun grammar(ref: PluginSyntaxRef): Grammar? {
        cache[ref.key]?.let { return it }
        val json = ref.syntax.grammar as? JsonObject ?: return null
        val compiled = runCatching { compile(json) }.getOrNull() ?: return null
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[ref.key] = compiled
        return compiled
    }

    /**
     * Compiles [json]. Throws [IllegalArgumentException] naming what is wrong:
     * an unknown kind, a regex that does not compile, a list past its cap.
     */
    fun compile(json: JsonObject): Grammar {
        val insensitive = (json["caseInsensitive"] as? JsonPrimitive)?.booleanOrNull ?: false
        val rules = (json["rules"] as? JsonArray).orEmpty()
        require(rules.size <= MAX_RULES) { "a grammar has at most $MAX_RULES rules" }
        val compiled = rules.mapIndexed { i, element ->
            val rule = element as? JsonObject ?: throw IllegalArgumentException("rule ${i + 1} is not an object")
            val match = rule.string("match") ?: throw IllegalArgumentException("rule ${i + 1} has no match")
            require(match.isNotEmpty() && match.length <= MAX_PATTERN) { "rule ${i + 1}: a match is 1 to $MAX_PATTERN characters" }
            val kind = Kind.of(rule.string("kind")) ?: throw IllegalArgumentException("rule ${i + 1}: no kind '${rule.string("kind")}'")
            var flags = 0
            val written = rule.string("flags").orEmpty()
            if ('i' in written || insensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if ('m' in written) flags = flags or Pattern.MULTILINE
            if ('s' in written) flags = flags or Pattern.DOTALL
            val pattern = try {
                Pattern.compile(match, flags)
            } catch (bad: PatternSyntaxException) {
                throw IllegalArgumentException("rule ${i + 1}: ${bad.description}")
            }
            Rule(pattern, kind)
        }
        fun words(key: String): Set<String> {
            val list = (json[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            require(list.size <= MAX_WORDS) { "$key has at most $MAX_WORDS words" }
            return list.map { if (insensitive) it.lowercase() else it }.toHashSet()
        }
        fun markers(key: String): List<String> = when (val v = json[key]) {
            is JsonPrimitive -> listOfNotNull(v.contentOrNull)
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }.filter { it.isNotEmpty() }.onEach { require(it.length <= MAX_MARKER) { "$key markers are at most $MAX_MARKER characters" } }
        val blocks = when (val v = json["blockComment"]) {
            is JsonArray -> if (v.all { it is JsonPrimitive }) {
                listOfNotNull(pair(v))
            } else {
                v.mapNotNull { (it as? JsonArray)?.let(::pair) }
            }
            else -> emptyList()
        }
        return Grammar(
            rules = compiled,
            keywords = words("keywords"),
            types = words("types"),
            constants = words("constants"),
            lineComments = markers("lineComment"),
            blockComments = blocks,
            strings = markers("strings").sortedByDescending { it.length },
            escape = (json["escape"] as? JsonPrimitive)?.contentOrNull?.firstOrNull() ?: '\\',
            numbers = (json["numbers"] as? JsonPrimitive)?.booleanOrNull ?: true,
            calls = (json["calls"] as? JsonPrimitive)?.booleanOrNull ?: false,
            caseInsensitive = insensitive,
        )
    }

    private fun pair(a: JsonArray): Pair<String, String>? {
        val open = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return null
        val close = (a.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return null
        require(open.isNotEmpty() && close.isNotEmpty() && open.length <= MAX_MARKER && close.length <= MAX_MARKER) { "blockComment markers are 1 to $MAX_MARKER characters" }
        return open to close
    }

    /** The coloured spans of [text], in order, never overlapping. */
    fun tokens(text: String, grammar: Grammar, budgetMillis: Long = budgetFor(text.length)): List<Token> {
        val out = ArrayList<Token>()
        val n = text.length
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        // The rules read the text through a clock: one expression that
        // backtracks for ever is stopped inside its match, not after it.
        val timed = Timed(text, deadline)
        val matchers: List<Pair<Matcher, Kind>> = grammar.rules.map { it.pattern.matcher(timed) to it.kind }
        var i = 0
        var steps = 0
        while (i < n) {
            if (++steps % CHECK_EVERY == 0 && System.nanoTime() > deadline) break
            val ruled = try {
                matchers.firstNotNullOfOrNull { (m, kind) ->
                    m.region(i, n)
                    if (m.lookingAt() && m.end() > i) m.end() to kind else null
                }
            } catch (_: OutOfTime) {
                break
            }
            if (ruled != null) {
                out += Token(i, ruled.first, ruled.second)
                i = ruled.first
                continue
            }
            val block = grammar.blockComments.firstOrNull { text.startsWith(it.first, i) }
            if (block != null) {
                val close = text.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                out += Token(i, end, Kind.COMMENT)
                i = end
                continue
            }
            if (grammar.lineComments.any { text.startsWith(it, i) }) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                out += Token(i, end, Kind.COMMENT)
                i = end
                continue
            }
            val quote = grammar.strings.firstOrNull { text.startsWith(it, i) }
            if (quote != null) {
                val end = stringEnd(text, i, quote, grammar.escape)
                out += Token(i, end, Kind.STRING)
                i = end
                continue
            }
            val c = text[i]
            val prevWord = i > 0 && isWordChar(text[i - 1])
            if (grammar.numbers && c.isDigit() && !prevWord) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                out += Token(i, j, Kind.NUMBER)
                i = j
                continue
            }
            if (isWordStart(c) && !prevWord) {
                var j = i + 1
                while (j < n && isWordChar(text[j])) j++
                val word = text.substring(i, j).let { if (grammar.caseInsensitive) it.lowercase() else it }
                val kind = when {
                    word in grammar.keywords -> Kind.KEYWORD
                    word in grammar.types -> Kind.TYPE
                    word in grammar.constants -> Kind.CONSTANT
                    grammar.calls && j < n && text[j] == '(' -> Kind.FUNCTION
                    else -> null
                }
                if (kind != null) out += Token(i, j, kind)
                i = j
                continue
            }
            i++
        }
        return out
    }

    private class OutOfTime : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** [text] that throws [OutOfTime] once [deadline] has passed, checked every few thousand reads. */
    private class Timed(private val text: String, private val deadline: Long) : CharSequence {
        private var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if ((++reads and READ_CHECK_MASK) == 0 && System.nanoTime() > deadline) throw OutOfTime()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String = text
    }

    private const val READ_CHECK_MASK = 0xFFF

    /** [MILLIS_PER_CHUNK] for each 64 KB, at least once. */
    fun budgetFor(length: Int): Long = MILLIS_PER_CHUNK * ((length / CHUNK) + 1)

    private fun stringEnd(text: String, start: Int, quote: String, escape: Char?): Int {
        var j = start + quote.length
        val multiline = quote.length > 1
        while (j < text.length) {
            val c = text[j]
            if (escape != null && c == escape && quote.length == 1) {
                j += 2
                continue
            }
            if (text.startsWith(quote, j)) return j + quote.length
            if (c == '\n' && !multiline) return j
            j++
        }
        return text.length
    }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$' || c == '@'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
