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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.wasimaster.wmkeyboard.core.theme.BoardEffectKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The press kinds of the moving board ([BoardEffectKind.onPress]): a splash
 * of paint, ink, light, smoke or sparks thrown over the keys by each press and
 * gone again a second or so later.
 *
 * Same contract as the board's other painters: a splash is a pure function
 * of where and when its press happened, read off [ThemePulse], so nothing is
 * kept per press and nothing is allocated per frame. Every colour, soft-edged
 * brush and stroke a splash can use is built once per theme in [PressKit];
 * the soft shapes are one unit-sized radial brush per colour, moved and
 * scaled into place, so the shader is made once and never per frame.
 *
 * The frame loop sleeps between presses: it wakes on [ThemePulse.revision]
 * and stops once the newest press's splash has run its course.
 */

/** Everything a splash draws with, made once per theme. */
internal class PressKit(val effect: KbBoardEffect) {

    /**
     * The colours a press picks from by its seed: the theme's own when it
     * chose some, otherwise a wheel of bright hues, so each press differs.
     */
    val colors: Array<Color> =
        if (effect.ownColors) {
            Array(effect.colors.size) { effect.colors[it] }
        } else {
            Array(WHEEL) { Color.hsv(it * 360f / WHEEL, 0.72f, 1f) }
        }

    /** The same colours, lifted toward white, for glints and the hearts of lights. */
    val lights: Array<Color> = Array(colors.size) { lerp(colors[it], Color.White, 0.6f) }

    /** A soft round spot per colour, unit sized and centred on zero. */
    val glows: Array<Brush> = Array(colors.size) { softBrush(colors[it]) }

    /** The same spots greyed down, for smoke. */
    val smokes: Array<Brush> = Array(colors.size) { softBrush(lerp(colors[it], Color.Gray, 0.55f)) }

    /** The meld's shader and buffers, for that kind only. */
    val meld: MeldPaint? = if (effect.kind == BoardEffectKind.MELD) MeldPaint() else null

    /** The meld's bridge colour: the theme's own when it chose one, else the board's light or dark glass. */
    val meldTint: Color? = if (effect.ownColors) effect.colors.firstOrNull() else null

    /** How long one splash runs at the effect's speed. */
    val lifeMs: Long = (baseLifeMs(effect.kind) / effect.speed.coerceAtLeast(0.25f)).toLong()

    private var strokeDensity = 0f
    var ring: Stroke = Stroke(width = 2f)
        private set
    var arc: Stroke = Stroke(width = 2f, cap = StrokeCap.Round)
        private set

    /** Rebuilds the strokes only when the density changes, which is never on a running keyboard. */
    fun strokesFor(density: Float) {
        if (density == strokeDensity) return
        strokeDensity = density
        ring = Stroke(width = 2f * density * effect.scale)
        arc = Stroke(width = 2.6f * density * effect.scale, cap = StrokeCap.Round)
    }

    fun colorIndex(seed: Int): Int = ((splashRnd(seed, 997) * colors.size).toInt()).coerceIn(0, colors.size - 1)

    private companion object {
        const val WHEEL = 12

        fun softBrush(c: Color): Brush = Brush.radialGradient(
            0f to c,
            0.5f to c.copy(alpha = c.alpha * 0.75f),
            1f to c.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )

        fun baseLifeMs(kind: BoardEffectKind): Float = when (kind) {
            BoardEffectKind.PAINT -> 1500f
            BoardEffectKind.INK -> 1500f
            BoardEffectKind.LIGHT_POOL -> 900f
            BoardEffectKind.FLOOD -> 1100f
            BoardEffectKind.PUFF -> 1500f
            BoardEffectKind.GLINT -> 550f
            BoardEffectKind.POP -> 650f
            BoardEffectKind.SWOOSH -> 650f
            BoardEffectKind.SPECKS -> 900f
            BoardEffectKind.MELD -> 750f
            else -> 1000f
        }
    }
}

/**
 * The splashes over the keys, as the keyboard draws them: composed only for a
 * press kind and only while motion is allowed, asleep between presses.
 *
 * The meld needs the keys themselves, not just where the press was: [cells]
 * gives every key's cell in root space and [gapHDp], [gapVDp] the gap each
 * cell keeps round its face, [keyRadiusDp] the faces' corners.
 */
@Composable
internal fun PressEffectOverlay(
    effect: KbBoardEffect,
    modifier: Modifier = Modifier,
    cells: () -> List<Rect> = { emptyList() },
    keyRadiusDp: Float = 8f,
    gapHDp: Float = 0f,
    gapVDp: Float = 0f,
) {
    val kit = remember(effect) { PressKit(effect) }
    val night = LocalKbTheme.current.dark
    var now by remember { mutableLongStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val pulse = ThemePulse.revision
    LaunchedEffect(kit, pulse) {
        while (true) {
            val t = withFrameMillis { SystemClock.uptimeMillis() }
            now = t
            if (t - ThemePulse.lastPressAt > kit.lifeMs) break
        }
    }
    Canvas(modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val t = now
        if (t == 0L) return@Canvas
        kit.strokesFor(density)
        val at = origin
        val meld = kit.meld
        if (meld != null) {
            val gx = gapHDp * density
            val gy = gapVDp * density
            val faces = cells().map { Rect(it.left - at.x + gx, it.top - at.y + gy, it.right - at.x - gx, it.bottom - at.y - gy) }
            var shown = 0
            ThemePulse.forEachRecent(t, kit.lifeMs) { x, y, age ->
                if (shown < MELD_MAX_ACTIVE) {
                    meld.draw(this, faces, x - at.x, y - at.y, meldPress(age, kit.lifeMs), keyRadiusDp * density, night, kit.meldTint)
                    shown++
                }
            }
            return@Canvas
        }
        ThemePulse.forEachRecent(t, kit.lifeMs) { x, y, age ->
            splash(kit, x - at.x, y - at.y, age, seedOf(t - age))
        }
    }
}

/**
 * A press kind as a picture: a few splashes caught part way, for the theme
 * cards and the editor's mock-up, which have no presses of their own.
 */
@Composable
internal fun PressEffectSample(effect: KbBoardEffect, modifier: Modifier = Modifier) {
    val kit = remember(effect) { PressKit(effect) }
    Canvas(modifier = modifier) {
        kit.strokesFor(density)
        val life = kit.lifeMs
        if (kit.meld != null) {
            // One meld caught at its widest; three would overlap their little grids.
            splash(kit, size.width / 2f, size.height / 2f, (life * 0.2f).toLong(), 11)
            return@Canvas
        }
        splash(kit, size.width * 0.26f, size.height * 0.42f, (life * 0.22f).toLong(), 11)
        splash(kit, size.width * 0.62f, size.height * 0.32f, (life * 0.40f).toLong(), 23)
        splash(kit, size.width * 0.80f, size.height * 0.72f, (life * 0.12f).toLong(), 37)
    }
}

/**
 * The picker's preview of a moving board: the effect itself in a small tile,
 * a press kind splashing in its middle over and over, the others moving as on
 * the keyboard. [animate] false holds one frame, as reduce motion does.
 */
@Composable
fun BoardEffectSwatch(effect: KbBoardEffect, modifier: Modifier = Modifier, animate: Boolean = true) {
    if (!effect.kind.onPress) {
        BoardEffectLayer(effect, modifier, still = !animate)
        return
    }
    if (!animate) {
        PressEffectSample(effect, modifier)
        return
    }
    val kit = remember(effect) { PressKit(effect) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(kit) {
        while (true) withFrameMillis { now = it }
    }
    Canvas(modifier = modifier) {
        kit.strokesFor(density)
        val cycle = kit.lifeMs + SWATCH_PAUSE_MS
        val round = now / cycle
        splash(kit, size.width / 2f, size.height / 2f, now % cycle, seedOf(round * 7919L))
    }
}

/** The quiet gap between two splashes in a picker tile. */
private const val SWATCH_PAUSE_MS = 350L

/** About one key across, in dp, before the effect's size. */
private const val KEY_SPAN_DP = 36f

private const val TAU = (2 * PI).toFloat()

/** A stable 0..1 value for a splash's [salt]th property. */
private fun splashRnd(seed: Int, salt: Int): Float {
    var h = seed * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777215f
}

/** A press's seed, from the time it happened, so a splash keeps its shape frame to frame. */
private fun seedOf(pressedAt: Long): Int = (pressedAt xor (pressedAt ushr 21)).toInt() * -1640531535

private fun easeOut(u: Float): Float {
    val v = 1f - u.coerceIn(0f, 1f)
    return 1f - v * v * v
}

/** A soft round spot: [brush] is one of [PressKit.glows], moved to ([x], [y]) and grown to [r]. */
private fun DrawScope.softDot(brush: Brush, x: Float, y: Float, r: Float, alpha: Float) {
    if (r <= 0f || alpha <= 0.004f) return
    translate(x, y) {
        scale(r, r, pivot = Offset.Zero) {
            drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceAtMost(1f))
        }
    }
}

/** One press's splash, [age] ms after the press at ([cx], [cy]) in this layer's space. */
private fun DrawScope.splash(kit: PressKit, cx: Float, cy: Float, age: Long, seed: Int) {
    val fx = kit.effect
    val life = kit.lifeMs
    if (age < 0 || age >= life) return
    val u = age.toFloat() / life
    val dp = density
    val k = KEY_SPAN_DP * dp * fx.scale
    val ci = kit.colorIndex(seed)
    val base = kit.colors[ci]
    val a = fx.intensity
    when (fx.kind) {
        BoardEffectKind.PAINT -> paintSplat(kit, cx, cy, age, u, k, base, a, seed)
        BoardEffectKind.INK -> {
            val alpha = a * (1f - u).pow(1.3f) * min(1f, age / 80f)
            val r = k * (0.45f + 0.6f * easeOut(u))
            for (i in 0 until 5) {
                val ang = splashRnd(seed, i) * TAU
                val dist = r * 0.4f * splashRnd(seed, i + 20)
                softDot(
                    kit.glows[ci], cx + cos(ang) * dist, cy + sin(ang) * dist,
                    r * (0.55f + 0.4f * splashRnd(seed, i + 40)), alpha,
                )
            }
        }
        BoardEffectKind.LIGHT_POOL -> {
            val alpha = a * min(1f, age / 70f) * (1f - u).pow(1.5f)
            softDot(kit.glows[ci], cx, cy, k * (0.95f + 0.45f * easeOut(u)), alpha)
        }
        BoardEffectKind.FLOOD -> {
            val diagonal = sqrt(size.width * size.width + size.height * size.height)
            val alpha = a * 0.7f * sin(PI.toFloat() * u) * min(1f, age / 60f)
            softDot(kit.glows[ci], cx, cy, diagonal * (0.25f + 0.85f * easeOut(u)), alpha)
        }
        BoardEffectKind.PUFF -> {
            val drift = k * 0.9f * easeOut(u)
            val alpha = a * 0.65f * (1f - u) * min(1f, age / 120f)
            for (i in 0 until 4) {
                val x = cx + (splashRnd(seed, i) - 0.5f) * k * 0.8f + sin(u * 3f + i) * k * 0.1f
                val y = cy - drift * (0.6f + 0.6f * splashRnd(seed, i + 10))
                val r = k * (0.35f + 0.65f * u) * (0.7f + 0.5f * splashRnd(seed, i + 20))
                softDot(kit.smokes[ci], x, y, r, alpha)
            }
        }
        BoardEffectKind.GLINT -> {
            val swell = sin(PI.toFloat() * u)
            val c = kit.lights[ci]
            softDot(kit.glows[ci], cx, cy, k * 0.5f * swell, a * 0.6f * swell)
            val tone = c.copy(alpha = c.alpha * (a * swell).coerceAtMost(1f))
            val ray = k * 0.85f * swell
            val short = ray * 0.4f
            drawLine(tone, Offset(cx - ray, cy), Offset(cx + ray, cy), strokeWidth = 2f * dp, cap = StrokeCap.Round)
            drawLine(tone, Offset(cx, cy - ray), Offset(cx, cy + ray), strokeWidth = 2f * dp, cap = StrokeCap.Round)
            drawLine(tone, Offset(cx - short, cy - short), Offset(cx + short, cy + short), strokeWidth = 1.2f * dp, cap = StrokeCap.Round)
            drawLine(tone, Offset(cx - short, cy + short), Offset(cx + short, cy - short), strokeWidth = 1.2f * dp, cap = StrokeCap.Round)
        }
        BoardEffectKind.POP -> {
            val e = easeOut(u)
            val c = base.copy(alpha = base.alpha * (a * (1f - u)).coerceAtMost(1f))
            drawCircle(c, k * (0.25f + 0.65f * e), Offset(cx, cy), style = kit.ring)
            val bits = 6 + (fx.density * 6).toInt()
            for (i in 0 until bits) {
                val ang = TAU * i / bits + splashRnd(seed, i) * 0.6f
                val dist = k * (0.3f + 0.95f * e) * (0.8f + 0.4f * splashRnd(seed, i + 30))
                drawCircle(c, (2.6f - 1.6f * u) * dp * fx.scale, Offset(cx + cos(ang) * dist, cy + sin(ang) * dist))
            }
            // Two small plus marks among the bits.
            for (j in 0 until 2) {
                val ang = splashRnd(seed, 60 + j) * TAU
                val dist = k * (0.5f + 0.7f * e)
                val px = cx + cos(ang) * dist
                val py = cy + sin(ang) * dist
                val arm = 3f * dp * fx.scale
                drawLine(c, Offset(px - arm, py), Offset(px + arm, py), strokeWidth = 1.4f * dp)
                drawLine(c, Offset(px, py - arm), Offset(px, py + arm), strokeWidth = 1.4f * dp)
            }
        }
        BoardEffectKind.SWOOSH -> {
            val e = easeOut(u)
            for (j in 0 until 3) {
                val r = k * (0.42f + 0.18f * j) * (0.85f + 0.3f * e)
                val turn = if (j % 2 == 0) 220f else -220f
                val start = splashRnd(seed, j) * 360f + turn * e
                val c = base.copy(alpha = base.alpha * (a * (1f - u) * (1f - 0.2f * j)).coerceAtMost(1f))
                drawArc(
                    c, start, 70f + 50f * (1f - u), useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f), style = kit.arc,
                )
            }
        }
        BoardEffectKind.MELD -> kit.meld?.let { meld ->
            // A picture has no keys of its own: a little grid stands in for them.
            val faces = meldSampleFaces(cx, cy, k * 0.6f)
            val face = (if (fx.colors.isNotEmpty()) fx.colors.last() else Color.White).copy(alpha = 0.22f)
            val r = k * 0.12f
            for (f in faces) drawRoundRect(face, f.topLeft, f.size, CornerRadius(r))
            meld.draw(this, faces, cx, cy, meldPress(age, life), r, night = true, tint = kit.meldTint)
        }
        BoardEffectKind.SPECKS -> {
            val e = easeOut(u)
            val n = 8 + (fx.density * 14).toInt()
            for (i in 0 until n) {
                val ang = splashRnd(seed, i) * TAU
                val dist = k * (0.25f + 1.1f * splashRnd(seed, i + 30)) * (0.4f + 0.6f * e)
                val c = kit.colors[(ci + i) % kit.colors.size]
                val alpha = (a * (1f - u) * (0.5f + 0.5f * splashRnd(seed, i + 60))).coerceAtMost(1f)
                drawCircle(
                    c.copy(alpha = c.alpha * alpha), (0.9f + 1.5f * splashRnd(seed, i + 90)) * dp * fx.scale,
                    Offset(cx + cos(ang) * dist, cy + sin(ang) * dist),
                )
            }
        }
        else -> Unit
    }
}

/** A splat of paint: a round core with lobes, flung drops, and drips that run down as it dries. */
private fun DrawScope.paintSplat(kit: PressKit, cx: Float, cy: Float, age: Long, u: Float, k: Float, base: Color, a: Float, seed: Int) {
    val fx = kit.effect
    val dp = density
    val grow = easeOut(age / 110f)
    val fade = if (u < 0.55f) 1f else 1f - (u - 0.55f) / 0.45f
    val c = base.copy(alpha = base.alpha * (a * fade).coerceIn(0f, 1f))
    val r0 = k * 0.36f * grow
    if (r0 <= 0f) return
    drawCircle(c, r0, Offset(cx, cy))
    val lobes = 5 + (fx.density * 5).toInt()
    for (i in 0 until lobes) {
        val ang = splashRnd(seed, i) * TAU
        val dist = r0 * (0.55f + 0.45f * splashRnd(seed, i + 40))
        drawCircle(c, r0 * (0.32f + 0.3f * splashRnd(seed, i + 80)), Offset(cx + cos(ang) * dist, cy + sin(ang) * dist))
    }
    val spikes = 4 + (fx.density * 4).toInt()
    for (i in 0 until spikes) {
        val ang = splashRnd(seed, i + 120) * TAU
        val dx = cos(ang)
        val dy = sin(ang)
        val reach = r0 * (1.35f + 0.9f * splashRnd(seed, i + 160))
        drawLine(
            c, Offset(cx + dx * r0 * 0.6f, cy + dy * r0 * 0.6f), Offset(cx + dx * reach, cy + dy * reach),
            strokeWidth = 2.2f * dp * fx.scale * grow, cap = StrokeCap.Round,
        )
        val drop = reach + r0 * 0.3f
        drawCircle(c, 1.8f * dp * fx.scale * grow + r0 * 0.08f, Offset(cx + dx * drop, cy + dy * drop))
    }
    // The drips start once the splat has landed and slow as they run.
    val run = easeOut((age - 120f) / (kit.lifeMs * 0.7f))
    if (age < 120 || run <= 0f) return
    val drips = 2 + (splashRnd(seed, 200) * 2f).toInt()
    for (i in 0 until drips) {
        val x = cx + (splashRnd(seed, i + 210) - 0.5f) * r0 * 1.4f
        val top = cy + r0 * 0.4f
        val len = k * (0.35f + 0.6f * splashRnd(seed, i + 230)) * run
        val w = (2.4f + 1.4f * splashRnd(seed, i + 250)) * dp * fx.scale
        drawLine(c, Offset(x, top), Offset(x, top + len), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(c, w * 0.75f, Offset(x, top + len))
    }
}
