package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyRegex.swift: a small backtracking engine behind the `re` namespace.
 *
 * java.util.regex has no budget and can't be stopped, so one catastrophic
 * pattern like `(a+)+b` would spin forever. This engine charges the
 * interpreter's step budget (and so its cancellation check) for every step.
 * A repeat whose body is one character, class or `.` runs as a loop; anything
 * else counts its depth and gives up past [PyRegex.MAX_DEPTH].
 *
 * Supported: literals, `.`, classes with ranges and negation, `\d \D \w \W \s
 * \S \b \B`, anchors, groups (capturing, `(?:…)`, `(?P<name>…)`), alternation,
 * and `* + ? {m,n}` greedy and lazy. Not supported: backreferences, lookaround,
 * possessive quantifiers. Subjects are matched as grapheme clusters, so
 * `m.start()` counts clusters, as PyMini's do.
 */

/** `re` module flags, with Python's values so `re.I | re.M` reads the same. */
internal object PyRegexFlags {
    const val IGNORE_CASE = 2
    const val MULTILINE = 8
    const val DOT_ALL = 16
}

private fun caseEq(a: String, b: String): Boolean = strEq(swiftLower(a), swiftLower(b))

/** One character-class item. */
internal sealed class RxClassItem {
    class Single(val c: String) : RxClassItem()
    class Range(val lo: String, val hi: String) : RxClassItem()
    class Digit(val want: Boolean) : RxClassItem()
    class Word(val want: Boolean) : RxClassItem()
    class Space(val want: Boolean) : RxClassItem()
}

internal class RxClass(var negated: Boolean = false, val items: MutableList<RxClassItem> = ArrayList()) {
    fun matches(c: String, ignoreCase: Boolean): Boolean {
        var hit = false
        for (item in items) {
            hit = when (item) {
                is RxClassItem.Single -> if (ignoreCase) caseEq(item.c, c) else strEq(item.c, c)
                is RxClassItem.Range -> if (ignoreCase) {
                    val lowered = swiftLower(c)
                    val uppered = swiftUpper(c)
                    (inRange(item, lowered)) || (inRange(item, uppered))
                } else {
                    inRange(item, c)
                }
                is RxClassItem.Digit -> charIsNumber(c) == item.want
                is RxClassItem.Word -> (charIsLetter(c) || charIsNumber(c) || c == "_") == item.want
                is RxClassItem.Space -> charIsWhitespace(c) == item.want
            }
            if (hit) break
        }
        return if (negated) !hit else hit
    }

    private fun inRange(r: RxClassItem.Range, c: String) = strCompare(r.lo, c) <= 0 && strCompare(c, r.hi) <= 0
}

/** A compiled pattern node. */
internal sealed class RxNode {
    object Empty : RxNode()
    class Char(val c: String) : RxNode()
    object AnyChar : RxNode()
    class CharClass(val cls: RxClass) : RxNode()
    class Seq(val items: List<RxNode>) : RxNode()
    class Alt(val branches: List<RxNode>) : RxNode()
    class Repeated(val node: RxNode, val min: Int, val max: Int?, val greedy: Boolean) : RxNode()
    /** [index] into the capture table, or null for `(?:…)`. */
    class Group(val index: Int?, val inner: RxNode) : RxNode()
    object AnchorStart : RxNode()
    object AnchorEnd : RxNode()
    /** `\b` (true) / `\B` (false). */
    class WordBoundary(val want: Boolean) : RxNode()
}

/** A half-open span of grapheme indices. */
internal data class Span(val start: Int, val end: Int)

/** One successful match: the whole pattern's span plus each group's (end exclusive). */
class PyMatch internal constructor(
    /** The subject the spans index into, as grapheme clusters. */
    internal val subject: List<String>,
    /** Index 0 is the whole match; 1… are capturing groups (null = didn't take part). */
    internal val spans: List<Span?>,
    internal val names: Map<String, Int>,
) {
    /** The whole matched text. */
    val value: String get() = text(0) ?: ""

    /** A group's text, or null when that group didn't take part in the match. */
    fun text(group: Int): String? {
        if (group < 0 || group >= spans.size) return null
        val r = spans[group] ?: return null
        return subject.subList(r.start, r.end).joinToString("")
    }

    internal fun index(name: String): Int? = names[name]
}

/** A compiled regular expression. */
internal class PyRegex(val root: RxNode, val groupCount: Int, val names: Map<String, Int>, val flags: Int) {
    val ignoreCase: Boolean get() = flags and PyRegexFlags.IGNORE_CASE != 0
    val multiline: Boolean get() = flags and PyRegexFlags.MULTILINE != 0
    val dotAll: Boolean get() = flags and PyRegexFlags.DOT_ALL != 0

    companion object {
        /** Longest subject this engine will scan. */
        const val MAX_SUBJECT = 40_000
        /** Deepest nested backtrack before giving up, which bounds the stack. */
        const val MAX_DEPTH = 2_000

        fun compile(pattern: String, flags: Int = 0): PyRegex {
            val parser = RxParser(graphemes(pattern))
            val root = parser.parseAlternation()
            if (!parser.atEnd) throw PyError("unbalanced parenthesis in pattern", "ValueError")
            return PyRegex(root, parser.groupCount, parser.names, flags)
        }

        /** `re.escape`: backslash every character that could be syntax. */
        fun escape(s: String): String {
            val out = StringBuilder()
            for (c in graphemes(s)) {
                if (charIsLetter(c) || charIsNumber(c) || c == "_") out.append(c) else out.append("\\").append(c)
            }
            return out.toString()
        }
    }

    /**
     * The first match at or after [start]. [anchored] pins it to [start]
     * (`re.match`); [whole] also requires it to reach the end (`re.fullmatch`).
     */
    fun firstMatch(subject: List<String>, start: Int, anchored: Boolean = false, whole: Boolean = false, tick: () -> Unit): PyMatch? {
        if (subject.size > MAX_SUBJECT) {
            throw PyError.abort("text too long for a regular expression (${subject.size} characters)")
        }
        var at = maxOf(0, start)
        do {
            val spans = RxMatcher(subject, this, tick).run(at, whole)
            if (spans != null) return PyMatch(subject, spans, names)
            if (anchored) return null
            at += 1
        } while (at <= subject.size)
        return null
    }

    /** Every non-overlapping match; a zero-width match advances one character. */
    fun allMatches(subject: List<String>, limit: Int = Int.MAX_VALUE, tick: () -> Unit): List<PyMatch> {
        val out = ArrayList<PyMatch>()
        var at = 0
        while (at <= subject.size && out.size < limit) {
            val m = firstMatch(subject, at, tick = tick) ?: break
            out.add(m)
            val span = m.spans[0] ?: Span(at, at)
            at = if (span.start == span.end) span.start + 1 else span.end
        }
        return out
    }
}

// MARK: - Pattern parsing

private class RxParser(val chars: List<String>) {
    var pos = 0
    var groupCount = 0
    val names = HashMap<String, Int>()

    val atEnd: Boolean get() = pos >= chars.size
    private fun peek(): String? = if (pos < chars.size) chars[pos] else null

    fun parseAlternation(): RxNode {
        val branches = arrayListOf(parseSequence())
        while (peek() == "|") {
            pos += 1
            branches.add(parseSequence())
        }
        return if (branches.size == 1) branches[0] else RxNode.Alt(branches)
    }

    fun parseSequence(): RxNode {
        val items = ArrayList<RxNode>()
        while (true) {
            val c = peek() ?: break
            if (c == "|" || c == ")") break
            items.add(parseQuantified())
        }
        if (items.isEmpty()) return RxNode.Empty
        return if (items.size == 1) items[0] else RxNode.Seq(items)
    }

    fun parseQuantified(): RxNode {
        val atom = parseAtom()
        val c = peek() ?: return atom
        var minimum = 0
        var maximum: Int? = null
        when (c) {
            "*" -> pos += 1
            "+" -> { pos += 1; minimum = 1 }
            "?" -> { pos += 1; maximum = 1 }
            "{" -> {
                // `{` is only a quantifier when it reads as one; otherwise it's a literal.
                val parsed = parseBraceQuantifier() ?: return atom
                minimum = parsed.first
                maximum = parsed.second
            }
            else -> return atom
        }
        var greedy = true
        if (peek() == "?") { greedy = false; pos += 1 }
        else if (peek() == "+") throw PyError("possessive quantifiers are not supported", "ValueError")
        return RxNode.Repeated(atom, minimum, maximum, greedy)
    }

    private fun parseBraceQuantifier(): Pair<Int, Int?>? {
        val save = pos
        pos += 1
        val lo = StringBuilder()
        while (peek()?.let { charIsNumber(it) } == true) { lo.append(peek()); pos += 1 }
        var hi: String? = null
        if (peek() == ",") {
            pos += 1
            val h = StringBuilder()
            while (peek()?.let { charIsNumber(it) } == true) { h.append(peek()); pos += 1 }
            hi = h.toString()
        }
        if (peek() != "}" || (lo.isEmpty() && hi == null)) { pos = save; return null }
        pos += 1
        val minimum = parseSwiftInt(lo.toString())?.toInt() ?: 0
        if (hi != null) return Pair(minimum, if (hi.isEmpty()) null else parseSwiftInt(hi)?.toInt())
        return Pair(minimum, minimum)
    }

    fun parseAtom(): RxNode {
        val c = peek() ?: return RxNode.Empty
        when (c) {
            "(" -> {
                pos += 1
                var index: Int? = null
                if (peek() == "?") {
                    pos += 1
                    if (peek() == ":") {
                        pos += 1
                    } else if (peek() == "P" || peek() == "<") {
                        if (peek() == "P") pos += 1
                        if (peek() != "<") throw PyError("lookaround is not supported by PyMini", "ValueError")
                        pos += 1
                        val name = StringBuilder()
                        while (true) {
                            val n = peek() ?: break
                            if (n == ">") break
                            name.append(n); pos += 1
                        }
                        if (peek() != ">") throw PyError("unterminated group name", "ValueError")
                        pos += 1
                        groupCount += 1
                        index = groupCount
                        names[name.toString()] = groupCount
                    } else {
                        throw PyError("this group syntax is not supported by PyMini", "ValueError")
                    }
                } else {
                    groupCount += 1
                    index = groupCount
                }
                val inner = parseAlternation()
                if (peek() != ")") throw PyError("missing ')' in pattern", "ValueError")
                pos += 1
                return RxNode.Group(index, inner)
            }
            "[" -> return RxNode.CharClass(parseClass())
            "." -> { pos += 1; return RxNode.AnyChar }
            "^" -> { pos += 1; return RxNode.AnchorStart }
            "$" -> { pos += 1; return RxNode.AnchorEnd }
            "\\" -> {
                pos += 1
                val e = peek() ?: throw PyError("pattern ends with a backslash", "ValueError")
                pos += 1
                return when (e) {
                    "d" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Digit(true))))
                    "D" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Digit(false))))
                    "w" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Word(true))))
                    "W" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Word(false))))
                    "s" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Space(true))))
                    "S" -> RxNode.CharClass(RxClass(false, arrayListOf(RxClassItem.Space(false))))
                    "b" -> RxNode.WordBoundary(true)
                    "B" -> RxNode.WordBoundary(false)
                    "A" -> RxNode.AnchorStart
                    "Z", "z" -> RxNode.AnchorEnd
                    "1", "2", "3", "4", "5", "6", "7", "8", "9" ->
                        throw PyError("backreferences are not supported by PyMini", "ValueError")
                    else -> RxNode.Char(escapeChar(e))
                }
            }
            else -> { pos += 1; return RxNode.Char(c) }
        }
    }

    fun escapeChar(c: String): String = when (c) {
        "n" -> "\n"
        "t" -> "\t"
        "r" -> "\r"
        "f" -> "\u000C"
        "v" -> "\u000B"
        "0" -> "\u0000"
        else -> c
    }

    fun parseClass(): RxClass {
        pos += 1
        val cls = RxClass()
        if (peek() == "^") { cls.negated = true; pos += 1 }
        if (peek() == "]") { cls.items.add(RxClassItem.Single("]")); pos += 1 }
        while (true) {
            val c = peek() ?: break
            if (c == "]") break
            pos += 1
            val lo: String
            if (c == "\\") {
                val e = peek() ?: throw PyError("pattern ends with a backslash", "ValueError")
                pos += 1
                when (e) {
                    "d" -> { cls.items.add(RxClassItem.Digit(true)); continue }
                    "D" -> { cls.items.add(RxClassItem.Digit(false)); continue }
                    "w" -> { cls.items.add(RxClassItem.Word(true)); continue }
                    "W" -> { cls.items.add(RxClassItem.Word(false)); continue }
                    "s" -> { cls.items.add(RxClassItem.Space(true)); continue }
                    "S" -> { cls.items.add(RxClassItem.Space(false)); continue }
                    else -> lo = escapeChar(e)
                }
            } else {
                lo = c
            }
            // A `-` that isn't last opens a range.
            if (peek() == "-" && pos + 1 < chars.size && chars[pos + 1] != "]") {
                pos += 1
                var hi = chars[pos]
                pos += 1
                if (hi == "\\") {
                    val e = peek()
                    if (e != null) { hi = escapeChar(e); pos += 1 }
                }
                if (strCompare(lo, hi) > 0) throw PyError("bad character range in pattern", "ValueError")
                cls.items.add(RxClassItem.Range(lo, hi))
            } else {
                cls.items.add(RxClassItem.Single(lo))
            }
        }
        if (peek() != "]") throw PyError("unterminated character class", "ValueError")
        pos += 1
        return cls
    }
}

// MARK: - Matching

private class RxMatcher(val subject: List<String>, val regex: PyRegex, val tick: () -> Unit) {
    val captures: Array<Span?> = arrayOfNulls(regex.groupCount + 1)
    var depth = 0

    fun run(start: Int, whole: Boolean): List<Span?>? {
        var result: List<Span?>? = null
        val matched = match(regex.root, start) { end ->
            if (whole && end != subject.size) {
                false
            } else {
                captures[0] = Span(start, end)
                result = captures.toList()
                true
            }
        }
        return if (matched) result else null
    }

    /**
     * Whether [node] matches at [pos], handing each candidate end to [cont].
     * Returning false from [cont] asks for the next alternative: that's the backtracking.
     */
    private fun match(node: RxNode, pos: Int, cont: (Int) -> Boolean): Boolean {
        tick()
        depth += 1
        try {
            if (depth >= PyRegex.MAX_DEPTH) throw PyError.abort("regular expression nested too deeply to finish")
            return when (node) {
                RxNode.Empty -> cont(pos)
                is RxNode.Char -> pos < subject.size && equal(subject[pos], node.c) && cont(pos + 1)
                RxNode.AnyChar -> {
                    if (pos >= subject.size) false
                    else if (!regex.dotAll && subject[pos] == "\n") false
                    else cont(pos + 1)
                }
                is RxNode.CharClass ->
                    pos < subject.size && node.cls.matches(subject[pos], regex.ignoreCase) && cont(pos + 1)
                RxNode.AnchorStart -> {
                    if (pos == 0) cont(pos)
                    else if (regex.multiline && pos > 0 && subject[pos - 1] == "\n") cont(pos)
                    else false
                }
                RxNode.AnchorEnd -> {
                    if (pos == subject.size) cont(pos)
                    else if (regex.multiline && subject[pos] == "\n") cont(pos)
                    else false
                }
                is RxNode.WordBoundary -> {
                    val before = pos > 0 && isWord(subject[pos - 1])
                    val after = pos < subject.size && isWord(subject[pos])
                    if ((before != after) == node.want) cont(pos) else false
                }
                is RxNode.Seq -> matchSeq(node.items, 0, pos, cont)
                is RxNode.Alt -> node.branches.any { match(it, pos, cont) }
                is RxNode.Group -> {
                    val index = node.index ?: return match(node.inner, pos, cont)
                    val saved = captures[index]
                    var restored = false
                    val ok = match(node.inner, pos) { end ->
                        captures[index] = Span(pos, end)
                        if (cont(end)) {
                            true
                        } else {
                            captures[index] = saved
                            restored = true
                            false
                        }
                    }
                    if (!ok && !restored) captures[index] = saved
                    ok
                }
                is RxNode.Repeated -> {
                    if (isSimple(node.node)) matchSimpleRepeat(node.node, node.min, node.max, node.greedy, pos, cont)
                    else matchRepeat(node.node, node.min, node.max, node.greedy, 0, pos, cont)
                }
            }
        } finally {
            depth -= 1
        }
    }

    private fun matchSeq(items: List<RxNode>, i: Int, pos: Int, cont: (Int) -> Boolean): Boolean {
        if (i == items.size) return cont(pos)
        return match(items[i], pos) { next -> matchSeq(items, i + 1, next, cont) }
    }

    /** Nodes that consume exactly one character repeat without recursion. */
    private fun isSimple(node: RxNode): Boolean = node is RxNode.Char || node === RxNode.AnyChar || node is RxNode.CharClass

    private fun consumesOne(node: RxNode, pos: Int): Boolean {
        if (pos >= subject.size) return false
        return when (node) {
            is RxNode.Char -> equal(subject[pos], node.c)
            RxNode.AnyChar -> regex.dotAll || subject[pos] != "\n"
            is RxNode.CharClass -> node.cls.matches(subject[pos], regex.ignoreCase)
            else -> false
        }
    }

    private fun matchSimpleRepeat(node: RxNode, minimum: Int, maximum: Int?, greedy: Boolean, pos: Int, cont: (Int) -> Boolean): Boolean {
        val ceiling = maximum ?: Int.MAX_VALUE
        if (greedy) {
            var count = 0
            var at = pos
            while (count < ceiling && consumesOne(node, at)) {
                tick()
                at += 1
                count += 1
            }
            while (count >= minimum) {
                if (cont(at)) return true
                if (count == 0) break
                at -= 1
                count -= 1
                tick()
            }
            return false
        }
        var count = 0
        var at = pos
        while (true) {
            tick()
            if (count >= minimum && cont(at)) return true
            if (count >= ceiling || !consumesOne(node, at)) return false
            at += 1
            count += 1
        }
    }

    private fun matchRepeat(node: RxNode, minimum: Int, maximum: Int?, greedy: Boolean, count: Int, pos: Int, cont: (Int) -> Boolean): Boolean {
        tick()
        val ceiling = maximum ?: Int.MAX_VALUE
        if (greedy) {
            if (count < ceiling) {
                val more = match(node, pos) { next ->
                    // A body that matched nothing would repeat for ever.
                    if (next == pos) false else matchRepeat(node, minimum, maximum, greedy, count + 1, next, cont)
                }
                if (more) return true
            }
            return if (count >= minimum) cont(pos) else false
        }
        if (count >= minimum && cont(pos)) return true
        if (count >= ceiling) return false
        return match(node, pos) { next ->
            if (next == pos) false else matchRepeat(node, minimum, maximum, greedy, count + 1, next, cont)
        }
    }

    private fun equal(a: String, b: String): Boolean = if (regex.ignoreCase) caseEq(a, b) else strEq(a, b)

    private fun isWord(c: String): Boolean = charIsLetter(c) || charIsNumber(c) || c == "_"
}
