package com.wasimaster.wmkeyboard.core.tools

/**
 * Which engine answers a sum. [CalcEngine] first, always: it is instant, it
 * is in every build, and it reads every plain sum the way the calculator
 * always has ("50+10%" is 50.1, "5p3" is 60). Only an expression it cannot
 * read, and that looks like a sum with units, constants or dates in it, goes
 * to [Numbat], which may not be fetched yet. Pure: the tests pin each case.
 */
object CalcRouting {

    enum class Route {
        /** [CalcEngine] reads it; its answer stands. */
        CALC_ENGINE,

        /** [CalcEngine] cannot, and it is worth asking [Numbat]. */
        NUMBAT,

        /** Neither: [CalcEngine]'s error is the answer. */
        NONE,
    }

    /** Longer than this is not a sum anyone types into one line. */
    const val MAX_LENGTH = 200

    /**
     * Where the calculator panel sends [expression]. Anything with a letter
     * that [CalcEngine] refused goes to Numbat: the user typed it into a
     * calculator, so prose is not the worry the strip has.
     */
    fun route(expression: String, degrees: Boolean = true): Route {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return Route.NONE
        if (runCatching { CalcEngine.evaluate(trimmed, degrees) }.isSuccess) return Route.CALC_ENGINE
        return if (numbatShaped(trimmed)) Route.NUMBAT else Route.NONE
    }

    /**
     * Where the suggestion strip sends the text at the cursor, which is
     * whatever the user was writing to someone: a sentence with a number in
     * it must stay a sentence. So on top of [route], Numbat only gets text
     * that does arithmetic on a quantity or converts one ([chipShaped]).
     */
    fun routeChip(expression: String, degrees: Boolean = true): Route = when (val route = route(expression, degrees)) {
        Route.NUMBAT -> if (chipShaped(forNumbat(expression))) Route.NUMBAT else Route.NONE
        else -> route
    }

    /** A letter, one line, short, and no temperature (Numbat has no °C). */
    fun numbatShaped(expression: String): Boolean =
        expression.length <= MAX_LENGTH &&
            expression.none { it == '\n' || it == '\r' } &&
            expression.any { it.isLetter() } &&
            TEMPERATURE.none { it in expression }

    /**
     * Arithmetic on a quantity, or a conversion: a digit, a letter, and
     * either an operator between two parts or an arrow or "to" before a
     * unit. "5 ft + 30 cm", "3 km/h * 20 min", "5 ft + 30 cm -> m". Not
     * "2 h 30 min" (Numbat multiplies those) and not "I ran 5 km".
     */
    fun chipShaped(expression: String): Boolean {
        if (!numbatShaped(expression) || expression.none { it.isDigit() }) return false
        val last = expression.last()
        if (!(last.isLetterOrDigit() || last == ')' || last in "²³")) return false
        if (WORD.findAll(expression).any { it.value.lowercase() in PROSE_WORDS }) return false
        return BETWEEN.containsMatchIn(expression) || CONVERSION.containsMatchIn(expression)
    }

    /**
     * [expression] spelled the way Numbat reads it: the root sign as
     * `sqrt(`, grouping commas dropped ("1,500 m"), and a trailing "to",
     * "in", "into" or "as" before a unit turned into the conversion arrow,
     * since in Numbat "in" is the inch ("5 ft + 30 cm in m"). The trailing word only
     * becomes an arrow when something before it already did arithmetic, so
     * "3 m in" stays three metre-inches if that is what was typed.
     */
    fun forNumbat(expression: String): String {
        var text = expression.trim()
        text = GROUPING.replace(text, "")
        text = text.replace("√(", "sqrt(")
        val asked = TRAILING_IN.find(text)
        if (asked != null && BETWEEN.containsMatchIn(text.substring(0, asked.range.first))) {
            text = text.substring(0, asked.range.first) + " -> " + asked.groupValues[1]
        }
        return text
    }

    private val TEMPERATURE = listOf("°C", "°F", "℃", "℉")

    private val WORD = Regex("""\p{L}+""")

    /**
     * Words of a sentence that Numbat would read as units: "9 am - 5 pm" is
     * attometres and picometres to it, "a" is the are, "at" the technical
     * atmosphere and "as" the attosecond. Text holding one stays off the strip.
     */
    private val PROSE_WORDS = setOf("am", "pm", "a", "an", "at", "as", "in", "into", "to", "it", "is", "or", "and", "of")

    /** An operator with something on both sides: "5 ft + 30 cm", "3 km/h*20 min". */
    private val BETWEEN = Regex("""[\p{L}\d)²³]\s*[+\-*/×÷−·^]\s*[\p{L}\d(]""")

    /** "-> m", "→ m", " to mph", " in m", " as kWh" before a unit at the end. */
    private val CONVERSION = Regex("""(?:->|→|\s(?:to|in|into|as)\s)\s*[\p{L}µ°][\p{L}\d/^µ²³·*]*$""", RegexOption.IGNORE_CASE)

    private val TRAILING_IN = Regex("""\s+(?:to|in|into|as)\s+([\p{L}µ][\p{L}\d/^µ²³]*)$""", RegexOption.IGNORE_CASE)

    /** A comma between digits that groups thousands: "1,500". */
    private val GROUPING = Regex("""(?<=\d),(?=\d{3}(?!\d))""")
}
