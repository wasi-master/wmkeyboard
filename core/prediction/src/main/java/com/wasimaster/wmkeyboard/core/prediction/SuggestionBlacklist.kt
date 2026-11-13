package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.regex.GuardedRegex
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Pattern entries on the suggestion blacklist (gap list L8).
 *
 * The blacklist is a set of strings. A plain entry is a word, stored
 * lowercased and matched exactly. An entry written between slashes, `/lo+l/`,
 * is a regular expression instead: stored as written (case matters to `\S`
 * and `\s`), matched case-insensitively against the *whole* candidate, so
 * `/lo+l/` hides "lol" and "loooool" but not "lolly". Patterns live in the
 * same sets as words, so the backup, the per-language lists and every reader
 * that only knows words keep working unchanged.
 *
 * A pattern runs behind the same guards as a pattern snippet
 * ([GuardedRegex]): no backreferences, no quantified group with a quantified
 * body, and a step budget on every run. The editor refuses one that trips any
 * of those, that runs away on a probe, or that would hide every word.
 */
object BlacklistPatterns {

    /** Longest pattern body accepted, in characters. */
    const val MAX_PATTERN_LENGTH = 200

    /**
     * Characters one run may read before it is judged runaway. A candidate is
     * one word, so an honest pattern reads a few hundred; this leaves room for
     * a pattern with several `.*` and still stops well inside a frame.
     */
    const val MAX_STEPS = 10_000

    /** Why a pattern entry cannot go on the list. */
    enum class Fault {
        EMPTY,
        TOO_LONG,
        SYNTAX,
        BACKREFERENCE,
        NESTED_QUANTIFIER,

        /** It ran past [MAX_STEPS] on one of the [RUNAWAY_PROBES]. */
        RUNAWAY,

        /** It matches every one of [EVERYDAY_WORDS], so it would empty the strip. */
        MATCHES_EVERYTHING,
    }

    /** A pattern the list will not hold. [description] is `java.util.regex`'s own text, untranslated. */
    data class Problem(val fault: Fault, val description: String? = null)

    /** True when [entry] is a pattern: at least one character between two slashes. */
    fun isPattern(entry: String): Boolean =
        entry.length > 2 && entry[0] == '/' && entry[entry.length - 1] == '/'

    /** The regular expression inside a pattern entry's slashes. */
    fun bodyOf(entry: String): String = entry.substring(1, entry.length - 1)

    /** [body] as a stored pattern entry. */
    fun entryOf(body: String): String = "/$body/"

    /**
     * [entry] the way the list stores it, or null when nothing is left: a
     * word trimmed and lowercased, a pattern trimmed and otherwise as written.
     */
    fun normalize(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        return if (isPattern(trimmed)) trimmed else trimmed.lowercase()
    }

    /**
     * Why [entry] cannot go on the list, or null when it can. A plain word
     * always can; a pattern has to pass every guard and both probes.
     */
    fun check(entry: String): Problem? {
        val trimmed = entry.trim()
        if (!isPattern(trimmed)) {
            return if (trimmed.isEmpty() || trimmed == "//") Problem(Fault.EMPTY) else null
        }
        val body = bodyOf(trimmed)
        GuardedRegex.check(body, MAX_PATTERN_LENGTH)?.let { error ->
            val fault = when (error.fault) {
                GuardedRegex.Fault.EMPTY -> Fault.EMPTY
                GuardedRegex.Fault.TOO_LONG -> Fault.TOO_LONG
                GuardedRegex.Fault.SYNTAX -> Fault.SYNTAX
                GuardedRegex.Fault.BACKREFERENCE -> Fault.BACKREFERENCE
                GuardedRegex.Fault.NESTED_QUANTIFIER -> Fault.NESTED_QUANTIFIER
            }
            return Problem(fault, error.description)
        }
        val pattern = GuardedRegex.compile(body)
        for (probe in RUNAWAY_PROBES) {
            if (GuardedRegex.matchesWithin(pattern, probe, MAX_STEPS) == null) return Problem(Fault.RUNAWAY)
        }
        if (EVERYDAY_WORDS.all { GuardedRegex.matchesWithin(pattern, it, MAX_STEPS) == true }) {
            return Problem(Fault.MATCHES_EVERYTHING)
        }
        return null
    }

    /**
     * The compiled form of [entry]'s pattern, or null when it is not one or
     * fails the guards. Compiled once per distinct pattern for the life of the
     * process: the list is pushed to the engine on every settings change and
     * language switch, and none of those should recompile anything.
     */
    fun compiled(entry: String): Pattern? {
        if (!isPattern(entry)) return null
        cache[entry]?.let { return it.pattern }
        val body = bodyOf(entry)
        val pattern = if (GuardedRegex.check(body, MAX_PATTERN_LENGTH) == null) GuardedRegex.compile(body) else null
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[entry] = Compiled(pattern)
        return pattern
    }

    /** One cache slot; a refused pattern is remembered as null so it is not screened twice. */
    private class Compiled(val pattern: Pattern?)

    private val cache = ConcurrentHashMap<String, Compiled>()

    /** Far above any real list's pattern count; only a bound against an unbounded stream. */
    private const val MAX_CACHED = 1_024

    /**
     * Inputs that make a backtracking pattern show itself: one letter many
     * times over, the same with a stray mark at the end, and two letters
     * alternating. A word-length run is all the engine ever hands in.
     */
    private val RUNAWAY_PROBES = listOf(
        "a".repeat(PROBE_LENGTH),
        "a".repeat(PROBE_LENGTH - 1) + "!",
        "ab".repeat(PROBE_LENGTH / 2),
    )

    private const val PROBE_LENGTH = 32

    /** Words no honest pattern hides all of; a pattern that does would leave the strip empty. */
    private val EVERYDAY_WORDS = listOf("a", "the", "and", "hello", "keyboard", "zebra", "x", "1")
}

/**
 * The blacklist one list (global, or global plus one language's) reads as:
 * the plain words in a hash set, the patterns compiled once.
 *
 * Built only when the list changes. [matches] is on the suggestion hot path,
 * so the common case, a list with no patterns at all, costs one set lookup.
 */
class BlacklistMatcher private constructor(
    private val words: Set<String>,
    private val patterns: Array<Pattern>,
) {
    /** Patterns that ran away on a real word; skipped from then on rather than retried per keystroke. */
    private val stopped = BooleanArray(patterns.size)

    /** True when this list holds no pattern, so a set lookup is the whole answer. */
    val hasPatterns: Boolean get() = patterns.isNotEmpty()

    /** True when [lower] (already lowercased) is on the list, exactly or by a pattern. */
    fun matches(lower: String): Boolean {
        if (lower in words) return true
        return patterns.isNotEmpty() && matchesPattern(lower)
    }

    /** True when one of the patterns, not the plain words, hides [lower]. */
    fun matchesPattern(lower: String): Boolean {
        for (i in patterns.indices) {
            if (stopped[i]) continue
            when (GuardedRegex.matchesWithin(patterns[i], lower, BlacklistPatterns.MAX_STEPS)) {
                true -> return true
                null -> stopped[i] = true
                false -> Unit
            }
        }
        return false
    }

    companion object {
        /** No words, no patterns. */
        val NONE = BlacklistMatcher(emptySet(), emptyArray())

        /**
         * The matcher for [entries]. With no pattern among them it holds
         * [entries] itself, so nothing is copied.
         */
        fun of(entries: Set<String>): BlacklistMatcher {
            if (entries.isEmpty()) return NONE
            if (entries.none(BlacklistPatterns::isPattern)) return BlacklistMatcher(entries, emptyArray())
            val patterns = entries.mapNotNull(BlacklistPatterns::compiled).toTypedArray()
            return BlacklistMatcher(entries, patterns)
        }
    }
}

/**
 * The blacklist as a plain-text file of its own, apart from the full backup.
 *
 * One entry per line. A line starting with `#` is a comment, a blank line is
 * nothing. An entry may end with a language tag, a space and `@` and the
 * language id (`whatever @en`), which puts it on that language's list instead
 * of the global one. A pattern is written between slashes as in the editor.
 *
 * ```
 * # WM Keyboard suggestion blacklist
 * idk
 * /lo+l/
 * whatever @en
 * ```
 */
object BlacklistFile {

    /** The type the system file picker is asked for and told the export is. */
    const val MIME_TYPE = "text/plain"

    /** The name an export is offered under. */
    const val FILE_NAME = "suggestion-blacklist.txt"

    /** One entry: a word or pattern, and the language it is blocked in or null for all. */
    data class Entry(val entry: String, val languageId: String?)

    /** A line [parse] left out, by its 1-based number, and why. */
    data class Rejected(val line: Int, val text: String, val problem: BlacklistPatterns.Problem)

    /**
     * What a file holds: [entries] in file order with repeats removed,
     * [duplicates] how many lines repeated an earlier one, and [rejected] the
     * patterns the guards refused.
     */
    data class Parsed(val entries: List<Entry>, val duplicates: Int, val rejected: List<Rejected>)

    fun parse(text: String): Parsed {
        val seen = LinkedHashSet<Entry>()
        var duplicates = 0
        val rejected = ArrayList<Rejected>()
        text.removePrefix(BOM).lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(COMMENT)) return@forEachIndexed
            val (body, language) = splitTag(line)
            val problem = BlacklistPatterns.check(body)
            if (problem != null) {
                rejected += Rejected(index + 1, line, problem)
                return@forEachIndexed
            }
            val entry = BlacklistPatterns.normalize(body) ?: return@forEachIndexed
            if (!seen.add(Entry(entry, language))) duplicates++
        }
        return Parsed(seen.toList(), duplicates, rejected)
    }

    /**
     * [global] and [byLanguage] as a file [parse] reads back: a short header,
     * the global entries, then each language's, every group sorted.
     */
    fun write(global: Collection<String>, byLanguage: Map<String, Collection<String>>): String = buildString {
        append(COMMENT).append(" WM Keyboard suggestion blacklist\n")
        append(COMMENT).append(" One word per line. /…/ is a pattern. \"word @en\" blocks it in one language only.\n")
        for (entry in global.sorted()) append(entry).append('\n')
        for (language in byLanguage.keys.sorted()) {
            for (entry in byLanguage.getValue(language).sorted()) {
                append(entry).append(' ').append(TAG).append(language).append('\n')
            }
        }
    }

    /** [line] split into its entry and its language tag, if it ends with one. */
    internal fun splitTag(line: String): Pair<String, String?> {
        val space = line.indexOfLast { it.isWhitespace() }
        if (space <= 0) return line to null
        val tag = line.substring(space + 1)
        if (tag.length < 2 || tag[0] != TAG || !tag.drop(1).all(::isTagChar)) return line to null
        return line.substring(0, space).trimEnd() to tag.substring(1)
    }

    private fun isTagChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private const val COMMENT = "#"
    private const val TAG = '@'
    private const val BOM = "﻿"
}
