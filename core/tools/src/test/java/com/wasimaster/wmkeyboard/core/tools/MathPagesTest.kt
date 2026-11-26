package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calculator tool's math pages (#1574): the engine's factorial, inverse
 * hyperbolics and variables, the formula solver, the constants and the chart
 * page's reading of pasted numbers.
 */
class MathPagesTest {

    @Test
    fun factorialIsPostfixAndBindsBeforeThePower() {
        assertEquals(120.0, CalcEngine.evaluate("5!"), 0.0)
        assertEquals(1.0, CalcEngine.evaluate("0!"), 0.0)
        assertEquals(36.0, CalcEngine.evaluate("3!^2"), 0.0)
        assertEquals(-6.0, CalcEngine.evaluate("-3!"), 0.0)
        // Γ(1.5), between the whole numbers.
        assertEquals(Math.sqrt(Math.PI) / 2, CalcEngine.evaluate("0.5!"), 1e-12)
    }

    @Test(expected = CalcEngine.CalcException::class)
    fun aNegativeWholeFactorialHasNoValue() {
        CalcEngine.evaluate("(-2)!")
    }

    @Test
    fun inverseHyperbolicsAreThere() {
        assertEquals(1.0, CalcEngine.evaluate("asinh(sinh(1))"), 1e-12)
        assertEquals(2.0, CalcEngine.evaluate("acosh(cosh(2))"), 1e-12)
        assertEquals(0.5, CalcEngine.evaluate("atanh(tanh(0.5))"), 1e-12)
    }

    @Test
    fun variablesComeBeforeTheBuiltInNames() {
        assertEquals(6.0, CalcEngine.evaluate("2x", variables = mapOf("x" to 3.0)), 0.0)
        // A formula's E is its energy, not Euler's number; e alone still is.
        assertEquals(10.0, CalcEngine.evaluate("E*2", variables = mapOf("E" to 5.0)), 0.0)
        assertEquals(Math.E, CalcEngine.evaluate("e", variables = mapOf("E" to 5.0)), 0.0)
        assertEquals(12.0, CalcEngine.evaluate("dH-T*dS", variables = mapOf("dH" to 20.0, "T" to 4.0, "dS" to 2.0)), 0.0)
    }

    @Test
    fun valueAtAnswersNaNWhereThereIsNoValue() {
        assertTrue(CalcEngine.valueAt("sqrt(x)", false, mapOf("x" to -1.0)).isNaN())
        assertTrue(CalcEngine.valueAt("1/x", false, mapOf("x" to 0.0)).isNaN())
        assertEquals(2.0, CalcEngine.valueAt("sqrt(x)", false, mapOf("x" to 4.0)), 0.0)
    }

    @Test
    fun theFirstQuantityComesStraightFromTheFormula() {
        val f = MathFormulas.byId("velocity_time")!!
        val v = MathFormulas.solve(f, mapOf("u" to 3.0, "a" to 2.0, "t" to 4.75), "v")
        assertEquals(12.5, v!!, 1e-12)
    }

    @Test
    fun anyOtherQuantityIsSolvedFor() {
        val f = MathFormulas.byId("velocity_time")!!
        val t = MathFormulas.solve(f, mapOf("v" to 12.5, "u" to 3.0, "a" to 2.0), "t")
        assertEquals(4.75, t!!, 1e-9)
        // A negative answer where that is the only one: slowing down.
        val a = MathFormulas.solve(f, mapOf("v" to 1.0, "u" to 5.0, "t" to 2.0), "a")
        assertEquals(-2.0, a!!, 1e-9)
    }

    @Test
    fun aQuadraticGivesItsPositiveRoot() {
        // s = u·t + ½·a·t² with s = 10, u = 0, a = 5: t = 2 (and −2).
        val f = MathFormulas.byId("displacement_time")!!
        val t = MathFormulas.solve(f, mapOf("s" to 10.0, "u" to 0.0, "a" to 5.0), "t")
        assertEquals(2.0, t!!, 1e-9)
    }

    @Test
    fun tinyAndHugeValuesAreFound() {
        // The mass of an electron at a given de Broglie wavelength.
        val f = MathFormulas.byId("de_broglie")!!
        val h = 6.62607015e-34
        val m = MathFormulas.solve(f, mapOf("lam" to h / (9.1093837139e-31 * 1e6), "v" to 1e6, "h" to h), "m")
        assertEquals(9.1093837139e-31, m!!, 1e-40)
    }

    @Test
    fun anglesAreInDegreesAndSolvedToTheirPrincipalValue() {
        val f = MathFormulas.byId("right_sine")!!
        val angle = MathFormulas.solve(f, mapOf("a" to 1.0, "c" to 2.0), "t")
        assertEquals(30.0, angle!!, 1e-9)
    }

    @Test
    fun noSolutionIsNull() {
        val f = MathFormulas.byId("right_sine")!!
        // An opposite side longer than the hypotenuse.
        assertNull(MathFormulas.solve(f, mapOf("a" to 3.0, "c" to 2.0), "t"))
        val root = MathFormulas.byId("quadratic_root_plus")!!
        assertNull(MathFormulas.solve(root, mapOf("a" to 1.0, "b" to 0.0, "c" to 1.0), "x"))
    }

    @Test
    fun theFilledFormulaBracketsNegatives() {
        val f = MathFormulas.byId("quadratic_discriminant")!!
        assertEquals("D = b² − 4·a·c", MathFormulas.shown(f))
        assertEquals(
            "D = (−3)² − 4·1·2",
            MathFormulas.filled(f, mapOf("a" to "1", "b" to "-3", "c" to "2"), "D"),
        )
    }

    @Test
    fun everyFormulaWorksOutItsFirstQuantity() {
        for (formula in MathFormulas.all) {
            // Distinct values, so no slope comes out as 0/0.
            val known = formula.quantities.drop(1).withIndex().associate { (i, q) ->
                q.name to (q.default?.toDouble() ?: (2.0 + i))
            }
            val value = MathFormulas.solve(formula, known, formula.target.name)
            // Those coefficients make a quadratic with no real root; the rest answer.
            if (formula.id.startsWith("quadratic_root")) continue
            assertNotNull(formula.id, value)
        }
    }

    @Test
    fun everyConstantParses() {
        for (constant in MathConstants.all) {
            assertTrue(constant.id, constant.number.isFinite())
            assertEquals(constant.id, constant.number, CalcEngine.evaluate(MathConstants.forCalculator(constant)), 0.0)
        }
        assertEquals("6.62607015 × 10⁻³⁴", MathConstants.pretty("6.62607015e-34"))
        assertEquals("299792458", MathConstants.pretty("299792458"))
    }

    @Test
    fun chartDataReadsListsAndTables() {
        val list = ChartData.parse("3, 5, −2.5\n8")
        assertEquals(listOf(3.0, 5.0, -2.5, 8.0), list.map { it.value })
        assertEquals("4", list.last().label)
        val table = ChartData.parse("Fruit\tCount\nApples\t12\nPears\t7\n")
        assertEquals(listOf("Apples", "Pears"), table.map { it.label })
        assertEquals(listOf(12.0, 7.0), table.map { it.value })
        val spaced = ChartData.parse("Red apples 4\nPlums 3")
        assertEquals("Red apples", spaced.first().label)
        val stats = ChartData.stats(listOf(1.0, 3.0, 2.0, 10.0))!!
        assertEquals(2.5, stats.median, 0.0)
        assertEquals(4.0, stats.mean, 0.0)
    }
}
