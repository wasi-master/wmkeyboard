package com.wasimaster.wmkeyboard.core.phone

import android.content.Context
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * A number taken apart: its dial code, the national number with any trunk
 * prefix gone, and the region it belongs to.
 *
 * [region] is null when no region under [countryCode] has the number, which
 * is the usual sign of a digit run that is not a phone number at all.
 * [explicitCountry] is whether the text itself named the country (`+`, `00`,
 * or the dial code written out) rather than it being taken from the phone.
 */
data class ParsedPhone(
    val countryCode: String,
    val national: String,
    val region: String?,
    val explicitCountry: Boolean,
) {
    /** `+8801712345678`: what `tel:`, WhatsApp and a contact card all take. */
    val e164: String get() = "+$countryCode$national"
}

/**
 * The OS's formatter, behind a seam so JVM tests can stand in for it.
 * Both answer null when the number cannot be formatted.
 */
interface PhoneFormatter {
    /** [number] as people write it in [region]: national for a local number, international otherwise. */
    fun format(number: String, region: String): String?

    /** [number] in E.164, `+` and digits, with [region] supplying a missing dial code. */
    fun toE164(number: String, region: String): String?
}

/**
 * [PhoneFormatter] on the libphonenumber that ships inside Android, through
 * the public [PhoneNumberUtils]. Costs the APK nothing. Wrapped because a
 * few OEM builds throw out of it, and so a JVM test's stub android.jar,
 * which throws on every call, reads as "cannot format".
 */
object SystemPhoneFormatter : PhoneFormatter {
    override fun format(number: String, region: String): String? =
        runCatching { PhoneNumberUtils.formatNumber(number, region.uppercase(Locale.ROOT)) }.getOrNull()

    override fun toE164(number: String, region: String): String? =
        runCatching { PhoneNumberUtils.formatNumberToE164(number, region.uppercase(Locale.ROOT)) }.getOrNull()
}

/**
 * Phone numbers for every country: is this real, is it a mobile, and how is
 * it written.
 *
 * Validity comes from [PhoneMetadata], libphonenumber's numbering plans cut
 * down to a 86 KB asset. Formatting comes from the copy of libphonenumber the
 * OS already carries ([SystemPhoneFormatter]), so the 360 KB library itself is
 * never bundled. A number written without a country is read against
 * [defaultRegion]: the mobile network's country, then the SIM's, then the
 * app's locale.
 *
 * [init] once from the application. Until then (and in JVM tests that do not
 * [install] a table) every query answers "do not know": [parse] is null and
 * [check] is null, which callers read as "use the old shape rules".
 */
object PhoneNumbers {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var table: PhoneMetadata? = null

    @Volatile
    private var tableTried = false

    /** Set by [install]: a fixed region that [defaultRegion] never re-reads. */
    @Volatile
    private var pinnedRegion: String? = null

    @Volatile
    private var regionCache: Pair<Long, String?>? = null

    /** What formats. Replaced only by tests. */
    @Volatile
    var formatter: PhoneFormatter = SystemPhoneFormatter

    /** How long a read of the phone's country stands; the network changes when someone travels. */
    private const val REGION_TTL_MS = 5 * 60_000L

    /**
     * Hands over the application context and starts reading the table off
     * the main thread, so the first clipboard scan does not pay for it.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        Thread({ metadata() }, "phone-metadata").apply { isDaemon = true }.start()
    }

    /**
     * Replaces the table and the region, for tests. A null [metadata] puts
     * things back the way an uninitialised process has them.
     */
    fun install(metadata: PhoneMetadata?, region: String?) {
        table = metadata
        tableTried = metadata != null
        pinnedRegion = region?.uppercase(Locale.ROOT)
        regionCache = null
        if (metadata == null) appContext = null
    }

    /** The numbering plans, read once; null before [init] or when the asset is missing. */
    fun metadata(): PhoneMetadata? {
        table?.let { return it }
        if (tableTried) return null
        val context = appContext ?: return null
        synchronized(this) {
            table?.let { return it }
            if (tableTried) return null
            table = runCatching {
                context.assets.open(PhoneMetadata.ASSET).bufferedReader().use { PhoneMetadata.parse(it.readText()) }
            }.getOrNull()?.takeIf { it.size > 0 }
            tableTried = true
            return table
        }
    }

    /**
     * The region a number with no dial code is read in, upper case, or null
     * when the phone gives none. See [PhoneRegions.pick] for the order.
     */
    val defaultRegion: String?
        get() {
            pinnedRegion?.let { return it }
            val context = appContext ?: return null
            val now = SystemClock.elapsedRealtime()
            regionCache?.let { (at, region) -> if (now - at < REGION_TTL_MS) return region }
            val region = PhoneRegions.read(context)
            regionCache = now to region
            return region
        }

    /**
     * [raw] taken apart, or null when it cannot be: no table yet, too few or
     * too many digits, a dial code no one has, or no country at all (no `+`
     * and no [region] to read it in).
     *
     * Follows libphonenumber's parse in the parts that matter for text people
     * copy: `+` or the region's own international prefix (`00`, `011`) names
     * a country; a national number loses its trunk prefix (`0`, Argentina's
     * `15`); and a number that starts with the region's own dial code but no
     * `+` (`8801712345678`) is read as having one when only that reading is
     * a real number.
     */
    fun parse(raw: String, region: String? = defaultRegion): ParsedPhone? {
        val table = metadata() ?: return null
        val trimmed = raw.trim()
        val digits = asciiDigits(trimmed) ?: return null
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null
        if (trimmed.startsWith('+') || trimmed.startsWith('＋')) return international(table, digits)
        val home = table.plan(region)
        if (home == null) {
            // No country to read it in: only an explicit `00` can name one.
            return if (digits.startsWith("00")) international(table, digits.substring(2)) else null
        }
        home.stripInternationalPrefix(digits)?.let { rest -> international(table, rest)?.let { return it } }
        val national = home.stripNationalPrefix(digits)
        if (digits.startsWith(home.countryCode)) {
            val rest = home.stripNationalPrefix(digits.substring(home.countryCode.length))
            val withCode = table.regionFor(home.countryCode, rest)
            val asWritten = table.regionFor(home.countryCode, national)
            if (withCode != null && withCode.typeOf(rest) != PhoneType.UNKNOWN &&
                (asWritten == null || asWritten.typeOf(national) == PhoneType.UNKNOWN)
            ) {
                return ParsedPhone(home.countryCode, rest, withCode.region, explicitCountry = true)
            }
        }
        val owner = table.regionFor(home.countryCode, national)
        return ParsedPhone(home.countryCode, national, owner?.region, explicitCountry = false)
    }

    /** The dial code at the front of [digits] (one to three digits), and the number after it. */
    private fun international(table: PhoneMetadata, digits: String): ParsedPhone? {
        for (length in 1..3) {
            if (digits.length <= length) return null
            val code = digits.substring(0, length)
            val plans = table.plansFor(code)
            if (plans.isEmpty()) continue
            // `+44 (0)20 …`: the trunk zero written inside an international number.
            val national = plans.first().stripNationalPrefix(digits.substring(length))
            val owner = table.regionFor(code, national)
            return ParsedPhone(code, national, owner?.region, explicitCountry = true)
        }
        return null
    }

    /**
     * The type of [number], read in [region] when it names no country of its
     * own. [PhoneType.UNKNOWN] when it is not a real number, and also when
     * there is no table to ask; [check] tells those two apart.
     */
    fun type(number: String, region: String? = defaultRegion): PhoneType {
        val parsed = parse(number, region) ?: return PhoneType.UNKNOWN
        val plan = metadata()?.plan(parsed.region) ?: return PhoneType.UNKNOWN
        return plan.typeOf(parsed.national)
    }

    /** Whether [number] is a real number: a type in its region's plan, at a length that region has. */
    fun isValid(number: String, region: String? = defaultRegion): Boolean =
        type(number, region) != PhoneType.UNKNOWN

    /**
     * [isValid] when the answer is known, and null when it is not: no table,
     * or a national number and no region to read it in. A null is the
     * caller's cue to keep the shape rules it had before there was a table.
     */
    fun check(number: String, region: String? = defaultRegion): Boolean? {
        if (metadata() == null) return null
        val trimmed = number.trim()
        val named = trimmed.startsWith('+') || trimmed.startsWith('＋') || trimmed.startsWith("00")
        if (!named && region == null) return null
        return isValid(number, region)
    }

    /**
     * [number] written the way the OS writes numbers for [region], or null
     * when it cannot: no region, or the formatter declines. A number from
     * another country comes out international.
     */
    fun format(number: String, region: String? = defaultRegion): String? {
        val home = region ?: parse(number, null)?.region ?: return null
        return formatter.format(number.trim(), home)?.takeIf { it.isNotBlank() }
    }

    /**
     * [number] in E.164, or null. The table's reading first, since it knows
     * when a national number in the user's own country is meant; the OS's
     * second, for a number the table cannot place.
     */
    fun e164(number: String, region: String? = defaultRegion): String? {
        parse(number, region)?.takeIf { it.region != null }?.let { return it.e164 }
        val home = region ?: return null
        return formatter.toE164(number.trim(), home)?.takeIf { it.startsWith('+') }
    }

    /**
     * Whether two spellings are one number: the same E.164 where both have
     * one, and the same digits otherwise.
     */
    fun same(a: String, b: String, region: String? = defaultRegion): Boolean {
        val left = e164(a, region)
        val right = e164(b, region)
        if (left != null && right != null) return left == right
        return a.filter(Char::isDigit) == b.filter(Char::isDigit)
    }

    /** At least a short code, at most E.164's fifteen plus an international prefix. */
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 17

    /**
     * The digits of [text] as ASCII, so a number copied in Bengali or Arabic
     * digits reads the same as one in Latin digits. Null when anything other
     * than digits and the punctuation numbers are written with is in it.
     */
    private fun asciiDigits(text: String): String? {
        val out = StringBuilder(text.length)
        for ((index, ch) in text.withIndex()) {
            when {
                ch in '0'..'9' -> out.append(ch)
                ch.isDigit() -> out.append('0' + Character.getNumericValue(ch).coerceIn(0, 9))
                ch in PUNCTUATION -> Unit
                (ch == '+' || ch == '＋') && index == 0 -> Unit
                else -> return null
            }
        }
        return out.toString()
    }

    private const val PUNCTUATION = " -().[]/ ‐‑‒–—−"
}

/** Where [PhoneNumbers.defaultRegion] comes from. */
object PhoneRegions {

    /**
     * The phone's country for reading national numbers: the mobile network
     * it is on, then its SIM, then the app's locale. Network first because
     * someone roaming dials and copies local numbers; the SIM is where they
     * are from. None of the three needs a permission.
     */
    fun read(context: Context): String? {
        val telephony = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()
        val network = runCatching { telephony?.networkCountryIso }.getOrNull()
        val sim = runCatching { telephony?.simCountryIso }.getOrNull()
        val locales = runCatching {
            val list = ConfigurationCompat.getLocales(context.resources.configuration)
            (0 until list.size()).mapNotNull { list.get(it)?.country }
        }.getOrDefault(emptyList())
        return pick(network, sim, locales)
    }

    /**
     * The first of [network], [sim] and [locales] that is a two-letter
     * region, upper case. Separate from [read] so the order is testable.
     */
    fun pick(network: String?, sim: String?, locales: List<String>): String? =
        (listOf(network, sim) + locales)
            .firstOrNull { it != null && it.length == 2 && it.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } }
            ?.uppercase(Locale.ROOT)
}
