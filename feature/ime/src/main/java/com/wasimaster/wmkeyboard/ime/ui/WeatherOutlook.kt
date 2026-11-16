package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.tools.OutlookDay
import com.wasimaster.wmkeyboard.core.tools.OutlookHour
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.core.tools.WeatherInfo
import com.wasimaster.wmkeyboard.ime.R
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Weather panel's outlook (#1369), under the current conditions: the next
 * 24 hours as a strip of columns, then the week a day to a row. Each gives
 * the temperature, the chance of rain and how much, the wind, the UV index
 * and the air quality index (the US one for °F, the European one for °C),
 * whichever the forecast carried.
 */
@Composable
internal fun WeatherOutlook(info: WeatherInfo, fahrenheit: Boolean) {
    if (info.hours.isEmpty() && info.days.isEmpty()) return
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val hour24 = remember { android.text.format.DateFormat.is24HourFormat(context) }
    if (info.hours.isNotEmpty()) {
        OutlookHeading(stringResource(R.string.ime_weather_outlook_hours))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            items(info.hours, key = { it.time }) { hour -> HourColumn(hour, fahrenheit, hour24) }
        }
    }
    if (info.days.isNotEmpty()) {
        OutlookHeading(stringResource(R.string.ime_weather_outlook_days))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(kb.cardShape())
                .background(kb.chip),
        ) {
            info.days.forEachIndexed { i, day -> DayRow(day, fahrenheit, today = i == 0) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun OutlookHeading(text: String) {
    val kb = LocalKbTheme.current
    Text(
        text,
        color = kb.toolbarIcon,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun HourColumn(hour: OutlookHour, fahrenheit: Boolean, hour24: Boolean) {
    val kb = LocalKbTheme.current
    val unit = "°"
    Column(
        Modifier
            .width(58.dp)
            .clip(kb.cardShape())
            .background(kb.chip)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(hourLabel(hour.minuteOfDay, hour24), color = kb.toolbarIcon, fontSize = 10.sp, maxLines = 1)
        Text(WeatherClient.emoji(hour.weatherCode, hour.isDay), fontSize = 18.sp)
        Text(
            "${WeatherClient.toDisplay(hour.temperatureC, fahrenheit)}$unit",
            color = kb.modifierKeyText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Small(rainText(hour.precipProbabilityPercent, hour.precipitationMm, fahrenheit))
        Small("💨 " + speedText(hour.windKmh, fahrenheit))
        if (hour.uvIndex >= 0) Small(stringResource(R.string.ime_weather_outlook_uv, uvText(hour.uvIndex)))
        hour.airQuality?.shown(fahrenheit)?.takeIf { it >= 0 }?.let { Small(stringResource(R.string.ime_weather_outlook_aqi, it)) }
    }
}

@Composable
private fun DayRow(day: OutlookDay, fahrenheit: Boolean, today: Boolean) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (today) stringResource(R.string.ime_weather_outlook_today) else dayLabel(day.date),
            color = kb.modifierKeyText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(52.dp),
        )
        Text(WeatherClient.emoji(day.weatherCode, true), fontSize = 16.sp, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center)
        Text(
            "${WeatherClient.toDisplay(day.highC, fahrenheit)}° / ${WeatherClient.toDisplay(day.lowC, fahrenheit)}°",
            color = kb.modifierKeyText,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(66.dp),
        )
        val parts = buildList {
            add(rainText(day.precipProbabilityPercent, day.precipitationMm, fahrenheit))
            add("💨 " + speedText(day.windMaxKmh, fahrenheit))
            if (day.uvIndexMax >= 0) add(stringResource(R.string.ime_weather_outlook_uv, uvText(day.uvIndexMax)))
            day.airQuality?.shown(fahrenheit)?.takeIf { it >= 0 }?.let { add(stringResource(R.string.ime_weather_outlook_aqi, it)) }
        }
        Text(
            parts.joinToString("  "),
            color = kb.toolbarIcon,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Small(text: String) {
    Text(text, color = LocalKbTheme.current.toolbarIcon, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "☔ 40% 1.2 mm", or the inches for °F; just the chance when nothing is expected to fall. */
private fun rainText(chance: Int, mm: Double, fahrenheit: Boolean): String {
    val amount = when {
        mm <= 0.0 -> ""
        fahrenheit -> " " + String.format(Locale.getDefault(), "%.2f in", mm / MM_PER_INCH)
        else -> " " + String.format(Locale.getDefault(), "%.1f mm", mm)
    }
    return "☔ " + (if (chance >= 0) "$chance%" else "") + amount
}

/** Wind in the temperature's system, as the current conditions show it. */
private fun speedText(kmh: Double, fahrenheit: Boolean): String =
    if (fahrenheit) "${(kmh * MPH_PER_KMH).roundToInt()} mph" else "${kmh.roundToInt()} km/h"

private fun uvText(uv: Double): String = String.format(Locale.getDefault(), "%.0f", uv)

private fun hourLabel(minuteOfDay: Int, hour24: Boolean): String {
    val h = minuteOfDay / 60
    return if (hour24) String.format(Locale.getDefault(), "%02d:00", h) else "${(h + 11) % 12 + 1} ${if (h < 12) "AM" else "PM"}"
}

/** "Tue 18" from `2026-11-18`, in the phone's language. */
private fun dayLabel(date: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return date
    SimpleDateFormat("EEE d", Locale.getDefault()).format(parsed)
}.getOrDefault(date)

private const val MM_PER_INCH = 25.4
private const val MPH_PER_KMH = 0.621371
