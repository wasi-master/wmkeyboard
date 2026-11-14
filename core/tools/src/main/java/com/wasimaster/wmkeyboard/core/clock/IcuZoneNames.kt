package com.wasimaster.wmkeyboard.core.clock

import android.icu.text.DisplayContext
import android.icu.text.LocaleDisplayNames
import android.icu.text.TimeZoneNames
import java.util.Locale

/**
 * Zone names from the ICU data Android ships: "Dhaka", "Bangladesh" in
 * English, "ঢাকা", "বাংলাদেশ" in Bengali. Both calls exist from API 24.
 *
 * [viewerRegion] is the phone's own region (`DeviceLocales.read().regionCodes`,
 * first rung); it decides the country of a contested zone ([ContestedRegions]).
 */
class IcuZoneNames(
    private val locale: Locale = Locale.getDefault(),
    private val viewerRegion: String? = null,
) : ZoneNames {

    private val names: TimeZoneNames? = runCatching { TimeZoneNames.getInstance(locale) }.getOrNull()

    // Short names: "Palestine", not "Palestinian Territories".
    private val countries: LocaleDisplayNames? =
        runCatching { LocaleDisplayNames.getInstance(locale, DisplayContext.LENGTH_SHORT) }.getOrNull()

    override fun city(id: String): String? =
        runCatching { names?.getExemplarLocationName(id) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun canonical(id: String): String =
        runCatching { android.icu.util.TimeZone.getCanonicalID(id) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: id

    override fun region(id: String): String? {
        val tz = runCatching { android.icu.util.TimeZone.getRegion(id) }.getOrNull()
        val code = ContestedRegions.regionCode(canonical(id), tz, viewerRegion)
        return if (code.isNullOrEmpty() || code == "001") null else country(code)
    }

    override fun country(code: String): String? =
        (runCatching { countries?.regionDisplayName(code) }.getOrNull() ?: Locale("", code).getDisplayCountry(locale))
            .takeIf { it.isNotBlank() && it != code }
}
