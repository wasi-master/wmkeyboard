package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int

/** Snapshot of current conditions from Open-Meteo. Temperatures in °C. */
data class WeatherInfo(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val windKmh: Double,
    /** Direction the wind comes from, degrees clockwise from north. */
    val windDirectionDeg: Int,
    val pressureHpa: Double,
    val cloudCoverPercent: Int,
    val precipitationMm: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val highC: Double,
    val lowC: Double,
    /** Today's max UV index; negative when the API omits it. */
    val uvIndexMax: Double,
    /** Chance of precipitation today; negative when the API omits it. */
    val precipProbabilityPercent: Int,
    /** Local "HH:mm", empty when the API omits them. */
    val sunrise: String,
    val sunset: String,
    val fetchedAtMillis: Long,
    /** Tomorrow's forecast, null when the response carried only one day. */
    val tomorrowHighC: Double? = null,
    val tomorrowLowC: Double? = null,
    val tomorrowCode: Int? = null,
    val tomorrowPrecipProbabilityPercent: Int? = null,
    /** The next 24 hours from the current one (#1369); empty from an older response. */
    val hours: List<OutlookHour> = emptyList(),
    /** Today and the six days after it (#1369). */
    val days: List<OutlookDay> = emptyList(),
)

/** An air quality index pair (#1369): the European one and the US one; -1 where missing. */
data class AirQuality(val european: Int, val us: Int) {
    /** The index a reader of [fahrenheit] units expects: the US one for °F, else the European one. */
    fun shown(fahrenheit: Boolean): Int = if (fahrenheit) us else european
}

/** One hour of the Weather panel's outlook (#1369), in the place's own time (`2026-11-16T14:00`). */
data class OutlookHour(
    val time: String,
    val temperatureC: Double,
    /** Negative when the API omits it. */
    val precipProbabilityPercent: Int,
    val precipitationMm: Double,
    val windKmh: Double,
    /** Negative when the API omits it. */
    val uvIndex: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val airQuality: AirQuality? = null,
) {
    /** Minutes after midnight, local to the place. */
    val minuteOfDay: Int get() = time.substringAfter('T', "").split(':').let { p ->
        (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}

/** One day of the outlook (#1369): `2026-11-16`, with its extremes. */
data class OutlookDay(
    val date: String,
    val highC: Double,
    val lowC: Double,
    val precipProbabilityPercent: Int,
    val precipitationMm: Double,
    val windMaxKmh: Double,
    val uvIndexMax: Double,
    val weatherCode: Int,
    /** The day's worst hour. */
    val airQuality: AirQuality? = null,
)

/** One geocoding hit: a place the user can pick as the weather location. */
data class GeoPlace(
    val name: String,
    /** Region + country for disambiguation, e.g. "Dhaka Division, Bangladesh". */
    val region: String,
    val latitude: Float,
    val longitude: Float,
    /** The place's tz id ("Asia/Dhaka"), when the geocoder names one; the world clock's online search needs it. */
    val timezone: String? = null,
)

/** One hour of an hourly forecast (#1212). [timeMillis] is the hour's start. */
data class WeatherHour(
    val timeMillis: Long,
    val temperatureC: Double,
    /** Chance of precipitation in that hour; negative when the API omits it. */
    val precipProbabilityPercent: Int,
    val weatherCode: Int,
    val isDay: Boolean,
)

/** The hour-by-hour forecast for one place, as far ahead as [WeatherClient.FORECAST_DAYS]. */
data class HourlyForecast(
    val latitude: Float,
    val longitude: Float,
    val hours: List<WeatherHour>,
    val fetchedAtMillis: Long,
) {
    /** The hour [millis] falls in, or null outside the forecast. */
    fun hourAt(millis: Long): WeatherHour? {
        if (hours.isEmpty()) return null
        var lo = 0
        var hi = hours.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (hours[mid].timeMillis <= millis) lo = mid else hi = mid - 1
        }
        val hit = hours[lo]
        return hit.takeIf { millis >= it.timeMillis && millis < it.timeMillis + HOUR_MS }
    }

    /** The hours that start inside [from] until [until]. */
    fun between(from: Long, until: Long): List<WeatherHour> =
        hours.filter { it.timeMillis >= from && it.timeMillis < until }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/**
 * Minimal Open-Meteo client (no API key, no account). Only called from the
 * weather tool: forecasts when the panel opens, geocoding when the user
 * searches for a place in settings. The keyboard makes no other network
 * requests.
 */
object WeatherClient {

    /** Blocking fetch; call on an IO dispatcher. Throws on any failure. */
    fun fetch(latitude: Float, longitude: Float): WeatherInfo {
        // The base goes on outside the format string: a configured address may
        // carry a '%' of its own.
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO) + String.format(
            Locale.US,
            "/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "is_day,weather_code,wind_speed_10m,wind_direction_10m," +
                "surface_pressure,cloud_cover,precipitation" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day," +
                "wind_speed_10m,uv_index" +
                "&daily=temperature_2m_max,temperature_2m_min,uv_index_max," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max,sunrise,sunset,weather_code" +
                // A week: the panel's days (#1369); the second also feeds the
                // "weather tomorrow" smart chip.
                "&timezone=auto&forecast_days=$OUTLOOK_DAYS",
            latitude,
            longitude,
        )
        val info = parse(get(url), System.currentTimeMillis())
        // Air quality is a second service of Open-Meteo's; the forecast stands without it.
        val air = runCatching { airQuality(latitude, longitude) }.getOrNull() ?: return info
        return withAirQuality(info, air)
    }

    /** How many days the panel's outlook covers, today included. */
    const val OUTLOOK_DAYS = 7

    /**
     * Blocking: Open-Meteo's air quality forecast, by hour in the place's own
     * time (#1369). Throws on failure.
     */
    fun airQuality(latitude: Float, longitude: Float): Map<String, AirQuality> {
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO_AIR_QUALITY) + String.format(
            Locale.US,
            "/v1/air-quality?latitude=%.4f&longitude=%.4f&hourly=european_aqi,us_aqi&timezone=auto&forecast_days=$OUTLOOK_DAYS",
            latitude,
            longitude,
        )
        return parseAirQuality(get(url))
    }

    internal fun parseAirQuality(body: String): Map<String, AirQuality> {
        val hourly = Json.parseToJsonElement(body).jsonObject["hourly"]?.takeIf { it !is JsonNull }?.jsonObject ?: return emptyMap()
        val times = hourly["time"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, AirQuality>()
        for (i in times.indices) {
            val time = times[i].takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: continue
            val eu = hourly.optAt("european_aqi", i)?.toInt() ?: -1
            val us = hourly.optAt("us_aqi", i)?.toInt() ?: -1
            if (eu >= 0 || us >= 0) out[time] = AirQuality(eu, us)
        }
        return out
    }

    /** [info] with each hour's air quality, and each day's worst. */
    internal fun withAirQuality(info: WeatherInfo, air: Map<String, AirQuality>): WeatherInfo {
        if (air.isEmpty()) return info
        val byDay = air.entries.groupBy { it.key.substringBefore('T') }.mapValues { (_, hours) ->
            AirQuality(hours.maxOf { it.value.european }, hours.maxOf { it.value.us })
        }
        return info.copy(
            hours = info.hours.map { h -> air[h.time]?.let { h.copy(airQuality = it) } ?: h },
            days = info.days.map { d -> byDay[d.date]?.let { d.copy(airQuality = it) } ?: d },
        )
    }

    /**
     * Blocking hour-by-hour forecast for the next [FORECAST_DAYS] days, for the
     * weather on calendar events (#1212); call on an IO dispatcher. Throws on
     * any failure. Times come back as epoch seconds, so no time zone has to be
     * worked out on this side.
     */
    fun fetchHourly(latitude: Float, longitude: Float): HourlyForecast {
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO) + String.format(
            Locale.US,
            "/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
                "&timeformat=unixtime&timezone=auto&forecast_days=%d",
            latitude,
            longitude,
            FORECAST_DAYS,
        )
        return parseHourly(get(url), latitude, longitude, System.currentTimeMillis())
    }

    /** How many days ahead [fetchHourly] reaches: Open-Meteo's most, today included. */
    const val FORECAST_DAYS = 16

    /** Blocking place-name search; call on an IO dispatcher. Throws on failure. */
    fun geocode(query: String, count: Int = 5): List<GeoPlace> {
        if (query.isBlank()) return emptyList()
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO_GEOCODING) + "/v1/search?name=" +
            URLEncoder.encode(query.trim(), "UTF-8") + "&count=" + count.coerceIn(1, 10) + "&language=en&format=json"
        val root = Json.parseToJsonElement(get(url)).jsonObject
        val results = root["results"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return results.map { it.jsonObject }.map { place ->
            GeoPlace(
                name = place.getValue("name").jsonPrimitive.content,
                region = listOfNotNull(
                    place.optString("admin1"),
                    place.optString("country"),
                ).joinToString(", "),
                latitude = place.getValue("latitude").jsonPrimitive.double.toFloat(),
                longitude = place.getValue("longitude").jsonPrimitive.double.toFloat(),
                timezone = place.optString("timezone"),
            )
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.WEATHER, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            netCall.status = connection.responseCode
            return netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    internal fun parse(body: String, now: Long): WeatherInfo {
        val root = Json.parseToJsonElement(body).jsonObject
        val current = root.getValue("current").jsonObject
        val daily = root.getValue("daily").jsonObject
        return WeatherInfo(
            temperatureC = current.getValue("temperature_2m").jsonPrimitive.double,
            feelsLikeC = current.getValue("apparent_temperature").jsonPrimitive.double,
            humidityPercent = current.getValue("relative_humidity_2m").jsonPrimitive.int,
            windKmh = current.getValue("wind_speed_10m").jsonPrimitive.double,
            windDirectionDeg = current.optFirstDouble("wind_direction_10m")?.toInt() ?: 0,
            pressureHpa = current.optFirstDouble("surface_pressure") ?: -1.0,
            cloudCoverPercent = current.optFirstDouble("cloud_cover")?.toInt() ?: -1,
            precipitationMm = current.optFirstDouble("precipitation") ?: 0.0,
            weatherCode = current.getValue("weather_code").jsonPrimitive.int,
            isDay = current.getValue("is_day").jsonPrimitive.int == 1,
            highC = daily.firstDouble("temperature_2m_max"),
            lowC = daily.firstDouble("temperature_2m_min"),
            uvIndexMax = daily.optFirstOfArray("uv_index_max")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            precipProbabilityPercent = daily.optFirstOfArray("precipitation_probability_max")
                ?.jsonPrimitive?.doubleOrNull?.toInt() ?: -1,
            sunrise = daily.optFirstOfArray("sunrise")?.jsonPrimitive?.content
                ?.substringAfter('T', "").orEmpty(),
            sunset = daily.optFirstOfArray("sunset")?.jsonPrimitive?.content
                ?.substringAfter('T', "").orEmpty(),
            fetchedAtMillis = now,
            tomorrowHighC = daily.optAt("temperature_2m_max", 1),
            tomorrowLowC = daily.optAt("temperature_2m_min", 1),
            tomorrowCode = daily.optAt("weather_code", 1)?.toInt(),
            tomorrowPrecipProbabilityPercent = daily.optAt("precipitation_probability_max", 1)?.toInt(),
            hours = root["hourly"]?.takeIf { it !is JsonNull }?.jsonObject
                ?.let { outlookHours(it, current.optString("time")) }.orEmpty(),
            days = outlookDays(daily),
        )
    }

    /** The 24 hours from the current one; all of them when the response does not say what time it is. */
    private fun outlookHours(hourly: JsonObject, now: String?): List<OutlookHour> {
        val times = hourly["time"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        // ISO local times compare as text: "2026-11-16T23" is this hour.
        val from = now?.take(13)
        val out = ArrayList<OutlookHour>(OUTLOOK_HOURS)
        for (i in times.indices) {
            val time = times[i].takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: continue
            if (from != null && time.take(13) < from) continue
            val temperature = hourly.optAt("temperature_2m", i) ?: continue
            out += OutlookHour(
                time = time,
                temperatureC = temperature,
                precipProbabilityPercent = hourly.optAt("precipitation_probability", i)?.toInt() ?: -1,
                precipitationMm = hourly.optAt("precipitation", i) ?: 0.0,
                windKmh = hourly.optAt("wind_speed_10m", i) ?: 0.0,
                uvIndex = hourly.optAt("uv_index", i) ?: -1.0,
                weatherCode = hourly.optAt("weather_code", i)?.toInt() ?: -1,
                isDay = (hourly.optAt("is_day", i) ?: 1.0) >= 1.0,
            )
            if (out.size == OUTLOOK_HOURS) break
        }
        return out
    }

    private fun outlookDays(daily: JsonObject): List<OutlookDay> {
        val dates = daily["time"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return dates.indices.mapNotNull { i ->
            val date = dates[i].takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: return@mapNotNull null
            OutlookDay(
                date = date,
                highC = daily.optAt("temperature_2m_max", i) ?: return@mapNotNull null,
                lowC = daily.optAt("temperature_2m_min", i) ?: return@mapNotNull null,
                precipProbabilityPercent = daily.optAt("precipitation_probability_max", i)?.toInt() ?: -1,
                precipitationMm = daily.optAt("precipitation_sum", i) ?: 0.0,
                windMaxKmh = daily.optAt("wind_speed_10m_max", i) ?: 0.0,
                uvIndexMax = daily.optAt("uv_index_max", i) ?: -1.0,
                weatherCode = daily.optAt("weather_code", i)?.toInt() ?: -1,
            )
        }
    }

    private const val OUTLOOK_HOURS = 24

    internal fun parseHourly(body: String, latitude: Float, longitude: Float, now: Long): HourlyForecast {
        val hourly = Json.parseToJsonElement(body).jsonObject.getValue("hourly").jsonObject
        val times = hourly.getValue("time").jsonArray
        val hours = ArrayList<WeatherHour>(times.size)
        for (i in times.indices) {
            val seconds = times[i].takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull ?: continue
            // An hour with no temperature says nothing worth drawing.
            val temperature = hourly.optAt("temperature_2m", i) ?: continue
            hours.add(
                WeatherHour(
                    timeMillis = seconds.toLong() * 1000L,
                    temperatureC = temperature,
                    precipProbabilityPercent = hourly.optAt("precipitation_probability", i)?.toInt() ?: -1,
                    weatherCode = hourly.optAt("weather_code", i)?.toInt() ?: -1,
                    isDay = (hourly.optAt("is_day", i) ?: 1.0) >= 1.0,
                ),
            )
        }
        hours.sortBy { it.timeMillis }
        return HourlyForecast(latitude, longitude, hours, now)
    }

    /** The [index]th element of a daily array, absent or null tolerated. */
    private fun JsonObject.optAt(key: String, index: Int): Double? =
        this[key]?.takeIf { it !is JsonNull }?.jsonArray?.getOrNull(index)
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.optString(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** A scalar field in `current` that older responses may lack. */
    private fun JsonObject.optFirstDouble(key: String): Double? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.firstDouble(key: String): Double =
        getValue(key).jsonArray[0].jsonPrimitive.double

    private fun JsonObject.optFirstOfArray(key: String) =
        this[key]?.takeIf { it !is JsonNull }?.jsonArray?.firstOrNull()
            ?.takeIf { it !is JsonNull }

    /**
     * WMO weather-code groups to a short label. The UI resolves the id, so
     * the line follows the language the user reads the keyboard in.
     */
    @StringRes
    fun describeRes(code: Int): Int = when (code) {
        0 -> R.string.core_tools_weather_condition_clear_sky
        1 -> R.string.core_tools_weather_condition_mainly_clear
        2 -> R.string.core_tools_weather_condition_partly_cloudy
        3 -> R.string.core_tools_weather_condition_overcast
        45, 48 -> R.string.core_tools_weather_condition_fog
        51, 53, 55 -> R.string.core_tools_weather_condition_drizzle
        56, 57 -> R.string.core_tools_weather_condition_freezing_drizzle
        61, 63, 65 -> R.string.core_tools_weather_condition_rain
        66, 67 -> R.string.core_tools_weather_condition_freezing_rain
        71, 73, 75 -> R.string.core_tools_weather_condition_snow
        77 -> R.string.core_tools_weather_condition_snow_grains
        80, 81, 82 -> R.string.core_tools_weather_condition_rain_showers
        85, 86 -> R.string.core_tools_weather_condition_snow_showers
        95 -> R.string.core_tools_weather_condition_thunderstorm
        96, 99 -> R.string.core_tools_weather_condition_thunderstorm_hail
        else -> R.string.core_tools_weather_condition_unknown
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    fun toDisplay(celsius: Double, fahrenheit: Boolean): Int =
        Math.round(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).toInt()

    /** "NW" style arrow-friendly compass point for a wind direction. */
    fun windCardinal(degrees: Int): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((degrees % 360) + 360) % 360 + 22) / 45 % 8]
    }

    /**
     * Wind speed and direction in the same system as the temperature.
     *
     * The API answers in km/h and the panel printed that whatever the
     * temperature unit was, so a US user got 72°F beside 11 km/h — half
     * imperial and half metric, which is nobody's convention. The unit symbols
     * and the compass letters stay as they are: they are international.
     */
    fun formatWind(windKmh: Double, directionDeg: Int, fahrenheit: Boolean): String {
        val speed = if (fahrenheit) windKmh * KM_PER_HOUR_IN_MPH else windKmh
        val unit = if (fahrenheit) "mph" else "km/h"
        return "${Math.round(speed)} $unit ${windCardinal(directionDeg)}"
    }

    /** One km/h in mph. */
    private const val KM_PER_HOUR_IN_MPH = 0.621371
}
