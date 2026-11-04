package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hand-drawn key art: each kind of key drawn as a stack of vector layers, the
 * way an illustrator draws a keycap. The layout of the file follows the skin
 * documents of the Android keyboard Kyng, so art drawn for one reads in the
 * other.
 *
 * A slot is drawn in its own design units ([SkinSlot.size]) and fitted to the
 * key nine-patch style: the parts of the drawing outside [SkinSlot.stretchX] /
 * [SkinSlot.stretchY] keep their proportions and the band between them takes
 * up the rest, so one letter-key drawing serves a spacebar without its corners
 * smearing.
 *
 * Colours are references, never values ([SkinColorRef]): `face` is whatever
 * colour the key itself has, `accent` the theme's accent, and so on, each with
 * optional `:lighten:`, `:darken:`, `:alpha:` and `:ink:` steps. A skin is
 * therefore worn by any palette, and recolours with the theme.
 */
@Serializable
data class KeySkinSpec(
    /** Keyed by [SkinSlotName]; a key with no slot of its own falls back along [skinSlotChain]. */
    val slots: Map<String, SkinSlot> = emptyMap(),
    /** Drawn over the whole board, under the keys. */
    val board: SkinSlot? = null,
)

@Serializable
data class SkinSlot(
    /** Width and height of the drawing, in its own units. */
    val size: List<Float> = listOf(100f, 100f),
    /** The horizontal band that stretches, as [from, to] in design units. */
    val stretchX: List<Float>? = null,
    /** The vertical band that stretches. */
    val stretchY: List<Float>? = null,
    /** How far the label moves while the key is held, in design units. */
    val labelShift: List<Float>? = null,
    val layers: List<SkinLayer> = emptyList(),
)

@Serializable
data class SkinLayer(
    /** SVG path data, in the slot's design units. */
    val d: String,
    val fill: SkinPaint? = null,
    val stroke: SkinPaint? = null,
    val strokeWidth: Float = 1f,
    val opacity: Float = 1f,
    /** A soft light around the path's outline. */
    val glow: SkinGlow? = null,
    /** Fill by the even-odd rule, so a path can cut holes in itself. */
    val evenOdd: Boolean = false,
    val press: SkinPress? = null,
)

/** A flat [color], or a gradient: [linear] is x0, y0, x1, y1 and [radial] is cx, cy, r, all in design units. */
@Serializable
data class SkinPaint(
    val color: String? = null,
    val linear: List<Float>? = null,
    val radial: List<Float>? = null,
    val stops: List<SkinStop> = emptyList(),
)

@Serializable
data class SkinStop(val at: Float, val color: String)

@Serializable
data class SkinGlow(val width: Float = 2f, val color: String = "accent", val alpha: Float = 0.4f)

/**
 * What the layer does while the key is held. [opacity], [scale] and [rotate]
 * are [at rest, held] pairs; [translate] and [squash] are where the layer goes
 * when held (it rests at no offset and no squash). [pivot] is the point scale,
 * rotate and squash turn about, in design units; the slot's centre if absent.
 */
@Serializable
data class SkinPress(
    val translate: List<Float>? = null,
    val opacity: List<Float>? = null,
    val scale: List<Float>? = null,
    val rotate: List<Float>? = null,
    val squash: List<Float>? = null,
    val pivot: List<Float>? = null,
)

/** The slots a skin may draw, and what each is for. */
object SkinSlotName {
    const val LETTER = "letter"
    /** Every key that does something rather than typing: shift, delete, mode… */
    const val SPECIAL = "special"
    const val ENTER = "enter"
    const val SPACE = "space"
    const val DELETE = "delete"
    const val SHIFT = "shift"
    /** The layer switch: ?123, ABC. */
    const val MODE = "mode"
    /** Keys that type something other than a letter: digits, punctuation. */
    const val PUNCTUATION = "punctuation"
    const val EMOJI = "emoji"
    const val GLOBE = "globe"
}

/** The slots tried, in order, for a key wearing [slot]: its own, then the class's, then the letter. */
fun skinSlotChain(slot: String): List<String> = when (slot) {
    SkinSlotName.LETTER -> listOf(SkinSlotName.LETTER)
    SkinSlotName.PUNCTUATION -> listOf(SkinSlotName.PUNCTUATION, SkinSlotName.LETTER)
    SkinSlotName.SPACE -> listOf(SkinSlotName.SPACE, SkinSlotName.LETTER)
    SkinSlotName.SPECIAL -> listOf(SkinSlotName.SPECIAL, SkinSlotName.LETTER)
    else -> listOf(slot, SkinSlotName.SPECIAL, SkinSlotName.LETTER)
}

/** The slot drawn for a key named [slot], or null when the skin has nothing for it. */
fun KeySkinSpec.slotFor(slot: String): SkinSlot? =
    skinSlotChain(slot).firstNotNullOfOrNull { slots[it] }

/** The most layers one slot may stack; past this a file is more likely a mistake than art. */
const val MAX_SKIN_LAYERS = 96

/** The longest path a layer may carry, in characters. */
const val MAX_SKIN_PATH_CHARS = 200_000

/** [this] with every slot held to the limits above, so a hostile file cannot stall the draw. */
fun KeySkinSpec.bounded(): KeySkinSpec {
    fun SkinSlot.bounded() = copy(layers = layers.filter { it.d.length <= MAX_SKIN_PATH_CHARS }.take(MAX_SKIN_LAYERS))
    return KeySkinSpec(slots = slots.mapValues { it.value.bounded() }, board = board?.bounded())
}

// ---- colour references -------------------------------------------------------

/**
 * A colour named by role rather than value: `face`, `accent:alpha:0.4`,
 * `#9A9EA2:darken:0.2`. [base] is the role name or a `#RRGGBB` / `#AARRGGBB`
 * value; [steps] apply in order.
 */
data class SkinColorRef(val base: String, val steps: List<Pair<String, Float>>) {

    /**
     * The ARGB colour this names, with [role] answering the role names; a role
     * it does not know is null, and so is the colour.
     */
    fun resolve(role: (String) -> Long?): Long? {
        var color = parseHexColor(base) ?: role(base) ?: return null
        for ((op, t) in steps) {
            color = when (op) {
                "lighten" -> mixArgb(color, 0xFFFFFFFF, t)
                "darken" -> mixArgb(color, 0xFF000000, t)
                "alpha" -> withAlpha(color, alphaOf(color) * t)
                // Towards whichever of black and white reads on it: a label
                // colour derived from the face it sits on.
                "ink" -> mixArgb(color, if (luminanceOf(color) > 0.45f) 0xFF000000 else 0xFFFFFFFF, t)
                else -> color
            }
        }
        return color
    }

    companion object {
        fun parse(text: String): SkinColorRef {
            val parts = text.trim().split(':')
            val steps = ArrayList<Pair<String, Float>>()
            var i = 1
            while (i + 1 < parts.size) {
                val t = parts[i + 1].toFloatOrNull()
                if (t != null) steps += parts[i].lowercase() to t.coerceIn(0f, 1f)
                i += 2
            }
            return SkinColorRef(parts.first(), steps)
        }
    }
}

/** `#RGB`, `#RRGGBB` or `#AARRGGBB` as ARGB; null for anything else. */
fun parseHexColor(text: String): Long? {
    if (!text.startsWith('#')) return null
    val hex = text.substring(1)
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        3 -> {
            val r = (value shr 8) and 0xF
            val g = (value shr 4) and 0xF
            val b = value and 0xF
            0xFF000000 or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
        }
        6 -> 0xFF000000 or value
        8 -> value
        else -> null
    }
}

fun alphaOf(argb: Long): Float = ((argb ushr 24) and 0xFF) / 255f

fun withAlpha(argb: Long, alpha: Float): Long =
    (argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt().toLong() shl 24)

/** [a] moved [t] of the way to [b], every channel including alpha. */
fun mixArgb(a: Long, b: Long, t: Float): Long {
    fun ch(c: Long, shift: Int) = ((c ushr shift) and 0xFF).toFloat()
    fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).roundToInt().coerceIn(0, 255).toLong()
    // Alpha stays the first colour's: lightening a translucent face must not
    // make it opaque.
    return (((a ushr 24) and 0xFF) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

// ---- path data ---------------------------------------------------------------

/** One absolute drawing command; every curve is cubic once parsed. */
sealed class PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand()
    data class LineTo(val x: Float, val y: Float) : PathCommand()
    data class CubicTo(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float,
    ) : PathCommand()
    object Close : PathCommand()
}

/**
 * SVG path data as absolute commands: every relative command made absolute,
 * H and V as lines, quadratics and arcs as cubics. Anything it cannot read
 * ends the path there rather than throwing, so a broken layer draws what it
 * has.
 */
fun parseSvgPath(d: String): List<PathCommand> {
    val out = ArrayList<PathCommand>()
    val tokens = PathTokens(d)
    var cx = 0f
    var cy = 0f
    var startX = 0f
    var startY = 0f
    var lastCtrlX = 0f
    var lastCtrlY = 0f
    var lastCmd = ' '
    var cmd = ' '
    while (true) {
        val next = tokens.command()
        // Numbers with no letter repeat the last command; a Z takes none, so
        // numbers after it are not a repeat but the end of what can be read.
        if (next != null) {
            cmd = next
        } else if (cmd == ' ' || cmd.uppercaseChar() == 'Z' || !tokens.hasNumber()) {
            break
        }
        val rel = cmd.isLowerCase()
        val ox = if (rel) cx else 0f
        val oy = if (rel) cy else 0f
        when (cmd.uppercaseChar()) {
            'M' -> {
                cx = ox + (tokens.number() ?: break); cy = oy + (tokens.number() ?: break)
                startX = cx; startY = cy
                out += PathCommand.MoveTo(cx, cy)
                // Further pairs after a move are lines.
                cmd = if (rel) 'l' else 'L'
            }
            'L' -> {
                cx = ox + (tokens.number() ?: break); cy = oy + (tokens.number() ?: break)
                out += PathCommand.LineTo(cx, cy)
            }
            'H' -> { cx = ox + (tokens.number() ?: break); out += PathCommand.LineTo(cx, cy) }
            'V' -> { cy = oy + (tokens.number() ?: break); out += PathCommand.LineTo(cx, cy) }
            'C' -> {
                val x1 = ox + (tokens.number() ?: break); val y1 = oy + (tokens.number() ?: break)
                val x2 = ox + (tokens.number() ?: break); val y2 = oy + (tokens.number() ?: break)
                cx = ox + (tokens.number() ?: break); cy = oy + (tokens.number() ?: break)
                out += PathCommand.CubicTo(x1, y1, x2, y2, cx, cy)
                lastCtrlX = x2; lastCtrlY = y2
            }
            'S' -> {
                val smooth = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                val x1 = if (smooth) 2 * cx - lastCtrlX else cx
                val y1 = if (smooth) 2 * cy - lastCtrlY else cy
                val x2 = ox + (tokens.number() ?: break); val y2 = oy + (tokens.number() ?: break)
                cx = ox + (tokens.number() ?: break); cy = oy + (tokens.number() ?: break)
                out += PathCommand.CubicTo(x1, y1, x2, y2, cx, cy)
                lastCtrlX = x2; lastCtrlY = y2
            }
            'Q' -> {
                val qx = ox + (tokens.number() ?: break); val qy = oy + (tokens.number() ?: break)
                val x = ox + (tokens.number() ?: break); val y = oy + (tokens.number() ?: break)
                out += quadToCubic(cx, cy, qx, qy, x, y)
                cx = x; cy = y; lastCtrlX = qx; lastCtrlY = qy
            }
            'T' -> {
                val smooth = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                val qx = if (smooth) 2 * cx - lastCtrlX else cx
                val qy = if (smooth) 2 * cy - lastCtrlY else cy
                val x = ox + (tokens.number() ?: break); val y = oy + (tokens.number() ?: break)
                out += quadToCubic(cx, cy, qx, qy, x, y)
                cx = x; cy = y; lastCtrlX = qx; lastCtrlY = qy
            }
            'A' -> {
                val rx = tokens.number() ?: break
                val ry = tokens.number() ?: break
                val rotation = tokens.number() ?: break
                val large = tokens.flag() ?: break
                val sweep = tokens.flag() ?: break
                val x = ox + (tokens.number() ?: break); val y = oy + (tokens.number() ?: break)
                arcToCubics(cx, cy, rx, ry, rotation, large, sweep, x, y, out)
                cx = x; cy = y
            }
            'Z' -> {
                out += PathCommand.Close
                cx = startX; cy = startY
            }
            else -> break
        }
        lastCmd = cmd
    }
    return out
}

private fun quadToCubic(x0: Float, y0: Float, qx: Float, qy: Float, x: Float, y: Float) = PathCommand.CubicTo(
    x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
    x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
    x, y,
)

/** The SVG endpoint arc as cubics, a quarter turn at most each (SVG spec, appendix F.6). */
private fun arcToCubics(
    x0: Float, y0: Float, rxIn: Float, ryIn: Float, angleDeg: Float,
    large: Boolean, sweep: Boolean, x: Float, y: Float, out: MutableList<PathCommand>,
) {
    if (x0 == x && y0 == y) return
    var rx = abs(rxIn)
    var ry = abs(ryIn)
    if (rx == 0f || ry == 0f) { out += PathCommand.LineTo(x, y); return }
    val phi = angleDeg * PI / 180.0
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)
    val dx = (x0 - x) / 2.0
    val dy = (y0 - y) / 2.0
    val x1p = cosPhi * dx + sinPhi * dy
    val y1p = -sinPhi * dx + cosPhi * dy
    val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if (lambda > 1) {
        val s = sqrt(lambda).toFloat()
        rx *= s; ry *= s
    }
    val rx2 = rx.toDouble() * rx
    val ry2 = ry.toDouble() * ry
    val num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
    val den = rx2 * y1p * y1p + ry2 * x1p * x1p
    var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
    if (large == sweep) coef = -coef
    val cxp = coef * rx * y1p / ry
    val cyp = -coef * ry * x1p / rx
    val cxc = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
    val cyc = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0
    fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a
    }
    val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if (!sweep && delta > 0) delta -= 2 * PI
    if (sweep && delta < 0) delta += 2 * PI
    val segments = ceil(abs(delta) / (PI / 2)).toInt().coerceIn(1, 8)
    val step = delta / segments
    val k = 4.0 / 3.0 * tan(step / 4)
    var t = theta1
    for (i in 0 until segments) {
        val c1 = cos(t); val s1 = sin(t)
        val t2 = t + step
        val c2 = cos(t2); val s2 = sin(t2)
        fun px(ex: Double, ey: Double) = (cxc + rx * ex * cosPhi - ry * ey * sinPhi).toFloat()
        fun py(ex: Double, ey: Double) = (cyc + rx * ex * sinPhi + ry * ey * cosPhi).toFloat()
        out += PathCommand.CubicTo(
            px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
            px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2),
            if (i == segments - 1) x else px(c2, s2), if (i == segments - 1) y else py(c2, s2),
        )
        t = t2
    }
}

/** A tokenizer for path data: command letters, numbers in every SVG spelling, and arc flags. */
private class PathTokens(private val s: String) {
    private var i = 0

    private fun skip() {
        while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
    }

    fun command(): Char? {
        skip()
        if (i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E') return s[i++]
        return null
    }

    fun hasNumber(): Boolean {
        skip()
        return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.')
    }

    /** An arc flag, which may be written with no separator before the next number. */
    fun flag(): Boolean? {
        skip()
        if (i >= s.length) return null
        return when (s[i]) {
            '0' -> { i++; false }
            '1' -> { i++; true }
            else -> null
        }
    }

    fun number(): Float? {
        skip()
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        var dot = false
        var digits = false
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit()) { digits = true; i++ }
            else if (c == '.' && !dot) { dot = true; i++ }
            else break
        }
        if (digits && i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            val save = i
            i++
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = save
        }
        if (!digits) { i = start; return null }
        return s.substring(start, i).toFloatOrNull()
    }
}

// ---- fitting a slot to a key -------------------------------------------------

/**
 * Maps a slot's design units onto a key [width] × [height] pixels: uniform
 * scale [unit], with the stretch bands taking up whatever the fixed parts
 * leave. A key too small for even its fixed parts is scaled down whole.
 */
class SkinFit(slot: SkinSlot, width: Float, height: Float) {
    private val dw = slot.size.getOrNull(0)?.takeIf { it > 0f } ?: 100f
    private val dh = slot.size.getOrNull(1)?.takeIf { it > 0f } ?: 100f
    val unit: Float = min(width / dw, height / dh)
    private val xAxis = Axis(dw, width, slot.stretchX, unit)
    private val yAxis = Axis(dh, height, slot.stretchY, unit)

    fun x(v: Float): Float = xAxis.map(v)
    fun y(v: Float): Float = yAxis.map(v)

    private class Axis(private val design: Float, private val target: Float, stretch: List<Float>?, private val unit: Float) {
        private val a = stretch?.getOrNull(0)?.coerceIn(0f, design)
        private val b = stretch?.getOrNull(1)?.coerceIn(0f, design)
        private val fixedLow = (a ?: 0f) * unit
        private val fixedHigh = (design - (b ?: design)) * unit
        private val middle = target - fixedLow - fixedHigh
        private val stretchable = a != null && b != null && b > a && middle >= 0f
        // Unstretched, the drawing is centred at its own proportions; a key too
        // small for the fixed parts takes the whole drawing squeezed to fit.
        private val offset = (target - design * unit) / 2f
        private val squeeze = target / design

        fun map(v: Float): Float = when {
            stretchable -> when {
                v <= a!! -> v * unit
                v >= b!! -> target - (design - v) * unit
                else -> fixedLow + (v - a) / (b - a) * middle
            }
            a != null && b != null && b > a -> v * squeeze
            else -> offset + v * unit
        }
    }
}

/** [commands] with every point put through [fit]. */
fun List<PathCommand>.fitted(fit: SkinFit): List<PathCommand> = map { c ->
    when (c) {
        is PathCommand.MoveTo -> PathCommand.MoveTo(fit.x(c.x), fit.y(c.y))
        is PathCommand.LineTo -> PathCommand.LineTo(fit.x(c.x), fit.y(c.y))
        is PathCommand.CubicTo -> PathCommand.CubicTo(
            fit.x(c.x1), fit.y(c.y1), fit.x(c.x2), fit.y(c.y2), fit.x(c.x), fit.y(c.y),
        )
        PathCommand.Close -> c
    }
}
