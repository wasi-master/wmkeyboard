package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Umbrella
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.core.tools.WeatherHour
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.EventForecastUi
import com.wasimaster.wmkeyboard.ime.calendar.ForecastPlace
import com.wasimaster.wmkeyboard.ime.ui.calendar.CalendarText
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import java.util.TimeZone

/**
 * The Weather panel opened from the weather on a calendar event (#1212):
 * that day's hours at the event's place, each with its temperature and its
 * chance of rain, starting at the event's own hour. A tap on an hour moves
 * the summary to it; the back arrow returns to the calendar on that day.
 */
@Composable
internal fun EventForecastView(ui: EventForecastUi, height: Dp, onAction: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val zone = TimeZone.getDefault()
    val hours = remember(ui.forecast, ui.dayStart, ui.dayEnd) { ui.forecast?.between(ui.dayStart, ui.dayEnd).orEmpty() }
    var picked by remember(ui.focusMillis) { mutableLongStateOf(ui.focusMillis) }
    val shown: WeatherHour? = remember(hours, picked) {
        hours.lastOrNull { it.timeMillis <= picked } ?: hours.firstOrNull()
    }
    val unit = if (ui.fahrenheit) "°F" else "°C"
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.ime_weather_event_back_desc)
            IconHint(back) {
                IconButton(onClick = { onAction(CalendarAction.ForecastBack) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = back, tint = kb.toolbarIcon)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    ui.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
                    color = kb.modifierKeyText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val date = remember(ui.dayStart) {
                    DateUtils.formatDateTime(
                        context,
                        ui.dayStart,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
                    )
                }
                val place = forecastPlaceLabel(ui.place)
                val highLow = if (hours.isNotEmpty()) {
                    stringResource(
                        R.string.ime_weather_high_low_info,
                        WeatherClient.toDisplay(hours.maxOf { it.temperatureC }, ui.fahrenheit),
                        WeatherClient.toDisplay(hours.minOf { it.temperatureC }, ui.fahrenheit),
                    )
                } else {
                    null
                }
                Text(
                    listOfNotNull(place, date, highLow).joinToString(" · "),
                    color = kb.toolbarIcon,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val refresh = stringResource(R.string.ime_weather_refresh_desc)
            IconHint(refresh) {
                IconButton(onClick = { onAction(CalendarAction.ForecastRefresh) }, enabled = !ui.loading) {
                    if (ui.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = kb.toolbarIcon, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = refresh, tint = kb.toolbarIcon)
                    }
                }
            }
        }
        when {
            hours.isEmpty() && ui.loading -> Message(stringResource(R.string.ime_weather_progress))
            hours.isEmpty() && ui.failed -> Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Message(stringResource(R.string.ime_weather_error), Modifier)
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(CommonR.string.common_retry), icon = Icons.Outlined.Refresh, height = 32.dp) {
                    onAction(CalendarAction.ForecastRefresh)
                }
            }
            hours.isEmpty() -> Message(stringResource(R.string.ime_weather_event_no_hours))
            else -> {
                if (shown != null) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(WeatherClient.emoji(shown.weatherCode, shown.isDay), fontSize = 32.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "${WeatherClient.toDisplay(shown.temperatureC, ui.fahrenheit)}$unit · " +
                                    stringResource(WeatherClient.describeRes(shown.weatherCode)),
                                color = kb.modifierKeyText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val at = CalendarText.time(EventTimes.minuteOfDay(shown.timeMillis, zone), ui.hour24)
                            val rain = if (shown.precipProbabilityPercent >= 0) {
                                stringResource(R.string.ime_weather_event_rain_info, shown.precipProbabilityPercent)
                            } else {
                                null
                            }
                            Text(
                                listOfNotNull(at, rain).joinToString(" · "),
                                color = kb.toolbarIcon,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val list = rememberLazyListState()
                LaunchedEffect(hours, ui.focusMillis) {
                    val i = hours.indexOfLast { it.timeMillis <= ui.focusMillis }.coerceAtLeast(0)
                    list.scrollToItem((i - 1).coerceAtLeast(0))
                }
                LazyRow(
                    state = list,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(hours, key = { _, h -> h.timeMillis }) { _, hour ->
                        HourCell(
                            hour = hour,
                            label = CalendarText.time(EventTimes.minuteOfDay(hour.timeMillis, zone), ui.hour24),
                            fahrenheit = ui.fahrenheit,
                            selected = hour == shown,
                        ) { picked = hour.timeMillis }
                    }
                }
            }
        }
    }
}

/** The place a forecast is for, saying so when the user's weather location stands in for the event's. */
@Composable
internal fun forecastPlaceLabel(place: ForecastPlace): String = when {
    place.fromEvent -> place.name
    place.name.isBlank() -> stringResource(R.string.ime_weather_event_home_unnamed)
    else -> stringResource(R.string.ime_weather_event_home_place, place.name)
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** One hour of the strip: the time, the sky, the temperature and the chance of rain. */
@Composable
private fun HourCell(hour: WeatherHour, label: String, fahrenheit: Boolean, selected: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(12.dp)
    val temperature = "${WeatherClient.toDisplay(hour.temperatureC, fahrenheit)}°"
    val conditions = stringResource(WeatherClient.describeRes(hour.weatherCode))
    val rain = if (hour.precipProbabilityPercent >= 0) "${hour.precipProbabilityPercent}%" else null
    val description = stringResource(
        R.string.ime_weather_event_hour_desc,
        label,
        temperature,
        conditions,
        rain ?: "–",
    )
    Column(
        Modifier
            .width(54.dp)
            .clip(shape)
            .background(if (selected) kb.accent.copy(alpha = 0.22f) else kb.chip.copy(alpha = kb.chip.alpha * 0.6f), shape)
            .then(if (selected) Modifier.border(1.dp, kb.accent, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = kb.toolbarIcon, fontSize = 10.sp, maxLines = 1)
        Text(WeatherClient.emoji(hour.weatherCode, hour.isDay), fontSize = 18.sp)
        Text(temperature, color = kb.modifierKeyText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (rain != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Umbrella, contentDescription = null, tint = kb.toolbarIcon, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(1.dp))
                Text(rain, color = kb.toolbarIcon, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
