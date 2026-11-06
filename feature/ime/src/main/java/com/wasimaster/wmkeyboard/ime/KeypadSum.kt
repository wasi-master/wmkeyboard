package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import kotlin.math.roundToLong

/**
 * A sum typed on a number field's keypad: "120", then × 3, answered on the
 * strip as 360.
 *
 * In a text field the calc chip reads the sum back out of the field. A number
 * field cannot hold one. A native `inputType="number"` box filters every
 * character its digits listener does not take, so the × and the 3 after it
 * never land, and Chrome's `<input type=number>` drops × and ÷ the same way.
 * The chip had nothing to read in exactly the fields where a sum is most
 * wanted: an amount, a quantity, a tip.
 *
 * So the keyboard holds the sum itself. [operand] is the number already in
 * the field, in front of the caret, and stays there untouched. [tail] is
 * everything from the first operator on, which only the keyboard has. Taking
 * the answer swaps the operand for the result; letting the sum go leaves the
 * field as it was.
 */
internal data class KeypadSum(val operand: String, val tail: String) {

    /** The sum as the strip shows it: "120 × 3", with real minus and times signs. */
    val display: String
        get() = buildString {
            val raw = operand + tail
            raw.forEachIndexed { i, c ->
                val prev = raw.getOrNull(i - 1)
                // A sign after a number is an operator; at the start, or after
                // another operator, it belongs to the number that follows.
                val binary = prev != null && (prev.isDigit() || prev in ").,%")
                when {
                    c == '*' || c == '×' -> append(" × ")
                    c == '/' || c == '÷' -> append(" ÷ ")
                    c == '^' -> append(" ^ ")
                    c == '+' && binary -> append(" + ")
                    (c == '-' || c == '−') && binary -> append(" − ")
                    c == '-' -> append('−')
                    else -> append(c)
                }
            }
        }.trim()

    /** The sum in the engine's terms: ASCII digits, a point for the decimal. */
    val expression: String get() = normalized(operand + tail)

    /**
     * Whether the answer is written with a decimal comma: the number in the
     * field already used one, or one was typed into the sum, and no point was.
     */
    private val decimalComma: Boolean
        get() = (operand + tail).let { ',' in it && '.' !in it }

    /** [text] added to the sum, or null when [text] is not part of a sum. */
    fun append(text: String): KeypadSum? {
        if (text.isEmpty() || !text.all { belongs(it) }) return null
        // A second operator straight after the first replaces it, the way a
        // calculator's does, so a slip from + to × needs no backspace. A minus
        // after × or ÷ is kept: that one makes the next number negative.
        val last = tail.lastOrNull()
        if (text.length == 1 && last != null && last in BINARY) {
            val c = text[0]
            val replaces = when (c) {
                '-', '−' -> last == '+' || last == '-' || last == '−'
                else -> c in BINARY
            }
            if (replaces) return copy(tail = tail.dropLast(1) + c)
        }
        return copy(tail = tail + text)
    }

    /** The sum with its last character gone, or null once the operator is. */
    fun backspace(): KeypadSum? {
        val left = tail.dropLast(1)
        return if (left.isEmpty()) null else copy(tail = left)
    }

    /**
     * What the sum comes to, as the field can take it. A sum still waiting on
     * its next number ("120 ×") answers with what it has so far, so the chip
     * holds still instead of spinning. [decimals] is whether the field takes
     * a fraction at all; one that does not gets the answer rounded, marked
     * with "~", rather than "12.5" typed as "125" once the field drops the
     * point. Null when there is nothing that evaluates.
     */
    fun answer(degrees: Boolean, precision: Int, decimals: Boolean): Answer? {
        val expr = expression
        val value = evaluate(expr, degrees) ?: evaluate(expr.trimEnd { it in TRAILING }, degrees) ?: return null
        val fractional = value != kotlin.math.floor(value)
        if (fractional && !decimals && kotlin.math.abs(value) < 1e15) {
            val rounded = value.roundToLong().toString()
            return Answer(shown = "~$rounded", insert = rounded)
        }
        var text = CalcEngine.format(value, precision)
        if (decimalComma) text = text.replace('.', ',')
        return Answer(shown = text, insert = text)
    }

    /**
     * The chip for the sum. Tapping it deletes the [operand] from the field
     * and types the answer in its place; its gear takes the sum to the
     * Calculator tool.
     */
    fun hit(degrees: Boolean, precision: Int, decimals: Boolean): SmartSuggest.SmartHit {
        val answer = answer(degrees, precision, decimals)
        val shown = answer?.shown ?: operand
        return SmartSuggest.SmartHit(
            kind = SmartSuggest.Kind.CALC,
            query = display,
            result = shown,
            insert = answer?.insert ?: operand,
            replaceSpan = operand.length,
            tool = ToolbarTool.CALCULATOR,
            prefill = ToolPrefill.Calc(expression.replace('*', '×').replace('/', '÷')),
            tiers = listOf(SmartSuggest.ChipTier(display, shown)),
        )
    }

    /** [shown] is the chip's face; [insert] is what a tap types. */
    data class Answer(val shown: String, val insert: String)

    companion object {
        /** How far back the field is read for the number a sum starts from. */
        const val LOOKBEHIND = 64

        /** Operators that join two numbers. */
        private const val BINARY = "+-*/×÷−^"

        /** What a sum still waiting on its next number ends in. */
        private const val TRAILING = "+-*/×÷−^.,( "

        /**
         * Whether [output], typed straight after a number, starts a sum.
         * Percent is left out: "50%" alone is not a sum anyone means, and a
         * field that took it would keep it.
         */
        fun startsSum(output: String): Boolean = output.length == 1 && output[0] in BINARY

        private fun belongs(c: Char): Boolean = c.isDigit() || c in "+-*/×÷−^%.,()"

        /**
         * The number that ends [before], the text in front of the caret, with
         * its minus sign when it has one; null when the caret is not after a
         * number. A minus only counts at the start of the text or after
         * something that is not part of a number, so "5-3" gives "3".
         */
        fun operandBefore(before: String): String? {
            var start = before.length
            while (start > 0 && (before[start - 1].isDigit() || before[start - 1] in ".,")) start--
            // A separator cannot open a number: ",5" after a word is "5".
            while (start < before.length && before[start] in ".,") start++
            val run = before.substring(start)
            if (run.none { it.isDigit() }) return null
            val sign = start > 0 && before[start - 1] in "-−" &&
                (start == 1 || !before[start - 2].let { it.isLetterOrDigit() || it in ".," })
            return if (sign) before.substring(start - 1) else run
        }

        /** Native digits to ASCII, a comma to a point, typographic signs to ASCII. */
        private fun normalized(raw: String): String = buildString {
            for (c in raw) {
                when {
                    c.isDigit() -> append(Character.forDigit(Character.digit(c, 10).coerceAtLeast(0), 10))
                    c == ',' -> append('.')
                    c == '−' -> append('-')
                    c == '×' -> append('*')
                    c == '÷' -> append('/')
                    else -> append(c)
                }
            }
        }

        private fun evaluate(expr: String, degrees: Boolean): Double? {
            if (expr.isEmpty()) return null
            return runCatching { CalcEngine.evaluate(expr, degrees) }.getOrNull()
                ?.takeUnless { it.isNaN() || it.isInfinite() }
        }
    }
}
