package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.CalcRouting.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine a sum goes to (CalcRouting), how the calculator chip reaches
 * Numbat, and the Kotlin half of the JNI contract. Numbat itself is native
 * and not here: native/numbat-jni's own `cargo test` covers it. The chip tests
 * stand a fake [SmartSuggest.NumbatLookup] in for it.
 */
class NumbatRoutingTest {

    // ---- the calculator panel ----

    @Test
    fun `plain sums stay with CalcEngine`() {
        for (sum in listOf("12*4", "2+3×4", "50+10%", "5p3", "4c2", "2π", "sin(30)", "√9", "3(4+1)", "1.2e-3*2", "2pi")) {
            assertEquals(sum, Route.CALC_ENGINE, CalcRouting.route(sum))
        }
    }

    @Test
    fun `sums with units go to Numbat`() {
        for (sum in listOf("3 km/h * 20 min", "5 ft + 30 cm -> m", "c * 2 s -> km", "now() + 3 weeks", "1 GiB / (10 Mbit/s)", "60 mph * 2.5 h")) {
            assertEquals(sum, Route.NUMBAT, CalcRouting.route(sum))
        }
    }

    @Test
    fun `what neither reads gets CalcEngine's error`() {
        assertEquals(Route.NONE, CalcRouting.route(""))
        assertEquals(Route.NONE, CalcRouting.route("   "))
        assertEquals(Route.NONE, CalcRouting.route("12*"))
        assertEquals(Route.NONE, CalcRouting.route("(1+2"))
        // Numbat has no °C, so temperatures stay off it.
        assertEquals(Route.NONE, CalcRouting.route("20 °C + 5 °C"))
        assertEquals(Route.NONE, CalcRouting.route("1 m\n+ 2 m"))
        assertEquals(Route.NONE, CalcRouting.route("1 m + " + "1 m + ".repeat(40)))
    }

    @Test
    fun `expressions are spelled the way Numbat reads them`() {
        assertEquals("5 ft + 30 cm -> m", CalcRouting.forNumbat("5 ft + 30 cm in m"))
        assertEquals("5 ft + 30 cm -> m", CalcRouting.forNumbat("5 ft + 30 cm to m"))
        assertEquals("500 W * 3 h -> kWh", CalcRouting.forNumbat("500 W * 3 h as kWh"))
        assertEquals("1500 m + 2 km", CalcRouting.forNumbat("1,500 m + 2 km"))
        assertEquals("sqrt(4 m²)", CalcRouting.forNumbat("√(4 m²)"))
        // Without arithmetic before it, "in" is still the inch.
        assertEquals("3 m in", CalcRouting.forNumbat("3 m in"))
        assertEquals("5 ft + 30 cm -> m", CalcRouting.forNumbat("  5 ft + 30 cm -> m "))
    }

    // ---- the strip ----

    @Test
    fun `the strip only sends arithmetic on quantities or conversions`() {
        assertEquals(Route.NUMBAT, CalcRouting.routeChip("3 km/h * 20 min"))
        assertEquals(Route.NUMBAT, CalcRouting.routeChip("5 ft + 30 cm -> m"))
        assertEquals(Route.NUMBAT, CalcRouting.routeChip("5 ft + 30 cm in m"))
        assertEquals(Route.NUMBAT, CalcRouting.routeChip("1 h + 30 min"))
        assertEquals(Route.CALC_ENGINE, CalcRouting.routeChip("12*4"))
        // A sentence with a number in it.
        for (prose in listOf("I ran 5 km", "2 h 30 min", "see you at 9 am - 5 pm", "5 days in a row", "3 apples", "2 kg of rice + 1 kg")) {
            assertEquals(prose, Route.NONE, CalcRouting.routeChip(prose))
        }
        // An operator left dangling is a sum still being typed.
        assertEquals(Route.NONE, CalcRouting.routeChip("5 ft +"))
        assertFalse(CalcRouting.chipShaped("km + m"))
    }

    private class FakeNumbat(private val answers: Map<String, Numbat.Outcome?>) : SmartSuggest.NumbatLookup {
        val asked = mutableListOf<String>()

        override fun answer(expression: String): Numbat.Outcome? {
            asked += expression
            // A key mapped to null is Numbat still loading; anything else unknown is refused.
            return if (expression in answers) answers[expression] else Numbat.Outcome.Failure("unknown", "x")
        }
    }

    private fun quantity(value: Double, unit: String) = Numbat.Outcome.Answer("$value $unit", Numbat.KIND_QUANTITY, value, unit)

    @Test
    fun `a sum with units raises a calculator chip`() {
        val numbat = FakeNumbat(mapOf("5 ft + 30 cm -> m" to quantity(1.8239999999999998, "m")))
        val hit = SmartSuggest.detect("it is 5 ft + 30 cm -> m", SmartSuggest.Context(numbat = numbat))!!
        assertEquals(SmartSuggest.Kind.CALC, hit.kind)
        assertEquals("5 ft + 30 cm -> m", hit.query)
        assertEquals("1.824 m", hit.result)
        assertEquals("1.824 m", hit.insert)
        assertEquals("5 ft + 30 cm -> m".length, hit.replaceSpan)
        assertEquals(ToolPrefill.Calc("5 ft + 30 cm -> m"), hit.prefill)
    }

    @Test
    fun `an equals sign appends the answer`() {
        val numbat = FakeNumbat(mapOf("3 km/h * 20 min" to quantity(1.0000000000000002, "km")))
        val hit = SmartSuggest.detect("3 km/h * 20 min=", SmartSuggest.Context(numbat = numbat))!!
        assertEquals("1 km", hit.result)
        assertEquals(0, hit.replaceSpan)
    }

    @Test
    fun `the unit chip's in becomes Numbat's arrow`() {
        val numbat = FakeNumbat(mapOf("5 ft + 30 cm -> m" to quantity(1.824, "m")))
        val hit = SmartSuggest.detect("5 ft + 30 cm in m", SmartSuggest.Context(numbat = numbat))
        assertEquals("1.824 m", hit?.result)
        assertEquals(listOf("5 ft + 30 cm -> m"), numbat.asked)
    }

    @Test
    fun `plain sums and prose never ask Numbat`() {
        val numbat = FakeNumbat(emptyMap())
        val calc = SmartSuggest.detect("12*4", SmartSuggest.Context(numbat = numbat))
        assertEquals("48", calc?.result)
        SmartSuggest.detect("I ran 5 km", SmartSuggest.Context(numbat = numbat))
        SmartSuggest.detect("open 9 am - 5 pm", SmartSuggest.Context(numbat = numbat))
        assertEquals(emptyList<String>(), numbat.asked)
    }

    @Test
    fun `without Numbat the strip is as it was`() {
        // The unit chip answers the last quantity alone.
        val hit = SmartSuggest.detect("5 ft + 30 cm", SmartSuggest.Context())
        assertEquals(SmartSuggest.Kind.UNIT, hit?.kind)
        assertEquals("30 cm", hit?.query)
    }

    @Test
    fun `a Numbat still loading falls back to the other chips`() {
        val numbat = FakeNumbat(mapOf("5 ft + 30 cm" to null))
        val hit = SmartSuggest.detect("5 ft + 30 cm", SmartSuggest.Context(numbat = numbat))
        assertEquals(SmartSuggest.Kind.UNIT, hit?.kind)
    }

    @Test
    fun `refusals, dates and echoes make no chip`() {
        val numbat = FakeNumbat(
            mapOf(
                "3 m + 2 s" to Numbat.Outcome.Failure("dimensions"),
                "3 km/h" to quantity(3.0, "km/h"),
                "1 day + 2 h -> x" to Numbat.Outcome.Answer("2026-11-22", "datetime"),
            ),
        )
        assertNull(SmartSuggest.detect("3 m + 2 s", SmartSuggest.Context(numbat = numbat, unitsEnabled = false)))
        assertNull(SmartSuggest.detect("1 day + 2 h -> x", SmartSuggest.Context(numbat = numbat, unitsEnabled = false)))
        assertTrue(numbat.asked.isNotEmpty())
    }

    @Test
    fun `prose before the sum falls away`() {
        val numbat = FakeNumbat(mapOf("3 km/h * 20 min" to quantity(1.0, "km")))
        val hit = SmartSuggest.detect("it was 5 then 3 km/h * 20 min", SmartSuggest.Context(numbat = numbat))
        assertEquals("3 km/h * 20 min", hit?.query)
    }

    @Test
    fun `the calculator setting switches it off`() {
        val numbat = FakeNumbat(mapOf("3 km/h * 20 min" to quantity(1.0, "km")))
        val hit = SmartSuggest.detect("3 km/h * 20 min", SmartSuggest.Context(numbat = numbat, calcEnabled = false))
        assertTrue(hit == null || hit.kind != SmartSuggest.Kind.CALC)
        assertEquals(emptyList<String>(), numbat.asked)
    }

    // ---- the JNI contract ----

    @Test
    fun `answers and failures are read back`() {
        assertEquals(
            Numbat.Outcome.Answer("1.824 m", "quantity", 1.824, "m"),
            Numbat.parse("""{"ok":true,"text":"1.824 m","kind":"quantity","value":1.824,"unit":"m"}"""),
        )
        assertEquals(
            Numbat.Outcome.Answer("2026-12-13 21:04:09", "datetime"),
            Numbat.parse("""{"ok":true,"text":"2026-12-13 21:04:09","kind":"datetime"}"""),
        )
        assertEquals(
            Numbat.Outcome.Failure("unknown", "flurbs"),
            Numbat.parse("""{"ok":false,"code":"unknown","detail":"flurbs","message":"Unknown identifier 'flurbs'."}"""),
        )
        assertEquals(Numbat.Outcome.Failure("native"), Numbat.parse("not json"))
    }

    @Test
    fun `quantities are written in the calculator's precision`() {
        assertEquals("1.824 m", Numbat.format(quantity(1.8239999999999998, "m"), 8))
        assertEquals("48", Numbat.format(quantity(48.0, ""), 8))
        assertEquals("0.33 h", Numbat.format(quantity(1.0 / 3, "h"), 2))
        assertEquals("2026-11-22", Numbat.format(Numbat.Outcome.Answer("2026-11-22", "datetime"), 8))
    }

    @Test
    fun `failures map onto the calculator's own wording`() {
        assertEquals(
            com.wasimaster.wmkeyboard.tools.R.string.core_tools_calc_error_units_mismatch,
            Numbat.error(Numbat.Outcome.Failure("dimensions"))?.messageRes,
        )
        assertEquals("flurbs", Numbat.error(Numbat.Outcome.Failure("unknown", "flurbs"))?.detail)
        assertNull(Numbat.error(Numbat.Outcome.Failure("parse")))
    }

    @Test
    fun `rates go over as units per dollar`() {
        val json = Numbat.ratesJson(CurrencyClient.Rates("USD", mapOf("USD" to 1.0, "EUR" to 0.5, "BDT" to 120.0, "BAD" to Double.NaN)))
        assertTrue(json, json.contains("\"EUR\":0.5"))
        assertTrue(json, json.contains("\"BDT\":120.0"))
        assertFalse(json, json.contains("BAD"))
        // A table on another base is rescaled by its own dollar rate.
        val euro = Numbat.ratesJson(CurrencyClient.Rates("EUR", mapOf("EUR" to 1.0, "USD" to 2.0)))
        assertTrue(euro, euro.contains("\"EUR\":0.5"))
        assertTrue(euro, euro.contains("\"USD\":1.0"))
    }
}
