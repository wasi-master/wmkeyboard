package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.wasimaster.wmkeyboard.core.theme.KeyLightingColorMode
import com.wasimaster.wmkeyboard.core.theme.KeyLightingPattern
import com.wasimaster.wmkeyboard.core.theme.KeyLightingTarget
import com.wasimaster.wmkeyboard.core.theme.hueShift
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The theme's key lighting: a light per key, the way a backlit keyboard has
 * one, driven by a pattern that runs on its own or answers the keys.
 *
 * Two copies are composed around the key grid: the [KeyLightingTarget.GLOW]
 * halo under the keys, so it shows in the gaps, and the face wash and lit
 * outlines over them. [behind] says which this one is; each draws nothing for
 * the other's target.
 *
 * The idle patterns run a frame loop for as long as they are composed. The
 * reactive ones sleep until a press ([ThemePulse.revision]) and run only until
 * the last press has faded, so a board nobody is typing on asks for no frames.
 */
@Composable
internal fun KeyLightingLayer(
    lighting: KbLighting,
    cells: () -> List<Rect>,
    cellsRevision: () -> Int,
    radiusDp: Float,
    behind: Boolean,
    modifier: Modifier = Modifier,
    gapHDp: Float = 0f,
    gapVDp: Float = 0f,
    /** A honeycomb key's own outline, by its cell; null lights the theme's rounded face. */
    shapeAt: (Rect) -> Shape? = { null },
) {
    val glow = lighting.target == KeyLightingTarget.GLOW
    if (glow != behind) return
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val kit = remember(lighting) { LightingKit(lighting) }
    val outlines = remember { FaceOutlines() }
    val reactive = lighting.pattern.reactive
    val pulse = if (reactive) ThemePulse.revision else 0
    // A steady light has nothing to animate: it repaints when the keys move.
    if (lighting.pattern != KeyLightingPattern.STEADY) LaunchedEffect(lighting, pulse) {
        // Idle patterns never stop; reactive ones stop once every press has faded.
        while (true) {
            val t = withFrameMillis { SystemClock.uptimeMillis() }
            now = t
            if (reactive && t - ThemePulse.lastPressAt > lighting.decayMs + REACTIVE_TAIL_MS) break
        }
    }
    Canvas(modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        cellsRevision()
        paintLighting(lighting, kit, cells(), now, origin, radiusDp, gapHDp * density, gapVDp * density, shapeAt, outlines)
    }
}

/** How much longer than the decay a ripple keeps travelling after its press. */
private const val REACTIVE_TAIL_MS = 600L

/**
 * The colours a lighting picks from and the gradient fills, made once per
 * lighting so the draw only indexes them. A gradient is unit sized and
 * stretched onto each key, so its shader is built here and never per frame.
 */
internal class LightingKit(lighting: KbLighting) {

    val colors: Array<Color> = when (lighting.colorMode) {
        KeyLightingColorMode.FIXED -> arrayOf(lighting.color)
        KeyLightingColorMode.RANDOM -> Array(WHEEL) { Color.hsv(it * 360f / WHEEL, 0.75f, 1f) }
        KeyLightingColorMode.PASTEL -> Array(WHEEL) { Color.hsv(it * 360f / WHEEL, 0.32f, 1f) }
    }

    /** Corner to corner, each colour into the one 70° further round the wheel. */
    val gradients: Array<Brush> = Array(colors.size) {
        val from = colors[it]
        Brush.linearGradient(
            0f to from,
            1f to hueShift(from, GRADIENT_TURN_DEG),
            start = Offset.Zero,
            end = Offset(1f, 1f),
        )
    }

    /** Specks of glitter, a little paler than the light itself. */
    val sparkles: Array<Color> = Array(colors.size) { lerp(colors[it], Color.White, 0.55f) }

    /** Set by [reactiveLevel] to the press that lit the key it just measured. */
    var winnerAt = 0L

    private var edgeDensity = 0f
    private var edge = Stroke(width = 1f)

    /** The outline's stroke, made again only when the density changes. */
    fun edgeFor(lighting: KbLighting, density: Float): Stroke {
        if (density != edgeDensity) {
            edgeDensity = density
            edge = Stroke(width = (lighting.sizeDp / 4f).coerceIn(1f, 4f) * density)
        }
        return edge
    }

    /** Which of [colors] a press or a key wears: [seed] is the press's time or a number per key. */
    fun indexFor(seed: Long): Int {
        if (colors.size == 1) return 0
        val mixed = hash((seed xor (seed ushr 19)).toInt())
        return (mixed * colors.size).toInt().coerceIn(0, colors.size - 1)
    }

    private companion object {
        const val WHEEL = 12
        const val GRADIENT_TURN_DEG = 70f
    }
}

/**
 * [gapH] and [gapV] are the padding each key keeps inside its cell, in pixels:
 * the cells are touch cells, gaps included, so the light is drawn on the face
 * they leave, the way the press splashes are. On the whole cell an outline sat
 * out in the gap, larger than the key and off its edge (Telegram group).
 */
private fun DrawScope.paintLighting(
    lighting: KbLighting,
    kit: LightingKit,
    cells: List<Rect>,
    now: Long,
    origin: Offset,
    radiusDp: Float,
    gapH: Float,
    gapV: Float,
    shapeAt: (Rect) -> Shape?,
    outlines: FaceOutlines,
) {
    if (cells.isEmpty()) return
    val t = now / 1000f * lighting.speed
    val width = size.width.coerceAtLeast(1f)
    val radius = CornerRadius(radiusDp * density)
    val reach = lighting.sizeDp * density
    for (index in cells.indices) {
        val cellRoot = cells[index]
        val cell = cellRoot.translate(-origin.x, -origin.y)
        val center = cell.center
        val u = center.x / width
        val level = when (lighting.pattern) {
            KeyLightingPattern.STEADY -> 1f
            KeyLightingPattern.BREATHE -> 0.25f + 0.75f * (0.5f + 0.5f * sin(t * 1.4f))
            KeyLightingPattern.WAVE -> 0.5f + 0.5f * sin((t * 0.6f - u) * 2f * PI.toFloat())
            KeyLightingPattern.SWEEP -> {
                val head = (t * 0.35f) % 1.4f - 0.2f
                exp(-((u - head) * 7f).pow(2))
            }
            KeyLightingPattern.TWINKLE -> {
                val phase = hash(index) * 20f
                max(0f, sin(t * (1f + hash(index + 99)) + phase)).pow(6)
            }
            KeyLightingPattern.HEARTBEAT -> {
                val beat = (t * 0.9f) % 1f
                max(exp(-((beat - 0.1f) * 22f).pow(2)), 0.7f * exp(-((beat - 0.28f) * 22f).pow(2)))
            }
            else -> reactiveLevel(lighting, kit, cell, origin, now)
        }
        if (level <= 0.01f) continue
        val drift = if (lighting.pattern == KeyLightingPattern.STEADY) 0f else t * 20f
        val hue = if (lighting.hueSpanDeg > 0f) u * lighting.hueSpanDeg + drift else 0f
        // A press's own colour on the patterns that answer presses, each key's
        // own on the ones that run by themselves; one colour when FIXED.
        val pick = kit.indexFor(if (lighting.pattern.reactive) kit.winnerAt else index.toLong() * 7919L)
        val color = hueShift(kit.colors[pick], hue)
        val a = (lighting.intensity * level).coerceIn(0f, 1f)
        // Presses are matched against the whole cell above (a tap in the gap
        // still lights its key); the light itself goes on the face.
        val face = Rect(cell.left + gapH, cell.top + gapV, cell.right - gapH, cell.bottom - gapV)
        if (face.width <= 0f || face.height <= 0f) continue
        val shape = shapeAt(cellRoot)
        if (shape != null) {
            val outline = outlines.of(cellRoot, shape, face.size, layoutDirection, this)
            translate(face.left, face.top) {
                lightHexKey(lighting, kit, outline, face.size, index, pick, color, a, reach, now)
            }
            continue
        }
        lightKey(lighting, kit, face, index, pick, color, a, radius, reach, now)
    }
}

/**
 * Lights one key, [cell] in this layer's space, in [color] at strength [a];
 * [pick] is which of the kit's colours it wears, for the gradient and the
 * glitter. Shared with the editor's preview tile, which lights one key by hand.
 */
internal fun DrawScope.lightKey(
    lighting: KbLighting,
    kit: LightingKit,
    cell: Rect,
    index: Int,
    pick: Int,
    color: Color,
    a: Float,
    radius: CornerRadius,
    reach: Float,
    now: Long,
) {
    when (lighting.target) {
        KeyLightingTarget.FACE -> drawRoundRect(
            color.copy(alpha = a * 0.55f), cell.topLeft, cell.size, radius, blendMode = BlendMode.Screen,
        )
        KeyLightingTarget.EDGE -> {
            // A stroke straddles its path: inset by half its width so the
            // line lies on the key's edge, inside it, not half out in the gap.
            val stroke = kit.edgeFor(lighting, density)
            val half = stroke.width / 2f
            drawRoundRect(
                color.copy(alpha = a),
                Offset(cell.left + half, cell.top + half),
                Size((cell.width - stroke.width).coerceAtLeast(0f), (cell.height - stroke.width).coerceAtLeast(0f)),
                CornerRadius((radius.x - half).coerceAtLeast(0f), (radius.y - half).coerceAtLeast(0f)),
                style = stroke,
            )
        }
        KeyLightingTarget.GLOW -> {
            // A soft halo as rings stepping outwards, cheaper than a blur and
            // close enough at the size a key gap is.
            val steps = 4
            for (s in steps downTo 1) {
                val grow = reach * s / steps
                drawRoundRect(
                    color.copy(alpha = a * 0.22f * (1f - (s - 1f) / steps)),
                    Offset(cell.left - grow, cell.top - grow),
                    Size(cell.width + grow * 2, cell.height + grow * 2),
                    CornerRadius(radius.x + grow),
                )
            }
        }
        KeyLightingTarget.FILL -> drawRoundRect(
            color.copy(alpha = a * 0.8f), cell.topLeft, cell.size, radius,
        )
        KeyLightingTarget.GRADIENT -> {
            if (cell.width > 0f && cell.height > 0f) {
                // The unit gradient stretched onto the key, its corners shrunk to match.
                translate(cell.left, cell.top) {
                    scale(cell.width, cell.height, pivot = Offset.Zero) {
                        drawRoundRect(
                            kit.gradients[pick], Offset.Zero, Size(1f, 1f),
                            CornerRadius(radius.x / cell.width, radius.y / cell.height),
                            alpha = a * 0.85f,
                        )
                    }
                }
            }
        }
        KeyLightingTarget.GLITTER -> glitter(kit.sparkles[pick], cell, index, now, a)
    }
}

/**
 * [lightKey] for a honeycomb key ([HexFaceShape]): the same lights, laid on its
 * hexagons rather than on a rounded rectangle, in the face's own space ([size]
 * is the face, [outline] its hexagons, which reach a little past it into the
 * neighbouring rows' notches).
 */
private fun DrawScope.lightHexKey(
    lighting: KbLighting,
    kit: LightingKit,
    outline: Outline,
    size: Size,
    index: Int,
    pick: Int,
    color: Color,
    a: Float,
    reach: Float,
    now: Long,
) {
    when (lighting.target) {
        KeyLightingTarget.FACE -> drawOutline(outline, color.copy(alpha = a * 0.55f), blendMode = BlendMode.Screen)
        KeyLightingTarget.EDGE -> drawOutline(outline, color.copy(alpha = a), style = kit.edgeFor(lighting, density))
        KeyLightingTarget.GLOW -> {
            // The rings of [lightKey], as strokes round the hexagons.
            val steps = 4
            for (s in steps downTo 1) {
                val grow = reach * s / steps
                drawOutline(
                    outline,
                    color.copy(alpha = a * 0.22f * (1f - (s - 1f) / steps)),
                    style = Stroke(width = grow * 2f),
                )
            }
        }
        KeyLightingTarget.FILL -> drawOutline(outline, color.copy(alpha = a * 0.8f))
        KeyLightingTarget.GRADIENT -> {
            val bounds = outline.bounds
            val path = (outline as? Outline.Generic)?.path
            if (path != null && bounds.width > 0f && bounds.height > 0f) {
                clipPath(path) {
                    translate(bounds.left, bounds.top) {
                        scale(bounds.width, bounds.height, pivot = Offset.Zero) {
                            drawRect(kit.gradients[pick], Offset.Zero, Size(1f, 1f), alpha = a * 0.85f)
                        }
                    }
                }
            }
        }
        KeyLightingTarget.GLITTER -> {
            val path = (outline as? Outline.Generic)?.path
            if (path == null) {
                glitter(kit.sparkles[pick], Rect(Offset.Zero, size), index, now, a)
            } else {
                clipPath(path) { glitter(kit.sparkles[pick], Rect(Offset.Zero, size), index, now, a) }
            }
        }
    }
}

/**
 * Specks of glitter over [cell]: where they sit is fixed per key and
 * reshuffled four times a second, and each one twinkles on its own; a few are
 * drawn as small four-point stars.
 */
private fun DrawScope.glitter(color: Color, cell: Rect, index: Int, now: Long, a: Float) {
    val shuffle = (now / GLITTER_SHUFFLE_MS).toInt()
    val dp = density
    for (k in 0 until GLITTER_SPECKS) {
        val salt = index * 131 + k * 17 + shuffle * 7
        val x = cell.left + cell.width * (0.14f + 0.72f * hash(salt))
        val y = cell.top + cell.height * (0.14f + 0.72f * hash(salt + 3))
        val twinkle = 0.45f + 0.55f * sin(now / 90f + k * 1.9f)
        val tone = color.copy(alpha = (a * twinkle).coerceIn(0f, 1f))
        if (k % 3 == 0) {
            val arm = (1.6f + 1.4f * hash(salt + 5)) * dp
            drawLine(tone, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 0.9f * dp)
            drawLine(tone, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 0.9f * dp)
        } else {
            drawCircle(tone, (0.7f + 0.9f * hash(salt + 7)) * dp, Offset(x, y))
        }
    }
}

private const val GLITTER_SPECKS = 9
private const val GLITTER_SHUFFLE_MS = 250L

/** How lit [cell] is by the recent presses, 0..1; leaves the press that lit it most in [LightingKit.winnerAt]. */
private fun reactiveLevel(lighting: KbLighting, kit: LightingKit, cell: Rect, origin: Offset, now: Long): Float {
    var level = 0f
    kit.winnerAt = 0L
    val decay = lighting.decayMs.toFloat()
    val keySpan = max(cell.width, cell.height)
    ThemePulse.forEachRecent(now, lighting.decayMs + REACTIVE_TAIL_MS) { px, py, ageMs ->
        val press = Offset(px - origin.x, py - origin.y)
        val age = ageMs / decay
        val fade = (1f - age).coerceIn(0f, 1f)
        val dist = (press - cell.center).getDistance() / keySpan
        val hit = when (lighting.pattern) {
            KeyLightingPattern.KEY -> if (cell.contains(press)) fade else 0f
            KeyLightingPattern.RIPPLE -> {
                val ring = age * 6f * lighting.speed
                max(0f, 1f - abs(dist - ring) * 1.4f) * fade
            }
            KeyLightingPattern.ECHO -> {
                var best = 0f
                for (k in 0 until 3) {
                    val ring = (age - k * 0.18f) * 5f * lighting.speed
                    if (ring > 0f) best = max(best, max(0f, 1f - abs(dist - ring) * 1.6f) * (1f - k * 0.3f))
                }
                best * fade
            }
            KeyLightingPattern.SPLASH -> if (dist <= age * 4f * lighting.speed) fade.pow(0.7f) else 0f
            KeyLightingPattern.CROSS -> {
                val sameRow = abs(press.y - cell.center.y) < cell.height / 2
                val sameCol = abs(press.x - cell.center.x) < cell.width / 2
                if ((sameRow || sameCol) && dist <= age * 8f * lighting.speed + 0.5f) fade else 0f
            }
            KeyLightingPattern.RAIN -> {
                // A drop falling from the pressed key down its column.
                val sameCol = abs(press.x - cell.center.x) < cell.width / 2
                val below = (cell.center.y - press.y) / cell.height
                if (sameCol && below >= 0f) max(0f, 1f - abs(below - age * 4f * lighting.speed)) * fade else 0f
            }
            else -> 0f
        }
        if (hit > level) {
            level = hit
            kit.winnerAt = now - ageMs
        }
    }
    return level
}

private fun hash(i: Int): Float {
    var h = i * 2654435761.toInt()
    h = h xor (h ushr 15)
    return (h and 0xFFFF) / 65535f
}
