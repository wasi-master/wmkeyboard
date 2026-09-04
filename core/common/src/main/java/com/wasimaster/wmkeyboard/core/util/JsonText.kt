package com.wasimaster.wmkeyboard.core.util

/**
 * The first complete JSON object or array in this string, with whatever
 * follows it dropped.
 *
 * Exports written before the truncating [requireOutputStream] existed could
 * land on top of a longer file and keep its tail: a complete document with a
 * few hundred kilobytes of stale text after the closing brace. Every JSON
 * parser rejects that as a whole, which threw away backups and layouts whose
 * first part was perfectly good. This cuts the document at the brace that
 * closes it, so those files read again.
 *
 * Returns the receiver unchanged when it holds a single value already, when it
 * has no object or array in it, or when the value never closes (a truncated
 * file, which a parser has to reject on its own terms). Strings and their
 * escapes are honoured, so a `}` inside a label cannot end the scan early.
 */
fun String.firstJsonDocument(): String {
    var i = 0
    // A UTF-8 BOM or leading whitespace ahead of the value is left alone: the
    // parser's view of what comes before the value is not this function's
    // business, only what comes after it.
    while (i < length && (this[i].isWhitespace() || this[i] == '\uFEFF')) i++
    if (i >= length || (this[i] != '{' && this[i] != '[')) return this
    var depth = 0
    var inString = false
    var escaped = false
    while (i < length) {
        val c = this[i]
        when {
            inString -> when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> depth++
            c == '}' || c == ']' -> {
                depth--
                if (depth == 0) {
                    val end = i + 1
                    // Nothing but whitespace after the value: hand back the
                    // same string, so the common case allocates nothing.
                    for (j in end until length) {
                        if (!this[j].isWhitespace()) return substring(0, end)
                    }
                    return this
                }
            }
        }
        i++
    }
    return this
}
