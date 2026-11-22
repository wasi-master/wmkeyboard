package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.tools.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * JNI surface of libwmnumbat (native/numbat-jni), the Numbat calculator
 * language. Only [Numbat] calls it; see that for the contract.
 */
internal object NumbatNative {
    const val LIBRARY = "wmnumbat"

    /** One expression to a JSON outcome; see [Numbat.parse]. */
    external fun nativeEvaluate(expression: String, digits: Int): String?

    /** The exchange-rate table as a JSON object of units per US dollar. */
    external fun nativeSetRates(json: String): Boolean

    /** Loads Numbat's prelude, the slow part of the first expression. */
    external fun nativeWarmUp()
}

/**
 * Sums with units, physical constants and dates: "3 km/h * 20 min",
 * "5 ft + 30 cm -> m", "c * 2 s -> km", "now() + 3 weeks". Numbat
 * (numbat.dev) runs them, in a native library of about 2 MB behind the
 * `numbat` gate, so a phone fetches it only once the calculator asks for it.
 *
 * Never the first engine: [CalcRouting] gives every sum [CalcEngine] can read
 * to [CalcEngine], which answers at once and needs nothing fetched, and only
 * what it cannot read comes here. Currency units are built from the keyboard's
 * own rate table ([CurrencyClient]), handed over with each call, so converting
 * money inside a sum works offline with whatever rates were last fetched.
 * Numbat's own fetching is compiled out.
 *
 * [evaluate] blocks: the first call loads the prelude, a few hundred
 * milliseconds on a phone, and a warm call takes about a millisecond. Call it
 * off the main thread.
 */
object Numbat {

    /** What an expression came to. */
    sealed interface Outcome {
        /**
         * An answer. [value] and [unit] are set for a quantity ([unit] is ""
         * for a plain number); [text] is Numbat's own rendering, which is all
         * a date or a string has. [kind] is `quantity`, `datetime`, `string`,
         * `boolean` or `other`.
         */
        data class Answer(val text: String, val kind: String, val value: Double? = null, val unit: String = "") : Outcome {
            val isQuantity: Boolean get() = kind == KIND_QUANTITY && value != null
        }

        /**
         * No answer. [code] is `refused` (an input that could run without
         * end, or a definition), `temperature`, `empty`, `parse`, `unknown`
         * ([detail] names the identifier), `dimensions` (units that do not
         * go together), `type`, `runtime`, or `native` when the call itself
         * failed.
         */
        data class Failure(val code: String, val detail: String = "") : Outcome
    }

    const val KIND_QUANTITY = "quantity"

    private val library = GatedLibrary({ FeatureModules.numbat }) {
        runCatching { FeatureModules.loadLibrary(NumbatNative.LIBRARY) }.isSuccess
    }

    /** Whether the library is loaded here and now; loads it if it can. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** The gate itself, for a panel that shows the download. */
    val gate: ModuleGate get() = FeatureModules.numbat

    /** Asks for the gate. Idempotent; the calculator calls it as it opens. */
    fun requestLibrary() = FeatureModules.numbat.requestInstall()

    /** True once the prelude is loaded, so the next call is quick. */
    @Volatile
    var warm: Boolean = false
        private set

    private val lock = Any()

    /** The table last handed to the library, compared by identity. */
    private var pushedRates: CurrencyClient.Rates? = null

    /**
     * Evaluates [expression] with [digits] significant digits in Numbat's own
     * text, after handing over [rates] if they are not the ones it has. Null
     * when the library is not here. Blocking.
     */
    fun evaluate(expression: String, digits: Int = DEFAULT_DIGITS, rates: CurrencyClient.Rates? = null): Outcome? {
        if (!available) return null
        synchronized(lock) {
            syncRates(rates)
            val json = runCatching { NumbatNative.nativeEvaluate(expression, digits) }.getOrNull()
            warm = true
            return json?.let(::parse) ?: Outcome.Failure(CODE_NATIVE)
        }
    }

    /** Loads the prelude ahead of the first expression. False when the library is not here. */
    fun warmUp(rates: CurrencyClient.Rates? = null): Boolean {
        if (!available) return false
        synchronized(lock) {
            if (runCatching { NumbatNative.nativeWarmUp() }.isFailure) return false
            syncRates(rates)
            warm = true
        }
        return true
    }

    private fun syncRates(rates: CurrencyClient.Rates?) {
        if (rates == null || rates === pushedRates) return
        if (runCatching { NumbatNative.nativeSetRates(ratesJson(rates)) }.getOrDefault(false)) pushedRates = rates
    }

    /** [rates] as the library takes them: one flat object of units per US dollar. */
    internal fun ratesJson(rates: CurrencyClient.Rates): String {
        // Every source is normalised to USD already; a table on another
        // base is rescaled by its own dollar rate, in case one ever is not.
        val usd = rates.rates["USD"]?.takeIf { rates.base != "USD" && it.isFinite() && it > 0.0 } ?: 1.0
        return buildJsonObject {
            for ((code, rate) in rates.rates) {
                val value = rate / usd
                if (value.isFinite() && value > 0.0) put(code, value)
            }
        }.toString()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The library's JSON outcome read back. Anything unreadable is a [Outcome.Failure]. */
    fun parse(text: String): Outcome {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Outcome.Failure(CODE_NATIVE)
        fun string(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            return Outcome.Failure(string("code") ?: CODE_NATIVE, string("detail").orEmpty())
        }
        val answer = string("text") ?: return Outcome.Failure(CODE_NATIVE)
        return Outcome.Answer(
            text = answer,
            kind = string("kind") ?: "other",
            value = (root["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() },
            unit = string("unit").orEmpty(),
        )
    }

    /**
     * An answer as the keyboard writes one: a quantity's number in the
     * calculator's [precision] (decimals, as [CalcEngine.format] counts them)
     * and its unit after a space; anything else as Numbat wrote it.
     */
    fun format(answer: Outcome.Answer, precision: Int): String {
        val value = answer.value
        if (!answer.isQuantity || value == null) return answer.text
        val number = CalcEngine.format(value, precision)
        return if (answer.unit.isEmpty()) number else "$number ${answer.unit}"
    }

    /**
     * [failure] as the calculator's own error, so the display words it the
     * way it words [CalcEngine]'s. Null where Numbat has nothing better to
     * say than [CalcEngine] already did (a parse error, say).
     */
    fun error(failure: Outcome.Failure): CalcEngine.CalcException? = when (failure.code) {
        "unknown" -> failure.detail.takeIf { it.isNotEmpty() }
            ?.let { CalcEngine.CalcException(R.string.core_tools_calc_error_unknown_name, it) }
        "dimensions" -> CalcEngine.CalcException(R.string.core_tools_calc_error_units_mismatch)
        "temperature" -> CalcEngine.CalcException(R.string.core_tools_calc_error_temperature)
        else -> null
    }

    private const val CODE_NATIVE = "native"

    /** Significant digits asked for; [format] rounds quantities itself. */
    const val DEFAULT_DIGITS = 10
}
