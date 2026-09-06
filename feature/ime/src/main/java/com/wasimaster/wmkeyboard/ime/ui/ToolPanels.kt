package com.wasimaster.wmkeyboard.ime.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconPack
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.DefaultThemesPanelBuiltIns
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.IconSettings
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.settings.ThemeSelectionTarget
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.modeThemeOwner
import com.wasimaster.wmkeyboard.core.settings.slotThemeId
import com.wasimaster.wmkeyboard.core.settings.themeSelectionTarget
import com.wasimaster.wmkeyboard.app.ThemePreview
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.AltCalendars
import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.MoonPhase
import com.wasimaster.wmkeyboard.core.tools.Qibla
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.SoundHapticAction
import com.wasimaster.wmkeyboard.ime.WeatherUi
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.fallbackLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- shared bits ----

/** Panel-sized key in the theme's modifier-key colors (same look as TextEditPanel). */
@Composable
private fun ToolPanelKey(
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    repeatable: Boolean = false,
    onAction: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    val shape = kb.keyShape()
    // Pressed tint, since these keys sit outside the main grid's press
    // machinery — without it a held numpad digit gives no sign it landed.
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) kb.pressedKey else kb.modifierKey, shape)
            .panelKeyBorder(kb, shape)
            .pointerInput(repeatable) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onAction()
                        var repeat: Job? = null
                        if (repeatable) {
                            repeat = scope.launch {
                                delay(400)
                                while (true) {
                                    onAction()
                                    delay(120)
                                }
                            }
                        }
                        tryAwaitRelease()
                        repeat?.cancel()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = if (label == null) description else null,
                    modifier = Modifier.size(22.dp), tint = kb.modifierKeyText)
            }
            if (label != null) {
                Text(label, color = kb.modifierKeyText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PanelMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

// ---- compass ----

/**
 * Live compass from the rotation-vector sensor: a rotating rose with a
 * fixed needle at the top, a degree/cardinal readout, and optionally the
 * qibla direction computed from the saved weather location.
 */
@Composable
internal fun CompassPanel(state: KeyboardUiState) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    // Raw sensor heading; the displayed azimuth chases it per frame below.
    var target by remember { mutableFloatStateOf(0f) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var hasReading by remember { mutableStateOf(false) }

    DisposableEffect(sensor) {
        if (sensor == null) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                target = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                hasReading = true
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Sensor events land at irregular ~20-60ms steps, which reads as jitter
    // when the rose snaps to each one. Instead the display chases the latest
    // reading with a time-based exponential pull evaluated every frame, so
    // the rotation glides at the display's refresh rate. Wraparound-aware
    // (359°→1° takes the short way).
    LaunchedEffect(sensor, hasReading) {
        if (sensor == null || !hasReading) return@LaunchedEffect
        azimuth = target
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                    val diff = ((target - azimuth + 540f) % 360f) - 180f
                    azimuth = (azimuth + diff * (1f - exp(-dt * 10f)) + 360f) % 360f
                }
                last = now
            }
        }
    }

    if (sensor == null) {
        Box(Modifier.fillMaxSize()) {
            PanelMessage(stringResource(R.string.ime_compass_no_sensor_empty))
        }
        return
    }

    val latitude = state.settings.weatherLatitude
    val longitude = state.settings.weatherLongitude
    val qiblaBearing = if (state.settings.compassShowQibla && latitude != null && longitude != null) {
        Qibla.bearing(latitude.toDouble(), longitude.toDouble()).toFloat()
    } else null

    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(kb.modifierKey, radius, center)
            rotate(-azimuth, center) {
                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = 13.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                for (deg in 0 until 360 step 15) {
                    val major = deg % 90 == 0
                    val angle = Math.toRadians(deg.toDouble())
                    val dir = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
                    val outer = center + dir * radius
                    val inner = center + dir * (radius - if (major) 12.dp.toPx() else 6.dp.toPx())
                    drawLine(
                        color = if (deg == 0) kb.accent else kb.toolbarIcon,
                        start = inner,
                        end = outer,
                        strokeWidth = if (major) 3f else 1.5f,
                    )
                    if (major) {
                        val label = when (deg) {
                            0 -> "N"; 90 -> "E"; 180 -> "S"; else -> "W"
                        }
                        textPaint.color = (if (deg == 0) kb.accent else kb.modifierKeyText).toArgb()
                        val pos = center + dir * (radius - 22.dp.toPx())
                        drawContext.canvas.nativeCanvas.apply {
                            save()
                            // Keep letters upright: undo the rose rotation at
                            // the letter's own position.
                            rotate(azimuth, pos.x, pos.y)
                            drawText(label, pos.x, pos.y + textPaint.textSize / 3f, textPaint)
                            restore()
                        }
                    }
                }
                // Qibla marker rotates with the rose so it points at the
                // real-world bearing.
                if (qiblaBearing != null) {
                    val angle = Math.toRadians(qiblaBearing.toDouble())
                    val dir = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
                    val pos = center + dir * (radius - 34.dp.toPx())
                    drawLine(kb.accent, center + dir * (radius - 12.dp.toPx()), center + dir * radius, 5f)
                    val qiblaPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 14.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.apply {
                        save()
                        rotate(azimuth, pos.x, pos.y)
                        drawText("🕋", pos.x, pos.y + qiblaPaint.textSize / 3f, qiblaPaint)
                        restore()
                    }
                }
            }
            // Fixed needle marking the device's facing direction.
            val needle = Path().apply {
                moveTo(center.x, center.y - radius + 2.dp.toPx())
                lineTo(center.x - 6.dp.toPx(), center.y - radius + 16.dp.toPx())
                lineTo(center.x + 6.dp.toPx(), center.y - radius + 16.dp.toPx())
                close()
            }
            drawPath(needle, kb.accent)
            drawCircle(kb.accent, 4.dp.toPx(), center)
        }
        if (state.settings.compassShowDegrees) {
            val shown = azimuth.roundToInt() % 360
            Text(
                if (hasReading) {
                    "$shown°  ${cardinal(shown)}"
                } else {
                    stringResource(R.string.ime_compass_calibrating_progress)
                },
                color = kb.modifierKeyText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state.settings.compassShowQibla) {
            Text(
                if (qiblaBearing != null) {
                    stringResource(
                        R.string.ime_compass_qibla_info,
                        qiblaBearing.roundToInt(),
                        cardinal(qiblaBearing.roundToInt()),
                    )
                } else {
                    stringResource(R.string.ime_compass_qibla_no_location_info)
                },
                color = kb.toolbarIcon,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

private fun cardinal(degrees: Int): String {
    val names = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    return names[((degrees % 360 + 360) % 360 + 11) / 22 % 16]
}

// ---- bubble level ----

/** Bubble level from the accelerometer; turns accent-colored when flat. */
@Composable
internal fun LevelPanel(state: KeyboardUiState) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val sensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    // Raw sensor gravity; the displayed values chase it per frame below.
    var rawX by remember { mutableFloatStateOf(0f) }
    var rawY by remember { mutableFloatStateOf(0f) }
    var rawZ by remember { mutableFloatStateOf(9.81f) }
    var gx by remember { mutableFloatStateOf(0f) }
    var gy by remember { mutableFloatStateOf(0f) }
    var gz by remember { mutableFloatStateOf(9.81f) }

    DisposableEffect(sensor) {
        if (sensor == null) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                rawX = event.values[0]
                rawY = event.values[1]
                rawZ = event.values[2]
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Same trick as the compass: a per-frame, time-based low-pass instead of
    // per-event smoothing, so the bubble drifts smoothly at the display's
    // refresh rate rather than twitching on every sensor sample.
    LaunchedEffect(sensor) {
        if (sensor == null) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                    val k = 1f - exp(-dt * 8f)
                    gx += (rawX - gx) * k
                    gy += (rawY - gy) * k
                    gz += (rawZ - gz) * k
                }
                last = now
            }
        }
    }

    if (sensor == null) {
        Box(Modifier.fillMaxSize()) {
            PanelMessage(stringResource(R.string.ime_level_no_sensor_empty))
        }
        return
    }

    val kb = LocalKbTheme.current
    val pitch = Math.toDegrees(atan2(gy.toDouble(), sqrt((gx * gx + gz * gz).toDouble())))
    val roll = Math.toDegrees(atan2(gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble())))
    val flat = abs(pitch) < 1.0 && abs(roll) < 1.0

    // One axis (pitch or roll) is "in the zone" within ~1°; a tube vial turns
    // accent when its own axis is level, independent of the bullseye's flat.
    fun axisLevel(g: Float) = abs(g / 9.81f) < 0.02f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(kb.modifierKey, radius, center)
                drawCircle(kb.toolbarIcon, radius * 0.25f, center, style = Stroke(1.5f))
                drawCircle(kb.toolbarIcon, radius * 0.65f, center, style = Stroke(1.5f))
                drawLine(kb.toolbarIcon, center - Offset(radius, 0f), center + Offset(radius, 0f), 1f)
                drawLine(kb.toolbarIcon, center - Offset(0f, radius), center + Offset(0f, radius), 1f)
                // The bubble floats toward the raised side, like a real vial.
                val range = radius - 10.dp.toPx()
                val bx = (-gx / 9.81f).coerceIn(-1f, 1f) * range
                val by = (-gy / 9.81f).coerceIn(-1f, 1f) * range
                val length = sqrt(bx * bx + by * by)
                val clamped = if (length > range) Offset(bx / length * range, by / length * range)
                    else Offset(bx, by)
                drawCircle(
                    if (flat) kb.accent else kb.modifierKeyText,
                    10.dp.toPx(),
                    center + Offset(-clamped.x, clamped.y),
                )
            }
            Spacer(Modifier.width(8.dp))
            // Vertical tube vial: pitch only. Bubble rides toward the raised end.
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(30.dp),
            ) {
                val cx = size.width / 2f
                val pad = 8.dp.toPx()
                val thickness = (size.width - 6.dp.toPx()).coerceAtLeast(6.dp.toPx())
                drawLine(
                    kb.modifierKey, Offset(cx, pad), Offset(cx, size.height - pad),
                    thickness, cap = StrokeCap.Round,
                )
                val bubbleR = (thickness / 2f - 2.dp.toPx()).coerceAtLeast(2f)
                val markY = size.height / 2f
                drawLine(kb.toolbarIcon, Offset(cx - thickness / 2f, markY - bubbleR),
                    Offset(cx + thickness / 2f, markY - bubbleR), 1.5f)
                drawLine(kb.toolbarIcon, Offset(cx - thickness / 2f, markY + bubbleR),
                    Offset(cx + thickness / 2f, markY + bubbleR), 1.5f)
                val range = ((size.height - 2 * pad) / 2f - bubbleR).coerceAtLeast(0f)
                val off = (-gy / 9.81f).coerceIn(-1f, 1f) * range
                drawCircle(
                    if (axisLevel(gy)) kb.accent else kb.modifierKeyText,
                    bubbleR, Offset(cx, markY + off),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Horizontal tube vial: roll only.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 6.dp),
        ) {
            val cy = size.height / 2f
            val pad = 8.dp.toPx()
            val thickness = (size.height - 6.dp.toPx()).coerceAtLeast(6.dp.toPx())
            drawLine(
                kb.modifierKey, Offset(pad, cy), Offset(size.width - pad, cy),
                thickness, cap = StrokeCap.Round,
            )
            val bubbleR = (thickness / 2f - 2.dp.toPx()).coerceAtLeast(2f)
            val markX = size.width / 2f
            drawLine(kb.toolbarIcon, Offset(markX - bubbleR, cy - thickness / 2f),
                Offset(markX - bubbleR, cy + thickness / 2f), 1.5f)
            drawLine(kb.toolbarIcon, Offset(markX + bubbleR, cy - thickness / 2f),
                Offset(markX + bubbleR, cy + thickness / 2f), 1.5f)
            val range = ((size.width - 2 * pad) / 2f - bubbleR).coerceAtLeast(0f)
            val off = (gx / 9.81f).coerceIn(-1f, 1f) * range
            drawCircle(
                if (axisLevel(gx)) kb.accent else kb.modifierKeyText,
                bubbleR, Offset(markX + off, cy),
            )
        }
        if (state.settings.levelShowAngles) {
            Text(
                String.format(Locale.US, "↕ %.1f°   ↔ %.1f°", pitch, roll),
                color = if (flat) kb.accent else kb.modifierKeyText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---- moon phase ----

/** Current moon phase, drawn from arithmetic — nothing fetched. */
@Composable
internal fun MoonPhasePanel(state: KeyboardUiState) {
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    val info = remember { MoonPhase.at(System.currentTimeMillis()) }
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f),
        ) {
            val radius = min(size.width, size.height) / 2f - 4.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val dark = kb.modifierKey
            val lit = Color(0xFFE8E2D0)
            drawCircle(dark, radius, center)
            // Waxing lights the right limb (northern view); waning and the
            // southern-hemisphere setting each mirror the drawing.
            val waning = info.cycleFraction > 0.5
            val f = if (waning) 1.0 - info.cycleFraction else info.cycleFraction
            val mirrored = waning != state.settings.moonSouthernHemisphere
            val term = cos(2.0 * Math.PI * f).toFloat() // 1 new → -1 full
            val discRect = Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))
            val ellipseHalf = radius * abs(term)
            val ellipseRect = Rect(
                center - Offset(ellipseHalf, radius),
                Size(ellipseHalf * 2, radius * 2),
            )
            val path = Path().apply {
                arcTo(discRect, -90f, 180f, forceMoveTo = true)
                if (ellipseHalf > 0.5f) {
                    arcTo(ellipseRect, 90f, if (term > 0) -180f else 180f, forceMoveTo = false)
                }
                close()
            }
            scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f, pivot = center) {
                drawPath(path, lit)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1.4f)) {
            Text(
                stringResource(MoonPhase.phaseNameRes(info.phaseIndex)),
                color = kb.modifierKeyText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.ime_moon_detail_info,
                    (info.illumination * 100).roundToInt(),
                    info.ageDays.roundToInt(),
                ),
                color = kb.toolbarIcon,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.ime_moon_full_info,
                    dateFormat.format(Date(info.nextFullMoonMillis)),
                ),
                color = kb.modifierKeyText,
                fontSize = 13.sp,
            )
            Text(
                stringResource(
                    R.string.ime_moon_new_info,
                    dateFormat.format(Date(info.nextNewMoonMillis)),
                ),
                color = kb.modifierKeyText,
                fontSize = 13.sp,
            )
        }
    }
}

// ---- weather ----

/** One statistic in the weather detail grid: small icon, value, caption. */
@Composable
private fun WeatherStat(
    icon: ImageVector,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.toolbarIcon)
        Text(
            value,
            color = kb.chipText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(caption, color = kb.toolbarIcon, fontSize = 9.sp, maxLines = 1)
    }
}

/** Current conditions from the service-side fetch; °C/°F per tool setting. */
@Composable
internal fun WeatherPanel(
    state: KeyboardUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Keyboard height, no more: the stat grid scrolls, and growing the
    // window upward for it shoved the app's own content off screen.
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    // One ring slot; what it does follows the state, like the button it rings.
    PanelFocusTarget(
        panel = PanelMode.WEATHER,
        region = FocusRegion.ACTIONS,
        count = if (state.weather is WeatherUi.Loading) 0 else 1,
        columns = 1,
    ) {
        when (state.weather) {
            WeatherUi.NoLocation -> onOpenSettings()
            WeatherUi.Error, is WeatherUi.Ready -> onRefresh()
            WeatherUi.Loading -> Unit
        }
    }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        when (val weather = state.weather) {
            WeatherUi.NoLocation -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.ime_weather_no_location_empty),
                    color = kb.toolbarIcon,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(10.dp))
                val openSettings = stringResource(R.string.ime_weather_open_settings_action)
                ToolPanelKey(
                    description = openSettings,
                    label = openSettings,
                    modifier = Modifier
                        .height(40.dp)
                        .width(160.dp)
                        .focusRing(focusedAction == 0),
                ) { onOpenSettings() }
            }
            WeatherUi.Loading -> PanelMessage(stringResource(R.string.ime_weather_progress))
            WeatherUi.Error -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.ime_weather_error),
                    color = kb.toolbarIcon,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                val retry = stringResource(CommonR.string.common_retry)
                ToolPanelKey(
                    description = retry,
                    label = retry,
                    modifier = Modifier
                        .height(40.dp)
                        .width(120.dp)
                        .focusRing(focusedAction == 0),
                ) { onRefresh() }
            }
            is WeatherUi.Ready -> {
                val info = weather.info
                val fahrenheit = state.settings.weatherFahrenheit
                val unit = if (fahrenheit) "°F" else "°C"
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(WeatherClient.emoji(info.weatherCode, info.isDay), fontSize = 40.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val conditions = stringResource(
                                WeatherClient.describeRes(info.weatherCode),
                            )
                            Text(
                                "${WeatherClient.toDisplay(info.temperatureC, fahrenheit)}$unit · " +
                                    conditions,
                                color = kb.modifierKeyText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val place = state.settings.weatherPlaceName.ifBlank { null }
                            val highLow = stringResource(
                                R.string.ime_weather_high_low_info,
                                WeatherClient.toDisplay(info.highC, fahrenheit),
                                WeatherClient.toDisplay(info.lowC, fahrenheit),
                            )
                            Text(
                                listOfNotNull(place, highLow).joinToString(" · "),
                                color = kb.toolbarIcon,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.focusRing(focusedAction == 0, CircleShape),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(
                                    R.string.ime_weather_refresh_desc,
                                ),
                                tint = kb.toolbarIcon,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // All stats visible at once in a wrapping grid — the
                    // panel has spare height, so use it instead of making
                    // the user scroll sideways through a strip.
                    // The captions are read before the list is built: the
                    // builder lambda is a plain lambda, not a composable one.
                    val feelsLikeCaption = stringResource(R.string.ime_weather_feels_like_label)
                    val humidityCaption = stringResource(R.string.ime_weather_humidity_label)
                    val windCaption = stringResource(R.string.ime_weather_wind_label)
                    val rainCaption = stringResource(R.string.ime_weather_rain_chance_label)
                    val cloudsCaption = stringResource(R.string.ime_weather_clouds_label)
                    val uvCaption = stringResource(R.string.ime_weather_uv_label)
                    val sunriseCaption = stringResource(R.string.ime_weather_sunrise_label)
                    val sunsetCaption = stringResource(R.string.ime_weather_sunset_label)
                    val stats = buildList {
                        add(
                            Triple(
                                Icons.Outlined.Thermostat,
                                "${WeatherClient.toDisplay(info.feelsLikeC, fahrenheit)}$unit",
                                feelsLikeCaption,
                            )
                        )
                        add(
                            Triple(
                                Icons.Outlined.WaterDrop,
                                "${info.humidityPercent}%",
                                humidityCaption,
                            )
                        )
                        add(
                            Triple(
                                Icons.Outlined.Air,
                                // Follows the temperature unit: a panel that
                                // said 72°F and 11 km/h was half imperial and
                                // half metric, which is nobody's convention.
                                WeatherClient.formatWind(
                                    info.windKmh,
                                    info.windDirectionDeg,
                                    fahrenheit = fahrenheit,
                                ),
                                windCaption,
                            )
                        )
                        if (info.precipProbabilityPercent >= 0) {
                            add(
                                Triple(
                                    Icons.Outlined.Umbrella,
                                    "${info.precipProbabilityPercent}%",
                                    rainCaption,
                                )
                            )
                        }
                        if (info.cloudCoverPercent >= 0) {
                            add(
                                Triple(
                                    Icons.Outlined.Cloud,
                                    "${info.cloudCoverPercent}%",
                                    cloudsCaption,
                                )
                            )
                        }
                        if (info.pressureHpa >= 0) {
                            add(Triple(Icons.Outlined.Compress, "${info.pressureHpa.roundToInt()}", "hPa"))
                        }
                        if (info.uvIndexMax >= 0) {
                            add(
                                Triple(
                                    Icons.Outlined.WbSunny,
                                    "%.1f".format(info.uvIndexMax),
                                    uvCaption,
                                )
                            )
                        }
                        if (info.sunrise.isNotEmpty()) {
                            add(Triple(Icons.Outlined.WbTwilight, info.sunrise, sunriseCaption))
                        }
                        if (info.sunset.isNotEmpty()) {
                            add(Triple(Icons.Outlined.WbTwilight, info.sunset, sunsetCaption))
                        }
                    }
                    for (row in stats.chunked(3)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for ((icon, value, caption) in row) {
                                WeatherStat(icon, value, caption, modifier = Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

// ---- calendar ----

/**
 * Month calendar: a Gregorian grid carrying the first chosen alternate
 * calendar's date inside each cell, both chosen calendars' month spans in the
 * header, and a Today shortcut. Tapping a day shows that day's summary across
 * the chosen calendars and its events read from the device calendar
 * (read-only; [onRequestPermission] asks for READ_CALENDAR).
 */
@Composable
internal fun CalendarPanel(
    state: KeyboardUiState,
    onRequestPermission: () -> Unit,
    onPrefillConsumed: () -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val today = remember {
        Calendar.getInstance().let {
            CalendarSystems.SimpleDate(
                it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
    // Opened from a "next friday" chip: land on that day, selected, so the
    // panel answers "am I free then" without a tap. Snapshotted at first
    // composition because the service clears the prefill immediately after.
    val prefill = remember { state.toolPrefill as? ToolPrefill.Calendar }
    LaunchedEffect(Unit) { if (prefill != null) onPrefillConsumed() }
    val opened = remember {
        prefill?.let { CalendarSystems.SimpleDate(it.year, it.month, it.day) } ?: today
    }
    var shownYear by remember { mutableIntStateOf(opened.year) }
    var shownMonth by remember { mutableIntStateOf(opened.month) }
    var selected by remember { mutableStateOf(opened) }

    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val monthLabel = remember(shownYear, shownMonth) {
        monthFormat.format(
            Calendar.getInstance().apply { set(shownYear, shownMonth - 1, 1) }.time
        )
    }
    // The two calendars riding along with the Gregorian one, in draw order and
    // with empty slots dropped, so everything below just iterates.
    val altCalendars = listOf(state.settings.calendarAltOne, state.settings.calendarAltTwo)
        .filter { it != AltCalendar.NONE }
        .distinct()
    // The day cells only have room for one extra number, so the first pick
    // that has one to give wins.
    val cellCalendar = altCalendars.firstOrNull { it.hasDayLabel }
    val hijriAdjust = state.settings.hijriAdjustDays
    val daysInMonth = CalendarSystems.gregorianMonthLength(shownYear, shownMonth)

    // Which months of the chosen calendars this Gregorian month spans.
    val spanLabel = remember(shownYear, shownMonth, altCalendars, hijriAdjust) {
        altCalendars
            .map { AltCalendars.monthSpan(it, shownYear, shownMonth, hijriAdjust) }
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
    }

    // Ring regions — the month/selection state is all composable-local, which
    // is exactly what the publish-up controller exists for. Three fixed
    // ACTIONS slots (Today no-ops while already on the current month, rather
    // than renumbering next); the day grid is seven columns, so Up/Down walk
    // weeks the way the eye does.
    PanelFocusTarget(
        panel = PanelMode.CALENDAR,
        region = FocusRegion.ACTIONS,
        count = 3,
        columns = 3,
    ) { index ->
        when (index) {
            0 -> if (shownMonth == 1) { shownMonth = 12; shownYear-- } else shownMonth--
            1 -> {
                shownYear = today.year
                shownMonth = today.month
                selected = today
            }
            2 -> if (shownMonth == 12) { shownMonth = 1; shownYear++ } else shownMonth++
        }
    }
    PanelFocusTarget(
        panel = PanelMode.CALENDAR,
        region = FocusRegion.RESULTS,
        count = daysInMonth,
        columns = 7,
    ) { index ->
        val day = index + 1
        if (day in 1..daysInMonth) {
            selected = CalendarSystems.SimpleDate(shownYear, shownMonth, day)
        }
    }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedDay = state.focusedIndex(FocusRegion.RESULTS)?.plus(1)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            // Header: month navigation + Today shortcut.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (shownMonth == 1) { shownMonth = 12; shownYear-- } else shownMonth--
                    },
                    modifier = Modifier
                        .size(30.dp)
                        .focusRing(focusedAction == 0, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = stringResource(
                            R.string.ime_calendar_previous_month_desc,
                        ),
                        tint = kb.toolbarIcon)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        monthLabel,
                        color = kb.modifierKeyText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (spanLabel.isNotEmpty()) {
                        Text(
                            spanLabel,
                            color = kb.toolbarIcon,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (shownYear != today.year || shownMonth != today.month) {
                    Box(
                        modifier = Modifier
                            .clip(kb.chipShape())
                            .background(kb.chip)
                            .chipBorder(kb, kb.chipShape())
                            .focusRing(focusedAction == 1, kb.chipShape())
                            .clickable {
                                shownYear = today.year
                                shownMonth = today.month
                                selected = today
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.ime_calendar_today_label),
                            color = kb.modifierKeyText,
                            fontSize = 11.sp,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (shownMonth == 12) { shownMonth = 1; shownYear++ } else shownMonth++
                    },
                    modifier = Modifier
                        .size(30.dp)
                        .focusRing(focusedAction == 2, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.ime_calendar_next_month_desc),
                        tint = kb.toolbarIcon)
                }
            }
            // Weekday initials, with the user's weekend tinted. Which days those
            // are is a setting, defaulted from the device's region — Friday and
            // Saturday in much of the Middle East, Friday alone in Bangladesh,
            // Saturday and Sunday most other places.
            val weekend = state.settings.calendarWeekend.days
            val weekdayInitials = stringArrayResource(R.array.ime_calendar_weekday_initials)
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayInitials.forEachIndexed { index, initial ->
                    Text(
                        initial,
                        color = if (index in weekend) kb.accent else kb.toolbarIcon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Day grid.
            val firstJdn = CalendarSystems.gregorianToJdn(shownYear, shownMonth, 1)
            val firstDow = CalendarSystems.dayOfWeek(firstJdn)
            val weeks = (firstDow + daysInMonth + 6) / 7
            // The grid is the flexible child, so it would otherwise surrender
            // whatever the event list asked for and collapse into unreadable
            // slivers on a busy day. Reserve legible week rows first and hand
            // the events everything left over — that is what keeps a long list
            // scrolling in a real amount of room instead of cramping the month.
            val eventsMaxHeight = (panelHeight - CALENDAR_CHROME_HEIGHT - (weeks * 26).dp)
                .coerceIn(0.dp, 200.dp)
            Column(modifier = Modifier.weight(1f)) {
                for (week in 0 until weeks) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        for (dow in 0 until 7) {
                            val day = week * 7 + dow - firstDow + 1
                            if (day < 1 || day > daysInMonth) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                val isToday = today.year == shownYear &&
                                    today.month == shownMonth && today.day == day
                                val isSelected = selected.year == shownYear &&
                                    selected.month == shownMonth && selected.day == day
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = 2.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) kb.chipActive else Color.Transparent)
                                        .then(
                                            if (isToday && !isSelected) {
                                                Modifier.border(1.dp, kb.accent, RoundedCornerShape(6.dp))
                                            } else Modifier
                                        )
                                        .focusRing(day == focusedDay, RoundedCornerShape(6.dp))
                                        .clickable {
                                            selected = CalendarSystems.SimpleDate(shownYear, shownMonth, day)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val primary = when {
                                        isSelected -> kb.chipActiveText
                                        isToday -> kb.accent
                                        else -> kb.modifierKeyText
                                    }
                                    if (cellCalendar != null) {
                                        val altDay = AltCalendars.dayLabel(
                                            cellCalendar, shownYear, shownMonth, day, hijriAdjust,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                day.toString(),
                                                color = primary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isToday || isSelected) FontWeight.Bold
                                                    else FontWeight.Normal,
                                            )
                                            Text(
                                                altDay,
                                                color = if (isSelected) kb.chipActiveText.copy(alpha = 0.7f)
                                                    else kb.toolbarIcon,
                                                fontSize = 8.sp,
                                                maxLines = 1,
                                                modifier = Modifier.padding(start = 2.dp),
                                            )
                                        }
                                    } else {
                                        Text(
                                            day.toString(),
                                            color = primary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isToday || isSelected) FontWeight.Bold
                                                else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Selected date across the enabled calendars, then its events.
            val gregorianText = remember(selected) {
                SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(
                    Calendar.getInstance().apply {
                        set(selected.year, selected.month - 1, selected.day)
                    }.time
                )
            }
            val weekdayText = remember(selected) {
                SimpleDateFormat("EEEE", Locale.getDefault()).format(
                    Calendar.getInstance().apply {
                        set(selected.year, selected.month - 1, selected.day)
                    }.time
                )
            }
            val relativeDays =
                (CalendarSystems.gregorianToJdn(selected.year, selected.month, selected.day) -
                    CalendarSystems.gregorianToJdn(today.year, today.month, today.day)).toInt()
            val relativeText = when {
                relativeDays == 0 -> stringResource(R.string.ime_calendar_today_label)
                relativeDays == 1 -> stringResource(R.string.ime_calendar_tomorrow_label)
                relativeDays == -1 -> stringResource(R.string.ime_calendar_yesterday_label)
                relativeDays > 0 -> pluralStringResource(
                    R.plurals.ime_calendar_in_days_info, relativeDays, relativeDays,
                )
                else -> pluralStringResource(
                    R.plurals.ime_calendar_days_ago_info, -relativeDays, -relativeDays,
                )
            }
            // The alternate calendars ride along as context on the second line.
            val altText = remember(selected, altCalendars, hijriAdjust) {
                altCalendars
                    .map {
                        AltCalendars.fullDate(
                            it, selected.year, selected.month, selected.day, hijriAdjust,
                        )
                    }
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    gregorianText,
                    color = kb.modifierKeyText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    if (altText.isEmpty()) "$weekdayText · $relativeText"
                    else "$weekdayText · $relativeText · $altText",
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            CalendarDayEvents(
                selected = selected,
                onRequestPermission = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = eventsMaxHeight),
            )
        }
    }
}

/**
 * Everything in the calendar panel that is not the day grid or the event
 * list: the month header, the weekday initials, the selected-day summary and
 * the paddings between them.
 */
private val CALENDAR_CHROME_HEIGHT = 96.dp

/**
 * The selected day's events read from the device calendar. Shows a compact
 * permission prompt until READ_CALENDAR is granted; after that it queries
 * on a background thread whenever the selected day changes, and re-checks
 * the grant when the keyboard returns to the foreground (the system dialog
 * lives in a trampoline activity, like the camera tool).
 */
@Composable
private fun CalendarDayEvents(
    selected: CalendarSystems.SimpleDate,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(hasCalendarPermission(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = hasCalendarPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission) {
        Row(
            modifier = modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ime_calendar_permission_info),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(kb.chipShape())
                    .background(kb.chipActive)
                    .chipBorder(kb, kb.chipShape())
                    .clickable {
                        feedback()
                        onRequestPermission()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.ime_calendar_allow_action),
                    color = kb.chipActiveText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    // null = still loading; empty = queried, nothing scheduled.
    var events by remember { mutableStateOf<List<DeviceCalendarEvent>?>(null) }
    LaunchedEffect(selected, hasPermission) {
        events = null
        events = withContext(Dispatchers.IO) {
            DeviceCalendar.eventsForDay(context, selected.year, selected.month, selected.day)
        }
    }

    val list = events
    when {
        list == null -> Unit
        list.isEmpty() -> Text(
            stringResource(R.string.ime_calendar_no_events_empty),
            color = kb.secondaryText,
            fontSize = 12.sp,
            modifier = modifier.padding(top = 6.dp),
        )
        else -> {
            val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
            // A lazy list rather than a scrolling Column: a busy day's events
            // are then always reachable by dragging inside the list, however
            // little height the panel could spare for it. The cards keep their
            // own breathing room, so a full day reads as a stack rather than a
            // wall of text.
            LazyColumn(
                modifier = modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                // No key: EVENT_ID repeats across the instances of a recurring
                // event, and duplicate keys are a crash rather than a glitch.
                items(list) { event -> EventRow(event, timeFormat) }
            }
        }
    }
}

/**
 * One event card: the calendar's own colour as a stripe down the left, the
 * title, and the time (with the location after it, when there is one). The
 * stripe rather than a dot means the colour still reads at a glance in a
 * stack of events, and the card gives each event an edge of its own.
 */
@Composable
private fun EventRow(event: DeviceCalendarEvent, timeFormat: SimpleDateFormat) {
    val kb = LocalKbTheme.current
    val color = if (event.color != 0) Color(event.color) else kb.accent
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Intrinsic height so the stripe can fill the card: inside a lazy
            // list the row's own height is otherwise unbounded and fillMaxHeight
            // would have nothing to fill.
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(kb.chip, shape)
            .chipBorder(kb, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color),
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                .weight(1f),
        ) {
            Text(
                event.title,
                color = kb.modifierKeyText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val time = if (event.allDay) {
                stringResource(R.string.ime_calendar_all_day_label)
            } else {
                timeFormat.format(Date(event.begin))
            }
            Text(
                event.location?.let { "$time · $it" } ?: time,
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Whether the calendar tool may read the device calendar. */
internal fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

// ---- themes ----

/**
 * Quick theme switcher: a two-column scrolling grid of miniature keyboard
 * previews (the same [ThemePreview] drawing the settings app uses, so
 * gradients, images and key shapes all show) — the default (dynamic) theme,
 * every built-in and every custom theme. Tap to apply immediately. The Auto
 * swatch previews what the default id would resolve to (device dynamic
 * colors), not the currently active theme.
 *
 * A second chip switches the panel to the installed icon packs — the same
 * tap-to-apply grid, drawing each pack's own glyphs. Icon packs have no
 * auto-theme or mode override, so that half is never read-only.
 *
 * A press means "the keyboard looks like this now", and under the auto pair
 * that is the pair's live half rather than the manual selection — writing the
 * manual one there is a press with no effect at all, because the resolver
 * ignores it while auto-theme is on. The two states that genuinely have no one
 * theme to set stay read-only: a mode carries its own theme for its own
 * lifetime, and a random half is decided by its set at the next shuffle.
 */
@Composable
internal fun ThemesPanel(
    state: KeyboardUiState,
    onThemeSelect: (String) -> Unit,
    onIconPackSelect: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    var iconsTab by remember { mutableStateOf(false) }
    // A mode that carries a theme owns it for as long as the mode is active, so
    // the panel shows which one is live and presses do nothing (it's read-only).
    //
    // `state.settings` is already the mode's view of the settings, so a mode
    // theme arrives here as an ordinary keyboardThemeId with auto-theme forced
    // off — only the *explanation* has to look the mode up.
    val modeTheme = state.settings.modeThemeOwner(state.activeModeId)
    val autoPair = state.settings.autoTheme
    val autoOn = autoPair.enabled
    val systemDark = isSystemInDarkTheme()
    // The same resolver the board itself draws with, so the card marked live
    // here is always the one on screen — including on a clock or sun schedule.
    val darkSlot = rememberAutoThemeDarkSlot(state.settings, systemDark)
    // Where a press would be stored, from the definition the service stores it
    // by: a grid that offers a press the service has nowhere to put is the bug
    // this whole panel had.
    val target = state.settings.themeSelectionTarget(darkSlot, modeTheme != null)
    val locked = target == ThemeSelectionTarget.NONE
    // A random half is the read-only state that is not a mode, and it needs a
    // sentence of its own: the set, not the pair, is what has to change.
    val randomSlot = locked && modeTheme == null
    // Resolved through the pair rather than read off the fixed id, so a random
    // half rings the theme it selected instead of the one it would show if the
    // set were empty.
    val selectedId =
        if (autoOn) autoPair.slotThemeId(darkSlot) else state.settings.keyboardThemeId
    val auto = autoKbTheme(state.settings)
    // The panel is a shortlist, not the gallery: every custom and downloaded
    // theme, plus the built-ins the user picked for it in Settings (a default
    // spread until they touch it). The active theme always shows even if it
    // was taken off the list — a live theme with no card reads as a bug.
    val shortlist = state.settings.toolbarBehavior.themesPanelBuiltIns
        ?: DefaultThemesPanelBuiltIns
    // Families flatten: the pinned set names variants directly, and every
    // custom variant is a card of its own — this panel has no room for dots.
    val themes = BuiltInThemes.flattenedThemes()
        .filter { it.id in shortlist || it.id == selectedId } +
        state.settings.customThemes.flattenedThemes()
    val context = LocalContext.current
    val packStore = remember(context) { IconPackStore.get(context) }
    val packRevision by packStore.revision.collectAsState()
    val packs = remember(packRevision) { packStore.packs() }
    // Full-bleed: the toolbar row hides and its space becomes the header —
    // back button on the left, the Themes/Icons tabs filling the rest — so
    // the cards get the reclaimed rows.
    FullBleedTool(
        state,
        title = "",
        onClose = onClose,
        headerActions = {
            // Tab reaches the chips: the icon grid is unreachable if the
            // keyboard can browse the themes but never switch the panel over.
            PanelFocusTarget(
                panel = PanelMode.THEMES,
                region = FocusRegion.CHIPS,
                count = 2,
                columns = 2,
                onActivate = { index -> iconsTab = index == 1 },
            )
            ThemesTabChips(
                iconsTab = iconsTab,
                onSelect = { iconsTab = it },
                focused = state.focusedIndex(FocusRegion.CHIPS),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        ThemesPanelBody(
            state, kb, iconsTab, locked, selectedId, auto, themes,
            packs, packStore, packRevision, modeTheme, autoOn, darkSlot, randomSlot,
            onThemeSelect, onIconPackSelect, onOpenRoute,
        )
    }
}

/** Everything under the header: the info row, then the theme or pack grid. */
@Composable
private fun ThemesPanelBody(
    state: KeyboardUiState,
    kb: KbTheme,
    iconsTab: Boolean,
    locked: Boolean,
    selectedId: String,
    auto: KbTheme,
    themes: List<ThemeSpec>,
    packs: List<IconPack>,
    packStore: IconPackStore,
    packRevision: Int,
    modeTheme: KeyboardMode?,
    autoOn: Boolean,
    darkSlot: Boolean,
    randomSlot: Boolean,
    onThemeSelect: (String) -> Unit,
    onIconPackSelect: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    iconsTab && packs.isEmpty() ->
                        stringResource(R.string.ime_icons_empty_info)
                    iconsTab -> stringResource(R.string.ime_icons_pick_info)
                    modeTheme != null -> stringResource(
                        R.string.ime_themes_mode_locked_info, modeTheme.name,
                    )
                    randomSlot -> stringResource(R.string.ime_themes_auto_random_info)
                    // Names the half a press writes to, so "use this theme"
                    // and "and it is your dark one" are the same sentence.
                    autoOn && darkSlot -> stringResource(R.string.ime_themes_auto_dark_info)
                    autoOn -> stringResource(R.string.ime_themes_auto_light_info)
                    else -> stringResource(R.string.ime_themes_pick_info)
                },
                color = kb.toolbarIcon,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            ToolPanelKey(
                description = stringResource(
                    if (iconsTab) R.string.ime_icons_edit_desc
                    else R.string.ime_themes_edit_desc,
                ),
                label = stringResource(
                    if (iconsTab) R.string.ime_icons_edit_action
                    else R.string.ime_themes_edit_action,
                ),
                modifier = Modifier.height(32.dp).width(120.dp),
            ) { onOpenRoute(if (iconsTab) "icons" else "themes") }
        }
        if (iconsTab) {
            IconPacksGrid(state, packs, packStore, packRevision, onIconPackSelect)
            return@Column
        }
        // Index 0 is the leading "Auto" card, so a theme sits one past its own
        // position. Read-only in exactly the states a press is, so the keyboard
        // and the hardware keyboard cannot disagree about what a card does.
        PanelFocusTarget(
            panel = PanelMode.THEMES,
            count = themes.size + 1,
            columns = 2,
            onActivate = { index ->
                if (locked) return@PanelFocusTarget
                if (index == 0) onThemeSelect(DEFAULT_THEME_ID)
                else themes.getOrNull(index - 1)?.let { onThemeSelect(it.id) }
            },
        )
        val focused = state.focusedIndex()
        val gridState = rememberLazyGridState()
        ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
        // Opened on the theme you are wearing. The shortlist runs past what
        // fits as soon as there are a few custom themes, and a grid that always
        // starts at "Auto" hides the one card the panel is really about — the
        // live one, which is also what someone switching away starts from.
        // Placed, not animated: this is where the panel opens, not a move.
        LaunchedEffect(Unit) {
            val index = themes.indexOfFirst { it.id == selectedId }
            // Index 0 is the leading Auto card, so a theme sits one past its own
            // position, exactly as the focus target above counts them.
            if (index >= 0) gridState.scrollToItem(index + 1)
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = DEFAULT_THEME_ID) {
                ThemeCard(
                    name = stringResource(CommonR.string.common_auto),
                    selected = selectedId == DEFAULT_THEME_ID,
                    nameOnDark = auto.board.luminance() < 0.5f,
                    focused = focused == 0,
                    onClick = { if (!locked) onThemeSelect(DEFAULT_THEME_ID) },
                ) { AutoThemePreview(auto) }
            }
            itemsIndexed(themes, key = { _, theme -> theme.id }) { index, theme ->
                ThemeCard(
                    name = theme.name,
                    selected = selectedId == theme.id,
                    nameOnDark = Color(theme.boardBackground.toInt()).luminance() < 0.5f,
                    focused = focused == index + 1,
                    onClick = { if (!locked) onThemeSelect(theme.id) },
                ) { ThemePreview(theme) }
            }
        }
    }
}

/** The two chips switching the panel between themes and icon packs. */
@Composable
private fun ThemesTabChips(
    iconsTab: Boolean,
    onSelect: (Boolean) -> Unit,
    focused: Int? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = kb.chipShape()
        listOf(
            R.string.ime_themes_tab_themes,
            R.string.ime_themes_tab_icons,
        ).forEachIndexed { index, labelRes ->
            val active = (index == 1) == iconsTab
            Text(
                stringResource(labelRes),
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(index == 1) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The icon pack half of the themes panel: Built-in first, then every
 * installed pack, each card previewing a handful of the pack's own glyphs.
 */
@Composable
private fun IconPacksGrid(
    state: KeyboardUiState,
    packs: List<IconPack>,
    store: IconPackStore,
    revision: Int,
    onIconPackSelect: (String) -> Unit,
) {
    PanelFocusTarget(
        panel = PanelMode.THEMES,
        count = packs.size + 1,
        columns = 2,
        onActivate = { index ->
            if (index == 0) onIconPackSelect("")
            else packs.getOrNull(index - 1)?.let { onIconPackSelect(it.id) }
        },
    )
    val focused = state.focusedIndex()
    val gridState = rememberLazyGridState()
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // One resolved set per pack, parsed off the main thread on first sight
    // and thrown away wholesale when the store changes — the revision is the
    // store's own change signal, exactly what rememberIconSet keys on. Cards
    // draw built-in glyphs for the frame or two the parse takes.
    val previews = remember(revision) { mutableStateMapOf<String, IconSet>() }
    val selected = state.settings.icons.activePackId
    // The active pack, for the same reason the themes half opens on the live
    // theme. Index 0 is the built-in card, which is where an empty id belongs.
    LaunchedEffect(Unit) {
        val index = packs.indexOfFirst { it.id == selected }
        if (index >= 0) gridState.scrollToItem(index + 1)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "") {
            IconPackCard(
                name = stringResource(R.string.ime_icons_builtin_label),
                selected = selected.isEmpty(),
                focused = focused == 0,
                iconSet = IconSet.Builtin,
                onClick = { onIconPackSelect("") },
            )
        }
        itemsIndexed(packs, key = { _, pack -> pack.id }) { index, pack ->
            LaunchedEffect(pack.id, revision) {
                if (pack.id !in previews) {
                    previews[pack.id] = withContext(Dispatchers.Default) {
                        buildIconSet(IconSettings(activePackId = pack.id), store)
                    }
                }
            }
            IconPackCard(
                name = pack.name,
                selected = selected == pack.id,
                focused = focused == index + 1,
                iconSet = previews[pack.id] ?: IconSet.Builtin,
                onClick = { onIconPackSelect(pack.id) },
            )
        }
    }
}

/**
 * A spread of key and tool slots, so packs that only cover one group still
 * look different from each other on the cards.
 */
private val IconPackPreviewSlots = listOf(
    IconSlots.KEY_SHIFT,
    IconSlots.KEY_BACKSPACE,
    IconSlots.KEY_ENTER,
    IconSlots.KEY_GLOBE,
    IconSlots.forTool(ToolbarTool.EMOJI),
    IconSlots.forTool(ToolbarTool.CLIPBOARD),
)

/** [ThemeCard]'s shape and selection border around a grid of pack glyphs. */
@Composable
private fun IconPackCard(
    name: String,
    selected: Boolean,
    focused: Boolean,
    iconSet: IconSet,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) Modifier.border(2.dp, kb.accent, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, kb.divider, RoundedCornerShape(10.dp))
            )
            .focusRing(focused, RoundedCornerShape(10.dp))
            .background(kb.chip)
            .clickable(onClick = onClick),
    ) {
        CompositionLocalProvider(LocalIconSet provides iconSet) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconPackPreviewSlots.chunked(3).forEach { rowSlots ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowSlots.forEach { slot ->
                            SlotIcon(
                                slot,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = kb.suggestionText,
                            )
                        }
                    }
                }
            }
        }
        Text(
            name,
            color = kb.suggestionText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp),
        )
    }
}

/** Selection border + name overlay around a miniature keyboard preview. */
@Composable
private fun ThemeCard(
    name: String,
    selected: Boolean,
    nameOnDark: Boolean,
    onClick: () -> Unit,
    focused: Boolean = false,
    preview: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) Modifier.border(2.dp, kb.accent, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, kb.divider, RoundedCornerShape(10.dp))
            )
            // Concentric with the selection border above, and it carries an
            // accent *fill* — the border alone already means "this is the
            // theme in use", so an outline could not tell the two apart.
            .focusRing(focused, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        preview()
        // Name on the preview itself, tinted for its board — labels under
        // the cards read as clutter in a dense grid.
        Text(
            name,
            color = if (nameOnDark) Color.White.copy(alpha = 0.85f)
                else Color.Black.copy(alpha = 0.75f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp),
        )
    }
}

/**
 * Miniature keyboard drawn from the resolved dynamic colors — the Auto
 * entry has no ThemeSpec to hand to [ThemePreview], so it mirrors that
 * layout from the live [KbTheme] values.
 */
@Composable
private fun AutoThemePreview(auto: KbTheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(auto.board)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .background(auto.toolCircle, CircleShape),
                )
            }
        }
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(8) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .background(auto.key, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(auto.keyText, CircleShape),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .height(14.dp)
                    .background(auto.modifierKey, RoundedCornerShape(4.dp)),
            )
            Box(
                modifier = Modifier
                    .weight(3.6f)
                    .height(14.dp)
                    .background(auto.key, RoundedCornerShape(4.dp)),
            )
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .height(14.dp)
                    .background(auto.accent, RoundedCornerShape(4.dp)),
            )
        }
    }
}

// ---- sound & haptics ----

/** Small selectable pill for the sound/haptic style rows. */
@Composable
private fun StyleChip(
    label: String,
    selected: Boolean,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (selected) kb.chipActiveText else kb.chipText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Quick sound & haptics controls without leaving the keyboard. Every change
 * lands in the same DataStore settings the full settings app edits.
 */
@Composable
internal fun SoundHapticsPanel(
    state: KeyboardUiState,
    onAction: (SoundHapticAction) -> Unit,
) {
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    val settings = state.settings
    val context = LocalContext.current
    // Key-press sounds ride the system sound-effect stream, which the
    // ringer switch mutes — surface that, or "sound on but silent" reads
    // as the keyboard being broken. Live-updates via the ringer broadcast.
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    var ringerMode by remember { mutableIntStateOf(audioManager.ringerMode) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                ringerMode = audioManager.ringerMode
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Ring regions: the two master switches, then the visible style chips as
    // one flat row (haptic styles first). The sliders stay touch-only —
    // Enter means nothing on one, and the arrows are the ring's own keys.
    PanelFocusTarget(
        panel = PanelMode.SOUND_HAPTICS,
        region = FocusRegion.ACTIONS,
        count = 2,
        columns = 2,
    ) { index ->
        if (index == 0) {
            onAction(SoundHapticAction.Haptics(!settings.hapticFeedback))
        } else {
            onAction(SoundHapticAction.Sound(!settings.keySound))
        }
    }
    val hapticChipCount = if (settings.hapticFeedback) HapticStyle.entries.size else 0
    // Custom and Pack both name something the user installed rather than a
    // fixed style, and neither has a picker on the keyboard — so each appears
    // here only when it is already the choice, to show what is selected and to
    // let the user step off it.
    val soundStyles = KeySoundStyle.entries.filter {
        when (it) {
            KeySoundStyle.CUSTOM, KeySoundStyle.PACK -> settings.keySoundStyle == it
            else -> true
        }
    }
    val soundChipCount = if (settings.keySound) soundStyles.size else 0
    PanelFocusTarget(
        panel = PanelMode.SOUND_HAPTICS,
        region = FocusRegion.CHIPS,
        count = hapticChipCount + soundChipCount,
        columns = (hapticChipCount + soundChipCount).coerceAtLeast(1),
    ) { index ->
        when {
            index < hapticChipCount ->
                HapticStyle.entries.getOrNull(index)
                    ?.let { onAction(SoundHapticAction.HapticStyleChange(it)) }
            else ->
                soundStyles.getOrNull(index - hapticChipCount)
                    ?.let { onAction(SoundHapticAction.SoundStyleChange(it)) }
        }
    }
    val focusedSwitch = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (settings.keySound && ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // A warning, so the error tint stays — but it sits in a chip's
            // clothes: the theme's chip shape and, when set, its outline.
            val warnShape = kb.cardShape()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(warnShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .chipBorder(kb, warnShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                        stringResource(R.string.ime_sound_vibrate_info)
                    } else {
                        stringResource(R.string.ime_sound_silent_info)
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ime_sound_haptics_title), color = kb.modifierKeyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.hapticFeedback,
                onCheckedChange = { onAction(SoundHapticAction.Haptics(it)) },
                modifier = Modifier.focusRing(focusedSwitch == 0),
            )
        }
        if (settings.hapticFeedback) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for ((index, style) in HapticStyle.entries.withIndex()) {
                    StyleChip(
                        label = stringResource(style.labelRes),
                        selected = settings.hapticStyle == style,
                        focused = index == focusedChip,
                    ) { onAction(SoundHapticAction.HapticStyleChange(style)) }
                }
            }
            val hapticNote = when (settings.hapticStyle) {
                HapticStyle.SYSTEM_KEY ->
                    stringResource(R.string.ime_sound_haptic_system_key_info)
                HapticStyle.SYSTEM_TAP ->
                    stringResource(R.string.ime_sound_haptic_system_tap_info)
                else -> null
            }
            if (hapticNote != null) {
                Text(hapticNote, color = kb.toolbarIcon, fontSize = 11.sp)
            }
            if (settings.hapticStyle == HapticStyle.CUSTOM || settings.hapticStyle == HapticStyle.SHARP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ime_sound_intensity_label),
                        color = kb.toolbarIcon, fontSize = 11.sp,
                        modifier = Modifier.width(60.dp))
                    Slider(
                        value = settings.hapticAmplitude.toFloat(),
                        onValueChange = { onAction(SoundHapticAction.HapticAmplitude(it.toInt())) },
                        valueRange = 1f..255f,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    Text(
                        "${settings.hapticAmplitude * 100 / 255}%",
                        color = kb.toolbarIcon, fontSize = 11.sp,
                    )
                }
            }
            // Duration only bites on the custom one-shot path; the predefined
            // and primitive effects have a HAL-fixed length.
            if (settings.hapticStyle == HapticStyle.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ime_sound_duration_label),
                        color = kb.toolbarIcon, fontSize = 11.sp,
                        modifier = Modifier.width(60.dp))
                    Slider(
                        value = settings.hapticStrengthMs.toFloat(),
                        onValueChange = { onAction(SoundHapticAction.HapticDuration(it.toInt())) },
                        valueRange = 5f..60f,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    Text(
                        "${settings.hapticStrengthMs} ms",
                        color = kb.toolbarIcon, fontSize = 11.sp,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ime_sound_key_sound_title), color = kb.modifierKeyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.keySound,
                onCheckedChange = { onAction(SoundHapticAction.Sound(it)) },
                modifier = Modifier.focusRing(focusedSwitch == 1),
            )
        }
        if (settings.keySound) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // CUSTOM names an installed file rather than a fixed style, and
                // there is no file picker on the keyboard — so it only appears
                // here when it is already the choice, to show what is selected
                // and to let the user step off it. The chip list here must
                // stay [soundStyles], which the CHIPS publisher indexes.
                for ((index, style) in soundStyles.withIndex()) {
                    StyleChip(
                        label = when (style) {
                            KeySoundStyle.CLICK ->
                                stringResource(R.string.ime_sound_style_click_label)
                            KeySoundStyle.STANDARD ->
                                stringResource(R.string.ime_sound_style_standard_label)
                            KeySoundStyle.POP ->
                                stringResource(R.string.ime_sound_style_pop_label)
                            KeySoundStyle.THOCK ->
                                stringResource(R.string.ime_sound_style_thock_label)
                            KeySoundStyle.CHIME ->
                                stringResource(R.string.ime_sound_style_chime_label)
                            KeySoundStyle.CUSTOM -> stringResource(CommonR.string.common_custom)
                            KeySoundStyle.PACK ->
                                stringResource(R.string.ime_sound_style_pack_label)
                        },
                        selected = settings.keySoundStyle == style,
                        focused = focusedChip != null && focusedChip - hapticChipCount == index,
                    ) { onAction(SoundHapticAction.SoundStyleChange(style)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ime_sound_volume_label),
                    color = kb.toolbarIcon, fontSize = 11.sp,
                    modifier = Modifier.width(60.dp))
                Slider(
                    value = settings.keySoundVolume,
                    onValueChange = { onAction(SoundHapticAction.SoundVolume(it)) },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Text(
                    "${(settings.keySoundVolume * 100).roundToInt()}%",
                    color = kb.toolbarIcon, fontSize = 11.sp,
                )
            }
        }
    }
}

// ---- numpad ----

/**
 * Dedicated number pad: phone-style digit grid with the common numeric
 * punctuation, backspace (repeating) and enter.
 */
@Composable
internal fun NumpadPanel(
    state: KeyboardUiState,
    onText: (String) -> Unit,
    onKey: (Key) -> Unit,
) {
    val height = keyRowsHeight(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        // A layout that authored its own Number layer gets it drawn here too
        // (issue #55): the layer was only ever reaching numeric fields, and
        // this panel — the tool, and the ?123 long press — kept showing the
        // shipped pad however the user had arranged theirs. The calculator
        // order is left to the layout in that case: the rows are the author's.
        val authored = state.layouts.number
        if (authored != null) {
            for (row in authored.rows) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    NumpadLayoutRow(row, onText, onKey)
                }
            }
            return@Column
        }
        // Phone-style puts 123 on top (like a dialer); the calculator-style
        // setting flips the digit rows to a desktop keypad's 789-on-top.
        val digits = if (state.settings.numpadCalculatorLayout) {
            listOf("7", "8", "9", "4", "5", "6", "1", "2", "3")
        } else {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        }
        val rows = listOf(
            listOf(digits[0], digits[1], digits[2], "⌫"),
            listOf(digits[3], digits[4], digits[5], "+"),
            listOf(digits[6], digits[7], digits[8], "-"),
            listOf(".", "0", ",", "⏎"),
        )
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                NumpadRow(row, onText, onKey)
            }
        }
    }
}

/**
 * One row of an authored Number layer on the numpad panel. Delete and enter
 * take the panel's own faces; a text key types its output; anything else the
 * author put there — a space, a symbols key, a tool — goes through the same
 * key dispatch a press on the grid would.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.NumpadLayoutRow(
    keys: List<Key>,
    onText: (String) -> Unit,
    onKey: (Key) -> Unit,
) {
    val feedback = LocalKeyPressFeedback.current
    for (key in keys) {
        val cell = Modifier.weight(key.width.coerceAtLeast(0.1f)).fillMaxHeight().padding(2.dp)
        when (key.action) {
            KeyAction.Delete -> ToolPanelKey(
                description = stringResource(CommonR.string.common_delete),
                icon = Icons.AutoMirrored.Outlined.Backspace,
                repeatable = true,
                modifier = cell,
            ) {
                feedback()
                onKey(key)
            }
            KeyAction.Enter -> ToolPanelKey(
                description = stringResource(R.string.ime_numpad_enter_desc),
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                modifier = cell,
            ) {
                feedback()
                onKey(key)
            }
            KeyAction.Text -> {
                val typed = key.output ?: key.label
                ToolPanelKey(description = key.label, label = key.label, modifier = cell) {
                    onText(typed)
                }
            }
            else -> ToolPanelKey(
                description = key.label.ifBlank { key.action.fallbackLabel() },
                label = key.label.ifBlank { key.action.fallbackLabel() },
                modifier = cell,
            ) {
                feedback()
                onKey(key)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumpadRow(
    keys: List<String>,
    onText: (String) -> Unit,
    onKey: (Key) -> Unit,
) {
    val feedback = LocalKeyPressFeedback.current
    for (label in keys) {
        when (label) {
            "⌫" -> ToolPanelKey(
                description = stringResource(CommonR.string.common_delete),
                icon = Icons.AutoMirrored.Outlined.Backspace,
                repeatable = true,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
            ) {
                feedback()
                onKey(Key("⌫", action = KeyAction.Delete))
            }
            "⏎" -> ToolPanelKey(
                description = stringResource(R.string.ime_numpad_enter_desc),
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
            ) {
                feedback()
                onKey(Key("⏎", action = KeyAction.Enter))
            }
            else -> ToolPanelKey(
                description = label,
                label = label,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
            ) { onText(label) }
        }
    }
}
