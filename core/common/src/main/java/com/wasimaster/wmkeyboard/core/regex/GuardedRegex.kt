package com.wasimaster.wmkeyboard.core.regex

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The guards every user-written regular expression in the app runs behind:
 * pattern snippets ([com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher])
 * and the suggestion blacklist's pattern entries.
 *
 * `java.util.regex` has no timeout, and these patterns run on the input
 * thread, so two defences stack. [check] refuses the shapes no honest pattern
 * needs (backreferences, a quantified group whose body is quantified), and
 * [matchesWithin] counts every character the matcher reads and gives up past a
 * budget, which is what really bounds the work.
 */
object GuardedRegex {

    /** Why a pattern cannot be used. */
    enum class Fault {
        /** There is no pattern to compile. */
        EMPTY,

        /** The source is longer than the caller's limit. */
        TOO_LONG,

        /** `java.util.regex` refused it. */
        SYNTAX,

        /** It uses `\1`-style backreferences. */
        BACKREFERENCE,

        /** It quantifies a group that is itself quantified, e.g. `(a+)+`. */
        NESTED_QUANTIFIER,
    }

    /**
     * A pattern the app will not run. [description] is the raw text
     * `java.util.regex` produced for a [Fault.SYNTAX], such as "Unclosed group
     * near index 6"; diagnostic data, deliberately not translated.
     */
    data class Error(val fault: Fault, val description: String? = null)

    /** Characters that quantify whatever came before them. */
    private const val QUANTIFIERS = "?*+{"

    /** Checks [pattern] (trimmed) against every guard, compiling it to be sure. */
    fun check(pattern: String, maxLength: Int): Error? {
        val source = pattern.trim()
        if (source.isEmpty()) return Error(Fault.EMPTY)
        if (source.length > maxLength) return Error(Fault.TOO_LONG)
        if (hasBackreference(source)) return Error(Fault.BACKREFERENCE)
        if (hasNestedQuantifier(source)) return Error(Fault.NESTED_QUANTIFIER)
        return try {
            compile(source)
            null
        } catch (e: PatternSyntaxException) {
            Error(Fault.SYNTAX, e.description)
        }
    }

    /** Compiles [source] the way every guarded pattern is: case-insensitive, Unicode case. */
    fun compile(source: String): Pattern =
        Pattern.compile(source, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

    /**
     * [input] wrapped so a matcher reading it throws [Runaway] after [budget]
     * characters. Hand it to [Pattern.matcher] and catch [Runaway].
     */
    fun budgeted(input: CharSequence, budget: Int): CharSequence = StepBudget(input, budget)

    /**
     * Whether [pattern] matches the whole of [input], or null when it read more
     * than [budget] characters getting there and was stopped.
     */
    fun matchesWithin(pattern: Pattern, input: CharSequence, budget: Int): Boolean? =
        try {
            pattern.matcher(StepBudget(input, budget)).matches()
        } catch (_: Runaway) {
            null
        }

    /** True when [source] refers back to an earlier group. */
    fun hasBackreference(source: String): Boolean {
        var i = 0
        while (i < source.length - 1) {
            if (source[i] == '\\') {
                val next = source[i + 1]
                if (next in '1'..'9' || next == 'k') return true
                i += 2
                continue
            }
            i++
        }
        return false
    }

    /**
     * True when [source] quantifies a group whose own body is quantified, the
     * `(a+)+` shape that turns a hundred characters into an unbounded search.
     *
     * This is a screen, not a proof: it looks only at the group that a
     * quantifier closes over. The step budget is what actually protects the
     * keyboard.
     */
    fun hasNestedQuantifier(source: String): Boolean {
        for (close in source.indices) {
            if (source[close] != ')' || isEscaped(source, close)) continue
            val quantifier = source.getOrNull(close + 1) ?: continue
            if (quantifier !in QUANTIFIERS) continue
            val open = matchingOpen(source, close) ?: continue
            if (bodyIsQuantified(source, open + 1, close)) return true
        }
        return false
    }

    /** True when the character at [index] is spelled out by a backslash. */
    private fun isEscaped(source: String, index: Int): Boolean {
        var slashes = 0
        var i = index - 1
        while (i >= 0 && source[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 1
    }

    /** The `(` that [close] closes, or null when the pattern is unbalanced. */
    private fun matchingOpen(source: String, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            if (isEscaped(source, i)) continue
            when (source[i]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth == 0) return i
                }
                else -> Unit
            }
        }
        return null
    }

    /** True when the group body in `[start, end)` quantifies anything itself. */
    private fun bodyIsQuantified(source: String, start: Int, end: Int): Boolean {
        var inClass = false
        var i = start
        while (i < end) {
            val c = source[i]
            when {
                c == '\\' -> i++
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                !inClass && c in QUANTIFIERS -> return true
            }
            i++
        }
        return false
    }

    /** Thrown out of a runaway matcher. Carries no stack: this is the typing path. */
    class Runaway : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Stops a matcher that will not stop itself.
     *
     * `java.util.regex` offers no timeout, but it reads the input one
     * character at a time through [get], including on every backtracking step.
     * Counting those reads bounds the work a pattern can do without changing
     * what it matches.
     */
    private class StepBudget(private val source: CharSequence, private val budget: Int) : CharSequence {

        private var steps = 0

        override val length: Int get() = source.length

        override fun get(index: Int): Char {
            if (++steps > budget) throw Runaway()
            return source[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            source.subSequence(startIndex, endIndex)

        override fun toString(): String = source.toString()
    }
}
