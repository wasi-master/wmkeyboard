package com.wasimaster.wmkeyboard.core.tools

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcEngineTest {

    private fun eval(expr: String, degrees: Boolean = true) = CalcEngine.evaluate(expr, degrees)

    @Test
    fun basicArithmetic() {
        assertEquals(7.0, eval("1+2*3"), 1e-9)
        assertEquals(9.0, eval("(1+2)*3"), 1e-9)
        assertEquals(2.5, eval("5/2"), 1e-9)
        assertEquals(-4.0, eval("-4"), 1e-9)
        assertEquals(1.0, eval("5 mod 2"), 1e-9)
        assertEquals(8.0, eval("2^3"), 1e-9)
        // Right-associative power.
        assertEquals(512.0, eval("2^3^2"), 1e-9)
    }

    @Test
    fun unicodeOperators() {
        assertEquals(6.0, eval("2×3"), 1e-9)
        assertEquals(2.0, eval("6÷3"), 1e-9)
        assertEquals(1.0, eval("3−2"), 1e-9)
    }

    @Test
    fun functionsAndConstants() {
        assertEquals(1.0, eval("sin(90)"), 1e-9)
        assertEquals(0.0, eval("sin(pi)", degrees = false), 1e-9)
        assertEquals(2.0, eval("sqrt(4)"), 1e-9)
        assertEquals(2.0, eval("√4"), 1e-9)
        assertEquals(3.0, eval("log(1000)"), 1e-9)
        assertEquals(1.0, eval("ln(e)", degrees = false), 1e-9)
        assertEquals(120.0, eval("abs(-120)"), 1e-9)
    }

    @Test
    fun implicitMultiplication() {
        assertEquals(2 * Math.PI, eval("2π"), 1e-9)
        assertEquals(14.0, eval("2(3+4)"), 1e-9)
        assertEquals(6.0, eval("(1+2)(2)"), 1e-9)
    }

    @Test
    fun percent() {
        assertEquals(0.5, eval("50%"), 1e-9)
        assertEquals(1.0, eval("5%2"), 1e-9) // % followed by value = modulo
    }

    @Test
    fun errorsAreReported() {
        assertFails("1/0")
        assertFails("2+")
        assertFails("foo(2)")
        assertFails("(1+2")
        assertFails("sqrt(-1)")
    }

    private fun assertFails(expr: String) {
        try {
            eval(expr)
            throw AssertionError("Expected failure for “$expr”")
        } catch (expected: CalcEngine.CalcException) {
            // expected
        }
    }

    @Test
    fun formatting() {
        assertEquals("3.14159265", CalcEngine.format(Math.PI, 8))
        assertEquals("2", CalcEngine.format(2.0, 8))
        assertEquals("0.5", CalcEngine.format(0.5, 8))
        assertTrue(CalcEngine.format(1e20, 6).contains("e"))
    }
}

class UnitConvertTest {

    // Looked up by the category's stored id, not by its label. The label is a
    // string resource now; the id is the part that must never change once
    // shipped, so it is the right thing for a test to name a category by.
    private fun unit(categoryId: String, symbol: String): UnitConvert.ConvUnit =
        UnitConvert.categories.first { it.id == categoryId }.units.first { it.symbol == symbol }

    @Test
    fun length() {
        assertEquals(
            1609.344,
            UnitConvert.convert(1.0, unit("Length", "mi"), unit("Length", "m")),
            1e-6,
        )
        assertEquals(
            2.54,
            UnitConvert.convert(1.0, unit("Length", "in"), unit("Length", "cm")),
            1e-9,
        )
    }

    @Test
    fun temperature() {
        assertEquals(
            212.0,
            UnitConvert.convert(100.0, unit("Temperature", "°C"), unit("Temperature", "°F")),
            1e-9,
        )
        assertEquals(
            273.15,
            UnitConvert.convert(0.0, unit("Temperature", "°C"), unit("Temperature", "K")),
            1e-9,
        )
        assertEquals(
            -40.0,
            UnitConvert.convert(-40.0, unit("Temperature", "°F"), unit("Temperature", "°C")),
            1e-9,
        )
    }

    @Test
    fun fuelEconomyReciprocal() {
        // 5 L/100km ≈ 47.04 mpg US
        assertEquals(
            47.04,
            UnitConvert.convert(5.0, unit("Fuel economy", "L/100km"), unit("Fuel economy", "mpg")),
            0.01,
        )
        // Round-trips through a reciprocal unit.
        val mpg = unit("Fuel economy", "mpg")
        val l100 = unit("Fuel economy", "L/100km")
        assertEquals(30.0, UnitConvert.convert(UnitConvert.convert(30.0, mpg, l100), l100, mpg), 1e-9)
    }

    @Test
    fun data() {
        assertEquals(
            1024.0,
            UnitConvert.convert(1.0, unit("Data", "KiB"), unit("Data", "B")),
            1e-9,
        )
        assertEquals(
            8.0,
            UnitConvert.convert(1.0, unit("Data", "B"), unit("Data", "bit")),
            1e-9,
        )
    }
}

class PasswordGenTest {

    private val random = SecureRandom()

    @Test
    fun passwordRespectsSpec() {
        val spec = PasswordGen.PasswordSpec(
            length = 20, upper = true, digits = true, symbols = true,
        )
        repeat(50) {
            val password = PasswordGen.password(spec, random)
            assertEquals(20, password.length)
            assertTrue(password.any { ch -> ch.isUpperCase() })
            assertTrue(password.any { ch -> ch.isDigit() })
            assertTrue(password.any { ch -> !ch.isLetterOrDigit() })
        }
    }

    @Test
    fun passwordDisabledClassesAbsent() {
        val spec = PasswordGen.PasswordSpec(
            length = 32, upper = false, digits = false, symbols = false,
        )
        repeat(20) {
            val password = PasswordGen.password(spec, random)
            assertTrue(password.all { ch -> ch in 'a'..'z' })
        }
    }

    @Test
    fun excludeAmbiguousFilters() {
        val spec = PasswordGen.PasswordSpec(
            length = 64, upper = true, digits = true, symbols = false, excludeAmbiguous = true,
        )
        repeat(20) {
            val password = PasswordGen.password(spec, random)
            assertTrue(password.none { ch -> ch in "Il1O0o5S8B" })
        }
    }

    @Test
    fun passphraseShape() {
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo")
        val phrase = PasswordGen.passphrase(
            words,
            PasswordGen.PassphraseSpec(words = 4, separator = "-", capitalize = true),
            random,
        )
        val parts = phrase.split("-")
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.first().isUpperCase() })
    }

    @Test
    fun wordlistFilter() {
        val words = PasswordGen.buildWordlist(
            sequenceOf("ok", "good", "Capital", "hyphen-ed", "sunshine", "waterfalls", "good"),
        )
        assertEquals(listOf("good", "sunshine"), words)
    }
}

class CurrencyClientTest {

    @Test
    fun parsesErApi() {
        val rates = CurrencyClient.parseErApi(
            """{"result":"success","rates":{"USD":1,"EUR":0.9,"BDT":120.5}}""",
        )
        assertEquals(0.9, rates.rates.getValue("EUR"), 1e-9)
        assertEquals(1.0, rates.rates.getValue("USD"), 1e-9)
        // Cross conversion EUR -> BDT.
        assertEquals(
            120.5 / 0.9,
            CurrencyClient.convert(1.0, "EUR", "BDT", rates)!!,
            1e-9,
        )
    }

    @Test
    fun parsesFrankfurter() {
        // The v1 shape at api.frankfurter.dev, which the old host redirects to.
        val rates = CurrencyClient.parseFrankfurter(
            """{"amount":1.0,"base":"USD","date":"2026-09-09","rates":{"EUR":0.92,"GBP":0.79}}""",
        )
        assertEquals(0.79, rates.rates.getValue("GBP"), 1e-9)
        assertNotNull(rates.rates["USD"])
    }

    @Test
    fun parsesAllRatesToday() {
        // The open endpoint at allratestoday.com, in the ExchangeRate-API shape.
        val rates = CurrencyClient.parseAllRatesToday(
            """{"result":"success","base_code":"USD","date":"2026-10-29","rates":{"EUR":0.8982,"GBP":0.7811}}""",
        )
        assertEquals(0.7811, rates.rates.getValue("GBP"), 1e-9)
        assertNotNull(rates.rates["USD"])
        assertThrows(IllegalStateException::class.java) {
            CurrencyClient.parseAllRatesToday("""{"error":"unknown currency"}""")
        }
    }

    @Test
    fun theFdroidBuildStartsFromFreeSourcesOnly() {
        // currency-api (CC0 data) and Frankfurter (MIT) are free software with
        // open data; ExchangeRate-API, Coinbase and CoinGecko are not.
        assertEquals(
            listOf("CURRENCY_API", "FRANKFURTER"),
            CurrencyClient.Provider.fiatDefaults(fdroid = true),
        )
        assertEquals(listOf("CURRENCY_API"), CurrencyClient.Provider.cryptoDefaults(fdroid = true))
    }

    @Test
    fun theOtherBuildsKeepTheirDefaults() {
        assertEquals(listOf("ER_API", "FRANKFURTER"), CurrencyClient.Provider.fiatDefaults(fdroid = false))
        assertEquals(
            listOf("COINBASE", "CURRENCY_API"),
            CurrencyClient.Provider.cryptoDefaults(fdroid = false),
        )
    }

    @Test
    fun everyDefaultNamesARealProviderThatServesItsTable() {
        for (fdroid in listOf(true, false)) {
            val fiat = CurrencyClient.Provider.fiatDefaults(fdroid).map { CurrencyClient.Provider.of(it) }
            val coins = CurrencyClient.Provider.cryptoDefaults(fdroid).map { CurrencyClient.Provider.of(it) }
            assertTrue(fiat.all { it != null && it.fiat })
            assertTrue(coins.all { it != null && it.crypto })
        }
    }

    @Test
    fun unknownCodeReturnsNull() {
        val rates = CurrencyClient.Rates("USD", mapOf("USD" to 1.0))
        assertNull(CurrencyClient.convert(1.0, "USD", "XXX", rates))
    }

    @Test
    fun unitNameIsTheBareCurrencyNameOrTheCode() {
        assertEquals("Taka", CurrencyClient.unitName("BDT"))
        assertEquals("Dollar", CurrencyClient.unitName("USD"))
        assertEquals("Euro", CurrencyClient.unitName("EUR"))
        // No known name → the code itself.
        assertEquals("XAF", CurrencyClient.unitName("XAF"))
    }

    @Test
    fun parsesCoinbaseWhereEveryRateIsAString() {
        val rates = CurrencyClient.parseCoinbase(
            """{"data":{"currency":"USD","rates":{"BTC":"0.0000153999","EUR":"0.86"}}}""",
        )
        assertEquals(1.53999e-5, rates.getValue("BTC"), 1e-12)
        assertEquals(0.86, rates.getValue("EUR"), 1e-9)
    }

    @Test
    fun parsesCurrencyApiAndCapitalisesItsCodes() {
        val rates = CurrencyClient.parseCurrencyApi(
            """{"date":"2026-08-06","usd":{"btc":0.0000154,"bdt":123.99}}""",
        )
        assertEquals(123.99, rates.getValue("BDT"), 1e-9)
        assertNotNull(rates["BTC"])
    }

    @Test
    fun coinGeckoPricesAreInvertedIntoCoinsPerDollar() {
        val rates = CurrencyClient.parseCoinGecko(
            """{"bitcoin":{"usd":64988},"ethereum":{"usd":1914.5}}""",
            mapOf("bitcoin" to "BTC", "ethereum" to "ETH"),
        )
        assertEquals(1.0 / 64988, rates.getValue("BTC"), 1e-12)
        assertEquals(1.0 / 1914.5, rates.getValue("ETH"), 1e-12)
    }

    @Test
    fun ratesThatAreNotUsableNumbersAreDropped() {
        val rates = CurrencyClient.parseCoinbase(
            """{"data":{"rates":{"BTC":"0.0001","DEAD":"0","BAD":"abc","NEG":"-2"}}}""",
        )
        assertEquals(setOf("BTC"), rates.keys)
    }

    @Test
    fun mergingKeepsOnlyTheCoinsThatAreOn() {
        val fiat = CurrencyClient.Rates("USD", mapOf("USD" to 1.0, "BDT" to 120.0))
        val raw = mapOf("BTC" to 0.000015, "DOGE" to 14.0, "PEPE" to 1e6)
        val merged = CurrencyClient.withCrypto(fiat, raw, setOf("BTC"))
        assertEquals(setOf("BTC"), merged.crypto)
        assertEquals(setOf("USD", "BDT", "BTC"), merged.rates.keys)
        assertTrue(merged.isCrypto("BTC"))
        assertFalse(merged.isCrypto("BDT"))
    }

    @Test
    fun aCoinSourceCannotDisplaceTheCurrencyTable() {
        // Coinbase and the currency-api quote EUR too, and their reading must
        // not overwrite the fiat one the rest of the tool runs on.
        val fiat = CurrencyClient.Rates("USD", mapOf("USD" to 1.0, "EUR" to 0.9))
        val merged = CurrencyClient.withCrypto(fiat, mapOf("EUR" to 0.5, "BTC" to 0.000015), setOf("BTC", "EUR"))
        assertEquals(0.9, merged.rates.getValue("EUR"), 1e-9)
        assertEquals(setOf("BTC"), merged.crypto)
    }

    @Test
    fun conversionCrossesBetweenCoinsAndCurrencies() {
        val fiat = CurrencyClient.Rates("USD", mapOf("USD" to 1.0, "BDT" to 120.0))
        val rates = CurrencyClient.withCrypto(fiat, mapOf("BTC" to 0.000015), setOf("BTC"))
        assertEquals(8_000_000.0, CurrencyClient.convert(1.0, "BTC", "BDT", rates)!!, 1e-6)
        assertEquals(1.5e-5, CurrencyClient.convert(1.0, "USD", "BTC", rates)!!, 1e-12)
    }
}

class CryptoCatalogTest {

    @Test
    fun aliasesAreLowercaseUniqueAndShortEnoughToMatch() {
        val seen = mutableSetOf<String>()
        for (coin in CryptoCatalog.coins) {
            for (alias in coin.aliases) {
                assertEquals("$alias is not lower case", alias.lowercase(), alias)
                // The currency scanner reads at most eight letters after an
                // amount, so a longer alias could never match anything.
                assertTrue("$alias is too long to ever match", alias.length in 2..8)
                assertTrue("$alias is claimed twice", seen.add(alias))
            }
        }
    }

    @Test
    fun namesResolveAlwaysAndBareTickersOnlyInCapitals() {
        assertEquals("BTC" to 1.0, CryptoCatalog.resolve("bitcoin", emptySet()))
        assertEquals("BTC" to 1.0, CryptoCatalog.resolve("btc", emptySet()))
        assertEquals("LINK" to 1.0, CryptoCatalog.resolve("LINK", emptySet()))
        assertNull(CryptoCatalog.resolve("link", emptySet()))
        // 1e-8 of a bitcoin.
        assertEquals("BTC" to 1e-8, CryptoCatalog.resolve("sats", emptySet()))
    }

    @Test
    fun aCoinThatIsOffResolvesToNothing() {
        assertNull(CryptoCatalog.resolve("bitcoin", setOf("ETH")))
        assertNull(CryptoCatalog.resolve("DOGE", setOf("ETH")))
    }

    @Test
    fun aTickerTheUserAddedResolvesInCapitals() {
        assertEquals("PEPE" to 1.0, CryptoCatalog.resolve("PEPE", setOf("PEPE")))
        assertNull(CryptoCatalog.resolve("pepe", setOf("PEPE")))
    }
}

class MoneyFormatTest {

    @Test
    fun significantDigitsSurviveWhereFixedDecimalsWouldNot() {
        assertEquals("0.0000154", MoneyFormat.significant(0.0000153999))
        assertEquals("0.00", MoneyFormat.fixed(0.0000153999, 2))
    }

    @Test
    fun trailingZerosAreDropped() {
        assertEquals("1.5", MoneyFormat.significant(1.5))
        assertEquals("2", MoneyFormat.significant(2.0))
        assertEquals("0", MoneyFormat.significant(0.0))
    }

    @Test
    fun anAmountTooSmallForTheDecimalCapKeepsItsDigits() {
        val text = MoneyFormat.significant(1e-15)
        assertNotEquals("0", text)
        assertTrue(text.any { it in '1'..'9' })
    }

    @Test
    fun coinAmountsFollowTheirOwnDecimalSetting() {
        assertEquals("0.125", MoneyFormat.amount(0.125, isCrypto = true, fiatDecimals = 2, cryptoDecimals = 0))
        assertEquals("0.1250", MoneyFormat.amount(0.125, isCrypto = true, fiatDecimals = 2, cryptoDecimals = 4))
        // Fiat rounds to its own two places, half-to-even as it always has.
        assertEquals("0.12", MoneyFormat.amount(0.125, isCrypto = false, fiatDecimals = 2, cryptoDecimals = 4))
    }
}

class WikipediaClientTest {

    @Test
    fun parsesSearch() {
        val results = WikipediaClient.parseSearch(
            """{"query":{"search":[
                {"title":"Kotlin (programming language)","snippet":"<span class=\"searchmatch\">Kotlin</span> is a language"},
                {"title":"Kotlin River","snippet":"river in Russia"}
            ]}}""".trimIndent(),
        )
        assertEquals(2, results.size)
        assertEquals("Kotlin (programming language)", results[0].title)
        assertEquals("Kotlin is a language", results[0].description)
    }

    @Test
    fun parsesSummary() {
        val summary = WikipediaClient.parseSummary(
            """{"title":"Dhaka","description":"Capital of Bangladesh",
                "extract":"Dhaka is the capital city.",
                "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Dhaka"}}}""".trimIndent(),
            fallbackUrl = "fallback",
        )
        assertEquals("Dhaka", summary.title)
        assertEquals("https://en.wikipedia.org/wiki/Dhaka", summary.url)
    }

    @Test
    fun parsesLinksAndFullText() {
        val links = WikipediaClient.parseLinks(
            """{"query":{"pages":{"123":{"links":[{"ns":0,"title":"Bangladesh"},{"ns":0,"title":"Bengal"}]}}}}""",
        )
        assertEquals(listOf("Bangladesh", "Bengal"), links)
        val text = WikipediaClient.parseFullText(
            """{"query":{"pages":{"123":{"extract":"Full text here."}}}}""",
        )
        assertEquals("Full text here.", text)
    }

    @Test
    fun articleUrlEncodes() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Ada_Lovelace",
            WikipediaClient.articleUrl("Ada Lovelace", "en"),
        )
    }
}

class AiClientParseTest {

    @Test
    fun parsesAnthropic() {
        assertEquals(
            "Hello!",
            AiClient.parseAnthropic(
                """{"content":[{"type":"text","text":"Hello!"}],"model":"m"}""",
            ),
        )
    }

    @Test
    fun parsesOpenAi() {
        assertEquals(
            "Result",
            AiClient.parseOpenAi(
                """{"choices":[{"message":{"role":"assistant","content":"Result"}}]}""",
            ),
        )
    }

    @Test
    fun parsesGemini() {
        assertEquals(
            "AB",
            AiClient.parseGemini(
                """{"candidates":[{"content":{"parts":[{"text":"A"},{"text":"B"}]}}]}""",
            ),
        )
    }

    @Test
    fun parsesOllama() {
        assertEquals(
            "Hi",
            AiClient.parseOllama("""{"message":{"role":"assistant","content":"Hi"}}"""),
        )
    }
}

class QrToolTest {

    @Test
    fun symbolCatalogHasNoDuplicatesWithinCategory() {
        for (category in SymbolCatalog.categories) {
            assertEquals(
                // The id, not the label: the label is a string resource that a
                // JVM test cannot resolve, and the id is what names the group.
                "Duplicates in ${category.id}",
                category.symbols.size,
                category.symbols.distinct().size,
            )
        }
    }
}
