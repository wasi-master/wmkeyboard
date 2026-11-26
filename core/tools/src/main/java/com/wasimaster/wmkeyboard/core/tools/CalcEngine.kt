package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Expression evaluator for the calculator tool. Recursive descent over
 * `+ - × ÷ * / mod ^ ( )`, the counting operators `nPr` and `nCr`,
 * scientific functions, the postfix factorial `!`, the constants π and e,
 * named variables (the math pages' formulas and graphs, #1574), and implicit
 * multiplication (`2π`, `3(4+1)`, `2x`). Trig honors the tool's degree/radian
 * setting. Pure and synchronous — nothing leaves the device.
 */
object CalcEngine {

    /**
     * A sum the engine cannot finish.
     *
     * [messageRes] is the wording the display shows, and [detail] is the one
     * argument that wording can take (the piece of the expression it names, or
     * "" when it names none). The UI resolves the pair, so the message follows
     * the language the user reads the keyboard in. The exception's own
     * [message] stays English and is for logs only.
     */
    class CalcException(
        @StringRes val messageRes: Int,
        val detail: String = "",
    ) : Exception("Calculator error $messageRes $detail")

    /**
     * Evaluates [expression]; throws [CalcException] with a short,
     * user-facing message when it can't. [degrees] applies to the trig
     * functions (inverse ones return in the same unit). A name in
     * [variables] stands for its value, matched case and all before the
     * built-in names, so a formula's `E` is its energy and not Euler's e.
     */
    fun evaluate(
        expression: String,
        degrees: Boolean = true,
        variables: Map<String, Double> = emptyMap(),
    ): Double {
        val parser = Parser(expression, degrees, variables, lenient = false)
        val value = parser.parseExpression()
        parser.skipSpaces()
        if (!parser.atEnd) {
            throw CalcException(R.string.core_tools_calc_error_unexpected, parser.rest().take(8))
        }
        if (value.isNaN()) throw CalcException(R.string.core_tools_calc_error_undefined_result)
        if (value.isInfinite()) throw CalcException(R.string.core_tools_calc_error_too_large)
        return value
    }

    /**
     * [evaluate] for drawing and for the formula solver (#1574), which ask
     * for hundreds of values in a row: where the sum has no value (a square
     * root of a negative, a division by zero, a pole) this answers NaN or an
     * infinity instead of throwing, so a plot leaves a gap there rather than
     * paying for an exception at every point. A sum that does not parse
     * still throws [CalcException].
     */
    fun valueAt(
        expression: String,
        degrees: Boolean,
        variables: Map<String, Double>,
    ): Double {
        val parser = Parser(expression, degrees, variables, lenient = true)
        val value = parser.parseExpression()
        parser.skipSpaces()
        if (!parser.atEnd) {
            throw CalcException(R.string.core_tools_calc_error_unexpected, parser.rest().take(8))
        }
        return value
    }

    /**
     * Result formatted for insertion: up to [precision] significant
     * decimals, trailing zeros stripped, integer values without a point.
     * Very large/small magnitudes fall back to scientific notation.
     */
    fun format(value: Double, precision: Int = 8): String {
        if (value == 0.0) return "0"
        val magnitude = abs(value)
        if (magnitude >= 1e15 || magnitude < 1e-9) {
            return String.format(java.util.Locale.US, "%.${precision.coerceIn(1, 12)}e", value)
                .replace(Regex("0+e"), "e")
                .replace(".e", "e")
                .replace("e+0", "e+").replace("e-0", "e-")
        }
        val rounded = String.format(java.util.Locale.US, "%.${precision.coerceIn(0, 12)}f", value)
        // Only strip fractional zeros. With precision 0 there is no decimal
        // point, so an unconditional trimEnd('0') would eat an integer's own
        // trailing zeros (100 -> "1").
        return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
    }

    /** The letters that stand for nPr and nCr. */
    private const val CHOOSE_OPS = "pPcC"

    /**
     * nPr — how many ordered picks of [r] there are out of [n]. Built up as
     * a running product rather than from factorials, so 60P3 is a number and
     * not an overflow of 60!.
     */
    private fun permutations(n: Double, r: Double): Double {
        requireCounts(n, r)
        var result = 1.0
        var i = 0.0
        while (i < r) {
            result *= n - i
            if (result.isInfinite()) return result
            i++
        }
        return result
    }

    /**
     * nCr — the same without the ordering. Multiplying and dividing in step
     * keeps every partial value a whole number, and picking the smaller of
     * r and n−r keeps the loop short.
     */
    private fun combinations(n: Double, r: Double): Double {
        requireCounts(n, r)
        var result = 1.0
        val k = kotlin.math.min(r, n - r)
        var i = 0.0
        while (i < k) {
            result = result * (n - i) / (i + 1)
            if (result.isInfinite()) return result
            i++
        }
        return kotlin.math.round(result)
    }

    private fun requireCounts(n: Double, r: Double) {
        if (!countsAreValid(n, r)) {
            throw CalcException(R.string.core_tools_calc_error_bad_counts)
        }
    }

    private fun countsAreValid(n: Double, r: Double): Boolean =
        n == floor(n) && r == floor(r) && n >= 0 && r >= 0 && r <= n

    /**
     * n! for a whole n, and Γ(n+1) between them, so 0.5! is √π/2 the way a
     * scientific calculator has it. NaN at the negative whole numbers, where
     * the gamma function has its poles.
     */
    internal fun factorial(n: Double): Double {
        if (n.isNaN()) return n
        if (n == floor(n)) {
            if (n < 0) return Double.NaN
            if (n > MAX_FACTORIAL) return Double.POSITIVE_INFINITY
            var result = 1.0
            var i = 2.0
            while (i <= n) {
                result *= i
                i++
            }
            return result
        }
        return gamma(n + 1)
    }

    /** The largest n whose n! a Double holds. */
    private const val MAX_FACTORIAL = 170.0

    /** Lanczos (g = 7, nine terms): about fifteen digits, enough for a display of twelve. */
    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
    )

    private fun gamma(x: Double): Double {
        // The reflection formula covers the left half, poles and all.
        if (x < 0.5) return Math.PI / (sin(Math.PI * x) * gamma(1 - x))
        val y = x - 1
        var sum = LANCZOS[0]
        for (i in 1 until LANCZOS.size) sum += LANCZOS[i] / (y + i)
        val t = y + LANCZOS.size - 1.5
        return sqrt(2 * Math.PI) * t.pow(y + 0.5) * exp(-t) * sum
    }

    private class Parser(
        private val text: String,
        private val degrees: Boolean,
        private val variables: Map<String, Double>,
        /** See [valueAt]: no value is NaN, not a [CalcException]. */
        private val lenient: Boolean,
    ) {
        private var pos = 0

        /**
         * A sum with no value: thrown, or NaN in [lenient] mode. Typed as a
         * Double so a caller can write `value = if (ok) … else noValue(…)`.
         */
        private fun noValue(@StringRes messageRes: Int, detail: String = ""): Double {
            if (lenient) return Double.NaN
            throw CalcException(messageRes, detail)
        }

        /**
         * Set by [parsePower] when the value it just read carried a trailing
         * "%", and by [parseTerm] when that value was the whole of the term
         * — the "20%" of "100+20%". [parseExpression] reads the latter to
         * decide whether the term is a share of the running value or a
         * number to add outright.
         */
        private var lastWasPercent = false
        private var percentOfLeft = false

        val atEnd: Boolean get() = pos >= text.length
        fun rest(): String = text.substring(pos)

        fun skipSpaces() {
            while (pos < text.length && text[pos] == ' ') pos++
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun accept(vararg chars: Char): Char? {
            skipSpaces()
            val c = peek() ?: return null
            return if (c in chars) { pos++; c } else null
        }

        // expression := term (('+' | '-') term)*
        //
        // A bare trailing "%" on the right-hand side is a share of what is
        // already on the left: "100+20%" is 120 and "100-20%" is 80, the way
        // a pocket calculator reads a tip or a discount. Alone, or against
        // × and ÷, "%" stays a plain hundredth — "20%" is 0.2 and
        // "100×20%" is 20.
        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                val op = accept('+', '-', '−') ?: return value
                val rhs = parseTerm()
                val delta = if (percentOfLeft) value * rhs else rhs
                value = if (op == '+') value + delta else value - delta
            }
        }

        // term := unary (('*' | '/' | '%' | 'p' | 'c' | juxtaposition) unary)*
        private fun parseTerm(): Double {
            var value = parseUnary()
            // Whether the term is nothing but a percentage, and how many
            // operators it went on to apply — together they tell a lone
            // "20%" apart from a "%" that trails arithmetic of its own.
            val firstWasPercent = lastWasPercent
            var ops = 0
            while (true) {
                skipSpaces()
                val c = peek() ?: break
                when {
                    c == '*' || c == '×' || c == '·' -> { pos++; value *= parseUnary() }
                    c == '/' || c == '÷' -> {
                        pos++
                        val rhs = parseUnary()
                        value = if (rhs == 0.0) noValue(R.string.core_tools_calc_error_division_by_zero) else value / rhs
                    }
                    // A "%" still here is the infix one: [parsePower] has
                    // already taken any that stood for a percentage.
                    c == '%' -> {
                        pos++
                        val rhs = parseUnary()
                        value = if (rhs == 0.0) noValue(R.string.core_tools_calc_error_division_by_zero) else value.mod(rhs)
                    }
                    text.startsWith("mod", pos) -> { pos += 3
                        val rhs = parseUnary()
                        value = if (rhs == 0.0) noValue(R.string.core_tools_calc_error_division_by_zero) else value.mod(rhs)
                    }
                    // "5p3" and "4c2" — the counting operators, written the
                    // way a calculator keypad writes them. A letter that
                    // starts a longer name is never one of them, which keeps
                    // "2pi", "2cos(30)" and "3cbrt(8)" as multiplication.
                    c in CHOOSE_OPS && text.getOrNull(pos + 1)?.isLetter() != true -> {
                        pos++
                        val r = parseUnary()
                        value = when {
                            lenient && !countsAreValid(value, r) -> Double.NaN
                            c == 'p' || c == 'P' -> permutations(value, r)
                            else -> combinations(value, r)
                        }
                    }
                    // Implicit multiplication: 2π, 2(3+4), (1+2)(3+4), 3√4.
                    c == '(' || c == '√' || c.isLetter() && !text.startsWith("mod", pos) ->
                        value *= parseUnary()
                    else -> break
                }
                ops++
            }
            // A share of the left side only when the percentage was the whole
            // of the term: "20%", not "20%×3" or "20%mod3".
            percentOfLeft = ops == 0 && firstWasPercent
            return value
        }

        // unary := ('-' | '+')* power
        private fun parseUnary(): Double {
            val op = accept('-', '−', '+')
            return when (op) {
                '-', '−' -> -parseUnary()
                '+' -> parseUnary()
                else -> parsePower()
            }
        }

        // power := atom '!'* ('^' unary)? '%'?   (right-associative)
        private fun parsePower(): Double {
            var base = parseAtom()
            skipSpaces()
            // "5!" and "3!!" — the factorial binds tighter than the power, so
            // "3!^2" is 36.
            while (peek() == '!') {
                pos++
                val n = base
                base = factorial(n)
                if (base.isNaN()) base = noValue(R.string.core_tools_calc_error_function_undefined, "!")
                skipSpaces()
            }
            val value = if (peek() == '^') {
                pos++
                base.pow(parseUnary())
            } else {
                base
            }
            return takePercent(value)
        }

        /**
         * A "%" that stands for a percentage binds to the value in front of
         * it, so "100×20%" is a fifth of a hundred and "100÷20%" is five
         * hundred. A "%" with a value after it is the infix modulo instead,
         * and is left for [parseTerm].
         */
        private fun takePercent(value: Double): Double {
            lastWasPercent = false
            skipSpaces()
            if (peek() != '%') return value
            var probe = pos + 1
            while (probe < text.length && text[probe] == ' ') probe++
            val next = text.getOrNull(probe)
            if (next != null && (next.isDigit() || next == '.' || next == '(' ||
                    next == '√' || next == 'π' || next.isLetter())
            ) {
                return value
            }
            pos++
            lastWasPercent = true
            return value / 100.0
        }

        private fun parseAtom(): Double {
            skipSpaces()
            val c = peek() ?: throw CalcException(R.string.core_tools_calc_error_incomplete)
            return when {
                c == '(' -> {
                    pos++
                    val value = parseExpression()
                    if (accept(')') == null) {
                        throw CalcException(R.string.core_tools_calc_error_missing_bracket)
                    }
                    value
                }
                c == '√' -> { pos++; applyChecked("√", sqrt(parseUnary())) }
                c.isDigit() || c == '.' -> parseNumber()
                c.isLetter() || c == 'π' -> parseNameOrFunction()
                else -> throw CalcException(R.string.core_tools_calc_error_unexpected, c.toString())
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            // Scientific literal: 1.2e-3 (only when digits follow the sign).
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                var probe = pos + 1
                if (probe < text.length && (text[probe] == '+' || text[probe] == '-')) probe++
                if (probe < text.length && text[probe].isDigit()) {
                    pos = probe
                    while (pos < text.length && text[pos].isDigit()) pos++
                }
            }
            return text.substring(start, pos).toDoubleOrNull()
                ?: throw CalcException(
                    R.string.core_tools_calc_error_bad_number,
                    text.substring(start, pos),
                )
        }

        private fun parseNameOrFunction(): Double {
            if (peek() == 'π') { pos++; return Math.PI }
            val start = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            val raw = text.substring(start, pos)
            variables[raw]?.let { return it }
            val name = raw.lowercase()
            when (name) {
                "pi" -> return Math.PI
                "e" -> return Math.E
            }
            skipSpaces()
            val arg = if (peek() == '(') {
                pos++
                val value = parseExpression()
                if (accept(')') == null) {
                    throw CalcException(R.string.core_tools_calc_error_missing_bracket)
                }
                value
            } else {
                // sin 30, √-style tight binding for a parenless argument.
                parseUnary()
            }
            val toRad = if (degrees) Math.PI / 180 else 1.0
            val fromRad = if (degrees) 180 / Math.PI else 1.0
            val result = when (name) {
                "sin" -> sin(arg * toRad)
                "cos" -> cos(arg * toRad)
                "tan" -> tan(arg * toRad)
                "asin" -> asin(arg) * fromRad
                "acos" -> acos(arg) * fromRad
                "atan" -> atan(arg) * fromRad
                "sinh" -> sinh(arg)
                "cosh" -> cosh(arg)
                "tanh" -> tanh(arg)
                "asinh" -> asinh(arg)
                "acosh" -> acosh(arg)
                "atanh" -> atanh(arg)
                "ln" -> ln(arg)
                "log" -> log10(arg)
                "lg" -> log2(arg)
                "sqrt" -> sqrt(arg)
                "cbrt" -> cbrt(arg)
                "abs" -> abs(arg)
                "exp" -> exp(arg)
                "floor" -> floor(arg)
                "ceil" -> ceil(arg)
                "round" -> kotlin.math.round(arg)
                else -> throw CalcException(R.string.core_tools_calc_error_unknown_function, name)
            }
            return applyChecked(name, result)
        }

        private fun applyChecked(name: String, value: Double): Double {
            if (value.isNaN()) {
                return noValue(R.string.core_tools_calc_error_function_undefined, name)
            }
            return value
        }
    }
}
