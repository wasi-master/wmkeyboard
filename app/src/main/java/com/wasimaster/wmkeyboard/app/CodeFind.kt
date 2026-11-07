package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import java.util.regex.Pattern

/** How a find reads its query. */
@Immutable
internal data class CodeFindOptions(
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

/** More matches than this is not a search anyone reads; the count stops here. */
internal const val MAX_FIND_MATCHES = 10_000

/**
 * The compiled search, or null when [query] is empty or is not a valid pattern.
 *
 * `^` and `$` match at every line, and `.` stops at a line break, which is what a
 * search in a code file means. Case folding is Unicode's rather than the phone
 * locale's, so a Turkish phone still finds `I` with `i`. A whole word is bounded
 * by letters, digits and the underscore, the characters a Python name is made of.
 */
internal fun findPattern(query: String, options: CodeFindOptions): Pattern? {
    if (query.isEmpty()) return null
    var source = if (options.regex) query else Pattern.quote(query)
    if (options.wholeWord) source = "(?<![\\p{L}\\p{N}_])(?:$source)(?![\\p{L}\\p{N}_])"
    var flags = Pattern.MULTILINE
    if (!options.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    return try {
        Pattern.compile(source, flags)
    } catch (invalid: IllegalArgumentException) {
        null
    }
}

/** Every non-empty match of [query] in [text], in document order. Empty for a blank or malformed query. */
internal fun findMatches(text: String, query: String, options: CodeFindOptions): List<TextRange> {
    val pattern = findPattern(query, options) ?: return emptyList()
    val matcher = pattern.matcher(text)
    val found = ArrayList<TextRange>()
    while (found.size < MAX_FIND_MATCHES && matcher.find()) {
        if (matcher.end() > matcher.start()) found += TextRange(matcher.start(), matcher.end())
    }
    return found
}

/** Replaces the one [match] with [replacement]. The caret lands after the new text. */
internal fun replaceMatch(
    text: String,
    match: TextRange,
    replacement: String,
    query: String,
    options: CodeFindOptions,
): CodeTextEdit {
    val pattern = if (options.regex) findPattern(query, options) else null
    val with = expand(pattern, text, match, replacement)
    return CodeTextEdit(match, with, TextRange(match.min + with.length))
}

/**
 * Replaces every one of [matches] as a single edit, so Replace all is one step of
 * history. Each match is replaced from the original text, never from text a
 * previous replacement produced, so a replacement that contains the query cannot
 * match again. Null when there is nothing to replace.
 */
internal fun replaceAllMatches(
    text: String,
    matches: List<TextRange>,
    replacement: String,
    query: String,
    options: CodeFindOptions,
): CodeTextEdit? {
    if (matches.isEmpty()) return null
    val pattern = if (options.regex) findPattern(query, options) else null
    val changes = matches.map { it to expand(pattern, text, it, replacement) }
    val grown = changes.sumOf { (range, with) -> with.length - range.length }
    val caret = changes.maxOf { it.first.max } + grown
    return mergeEdits(text, changes, TextRange(caret))
}

/**
 * The text that replaces [match]. In pattern mode `$1` names a group, as Java's
 * own replace does; a replacement that names a group the pattern lacks, or ends
 * in a lone backslash, is used as typed rather than failing.
 */
private fun expand(pattern: Pattern?, text: String, match: TextRange, replacement: String): String {
    if (pattern == null) return replacement
    val matcher = pattern.matcher(text)
    if (!matcher.find(match.min) || matcher.start() != match.min || matcher.end() != match.max) return replacement
    return try {
        val buffer = StringBuffer()
        matcher.appendReplacement(buffer, replacement)
        buffer.substring(match.min)
    } catch (invalid: IllegalArgumentException) {
        replacement
    } catch (invalid: IndexOutOfBoundsException) {
        replacement
    }
}
