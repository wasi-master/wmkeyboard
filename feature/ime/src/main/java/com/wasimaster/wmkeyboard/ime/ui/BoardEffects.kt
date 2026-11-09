package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.wasimaster.wmkeyboard.core.theme.BoardEffectKind
import com.wasimaster.wmkeyboard.core.theme.hueShift
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The theme's moving board, drawn over the board colour and under the keys.
 *
 * Every painter is stateless: a flake, a star or a bubble is a pure function
 * of its index and the clock, so nothing is allocated per frame and nothing
 * has to be carried across a theme change. The clock is not wall time: it is
 * integrated frame by frame at the effect's speed, and typing (through
 * [ThemePulse]) speeds it up, so a reactive board surges while the keys are
 * being hit and settles back without ever jumping.
 *
 * The frame loop runs while the layer is composed; the caller composes it only
 * when motion is allowed. [still] draws one frame and stops, which is what the
 * theme previews use. A press kind ([BoardEffectKind.onPress]) is handed to
 * [PressEffectOverlay], or as a picture to [PressEffectSample].
 */
@Composable
fun BoardEffectLayer(effect: KbBoardEffect, modifier: Modifier = Modifier, still: Boolean = false) {
    // A press kind has no clock of its own: it draws the presses (PressEffects.kt).
    if (effect.kind.onPress) {
        if (still) PressEffectSample(effect, modifier) else PressEffectOverlay(effect, modifier)
        return
    }
    var clock by remember { mutableFloatStateOf(STILL_CLOCK_S) }
    var energy by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // A board turned all the way down (no speed, deaf to typing) never moves:
    // still colour pools, a frozen aurora. It draws once, with no frame loop
    // waking the phone sixty times a second to draw the same picture.
    val frozen = effect.speed <= 0f && effect.reactive <= 0f && effect.kind !in PRESS_DRAWN_EFFECTS
    if (!still && !frozen) {
        LaunchedEffect(effect) {
            var last = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    val dt = ((now - last).coerceIn(0L, 100L)) / 1000f
                    last = now
                    val e = if (effect.reactive > 0f) ThemePulse.energy(SystemClock.uptimeMillis(), effect.halfLifeMs) else 0f
                    energy = e * effect.reactive
                    clock += boardClockStep(dt, effect.speed, energy)
                }
            }
        }
    }
    // A still frame has no touches to place, so it does not track where it
    // is: in a scrolling gallery that wrote state, and redrew the effect, on
    // every frame of every scroll.
    Canvas(modifier = if (still) modifier else modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        paintBoardEffect(effect, clock, energy, origin)
    }
}

/**
 * How far the effect's clock moves in [dtS] seconds at [speed] with typing
 * [energy] 0..1. Typing multiplies the pace and also adds some of its own, so
 * a board whose speed is turned down to nothing still stirs while it is typed
 * on (#1129). The surge used to be 1.6 times the energy alone, which one key
 * press barely moved: it read as Reacts to typing doing nothing.
 */
internal fun boardClockStep(dtS: Float, speed: Float, energy: Float): Float =
    dtS * (speed * (1f + REACT_SPEED_GAIN * energy) + REACT_OWN_PACE * energy)

/** How much faster a board runs at full typing energy, over its own speed. */
private const val REACT_SPEED_GAIN = 2.5f

/** The pace typing alone gives a board, at full energy, in the effect's own seconds per second. */
private const val REACT_OWN_PACE = 1f

/** A moment into the effect where every kind has something on screen; what a still preview shows. */
private const val STILL_CLOCK_S = 7.3f

/** Draws [effect] at [t] seconds into its clock with typing [energy] 0..1. */
fun DrawScope.paintBoardEffect(effect: KbBoardEffect, t: Float, energy: Float, origin: Offset = Offset.Zero) {
    if (size.width <= 0f || size.height <= 0f) return
    val p = Painter(this, effect, t, energy, origin)
    when (effect.kind) {
        BoardEffectKind.SNOW -> p.snow()
        BoardEffectKind.RAIN -> p.rain()
        BoardEffectKind.CODE_RAIN -> p.codeRain()
        BoardEffectKind.BOKEH -> p.bokeh()
        BoardEffectKind.STARFIELD -> p.starfield()
        BoardEffectKind.WARP -> p.warp()
        BoardEffectKind.FIREFLIES -> p.fireflies(dim = false)
        BoardEffectKind.MOTES -> p.fireflies(dim = true)
        BoardEffectKind.EMBERS -> p.embers()
        BoardEffectKind.BUBBLES -> p.bubbles()
        BoardEffectKind.CONFETTI -> p.confetti()
        BoardEffectKind.FIREWORKS -> p.fireworks()
        BoardEffectKind.AURORA -> p.aurora()
        BoardEffectKind.NEBULA -> p.nebula()
        BoardEffectKind.LAVA -> p.lava()
        BoardEffectKind.WAVES -> p.waves()
        BoardEffectKind.SMOKE -> p.smoke()
        BoardEffectKind.DOT_GRID -> p.dotGrid()
        BoardEffectKind.CONTOUR -> p.contour()
        BoardEffectKind.EQUALIZER -> p.equalizer()
        BoardEffectKind.SCAN -> p.scan()
        BoardEffectKind.SHEEN -> p.sheen()
        BoardEffectKind.RIPPLES -> p.ripples()
        BoardEffectKind.ORBS -> p.orbs()
        // The press kinds are drawn from the presses themselves, in PressEffects.kt.
        else -> Unit
    }
}

/**
 * An effect's opacity at [intensity] under typing [energy] 0..1: brighter by
 * a share of the room left above it, so a faint board brightens visibly and a
 * bright one does not clip.
 */
internal fun typingAlpha(intensity: Float, energy: Float): Float {
    val base = intensity.coerceIn(0f, 1f)
    return (base + (1f - base) * TYPING_BRIGHTEN * energy.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/** The share of the room above an effect's opacity that full typing energy takes up. */
private const val TYPING_BRIGHTEN = 0.7f

/** A stable 0..1 value for particle [i]'s [salt]th property. */
private fun rnd(i: Int, salt: Int): Float {
    var h = i * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777215f
}

private fun frac(v: Float): Float = v - floor(v)

private class Painter(
    val d: DrawScope,
    val fx: KbBoardEffect,
    val t: Float,
    val e: Float,
    val origin: Offset,
) {
    val w = d.size.width
    val h = d.size.height
    val dp = d.density
    val scale = fx.scale
    /** Brighter under typing, most of the way to opaque at full energy. */
    val alpha = typingAlpha(fx.intensity, e)

    /** How many of a thing, by density and by how big the board is against a phone's. */
    fun count(base: Int, cap: Int = base * 3): Int {
        val area = (w * h) / (360f * 220f * dp * dp)
        return (base * (0.2f + 1.6f * fx.density) * area.coerceIn(0.5f, 2.5f)).toInt().coerceIn(1, cap)
    }

    fun color(i: Int): Color = fx.colors[i % fx.colors.size]

    fun glow(center: Offset, radius: Float, c: Color, a: Float) {
        if (radius <= 0f || a <= 0f) return
        d.drawCircle(
            Brush.radialGradient(
                0f to c.copy(alpha = a * c.alpha),
                0.35f to c.copy(alpha = a * c.alpha * 0.55f),
                1f to c.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius,
            center,
        )
    }

    fun snow() {
        val n = count(70)
        val fall = h + 20 * dp
        for (i in 0 until n) {
            val speed = (14f + 26f * rnd(i, 1)) * dp
            val y = frac(rnd(i, 2) + t * speed / fall) * fall - 10 * dp
            val x = rnd(i, 3) * w + sin(t * (0.5f + rnd(i, 4)) + rnd(i, 5) * 6f) * 12f * dp
            val r = (1f + 2.2f * rnd(i, 6)) * dp * scale
            d.drawCircle(lerp(color(i), Color.White, 0.65f).copy(alpha = alpha * (0.5f + 0.5f * rnd(i, 7))), r, Offset(x, y))
        }
    }

    fun rain() {
        val n = count(80)
        val slant = 0.2f
        for (i in 0 until n) {
            val len = (10f + 14f * rnd(i, 1)) * dp * scale
            val speed = (260f + 220f * rnd(i, 2)) * dp
            val span = h + len * 2
            val y = frac(rnd(i, 3) + t * speed / span) * span - len
            val x = frac(rnd(i, 4) + y * slant / w) * w
            d.drawLine(
                color(i).copy(alpha = alpha * (0.35f + 0.4f * rnd(i, 5))),
                Offset(x, y), Offset(x - len * slant, y - len),
                strokeWidth = 1.2f * dp, cap = StrokeCap.Round,
            )
        }
    }

    fun codeRain() {
        val cell = 11f * dp * scale
        val cols = (w / cell).toInt().coerceAtLeast(1)
        val trail = 14
        for (c in 0 until cols) {
            if (rnd(c, 9) > 0.25f + 0.75f * fx.density) continue
            val speed = (60f + 90f * rnd(c, 1)) * dp
            val span = h * 1.6f + trail * cell
            val head = frac(rnd(c, 2) + t * speed / span) * span - trail * cell
            val x = c * cell + cell * 0.2f
            for (k in 0 until trail) {
                val y = head - k * cell
                if (y < -cell || y > h) continue
                val fade = (1f - k / trail.toFloat()).pow(1.6f)
                // A glyph that changes now and then: three bars out of a hashed pattern.
                val glyph = (rnd(c * 31 + k, floor(t * 3f + rnd(c, k)).toInt()) * 7).toInt() + 1
                val tone = if (k == 0) lerp(color(0), Color.White, 0.7f) else color(c)
                val a = alpha * fade
                for (bar in 0 until 3) {
                    if ((glyph shr bar) and 1 == 0) continue
                    d.drawRect(
                        tone.copy(alpha = a),
                        Offset(x, y + bar * cell * 0.28f),
                        Size(cell * (0.35f + 0.25f * ((glyph + bar) % 2)), cell * 0.16f),
                    )
                }
            }
        }
    }

    fun bokeh() {
        val n = count(14, 40)
        for (i in 0 until n) {
            val r = (16f + 42f * rnd(i, 1)) * dp * scale
            val x = rnd(i, 2) * w + sin(t * 0.13f * (0.5f + rnd(i, 3)) + i) * 30 * dp
            val y = rnd(i, 4) * h + cos(t * 0.11f * (0.5f + rnd(i, 5)) + i) * 20 * dp
            val pulse = 0.55f + 0.45f * sin(t * (0.3f + 0.4f * rnd(i, 6)) + i * 1.7f)
            glow(Offset(x, y), r, color(i), alpha * 0.5f * pulse)
        }
    }

    /**
     * Expressive Keyboard's colour pools: a few big soft spots, each a plain
     * radial fade from its colour to nothing, wandering slowly round where it
     * started. Under glass keys they are what the glass has to show.
     */
    fun orbs() {
        val n = (2 + (fx.density * 4).toInt()).coerceIn(2, 6)
        val big = max(w, h)
        for (i in 0 until n) {
            val r = big * (0.28f + 0.22f * rnd(i, 1)) * scale
            val x = (rnd(i, 2) * 1.1f - 0.05f) * w + sin(t * 0.09f * (0.6f + rnd(i, 3)) + i * 2.1f) * w * 0.06f
            val y = (rnd(i, 4) * 1.2f - 0.1f) * h + cos(t * 0.07f * (0.6f + rnd(i, 5)) + i * 1.3f) * h * 0.12f
            val c = color(i)
            val center = Offset(x, y)
            d.drawCircle(
                Brush.radialGradient(
                    0f to c.copy(alpha = c.alpha * alpha),
                    1f to c.copy(alpha = 0f),
                    center = center,
                    radius = r,
                ),
                r,
                center,
            )
        }
    }

    fun starfield() {
        val n = count(100, 300)
        for (i in 0 until n) {
            val x = rnd(i, 1) * w
            val y = rnd(i, 2) * h
            val tw = 0.5f + 0.5f * sin(t * (0.8f + 2.2f * rnd(i, 3)) + rnd(i, 4) * 20f)
            val r = (0.5f + 1.3f * rnd(i, 5).pow(3)) * dp * scale
            val c = lerp(color(i), Color.White, 0.6f).copy(alpha = alpha * (0.25f + 0.75f * tw))
            d.drawCircle(c, r, Offset(x, y))
            if (rnd(i, 6) > 0.96f) {
                val g = r * 4f * tw
                d.drawLine(c, Offset(x - g, y), Offset(x + g, y), strokeWidth = 0.6f * dp)
                d.drawLine(c, Offset(x, y - g), Offset(x, y + g), strokeWidth = 0.6f * dp)
            }
        }
    }

    fun warp() {
        val n = count(90, 260)
        val cx = w / 2f
        val cy = h * 0.45f
        val far = sqrt(w * w + h * h) * 0.6f
        for (i in 0 until n) {
            val angle = rnd(i, 1) * 2f * PI.toFloat()
            val z = frac(rnd(i, 2) + t * 0.35f)
            val z0 = max(0f, z - 0.05f - 0.08f * e)
            val dx = cos(angle)
            val dy = sin(angle)
            val r1 = z * z * far
            val r0 = z0 * z0 * far
            d.drawLine(
                lerp(color(i), Color.White, 0.5f).copy(alpha = alpha * z),
                Offset(cx + dx * r0, cy + dy * r0), Offset(cx + dx * r1, cy + dy * r1),
                strokeWidth = (0.6f + 1.6f * z) * dp * scale, cap = StrokeCap.Round,
            )
        }
    }

    fun fireflies(dim: Boolean) {
        val n = count(if (dim) 40 else 22, 90)
        for (i in 0 until n) {
            val x0 = rnd(i, 1) * w
            val y0 = if (dim) frac(rnd(i, 2) - t * 0.01f * (0.5f + rnd(i, 7))) * h else rnd(i, 2) * h
            val x = x0 + sin(t * (0.2f + 0.3f * rnd(i, 3)) + i) * 28 * dp
            val y = y0 + cos(t * (0.17f + 0.3f * rnd(i, 4)) + i * 1.3f) * 18 * dp
            val blink = if (dim) 0.35f else max(0f, sin(t * (0.5f + rnd(i, 5)) + rnd(i, 6) * 9f)).pow(2)
            if (blink <= 0.01f) continue
            val r = (if (dim) 1.2f else 2f) * dp * scale
            glow(Offset(x, y), r * 5f, color(i), alpha * blink * 0.6f)
            d.drawCircle(lerp(color(i), Color.White, 0.6f).copy(alpha = alpha * blink), r, Offset(x, y))
        }
    }

    fun embers() {
        val n = count(45, 120)
        for (i in 0 until n) {
            val life = frac(rnd(i, 1) + t * (0.08f + 0.12f * rnd(i, 2)))
            val y = h + 10 * dp - life * (h + 30 * dp)
            val x = rnd(i, 3) * w + sin(t * 1.3f + i) * 10 * dp + life * (rnd(i, 4) - 0.5f) * 60 * dp
            val flicker = 0.6f + 0.4f * sin(t * 9f + i * 3f)
            val a = alpha * (1f - life) * flicker
            val r = (0.8f + 1.8f * rnd(i, 5)) * dp * scale
            glow(Offset(x, y), r * 4f, color(i), a * 0.5f)
            d.drawCircle(lerp(color(i), Color.White, 0.35f).copy(alpha = a), r, Offset(x, y))
        }
    }

    fun bubbles() {
        val n = count(22, 70)
        for (i in 0 until n) {
            val r = (4f + 9f * rnd(i, 1)) * dp * scale
            val span = h + r * 4
            val y = h + r * 2 - frac(rnd(i, 2) + t * (20f + 30f * rnd(i, 3)) * dp / span) * span
            val x = rnd(i, 4) * w + sin(t * 1.4f + i) * 6 * dp
            val c = color(i)
            d.drawCircle(c.copy(alpha = alpha * 0.08f), r, Offset(x, y))
            d.drawCircle(c.copy(alpha = alpha * 0.55f), r, Offset(x, y), style = Stroke(width = 1f * dp))
            d.drawCircle(Color.White.copy(alpha = alpha * 0.6f), r * 0.22f, Offset(x - r * 0.4f, y - r * 0.4f))
        }
    }

    fun confetti() {
        val n = count(55, 150)
        for (i in 0 until n) {
            val span = h + 20 * dp
            val y = frac(rnd(i, 1) + t * (30f + 40f * rnd(i, 2)) * dp / span) * span - 10 * dp
            val x = rnd(i, 3) * w + sin(t * (1f + rnd(i, 4)) + i) * 14 * dp
            val spin = t * (90f + 200f * rnd(i, 5)) + rnd(i, 6) * 360f
            val c = hueShift(color(i), (i % 5) * 47f)
            val pw = 4f * dp * scale
            val ph = 7f * dp * scale * (0.3f + 0.7f * kotlin.math.abs(cos(spin * PI.toFloat() / 180f)))
            d.rotate(spin, Offset(x, y)) {
                drawRect(c.copy(alpha = alpha), Offset(x - pw / 2, y - ph / 2), Size(pw, ph))
            }
        }
    }

    fun fireworks() {
        val shells = (2 + 4 * fx.density + 3 * e).toInt()
        val sparks = 26
        for (j in 0 until shells) {
            val period = 2.2f + 1.6f * rnd(j, 1)
            val phase = rnd(j, 2) * period
            val cycle = floor((t + phase) / period).toInt()
            val age = (t + phase) - cycle * period
            val life = 1.5f
            if (age > life) continue
            val cx = (0.1f + 0.8f * rnd(j * 97 + cycle, 3)) * w
            val cy = (0.15f + 0.4f * rnd(j * 97 + cycle, 4)) * h
            val c = hueShift(color(j + cycle), rnd(j * 97 + cycle, 5) * 60f - 30f)
            val fade = (1f - age / life).pow(1.3f)
            val reach = (55f + 35f * rnd(j, 6)) * dp * scale
            for (k in 0 until sparks) {
                val angle = 2f * PI.toFloat() * k / sparks + rnd(j, k)
                val dist = reach * (1f - (1f - age / life).pow(3))
                val fall = 22f * dp * age * age
                val x = cx + cos(angle) * dist
                val y = cy + sin(angle) * dist + fall
                val tail = dist * 0.82f
                d.drawLine(
                    c.copy(alpha = alpha * fade * 0.5f),
                    Offset(cx + cos(angle) * tail, cy + sin(angle) * tail + fall * 0.8f), Offset(x, y),
                    strokeWidth = 1f * dp, cap = StrokeCap.Round,
                )
                d.drawCircle(lerp(c, Color.White, 0.4f).copy(alpha = alpha * fade), 1.4f * dp * scale, Offset(x, y))
            }
        }
    }

    fun aurora() {
        val bands = 3
        val steps = 32
        for (b in 0 until bands) {
            val c = color(b)
            val top = Path()
            var minY = h
            val ys = FloatArray(steps + 1)
            val thick = FloatArray(steps + 1)
            for (s in 0..steps) {
                val x = w * s / steps
                val u = s / steps.toFloat()
                ys[s] = h * (0.18f + 0.14f * b) +
                    sin(u * 2f * PI.toFloat() * (1f + 0.5f * b) + t * 0.3f * (1f + 0.3f * b)) * h * 0.07f +
                    sin(u * 9f + t * 0.5f) * h * 0.02f
                thick[s] = h * (0.18f + 0.1f * sin(u * 5f + t * 0.4f + b))
                minY = min(minY, ys[s])
                if (s == 0) top.moveTo(x, ys[s]) else top.lineTo(x, ys[s])
            }
            for (s in steps downTo 0) top.lineTo(w * s / steps, ys[s] + thick[s])
            top.close()
            d.drawPath(
                top,
                Brush.verticalGradient(
                    0f to c.copy(alpha = 0f),
                    0.25f to c.copy(alpha = alpha * 0.45f),
                    1f to c.copy(alpha = 0f),
                    startY = minY,
                    endY = minY + h * 0.4f,
                ),
            )
        }
    }

    fun nebula() {
        for (i in 0 until 6) {
            val r = w * (0.3f + 0.25f * rnd(i, 1)) * scale
            val x = rnd(i, 2) * w + sin(t * 0.05f + i) * w * 0.08f
            val y = rnd(i, 3) * h + cos(t * 0.04f + i * 2f) * h * 0.1f
            glow(Offset(x, y), r, hueShift(color(i), sin(t * 0.03f + i) * 25f), alpha * 0.35f)
        }
        val stars = count(40, 120)
        for (i in 0 until stars) {
            val tw = 0.5f + 0.5f * sin(t * (1f + 2f * rnd(i, 7)) + i)
            d.drawCircle(Color.White.copy(alpha = alpha * 0.6f * tw), 0.8f * dp, Offset(rnd(i, 8) * w, rnd(i, 9) * h))
        }
    }

    fun lava() {
        val n = (5 + 5 * fx.density).toInt()
        for (i in 0 until n) {
            val r = w * (0.1f + 0.1f * rnd(i, 1)) * scale
            val y = h * (0.5f + 0.48f * sin(t * 0.12f * (0.6f + rnd(i, 2)) + rnd(i, 3) * 6f))
            val x = w * (0.08f + 0.84f * rnd(i, 4)) + sin(t * 0.07f + i) * w * 0.05f
            glow(Offset(x, y), r * 1.6f, color(i), alpha * 0.55f)
            d.drawCircle(color(i).copy(alpha = alpha * 0.5f), r * 0.7f, Offset(x, y))
        }
    }

    fun waves() {
        val layers = 3
        val steps = 40
        for (l in 0 until layers) {
            val path = Path()
            val base = h * (0.6f + 0.12f * l)
            path.moveTo(0f, h)
            for (s in 0..steps) {
                val u = s / steps.toFloat()
                val y = base + sin(u * 2f * PI.toFloat() * (1.2f + 0.4f * l) + t * (0.6f + 0.25f * l)) *
                    h * 0.035f * (1f + e) * scale
                path.lineTo(u * w, y)
            }
            path.lineTo(w, h)
            path.close()
            d.drawPath(path, color(l).copy(alpha = alpha * (0.16f + 0.08f * l)))
        }
    }

    fun smoke() {
        val n = (6 + 10 * fx.density).toInt()
        for (i in 0 until n) {
            val age = frac(rnd(i, 1) + t * 0.05f * (0.6f + rnd(i, 2)))
            val x = rnd(i, 3) * w + sin(t * 0.3f + i) * 20 * dp + age * (rnd(i, 4) - 0.5f) * 80 * dp
            val y = h * (1.05f - age * 1.15f)
            val r = (24f + 70f * age) * dp * scale
            glow(Offset(x, y), r, lerp(color(i), Color.Gray, 0.4f), alpha * 4f * age * (1f - age) * 0.4f)
        }
    }

    fun dotGrid() {
        val gap = 14f * dp * scale
        val cols = (w / gap).toInt() + 1
        val rows = (h / gap).toInt() + 1
        val now = SystemClock.uptimeMillis()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = c * gap + gap / 2
                val y = r * gap + gap / 2
                var lit = 0.5f + 0.5f * sin(x * 0.018f / dp + y * 0.012f / dp - t * 1.4f)
                ThemePulse.forEachRecent(now, RIPPLE_MS) { px, py, age ->
                    val dist = Offset(x - (px - origin.x), y - (py - origin.y)).getDistance()
                    val ring = age / 1000f * 160f * dp
                    val band = 1f - kotlin.math.abs(dist - ring) / (18f * dp)
                    if (band > 0f) lit = max(lit, band * (1f - age / RIPPLE_MS.toFloat()) * 1.6f)
                }
                d.drawCircle(color(0).copy(alpha = alpha * (0.12f + 0.55f * lit.coerceAtMost(1f))), 1.3f * dp * scale, Offset(x, y))
            }
        }
    }

    fun contour() {
        for (i in 0 until 3) {
            val cx = w * (0.2f + 0.6f * rnd(i, 1)) + sin(t * 0.08f + i) * w * 0.1f
            val cy = h * (0.2f + 0.6f * rnd(i, 2)) + cos(t * 0.07f + i) * h * 0.1f
            val step = 16f * dp * scale
            val drift = frac(t * 0.15f) * step
            for (k in 0 until 9) {
                val r = k * step + drift
                d.drawCircle(color(i).copy(alpha = alpha * 0.4f * (1f - k / 9f)), r, Offset(cx, cy), style = Stroke(width = 1f * dp))
            }
        }
    }

    fun equalizer() {
        val bars = (16 + 20 * fx.density).toInt()
        val bw = w / bars
        for (i in 0 until bars) {
            val level = 0.5f + 0.25f * sin(t * (2f + 3f * rnd(i, 1)) + i) + 0.25f * sin(t * (1.1f + rnd(i, 2)) * 2.3f + i * 0.7f)
            val bh = h * 0.4f * (0.12f + 0.88f * level) * (0.6f + 0.8f * e) * scale
            val c = color(i)
            d.drawRoundRect(
                Brush.verticalGradient(listOf(c.copy(alpha = alpha * 0.7f), c.copy(alpha = 0f)), startY = h - bh, endY = h),
                topLeft = Offset(i * bw + bw * 0.15f, h - bh),
                size = Size(bw * 0.7f, bh),
                cornerRadius = CornerRadius(bw * 0.2f),
            )
        }
    }

    fun scan() {
        val y = (frac(t * 0.22f) * 1.3f - 0.15f) * h
        val band = 36f * dp * scale
        val c = color(0)
        d.drawRect(
            Brush.verticalGradient(
                0f to c.copy(alpha = 0f), 0.85f to c.copy(alpha = alpha * 0.35f), 1f to c.copy(alpha = 0f),
                startY = y - band, endY = y + 2 * dp,
            ),
            Offset(0f, y - band), Size(w, band + 2 * dp),
        )
        d.drawLine(lerp(c, Color.White, 0.5f).copy(alpha = alpha * 0.7f), Offset(0f, y), Offset(w, y), strokeWidth = 1f * dp)
    }

    fun sheen() {
        val p = frac(t * 0.2f) * 1.8f - 0.4f
        val c = lerp(color(0), Color.White, 0.6f)
        val span = w + h
        val start = Offset(p * span - h * 0.4f, 0f)
        d.drawRect(
            Brush.linearGradient(
                0f to c.copy(alpha = 0f), 0.5f to c.copy(alpha = alpha * 0.35f), 1f to c.copy(alpha = 0f),
                start = start, end = Offset(start.x + 80f * dp * scale, start.y + 40f * dp * scale),
            ),
        )
    }

    fun ripples() {
        val n = (3 + 6 * fx.density).toInt()
        for (i in 0 until n) {
            val period = 2.6f + rnd(i, 1)
            val cycle = floor((t + rnd(i, 2) * period) / period).toInt()
            val age = frac((t + rnd(i, 2) * period) / period)
            val center = Offset(rnd(i * 13 + cycle, 3) * w, rnd(i * 13 + cycle, 4) * h)
            for (ring in 0 until 2) {
                val a = age - ring * 0.15f
                if (a <= 0f) continue
                d.drawCircle(
                    color(i).copy(alpha = alpha * 0.5f * (1f - a)), a * 46f * dp * scale, center,
                    style = Stroke(width = 1.2f * dp),
                )
            }
        }
        val now = SystemClock.uptimeMillis()
        ThemePulse.forEachRecent(now, RIPPLE_MS) { px, py, age ->
            val a = age / RIPPLE_MS.toFloat()
            d.drawCircle(
                color(1).copy(alpha = alpha * (1f - a)), a * 130f * dp * scale,
                Offset(px - origin.x, py - origin.y), style = Stroke(width = 1.6f * dp),
            )
        }
    }
}

/** The effects that draw the presses themselves, and so want them even when not reactive. */
internal val PRESS_DRAWN_EFFECTS = setOf(BoardEffectKind.RIPPLES, BoardEffectKind.DOT_GRID)

/** How long a press's ring travels across a reactive board. */
private const val RIPPLE_MS = 1100L
