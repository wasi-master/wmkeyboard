package com.wasimaster.wmkeyboard.ime.ui.clock

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.DiscordStyle
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.InsertFormat
import com.wasimaster.wmkeyboard.core.clock.WorldZone
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.clock.ZoneCoordinates
import com.wasimaster.wmkeyboard.core.clock.ZoneEntry
import com.wasimaster.wmkeyboard.core.clock.utcOffsetLabel
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bedtime
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.SolarCalculator
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.clock.InsertWhen
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * World clocks. The phone's own time is the hero card on top, large; under
 * it the other cities ride a carousel of tinted tiles, warm where it is day
 * and dusky where it is night, each with its time, how far ahead or behind
 * it is, and its sun. A tap on a tile opens the ways to type that time; a
 * hold opens its chip row (type, move, rename, remove) under the carousel.
 * "Add a city" is the bar along the bottom, and the last tile.
 *
 * The clock ticks in [rememberNowState]; only the digits read it every
 * second, the rest of the tab once a minute.
 */
@Composable
internal fun ClocksTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val search = ui.search
    if (search != null) {
        SearchResults(search, ui, act)
        return
    }
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNowState(if (ui.config.showSeconds) 1000L else 15_000L)
    val minute by remember(now) { derivedStateOf { now.value / 60_000L } }
    val nowMinute = minute * 60_000L
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Re-sorted once a minute: the offsets only move at a change of daylight time.
    val zones = remember(ui.config, minute) { ui.config.shownZones(nowMinute) }
    val grid = ui.config.zonesGrid
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "home") { HomeClock(now, nowMinute, ui, act, Modifier.animateItem().popIn(0, animate)) }
            if (ui.insertZone == "") {
                item(key = "home-insert") { InsertSheet("", nowMinute, ui, act, Modifier.animateItem()) }
            }
            val onTap = { zone: WorldZone ->
                menuFor = null
                act(ClockAction.InsertSheet(zone.zoneId))
            }
            val onHold = { zone: WorldZone -> menuFor = if (menuFor == zone.zoneId) null else zone.zoneId }
            // The chip row and the insert sheet of the chosen city go right
            // under the row (or the carousel) that holds it.
            fun LazyListScope.under(ids: List<String>) {
                zones.firstOrNull { it.zoneId == menuFor && it.zoneId in ids }?.let { zone ->
                    item(key = "${zone.zoneId}-menu") { ZoneMenu(zone, ui, act, Modifier.animateItem()) }
                }
                zones.firstOrNull { it.zoneId == ui.insertZone && it.zoneId in ids }?.let { zone ->
                    item(key = "${zone.zoneId}-insert") { InsertSheet(zone.zoneId, nowMinute, ui, act, Modifier.animateItem()) }
                }
            }
            if (grid) {
                // Two to a row when asked for (#970), the Add tile last.
                val cells: List<WorldZone?> = zones + listOf<WorldZone?>(null)
                cells.chunked(2).forEachIndexed { r, pair ->
                    item(key = "row-" + pair.joinToString("|") { it?.zoneId ?: "+" }) {
                        Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { c, zone ->
                                if (zone == null) {
                                    AddTile(animate, index = r * 2 + c + 1, modifier = Modifier.weight(1f)) { act(ClockAction.SearchOpen) }
                                } else {
                                    ZoneTile(
                                        zone = zone,
                                        now = now,
                                        nowMinute = nowMinute,
                                        ui = ui,
                                        index = r * 2 + c + 1,
                                        animate = animate,
                                        highlighted = ui.insertZone == zone.zoneId || menuFor == zone.zoneId,
                                        onTap = { onTap(zone) },
                                        onHold = { onHold(zone) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    under(pair.mapNotNull { it?.zoneId })
                }
            } else {
                item(key = "cities") {
                    LazyRow(
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(zones, key = { _, z -> z.zoneId }) { i, zone ->
                            ZoneTile(
                                zone = zone,
                                now = now,
                                nowMinute = nowMinute,
                                ui = ui,
                                index = i + 1,
                                animate = animate,
                                highlighted = ui.insertZone == zone.zoneId || menuFor == zone.zoneId,
                                onTap = { onTap(zone) },
                                onHold = { onHold(zone) },
                                modifier = Modifier
                                    .animateItem()
                                    .width(TILE_WIDTH),
                            )
                        }
                        item(key = "add") {
                            AddTile(animate, index = zones.size + 1, modifier = Modifier.width(if (zones.isEmpty()) TILE_WIDTH * 2 else TILE_WIDTH)) {
                                act(ClockAction.SearchOpen)
                            }
                        }
                    }
                }
                under(zones.map { it.zoneId })
            }
            item(key = "tail") { Spacer(Modifier.height(2.dp)) }
        }
        ActionBar(
            buildList {
                add(KitAction(stringResource(R.string.ime_clock_add_city), Icons.Outlined.Add, primary = true) { act(ClockAction.SearchOpen) })
                if (zones.isNotEmpty()) {
                    add(KitAction(stringResource(R.string.ime_clock_insert_all), Icons.Outlined.ContentPaste) { act(ClockAction.InsertAllClocks) })
                }
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            height = 36.dp,
        )
    }
}

/** The hero: this phone's time, its date and city, today's sun, and a face. */
@Composable
private fun HomeClock(now: State<Long>, nowMinute: Long, ui: ClockUi, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val coords = remember(zone.id) { ZoneCoordinates.of(zone.id) }
    val sun = remember(zone.id, nowMinute / DAY_MS) { coords?.let { SolarCalculator.forDate(it.first, it.second, nowMinute, zone) } }
    val city = remember(zone.id) { IcuZoneNames().city(zone.id) ?: ZoneCatalog.fallbackCity(zone.id) }
    ClockCard(
        modifier = modifier.fillMaxWidth(),
        highlighted = ui.insertZone == "",
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        onClick = { act(ClockAction.InsertSheet("")) },
        onLongClick = { act(ClockAction.InsertDefault("")) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigTime(now, zone, ui.hour24, ui.config.showSeconds)
                Text(
                    "${ClockFormats.longDate(nowMinute, zone)} · $city",
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sun != null) SunLine(sun.sunriseMinutes, sun.sunsetMinutes, ui.hour24)
            }
            AnalogDial(
                now = now,
                zone = zone,
                day = isDay(nowMinute, zone, sun?.sunriseMinutes, sun?.sunsetMinutes),
                seconds = ui.config.showSeconds,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/** The hero digits, in their own scope: a second's tick redraws these and nothing around them. */
@Composable
private fun BigTime(now: State<Long>, zone: TimeZone, hour24: Boolean, showSeconds: Boolean) {
    val kb = LocalKbTheme.current
    val at = now.value
    Row(verticalAlignment = Alignment.Bottom) {
        Text(ClockFormats.timeDigits(at, zone, hour24), color = kb.suggestionText, style = digits(40.sp))
        if (showSeconds) {
            Text(
                ":" + two(Calendar.getInstance(zone).apply { timeInMillis = at }.get(Calendar.SECOND)),
                color = kb.secondaryText,
                style = digits(20.sp),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val ampm = ClockFormats.amPm(at, zone, hour24)
        if (ampm.isNotEmpty()) {
            Text(ampm, color = kb.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
    }
}

/**
 * A city on the carousel: its name and a sun or a moon, its time large, how
 * far from here it is, and its sunrise and sunset. Warm where it is day
 * there, dusky where it is night (see [zoneTint]), with a stripe down the
 * side in the same mood.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoneTile(
    zone: WorldZone,
    now: State<Long>,
    nowMinute: Long,
    ui: ClockUi,
    index: Int,
    animate: Boolean,
    highlighted: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tz = remember(zone.zoneId) { TimeZone.getTimeZone(zone.zoneId) }
    val home = TimeZone.getDefault()
    val coords = remember(zone) {
        val lat = zone.latitude
        val lon = zone.longitude
        if (lat != null && lon != null) lat to lon else ZoneCoordinates.of(zone.zoneId)
    }
    val sun = remember(zone.zoneId, nowMinute / DAY_MS) { coords?.let { SolarCalculator.forDate(it.first, it.second, nowMinute, tz) } }
    val city = remember(zone) { zone.label.ifEmpty { IcuZoneNames().city(zone.zoneId) ?: ZoneCatalog.fallbackCity(zone.zoneId) } }
    val shift = ClockFormats.dayShift(nowMinute, tz, home)
    val delta = ClockFormats.offsetDelta(nowMinute, tz, home)
    val dayWord = stringResource(
        when (shift) {
            1 -> R.string.ime_clock_tomorrow
            -1 -> R.string.ime_clock_yesterday
            else -> R.string.ime_clock_today
        },
    )
    val day = isDay(nowMinute, tz, sun?.sunriseMinutes, sun?.sunsetMinutes)
    val shape = kb.cardShape()
    val stripe = if (day) kb.accent else kb.secondaryText.copy(alpha = 0.55f)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .popIn(index, animate, key = zone.zoneId)
            .pressScale(source, animate)
            .heightIn(min = TILE_HEIGHT)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(if (highlighted) kb.accent.copy(alpha = 0.22f) else zoneTint(kb, day))
            .chipBorder(kb, shape)
            .drawBehind {
                val w = STRIPE.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                drawRect(stripe, Offset(x, 0f), Size(w, size.height))
            }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onLongClick = {
                    feedback()
                    onHold()
                },
            ) {
                feedback()
                onTap()
            }
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                city,
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (day) Icons.Outlined.WbSunny else Icons.Outlined.Bedtime,
                contentDescription = null,
                tint = if (day) kb.accent else kb.secondaryText,
                modifier = Modifier.size(14.dp),
            )
        }
        TileTime(now, tz, ui.hour24)
        Text(
            stringResource(R.string.ime_clock_zone_delta, dayWord, ClockFormats.deltaLabel(delta)),
            color = kb.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sun != null) SunLine(sun.sunriseMinutes, sun.sunsetMinutes, ui.hour24)
    }
}

/** A tile's time, in its own scope. */
@Composable
private fun TileTime(now: State<Long>, zone: TimeZone, hour24: Boolean) {
    val kb = LocalKbTheme.current
    val at = now.value
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
        Text(ClockFormats.timeDigits(at, zone, hour24), color = kb.suggestionText, style = digits(22.sp, FontWeight.Normal))
        val ampm = ClockFormats.amPm(at, zone, hour24)
        if (ampm.isNotEmpty()) {
            Text(ampm, color = kb.secondaryText, fontSize = 10.sp, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
        }
    }
}

/**
 * The tile's mood from the theme alone: by day the chip warmed with the
 * accent, by night the chip sunk towards the board with a trace of accent,
 * so a light theme and a dark one both read day and night at a glance.
 */
private fun zoneTint(kb: KbTheme, day: Boolean): Color {
    val base = kb.chip.copy(alpha = kb.chip.alpha * 0.75f)
    return if (day) {
        kb.accent.copy(alpha = 0.24f).compositeOver(base)
    } else {
        kb.accent.copy(alpha = 0.06f).compositeOver(kb.board.copy(alpha = 0.40f).compositeOver(base))
    }
}

/** The last tile on the carousel: a plus and "Add a city". */
@Composable
private fun AddTile(animate: Boolean, index: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .popIn(index, animate)
            .pressScale(source, animate)
            .heightIn(min = TILE_HEIGHT)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.35f))
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_clock_add_city), color = kb.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A city's own chips, under the carousel: type its time, move, rename, remove. */
@Composable
private fun ZoneMenu(zone: WorldZone, ui: ClockUi, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(
            stringResource(formatLabel(ui.insertFormat)),
            icon = Icons.Outlined.ContentPaste,
            primary = true,
            height = 30.dp,
        ) { act(ClockAction.InsertDefault(zone.zoneId)) }
        // Sorted by time, the list has no order of its own to move a city in.
        if (!ui.config.sortZonesByTime) {
            ToolPanelChip(stringResource(R.string.ime_clock_move_up_desc), icon = Icons.Outlined.KeyboardArrowUp) {
                act(ClockAction.MoveZone(zone.zoneId, -1))
            }
            ToolPanelChip(stringResource(R.string.ime_clock_move_down_desc), icon = Icons.Outlined.KeyboardArrowDown) {
                act(ClockAction.MoveZone(zone.zoneId, 1))
            }
        }
        ToolPanelChip(stringResource(R.string.ime_clock_rename_desc), icon = Icons.Outlined.Edit) {
            act(ClockAction.RenameZone(zone.zoneId))
        }
        ToolPanelChip(stringResource(R.string.ime_clock_remove_desc), icon = Icons.Outlined.Delete) {
            act(ClockAction.RemoveZone(zone.zoneId))
        }
    }
}

/** Sunrise and sunset, small, with their glyphs. */
@Composable
private fun SunLine(sunrise: Int, sunset: Int, hour24: Boolean) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
        Icon(Icons.Outlined.WbTwilight, contentDescription = stringResource(R.string.ime_clock_sunrise_desc), tint = kb.secondaryText, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(minutesText(sunrise, hour24), color = kb.secondaryText, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Bedtime, contentDescription = stringResource(R.string.ime_clock_sunset_desc), tint = kb.secondaryText, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(minutesText(sunset, hour24), color = kb.secondaryText, fontSize = 10.sp)
    }
}

/**
 * Every way to type this clock's time, each chip showing exactly what it
 * types. Tap types it; hold makes it what a hold on a clock card types, and
 * the default wears a dot. Above the chips, Now or another moment on this
 * clock: tomorrow at 12:00 here, say. Under them, Discord's own styles, which
 * each reader sees in their own time zone.
 */
@Composable
private fun InsertSheet(zoneId: String, now: Long, ui: ClockUi, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val tz = if (zoneId.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(zoneId)
    val place = remember(zoneId, ui.config.zones) {
        ui.config.zones.firstOrNull { it.zoneId == zoneId }?.label?.takeIf { it.isNotEmpty() }
            ?: IcuZoneNames().city(tz.id) ?: ZoneCatalog.fallbackCity(tz.id)
    }
    val picked = ui.insertWhen
    val at = picked?.let { ClockFormats.atLocal(now, tz, it.dayOffset, it.hour, it.minute) } ?: now
    // A picked moment stays put; the live one moves on with the minute.
    val tick = if (picked == null) now / 60_000 else at
    val relative = { t: Long -> relativeText(context, t, now) }
    Column(modifier.fillMaxWidth()) {
        WhenRow(picked, now, tz, act)
        AnimatedVisibility(visible = picked != null) {
            val w = picked ?: InsertWhen(0, 0, 0)
            TimeWheel(
                hour = w.hour,
                minute = w.minute,
                hour24 = ui.hour24,
                amLabel = remember { DateFormatSymbols.getInstance().amPmStrings[0] },
                pmLabel = remember { DateFormatSymbols.getInstance().amPmStrings[1] },
                hourDescription = stringResource(R.string.ime_clock_hour_desc),
                minuteDescription = stringResource(R.string.ime_clock_minute_desc),
                onChange = { h, m -> act(ClockAction.SetInsertWhen(w.copy(hour = h, minute = m))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Discord has a row of its own below.
            InsertFormat.entries.filter { it != InsertFormat.DISCORD }.forEach { format ->
                val text = remember(format, tick, zoneId, ui.hour24, place) {
                    ClockFormats.format(format, at, tz, ui.hour24, place, relative = relative)
                }
                FormatChip(
                    label = stringResource(formatLabel(format)),
                    text = text,
                    selected = format == ui.insertFormat,
                    onClick = { act(ClockAction.Insert(zoneId, format)) },
                    onLongClick = { act(ClockAction.SetDefaultFormat(format)) },
                )
            }
        }
        Text(
            stringResource(R.string.ime_clock_discord_header),
            color = kb.secondaryText,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 3.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DiscordStyle.entries.forEach { style ->
                val text = remember(style, tick, zoneId, ui.hour24) {
                    ClockFormats.discordPreview(at, style, tz, ui.hour24, relative)
                }
                FormatChip(
                    label = stringResource(discordLabel(style)) + " · :" + style.code,
                    text = text,
                    selected = ui.insertFormat == InsertFormat.DISCORD && style == ui.discordStyle,
                    onClick = { act(ClockAction.InsertDiscord(zoneId, style)) },
                    onLongClick = { act(ClockAction.SetDefaultDiscord(style)) },
                )
            }
        }
        Text(
            stringResource(R.string.ime_clock_insert_hint),
            color = kb.secondaryText,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 3.dp),
        )
    }
}

/**
 * Now, or a day and a time on this clock. Picking starts at the next whole
 * hour; the arrows step the day.
 */
@Composable
private fun WhenRow(picked: InsertWhen?, now: Long, tz: TimeZone, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(stringResource(R.string.ime_clock_when_now), primary = picked == null, height = 30.dp) {
            act(ClockAction.SetInsertWhen(null))
        }
        if (picked == null) {
            PillButton(stringResource(R.string.ime_clock_when_pick), height = 30.dp) {
                val hour = Calendar.getInstance(tz).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) + 1
                act(ClockAction.SetInsertWhen(InsertWhen(dayOffset = hour / 24, hour = hour % 24, minute = 0)))
            }
        } else {
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_previous_day_desc), size = 30.dp) {
                act(ClockAction.SetInsertWhen(picked.copy(dayOffset = (picked.dayOffset - 1).coerceAtLeast(-MAX_PICK_DAYS))))
            }
            Text(
                when (picked.dayOffset) {
                    0 -> stringResource(R.string.ime_clock_today)
                    1 -> stringResource(R.string.ime_clock_tomorrow)
                    -1 -> stringResource(R.string.ime_clock_yesterday)
                    else -> ClockFormats.shortDate(now + picked.dayOffset * DAY_MS, tz)
                },
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_next_day_desc), size = 30.dp) {
                act(ClockAction.SetInsertWhen(picked.copy(dayOffset = (picked.dayOffset + 1).coerceAtMost(MAX_PICK_DAYS))))
            }
        }
    }
}

/** A small label over the text a tap types. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormatChip(label: String, text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .clockChip(selected = selected, shape = kb.chipShape())
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = (if (selected) kb.chipActiveText else kb.secondaryText).copy(alpha = 0.8f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text,
            color = if (selected) kb.chipActiveText else kb.chipText,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** "now" within the minute, else Android's own "in 3 hours" / "2 days ago". */
private fun relativeText(context: android.content.Context, t: Long, now: Long): String =
    if (kotlin.math.abs(t - now) < 60_000L) {
        context.getString(R.string.ime_clock_relative_now)
    } else {
        DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

@Composable
private fun SearchResults(results: List<ZoneEntry>, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val now = rememberNow(60_000L)
    val added = remember(ui.config.zones) { ui.config.zones.map { it.zoneId }.toSet() }
    if (results.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.ime_clock_search_empty_title),
            body = stringResource(R.string.ime_clock_search_empty_body),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(results, key = { it.key }) { e ->
            val tz = remember(e.id) { TimeZone.getTimeZone(e.id) }
            val isAdded = e.id in added
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(kb.chipShape())
                    .clickable(enabled = !isAdded) { act(ClockAction.AddZone(e)) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.city, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOf(e.region, utcOffsetLabel(e.offsetMinutes)).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(ClockFormats.time(now, tz, ui.hour24), color = kb.secondaryText, style = digits(13.sp, FontWeight.Normal))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(24.dp)
                        .clockChip(selected = isAdded, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isAdded) Icons.Outlined.Check else Icons.Outlined.Add,
                        contentDescription = null,
                        tint = if (isAdded) kb.chipActiveText else kb.chipText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A small clock face: hands for the hour and the minute (and the second),
 * the face light by day and dark by night, so a glance down the list says
 * who is awake.
 */
@Composable
internal fun AnalogDial(now: State<Long>, zone: TimeZone, day: Boolean, seconds: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val face = if (day) kb.accent.copy(alpha = 0.18f) else kb.suggestionText.copy(alpha = 0.85f)
    // By night the face takes the text colour, so the hands take the board's.
    val hands = if (day) kb.suggestionText else kb.board
    val accent = kb.accent
    Box(modifier, contentAlignment = Alignment.Center) {
        // The time is read here, in the draw, so a tick redraws the hands alone.
        Canvas(Modifier.fillMaxSize()) {
            val cal = Calendar.getInstance(zone).apply { timeInMillis = now.value }
            val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
            val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
            val s = cal.get(Calendar.SECOND).toFloat()
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(face, r)
            for (i in 0 until 12) {
                val a = i / 12f * 2 * PI.toFloat()
                val inner = if (i % 3 == 0) 0.78f else 0.86f
                drawLine(
                    hands.copy(alpha = 0.45f),
                    c + polar(a, r * inner),
                    c + polar(a, r * 0.94f),
                    strokeWidth = (if (i % 3 == 0) 1.6f else 1f) * density,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(hands, c, c + polar(h / 12f * 2 * PI.toFloat(), r * 0.5f), strokeWidth = r * 0.11f, cap = StrokeCap.Round)
            drawLine(hands, c, c + polar(m / 60f * 2 * PI.toFloat(), r * 0.74f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
            if (seconds) {
                drawLine(accent, c, c + polar(s / 60f * 2 * PI.toFloat(), r * 0.8f), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
            }
            drawCircle(accent, r * 0.07f, c)
            drawCircle(hands.copy(alpha = 0.15f), r, c, style = Stroke(width = 1f * density))
        }
    }
}

private fun polar(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

/** Day between sunrise and sunset, or 6 to 18 where the table has no sun times for the zone. */
internal fun isDay(now: Long, zone: TimeZone, sunrise: Int?, sunset: Int?): Boolean {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val start = sunrise ?: (6 * 60)
    val end = sunset ?: (18 * 60)
    return minute in start until end
}

private fun minutesText(minutes: Int, hour24: Boolean): String {
    val h = (minutes / 60) % 24
    val m = minutes % 60
    return if (hour24) {
        "${two(h)}:${two(m)}"
    } else {
        val h12 = if (h % 12 == 0) 12 else h % 12
        "$h12:${two(m)}${if (h < 12) "a" else "p"}"
    }
}

internal fun two(n: Int) = n.toString().padStart(2, '0')

internal fun formatLabel(format: InsertFormat): Int = when (format) {
    InsertFormat.TIME -> R.string.ime_clock_format_time
    InsertFormat.DATE_TIME -> R.string.ime_clock_format_date_time
    InsertFormat.DATE_LONG -> R.string.ime_clock_format_date_long
    InsertFormat.TIME_PLACE -> R.string.ime_clock_format_time_place
    InsertFormat.ISO -> R.string.ime_clock_format_iso
    InsertFormat.RELATIVE -> R.string.ime_clock_format_relative
    InsertFormat.DISCORD -> R.string.ime_clock_format_discord
    InsertFormat.UNIX -> R.string.ime_clock_format_unix
}

internal fun discordLabel(style: DiscordStyle): Int = when (style) {
    DiscordStyle.SHORT_TIME -> R.string.ime_clock_discord_short_time
    DiscordStyle.LONG_TIME -> R.string.ime_clock_discord_long_time
    DiscordStyle.SHORT_DATE -> R.string.ime_clock_discord_short_date
    DiscordStyle.LONG_DATE -> R.string.ime_clock_discord_long_date
    DiscordStyle.SHORT_DATE_TIME -> R.string.ime_clock_discord_short_date_time
    DiscordStyle.LONG_DATE_TIME -> R.string.ime_clock_discord_long_date_time
    DiscordStyle.RELATIVE -> R.string.ime_clock_discord_relative
    DiscordStyle.NUMERIC -> R.string.ime_clock_discord_numeric
    DiscordStyle.NUMERIC_SECONDS -> R.string.ime_clock_discord_numeric_seconds
}

private const val DAY_MS = 86_400_000L

/** A city tile on the carousel; the Add tile alone on an empty carousel is twice as wide. */
private val TILE_WIDTH = 150.dp
private val TILE_HEIGHT = 98.dp
private val STRIPE = 3.dp

/** How far the insert sheet's day arrows go either way. */
private const val MAX_PICK_DAYS = 366
