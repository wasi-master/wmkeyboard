package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import java.util.Locale

/**
 * Pre-loaded input handed to a tool panel when it is opened from a smart
 * chip, so "1 ft" lands on the converter with Length/ft→m and 1 already
 * filled in instead of on whatever the panel was last used for.
 */
sealed interface ToolPrefill {
    data class Calc(val expression: String) : ToolPrefill
    data class Units(
        val category: String,
        val from: String,
        val to: String,
        val value: String,
    ) : ToolPrefill

    data class Currency(val from: String, val to: String, val amount: String) : ToolPrefill

    /** The calendar opens showing (and selecting) this Gregorian day. */
    data class Calendar(val year: Int, val month: Int, val day: Int) : ToolPrefill

    /** A term the dictionary or Wikipedia panel looks up as it opens. */
    data class Lookup(val term: String) : ToolPrefill

    /** A search the GIF/sticker panel opens on. */
    data class Gif(val query: String) : ToolPrefill

    /** A vocabulary word whose card the panel opens on. */
    data class Vocab(val word: String) : ToolPrefill
}

/**
 * Recognises tool-shaped text as it is typed and offers the answer on the
 * suggestion strip: "12*4" → 48, "150 usd" → the amount in your currency,
 * "1 ft" → the same length in metres, "wiki" → open Wikipedia.
 *
 * Everything here is pure and synchronous — [detect] takes the text before
 * the cursor plus a [Context] snapshot and returns at most one [SmartHit].
 * The only thing it cannot compute on its own is a currency conversion with
 * no rates loaded, which comes back as [SmartHit.pending] so the caller can
 * kick off a fetch and show a placeholder chip meanwhile.
 */
object SmartSuggest {

    /**
     * What a hit is, which decides how the strip draws it. [CALC], [CURRENCY],
     * [UNIT], [NUMBER], [DATE] and [WEATHER] are answers and take the whole
     * strip; [TOOL] (a typed keyword), [LOOKUP] (a term to look up) and
     * [INTENT] (text that sounds like a job a tool does) stay narrow beside
     * the word candidates.
     */
    enum class Kind { CALC, CURRENCY, UNIT, NUMBER, DATE, WEATHER, TOOL, LOOKUP, INTENT, VOCAB }

    /** The kinds that only claim the space their label needs. */
    val narrowKinds: Set<Kind> = setOf(Kind.TOOL, Kind.LOOKUP, Kind.INTENT, Kind.VOCAB)

    /**
     * One way of drawing an answer chip, verbose first. The strip walks the
     * list until one fits its width: "1 thousand Dollar → 120,000.00 Taka",
     * then the result loses its ".00", then it rounds (a "~" marks a rounding
     * that actually moved the number — a tap still inserts the exact amount),
     * then the codes give way to symbols. Tiers marked [lastResort] trade the
     * amount as typed for plain digits ("1 thousand" → "1000") and are only
     * reached after the strip has also tried a smaller font.
     */
    data class ChipTier(
        val query: String,
        val result: String,
        val lastResort: Boolean = false,
    )

    /**
     * One recognised trigger. [replaceSpan] is how many characters before
     * the cursor the trigger occupies — tapping the chip deletes exactly
     * that many and types [insert] in their place. A trigger that ends in
     * "=" reports a span of 0 so the result appends after it.
     */
    data class SmartHit(
        val kind: Kind,
        /** What was recognised, shown on the left of the chip. */
        val query: String,
        /** The answer, or null while rates are still in flight. */
        val result: String?,
        /** Text a tap types, or null when the chip only opens a tool. */
        val insert: String?,
        val replaceSpan: Int,
        val tool: ToolbarTool,
        val prefill: ToolPrefill?,
        /** True while a currency conversion is waiting on exchange rates. */
        val pending: Boolean = false,
        /** True when what is missing is the coin table rather than the fiat one. */
        val pendingCrypto: Boolean = false,
        /** True while a weather chip is waiting on a forecast fetch. */
        val pendingWeather: Boolean = false,
        /** Width-tiered renderings of query → result; empty for keyword chips. */
        val tiers: List<ChipTier> = emptyList(),
    )

    /** Everything [detect] needs from settings and live tool state. */
    data class Context(
        val calcEnabled: Boolean = true,
        val currencyEnabled: Boolean = true,
        val unitsEnabled: Boolean = true,
        val keywordsEnabled: Boolean = true,
        val degrees: Boolean = true,
        val precision: Int = 8,
        val rates: CurrencyClient.Rates? = null,
        val currencyFrom: String = "USD",
        val currencyTo: String = "BDT",
        val currencyDecimals: Int = 2,
        val cryptoEnabled: Boolean = true,
        /** The coins that are on; empty means the catalogue defaults. */
        val cryptoTickers: Set<String> = emptySet(),
        /** Decimal places for coin amounts; 0 means significant digits instead. */
        val cryptoDecimals: Int = 0,
        /** Set while the coin table cannot be fetched, so a coin chip gives up rather than spins. */
        val cryptoUnavailable: Boolean = false,
        val unitLast: String = "",
        /**
         * Read a length in feet as feet and inches — "3 ft 3.37 in" rather
         * than "3.2808399 ft". See [CompoundUnits].
         */
        val compoundUnits: Boolean = true,
        val enabledTools: Collection<ToolbarTool> = emptyList(),
        val keywordOverrides: String = "",
        /** Tools whose keywords must match the typed capitals exactly. */
        val caseSensitiveKeywords: String = "",
        // ---- contextual chips ----
        val dateChips: Boolean = true,
        /** Today as a Julian Day Number; date chips stay off at the default. */
        val todayJdn: Long = -1,
        val weatherChips: Boolean = true,
        /** The last fetched conditions, or null when none are fresh. */
        val weather: WeatherInfo? = null,
        /** Whether a weather location is configured, so a fetch could succeed. */
        val weatherAvailable: Boolean = false,
        val weatherFahrenheit: Boolean = false,
        val lookupChips: Boolean = true,
        val intentChips: Boolean = true,
        val gifChips: Boolean = true,
        // ---- vocabulary nudges ----
        /** "hate" → abhor, when a plainer word with a stronger alternative is typed. */
        val vocabChips: Boolean = true,
        /** Also offer the card when a vocabulary word itself is typed. */
        val vocabSelfChips: Boolean = true,
        /** The loaded pack index, or null before the packs are read — like [rates]. */
        val vocab: VocabIndex? = null,
        val vocabScope: VocabNudgeScope = VocabNudgeScope.UNLEARNT,
        /** The Zipf gap a trigger needs, from the sensitivity setting. */
        val vocabMinGap: Double = 0.0,
        /** Whether the user has marked a lemma learnt; one hash lookup, nothing slower. */
        val vocabLearnt: (String) -> Boolean = { false },
        /** Typed words that already nudged in this field (or today), lowercase. */
        val vocabRetired: Set<String> = emptySet(),
        // ---- number grouping ----
        /** "1234567" → the same digits punctuated. */
        val numberChips: Boolean = true,
        val numberGrouping: NumberGrouping = NumberGrouping.AUTO,
        /**
         * The language being typed in, as a locale tag. Only read when
         * [numberGrouping] is [NumberGrouping.AUTO], which follows it.
         */
        val numberLocale: String = "",
        /**
         * The digit glyphs the keyboard is typing, when they are not Latin —
         * "১২৩৪৫৬৭" has to come back as "১২,৩৪,৫৬৭" and not as ASCII. Ten
         * glyphs for 0..9 in order, as the numeral systems store them.
         */
        val numberDigits: String? = null,
    )

    /** Characters of context the scanners look back over. */
    const val LOOKBEHIND = 48

    /**
     * The first trigger that matches the tail of [text], in priority order:
     * the answer chips first (currency, units, arithmetic — phrase forms
     * before symbol forms — dates, weather), then the narrow ones (lookups,
     * intents, tool keywords), and the ambient grammar hint dead last since
     * it matches no text at all. Currency and units both start with a number
     * so they can never both match; arithmetic is tried after them because
     * "12/04" style text should lose to a real unit or currency reading, and
     * a bare number is tried after all three because a number that means
     * something is worth more than a number that is merely long.
     */
    fun detect(text: String, ctx: Context): SmartHit? {
        if (text.isEmpty()) return null
        val tail = text.takeLast(LOOKBEHIND)
        if (ctx.currencyEnabled) detectCurrency(tail, ctx)?.let { return it }
        if (ctx.unitsEnabled) detectUnit(tail, ctx)?.let { return it }
        if (ctx.calcEnabled) detectCalcPhrase(tail, ctx)?.let { return it }
        if (ctx.calcEnabled) detectCalc(tail, ctx)?.let { return it }
        if (ctx.numberChips) detectNumber(tail, ctx)?.let { return it }
        if (ctx.dateChips) detectDate(tail, ctx)?.let { return it }
        if (ctx.weatherChips) detectWeather(tail, ctx)?.let { return it }
        if (ctx.lookupChips) detectLookup(tail, ctx)?.let { return it }
        if (ctx.intentChips) detectIntent(tail, ctx)?.let { return it }
        if (ctx.keywordsEnabled) detectKeyword(tail, ctx)?.let { return it }
        // Last: a nudge is advice, and every explicit trigger above outranks it.
        if (ctx.vocabChips) detectVocab(tail, ctx)?.let { return it }
        return null
    }

    private fun toolOn(tool: ToolbarTool, ctx: Context): Boolean = tool in ctx.enabledTools

    // ---- numbers ----

    /** "1500", "1,500", "1500.25" — grouping commas allowed, not required. */
    private const val NUM = """\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"""

    private fun parseNumber(raw: String): Double? = raw.replace(",", "").toDoubleOrNull()

    private fun money(value: Double, decimals: Int): String = MoneyFormat.fixed(value, decimals)

    // ---- amount phrases ----

    /**
     * What a number can be multiplied by when it is written short or spelled
     * out: "1.5k", "2 million", "3 crore". The single letters are only ever
     * read this way in front of a currency, where "5m usd" cannot be metres.
     */
    private val magnitudes: Map<String, Double> = mapOf(
        "k" to 1e3, "thousand" to 1e3, "thousands" to 1e3, "hajar" to 1e3,
        "m" to 1e6, "mn" to 1e6, "million" to 1e6, "millions" to 1e6,
        "b" to 1e9, "bn" to 1e9, "billion" to 1e9, "billions" to 1e9,
        "lakh" to 1e5, "lakhs" to 1e5, "lac" to 1e5, "lacs" to 1e5,
        "koti" to 1e7, "crore" to 1e7, "crores" to 1e7,
    )

    // Longest first, so "million" is never read as "m" with "illion" left over.
    private val MAG = magnitudes.keys.sortedByDescending { it.length }.joinToString("|")

    /**
     * A multiplier has to end where it ends: without this, "150 bdt" reads as
     * 150 billion of a currency called "dt".
     */
    private const val MAG_END = """(?![\p{L}])"""

    /** One number with an optional multiplier after it. */
    private val CHUNK = Regex("""($NUM)(?:\s?($MAG)$MAG_END)?""", RegexOption.IGNORE_CASE)

    /** A run of such numbers: "1500", "1.5k", "1 thousand 500". */
    private val AMOUNT = """(?:$NUM)(?:\s?(?:$MAG)$MAG_END)?""" +
        """(?:\s+(?:$NUM)(?:\s?(?:$MAG)$MAG_END)?)*"""

    /**
     * An amount phrase read back as a number, plus where in the phrase the
     * part that counts begins — text to the left of [start] is prose that
     * happened to end in a number.
     */
    private data class Amount(val value: Double, val text: String, val start: Int)

    /**
     * Reads [phrase] right to left. Only the last number may stand without a
     * multiplier, and each number further left has to name a bigger one, so
     * "1 thousand 500" is 1500 while "in 2020 500" keeps only the 500.
     */
    private fun parseAmount(phrase: String): Amount? {
        val chunks = CHUNK.findAll(phrase).toList()
        if (chunks.isEmpty()) return null
        var total = 0.0
        var lastScale = 0.0
        var start = phrase.length
        for (index in chunks.indices.reversed()) {
            val chunk = chunks[index]
            val word = chunk.groupValues[2]
            val scale = if (word.isEmpty()) 1.0 else magnitudes[word.lowercase(Locale.ROOT)] ?: break
            if (index != chunks.lastIndex && (word.isEmpty() || scale <= lastScale)) break
            val value = parseNumber(chunk.groupValues[1]) ?: break
            total += value * scale
            lastScale = scale
            start = chunk.range.first
        }
        if (start >= phrase.length) return null
        return Amount(total, phrase.substring(start).trim(), start)
    }

    // ---- currency ----

    private val CURRENCY_SUFFIX =
        Regex("""(?<![\w.,])($AMOUNT)\s?([\p{L}]{2,8}|[^\s\w])$""", RegexOption.IGNORE_CASE)
    private val CURRENCY_PREFIX =
        Regex("""(?<![\w])([\p{L}]{0,2}[^\s\w])\s?($AMOUNT)$""", RegexOption.IGNORE_CASE)

    /** How the trigger was typed, kept so the chip can echo it back. */
    private data class CurrencyMatch(
        val amount: Amount,
        val token: String,
        val span: Int,
        /** True when the token sat in front of the amount, "$150" style. */
        val tokenLeads: Boolean,
    )

    private fun detectCurrency(tail: String, ctx: Context): SmartHit? {
        val m = run {
            CURRENCY_SUFFIX.find(tail)?.let { match ->
                val parsed = parseAmount(match.groupValues[1]) ?: return null
                return@run CurrencyMatch(
                    parsed, match.groupValues[2], match.value.length - parsed.start, tokenLeads = false,
                )
            }
            CURRENCY_PREFIX.find(tail)?.let { match ->
                val parsed = parseAmount(match.groupValues[2]) ?: return null
                // The symbol sits in front of the amount, so dropping a
                // leading number would leave it stranded outside the span.
                if (parsed.start != 0) return null
                return@run CurrencyMatch(parsed, match.groupValues[1], match.value.length, tokenLeads = true)
            }
            return null
        }
        val (from, scale) = resolveCurrency(m.token, ctx) ?: return null
        // "100000 sats" is a thousandth of a bitcoin: the token carries its
        // own scale, and everything downstream works in whole coins.
        val amount = m.amount.value * scale
        val to = targetCurrency(from, ctx) ?: return null
        val fromCrypto = isCryptoCode(from, ctx)
        val toCrypto = isCryptoCode(to, ctx)

        // "100000 sats" must not read back as "100000 BTC", so a token that
        // carries a scale of its own keeps its spelling on the chip.
        val fromLabel = if (scale != 1.0) m.token else from
        val query = "${m.amount.text} $fromLabel"
        val prefill = ToolPrefill.Currency(from, to, prefillAmount(amount, fromCrypto))
        fun pendingHit(onCoins: Boolean) = SmartHit(
            kind = Kind.CURRENCY, query = query, result = null, insert = null,
            replaceSpan = m.span, tool = ToolbarTool.CURRENCY, prefill = prefill,
            pending = true, pendingCrypto = onCoins,
        )

        val rates = ctx.rates ?: return pendingHit(fromCrypto || toCrypto)
        // A coin the table does not carry yet is worth waiting for — the
        // fiat rates arriving first must not make the chip disappear. A coin
        // source that is down gives up instead, so the chip never spins for
        // good.
        val missing = listOf(from, to).filterNot { rates.rates.containsKey(it) }
        if (missing.isNotEmpty()) {
            val coinsMissing = missing.any { isCryptoCode(it, ctx) }
            return if (coinsMissing && !ctx.cryptoUnavailable) pendingHit(true) else null
        }
        val converted = CurrencyClient.convert(amount, from, to, rates) ?: return null
        // The result side is the user's local currency: show and insert its
        // name ("Taka") rather than the ISO code ("BDT"). Coins keep their
        // ticker, since their names shorten badly.
        val resultName = if (toCrypto) to else CurrencyClient.unitName(to)
        val result = "${cryptoAware(converted, toCrypto, ctx)} $resultName"
        return SmartHit(
            kind = Kind.CURRENCY, query = query, result = result, insert = result,
            replaceSpan = m.span, tool = ToolbarTool.CURRENCY, prefill = prefill,
            tiers = if (toCrypto) {
                cryptoTiers(m, fromLabel, to, converted, ctx)
            } else {
                currencyTiers(m, from, to, converted, ctx.currencyDecimals)
            },
        )
    }

    /**
     * What an amount converts into: the saved pair's other side, unless that
     * code has left the rate table — turning coins off or dropping a ticker
     * would otherwise leave [Context.currencyTo] pointing at nothing and
     * take every currency chip down with it.
     */
    private fun targetCurrency(from: String, ctx: Context): String? {
        val saved = listOf(
            if (from == ctx.currencyTo) ctx.currencyFrom else ctx.currencyTo,
            if (from == ctx.currencyTo) ctx.currencyTo else ctx.currencyFrom,
        )
        saved.firstOrNull { it != from && inTable(it, ctx) }?.let { return it }
        return CurrencyClient.popular.firstOrNull { it != from && inTable(it, ctx) }
    }

    /** With no rates yet every code is still a candidate; the chip pends either way. */
    private fun inTable(code: String, ctx: Context): Boolean =
        ctx.rates?.rates?.containsKey(code) ?: true

    private fun isCryptoCode(code: String, ctx: Context): Boolean =
        ctx.rates?.isCrypto(code) == true ||
            (ctx.cryptoEnabled && code in CryptoCatalog.enabled(ctx.cryptoTickers))

    private fun cryptoAware(value: Double, isCrypto: Boolean, ctx: Context): String =
        MoneyFormat.amount(value, isCrypto, ctx.currencyDecimals, ctx.cryptoDecimals)

    /**
     * The amount the panel opens with. It goes into a text field that reads
     * plain digits back, so it stays ungrouped; four decimals would round a
     * fraction of a coin down to "0", so coins get room for twelve.
     */
    private fun prefillAmount(value: Double, isCrypto: Boolean): String =
        CalcEngine.format(value, if (isCrypto) 12 else 4)

    /**
     * The display ladder for a currency chip. The widest tier echoes the
     * trigger the way it was typed — "150 dollar" stays "150 Dollar", not
     * "150 USD" — and the result carries full decimals and the currency's
     * name. Each step down gives something up: the ".00", exactness (the "~"
     * tiers round to whole units), the name (code, then symbol). The
     * [ChipTier.lastResort] tiers also flatten a spelled-out amount
     * ("1 thousand") into digits, which the strip only shows once a smaller
     * font has failed too.
     */
    private fun currencyTiers(
        m: CurrencyMatch,
        from: String,
        to: String,
        converted: Double,
        decimals: Int,
    ): List<ChipTier> {
        val name = CurrencyClient.unitName(to)
        val full = money(converted, decimals)
        val rounded = kotlin.math.round(converted)
        // "~" only when the rounding visibly moved the number: nobody calls
        // 18,300.55 → 18,301 an approximation, but 1.25 → 1 is one.
        val tilde = if (kotlin.math.abs(converted - rounded) > 0.005 * kotlin.math.abs(converted)) "~" else ""
        val roundText = "$tilde${money(rounded, 0)}"
        val symbolResult = symbolFor(to)?.let { "$tilde$it${money(rounded, 0)}" } ?: "$roundText $to"

        val word = m.token.takeIf { it.lowercase(Locale.ROOT) in currencyWords }
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val qTyped = when {
            // "150 dollar" reads back as "150 Dollar" — the word the user
            // wrote, not the code it resolved to.
            word != null -> "${m.amount.text} $word"
            m.token.none(Char::isLetter) ->
                if (m.tokenLeads) "${m.token}${m.amount.text}" else "${m.amount.text}${m.token}"
            else -> "${m.amount.text} $from"
        }
        val qCode = "${m.amount.text} $from"
        val digits = CalcEngine.format(m.amount.value, 4)
        val qDigits = "$digits $from"
        val qCompact = symbolFor(from)?.let { "$it$digits" } ?: qDigits
        return buildList {
            add(ChipTier(qTyped, "$full $name"))
            if (decimals > 0 && full.endsWith("." + "0".repeat(decimals))) {
                add(ChipTier(qTyped, "${money(converted, 0)} $name"))
            }
            add(ChipTier(qTyped, "$roundText $name"))
            add(ChipTier(qCode, "$roundText $name"))
            add(ChipTier(qCode, symbolResult))
            add(ChipTier(qDigits, symbolResult, lastResort = true))
            add(ChipTier(qCompact, symbolResult, lastResort = true))
        }.distinct()
    }


    /**
     * The display ladder for a coin result. Rounding is useless here — a
     * whole number of bitcoin is almost always zero — so each step drops a
     * significant digit instead, and the ticker stays put because it is
     * already as short as the name can get.
     */
    private fun cryptoTiers(
        m: CurrencyMatch,
        fromLabel: String,
        to: String,
        converted: Double,
        ctx: Context,
    ): List<ChipTier> {
        val qTyped = when {
            m.token.none(Char::isLetter) ->
                if (m.tokenLeads) "${m.token}${m.amount.text}" else "${m.amount.text}${m.token}"
            else -> "${m.amount.text} ${m.token}"
        }
        val qCode = "${m.amount.text} $fromLabel"
        val qDigits = "${CalcEngine.format(m.amount.value, 4)} $fromLabel"
        val amounts = if (ctx.cryptoDecimals > 0) {
            listOf(MoneyFormat.fixed(converted, ctx.cryptoDecimals))
        } else {
            listOf(5, 4, 3).map { MoneyFormat.significant(converted, sig = it) }
        }
        return buildList {
            for (text in amounts) add(ChipTier(qTyped, "$text $to"))
            add(ChipTier(qCode, "${amounts.last()} $to"))
            add(ChipTier(qDigits, "${amounts.last()} $to", lastResort = true))
        }.distinct()
    }

    /**
     * A currency token → the code it means and the scale one unit of it
     * carries (1, or a hundred-millionth for "sats"). Symbols and
     * spelled-out names always match; bare three-letter codes match the
     * well-known list in any case, and any other live code only when typed
     * in capitals, so "150 all" stays English text while "150 ALL" is
     * Albanian lek. Coins go through the same rule in [CryptoCatalog].
     */
    private fun resolveCurrency(token: String, ctx: Context): Pair<String, Double>? {
        if (token.isEmpty()) return null
        val rates = ctx.rates
        currencySymbols[token]?.let { return it to 1.0 }
        val lower = token.lowercase(Locale.ROOT)
        currencyWords[lower]?.let { return it to 1.0 }
        if (ctx.cryptoEnabled) {
            CryptoCatalog.resolve(token, ctx.cryptoTickers)?.let { return it }
        }
        val upper = token.uppercase(Locale.ROOT)
        if (upper.length != 3) return null
        if (upper in CurrencyClient.names || upper in CurrencyClient.popular) {
            // "try" reads as the verb far more often than Turkish lira.
            if (lower == "try" && token != "TRY") return null
            return upper to 1.0
        }
        if (token == upper && rates?.rates?.containsKey(upper) == true) return upper to 1.0
        return null
    }

    private val currencySymbols: Map<String, String> = mapOf(
        "$" to "USD", "US$" to "USD", "C$" to "CAD", "A$" to "AUD", "S$" to "SGD",
        "NZ$" to "NZD", "R$" to "BRL", "HK$" to "HKD", "NT$" to "TWD",
        "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR", "₨" to "PKR",
        "৳" to "BDT", "₽" to "RUB", "₩" to "KRW", "₺" to "TRY", "₦" to "NGN",
        "₱" to "PHP", "฿" to "THB", "₫" to "VND", "₪" to "ILS", "₴" to "UAH",
        "₸" to "KZT", "₭" to "LAK", "₮" to "MNT", "₡" to "CRC", "﷼" to "SAR", "₿" to "BTC",
        "₾" to "GEL", "₼" to "AZN", "៛" to "KHR", "₲" to "PYG", "₵" to "GHS",
        "zł" to "PLN", "Kč" to "CZK",
    )

    /**
     * The display symbol for a code — the reverse of [currencySymbols],
     * first spelling wins so USD is "$" and not "US$".
     */
    private val symbolByCode: Map<String, String> = buildMap {
        currencySymbols.forEach { (symbol, code) -> putIfAbsent(code, symbol) }
    }

    private fun symbolFor(code: String): String? = symbolByCode[code]

    // "pound(s)" is deliberately absent: it reads as mass far more often
    // than as sterling, and £/GBP/quid cover the currency.
    private val currencyWords: Map<String, String> = mapOf(
        "dollar" to "USD", "dollars" to "USD", "buck" to "USD", "bucks" to "USD",
        "euro" to "EUR", "euros" to "EUR",
        "quid" to "GBP", "sterling" to "GBP",
        "tk" to "BDT", "taka" to "BDT", "takas" to "BDT",
        "rupee" to "INR", "rupees" to "INR",
        "yen" to "JPY", "yuan" to "CNY", "rmb" to "CNY",
        "ringgit" to "MYR", "riyal" to "SAR", "riyals" to "SAR",
        "dirham" to "AED", "dirhams" to "AED", "dinar" to "KWD",
        "peso" to "MXN", "pesos" to "MXN", "rand" to "ZAR",
        "lira" to "TRY", "ruble" to "RUB", "rubles" to "RUB", "rouble" to "RUB",
        "franc" to "CHF", "francs" to "CHF",
        "krona" to "SEK", "kronor" to "SEK", "krone" to "NOK", "kroner" to "NOK",
        "baht" to "THB", "rupiah" to "IDR", "naira" to "NGN",
        "shekel" to "ILS", "shekels" to "ILS", "zloty" to "PLN",
        "koruna" to "CZK", "forint" to "HUF", "dong" to "VND", "won" to "KRW",
    )

    // ---- units ----

    /**
     * The magnitudes a unit amount may spell out — the words only. The
     * single-letter shorthands stay currency-territory: "5m" is five metres
     * and "1.5k" could be kelvin, but "1 thousand miles" is unambiguous.
     */
    private val MAG_WORDS = magnitudes.keys.filter { it.length >= 2 }
        .sortedByDescending { it.length }.joinToString("|")

    /** "1500", "1.5", "2 lakh", "1 thousand 500" — in front of a unit. */
    private val UNIT_AMOUNT = """(?:$NUM)(?:\s?(?i:$MAG_WORDS)$MAG_END)?""" +
        """(?:\s+(?:$NUM)(?:\s?(?i:$MAG_WORDS)$MAG_END)?)*"""

    private val UNIT_TAIL = Regex("""(?<![\w.,])($UNIT_AMOUNT)(\s?)([\p{L}°²³/]{1,9})$""")

    private fun detectUnit(tail: String, ctx: Context): SmartHit? {
        val match = UNIT_TAIL.find(tail) ?: return null
        val amount = parseAmount(match.groupValues[1]) ?: return null
        val spaced = match.groupValues[2].isNotEmpty()
        val token = match.groupValues[3]
        val from = resolveUnit(token, spaced) ?: return null
        val category = UnitConvert.categories.first { cat -> cat.units.any { it.symbol == from.symbol } }
        val to = targetUnit(category, from, ctx.unitLast) ?: return null

        val converted = UnitConvert.convert(amount.value, from, to)
        if (converted.isNaN() || converted.isInfinite()) return null
        val digits = CalcEngine.format(amount.value, ctx.precision)
        val resultValue = CalcEngine.format(converted, ctx.precision)
        // A unit the user spelled out is answered in words ("1 meter" →
        // "3 feet 3.37 inches"); one written as a symbol gets symbols back.
        val style = if (token.lowercase(Locale.ROOT) == from.symbol.lowercase(Locale.ROOT)) {
            CompoundUnits.Style.SYMBOL
        } else {
            CompoundUnits.Style.WORD
        }
        val compound = compoundResult(converted, to.symbol, style, ctx)
        val result = compound ?: "$resultValue ${to.symbol}"
        return SmartHit(
            kind = Kind.UNIT,
            query = "$digits ${from.symbol}",
            result = result,
            insert = result,
            // Like currency, prose to the left of the amount that happened to
            // end in a number ("in 2020 500 miles") stays outside the span.
            replaceSpan = match.value.length - amount.start,
            tool = ToolbarTool.UNIT_CONVERT,
            prefill = ToolPrefill.Units(
                category = category.id,
                from = from.symbol,
                to = to.symbol,
                value = digits,
            ),
            tiers = unitTiers(amount, token, spaced, digits, from, to, result, converted, style, ctx),
        )
    }

    /**
     * How many decimals a compound minor gets. Hundredths of an inch is
     * already past what a measurement means, so the setting only ever takes
     * the number down.
     */
    private const val COMPOUND_MINOR_DECIMALS = 2

    /**
     * The compound reading of a result, or null when it has none — the unit
     * does not take one, or the user turned them off.
     */
    private fun compoundResult(
        converted: Double,
        toSymbol: String,
        style: CompoundUnits.Style,
        ctx: Context,
        decimals: Int = ctx.precision.coerceAtMost(COMPOUND_MINOR_DECIMALS),
    ): String? =
        if (ctx.compoundUnits) CompoundUnits.format(converted, toSymbol, style, decimals) else null

    /**
     * The display ladder for a unit chip, mirroring [currencyTiers]: the
     * amount and unit as typed with the full result first, then the result
     * rounds to two decimals and then to whole units ("~" when a rounding
     * visibly moved the number), then a spelled-out unit shrinks to its
     * symbol ("1 thousand miles" → "1 thousand mi"), and as a last resort
     * the amount flattens to digits — "1000 mi".
     */
    private fun unitTiers(
        amount: Amount,
        token: String,
        spaced: Boolean,
        digits: String,
        from: UnitConvert.ConvUnit,
        to: UnitConvert.ConvUnit,
        fullResult: String,
        converted: Double,
        style: CompoundUnits.Style,
        ctx: Context,
    ): List<ChipTier> {
        /** "~" once a rounding has visibly moved the number. */
        fun tilde(shown: Double): String =
            if (kotlin.math.abs(converted - shown) > 0.005 * kotlin.math.abs(converted)) "~" else ""
        val qTyped = "${amount.text}${if (spaced) " " else ""}$token"
        // Swapping the typed unit for its symbol only helps when the symbol
        // is actually shorter — "miles" → "mi", but never "c" → "°C".
        val qMid = if (token.length > from.symbol.length) "${amount.text} ${from.symbol}" else qTyped
        val qCanon = "$digits ${from.symbol}"
        // The digits tier exists to flatten spelled-out amounts; a glued
        // short form like "30c" is already narrower than "30 °C".
        val digitsTierFits = qCanon != qMid && qCanon.length < qMid.length

        val minorDecimals = ctx.precision.coerceAtMost(COMPOUND_MINOR_DECIMALS)
        fun compound(form: CompoundUnits.Style, decimals: Int): String? =
            compoundResult(converted, to.symbol, form, ctx, decimals)
                ?.let { tilde(CompoundUnits.snap(converted, to.symbol, decimals)) + it }

        // A compound result gives up its two halves in a different order than
        // a plain one: the spelled-out names go first, then the hundredths of
        // the minor, and only then the names give way to ' and ", which say
        // feet and inches to everyone and cost four characters less.
        compound(style, minorDecimals)?.let { full ->
            val symbols = compound(CompoundUnits.Style.SYMBOL, minorDecimals)
            val whole = compound(CompoundUnits.Style.SYMBOL, 0)
            val prime = compound(CompoundUnits.Style.PRIME, 0)
            return buildList {
                add(ChipTier(qTyped, full))
                symbols?.let { add(ChipTier(qTyped, it)) }
                whole?.let { add(ChipTier(qTyped, it)) }
                whole?.let { add(ChipTier(qMid, it)) }
                prime?.let { add(ChipTier(qMid, it)) }
                if (digitsTierFits) prime?.let { add(ChipTier(qCanon, it, lastResort = true)) }
            }.distinct()
        }

        fun rounded(value: Double): String =
            "${tilde(value)}${CalcEngine.format(value, 2)} ${to.symbol}"
        val twoText = rounded(kotlin.math.round(converted * 100) / 100)
        val wholeText = rounded(kotlin.math.round(converted))
        return buildList {
            add(ChipTier(qTyped, fullResult))
            add(ChipTier(qTyped, twoText))
            add(ChipTier(qTyped, wholeText))
            add(ChipTier(qMid, wholeText))
            if (digitsTierFits) add(ChipTier(qCanon, wholeText, lastResort = true))
        }.distinct()
    }

    /**
     * Where a recognised unit converts to by default: whatever the user last
     * paired it with in the converter, else the counterpart in [unitPartner],
     * else the category's first other unit.
     */
    private fun targetUnit(
        category: UnitConvert.Category,
        from: UnitConvert.ConvUnit,
        unitLast: String,
    ): UnitConvert.ConvUnit? {
        val saved = unitLast.split(';').firstOrNull { entry ->
            val parts = entry.split('|')
            parts.size == 3 && parts[0] == category.id && parts[1] == from.symbol
        }?.split('|')?.getOrNull(2)
        saved?.let { symbol ->
            category.units.firstOrNull { it.symbol == symbol && it.symbol != from.symbol }
                ?.let { return it }
        }
        unitPartner[from.symbol]?.let { symbol ->
            category.units.firstOrNull { it.symbol == symbol }?.let { return it }
        }
        return category.units.firstOrNull { it.symbol != from.symbol }
    }

    /**
     * A typed unit token → catalog unit. [spaced] is false for the glued
     * "30c" form, which is the only place the one-letter temperature and
     * mass abbreviations are safe — "30 c" is far more likely to be prose.
     */
    private fun resolveUnit(token: String, spaced: Boolean): UnitConvert.ConvUnit? {
        val exact = unitAliases[token]
        val lower = token.lowercase(Locale.ROOT)
        val symbol = exact ?: unitAliases[lower] ?: return null
        if (spaced && lower in spacedUnitBlocklist) return null
        return UnitConvert.categories.firstNotNullOfOrNull { cat ->
            cat.units.firstOrNull { it.symbol == symbol }
        }
    }

    /**
     * Tokens that only count as units when glued to the number. Each is a
     * common English word or an abbreviation short enough to appear mid
     * sentence, and a chip firing on "5 in the box" is worse than missing
     * "5 in" as inches.
     */
    private val spacedUnitBlocklist = setOf(
        "in", "s", "d", "h", "t", "b", "c", "f", "a", "w", "j", "k", "l",
        "pt", "st", "at", "ct", "gon", "bar", "cup", "ton", "turn", "mo", "bit",
    )

    /**
     * Every spelling that maps onto a [UnitConvert] symbol. Keys are matched
     * case-sensitively first (so "MB" beats "mb" → millibar were that ever
     * added) and then lowercased, which is why the odd-cased entries below
     * carry both forms.
     */
    private val unitAliases: Map<String, String> = buildMap {
        fun alias(symbol: String, vararg names: String) {
            put(symbol, symbol)
            names.forEach { put(it, symbol) }
        }
        // Length
        alias("mm", "millimetre", "millimetres", "millimeter", "millimeters")
        alias("cm", "centimetre", "centimetres", "centimeter", "centimeters")
        alias("m", "metre", "metres", "meter", "meters")
        alias("km", "kms", "kilometre", "kilometres", "kilometer", "kilometers")
        alias("in", "inch", "inches")
        alias("ft", "foot", "feet")
        alias("yd", "yds", "yard", "yards")
        alias("mi", "mile", "miles")
        alias("nmi", "nauticalmile", "nauticalmiles")
        alias("µm", "um", "micrometre", "micrometres", "micron", "microns")
        alias("nm", "nanometre", "nanometres", "nanometer", "nanometers")
        alias("ly", "lightyear", "lightyears")
        // Mass
        alias("mg", "milligram", "milligrams")
        alias("g", "gm", "gram", "grams", "gramme", "grammes")
        alias("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        alias("t", "tonne", "tonnes")
        alias("oz", "ounce", "ounces")
        alias("lb", "lbs", "pound", "pounds")
        alias("st", "stone", "stones")
        alias("ton", "tons")
        alias("ct", "carat", "carats")
        // Temperature
        alias("°C", "c", "celsius", "centigrade", "°c")
        alias("°F", "f", "fahrenheit", "°f")
        alias("K", "kelvin")
        // Area
        alias("mm²", "mm2", "sqmm")
        alias("cm²", "cm2", "sqcm")
        alias("m²", "m2", "sqm")
        alias("ha", "hectare", "hectares")
        alias("km²", "km2", "sqkm")
        alias("in²", "in2", "sqin")
        alias("ft²", "ft2", "sqft")
        alias("yd²", "yd2", "sqyd")
        alias("ac", "acre", "acres")
        alias("mi²", "mi2", "sqmi")
        alias("katha", "kathas")
        alias("bigha", "bighas")
        // Volume
        alias("mL", "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        alias("L", "l", "litre", "litres", "liter", "liters")
        alias("m³", "m3")
        alias("tsp", "teaspoon", "teaspoons")
        alias("tbsp", "tablespoon", "tablespoons")
        alias("fl oz", "floz", "fluidounce", "fluidounces")
        alias("cup", "cups")
        alias("pt", "pint", "pints")
        alias("qt", "quart", "quarts")
        alias("gal", "gallon", "gallons")
        alias("in³", "in3")
        alias("ft³", "ft3")
        // Speed
        alias("m/s", "mps")
        alias("km/h", "kmh", "kph", "kmph")
        alias("mph")
        alias("kn", "knot", "knots")
        alias("ft/s", "fps")
        alias("Mach", "mach")
        // Time
        alias("ms", "millisecond", "milliseconds")
        alias("s", "sec", "secs", "second", "seconds")
        alias("min", "mins", "minute", "minutes")
        alias("h", "hr", "hrs", "hour", "hours")
        alias("d", "day", "days")
        alias("wk", "wks", "week", "weeks")
        alias("mo", "month", "months")
        alias("yr", "yrs", "year", "years")
        // Data
        alias("bit", "bits")
        alias("B", "byte", "bytes")
        alias("kB", "kb", "kilobyte", "kilobytes")
        alias("MB", "mb", "megabyte", "megabytes")
        alias("GB", "gb", "gigabyte", "gigabytes")
        alias("TB", "tb", "terabyte", "terabytes")
        alias("KiB", "kib")
        alias("MiB", "mib")
        alias("GiB", "gib")
        alias("TiB", "tib")
        // Energy
        alias("J", "j", "joule", "joules")
        alias("kJ", "kj", "kilojoule", "kilojoules")
        alias("cal", "calorie", "calories")
        alias("kcal", "kilocalorie", "kilocalories")
        alias("Wh", "wh")
        alias("kWh", "kwh")
        alias("BTU", "btu")
        alias("eV", "ev")
        // Power
        alias("W", "w", "watt", "watts")
        alias("kW", "kw", "kilowatt", "kilowatts")
        alias("MW", "mw", "megawatt", "megawatts")
        alias("hp", "horsepower")
        alias("PS")
        // Pressure
        alias("Pa", "pa", "pascal", "pascals")
        alias("kPa", "kpa")
        alias("bar", "bars")
        alias("atm", "atmosphere", "atmospheres")
        alias("psi")
        alias("mmHg", "mmhg", "torr")
        // Angle
        alias("°", "deg", "degree", "degrees")
        alias("rad", "radian", "radians")
        alias("gon", "gradian", "gradians")
        alias("turn", "turns")
        // Frequency
        alias("Hz", "hz", "hertz")
        alias("kHz", "khz")
        alias("MHz", "mhz")
        alias("GHz", "ghz")
        alias("rpm")
        // Fuel economy
        alias("L/100km", "l/100km")
        alias("km/L", "kmpl", "km/l")
        alias("mpg")
    }

    /** The unit each recognised unit converts into unless told otherwise. */
    private val unitPartner: Map<String, String> = mapOf(
        "mm" to "in", "cm" to "in", "m" to "ft", "km" to "mi",
        "in" to "cm", "ft" to "m", "yd" to "m", "mi" to "km", "nmi" to "km",
        "mg" to "g", "g" to "oz", "kg" to "lb", "t" to "kg",
        "oz" to "g", "lb" to "kg", "st" to "kg", "ton" to "t", "ct" to "g",
        "°C" to "°F", "°F" to "°C", "K" to "°C",
        "m²" to "ft²", "ft²" to "m²", "ha" to "ac", "ac" to "ha",
        "km²" to "mi²", "mi²" to "km²", "katha" to "m²", "bigha" to "ac",
        "mL" to "fl oz", "L" to "gal", "gal" to "L", "fl oz" to "mL",
        "cup" to "mL", "tsp" to "mL", "tbsp" to "mL", "pt" to "L", "qt" to "L",
        "m/s" to "km/h", "km/h" to "mph", "mph" to "km/h", "kn" to "km/h",
        "ft/s" to "m/s", "Mach" to "km/h",
        "ms" to "s", "s" to "min", "min" to "h", "h" to "min",
        "d" to "h", "wk" to "d", "mo" to "d", "yr" to "d",
        "bit" to "B", "B" to "KiB", "kB" to "MB", "MB" to "GB", "GB" to "MB",
        "TB" to "GB", "KiB" to "kB", "MiB" to "MB", "GiB" to "GB", "TiB" to "TB",
        "J" to "cal", "kJ" to "kcal", "cal" to "J", "kcal" to "kJ",
        "Wh" to "kWh", "kWh" to "Wh", "BTU" to "kJ", "eV" to "J",
        "W" to "hp", "kW" to "hp", "MW" to "kW", "hp" to "kW", "PS" to "kW",
        "Pa" to "psi", "kPa" to "psi", "bar" to "psi", "atm" to "bar",
        "psi" to "bar", "mmHg" to "kPa",
        "°" to "rad", "rad" to "°", "gon" to "°", "turn" to "°",
        "Hz" to "kHz", "kHz" to "MHz", "MHz" to "GHz", "GHz" to "MHz", "rpm" to "Hz",
        "L/100km" to "mpg", "km/L" to "mpg", "mpg" to "L/100km", "mpg UK" to "L/100km",
    )

    // ---- arithmetic ----

    private val CALC_TAIL = Regex("""(?<![\w.])([\d.,()+\-*/^%×÷−√πePpCc ]{2,40}?)(=?)$""")
    private const val CALC_OPERATORS = "+-*/^%×÷−√"
    /** nPr and nCr, the only letters the strip reads as operators. */
    private const val CHOOSE_OPS = "pPcC"
    /** A written date: "12/04/2025", "2025-01-02", "3.4.26". */
    private val DATE_LIKE = Regex("""^\d{1,4}([/.\-])\d{1,2}\1\d{1,4}$""")
    /** "12/04" — a zero-padded pair is a date, not a division. */
    private val PADDED_PAIR = Regex("""^0\d+/\d+$|^\d+/0\d+$""")

    private fun detectCalc(tail: String, ctx: Context): SmartHit? {
        val match = CALC_TAIL.find(tail) ?: return null
        val explicit = match.groupValues[2] == "="
        val raw = match.groupValues[1]
        val expression = raw.trim()
        // Three characters is the shortest real sum ("1+1"); a root is the
        // one shape that says what it is in two ("√9").
        if (expression.length < 3 && '√' !in expression) return null
        if (!looksLikeArithmetic(expression, explicit)) return null

        val value = runCatching { CalcEngine.evaluate(expression, ctx.degrees) }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val result = CalcEngine.format(value, ctx.precision)
        // "5" evaluating to "5" is not an answer worth a chip.
        if (result == expression.replace(" ", "")) return null

        // The narrow tier keeps two decimals; "~" says it is a preview and
        // the tap still inserts the full-precision answer.
        val short = CalcEngine.format(value, 2)
        val tiers = buildList {
            add(ChipTier(expression, result))
            if (short != result) add(ChipTier(expression, "~$short"))
        }

        return SmartHit(
            kind = Kind.CALC,
            query = expression,
            result = result,
            insert = result,
            // A typed "=" is the user asking for the answer to follow it,
            // so the chip appends instead of swallowing what they wrote.
            // Otherwise the span covers the expression *as typed* — leading
            // whitespace is not part of it, trailing whitespace is, or
            // "12*4 " would delete four characters ending in the space and
            // leave the 1 behind.
            replaceSpan = if (explicit) 0 else raw.trimStart().length,
            tool = ToolbarTool.CALCULATOR,
            prefill = ToolPrefill.Calc(expression),
            tiers = tiers,
        )
    }

    // ---- plain numbers ----

    /**
     * Shorter than this and there is nothing to offer: four digits is far more
     * often a year than an amount, and three digits group to themselves.
     */
    private const val MIN_GROUPED = 5

    /**
     * Longer than this the digits have stopped being a quantity — card
     * numbers, account numbers and IMEIs run past anything anyone types as a
     * number they mean to read.
     */
    private const val MAX_GROUPED = 15

    /**
     * What may sit immediately left of the run, besides a space or the start
     * of the field. Anything else — a letter, ".", "+", "-", "/", ":", "#",
     * or a separator from a number that is already grouped — means the digits
     * belong to a phone number, a date, a version, a ratio or an identifier,
     * none of which wants punctuation.
     */
    private const val NUMBER_OPENERS = "([{\"'“‘"

    /** The value of [c] as a digit, ASCII or in [glyphs], or -1 for anything else. */
    private fun digitValue(c: Char, glyphs: String?): Int = when {
        c in '0'..'9' -> c - '0'
        glyphs != null -> glyphs.indexOf(c)
        else -> -1
    }

    /** ASCII digits in [text] rewritten as [glyphs]; everything else survives. */
    private fun mapToGlyphs(text: String, glyphs: String): String =
        buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') glyphs[c - '0'] else c)
        }

    /**
     * A long run of bare digits, offered back grouped: "1234567" → "1,234,567",
     * or "12,34,567" where people count in lakh and crore (see [NumberGroups]).
     *
     * Only the digits at the cursor count, so the chip follows the number as it
     * is typed; a decimal point that has already been typed carries its
     * fraction through untouched. The guards are all about *not* firing —
     * a keyboard that offers to punctuate phone numbers is worse than one that
     * offers nothing.
     */
    private fun detectNumber(tail: String, ctx: Context): SmartHit? {
        val glyphs = ctx.numberDigits
        fun digitsLeftOf(index: Int): Int {
            var i = index
            while (i > 0 && digitValue(tail[i - 1], glyphs) >= 0) i--
            return i
        }

        val trailing = digitsLeftOf(tail.length)
        // "1234567.89": group the integer half, keep the rest as typed. The
        // point needs a digit on both sides, or "3.14159" would read as a
        // fraction of a three.
        val decimal = trailing in 2 until tail.length && tail[trailing - 1] == '.'
        val fraction = if (decimal) tail.substring(trailing) else ""
        val end = if (decimal) trailing - 1 else tail.length
        val start = if (decimal) digitsLeftOf(end) else trailing
        val run = tail.substring(start, end)
        if (run.length !in MIN_GROUPED..MAX_GROUPED) return null
        // A leading zero marks an identifier — a phone number, an account, a
        // PIN — and never an amount.
        if (digitValue(run[0], glyphs) == 0) return null
        val opener = tail.getOrNull(start - 1)
        if (opener != null && !opener.isWhitespace() && opener !in NUMBER_OPENERS) return null

        val ascii = buildString(run.length) { for (c in run) append('0' + digitValue(c, glyphs)) }
        val style = NumberGroups.styleFor(ctx.numberGrouping, ctx.numberLocale)
        val grouped = NumberGroups.group(ascii, style)
        if (grouped == ascii) return null

        val asciiFraction = if (decimal) {
            "." + buildString(fraction.length) {
                for (c in fraction) append('0' + digitValue(c, glyphs))
            }
        } else {
            ""
        }
        val typedTail = if (decimal) ".$fraction" else ""
        // The answer comes back in the digits that were *typed*, not in the
        // ones the language would draw: the same keyboard commits ASCII in a
        // numeric field and Bangla digits in a message.
        val shown = glyphs
            ?.takeIf { run[0] !in '0'..'9' }
            ?.let { mapToGlyphs(grouped, it) }
            ?: grouped
        val result = shown + typedTail
        val query = run + typedTail
        return SmartHit(
            kind = Kind.NUMBER,
            query = query,
            result = result,
            insert = result,
            replaceSpan = query.length,
            tool = ToolbarTool.CALCULATOR,
            prefill = ToolPrefill.Calc(ascii + asciiFraction),
        )
    }

    /**
     * Filters the many number-and-punctuation runs that are not sums.
     * A trailing "=" is taken as the user explicitly asking, and skips the
     * ambiguity checks below.
     */
    private fun looksLikeArithmetic(expression: String, explicit: Boolean): Boolean {
        val compact = expression.replace(" ", "")
        if (compact.isEmpty()) return false
        if (!compact.last().let { it.isDigit() || it == ')' || it == '%' || it == 'π' || it == 'e' }) {
            return false
        }
        if (!compact.first().let { it.isDigit() || it == '(' || it == '-' || it == '√' || it == 'π' }) {
            return false
        }
        // nPr and nCr only count glued between two digits — "5p3". Spread
        // out ("page 5 c 2") or up against anything else it is prose that
        // happens to hold a number, so the whole reading is dropped.
        if (expression.any { it in CHOOSE_OPS }) {
            val glued = expression.indices.filter { expression[it] in CHOOSE_OPS }.all { index ->
                index > 0 && expression[index - 1].isDigit() &&
                    expression.getOrNull(index + 1)?.isDigit() == true
            }
            if (!glued) return false
        }

        // A sign at the very front is part of the number, not a sum — but a
        // leading root is the whole point of "√9".
        val operators = compact.filterIndexed { index, c ->
            c in CHOOSE_OPS || c in CALC_OPERATORS && (index > 0 || c == '√')
        }
        if (operators.isEmpty()) return false
        if (compact.count { it.isDigit() } < 2 && '√' !in compact && 'π' !in compact) return false
        if (explicit) return true

        // "100%" is how people write a percentage, not a request to divide
        // it by a hundred. Infix "%" still counts, as does "50%+10".
        if (operators == "%" && compact.endsWith("%")) return false

        // A whole date is never a sum, and neither is the zero-padded half of
        // one: "12/04" is December. A plain "1/2" is a half, though, so an
        // unpadded pair earns its chip.
        if (DATE_LIKE.matches(compact)) return false
        if (PADDED_PAIR.matches(compact)) return false

        // "555-1234" is a phone number far more often than a subtraction, so
        // a lone dash between plain integers needs a second operator first.
        if (operators == "-") {
            val parts = compact.split('-')
            if (parts.size == 2 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) return false
        }
        return true
    }

    // ---- spoken arithmetic ----

    /** "15% of 200", "15% tip on 4500" — a share of an amount. */
    private val PERCENT_OF = Regex(
        """(?<![\w.,])($NUM)\s?%\s(?:tip\s)?(?:of|on)\s($NUM)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "20% off 1500" — the amount after the discount. */
    private val PERCENT_OFF = Regex(
        """(?<![\w.,])($NUM)\s?%\soff\s(?:of\s)?($NUM)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "split 4500 between 3", "split 4500 3 ways", "4500 split 3 ways". */
    private val SPLIT_LEAD = Regex(
        """(?<![\p{L}])split\s($NUM)\s(?:(?:between|among|by)\s)?($NUM)(?:\s?(?:ways|people|persons|friends))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val SPLIT_TRAIL = Regex(
        """(?<![\w.,])($NUM)\ssplit\s(?:(?:between|among|by)\s)?($NUM)(?:\s?(?:ways|people|persons|friends))?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The arithmetic people write as prose rather than as a sum: percentages
     * of an amount, discounts, and bills split between friends. Same chip as
     * [detectCalc]; only the trigger's spelling differs, so the calculator
     * still opens with an equivalent expression.
     */
    private fun detectCalcPhrase(tail: String, ctx: Context): SmartHit? {
        data class Phrase(val expression: String, val value: Double, val span: Int)

        val hit = run {
            PERCENT_OF.find(tail)?.let { m ->
                val p = parseNumber(m.groupValues[1]) ?: return@run null
                val base = parseNumber(m.groupValues[2]) ?: return@run null
                return@run Phrase("${digits(p)}/100*${digits(base)}", p / 100 * base, m.value.length)
            }
            PERCENT_OFF.find(tail)?.let { m ->
                val p = parseNumber(m.groupValues[1]) ?: return@run null
                val base = parseNumber(m.groupValues[2]) ?: return@run null
                return@run Phrase(
                    "${digits(base)}-${digits(p)}/100*${digits(base)}",
                    base - p / 100 * base,
                    m.value.length,
                )
            }
            val split = SPLIT_LEAD.find(tail) ?: SPLIT_TRAIL.find(tail) ?: return@run null
            val total = parseNumber(split.groupValues[1]) ?: return@run null
            val parts = parseNumber(split.groupValues[2]) ?: return@run null
            if (parts == 0.0) return@run null
            Phrase("${digits(total)}/${digits(parts)}", total / parts, split.value.length)
        } ?: return null

        if (hit.value.isNaN() || hit.value.isInfinite()) return null
        val result = CalcEngine.format(hit.value, ctx.precision)
        val query = tail.takeLast(hit.span)
        val short = CalcEngine.format(hit.value, 2)
        return SmartHit(
            kind = Kind.CALC,
            query = query,
            result = result,
            insert = result,
            replaceSpan = hit.span,
            tool = ToolbarTool.CALCULATOR,
            prefill = ToolPrefill.Calc(hit.expression),
            tiers = buildList {
                add(ChipTier(query, result))
                if (short != result) add(ChipTier(query, "~$short"))
            },
        )
    }

    /** A parsed amount back as plain digits for a calculator expression. */
    private fun digits(value: Double): String = CalcEngine.format(value, 8)

    // ---- dates ----

    private fun detectDate(tail: String, ctx: Context): SmartHit? {
        if (ctx.todayJdn <= 0 || !toolOn(ToolbarTool.CALENDAR, ctx)) return null
        val d = DateSuggest.find(tail, ctx.todayJdn) ?: return null
        val date = CalendarSystems.jdnToGregorian(d.jdn)
        return SmartHit(
            kind = Kind.DATE,
            query = d.phrase,
            result = d.display,
            // "next friday" becomes "next friday (21 Aug)": the phrase stays —
            // it is what the recipient reads — and the date it means lands
            // beside it.
            insert = "${d.phrase} (${d.annotation})",
            replaceSpan = tail.length - d.start,
            tool = ToolbarTool.CALENDAR,
            prefill = ToolPrefill.Calendar(date.year, date.month, date.day),
            tiers = listOf(ChipTier(d.phrase, d.display), ChipTier(d.phrase, d.annotation)),
        )
    }

    // ---- weather ----

    private val WEATHER_PHRASE = Regex(
        "(?:will it rain|is it (?:going to |gonna )?rain(?:ing)?|gonna rain|" +
            "how(?:'s| is) the weather|hows the weather|what(?:'s| is) the weather(?: like)?|" +
            "how (?:hot|cold|warm) is it(?: outside)?|" +
            "temperature (?:outside|now|today|tomorrow)|chance of rain)" +
            "(?:\\s(?:today|tomorrow|tonight))?\\s?\\??$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A question the weather tool can answer on the spot. The chip never
     * types into the field on its own — the question was probably meant for
     * the person on the other end — so a tap opens the tool instead, and the
     * answer sits on the strip for the asker meanwhile.
     */
    private fun detectWeather(tail: String, ctx: Context): SmartHit? {
        if (!toolOn(ToolbarTool.WEATHER, ctx)) return null
        val match = WEATHER_PHRASE.find(tail) ?: return null
        val query = match.value.trimEnd(' ', '?')
        val tomorrow = match.value.lowercase(Locale.ROOT).contains("tomorrow")
        val info = ctx.weather ?: return if (ctx.weatherAvailable) {
            SmartHit(
                kind = Kind.WEATHER, query = query, result = null, insert = null,
                replaceSpan = 0, tool = ToolbarTool.WEATHER, prefill = null,
                pending = true, pendingWeather = true,
            )
        } else {
            null
        }

        val unit = if (ctx.weatherFahrenheit) "°F" else "°C"
        fun deg(celsius: Double) = "${WeatherClient.toDisplay(celsius, ctx.weatherFahrenheit)}"
        val tiers: List<ChipTier>
        if (tomorrow) {
            val high = info.tomorrowHighC ?: return null
            val low = info.tomorrowLowC ?: return null
            val emoji = WeatherClient.emoji(info.tomorrowCode ?: info.weatherCode, isDay = true)
            val range = "${deg(low)}–${deg(high)}$unit"
            val rain = info.tomorrowPrecipProbabilityPercent?.takeIf { it >= 0 }
            tiers = buildList {
                add(ChipTier("$query?", "$emoji $range" + (rain?.let { " · ☔$it%" }.orEmpty())))
                add(ChipTier("$query?", "$emoji $range"))
            }
        } else {
            val emoji = WeatherClient.emoji(info.weatherCode, info.isDay)
            val now = "${deg(info.temperatureC)}$unit"
            val range = "${deg(info.lowC)}–${deg(info.highC)}$unit"
            val rain = info.precipProbabilityPercent.takeIf { it >= 0 }
            tiers = buildList {
                add(ChipTier("$query?", "$emoji $now · $range" + (rain?.let { " · ☔$it%" }.orEmpty())))
                add(ChipTier("$query?", "$emoji $now · $range"))
                add(ChipTier("$query?", "$emoji $now"))
            }
        }
        return SmartHit(
            kind = Kind.WEATHER,
            query = query,
            result = tiers.first().result,
            insert = null,
            replaceSpan = 0,
            tool = ToolbarTool.WEATHER,
            prefill = null,
            tiers = tiers,
        )
    }

    // ---- lookups ----

    private val DEFINE_TAIL = Regex(
        """(?<![\p{L}])(?:define|definition of|meaning of)\s+([\p{L}]{2,24})\s?\??$""",
        RegexOption.IGNORE_CASE,
    )
    private val WHAT_MEAN_TAIL = Regex(
        """(?<![\p{L}])what does\s+([\p{L}]{2,24})\s+mean\s?\??$""",
        RegexOption.IGNORE_CASE,
    )
    private val WIKI_TAIL = Regex(
        """(?<![\p{L}])(?:who (?:is|was)|what (?:is|are)|tell me about)\s+([\p{L}][\p{L} .'\-]{1,40}?)\s?\??$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words a "what is …" has not finished saying yet. Not stopwords in
     * general — only the ones that read as the start of a longer phrase, so
     * the chip waits for the noun instead of offering to look up "the".
     */
    private val lookupNoise = setOf(
        "the", "a", "an", "it", "this", "that", "your", "my", "his", "her",
        "their", "our", "its", "up", "so", "he", "she", "you", "there",
    )

    /**
     * A typed question with a term in it: the dictionary forms first since
     * they are more specific, then the encyclopedia ones. The chip never eats
     * the question — it may well be meant for the other person — it just
     * offers the lookup alongside.
     */
    private fun detectLookup(tail: String, ctx: Context): SmartHit? {
        val dictOn = toolOn(ToolbarTool.DICTIONARY, ctx)
        if (dictOn) {
            val word = DEFINE_TAIL.find(tail)?.groupValues?.get(1)
                ?: WHAT_MEAN_TAIL.find(tail)?.groupValues?.get(1)
            if (word != null && word.lowercase(Locale.ROOT) !in lookupNoise) {
                return lookupHit(ToolbarTool.DICTIONARY, word)
            }
        }
        if (toolOn(ToolbarTool.WIKIPEDIA, ctx)) {
            val term = WIKI_TAIL.find(tail)?.groupValues?.get(1)?.trim()
            if (term != null && term.length >= 3 &&
                term.last().isLetter() &&
                term.lowercase(Locale.ROOT) !in lookupNoise &&
                term.count { it == ' ' } <= 3
            ) {
                return lookupHit(ToolbarTool.WIKIPEDIA, term)
            }
        }
        return null
    }

    private fun lookupHit(tool: ToolbarTool, term: String): SmartHit = SmartHit(
        kind = Kind.LOOKUP,
        query = term,
        result = null,
        insert = null,
        replaceSpan = 0,
        tool = tool,
        prefill = ToolPrefill.Lookup(term),
    )

    // ---- intents ----

    private val TRANSLATE_TAIL = Regex(
        "(?:how do (?:you|i) say|translate (?:this|it)|" +
            "in (?:english|bengali|bangla|hindi|urdu|arabic|spanish|french|german|" +
            "chinese|japanese|korean|italian|portuguese|russian|turkish))\\s?\\??$",
        RegexOption.IGNORE_CASE,
    )

    /** A celebratory phrase → the search the GIF panel opens on. */
    private val gifPhrases: List<Pair<String, String>> = listOf(
        "happy birthday" to "happy birthday",
        "hbd" to "happy birthday",
        "শুভ জন্মদিন" to "happy birthday",
        "happy anniversary" to "happy anniversary",
        "congratulations" to "congratulations",
        "congrats" to "congratulations",
        "অভিনন্দন" to "congratulations",
        "well done" to "well done",
        "good luck" to "good luck",
        "get well soon" to "get well soon",
        "happy new year" to "happy new year",
        "merry christmas" to "merry christmas",
        "eid mubarak" to "eid mubarak",
        "ঈদ মোবারক" to "eid mubarak",
    )

    /**
     * Text that sounds like a job a tool does: "how do you say" for the
     * translator, a celebration for the GIF search. Both keep what was
     * typed — the chip is a door, not an autocorrect.
     */
    private fun detectIntent(tail: String, ctx: Context): SmartHit? {
        if (ctx.gifChips) {
            // Trailing punctuation and emoji off first: "congrats!! 🎉" still
            // ends in a celebration.
            val trimmed = tail.trimEnd { !it.isLetter() }
            val lower = trimmed.lowercase(Locale.ROOT)
            for ((phrase, search) in gifPhrases) {
                if (!lower.endsWith(phrase)) continue
                val start = trimmed.length - phrase.length
                if (start > 0 && trimmed[start - 1].isLetter()) continue
                val tool = when {
                    toolOn(ToolbarTool.GIF, ctx) -> ToolbarTool.GIF
                    toolOn(ToolbarTool.STICKER, ctx) -> ToolbarTool.STICKER
                    else -> continue
                }
                return intentHit(tool, phrase, ToolPrefill.Gif(search))
            }
        }
        if (toolOn(ToolbarTool.TRANSLATE, ctx)) {
            TRANSLATE_TAIL.find(tail)?.let {
                return intentHit(ToolbarTool.TRANSLATE, it.value.trimEnd(' ', '?'))
            }
        }
        return null
    }

    private fun intentHit(tool: ToolbarTool, query: String, prefill: ToolPrefill? = null): SmartHit =
        SmartHit(
            kind = Kind.INTENT,
            query = query,
            result = null,
            insert = null,
            replaceSpan = 0,
            tool = tool,
            prefill = prefill,
        )

    // ---- tool keywords ----

    private val KEYWORD_TAIL = Regex("""(?<![\w])([\p{L}]{2,16})$""")

    private fun detectKeyword(tail: String, ctx: Context): SmartHit? {
        val word = KEYWORD_TAIL.find(tail)?.groupValues?.get(1) ?: return null
        val overrides = decodeKeywords(ctx.keywordOverrides)
        val exact = decodeCaseSensitive(ctx.caseSensitiveKeywords)
        val tool = ToolbarTool.entries.firstOrNull { candidate ->
            candidate in ctx.enabledTools &&
                matchesKeyword(word, overrides[candidate] ?: defaultKeywords[candidate].orEmpty(), candidate in exact)
        } ?: return null
        return SmartHit(
            kind = Kind.TOOL,
            query = word,
            result = null,
            insert = null,
            replaceSpan = if (tool == ToolbarTool.TEXT_EDIT) 0 else word.length,
            tool = tool,
            prefill = null,
        )
    }

    /**
     * Whether [typed] is one of [keywords].
     *
     * Case-insensitively by default, which is what a keyboard wants: nobody
     * types "wiki" the same way twice. A tool the user marked case-sensitive
     * compares literally instead, so "AI" can open the AI tool while "ai" in
     * the middle of a word — or "Ai" at the start of a sentence the keyboard
     * auto-capitalised — is left alone.
     */
    private fun matchesKeyword(typed: String, keywords: List<String>, caseSensitive: Boolean): Boolean =
        if (caseSensitive) {
            typed in keywords
        } else {
            val lower = typed.lowercase(Locale.ROOT)
            keywords.any { it.lowercase(Locale.ROOT) == lower }
        }

    // ---- vocabulary nudges ----

    /**
     * The last whole word plus up to two trailing separators, so "hate",
     * "hate " and "hate. " all offer while "hate. I" (a new word begun) does
     * not. Two rather than one because the auto-space after punctuation
     * arrives as one keystroke with the punctuation.
     */
    private val VOCAB_TAIL = Regex("""(?<![\p{L}\d'’-])([\p{L}][\p{L}'’-]{1,22}[\p{L}])([\s\p{Punct}]{0,2})$""")

    /**
     * "hate" → abhor: the typed word is a trigger in an installed pack. The
     * chip's face carries the replacement (bent to the typed inflection and
     * case) and [SmartHit.replaceSpan] covers the separator too, so a swap
     * keeps the spacing. When the typed word *is* a vocabulary word the hit
     * carries no [SmartHit.result] — an open-only chip that shows the card.
     */
    private fun detectVocab(tail: String, ctx: Context): SmartHit? {
        if (!toolOn(ToolbarTool.VOCABULARY, ctx)) return null
        val index = ctx.vocab ?: return null
        if (index.isEmpty) return null
        val match = VOCAB_TAIL.find(tail) ?: return null
        val typed = match.groupValues[1]
        val trail = match.groupValues[2]
        val key = typed.lowercase(Locale.ROOT)
        if (key in ctx.vocabRetired) return null
        val hit = index.hitsFor(key, ctx.vocabMinGap).firstOrNull { vocabInScope(it.lemma, ctx) }
        if (hit != null) {
            val replacement = if (typed[0].isUpperCase()) {
                hit.replacement.replaceFirstChar { it.titlecase(Locale.ROOT) }
            } else {
                hit.replacement
            }
            return SmartHit(
                kind = Kind.VOCAB,
                query = typed,
                result = replacement,
                insert = replacement + trail,
                replaceSpan = typed.length + trail.length,
                tool = ToolbarTool.VOCABULARY,
                prefill = ToolPrefill.Vocab(hit.lemma),
            )
        }
        if (!ctx.vocabSelfChips) return null
        val record = index.lookupAnyForm(key) ?: return null
        if (!vocabInScope(record.word, ctx)) return null
        return SmartHit(
            kind = Kind.VOCAB,
            query = typed,
            result = null,
            insert = null,
            replaceSpan = 0,
            tool = ToolbarTool.VOCABULARY,
            prefill = ToolPrefill.Vocab(record.word),
        )
    }

    private fun vocabInScope(lemma: String, ctx: Context): Boolean = when (ctx.vocabScope) {
        VocabNudgeScope.UNLEARNT -> !ctx.vocabLearnt(lemma)
        VocabNudgeScope.ALL -> true
        VocabNudgeScope.LEARNT_ONLY -> ctx.vocabLearnt(lemma)
    }

    /** The keywords each tool answers to until the user edits them. */
    val defaultKeywords: Map<ToolbarTool, List<String>> = mapOf(
        ToolbarTool.WIKIPEDIA to listOf("wiki", "wikipedia"),
        ToolbarTool.TRANSLATE to listOf("translate"),
        ToolbarTool.CALCULATOR to listOf("calc", "calculator"),
        ToolbarTool.UNIT_CONVERT to listOf("convert", "unit"),
        ToolbarTool.CURRENCY to listOf("currency", "forex"),
        ToolbarTool.GIF to listOf("gif"),
        ToolbarTool.STICKER to listOf("sticker"),
        ToolbarTool.EMOJI to listOf("emoji"),
        ToolbarTool.WEATHER to listOf("weather"),
        ToolbarTool.CLIPBOARD to listOf("clipboard"),
        ToolbarTool.SNIPPETS to listOf("snippet", "snippets"),
        ToolbarTool.QR_GEN to listOf("qr"),
        ToolbarTool.PASSWORD_GEN to listOf("password", "passgen"),
        ToolbarTool.AI to listOf("ai"),
        ToolbarTool.WEB_SEARCH to listOf("search"),
        ToolbarTool.IMAGE_SEARCH to listOf("images"),
        ToolbarTool.SYMBOLS to listOf("symbol", "symbols"),
        ToolbarTool.VOICE to listOf("dictate"),
        ToolbarTool.OCR to listOf("ocr"),
        ToolbarTool.QR_SCAN to listOf("scan"),
        ToolbarTool.DOC_SCAN to listOf("docscan"),
        ToolbarTool.HANDWRITING to listOf("handwrite"),
        ToolbarTool.TYPING_TEST to listOf("wpm"),
        ToolbarTool.DICTIONARY to listOf("define", "dict"),
        ToolbarTool.VOCABULARY to listOf("vocab", "vocabulary"),
        ToolbarTool.GRAMMAR to listOf("grammar"),
        ToolbarTool.CALENDAR to listOf("calendar", "schedule"),
        ToolbarTool.MOON_PHASE to listOf("moon"),
        ToolbarTool.COMPASS to listOf("compass"),
        ToolbarTool.LEVEL to listOf("level"),
        ToolbarTool.THEMES to listOf("theme", "themes"),
        ToolbarTool.MODES to listOf("modes"),
        ToolbarTool.RESIZE to listOf("resize"),
        ToolbarTool.NUMPAD to listOf("numpad"),
        ToolbarTool.CAMERA to listOf("camera"),
        ToolbarTool.TEXT_EDIT to listOf("edit"),
        ToolbarTool.TRACKPAD to listOf("trackpad"),
        ToolbarTool.APP_LAUNCHER to listOf("apps", "launch"),
    )

    fun keywordsFor(tool: ToolbarTool, overrides: String): List<String> =
        decodeKeywords(overrides)[tool] ?: defaultKeywords[tool].orEmpty()

    /**
     * Overrides encode as "TOOL=a,b;TOOL=c". A tool with an empty list is
     * kept in the map (that is how "no keywords for this one" is stored, as
     * distinct from "never edited, use the defaults").
     *
     * Capitals are kept as the user typed them, because a tool can be marked
     * case-sensitive (see [caseSensitiveKeyword]) and a keyword folded to
     * lower case on the way in could then never match anything. Everything
     * stored before that existed is already lower case, which is exactly what
     * a case-insensitive tool wants.
     */
    fun decodeKeywords(encoded: String): Map<ToolbarTool, List<String>> =
        encoded.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=', "").ifEmpty { return@mapNotNull null }
            val tool = ToolbarTool.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val words = entry.substringAfter('=', "")
                .split(',')
                .map { it.trim() }
                .filter { it.length >= 2 && it.all(Char::isLetter) }
            tool to words
        }.toMap()

    fun encodeKeywords(map: Map<ToolbarTool, List<String>>): String =
        map.entries.joinToString(";") { (tool, words) -> "${tool.name}=${words.joinToString(",")}" }

    /** Replaces one tool's keywords inside an encoded override string. */
    fun withKeywords(encoded: String, tool: ToolbarTool, words: List<String>): String {
        val map = decodeKeywords(encoded).toMutableMap()
        val cleaned = words.map { it.trim() }
            .filter { it.length >= 2 && it.all(Char::isLetter) }
            .distinct()
        if (cleaned == defaultKeywords[tool].orEmpty()) map.remove(tool) else map[tool] = cleaned
        return encodeKeywords(map)
    }

    // ---- case sensitivity ----

    /**
     * The tools whose keywords are matched literally, encoded as a plain
     * comma-separated list of enum names. Absent means case-insensitive, which
     * is the default and by far the common case.
     */
    fun decodeCaseSensitive(encoded: String): Set<ToolbarTool> {
        if (encoded.isEmpty()) return emptySet()
        return encoded.split(',')
            .mapNotNullTo(mutableSetOf()) { name ->
                ToolbarTool.entries.firstOrNull { it.name == name }
            }
    }

    fun encodeCaseSensitive(tools: Set<ToolbarTool>): String =
        ToolbarTool.entries.filter { it in tools }.joinToString(",") { it.name }

    fun caseSensitiveKeyword(tool: ToolbarTool, encoded: String): Boolean =
        tool in decodeCaseSensitive(encoded)

    /** Adds or removes one tool from an encoded case-sensitivity list. */
    fun withCaseSensitive(encoded: String, tool: ToolbarTool, sensitive: Boolean): String {
        val tools = decodeCaseSensitive(encoded).toMutableSet()
        if (sensitive) tools += tool else tools -= tool
        return encodeCaseSensitive(tools)
    }
}
