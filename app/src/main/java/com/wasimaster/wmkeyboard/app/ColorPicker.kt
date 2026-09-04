package com.wasimaster.wmkeyboard.app

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.theme.SeedSwatches
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- shared bits ----

private fun pickerColorOf(argb: Long): Color = Color(argb.toInt())

/** A colour as the ARGB long that the picker, the themes and the settings store. */
internal fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Side of one square of the transparency checkerboard. */
private val CheckerCell = 5.dp

/** Neutrals the seed swatches do not carry, so grey keys stay one press away. */
private val NeutralSwatches = listOf(
    0xFFFFFFFF, 0xFFDDDDE2, 0xFF9A9AA2, 0xFF505057, 0xFF202024, 0xFF000000,
)

/** How many colour stops draw the hue wheel and the hue bar. */
private const val HUE_STEPS = 60

private const val FULL_TURN = 360f

private const val PERCENT = 100f

/**
 * The two greys of the checkerboard that shows through a translucent colour.
 * They follow the theme, so the board stays quiet on a dark screen.
 */
@Composable
private fun rememberCheckerColors(): Pair<Color, Color> {
    val onDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (onDark) {
        Color(0xFF43434A) to Color(0xFF2B2B31)
    } else {
        Color(0xFFFFFFFF) to Color(0xFFD5D5DC)
    }
}

private fun DrawScope.drawChecker(colors: Pair<Color, Color>) {
    val cell = CheckerCell.toPx()
    drawRect(colors.first)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(
                color = colors.second,
                topLeft = Offset(x, y),
                size = Size(min(cell, size.width - x), min(cell, size.height - y)),
            )
            x += cell * 2
        }
        y += cell
        row++
    }
}

/** Black or white, whichever reads on [background]. */
private fun contrastOn(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

/** A colour, drawn over a checkerboard so a translucent one looks translucent. */
@Composable
internal fun Swatch(color: Long, size: Dp = 28.dp) {
    val checker = rememberCheckerColors()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind { drawChecker(checker) }
            .background(pickerColorOf(color))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

// ---- the dialog ----

/**
 * HSVA picker: a hue and saturation wheel, brightness and opacity bars, a hex
 * field, and preset swatches. The wheel can be swapped for hue and saturation
 * bars, which read out numbers and answer TalkBack.
 *
 * Presets set hue, saturation and brightness but keep the current opacity, so
 * building a translucent scrim colour stays a one-bar job.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    supportsAlpha: Boolean,
    showReset: Boolean,
    onPick: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialColor = pickerColorOf(initial)
    val initialHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toArgb(), it) }
    }
    var hue by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[2]) }
    var opacity by rememberSaveable(initial) {
        mutableFloatStateOf(if (supportsAlpha) initialColor.alpha else 1f)
    }
    var onWheel by rememberSaveable { mutableStateOf(true) }

    val current = Color.hsv(hue, saturation, brightness, opacity)
    val currentHex = if (supportsAlpha) {
        "%08X".format(current.toArgb())
    } else {
        "%06X".format(current.toArgb() and 0xFFFFFF)
    }

    // Black and the greys carry no hue, and no saturation once they are black.
    // Keeping the old numbers there stops the wheel from jumping to red the
    // moment somebody types 000000 and then reaches for the brightness bar.
    fun setFrom(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        if (hsv[1] > 0f) hue = hsv[0]
        if (hsv[2] > 0f) saturation = hsv[1]
        brightness = hsv[2]
        if (supportsAlpha) opacity = color.alpha
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sideBySide = landscape && onWheel

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = { onWheel = !onWheel }) {
                    Icon(
                        imageVector = if (onWheel) Icons.Outlined.Tune else Icons.Outlined.Palette,
                        contentDescription = stringResource(
                            if (onWheel) R.string.theme_color_sliders_action else R.string.theme_color_wheel_action,
                        ),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ComparePreview(initialColor, current, onRevert = { setFrom(initialColor) })
                Spacer(Modifier.height(12.dp))
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HsvWheel(
                            hue = hue,
                            saturation = saturation,
                            brightness = brightness,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                        ) { h, s -> hue = h; saturation = s }
                        Column(modifier = Modifier.weight(1f)) {
                            ValueBars(
                                hue, saturation, brightness, opacity, supportsAlpha,
                                onBrightness = { brightness = it },
                                onOpacity = { opacity = it },
                            )
                            HexField(currentHex, current.toArgb(), supportsAlpha, opacity) { setFrom(it) }
                        }
                    }
                } else {
                    if (onWheel) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            HsvWheel(
                                hue = hue,
                                saturation = saturation,
                                brightness = brightness,
                                modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f),
                            ) { h, s -> hue = h; saturation = s }
                        }
                    } else {
                        HueBar(hue) { hue = it }
                        SaturationBar(hue, saturation, brightness) { saturation = it }
                    }
                    ValueBars(
                        hue, saturation, brightness, opacity, supportsAlpha,
                        onBrightness = { brightness = it },
                        onOpacity = { opacity = it },
                    )
                    HexField(currentHex, current.toArgb(), supportsAlpha, opacity) { setFrom(it) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.theme_color_presets_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                // A preset sets the colour but not the opacity, so a scrim you
                // already made translucent stays translucent.
                PresetSwatches(current) { preset ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(preset.toArgb(), hsv)
                    if (hsv[1] > 0f) hue = hsv[0]
                    saturation = hsv[1]
                    brightness = hsv[2]
                }
            }
        },
        confirmButton = {
            Row {
                if (showReset) {
                    TextButton(onClick = onReset) { Text(stringResource(CommonR.string.common_auto)) }
                }
                TextButton(onClick = { onPick(current.argbLong()) }) {
                    Text(stringResource(R.string.theme_color_apply_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** The colour before the change on the left, the colour now on the right. */
@Composable
private fun ComparePreview(initial: Color, current: Color, onRevert: () -> Unit) {
    val checker = rememberCheckerColors()
    val shape = RoundedCornerShape(12.dp)
    val beforeDesc = stringResource(R.string.theme_color_before_desc)
    val afterDesc = stringResource(R.string.theme_color_after_desc)
    val revertLabel = stringResource(R.string.theme_color_revert_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawBehind { drawChecker(checker) }
                .background(initial)
                .clickable(onClickLabel = revertLabel) { onRevert() }
                .semantics { contentDescription = beforeDesc },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawBehind { drawChecker(checker) }
                .background(current)
                .semantics { contentDescription = afterDesc },
        )
    }
}

/**
 * Hue around the wheel, saturation out from the middle. Brightness only dims
 * the drawing: it stays on its own bar, the way every other HSV picker does it.
 */
@Composable
private fun HsvWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    modifier: Modifier = Modifier,
    onChange: (Float, Float) -> Unit,
) {
    val handler by rememberUpdatedState(onChange)
    val hueBrush = remember {
        Brush.sweepGradient(List(HUE_STEPS + 1) { Color.hsv(FULL_TURN * it / HUE_STEPS, 1f, 1f) })
    }
    val desc = stringResource(R.string.theme_color_wheel_desc)
    Canvas(
        modifier = modifier
            .semantics { contentDescription = desc }
            .pointerInput(Unit) {
                // One gesture loop, not a tap detector plus a drag detector: two
                // detectors on one node fight over the down event and the drag
                // one never wins. This also drops the touch slop, so the colour
                // follows the finger from the first pixel.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pickOnWheel(down.position, size.width, size.height, handler)
                    down.consume()
                    drag(down.id) { change ->
                        pickOnWheel(change.position, size.width, size.height, handler)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        val middle = Offset(size.width / 2f, size.height / 2f)
        drawCircle(hueBrush, radius, middle)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), middle, radius),
            radius = radius,
            center = middle,
        )
        if (brightness < 1f) {
            drawCircle(Color.Black.copy(alpha = 1f - brightness), radius, middle)
        }
        val angle = hue * PI / 180.0
        val thumb = middle + Offset(
            (cos(angle) * saturation * radius).toFloat(),
            (sin(angle) * saturation * radius).toFloat(),
        )
        drawThumb(thumb, 9.dp.toPx())
    }
}

private fun DrawScope.drawThumb(at: Offset, radius: Float) {
    drawCircle(Color.Black.copy(alpha = 0.4f), radius + 1.dp.toPx(), at, style = Stroke(1.dp.toPx()))
    drawCircle(Color.White, radius, at, style = Stroke(3.dp.toPx()))
}

private fun pickOnWheel(at: Offset, width: Int, height: Int, onChange: (Float, Float) -> Unit) {
    val radius = min(width, height) / 2f
    if (radius <= 0f) return
    val dx = at.x - width / 2f
    val dy = at.y - height / 2f
    val saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
    val degrees = (atan2(dy, dx) * 180f / PI.toFloat() + FULL_TURN) % FULL_TURN
    onChange(degrees, saturation)
}

/** Brightness, and opacity when the colour carries it. */
@Composable
private fun ValueBars(
    hue: Float,
    saturation: Float,
    brightness: Float,
    opacity: Float,
    supportsAlpha: Boolean,
    onBrightness: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
) {
    val percent = stringResource(R.string.theme_color_percent_value)
    ChannelBar(
        label = stringResource(R.string.theme_color_brightness_label),
        readout = percent.format((brightness * PERCENT).roundToInt()),
        fraction = brightness,
        brush = Brush.horizontalGradient(
            listOf(Color.hsv(hue, saturation, 0f), Color.hsv(hue, saturation, 1f)),
        ),
        onChange = onBrightness,
    )
    if (supportsAlpha) {
        val opaque = Color.hsv(hue, saturation, brightness)
        ChannelBar(
            label = stringResource(R.string.theme_color_opacity_label),
            readout = percent.format((opacity * PERCENT).roundToInt()),
            fraction = opacity,
            brush = Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque)),
            showChecker = true,
            onChange = onOpacity,
        )
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val degrees = stringResource(R.string.theme_color_degrees_value)
    val brush = remember {
        Brush.horizontalGradient(List(HUE_STEPS + 1) { Color.hsv(FULL_TURN * it / HUE_STEPS, 1f, 1f) })
    }
    ChannelBar(
        label = stringResource(R.string.theme_color_hue_label),
        readout = degrees.format(hue.roundToInt()),
        fraction = hue / FULL_TURN,
        brush = brush,
        onChange = { onChange((it * FULL_TURN).coerceIn(0f, FULL_TURN)) },
    )
}

@Composable
private fun SaturationBar(hue: Float, saturation: Float, brightness: Float, onChange: (Float) -> Unit) {
    val percent = stringResource(R.string.theme_color_percent_value)
    ChannelBar(
        label = stringResource(R.string.theme_color_saturation_label),
        readout = percent.format((saturation * PERCENT).roundToInt()),
        fraction = saturation,
        brush = Brush.horizontalGradient(
            listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness)),
        ),
        onChange = onChange,
    )
}

/**
 * One channel: a label, a readout, and a gradient bar you drag. It carries
 * progress semantics, so TalkBack can set it without touching the gradient.
 */
@Composable
private fun ChannelBar(
    label: String,
    readout: String,
    fraction: Float,
    brush: Brush,
    showChecker: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val handler by rememberUpdatedState(onChange)
    val checker = rememberCheckerColors()
    val shape = RoundedCornerShape(50)
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(shape)
                .then(if (showChecker) Modifier.drawBehind { drawChecker(checker) } else Modifier)
                .background(brush)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .semantics {
                    contentDescription = label
                    stateDescription = readout
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target -> handler(target.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        handler((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        drag(down.id) { change ->
                            handler((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val inset = 3.dp.toPx()
                    val radius = size.height / 2f - inset
                    val x = (fraction * size.width).coerceIn(radius + inset, size.width - radius - inset)
                    drawThumb(Offset(x, size.height / 2f), radius)
                },
        )
    }
}

/**
 * The colour as text. The field takes 3, 6 or 8 hex digits, with or without a
 * leading `#`, and pushes every complete one straight to the picker.
 */
@Composable
private fun HexField(
    hex: String,
    argb: Int,
    supportsAlpha: Boolean,
    opacity: Float,
    onParsed: (Color) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(hex, TextRange(hex.length))) }
    val alphaByte = if (supportsAlpha) (opacity * 255).roundToInt().coerceIn(0, 255) else 255
    // Rewrite the field only when its own text no longer means the colour the
    // wheel is on. Comparing the strings instead would fight the user: deleting
    // AARRGGBB down past six digits parses, which changes nothing about the
    // colour, and the field would snap back to eight digits under the caret.
    LaunchedEffect(hex) {
        if (parseHex(field.text, alphaByte) != argb) {
            field = TextFieldValue(hex, TextRange(hex.length))
        }
    }
    val focus = LocalFocusManager.current
    val digits = if (supportsAlpha) 8 else 6
    OutlinedTextField(
        value = field,
        onValueChange = { typed ->
            // Keep whatever case they typed. Rebuilding the value on every
            // letter to upper-case it re-syncs the IME mid-word, and that is
            // how a keyboard ends up committing the same characters twice.
            val cleaned = typed.text.filter { it.isHexDigit() }.take(digits)
            field = if (cleaned == typed.text) {
                typed
            } else {
                TextFieldValue(cleaned, TextRange(cleaned.length))
            }
            parseHex(cleaned, alphaByte)?.let { onParsed(Color(it)) }
        },
        label = {
            Text(
                stringResource(
                    if (supportsAlpha) R.string.theme_color_hex_label else R.string.theme_color_hex_rgb_label,
                ),
            )
        },
        prefix = { Text("#") },
        singleLine = true,
        // A password field is the one keyboard type that no IME decorates with
        // autocorrect or word suggestions. Nothing here is masked.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Returns the ARGB behind [text], or null while the text is still partial.
 *
 * Six digits are RRGGBB and keep the opacity the picker already carries in
 * [alpha]. The old picker forced FF there, which is what issue #19 saw as
 * "hex color adds FF". Public for the unit test, see ColorPickerHexTest.
 */
fun parseHex(text: String, alpha: Int): Int? {
    val full = when (text.length) {
        3 -> text.map { "$it$it" }.joinToString("")
        6, 8 -> text
        else -> return null
    }
    val parsed = full.toULongOrNull(16)?.toLong() ?: return null
    return if (full.length == 6) ((alpha.toLong() shl 24) or parsed).toInt() else parsed.toInt()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSwatches(current: Color, onPick: (Color) -> Unit) {
    val presets = remember { SeedSwatches + NeutralSwatches }
    val descFormat = stringResource(R.string.theme_color_preset_desc)
    val currentRgb = current.toArgb() and 0xFFFFFF
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (preset in presets) {
            val color = pickerColorOf(preset)
            val selected = (preset and 0xFFFFFFL).toInt() and 0xFFFFFF == currentRgb
            val desc = descFormat.format("%06X".format(preset and 0xFFFFFFL))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onPick(color) }
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = contrastOn(color),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
