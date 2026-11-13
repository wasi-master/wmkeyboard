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
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Air
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Compress
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Umbrella
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WaterDrop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.emoji.EmojiFontShaping
import com.wasimaster.wmkeyboard.core.emoji.EmojiShaper
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.fonts.InstalledFont
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.icons.IconPack
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.DefaultThemesPanelBuiltIns
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.IconSettings
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.settings.ThemeSelectionTarget
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.ui.WmSlider
import com.wasimaster.wmkeyboard.core.ui.rememberLiveSlider
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.settings.modeThemeOwner
import com.wasimaster.wmkeyboard.core.settings.slotThemeId
import com.wasimaster.wmkeyboard.core.settings.themeSelectionTarget
import com.wasimaster.wmkeyboard.app.ThemePreview
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.PaletteStyle
import com.wasimaster.wmkeyboard.ime.AppColors
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.tools.MoonPhase
import com.wasimaster.wmkeyboard.core.tools.Qibla
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.SoundHapticAction
import com.wasimaster.wmkeyboard.ime.WeatherUi
import com.wasimaster.wmkeyboard.ime.calendar.CalendarCallbacks
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import java.text.SimpleDateFormat
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
            // Glass like the board's own keys, and only where those have faces.
            .then(if (kb.keysHaveFaces) Modifier.frostGlass(kb, shape, FrostSurface.MODIFIER_KEYS) else Modifier)
            .background(if (pressed) kb.pressedKey else kb.keyFace(kb.modifierKey), shape)
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
internal fun CompassPanel(
    state: KeyboardUiState,
    /**
     * The Location tool (#350): Use my location for the qibla, and a place to
     * point at, picked on the Location panel's Found tab.
     */
    location: LocationCallbacks? = null,
) {
    val onUseMyLocation = location?.onUseMyLocationForTools
    val locationUi = location?.state?.collectAsState()?.value
    val place = locationUi?.compassTarget
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    // Raw sensor heading; the displayed azimuth chases it per frame below.
    var target by remember { mutableFloatStateOf(0f) }
    // Magnetic to true north, once a saved place says where the phone is (#350).
    // The rotation-vector sensor points at magnetic north, and the qibla
    // bearing is a true one; with a saved place the gap between the two is
    // known, so the rose turns to true north and the marker lands where the
    // Kaaba is rather than up to twenty degrees off it. Cached: the panel
    // recomposes every frame while the rose turns.
    val savedLat = state.settings.weather.latitude
    val savedLon = state.settings.weather.longitude
    val declination = remember(savedLat, savedLon) {
        if (savedLat != null && savedLon != null) {
            android.hardware.GeomagneticField(savedLat, savedLon, 0f, System.currentTimeMillis()).declination
        } else {
            0f
        }
    }
    val declinationNow by rememberUpdatedState(declination)
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
                target = (Math.toDegrees(orientation[0].toDouble()).toFloat() + declinationNow + 360f) % 360f
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

    val latitude = state.settings.weather.latitude
    val longitude = state.settings.weather.longitude
    val qiblaBearing = if (state.settings.sensorTools.compassQibla && latitude != null && longitude != null) {
        Qibla.bearing(latitude.toDouble(), longitude.toDouble()).toFloat()
    } else null
    // Measured from a fresh reading when there is one, else from the saved place.
    val origin = locationUi?.origin ?: if (latitude != null && longitude != null) {
        com.wasimaster.wmkeyboard.core.location.GeoPoint(latitude.toDouble(), longitude.toDouble())
    } else {
        null
    }
    val targetBearing = if (place != null && origin != null) origin.bearingTo(place.point).toFloat() else null

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
                // A place from the Location tool, the same way as the qibla.
                if (targetBearing != null) {
                    val angle = Math.toRadians(targetBearing.toDouble())
                    val dir = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
                    val pos = center + dir * (radius - 34.dp.toPx())
                    drawLine(kb.accent, center + dir * (radius - 12.dp.toPx()), center + dir * radius, 5f)
                    val pinPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 14.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.apply {
                        save()
                        rotate(azimuth, pos.x, pos.y)
                        drawText("📍", pos.x, pos.y + pinPaint.textSize / 3f, pinPaint)
                        restore()
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
        if (state.settings.sensorTools.compassDegrees) {
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
        if (state.settings.sensorTools.compassQibla) {
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
            if (qiblaBearing == null && onUseMyLocation != null) {
                ToolPanelChip(
                    stringResource(R.string.ime_location_use_my_location_action),
                    selected = true,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { onUseMyLocation() }
            }
        }
        if (place != null && location != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 12.dp, end = 12.dp),
            ) {
                Text(
                    if (origin != null) {
                        val bearing = origin.bearingTo(place.point).roundToInt() % 360
                        stringResource(
                            R.string.ime_location_compass_target,
                            "📍 " + place.label,
                            com.wasimaster.wmkeyboard.core.location.LocationFormatter.distance(origin.distanceTo(place.point)),
                            bearing,
                            cardinal(bearing),
                        )
                    } else {
                        stringResource(R.string.ime_location_compass_needs_position, "📍 " + place.label)
                    },
                    color = kb.toolbarIcon,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                ToolPanelChip(stringResource(R.string.ime_location_compass_clear_action)) { location.onCompassClear() }
            }
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
        if (state.settings.sensorTools.levelAngles) {
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

// ---- custom layouts ----

/**
 * The Secondary layout tool's picker (issue #62): one button per secondary
 * layout, by name. A tap goes through the ordinary key dispatch as an "open a
 * layout" key — the service opens the grid and closes this panel — so the
 * panel needs no callback of its own.
 */
@Composable
internal fun CustomLayoutPanel(
    state: KeyboardUiState,
    onKey: (Key) -> Unit,
) {
    val height = keyRowsHeight(state)
    val feedback = LocalKeyPressFeedback.current
    val layouts = remember(state.settings.customLayouts, state.layouts) {
        secondaryLayouts(state.settings.customLayouts).filter { it.id in state.layouts.secondaries }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(layouts.size, key = { layouts[it].id }) { index ->
            val layout = layouts[index]
            ToolPanelKey(
                description = layout.name,
                label = layout.name,
                modifier = Modifier
                    .height(48.dp)
                    .padding(2.dp),
            ) {
                feedback()
                onKey(Key(layout.name, action = KeyAction.Layout(layout.id)))
            }
        }
    }
}

// ---- moon phase ----

/** Current moon phase, drawn from arithmetic — nothing fetched. */
@Composable
internal fun MoonPhasePanel(state: KeyboardUiState) {
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    val now = remember { System.currentTimeMillis() }
    val info = remember { MoonPhase.at(now) }
    val quarters = remember { MoonPhase.quarters(now) }
    val context = LocalContext.current
    // The time with the day: a phase falls at an hour, and "full moon on
    // Tuesday" was not enough to know whether tonight is the night (#970).
    val dateFormat = remember {
        val time = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        SimpleDateFormat("EEE d MMM, $time", Locale.getDefault())
    }
    val arabic = state.settings.sensorTools.moonArabicNames

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
            val mirrored = waning != state.settings.sensorTools.moonSouthern
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
            if (arabic) {
                Text(MoonPhase.arabicName(info.phaseIndex), color = kb.modifierKeyText, fontSize = 15.sp)
            }
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
            // The main phases around today (#970): the one just gone, dimmed,
            // then the next three.
            for (q in quarters) {
                val past = q.atMillis <= now
                val name = stringResource(MoonPhase.phaseNameRes(q.phaseIndex)) +
                    if (arabic) " · " + MoonPhase.arabicName(q.phaseIndex) else ""
                Text(
                    stringResource(R.string.ime_moon_phase_at, name, dateFormat.format(Date(q.atMillis))),
                    color = if (past) kb.toolbarIcon else kb.modifierKeyText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            .frostGlass(kb, shape, FrostSurface.CHIPS)
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
    /** Reads the phone's position once and saves it as the weather location (#350); null hides the offer. */
    onUseMyLocation: (() -> Unit)? = null,
    /** The Calendar tool's: an event's hourly forecast, drawn in place of the current conditions (#1212). */
    calendar: CalendarCallbacks = CalendarCallbacks(),
) {
    // Keyboard height, no more: the stat grid scrolls, and growing the
    // window upward for it shoved the app's own content off screen.
    val height = keyRowsHeight(state)
    val eventForecast = calendar.forecast.collectAsState().value
    if (eventForecast != null) {
        EventForecastView(eventForecast, height, calendar.onAction)
        return
    }
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
                if (onUseMyLocation != null) {
                    Spacer(Modifier.height(8.dp))
                    val useMine = stringResource(R.string.ime_location_use_my_location_action)
                    ToolPanelKey(
                        description = useMine,
                        label = useMine,
                        modifier = Modifier
                            .height(40.dp)
                            .width(160.dp),
                    ) { onUseMyLocation() }
                }
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
                val fahrenheit = state.settings.weather.fahrenheit
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
                            val place = state.settings.weather.placeName.ifBlank { null }
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
                        IconHint(
                            stringResource(
                                R.string.ime_weather_refresh_desc,
                            ),
                        ) {
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

// ---- themes ----

/**
 * Quick theme switcher: a two-column scrolling grid of miniature keyboard
 * previews (the same [ThemePreview] drawing the settings app uses, so
 * gradients, images and key shapes all show) — the default (dynamic) theme,
 * every built-in and every custom theme. Tap to apply immediately. The Auto
 * swatch previews what the default id would resolve to (device dynamic
 * colors), not the currently active theme.
 *
 * Two more chips switch the panel to the installed icon packs and to the emoji
 * faces — the same tap-to-apply grid, drawing each pack's own glyphs and each
 * font's own emoji. Neither of those has an auto-theme or mode override, so
 * those halves are never read-only.
 *
 * Every grid ends on the same last entry: the add-on store, filtered to the one
 * kind that grid is showing. A quick switcher whose answer to "I want another
 * one" is "go and find the store yourself" is a dead end, and the store already
 * links the other way (see `AddonStoreRow` in the settings app).
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
    onAppColorPick: (String, Int) -> Unit,
    onAppColorStyle: (PaletteStyle) -> Unit,
    onIconPackSelect: (String) -> Unit,
    onEmojiFontSelect: (EmojiFontChoice, String) -> Unit,
    onFontSelect: (ScriptId, String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    var tab by remember { mutableStateOf(ThemesPanelTab.THEMES) }
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
    val fontStore = remember(context) { FontStore.get(context) }
    val fontRevision by fontStore.revision.collectAsState()
    // The standing choice is part of the key: it decides whether the two faces
    // that can vanish (Google's, and an imported file) still get a card.
    val faces = remember(context, fontRevision, state.settings.emojiFont) {
        emojiFaceOptions(context, fontStore, state.settings.emojiFont)
    }
    val scriptTabs = rememberFontScriptTabs(state.settings)
    // The fonts tab opens on the script being typed, because that is the one
    // whose keys the user can see the effect on. A script with no picker (most
    // of them: only a curated set has alternatives worth listing) falls back to
    // the English tab, which is also where Cyrillic and Greek are chosen.
    var fontScript by remember(scriptTabs) {
        mutableStateOf(
            scriptTabs.firstOrNull { it.script == state.settings.script.id }?.script
                ?: ScriptId.LATIN,
        )
    }
    // Full-bleed: the toolbar row hides and its space becomes the header —
    // back button on the left, the Themes/Icons/Emoji tabs filling the rest —
    // so the cards get the reclaimed rows.
    FullBleedTool(
        state,
        title = "",
        onClose = onClose,
        headerActions = {
            // Tab reaches the chips: the icon and emoji grids are unreachable
            // if the keyboard can browse the themes but never switch the panel
            // over.
            PanelFocusTarget(
                panel = PanelMode.THEMES,
                region = FocusRegion.CHIPS,
                count = ThemesPanelTab.entries.size,
                columns = ThemesPanelTab.entries.size,
                onActivate = { index ->
                    ThemesPanelTab.entries.getOrNull(index)?.let { tab = it }
                },
            )
            ThemesTabChips(
                tab = tab,
                onSelect = { tab = it },
                focused = state.focusedIndex(FocusRegion.CHIPS),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        val tabs = ThemesPanelTab.entries
        Box(Modifier.fillMaxSize().swipeTabs(tab.ordinal, tabs.size) { tab = tabs[it] }) {
            ThemesPanelBody(
                state, kb, tab, locked, selectedId, auto, themes,
                packs, packStore, packRevision, faces,
                scriptTabs, fontScript, { fontScript = it }, fontStore, fontRevision,
                modeTheme, autoOn, darkSlot, randomSlot,
                onThemeSelect, onAppColorPick, onAppColorStyle,
                onIconPackSelect, onEmojiFontSelect, onFontSelect, onOpenRoute,
            )
        }
    }
}

/** Which grid of the appearance panel is on screen. */
private enum class ThemesPanelTab { THEMES, ICONS, EMOJI, FONTS }

/**
 * The add-on store filtered to one kind of add-on.
 *
 * Spelled out here rather than imported because the store's routes belong to
 * the settings app, which this module cannot see. It is still not a loose
 * string: the argument is the enum's own name, which is exactly what the
 * settings app parses the route argument back into.
 */
private fun addonsTypeRoute(type: AddonType): String = "addons?type=${type.name}"

/** Everything under the header: the info row, then the grid for [tab]. */
@Composable
private fun ThemesPanelBody(
    state: KeyboardUiState,
    kb: KbTheme,
    tab: ThemesPanelTab,
    locked: Boolean,
    selectedId: String,
    auto: KbTheme,
    themes: List<ThemeSpec>,
    packs: List<IconPack>,
    packStore: IconPackStore,
    packRevision: Int,
    faces: List<EmojiFaceOption>,
    scriptTabs: List<FontScriptTab>,
    fontScript: ScriptId,
    onFontScriptSelect: (ScriptId) -> Unit,
    fontStore: FontStore,
    fontRevision: Int,
    modeTheme: KeyboardMode?,
    autoOn: Boolean,
    darkSlot: Boolean,
    randomSlot: Boolean,
    onThemeSelect: (String) -> Unit,
    onAppColorPick: (String, Int) -> Unit,
    onAppColorStyle: (PaletteStyle) -> Unit,
    onIconPackSelect: (String) -> Unit,
    onEmojiFontSelect: (EmojiFontChoice, String) -> Unit,
    onFontSelect: (ScriptId, String) -> Unit,
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
                    tab == ThemesPanelTab.FONTS ->
                        stringResource(R.string.ime_font_pick_info)
                    tab == ThemesPanelTab.EMOJI ->
                        stringResource(R.string.ime_emoji_font_pick_info)
                    tab == ThemesPanelTab.ICONS && packs.isEmpty() ->
                        stringResource(R.string.ime_icons_empty_info)
                    tab == ThemesPanelTab.ICONS -> stringResource(R.string.ime_icons_pick_info)
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
                    when (tab) {
                        ThemesPanelTab.THEMES -> R.string.ime_themes_edit_desc
                        ThemesPanelTab.ICONS -> R.string.ime_icons_edit_desc
                        ThemesPanelTab.EMOJI -> R.string.ime_emoji_font_edit_desc
                        ThemesPanelTab.FONTS -> R.string.ime_font_edit_desc
                    },
                ),
                label = stringResource(
                    when (tab) {
                        ThemesPanelTab.THEMES -> R.string.ime_themes_edit_action
                        ThemesPanelTab.ICONS -> R.string.ime_icons_edit_action
                        ThemesPanelTab.EMOJI -> R.string.ime_emoji_font_edit_action
                        ThemesPanelTab.FONTS -> R.string.ime_font_edit_action
                    },
                ),
                modifier = Modifier.height(32.dp).width(120.dp),
            ) {
                onOpenRoute(
                    when (tab) {
                        ThemesPanelTab.THEMES -> "themes"
                        ThemesPanelTab.ICONS -> "icons"
                        ThemesPanelTab.EMOJI -> "emoji"
                        // Straight to the script on screen, not to the list of
                        // scripts: the panel already knows which one is being
                        // looked at.
                        ThemesPanelTab.FONTS -> "fonts/${fontScript.name}"
                    },
                )
            }
        }
        when (tab) {
            ThemesPanelTab.ICONS -> {
                IconPacksGrid(state, packs, packStore, packRevision, onIconPackSelect, onOpenRoute)
                return@Column
            }
            ThemesPanelTab.EMOJI -> {
                EmojiFacesGrid(state, faces, onEmojiFontSelect, onOpenRoute)
                return@Column
            }
            ThemesPanelTab.FONTS -> {
                FontsGrid(
                    state, scriptTabs, fontScript, onFontScriptSelect,
                    fontStore, fontRevision, onFontSelect, onOpenRoute,
                )
                return@Column
            }
            ThemesPanelTab.THEMES -> Unit
        }
        // The app's own colours sit above the grid while the keyboard is
        // taking them, so the alternative colours and styles are one press
        // away in the app they are for.
        val appColors by AppColors.current.collectAsState()
        val sample = appColors
        if (state.settings.appColors.enabled && sample != null) {
            AppColorStrip(sample, state.settings.appColors, onAppColorPick, onAppColorStyle)
        }
        // Index 0 is the leading "Auto" card, so a theme sits one past its own
        // position, and the store card closes the list one past the last theme.
        // Read-only in exactly the states a press is, so the keyboard and the
        // hardware keyboard cannot disagree about what a card does — except for
        // the store card, which is a link rather than a selection and so stays
        // live even where a theme cannot be chosen.
        PanelFocusTarget(
            panel = PanelMode.THEMES,
            count = themes.size + 2,
            columns = 2,
            onActivate = { index ->
                if (index == themes.size + 1) {
                    onOpenRoute(addonsTypeRoute(AddonType.Theme))
                    return@PanelFocusTarget
                }
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
                ) { ThemePreview(theme, cached = true) }
            }
            getMoreItem(
                labelRes = R.string.ime_themes_more_label,
                focused = focused == themes.size + 1,
            ) { onOpenRoute(addonsTypeRoute(AddonType.Theme)) }
        }
    }
}

/** The chips switching the panel between themes, icon packs and emoji faces. */
@Composable
private fun ThemesTabChips(
    tab: ThemesPanelTab,
    onSelect: (ThemesPanelTab) -> Unit,
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
            R.string.ime_themes_tab_emoji,
            R.string.ime_themes_tab_fonts,
        ).forEachIndexed { index, labelRes ->
            val active = ThemesPanelTab.entries[index] == tab
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
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(index == focused, shape)
                    .clickable { onSelect(ThemesPanelTab.entries[index]) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The icon pack half of the themes panel: Built-in first, then every
 * installed pack, each card previewing a handful of the pack's own glyphs,
 * and the store last.
 */
@Composable
private fun IconPacksGrid(
    state: KeyboardUiState,
    packs: List<IconPack>,
    store: IconPackStore,
    revision: Int,
    onIconPackSelect: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    PanelFocusTarget(
        panel = PanelMode.THEMES,
        count = packs.size + 2,
        columns = 2,
        onActivate = { index ->
            when (index) {
                0 -> onIconPackSelect("")
                packs.size + 1 -> onOpenRoute(addonsTypeRoute(AddonType.IconPack))
                else -> packs.getOrNull(index - 1)?.let { onIconPackSelect(it.id) }
            }
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
        getMoreItem(
            labelRes = R.string.ime_icons_more_label,
            focused = focused == packs.size + 1,
        ) { onOpenRoute(addonsTypeRoute(AddonType.IconPack)) }
    }
}

// ---- emoji faces ----

/**
 * One card in the emoji half: a face the keyboard can draw emoji with.
 *
 * [choice] and [installedId] together are what the setting stores — the
 * installed faces all share one choice and are told apart by the store id, so
 * neither alone identifies a card. [key] exists because the grid needs a stable
 * item key across both kinds.
 *
 * A card is named either by a string resource (the three faces that are not
 * files in the library) or by the font's own name; exactly one is ever set.
 */
private data class EmojiFaceOption(
    val key: String,
    val choice: EmojiFontChoice,
    val installedId: String = "",
    val name: String = "",
    @StringRes val labelRes: Int = 0,
)

/**
 * Every emoji face worth offering in the panel, in the order the settings
 * screen lists them: the phone's own, Google's, the library, an imported file.
 *
 * [selected] is the standing choice, and it keeps a card that would otherwise
 * be filtered out: a phone that has lost Play services must still show what it
 * is set to, or the panel says the user is using a face that is not on it.
 */
private fun emojiFaceOptions(
    context: Context,
    store: FontStore,
    selected: EmojiFontChoice,
): List<EmojiFaceOption> = buildList {
    add(
        EmojiFaceOption(
            key = "system",
            choice = EmojiFontChoice.SYSTEM,
            labelRes = R.string.ime_emoji_font_system_label,
        ),
    )
    // Google's face is a file from the Play services font provider rather than
    // one of the app's own, so it is only an answer on a device that has one.
    if (PlayServices.hasFontProvider(context) || selected == EmojiFontChoice.NOTO) {
        add(
            EmojiFaceOption(
                key = "noto",
                choice = EmojiFontChoice.NOTO,
                labelRes = R.string.ime_emoji_font_noto_label,
            ),
        )
    }
    // The downloaded faces: an emoji-font add-on and the one-tap Noto fetch
    // both land in this library, so this is the whole of "what did I install".
    store.emojiFonts().forEach { font ->
        add(
            EmojiFaceOption(
                key = "installed:${font.id}",
                choice = EmojiFontChoice.INSTALLED,
                installedId = font.id,
                name = font.name,
            ),
        )
    }
    // Imported by hand in the settings app. There is no importer in the
    // keyboard, so this card only exists once a file has arrived — offering
    // "Custom" with nothing behind it would select the system font.
    if (KeyboardFonts.customEmojiFontFile(context).exists()) {
        add(
            EmojiFaceOption(
                key = "custom",
                choice = EmojiFontChoice.CUSTOM,
                labelRes = CommonR.string.common_custom,
            ),
        )
    }
}

/** Whether [settings] is currently drawing emoji with this face. */
private fun EmojiFaceOption.isLive(settings: KeyboardSettings): Boolean =
    settings.emojiFont == choice &&
        (choice != EmojiFontChoice.INSTALLED ||
            settings.emojiFontInstalled.installedId == installedId)

/** The faces the cards draw — shapes people tell apart at a glance. */
private val EmojiFacePreview = listOf("😀", "😂", "🥰", "😎", "👍", "🎉")

/**
 * The emoji half of the themes panel: every face the keyboard can draw emoji
 * with, each card drawing the same handful of emoji in that font, and the
 * store last.
 */
@Composable
private fun EmojiFacesGrid(
    state: KeyboardUiState,
    faces: List<EmojiFaceOption>,
    onEmojiFontSelect: (EmojiFontChoice, String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    PanelFocusTarget(
        panel = PanelMode.THEMES,
        count = faces.size + 1,
        columns = 2,
        onActivate = { index ->
            val face = faces.getOrNull(index)
            if (face == null) onOpenRoute(addonsTypeRoute(AddonType.EmojiFont))
            else onEmojiFontSelect(face.choice, face.installedId)
        },
    )
    val focused = state.focusedIndex()
    val gridState = rememberLazyGridState()
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // A font's coverage tables decide how each emoji has to be spelled for it
    // (see [EmojiFontShaping]) — ❤️ in particular comes out of the system font
    // in a face with no variation-selector table. Read off the main thread and
    // kept per card, the same shape the icon grid caches its parsed packs in:
    // a card that reads a megabyte of font on the frame it draws is a stall.
    val shapers = remember(faces) { mutableStateMapOf<String, EmojiShaper>() }
    // Opened on the face in use, for the same reason the other two grids are.
    LaunchedEffect(Unit) {
        val index = faces.indexOfFirst { it.isLive(state.settings) }
        if (index >= 0) gridState.scrollToItem(index)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(faces, key = { _, face -> face.key }) { index, face ->
            LaunchedEffect(face.key) {
                if (face.key !in shapers) {
                    val file = KeyboardFonts.emojiFontFile(context, face.choice, face.installedId)
                    shapers[face.key] = withContext(Dispatchers.Default) {
                        EmojiFontShaping.warm(file)
                        EmojiFontShaping.forFontFile(file)
                    }
                }
            }
            EmojiFaceCard(
                name = if (face.labelRes != 0) stringResource(face.labelRes) else face.name,
                family = remember(face.key) {
                    KeyboardFonts.emojiFamily(context, face.choice, face.installedId)
                },
                shaper = shapers[face.key] ?: EmojiFontShaping.Identity,
                selected = face.isLive(state.settings),
                focused = focused == index,
                onClick = { onEmojiFontSelect(face.choice, face.installedId) },
            )
        }
        getMoreItem(
            labelRes = R.string.ime_emoji_font_more_label,
            focused = focused == faces.size,
        ) { onOpenRoute(addonsTypeRoute(AddonType.EmojiFont)) }
    }
}

/** [ThemeCard]'s shape and selection border around a line-up of emoji. */
@Composable
private fun EmojiFaceCard(
    name: String,
    family: FontFamily?,
    shaper: EmojiShaper,
    selected: Boolean,
    focused: Boolean,
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
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EmojiFacePreview.chunked(3).forEach { rowEmoji ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowEmoji.forEach { emoji ->
                        val spelling = shaper.spelling(emoji)
                        Text(
                            spelling.text,
                            // One Text per emoji: emoji fonts often have no
                            // space glyph, so a single spaced string overlaps.
                            fontSize = 20.sp,
                            fontFamily = if (spelling.systemFont) null else family,
                            maxLines = 1,
                        )
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

// ---- key label fonts ----

/**
 * One script's font picker in the fonts tab.
 *
 * [ScriptId.LATIN] is the odd one: it has no entry in
 * [KeyboardFonts.scriptFontChoices] because it is not a per-script override at
 * all — it is the keyboard's own `keyFontId`, which Cyrillic and Greek ride
 * too. Every other tab writes one key of `scriptFontIds`.
 */
private data class FontScriptTab(
    val script: ScriptId,
    @StringRes val labelRes: Int,
    val sample: String,
    /** Google Fonts names offered here, before the provider check. */
    val googleNames: List<String>,
)

/**
 * The script chips the fonts tab offers: English first, then every enabled
 * language's script that has a curated list of its own.
 *
 * Enabled rather than all of them, exactly as the settings screen filters —
 * twenty-two chips for scripts the user does not type is not a picker. A
 * script with no curated alternatives (Thaana, say) has no chip at all: its
 * automatic Noto face is the only sensible answer and there is nothing to
 * choose between.
 */
@Composable
private fun rememberFontScriptTabs(settings: KeyboardSettings): List<FontScriptTab> {
    val latinSample = stringResource(R.string.ime_font_latin_sample)
    return remember(settings.enabledLanguages, latinSample) {
        val enabled = settings.enabledLanguages.mapTo(mutableSetOf()) { it.script }
        buildList {
            add(
                FontScriptTab(
                    ScriptId.LATIN,
                    R.string.ime_font_script_latin_label,
                    latinSample,
                    KeyboardFonts.googleFonts,
                ),
            )
            for (choices in KeyboardFonts.scriptFontChoices) {
                if (choices.script !in enabled) continue
                add(
                    FontScriptTab(
                        choices.script, choices.labelRes, choices.sample, choices.fonts,
                    ),
                )
            }
        }
    }
}

/** One selectable face in the fonts tab; [labelRes] names it when [name] cannot. */
private data class FontOption(
    val id: String,
    val name: String = "",
    @StringRes val labelRes: Int = 0,
)

/**
 * The faces one script tab offers: the automatic one, the library, the curated
 * Google Fonts, then the file this script's picker imported before fonts moved
 * into the library.
 *
 * The library is filtered the way the settings screen filters it — a font that
 * declared its languages is only offered for the scripts it claims, so a Latin
 * display face never turns up in the Devanagari list about to blank the board.
 * A font that declared nothing is offered everywhere, which is the honest
 * reading of "no claim".
 */
private fun fontOptions(
    context: Context,
    tab: FontScriptTab,
    installed: List<InstalledFont>,
    customName: String,
): List<FontOption> = buildList {
    add(FontOption(KeyboardFonts.DEFAULT_ID, labelRes = R.string.ime_font_system_default_label))
    // English also drives Cyrillic and Greek, which have no picker of their own.
    val scripts = if (tab.script == ScriptId.LATIN) {
        setOf(ScriptId.LATIN, ScriptId.CYRILLIC, ScriptId.GREEK)
    } else {
        setOf(tab.script)
    }
    for (font in installed) {
        val claims = font.langIds.isEmpty() ||
            font.langIds.any { LanguageRegistry.byId(it).script in scripts }
        if (claims) add(FontOption(FontStore.fontIdFor(font.id), name = font.name))
    }
    // Every Google Fonts entry is a file from the Play services font provider.
    // Without one each would resolve to the system face, so picking it would
    // change nothing on screen — the rows come out rather than sit there.
    if (PlayServices.hasFontProvider(context)) {
        for (name in tab.googleNames) add(FontOption(KeyboardFonts.googleId(name), name = name))
    }
    val customId = if (tab.script == ScriptId.LATIN) {
        KeyboardFonts.CUSTOM_ID.takeIf { KeyboardFonts.customFontFile(context).exists() }
    } else {
        KeyboardFonts.customScriptFontId(tab.script)
            ?.takeIf { KeyboardFonts.customScriptFontFile(context, tab.script)?.exists() == true }
    }
    if (customId != null) {
        add(FontOption(customId, name = customName, labelRes = R.string.ime_font_custom_label))
    }
}

/** Which face [settings] draws this script's key labels with. */
private fun selectedFontId(settings: KeyboardSettings, script: ScriptId): String =
    if (script == ScriptId.LATIN) {
        settings.keyFontId
    } else {
        settings.scriptFontIds[script.name] ?: KeyboardFonts.DEFAULT_ID
    }

/**
 * The fonts half of the appearance panel: a chip per script, then that
 * script's faces, each row drawn in the face it offers.
 *
 * One column rather than two, unlike every other grid here. A font is judged by
 * reading text in it, and a sample line for a script — "আমি ভালো আছি · কখগঘঙ
 * চছজঝঞ" — has nowhere to go in half a phone's width.
 */
@Composable
private fun FontsGrid(
    state: KeyboardUiState,
    scriptTabs: List<FontScriptTab>,
    script: ScriptId,
    onScriptSelect: (ScriptId) -> Unit,
    store: FontStore,
    revision: Int,
    onFontSelect: (ScriptId, String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val tab = scriptTabs.firstOrNull { it.script == script } ?: scriptTabs.first()
    val installed = remember(revision) { store.textFonts() }
    val customName = if (tab.script == ScriptId.LATIN) {
        state.settings.customFontName
    } else {
        state.settings.customScriptFontNames[tab.script.name].orEmpty()
    }
    val options = remember(tab, installed, customName) {
        fontOptions(context, tab, installed, customName)
    }
    val selected = selectedFontId(state.settings, tab.script)
    // Its own region, not a second CHIPS row: the focus controller keys a
    // region's activation by region, so two rows under one name would leave
    // Enter running the wrong row's action. Empty when the row is not drawn —
    // a user typing only Latin has one tab and no row, and a ring stop with
    // nothing on screen under it is a dead press.
    val scriptChips = if (scriptTabs.size > 1) scriptTabs else emptyList()
    PanelFocusTarget(
        panel = PanelMode.THEMES,
        region = FocusRegion.CATEGORIES,
        count = scriptChips.size,
        columns = scriptChips.size.coerceAtLeast(1),
        onActivate = { index -> scriptChips.getOrNull(index)?.let { onScriptSelect(it.script) } },
    )
    PanelFocusTarget(
        panel = PanelMode.THEMES,
        count = options.size + 1,
        columns = 1,
        onActivate = { index ->
            val option = options.getOrNull(index)
            if (option == null) onOpenRoute(addonsTypeRoute(AddonType.Font))
            else onFontSelect(tab.script, option.id)
        },
    )
    if (scriptChips.isNotEmpty()) {
        FontScriptChips(
            tabs = scriptChips,
            script = tab.script,
            focused = state.focusedIndex(FocusRegion.CATEGORIES),
            onSelect = onScriptSelect,
        )
    }
    val focused = state.focusedIndex()
    val gridState = rememberLazyGridState()
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // Opened on the face in use, for the same reason the other grids are. Keyed
    // on the tab so switching script re-places the list rather than leaving it
    // scrolled to a row belonging to the script before it.
    LaunchedEffect(tab) {
        val index = options.indexOfFirst { it.id == selected }
        if (index >= 0) gridState.scrollToItem(index)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(1),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(options, key = { _, option -> option.id }) { index, option ->
            FontCard(
                name = option.name.ifBlank {
                    if (option.labelRes != 0) stringResource(option.labelRes) else option.id
                },
                sample = tab.sample,
                family = remember(option.id, revision) {
                    KeyboardFonts.family(context, option.id)
                },
                selected = selected == option.id,
                focused = focused == index,
                onClick = { onFontSelect(tab.script, option.id) },
            )
        }
        getMoreItem(
            labelRes = R.string.ime_font_more_label,
            focused = focused == options.size,
        ) { onOpenRoute(addonsTypeRoute(AddonType.Font)) }
    }
}

/** The scrollable script row above the font list. */
@Composable
private fun FontScriptChips(
    tabs: List<FontScriptTab>,
    script: ScriptId,
    focused: Int?,
    onSelect: (ScriptId) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        for ((index, tab) in tabs.withIndex()) {
            StyleChip(
                label = stringResource(tab.labelRes),
                selected = tab.script == script,
                focused = index == focused,
            ) { onSelect(tab.script) }
        }
    }
}

/**
 * One font row: its name and a sample line, both drawn in the font itself, so
 * the row is its own preview.
 */
@Composable
private fun FontCard(
    name: String,
    sample: String,
    family: FontFamily?,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (selected) Modifier.border(2.dp, kb.accent, shape)
                else Modifier.border(1.dp, kb.divider, shape)
            )
            .focusRing(focused, shape)
            .background(kb.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            name,
            color = kb.suggestionText,
            fontFamily = family,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sample,
            color = kb.toolbarIcon,
            fontFamily = family,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- the store link every grid ends on ----

/**
 * The last entry of every grid in this panel: a full-width row that opens the
 * add-on store filtered to the kind of thing the grid holds.
 *
 * Full width rather than a card of its own, for two reasons. A card would have
 * to guess the height of whatever it sits beside — the theme cards are as tall
 * as their preview draws, the pack and face cards are a fixed 92dp — and a row
 * spanning the grid reads as the end of the list rather than as one more
 * choice in it, which is exactly what it is.
 */
private fun LazyGridScope.getMoreItem(
    @StringRes labelRes: Int,
    focused: Boolean,
    onClick: () -> Unit,
) = item(key = "wmkb:get-more", span = { GridItemSpan(maxLineSpan) }) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .border(1.dp, kb.divider, shape)
            .focusRing(focused, shape)
            .background(kb.chip)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            tint = kb.suggestionText,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(labelRes),
            color = kb.suggestionText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
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
            .frostGlass(kb, shape, FrostSurface.CHIPS)
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
 * The installed sounds, or the installed sound packs, under the style chips.
 *
 * Custom and Pack are the two key sounds that name a file the user installed
 * rather than a waveform the app generates, and until this row existed neither
 * could be *chosen* from the keyboard — the chips could only show a selection
 * already made in Settings, so a freshly installed sound pack was invisible
 * here. Scrolls sideways for the same reason the style chips do: a name is as
 * long as its author made it.
 *
 * The store link closes the row rather than opening it, so an empty library
 * reads as "here is where more come from" instead of as a broken picker.
 */
@Composable
private fun SoundChoiceRow(
    choices: List<Pair<String, String>>,
    selectedId: String,
    focused: Int?,
    @StringRes emptyRes: Int,
    @StringRes moreRes: Int,
    onMore: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    if (choices.isEmpty()) {
        Text(stringResource(emptyRes), color = kb.toolbarIcon, fontSize = 11.sp)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for ((index, choice) in choices.withIndex()) {
            StyleChip(
                label = choice.first,
                selected = selectedId == choice.second,
                focused = index == focused,
            ) { onSelect(choice.second) }
        }
        StyleChip(
            label = stringResource(moreRes),
            selected = false,
            focused = focused == choices.size,
            onClick = onMore,
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
    onOpenRoute: (String) -> Unit,
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
            onAction(SoundHapticAction.Haptics(!settings.haptics.enabled))
        } else {
            onAction(SoundHapticAction.Sound(!settings.sound.enabled))
        }
    }
    val hapticChipCount = if (settings.haptics.enabled) HapticStyle.entries.size else 0
    val soundStore = remember(context) { SoundStore.get(context) }
    val soundRevision by soundStore.revision.collectAsState()
    val sounds = remember(soundRevision) { soundStore.sounds() }
    val soundPackStore = remember(context) { SoundPackStore.get(context) }
    val packRevision by soundPackStore.revision.collectAsState()
    val soundPacks = remember(packRevision) { soundPackStore.packs() }
    // Custom and Pack both name something the user installed rather than a
    // fixed style, so each is offered once there is anything for it to name —
    // and kept when it is the standing choice even if that thing has since been
    // deleted, so the user can see what is selected and step off it.
    val soundStyles = KeySoundStyle.entries.filter {
        when (it) {
            KeySoundStyle.CUSTOM -> sounds.isNotEmpty() || settings.sound.style == it
            KeySoundStyle.PACK -> soundPacks.isNotEmpty() || settings.sound.style == it
            else -> true
        }
    }
    val soundChipCount = if (settings.sound.enabled) soundStyles.size else 0
    // What the chosen style picks from, as name/id pairs — empty for the five
    // built-in styles, which are waveforms rather than files.
    val soundChoices: List<Pair<String, String>> = when {
        !settings.sound.enabled -> emptyList()
        settings.sound.style == KeySoundStyle.CUSTOM -> sounds.map { it.name to it.id }
        settings.sound.style == KeySoundStyle.PACK -> soundPacks.map { it.name to it.id }
        else -> emptyList()
    }
    val soundChoiceRoute = if (settings.sound.style == KeySoundStyle.PACK) {
        addonsTypeRoute(AddonType.SoundPack)
    } else {
        addonsTypeRoute(AddonType.Sound)
    }
    // The list, then the store link that closes it — the same last entry every
    // grid in the appearance panel ends on.
    PanelFocusTarget(
        panel = PanelMode.SOUND_HAPTICS,
        region = FocusRegion.RESULTS,
        count = if (soundChoices.isEmpty()) 0 else soundChoices.size + 1,
        columns = (soundChoices.size + 1).coerceAtLeast(1),
    ) { index ->
        val choice = soundChoices.getOrNull(index)
        when {
            choice == null -> onOpenRoute(soundChoiceRoute)
            settings.sound.style == KeySoundStyle.PACK ->
                onAction(SoundHapticAction.SoundPackChange(choice.second))
            else -> onAction(SoundHapticAction.SoundCustomChange(choice.second))
        }
    }
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
        if (settings.sound.enabled && ringerMode != AudioManager.RINGER_MODE_NORMAL) {
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
                checked = settings.haptics.enabled,
                onCheckedChange = { onAction(SoundHapticAction.Haptics(it)) },
                modifier = Modifier.focusRing(focusedSwitch == 0),
            )
        }
        if (settings.haptics.enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for ((index, style) in HapticStyle.entries.withIndex()) {
                    StyleChip(
                        label = stringResource(style.labelRes),
                        selected = settings.haptics.style == style,
                        focused = index == focusedChip,
                    ) { onAction(SoundHapticAction.HapticStyleChange(style)) }
                }
            }
            val hapticNote = when (settings.haptics.style) {
                HapticStyle.SYSTEM_KEY ->
                    stringResource(R.string.ime_sound_haptic_system_key_info)
                HapticStyle.SYSTEM_TAP ->
                    stringResource(R.string.ime_sound_haptic_system_tap_info)
                else -> null
            }
            if (hapticNote != null) {
                Text(hapticNote, color = kb.toolbarIcon, fontSize = 11.sp)
            }
            if (settings.haptics.style == HapticStyle.CUSTOM || settings.haptics.style == HapticStyle.SHARP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ime_sound_intensity_label),
                        color = kb.toolbarIcon, fontSize = 11.sp,
                        modifier = Modifier.width(60.dp))
                    // One write when the finger lifts, not one per touch
                    // event: a settings write recomposes this keyboard and the
                    // settings app behind it, so the old per-event write left
                    // the thumb waiting for the store to answer.
                    val amplitude = rememberLiveSlider(
                        settings.haptics.amplitude.toFloat(),
                        { onAction(SoundHapticAction.HapticAmplitude(it.toInt())) },
                    )
                    WmSlider(
                        value = amplitude.value,
                        onValueChange = amplitude::onDrag,
                        onValueChangeFinished = amplitude::onRelease,
                        valueRange = 1f..255f,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    Text(
                        "${amplitude.value.roundToInt() * 100 / 255}%",
                        color = kb.toolbarIcon, fontSize = 11.sp,
                    )
                }
            }
            // Duration only bites on the custom one-shot path; the predefined
            // and primitive effects have a HAL-fixed length.
            if (settings.haptics.style == HapticStyle.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ime_sound_duration_label),
                        color = kb.toolbarIcon, fontSize = 11.sp,
                        modifier = Modifier.width(60.dp))
                    val duration = rememberLiveSlider(
                        settings.haptics.strengthMs.toFloat(),
                        { onAction(SoundHapticAction.HapticDuration(it.toInt())) },
                    )
                    WmSlider(
                        value = duration.value,
                        onValueChange = duration::onDrag,
                        onValueChangeFinished = duration::onRelease,
                        valueRange = 5f..60f,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    Text(
                        "${duration.value.roundToInt()} ms",
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
                checked = settings.sound.enabled,
                onCheckedChange = { onAction(SoundHapticAction.Sound(it)) },
                modifier = Modifier.focusRing(focusedSwitch == 1),
            )
        }
        if (settings.sound.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Custom and Pack name an installed file rather than a fixed
                // style; the row under this one picks which file. The chip list
                // here must stay [soundStyles], which the CHIPS publisher
                // indexes.
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
                        selected = settings.sound.style == style,
                        focused = focusedChip != null && focusedChip - hapticChipCount == index,
                    ) { onAction(SoundHapticAction.SoundStyleChange(style)) }
                }
            }
            if (settings.sound.style == KeySoundStyle.CUSTOM ||
                settings.sound.style == KeySoundStyle.PACK
            ) {
                SoundChoiceRow(
                    choices = soundChoices,
                    selectedId = if (settings.sound.style == KeySoundStyle.PACK) {
                        settings.sound.packId
                    } else {
                        settings.sound.customId
                    },
                    focused = state.focusedIndex(FocusRegion.RESULTS),
                    emptyRes = if (settings.sound.style == KeySoundStyle.PACK) {
                        R.string.ime_sound_packs_empty
                    } else {
                        R.string.ime_sound_customs_empty
                    },
                    moreRes = if (settings.sound.style == KeySoundStyle.PACK) {
                        R.string.ime_sound_packs_more_label
                    } else {
                        R.string.ime_sound_customs_more_label
                    },
                    onMore = { onOpenRoute(soundChoiceRoute) },
                ) { id ->
                    if (settings.sound.style == KeySoundStyle.PACK) {
                        onAction(SoundHapticAction.SoundPackChange(id))
                    } else {
                        onAction(SoundHapticAction.SoundCustomChange(id))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ime_sound_volume_label),
                    color = kb.toolbarIcon, fontSize = 11.sp,
                    modifier = Modifier.width(60.dp))
                val volume = rememberLiveSlider(
                    settings.sound.volume,
                    { onAction(SoundHapticAction.SoundVolume(it)) },
                )
                WmSlider(
                    value = volume.value,
                    onValueChange = volume::onDrag,
                    onValueChangeFinished = volume::onRelease,
                    valueRange = 0.05f..1f,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Text(
                    "${(volume.value * 100).roundToInt()}%",
                    color = kb.toolbarIcon, fontSize = 11.sp,
                )
            }
        }
    }
}

