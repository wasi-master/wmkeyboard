package com.wasimaster.wmkeyboard.core.input.imported

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A jquery.ime input method: Wikimedia's transliteration engine, whose rule
 * files cover several hundred methods (Nepali, Sanskrit and Hindi
 * transliteration, Malayalam's Mozhi and Swanalekha, Odia's Lekhani, Telugu's
 * KaChaTaThaPa), and the format Indic Keyboard ships them in.
 *
 * A method is an ordered list of rules, each a regular expression over the
 * text just before the cursor with the key appended, an optional one over the
 * keys typed just before, and a replacement. On every key the first rule whose
 * expressions both match at the end rewrites that tail; the rest of the field
 * is never touched. That is all the engine does, and doing exactly that, rule
 * order included, is what makes a method type here the way it types on
 * Wikipedia: `k` writes क्, `a` after it turns क् into क, and `h` after क्
 * turns it into ख्.
 *
 * The rule files are data, read by this code; no script runs. A rule whose
 * expression this platform's regex engine cannot compile is left out
 * ([skipped]), since a method with one rule fewer still types.
 *
 * @param maxKeyLength how many characters before the cursor a rule may see
 * @param contextLength how many earlier keys a rule's context may see
 */
class RegexRules(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val license: String,
    val maxKeyLength: Int,
    val contextLength: Int,
    val rules: List<Rule>,
    /** Rules in the file this platform could not compile. */
    val skipped: Int = 0,
) {
    class Rule(val input: Pattern, val context: Pattern?, val replacement: String)

    /**
     * What one key does: [input] is the field's last [maxKeyLength]
     * characters with the key after them, [context] the keys typed before it.
     * Null when no rule fires and the key types itself.
     */
    fun transliterate(input: String, context: String): String? {
        for (rule in rules) {
            val m = rule.input.matcher(input)
            if (!m.find()) continue
            if (rule.context != null && !rule.context.matcher(context).find()) continue
            return replaceFirst(m, input, rule.replacement)
        }
        return null
    }

    /** [keys] typed one after another into an empty field: what the field then holds. */
    fun run(keys: String): String {
        var out = ""
        var context = ""
        var i = 0
        while (i < keys.length) {
            val cp = keys.codePointAt(i)
            val key = String(Character.toChars(cp))
            i += key.length
            val start = (out.length - maxKeyLength).coerceAtLeast(0)
            val input = out.substring(start) + key
            out = out.substring(0, start) + (transliterate(input, context) ?: input)
            context = (context + key).takeLast(contextLength)
        }
        return out
    }

    /**
     * The characters besides letters and digits that the rules read as keys:
     * `\` to escape, `` ` `` for a virama, `.` for a danda, `~` and `^` for
     * the rarer signs. A composer buffers these so they reach the rules
     * instead of being typed as punctuation. Digits stay digits, as on every
     * other layout here; the numeral setting decides how they are written.
     */
    val keys: Set<Char> by lazy {
        val candidates = HashSet<Char>()
        for (rule in rules) {
            for (c in rule.input.pattern()) if (c in '!'..'~' && !c.isLetterOrDigit()) candidates += c
        }
        val found = candidates.filterTo(HashSet()) { c -> PROBES.any { prefix -> reads(prefix, c) } }
        if (typesScript) for (rule in rules) signsBetweenLetters(rule.input.pattern(), candidates, found)
        found
    }

    /**
     * The characters past ASCII the rules' patterns name. On a method typed
     * in the script itself (KaChaTaThaPa's keys are క, ్ and అ) these are
     * keys too, and the ones that are not letters, the virama first, have to
     * be buffered for the rules to see them.
     */
    val scriptChars: Set<Char> by lazy {
        val out = HashSet<Char>()
        for (rule in rules) for (c in rule.input.pattern()) if (c >= '\u0080') out += c
        out
    }

    /**
     * Whether the keys are the script's own letters rather than roman ones:
     * most rules end on a character past ASCII, where a roman method's end on
     * the roman letter just typed.
     */
    val typesScript: Boolean by lazy {
        var script = 0
        var roman = 0
        for (rule in rules) {
            val p = rule.input.pattern()
            val last = p.lastOrNull { it !in REGEX_TAIL } ?: continue
            if (last >= '\u0080') script++ else if (last.isLetter()) roman++
        }
        script > roman
    }

    /**
     * A sign a script-keyed method reads between its letters: the rule's own
     * letters on either side of [candidates]' sign, typed with the sign
     * between them, lose the sign. KaChaTaThaPa's `అ'` is ఆ, `్^` holds a
     * ZWJ, and `మ్&బ్` keeps the మ్ an anusvara would otherwise replace; a
     * full stop after మ్ turns it into ం but stays a full stop, so it ends
     * the word like any other.
     */
    private fun signsBetweenLetters(pattern: String, candidates: Set<Char>, found: MutableSet<Char>) {
        for (i in pattern.indices) {
            val c = pattern[i]
            if (c !in candidates || c in found) continue
            val before = scriptRun(pattern, i, -1)
            if (before.isEmpty()) continue
            val after = scriptRun(pattern, i, 1)
            if (c !in run(before + c + after)) found += c
        }
    }

    private fun reads(prefix: String, key: Char): Boolean {
        val out = run(prefix)
        val start = (out.length - maxKeyLength).coerceAtLeast(0)
        val input = out.substring(start) + key
        return transliterate(input, prefix.takeLast(contextLength)) != null
    }

    private companion object {
        /** What a sign key is tried after: nothing, a consonant, a vowel. */
        val PROBES = listOf("", "k", "a", "ka", "n")

        /** Regex syntax a pattern can end on after its last literal. */
        const val REGEX_TAIL = ")]?*+}|\$"

        /**
         * The nearest run of characters past ASCII before ([step] -1) or
         * after (+1) [from] in [pattern], whatever syntax lies between.
         */
        fun scriptRun(pattern: String, from: Int, step: Int): String {
            var i = from + step
            while (i in pattern.indices && pattern[i] < '\u0080') i += step
            val run = StringBuilder()
            while (i in pattern.indices && pattern[i] >= '\u0080') {
                run.append(pattern[i])
                i += step
            }
            return if (step < 0) run.reverse().toString() else run.toString()
        }

        /**
         * JavaScript's `String.replace` with a string replacement, first match
         * only: `$1`…`$99`, `$&`, `` $` ``, `$'` and `$$`. An unmatched group
         * writes nothing, as in JavaScript (Android's own `appendReplacement`
         * would write "null").
         */
        fun replaceFirst(m: Matcher, input: String, replacement: String): String {
            val out = StringBuilder(input.length + replacement.length)
            out.append(input, 0, m.start())
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                if (c != '$' || i + 1 >= replacement.length) {
                    out.append(c)
                    i++
                    continue
                }
                val next = replacement[i + 1]
                when {
                    next == '$' -> { out.append('$'); i += 2 }
                    next == '&' -> { out.append(m.group()); i += 2 }
                    next == '`' -> { out.append(input, 0, m.start()); i += 2 }
                    next == '\'' -> { out.append(input, m.end(), input.length); i += 2 }
                    next.isDigit() -> {
                        // Two digits when that names a group, else one.
                        val two = if (i + 2 < replacement.length && replacement[i + 2].isDigit()) {
                            (next - '0') * 10 + (replacement[i + 2] - '0')
                        } else {
                            -1
                        }
                        val group = if (two in 1..m.groupCount()) two else next - '0'
                        if (group in 1..m.groupCount()) {
                            m.group(group)?.let(out::append)
                            i += if (group == two) 3 else 2
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            out.append(input, m.end(), input.length)
            return out.toString()
        }
    }
}

/**
 * Reads a jquery.ime method out of the files it comes in: Wikimedia's own
 * rule files (`rules/<lang>/<id>.js`), and the XML Indic Keyboard converted
 * them to (`res/raw/<id>.xml`).
 *
 * A `.js` rule file is a script, but a fixed-shape one: an object literal of
 * strings and numbers handed to `$.ime.register`. This reads that literal and
 * nothing else; a file that builds its rules in code, which a handful do, is
 * refused rather than run.
 */
object JqueryImeFiles {

    /** Whether [text] looks like one of the two, so an import can tell it from the other formats. */
    fun looks(text: String): Boolean =
        ("\$.ime.register" in text && "patterns" in text) || (text.contains("<inputmethod") && text.contains("<pattern"))

    fun parse(text: String): RegexRules? = if (text.contains("<inputmethod")) parseXml(text) else parseJs(text)

    // ---- .js ----

    fun parseJs(text: String): RegexRules? {
        val source = JsReader(text)
        val start = source.findKey("patterns") ?: return null
        val array = source.readValue(start) as? List<*> ?: return null
        val raw = array.mapNotNull { rule ->
            val parts = (rule as? List<*>)?.map { it as? String ?: return@mapNotNull null } ?: return@mapNotNull null
            when (parts.size) {
                2 -> Triple(parts[0], null, parts[1])
                3 -> Triple(parts[0], parts[1], parts[2])
                else -> null
            }
        }
        fun str(key: String): String = source.findKey(key)?.let { source.readValue(it) as? String }.orEmpty()
        fun num(key: String, default: Int): Int =
            source.findKey(key)?.let { (source.readValue(it) as? Double)?.toInt() } ?: default
        return build(
            id = str("id"),
            name = str("name"),
            description = str("description"),
            author = str("author"),
            license = str("license"),
            maxKeyLength = num("maxKeyLength", 1),
            contextLength = num("contextLength", 0),
            raw = raw,
        )
    }

    // ---- .xml ----

    fun parseXml(text: String): RegexRules? {
        val head = Regex("<inputmethod\\b([^>]*)>").find(text) ?: return null
        val attrs = attributes(head.groupValues[1])
        val raw = Regex("<pattern\\b([^>]*?)/?>").findAll(text).mapNotNull { m ->
            val a = attributes(m.groupValues[1])
            // AltGr rules need a key this keyboard has no way to hold.
            if (a["altGr"] == "true") return@mapNotNull null
            val input = a["input"] ?: return@mapNotNull null
            val replacement = a["replacement"] ?: return@mapNotNull null
            Triple(input, a["context"], replacement)
        }.toList()
        return build(
            id = attrs["id"].orEmpty(),
            name = attrs["name"].orEmpty(),
            description = attrs["description"].orEmpty(),
            author = attrs["author"].orEmpty(),
            license = attrs["license"].orEmpty(),
            maxKeyLength = attrs["maxKeyLength"]?.toIntOrNull() ?: 1,
            contextLength = attrs["contextLength"]?.toIntOrNull() ?: 0,
            raw = raw,
        )
    }

    private fun attributes(text: String): Map<String, String> =
        Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").findAll(text).associate { m ->
            m.groupValues[1] to unescapeXml(m.groupValues[3].ifEmpty { m.groupValues[4] })
        }

    private fun unescapeXml(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|lt|gt|amp|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val cp = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(cp))
                }
            }
        }
    }

    private fun build(
        id: String,
        name: String,
        description: String,
        author: String,
        license: String,
        maxKeyLength: Int,
        contextLength: Int,
        raw: List<Triple<String, String?, String>>,
    ): RegexRules? {
        var skipped = 0
        val rules = raw.mapNotNull { (input, context, replacement) ->
            try {
                RegexRules.Rule(
                    Pattern.compile("$input$"),
                    context?.let { Pattern.compile("$it$") },
                    replacement,
                )
            } catch (_: PatternSyntaxException) {
                skipped++
                null
            }
        }
        if (rules.isEmpty()) return null
        // A file can declare less history than its own rules read: Nepali's
        // says 1 while ([क-ह]़?)्a needs three characters before the key, so
        // on jquery.ime itself "na" types न्अ. The rules are the better
        // witness, so the longest match any of them can make decides.
        val needed = raw.maxOf { (input, _, _) -> longestMatch(input) } - 1
        return RegexRules(
            id = id,
            name = name.ifBlank { id },
            description = description,
            author = author,
            license = license,
            maxKeyLength = maxOf(maxKeyLength, needed).coerceIn(1, MAX_KEY_LENGTH),
            contextLength = contextLength.coerceIn(0, MAX_KEY_LENGTH),
            rules = rules,
            skipped = skipped,
        )
    }

    /** No real method looks further back; a file asking for more is wrong, not ambitious. */
    private const val MAX_KEY_LENGTH = 64

    /**
     * The most UTF-16 units [regex] can match, for the subset rule files use:
     * literals and escapes, classes, groups with alternatives, and the
     * quantifiers. A repeated atom (`+`, `*`) counts once, since a key's rule
     * is written for the text one key produces; a lookaround counts nothing.
     */
    internal fun longestMatch(regex: String): Int = Longest(regex).alternatives()

    private class Longest(private val s: String) {
        private var i = 0

        fun alternatives(): Int {
            var best = 0
            var current = 0
            while (i < s.length) {
                when (s[i]) {
                    ')' -> break
                    '|' -> {
                        best = maxOf(best, current)
                        current = 0
                        i++
                    }
                    else -> current += quantified(atom())
                }
            }
            return maxOf(best, current)
        }

        private fun atom(): Int {
            val c = s[i]
            return when (c) {
                '\\' -> {
                    val e = s.getOrNull(i + 1)
                    i += when (e) {
                        'u' -> 6
                        'x' -> 4
                        else -> 2
                    }
                    if (e == 'b' || e == 'B') 0 else 1
                }
                '[' -> {
                    i++
                    if (i < s.length && s[i] == '^') i++
                    if (i < s.length && s[i] == ']') i++
                    while (i < s.length && s[i] != ']') i += if (s[i] == '\\') 2 else 1
                    i++
                    1
                }
                '(' -> {
                    i++
                    var look = false
                    if (s.startsWith("?:", i)) {
                        i += 2
                    } else if (s.startsWith("?=", i) || s.startsWith("?!", i)) {
                        i += 2
                        look = true
                    } else if (s.startsWith("?<=", i) || s.startsWith("?<!", i)) {
                        i += 3
                        look = true
                    }
                    val inner = alternatives()
                    i++
                    if (look) 0 else inner
                }
                '^', '$' -> {
                    i++
                    0
                }
                else -> {
                    i++
                    1
                }
            }
        }

        private fun quantified(atom: Int): Int {
            if (i >= s.length) return atom
            return when (s[i]) {
                '?', '*', '+' -> {
                    i++
                    if (i < s.length && s[i] == '?') i++
                    atom
                }
                '{' -> {
                    val end = s.indexOf('}', i)
                    if (end < 0) return atom
                    val body = s.substring(i + 1, end)
                    i = end + 1
                    val most = body.substringAfter(',', body).trim().toIntOrNull() ?: body.substringBefore(',').trim().toIntOrNull() ?: 1
                    atom * most.coerceIn(1, MAX_KEY_LENGTH)
                }
                else -> atom
            }
        }
    }

    /**
     * Just enough of JavaScript's literal syntax for a rule file's object:
     * strings (with every escape), numbers, arrays, comments, and keys found
     * by name. Anything else is a value this reader does not take.
     */
    private class JsReader(private val s: String) {

        /** Where the value of the first `key:` outside a string or comment starts. */
        fun findKey(key: String): Int? {
            var i = 0
            while (i < s.length) {
                when {
                    s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    s[i] == '\'' || s[i] == '"' -> i = skipString(i)
                    s.startsWith(key, i) && (i == 0 || !isIdent(s[i - 1])) -> {
                        var j = i + key.length
                        if (j < s.length && isIdent(s[j])) {
                            i = j
                            continue
                        }
                        j = skipBlank(j)
                        if (j < s.length && s[j] == ':') return skipBlank(j + 1)
                        i = j
                    }
                    else -> i++
                }
            }
            return null
        }

        /** The literal at [at]: a String, a Double, or a List of them. Null for anything else. */
        fun readValue(at: Int): Any? = runCatching { Value(at).also { it.read() }.result }.getOrNull()

        private inner class Value(var i: Int) {
            var result: Any? = null

            fun read() {
                result = value()
            }

            private fun value(): Any? {
                i = skipBlank(i)
                if (i >= s.length) return null
                return when (s[i]) {
                    '\'', '"' -> string()
                    '[' -> array()
                    else -> number()
                }
            }

            private fun array(): List<Any?> {
                i++
                val out = ArrayList<Any?>()
                while (true) {
                    i = skipBlank(i)
                    require(i < s.length)
                    if (s[i] == ']') {
                        i++
                        return out
                    }
                    if (s[i] == ',') {
                        i++
                        continue
                    }
                    val v = value()
                    requireNotNull(v)
                    out += v
                }
            }

            private fun number(): Double? {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '-')) i++
                return s.substring(start, i).toDoubleOrNull()
            }

            private fun string(): String {
                val quote = s[i++]
                val out = StringBuilder()
                while (true) {
                    require(i < s.length)
                    val c = s[i++]
                    when (c) {
                        quote -> return out.toString()
                        '\\' -> {
                            require(i < s.length)
                            when (val e = s[i++]) {
                                'n' -> out.append('\n')
                                't' -> out.append('\t')
                                'r' -> out.append('\r')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'v' -> out.append('\u000B')
                                '0' -> out.append('\u0000')
                                'x' -> {
                                    out.append(s.substring(i, i + 2).toInt(16).toChar())
                                    i += 2
                                }
                                'u' -> if (s[i] == '{') {
                                    val end = s.indexOf('}', i)
                                    out.appendCodePoint(s.substring(i + 1, end).toInt(16))
                                    i = end + 1
                                } else {
                                    out.append(s.substring(i, i + 4).toInt(16).toChar())
                                    i += 4
                                }
                                // A line continuation writes nothing.
                                '\n' -> Unit
                                '\r' -> if (i < s.length && s[i] == '\n') i++
                                // Any other escaped character is itself, as in JavaScript.
                                else -> out.append(e)
                            }
                        }
                        else -> out.append(c)
                    }
                }
            }
        }

        private fun skipString(at: Int): Int {
            val quote = s[at]
            var i = at + 1
            while (i < s.length && s[i] != quote) {
                if (s[i] == '\\') i++
                i++
            }
            return i + 1
        }

        private fun skipBlank(at: Int): Int {
            var i = at
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    else -> return i
                }
            }
            return i
        }

        private fun isIdent(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
    }
}
