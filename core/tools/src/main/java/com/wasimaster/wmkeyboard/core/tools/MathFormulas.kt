package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * The calculator tool's fill-in formulas (#1574): pick one, type the values
 * that are known, and the one left over is worked out, whichever it is.
 *
 * Each formula is written once, solved for its first quantity ([Formula.rhs]).
 * Asking for any other quantity turns it into a root search over that one
 * ([solve]), so `v = u + a·t` also answers for `t` without a second copy of it
 * rearranged by hand. Everything runs through [CalcEngine]; nothing leaves the
 * device.
 */
object MathFormulas {

    enum class Topic(@StringRes val nameRes: Int) {
        ALGEBRA(R.string.core_tools_math_topic_algebra),
        GEOMETRY(R.string.core_tools_math_topic_geometry),
        TRIGONOMETRY(R.string.core_tools_math_topic_trigonometry),
        MECHANICS(R.string.core_tools_math_topic_mechanics),
        ELECTRICITY(R.string.core_tools_math_topic_electricity),
        WAVES(R.string.core_tools_math_topic_waves),
        HEAT(R.string.core_tools_math_topic_heat),
        CHEMISTRY(R.string.core_tools_math_topic_chemistry),
    }

    /**
     * One value a formula relates. [name] is what [Formula.rhs] calls it (letters
     * only, so [CalcEngine] reads it as one name), [shown] how it is printed.
     * [default] fills the field to begin with: g, G, h, c, R and the like.
     */
    class Quantity(
        val name: String,
        val shown: String,
        @StringRes val labelRes: Int,
        val unit: String,
        val default: String? = null,
    )

    class Formula(
        val id: String,
        val topic: Topic,
        @StringRes val nameRes: Int,
        /** The formula as printed, each quantity as `{name}`. */
        val template: String,
        /** The first quantity, in terms of the rest. */
        val rhs: String,
        val quantities: List<Quantity>,
    ) {
        val target: Quantity get() = quantities.first()
    }

    val all: List<Formula> get() = FORMULA_LIST

    fun inTopic(topic: Topic): List<Formula> = FORMULA_LIST.filter { it.topic == topic }

    fun byId(id: String): Formula? = FORMULA_LIST.firstOrNull { it.id == id }

    /** The formula as printed, with its symbols: `v = u + a·t`. */
    fun shown(formula: Formula): String = fill(formula) { it.shown }

    /**
     * The formula with the [values] typed so far in place of their symbols, and
     * [unknown]'s symbol left standing: `12.5 = 3 + 2·t`. A negative value is
     * bracketed, so `b²` with b = −3 reads `(−3)²`.
     */
    fun filled(formula: Formula, values: Map<String, String>, unknown: String): String =
        fill(formula) { quantity ->
            val typed = values[quantity.name]?.takeIf { quantity.name != unknown && it.isNotBlank() }
            when {
                typed == null -> quantity.shown
                typed.startsWith('-') || typed.startsWith('−') -> "(−${typed.drop(1)})"
                else -> typed
            }
        }

    private fun fill(formula: Formula, each: (Quantity) -> String): String {
        var text = formula.template
        for (quantity in formula.quantities) text = text.replace("{${quantity.name}}", each(quantity))
        return text
    }

    /**
     * [unknown] worked out from the [known] values, or null when they leave it
     * without one (a square root of a negative, no root anywhere the search
     * looks). The first quantity comes straight from [Formula.rhs]; any other
     * is a root of `rhs − target` over that quantity, the smallest positive
     * one where there are several (a time, a length and a speed are positive),
     * else zero, else the negative one nearest zero.
     */
    fun solve(formula: Formula, known: Map<String, Double>, unknown: String): Double? {
        val target = formula.target.name
        val vars = HashMap(known)
        vars.remove(unknown)
        return try {
            if (unknown == target) {
                CalcEngine.valueAt(formula.rhs, true, vars).takeIf { it.isFinite() }
            } else {
                val goal = known[target] ?: return null
                findRoot(goal) { z ->
                    vars[unknown] = z
                    CalcEngine.valueAt(formula.rhs, true, vars) - goal
                }
            }
        } catch (_: CalcEngine.CalcException) {
            null
        }
    }

    /**
     * Where [g] crosses zero, searched over ±10^k for k from −[SEARCH_DECADES] to
     * +[SEARCH_DECADES] in quarter decades, then narrowed by bisection. A sign
     * change across a pole (tan near 90°) narrows to a point where g is huge,
     * not near zero, and is passed over.
     */
    internal fun findRoot(scale: Double, g: (Double) -> Double): Double? {
        val steps = SEARCH_DECADES * STEPS_PER_DECADE
        fun at(i: Int, sign: Int): Double = sign * 10.0.pow(i.toDouble() / STEPS_PER_DECADE)

        fun scan(sign: Int): Double? {
            var a = at(-steps, sign)
            var ga = g(a)
            for (i in -steps + 1..steps) {
                val b = at(i, sign)
                val gb = g(b)
                if (ga == 0.0) return a
                if (ga.isFinite() && gb.isFinite() && (ga < 0) != (gb < 0)) {
                    val root = bisect(a, ga, b, g)
                    val residual = abs(g(root))
                    val size = max(abs(scale), max(abs(ga + scale), abs(gb + scale)))
                    if (residual <= size * TOLERANCE || residual < Double.MIN_VALUE * 1e10) return root
                }
                a = b
                ga = gb
            }
            return if (ga == 0.0) a else null
        }
        scan(1)?.let { return it }
        if (g(0.0) == 0.0) return 0.0
        return scan(-1)
    }

    private fun bisect(start: Double, gStart: Double, end: Double, g: (Double) -> Double): Double {
        var lo = start
        var hi = end
        var gLo = gStart
        repeat(BISECTIONS) {
            val mid = (lo + hi) / 2
            if (mid == lo || mid == hi) return mid
            val gMid = g(mid)
            if (gMid == 0.0) return mid
            if (!gMid.isFinite()) return mid
            if ((gLo < 0) != (gMid < 0)) {
                hi = mid
            } else {
                lo = mid
                gLo = gMid
            }
        }
        return (lo + hi) / 2
    }

    /** Masses of electrons to masses of stars, in either direction. */
    private const val SEARCH_DECADES = 40
    private const val STEPS_PER_DECADE = 4
    private const val BISECTIONS = 200
    private const val TOLERANCE = 1e-9
}
