package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `{= expression}` in a snippet's text: a sum worked out when the snippet
 * expands (#1550).
 *
 * `{= 0.2365 * $1 : 2} g protein` behind the pattern
 * `^ate (\d+(?:\.\d+)?) g of peanuts$` turns "ate 125 g of peanuts" into
 * "29.56 g protein". The expression is the calculator tool's own
 * ([CalcEngine]: `+ - * / ^ %`, brackets, decimals, its functions), so it runs
 * on the device and nowhere else. An optional `: N` after it rounds to N
 * decimal places and keeps them all (`47.30`); without one the result is the
 * calculator's usual short form (`47.3`).
 *
 * Capture references inside the braces are read as numbers and nothing else.
 * A capture that is not a plain number makes the whole placeholder stay as
 * written, so user text can never reach the evaluator as an expression.
 */
object SnippetMath {

    /** What opens a sum. `{` alone is every other template variable. */
    const val OPEN = "{="

    /** Longest expression between the braces, in characters. */
    const val MAX_LENGTH = 200

    /** Most decimal places `: N` may ask for. */
    const val MAX_PLACES = 12

    /** A sum found in a text: what stood between the braces, and where it ended. */
    class Span(val body: String, val end: Int)

    /**
     * The `{= … }` that opens at [at] in [text], or null when none does.
     *
     * A `${1:…}` reference inside it carries a `}` of its own, so those are
     * stepped over rather than read as the end. A `{` or a line break inside
     * means it is not a sum at all.
     */
    fun spanAt(text: String, at: Int): Span? {
        if (!text.startsWith(OPEN, at)) return null
        val start = at + OPEN.length
        val limit = minOf(text.length, start + MAX_LENGTH + 1)
        var i = start
        while (i < limit) {
            val c = text[i]
            if (c == '}') return Span(text.substring(start, i), i + 1)
            if (c == '{' || c == '\n') return null
            if (c == '$' && text.getOrNull(i + 1) == '{') {
                val close = text.indexOf('}', startIndex = i + 2)
                if (close < 0 || close >= limit) return null
                i = close + 1
                continue
            }
            i++
        }
        return null
    }

    /**
     * The value of one sum's [body], ready to insert.
     *
     * [group] gives the text a capture reference stands for. A reference with
     * no group behind it (a panel tap, which blanks every capture) gives "",
     * the same blank a bare `$1` leaves. Null means the sum could not be
     * worked out, and the caller keeps it as written so the mistake shows.
     */
    fun evaluate(body: String, group: (Int) -> String?): String? {
        val parts = splitPlaces(body) ?: return null
        val sum = StringBuilder(parts.expression.length + 8)
        var i = 0
        val expression = parts.expression
        while (i < expression.length) {
            val c = expression[i]
            if (c != '$') {
                sum.append(c)
                i++
                continue
            }
            val ref = readReference(expression, i) ?: return null
            val raw = group(ref.index) ?: return ""
            val number = numberOf(raw) ?: return null
            sum.append('(').append(number).append(')')
            i = ref.end
        }
        if (sum.isBlank()) return null
        val value = try {
            CalcEngine.evaluate(sum.toString(), degrees = true)
        } catch (_: CalcEngine.CalcException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }
        return format(value, parts.places)
    }

    /** Every sum in [text] worked out, for a snippet with no captures. */
    fun expandAll(text: String): String {
        if (!text.contains(OPEN)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val span = if (text[i] == '{') spanAt(text, i) else null
            val value = span?.let { evaluate(it.body) { null } }
            if (span != null && value != null) {
                out.append(value)
                i = span.end
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private class Parts(val expression: String, val places: Int?)

    /** `0.2365 * $1 : 2` → the expression and 2. A `:` that is not a count is a fault. */
    private fun splitPlaces(body: String): Parts? {
        val colon = body.lastIndexOf(':')
        // A colon inside `${1:…}` belongs to the reference, not to the places.
        val lastRefClose = body.lastIndexOf('}')
        if (colon < 0 || colon < lastRefClose) return Parts(body.trim(), places = null)
        val count = body.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (count !in 0..MAX_PLACES) return null
        return Parts(body.substring(0, colon).trim(), places = count)
    }

    private class Reference(val index: Int, val end: Int)

    /** `$1`, or `${1}` and `${1:anything}` (the transform means nothing to a number). */
    private fun readReference(text: String, at: Int): Reference? {
        val next = text.getOrNull(at + 1) ?: return null
        if (next in '0'..'9') return Reference(next - '0', at + 2)
        if (next != '{') return null
        val close = text.indexOf('}', startIndex = at + 2)
        if (close < 0) return null
        val index = text.substring(at + 2, close).substringBefore(':').trim().toIntOrNull()
            ?.takeIf { it in 0..SnippetMatcher.MAX_GROUPS } ?: return null
        return Reference(index, close + 1)
    }

    /**
     * [raw] as a number the evaluator can read, or null when it is anything
     * else. Digits, at most one decimal mark (a comma counts as one, for the
     * languages that write `12,5`), and a sign in front.
     */
    private fun numberOf(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val out = StringBuilder(text.length)
        var marks = 0
        var digits = 0
        for ((k, c) in text.withIndex()) {
            when {
                c in '0'..'9' -> { out.append(c); digits++ }
                c == '.' || c == ',' -> { out.append('.'); marks++ }
                (c == '-' || c == '+' || c == '−') && k == 0 -> out.append(if (c == '+') "" else "-")
                else -> return null
            }
        }
        if (digits == 0 || marks > 1) return null
        return out.toString()
    }

    private fun format(value: Double, places: Int?): String {
        if (places == null) return CalcEngine.format(value)
        val rounded = BigDecimal(value.toString()).setScale(places, RoundingMode.HALF_UP)
        // "-0.00" reads as a mistake; a sum that rounds to nothing is 0.
        return if (rounded.signum() == 0) rounded.abs().toPlainString() else rounded.toPlainString()
    }
}
