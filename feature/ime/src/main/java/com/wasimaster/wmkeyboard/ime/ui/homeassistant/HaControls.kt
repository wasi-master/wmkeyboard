package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures
import com.wasimaster.wmkeyboard.core.homeassistant.HaInsertFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaKind
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipNext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipPrevious
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeUp
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAction
import com.wasimaster.wmkeyboard.ime.homeassistant.HaSnapshotUi
import com.wasimaster.wmkeyboard.ime.homeassistant.HaTile
import com.wasimaster.wmkeyboard.ime.ui.LocalHapticFeedback
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockSwitch
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.SectionLabel
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip
import com.wasimaster.wmkeyboard.ime.ui.clock.digits
import com.wasimaster.wmkeyboard.ime.ui.gallery.ColorRow
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The controls inside an open tile's card, by the entity's kind. */
@Composable
@Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
internal fun HaKindControls(tile: HaTile, snapshot: HaSnapshotUi?, insertFormat: HaInsertFormat, confirmRisky: Boolean, animate: Boolean, act: (HaAction) -> Unit) {
    val e = tile.entity
    val id = tile.id
    when (tile.kind) {
        HaKind.LIGHT -> {
            OnOffRow(tile, act)
            if (e.supportsBrightness) {
                SectionLabel(stringResource(R.string.ime_ha_brightness))
                HaSlider(
                    value = (e.brightnessPct ?: if (e.isOn) 100 else 0).toFloat(), range = 0f..100f, unit = "%",
                    live = { act(HaAction.Brightness(id, it.roundToInt())) }, animate = animate,
                ) { act(HaAction.Brightness(id, it.roundToInt())) }
            }
            if (e.supportsColorTemp) {
                SectionLabel(stringResource(R.string.ime_ha_color_temp))
                HaSlider(
                    value = (e.colorTempKelvin ?: ((e.minColorTempKelvin + e.maxColorTempKelvin) / 2)).toFloat(),
                    range = e.minColorTempKelvin.toFloat()..e.maxColorTempKelvin.toFloat(),
                    unit = " K",
                    track = Brush.horizontalGradient(listOf(Color(0xFFFFB46B), Color(0xFFFFE6C8), Color(0xFFCFE4FF))),
                    animate = animate,
                ) { act(HaAction.ColorTemp(id, it.roundToInt())) }
            }
            if (e.supportsColor) {
                SectionLabel(stringResource(R.string.ime_ha_color))
                HueBar(e.hsColor?.first?.toFloat()) { hue -> act(HaAction.HsColor(id, hue.toDouble(), 100.0)) }
                Spacer(Modifier.height(6.dp))
                ColorRow(colors = PRESET_COLORS, selected = e.rgbColor?.let { (r, g, b) -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b } ?: 0) { argb ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(argb, hsv)
                    act(HaAction.HsColor(id, hsv[0].toDouble(), (hsv[1] * 100).toDouble()))
                }
            }
            if (e.effectList.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_effect))
                ChipRow(e.effectList, e.effect) { act(HaAction.Effect(id, it)) }
            }
        }
        HaKind.SWITCH, HaKind.AUTOMATION -> {
            OnOffRow(tile, act)
            if (tile.kind == HaKind.AUTOMATION) {
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.ime_ha_trigger), icon = Icons.Outlined.PlayArrow) { act(HaAction.Run(id)) }
            }
        }
        HaKind.SIREN -> {
            SectionLabel(stringResource(R.string.ime_ha_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton(stringResource(R.string.ime_ha_w_on), primary = !e.isOn, hold = confirmRisky, modifier = Modifier.weight(1f)) { act(HaAction.Toggle(id, true)) }
                HoldButton(stringResource(R.string.ime_ha_w_off), primary = e.isOn, hold = false, modifier = Modifier.weight(1f)) { act(HaAction.Toggle(id, false)) }
            }
        }
        HaKind.FAN -> {
            OnOffRow(tile, act)
            if (e.supports(HaFeatures.Fan.SET_SPEED)) {
                SectionLabel(stringResource(R.string.ime_ha_speed))
                HaSlider(
                    value = (e.percentage ?: 0).toFloat(), range = 0f..100f, unit = "%", step = e.percentageStep.toFloat().coerceAtLeast(1f), animate = animate,
                ) { act(HaAction.Percentage(id, it.roundToInt())) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.supports(HaFeatures.Fan.OSCILLATE)) {
                    Text(stringResource(R.string.ime_ha_oscillate), color = LocalKbTheme.current.suggestionText, fontSize = 12.sp)
                    ClockSwitch(e.oscillating == true, stringResource(R.string.ime_ha_oscillate)) { act(HaAction.Oscillate(id, it)) }
                }
                if (e.supports(HaFeatures.Fan.DIRECTION)) {
                    Spacer(Modifier.weight(1f))
                    ChipRow(listOf("forward", "reverse"), e.direction, labels = { if (it == "forward") stringResource(R.string.ime_ha_direction_forward) else stringResource(R.string.ime_ha_direction_reverse) }) {
                        act(HaAction.FanDirection(id, it))
                    }
                }
            }
            if (e.presetModes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_preset))
                ChipRow(e.presetModes, e.presetMode) { act(HaAction.FanPreset(id, it)) }
            }
        }
        HaKind.COVER, HaKind.VALVE -> {
            val features = if (tile.kind == HaKind.COVER) HaFeatures.Cover.STOP else HaFeatures.Valve.STOP
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HoldButton(stringResource(R.string.ime_ha_open), icon = Icons.Outlined.ArrowUpward, hold = confirmRisky && tile.risky, modifier = Modifier.weight(1f)) {
                    act(HaAction.Cover(id, HaAction.CoverCommand.OPEN))
                }
                if (e.supports(features)) {
                    HoldButton(stringResource(R.string.ime_ha_stop), icon = Icons.Outlined.Stop, hold = false, modifier = Modifier.weight(1f)) {
                        act(HaAction.Cover(id, HaAction.CoverCommand.STOP))
                    }
                }
                HoldButton(stringResource(R.string.ime_ha_close), icon = Icons.Outlined.ArrowDownward, hold = confirmRisky && tile.risky, modifier = Modifier.weight(1f)) {
                    act(HaAction.Cover(id, HaAction.CoverCommand.CLOSE))
                }
            }
            val positionable = if (tile.kind == HaKind.COVER) e.supports(HaFeatures.Cover.SET_POSITION) else e.supports(HaFeatures.Valve.SET_POSITION)
            if (positionable && e.currentPosition != null) {
                SectionLabel(stringResource(R.string.ime_ha_position))
                HaSlider(value = e.currentPosition!!.toFloat(), range = 0f..100f, unit = "%", animate = animate) { act(HaAction.Position(id, it.roundToInt())) }
            }
            if (tile.kind == HaKind.COVER && e.supports(HaFeatures.Cover.SET_TILT_POSITION) && e.currentTiltPosition != null) {
                SectionLabel(stringResource(R.string.ime_ha_tilt))
                HaSlider(value = e.currentTiltPosition!!.toFloat(), range = 0f..100f, unit = "%", animate = animate) { act(HaAction.Tilt(id, it.roundToInt())) }
            }
        }
        HaKind.CLIMATE -> {
            ClimateDial(tile, animate, act)
            if (e.hvacModes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_mode))
                ChipRow(e.hvacModes, e.state, labels = { hvacLabel(it) }) { act(HaAction.HvacMode(id, it)) }
            }
            if (e.presetModes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_preset))
                ChipRow(e.presetModes, e.presetMode) { act(HaAction.Preset(id, it)) }
            }
            if (e.fanModes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_fan_mode))
                ChipRow(e.fanModes, e.fanMode) { act(HaAction.FanMode(id, it)) }
            }
        }
        HaKind.HUMIDIFIER -> {
            OnOffRow(tile, act)
            SectionLabel(stringResource(R.string.ime_ha_humidity))
            HaSlider(
                value = (e.targetHumidity ?: e.currentHumidity ?: 50.0).toFloat(), range = e.minHumidity.toFloat()..e.maxHumidity.toFloat(), unit = "%", animate = animate,
            ) { act(HaAction.Humidity(id, it.roundToInt())) }
            if (e.availableModes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_mode))
                ChipRow(e.availableModes, e.mode) { act(HaAction.Mode(id, it)) }
            }
        }
        HaKind.WATER_HEATER -> {
            if (e.supports(HaFeatures.WaterHeater.TARGET_TEMPERATURE)) {
                SectionLabel(stringResource(R.string.ime_ha_target))
                HaSlider(
                    value = (e.targetTemperature ?: e.minTemp ?: 40.0).toFloat(),
                    range = (e.minTemp ?: 30.0).toFloat()..(e.maxTemp ?: 70.0).toFloat(),
                    unit = e.temperatureUnit ?: "°", step = 1f, animate = animate,
                ) { act(HaAction.Temperature(id, it.toDouble())) }
            }
            if (e.operationList.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_mode))
                ChipRow(e.operationList, e.state) { act(HaAction.Mode(id, it)) }
            }
        }
        HaKind.MEDIA -> MediaControls(tile, act)
        HaKind.LOCK -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HoldButton(stringResource(R.string.ime_ha_unlock), primary = e.state == "locked", hold = confirmRisky, modifier = Modifier.weight(1f)) { act(HaAction.Unlock(id)) }
                HoldButton(stringResource(R.string.ime_ha_lock), primary = e.state != "locked", hold = confirmRisky, modifier = Modifier.weight(1f)) { act(HaAction.Lock(id)) }
            }
            if (e.supports(HaFeatures.Lock.OPEN)) {
                Spacer(Modifier.height(8.dp))
                HoldButton(stringResource(R.string.ime_ha_open_lock), hold = confirmRisky, modifier = Modifier.fillMaxWidth()) { act(HaAction.OpenLock(id)) }
            }
        }
        HaKind.ALARM -> {
            HoldButton(stringResource(R.string.ime_ha_disarm), primary = e.state != "disarmed", hold = confirmRisky, modifier = Modifier.fillMaxWidth()) { act(HaAction.Disarm(id)) }
            Spacer(Modifier.height(8.dp))
            val modes = HaActions.armModes(e).ifEmpty { listOf(HaActions.ArmMode.HOME, HaActions.ArmMode.AWAY) }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    HoldButton(armLabel(mode), primary = e.state == mode.state, hold = confirmRisky) { act(HaAction.Arm(id, mode)) }
                }
            }
        }
        HaKind.VACUUM -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val cleaning = e.state == "cleaning"
                RoundButton(if (cleaning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(if (cleaning) R.string.ime_ha_vacuum_pause else R.string.ime_ha_vacuum_start), primary = true, size = 40.dp) {
                    act(HaAction.Vacuum(id, if (cleaning) HaActions.VacuumCommand.PAUSE else HaActions.VacuumCommand.START))
                }
                RoundButton(Icons.Outlined.Stop, stringResource(R.string.ime_ha_vacuum_stop), size = 40.dp) { act(HaAction.Vacuum(id, HaActions.VacuumCommand.STOP)) }
                PillButton(stringResource(R.string.ime_ha_vacuum_return), height = 40.dp) { act(HaAction.Vacuum(id, HaActions.VacuumCommand.RETURN)) }
                if (e.supports(HaFeatures.Vacuum.LOCATE)) PillButton(stringResource(R.string.ime_ha_vacuum_locate), height = 40.dp) { act(HaAction.Vacuum(id, HaActions.VacuumCommand.LOCATE)) }
            }
            if (e.fanSpeedList.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ime_ha_fan_speed))
                ChipRow(e.fanSpeedList, e.fanSpeed) { act(HaAction.VacuumSpeed(id, it)) }
            }
        }
        HaKind.NUMBER -> {
            val min = (e.min ?: 0.0).toFloat()
            val max = (e.max ?: 100.0).toFloat()
            HaSlider(value = (e.numeric ?: min.toDouble()).toFloat(), range = min..max, unit = e.unit?.let { " $it" } ?: "", step = (e.step ?: 1.0).toFloat(), animate = animate) {
                act(HaAction.Value(id, it.toDouble()))
            }
        }
        HaKind.SELECT -> ChipRow(e.options, e.state) { act(HaAction.Option(id, it)) }
        HaKind.TEXT -> {
            Text(e.state.ifBlank { "—" }, color = LocalKbTheme.current.suggestionText, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.ime_ha_edit_text), icon = Icons.Outlined.Edit) { act(HaAction.EditText(id)) }
        }
        HaKind.BUTTON -> PillButton(stringResource(R.string.ime_ha_press), icon = Icons.Outlined.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { act(HaAction.Tap(id)) }
        HaKind.SCENE, HaKind.SCRIPT -> PillButton(stringResource(R.string.ime_ha_run), icon = Icons.Outlined.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { act(HaAction.Run(id)) }
        HaKind.CAMERA -> CameraBlock(tile, snapshot, act)
        HaKind.SENSOR, HaKind.BINARY_SENSOR, HaKind.WEATHER, HaKind.PERSON, HaKind.OTHER -> SensorBlock(tile, insertFormat, animate, act)
    }
}

@Composable
private fun OnOffRow(tile: HaTile, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(tile.stateLine, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        ClockSwitch(tile.shownOn, stringResource(R.string.ime_ha_tile_switch_desc, tile.name)) { act(HaAction.Toggle(tile.id, it)) }
    }
}

/**
 * A pill slider drawn in the theme's colours: the fill is the accent (or a
 * gradient, for colour temperature), the thumb grows while the finger is
 * down, and every five per cent ticks the haptic. [live] fires while dragging,
 * throttled; [onDone] once, when the finger lifts.
 */
@Composable
@Suppress("detekt.LongParameterList")
internal fun HaSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    step: Float = 1f,
    track: Brush? = null,
    live: ((Float) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") animate: Boolean = true,
    onDone: (Float) -> Unit,
) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var local by remember(value) { mutableFloatStateOf(value) }
    var lastTick by remember { mutableIntStateOf(-1) }
    val done by rememberUpdatedState(onDone)
    val liveCb by rememberUpdatedState(live)
    val span = (range.endInclusive - range.start).coerceAtLeast(0.001f)
    fun snap(v: Float): Float = (Math.round((v - range.start) / step) * step + range.start).coerceIn(range.start, range.endInclusive)
    LaunchedEffect(dragging, local) {
        if (dragging && liveCb != null) {
            delay(150)
            liveCb?.invoke(snap(local))
        }
    }
    val shown = snap(local)
    val fraction = ((shown - range.start) / span).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(if (dragging) 34.dp else 30.dp)
                .pointerInput(range) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            local = range.start + (pos.x / width).coerceIn(0f, 1f) * span
                        },
                        onDragEnd = {
                            dragging = false
                            done(snap(local))
                        },
                        onDragCancel = { dragging = false },
                        onDrag = { change, _ ->
                            local = range.start + (change.position.x / width).coerceIn(0f, 1f) * span
                            val tick = (((local - range.start) / span) * 20).toInt()
                            if (tick != lastTick) {
                                lastTick = tick
                                haptic()
                            }
                        },
                    )
                }
                .pointerInput(range) {
                    detectTapGestures { pos ->
                        local = range.start + (pos.x / width).coerceIn(0f, 1f) * span
                        haptic()
                        done(snap(local))
                    }
                },
        ) {
            width = size.width
            val r = size.height / 2
            drawRoundRect(kb.chip, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            val fillWidth = (size.width * fraction).coerceAtLeast(r * 2)
            if (track != null) {
                drawRoundRect(track, size = Size(size.width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), alpha = 0.55f)
                drawRoundRect(track, size = Size(fillWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            } else {
                drawRoundRect(kb.accent.copy(alpha = 0.85f), size = Size(fillWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            }
            val thumbR = if (dragging) r - 2.dp.toPx() else r - 5.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.95f), radius = thumbR, center = Offset(fillWidth - r, r))
            drawCircle(kb.accent, radius = thumbR, center = Offset(fillWidth - r, r), style = Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = (if (step >= 1f) shown.roundToInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", shown)) + unit,
            color = kb.suggestionText,
            style = digits(13.sp, FontWeight.Medium),
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** A hue strip; a tap or a drag picks the hue at full saturation. */
@Composable
private fun HueBar(hue: Float?, onPick: (Float) -> Unit) {
    val kb = LocalKbTheme.current
    var width by remember { mutableFloatStateOf(1f) }
    var local by remember(hue) { mutableFloatStateOf(hue ?: 0f) }
    val pick by rememberUpdatedState(onPick)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ -> local = (change.position.x / width).coerceIn(0f, 1f) * 360f },
                    onDragEnd = { pick(local) },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    local = (pos.x / width).coerceIn(0f, 1f) * 360f
                    pick(local)
                }
            },
    ) {
        width = size.width
        val r = size.height / 2
        drawRoundRect(Brush.horizontalGradient(HUE_STOPS), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        val x = (local / 360f) * size.width
        drawCircle(Color.White, radius = r - 3.dp.toPx(), center = Offset(x.coerceIn(r, size.width - r), r))
        drawCircle(kb.board, radius = r - 3.dp.toPx(), center = Offset(x.coerceIn(r, size.width - r), r), style = Stroke(2.dp.toPx()))
    }
}

/** A row of options as chips; the current one lit. [labels] translates a raw option name. */
@Composable
internal fun ChipRow(options: List<String>, selected: String?, labels: @Composable (String) -> String = { it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, onPick: (String) -> Unit) {
    val kb = LocalKbTheme.current
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .clockChip(selected = on)
                    .clickable { onPick(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(labels(option), color = if (on) kb.chipActiveText else kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * A button that, when [hold] is on, fires only after the finger has stayed
 * down long enough for the ring to fill. Without [hold] it is a pill button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HoldButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean = false,
    hold: Boolean,
    onFire: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val state = rememberHold(hold) {
        haptic()
        onFire()
    }
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .height(40.dp)
            .clockChip(selected = primary, shape = shape)
            .holdRing(state, kb.accent, kb.chipRadiusDp.dp)
            .then(if (hold) state.modifier else Modifier.combinedClickable(onClick = onFire))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary) kb.chipActiveText else kb.chipText
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    if (hold && state.pressed) {
        Text(stringResource(R.string.ime_ha_hold_to_confirm), color = kb.secondaryText, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

/**
 * The thermostat: a three-quarter arc from the lowest to the highest setting,
 * filled to the target in the action's colour (orange heating, blue cooling),
 * the current temperature as a dot on the arc, the target large in the middle
 * with minus and plus either side. The digits roll rather than jump.
 */
@Composable
private fun ClimateDial(tile: HaTile, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val e = tile.entity
    val min = (e.minTemp ?: 7.0).toFloat()
    val max = (e.maxTemp ?: 35.0).toFloat()
    val step = (e.targetTempStep ?: 0.5).toFloat()
    val target = (e.targetTemperature ?: e.targetTempLow ?: e.currentTemperature ?: min.toDouble()).toFloat()
    val current = e.currentTemperature?.toFloat()
    val unit = e.temperatureUnit ?: "°"
    val color = hvacColor(e.hvacAction, kb)
    val shownTarget = rollingValue(target, animate)
    val fraction = ((shownTarget - min) / (max - min).coerceAtLeast(0.1f)).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RoundButton(Icons.Outlined.Remove, "−", size = 40.dp) { act(HaAction.Temperature(tile.id, (target - step).coerceAtLeast(min).toDouble())) }
        Box(Modifier.weight(1f).aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                val stroke = 10.dp.toPx()
                val diameter = minOf(size.width, size.height * 1.5f) - stroke
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter * 0.8f) / 2 - stroke / 2)
                val arcSize = Size(diameter, diameter)
                drawArc(kb.divider, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawArc(color, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                if (current != null) {
                    val f = ((current - min) / (max - min).coerceAtLeast(0.1f)).coerceIn(0f, 1f)
                    val angle = Math.toRadians((135f + 270f * f).toDouble())
                    val r = diameter / 2
                    val c = Offset(topLeft.x + r, topLeft.y + r)
                    drawCircle(kb.board, radius = stroke * 0.7f, center = Offset(c.x + r * kotlin.math.cos(angle).toFloat(), c.y + r * kotlin.math.sin(angle).toFloat()))
                    drawCircle(kb.suggestionText, radius = stroke * 0.45f, center = Offset(c.x + r * kotlin.math.cos(angle).toFloat(), c.y + r * kotlin.math.sin(angle).toFloat()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(java.util.Locale.getDefault(), if (step < 1f) "%.1f" else "%.0f", shownTarget) + unit,
                    color = kb.suggestionText,
                    style = digits(28.sp, FontWeight.Light),
                )
                if (current != null) {
                    Text(
                        stringResource(R.string.ime_ha_current) + " " + String.format(java.util.Locale.getDefault(), "%.1f", current) + unit,
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        RoundButton(Icons.Outlined.Add, "+", size = 40.dp, primary = true) { act(HaAction.Temperature(tile.id, (target + step).coerceAtMost(max).toDouble())) }
    }
}

@Composable
private fun MediaControls(tile: HaTile, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val e = tile.entity
    val id = tile.id
    val title = e.mediaTitle?.takeIf { it.isNotBlank() }
    if (title != null) {
        Text(title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        listOfNotNull(e.mediaArtist, e.mediaAlbum ?: e.mediaSeries).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e.supports(HaFeatures.Media.PREVIOUS_TRACK)) RoundButton(Icons.Outlined.SkipPrevious, stringResource(R.string.ime_ha_previous), size = 40.dp) { act(HaAction.Media(id, HaAction.MediaCommand.PREVIOUS)) }
        RoundButton(if (e.state == "playing") Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(R.string.ime_ha_play_pause), primary = true, size = 48.dp) {
            act(HaAction.Media(id, HaAction.MediaCommand.PLAY_PAUSE))
        }
        if (e.supports(HaFeatures.Media.NEXT_TRACK)) RoundButton(Icons.Outlined.SkipNext, stringResource(R.string.ime_ha_next), size = 40.dp) { act(HaAction.Media(id, HaAction.MediaCommand.NEXT)) }
        if (e.supports(HaFeatures.Media.TURN_ON) && e.supports(HaFeatures.Media.TURN_OFF)) {
            Spacer(Modifier.weight(1f))
            ClockSwitch(e.state != "off", stringResource(R.string.ime_ha_tile_switch_desc, tile.name)) { act(HaAction.Toggle(id, it)) }
        }
    }
    if (e.supports(HaFeatures.Media.VOLUME_SET)) {
        SectionLabel(stringResource(R.string.ime_ha_volume))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.supports(HaFeatures.Media.VOLUME_MUTE)) {
                RoundButton(if (e.isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, stringResource(R.string.ime_ha_mute), size = 32.dp, primary = e.isMuted) { act(HaAction.Mute(id, !e.isMuted)) }
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.weight(1f)) {
                HaSlider(value = ((e.volumeLevel ?: 0.0) * 100).toFloat(), range = 0f..100f, unit = "%") { act(HaAction.Volume(id, it / 100.0)) }
            }
        }
    }
    if (e.sourceList.isNotEmpty() && e.supports(HaFeatures.Media.SELECT_SOURCE)) {
        SectionLabel(stringResource(R.string.ime_ha_source))
        ChipRow(e.sourceList, e.source, labels = { it }) { act(HaAction.Source(id, it)) }
    }
}

@Composable
private fun CameraBlock(tile: HaTile, snapshot: HaSnapshotUi?, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val bytes = snapshot?.takeIf { it.entityId == tile.id }?.bytes
    val bitmap = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .clickable { act(HaAction.Snapshot(tile.id)) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = tile.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            snapshot?.loading == true -> Text(stringResource(R.string.ime_ha_snapshot_loading), color = kb.secondaryText, fontSize = 12.sp)
            snapshot?.error != null -> Text(snapshot.error, color = kb.secondaryText, fontSize = 12.sp)
            else -> Icon(tile.glyph.vector(), contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(32.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(stringResource(R.string.ime_ha_snapshot_refresh), icon = Icons.Outlined.Refresh) { act(HaAction.Snapshot(tile.id)) }
        if (bitmap != null) PillButton(stringResource(R.string.ime_ha_snapshot_send), primary = true) { act(HaAction.SendSnapshot(tile.id)) }
    }
}

/** A read-only entity: the figure large, the unit, and the three ways to type it; hold a chip to make it the default. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SensorBlock(tile: HaTile, insertFormat: HaInsertFormat, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val e = tile.entity
    Row(verticalAlignment = Alignment.Bottom) {
        com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade(tile.value ?: tile.stateLine, animate, label = "haValue") { v ->
            Text(v, color = kb.suggestionText, style = digits(30.sp, FontWeight.Light))
        }
        if (tile.value != null && !e.unit.isNullOrBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(e.unit.orEmpty(), color = kb.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
    if (e.lastChanged > 0) {
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString((e.lastChanged * 1000).toLong(), System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        Text(stringResource(R.string.ime_ha_last_changed, ago), color = kb.secondaryText, fontSize = 11.sp)
    }
    Spacer(Modifier.height(8.dp))
    SectionLabel(stringResource(R.string.ime_ha_insert))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HaInsertFormat.entries.forEach { f ->
            val label = when (f) {
                HaInsertFormat.VALUE -> stringResource(R.string.ime_ha_insert_value)
                HaInsertFormat.VALUE_UNIT -> stringResource(R.string.ime_ha_insert_value_unit)
                HaInsertFormat.NAME_VALUE -> stringResource(R.string.ime_ha_insert_name_value)
            }
            Box(
                modifier = Modifier
                    .clockChip(selected = f == insertFormat)
                    .combinedClickable(onClick = { act(HaAction.Insert(tile.id, f)) }, onLongClick = { act(HaAction.DefaultInsertFormat(f)) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(label, color = if (f == insertFormat) kb.chipActiveText else kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Text(stringResource(R.string.ime_ha_insert_default_hint), color = kb.secondaryText, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun hvacLabel(mode: String): String = stringResource(
    when (mode) {
        "off" -> R.string.ime_ha_w_off
        "heat" -> R.string.ime_ha_w_heat
        "cool" -> R.string.ime_ha_w_cool
        "heat_cool" -> R.string.ime_ha_w_heat_cool
        "auto" -> R.string.ime_ha_w_auto
        "dry" -> R.string.ime_ha_w_dry
        "fan_only" -> R.string.ime_ha_w_fan_only
        else -> R.string.ime_ha_w_unknown
    },
)

@Composable
private fun armLabel(mode: HaActions.ArmMode): String = stringResource(
    when (mode) {
        HaActions.ArmMode.HOME -> R.string.ime_ha_arm_home
        HaActions.ArmMode.AWAY -> R.string.ime_ha_arm_away
        HaActions.ArmMode.NIGHT -> R.string.ime_ha_arm_night
        HaActions.ArmMode.VACATION -> R.string.ime_ha_arm_vacation
        HaActions.ArmMode.CUSTOM -> R.string.ime_ha_arm_custom
    },
)

private val HUE_STOPS = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Suppress("detekt.MagicNumber")
private val PRESET_COLORS: List<Int> = listOf(
    0xFFFFFFFF.toInt(), 0xFFFFE4B5.toInt(), 0xFFFFC107.toInt(), 0xFFFF7043.toInt(), 0xFFF44336.toInt(), 0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(), 0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF00BCD4.toInt(), 0xFF4CAF50.toInt(), 0xFFCDDC39.toInt(),
)

/** Text on the accent for a filled control. */
@Suppress("unused")
internal fun onAccent(kb: com.wasimaster.wmkeyboard.ime.ui.KbTheme): Color = maxContrastOn(kb.accent)
