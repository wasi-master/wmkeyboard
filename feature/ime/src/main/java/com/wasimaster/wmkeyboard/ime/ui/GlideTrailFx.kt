package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.wasimaster.wmkeyboard.core.theme.TouchRingMode
import com.wasimaster.wmkeyboard.core.theme.TrailSparkleKind
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * The glide trail's theme looks (gintype-style typing effects, built on this
 * keyboard's own trail): colours running along the comet and a glow round it,
 * sparkles the stroke sheds, a burst where a glide ends, rings round a touch,
 * and the keys a glide crosses answering as if pressed. The comet's geometry
 * is the trail's own ([GlideTrail], [sampleEdge]); this file only colours it
 * and adds what flies off it.
 */

// ---- colour ------------------------------------------------------------------

/** Past this the flow clock wraps, so a long uptime keeps the float precise. */
private const val FLOW_WRAP_MS = 600_000L

/**
 * Where the trail's colours have flowed to at [nowMs], in turns; NaN for a
 * trail whose colours hold still. Flow speeds come in steps of 0.05 turns a
 * second, which puts a whole number of turns in [FLOW_WRAP_MS], so the wrap
 * does not jump.
 */
internal fun KbTrail.flowPhase(nowMs: Long): Float =
    if (flow == 0f || colors.size < 2) Float.NaN else (nowMs % FLOW_WRAP_MS) / 1000f * flow % 1f

/**
 * The trail's colour [u] of the way from its tail (0) to its head (1). A still
 * trail ([phase] NaN) runs once through [colors], first at the tail and last at
 * the head; a flowing one runs round them, shifted by [phase], so each colour
 * moves toward the finger and the last leads back into the first. [solid] is
 * the colour when the theme gave none.
 */
internal fun trailColorAt(colors: List<Color>, u: Float, phase: Float, solid: Color): Color {
    val n = colors.size
    if (n == 0) return solid
    if (n == 1) return colors[0]
    if (phase.isNaN()) {
        val x = u.coerceIn(0f, 1f) * (n - 1)
        val i = x.toInt().coerceAtMost(n - 2)
        return lerp(colors[i], colors[i + 1], x - i)
    }
    val wrapped = (u - phase) % 1f
    val x = (if (wrapped < 0f) wrapped + 1f else wrapped) * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerp(colors[i], colors[(i + 1) % n], x - i)
}

// ---- the comet ---------------------------------------------------------------

/** The glow's two passes: how much wider than the line, and how strong, at full glow. */
internal const val GLOW_OUTER_WIDTH = 2.8f
internal const val GLOW_OUTER_ALPHA = 0.16f
internal const val GLOW_INNER_WIDTH = 1.7f
internal const val GLOW_INNER_ALPHA = 0.3f

/**
 * One pass of the comet: each sample a piece of ribbon that fades and thins
 * with age, so the tail dissolves behind the finger instead of leaving the
 * whole path on screen. [headWidth] is the width under the finger; the glow
 * draws two more passes, wider and fainter, under the line.
 *
 * The comet is *filled* as a ribbon rather than stroked as a chain of
 * round-capped lines, in one piece per sample.
 *
 * A round cap put a whole disc at both ends of every segment, so each interior
 * sample was painted twice — and translucent paint over translucent paint
 * composites: 0.55 over 0.55 is 0.80. Every sample showed as a brighter bead,
 * worst at the head where the stroke is widest, which read as the trail
 * stuttering rather than flowing.
 *
 * A piece runs from the midpoint of the edge behind it to the midpoint of the
 * edge ahead, curving through the sample's own edge point as the control.
 * Neighbours therefore meet at that midpoint sharing a tangent as well as a
 * point: no pixel is covered twice (no beads), no pixel is left uncovered (no
 * seam), and the ribbon reads as a curve rather than as the polygon the raw
 * samples spell. It also closes the gaps the old draw left when a fast flick
 * spaced the samples further apart than the stroke was thick.
 *
 * Per-piece rather than one path for the whole trail because the age fade is
 * per sample, and so is the colour once a theme gives the trail several: one
 * path can carry only one of each. The cost is a drawPath per sample where the
 * old one cost a drawLine.
 */
internal fun DrawScope.drawCometPass(
    stroke: GlideTrail,
    count: Int,
    trailMs: Long,
    headWidth: Float,
    quad: Path,
    opacity: Float,
    solid: Color,
    colors: List<Color>,
    phase: Float,
) {
    if (count <= 0 || opacity <= 0f) return
    val tailWidth = headWidth * 0.3f
    val last = (count - 1).coerceAtLeast(1).toFloat()
    fun edgeAt(i: Int): Offset = stroke.sampleEdge(
        i,
        count,
        stroke.sampleHalfWidth(i, trailMs, headWidth, tailWidth),
    )
    var prevEdge = edgeAt(0)
    var edge = prevEdge
    for (i in 0 until count) {
        val nextEdge = if (i + 1 < count) edgeAt(i + 1) else edge
        val life = stroke.sampleLife(i, trailMs)
        if (life > 0f) {
            val here = Offset(stroke.x(i), stroke.y(i))
            val back = if (i > 0) Offset(stroke.x(i - 1), stroke.y(i - 1)) else here
            val ahead = if (i + 1 < count) Offset(stroke.x(i + 1), stroke.y(i + 1)) else here
            // The tail end and the head end have no neighbour to meet halfway,
            // so they take the sample itself and the ribbon simply stops there.
            val fromLeft = (back + prevEdge + here + edge) / 2f
            val toLeft = (here + edge + ahead + nextEdge) / 2f
            val fromRight = (back - prevEdge + here - edge) / 2f
            val toRight = (here - edge + ahead - nextEdge) / 2f
            quad.reset()
            quad.moveTo(fromLeft.x, fromLeft.y)
            quad.quadraticTo(here.x + edge.x, here.y + edge.y, toLeft.x, toLeft.y)
            if (i == count - 1) {
                // The head would be cut square otherwise, which shows as soon
                // as the finger lifts and the trail is left on screen to fade.
                // One quadratic bulging forward by the half width rounds it
                // off, inside the same fill so it costs no second coverage. The
                // forward direction is the edge offset turned a quarter turn,
                // so it needs no second derivation.
                quad.quadraticTo(here.x + edge.y * 2f, here.y - edge.x * 2f, toRight.x, toRight.y)
            } else {
                quad.lineTo(toRight.x, toRight.y)
            }
            quad.quadraticTo(here.x - edge.x, here.y - edge.y, fromRight.x, fromRight.y)
            quad.close()
            val color = trailColorAt(colors, i / last, phase, solid)
            drawPath(quad, color = color.copy(alpha = opacity * life))
        }
        prevEdge = edge
        edge = nextEdge
    }
}

// ---- sparkles ----------------------------------------------------------------

/** The four shapes a sparkle can be; [TrailSparkleKind.MIXED] picks one per sparkle. */
internal const val SPARK_STAR = 0
internal const val SPARK_STREAK = 1
internal const val SPARK_FLAKE = 2
internal const val SPARK_MOTE = 3

internal fun sparkShapeFor(kind: TrailSparkleKind): Int = when (kind) {
    TrailSparkleKind.SPARKLES -> SPARK_STAR
    TrailSparkleKind.SPARKS -> SPARK_STREAK
    TrailSparkleKind.CONFETTI -> SPARK_FLAKE
    TrailSparkleKind.DUST -> SPARK_MOTE
    TrailSparkleKind.MIXED -> Random.nextInt(4)
}

/**
 * What the glide trail throws off, built like the press burst's
 * [ParticleField]: plain arrays invisible to Compose, one `revision` the draw
 * reads, one `active` flag that composes the layer and runs its clock, and
 * stateless physics, so a sparkle's place is worked out from its age. Shapes
 * are drawn, not bitmaps: a star, a streak, a flake, a speck, each in the
 * colour it was given when it was thrown.
 */
@Stable
internal class TrailSparkField {

    val x = FloatArray(CAPACITY)
    val y = FloatArray(CAPACITY)
    val vx = FloatArray(CAPACITY)
    val vy = FloatArray(CAPACITY)
    val sizePx = FloatArray(CAPACITY)
    val spinDegPerS = FloatArray(CAPACITY)
    val gravityPxS2 = FloatArray(CAPACITY)
    val bornAt = LongArray(CAPACITY)
    val lifeMs = IntArray(CAPACITY)
    val shape = IntArray(CAPACITY)
    val argb = IntArray(CAPACITY)

    private var head = 0

    var revision by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set

    /** The clock's last stamp, on [SystemClock.uptimeMillis] like every spawn. */
    var nowMs by mutableLongStateOf(0L)
        private set

    /**
     * Throws one sparkle of [shapeId] from ([cx], [cy]) in [argbColor]. Along
     * the line ([burst] false) it drifts off sideways from the direction of
     * travel ([dirX], [dirY]); in a burst it flies out every way, faster.
     */
    fun emit(
        cx: Float,
        cy: Float,
        dirX: Float,
        dirY: Float,
        shapeId: Int,
        argbColor: Int,
        now: Long,
        density: Float,
        burst: Boolean,
    ) {
        val i = head
        head = (head + 1) % CAPACITY
        val p = SHAPES[shapeId.coerceIn(0, SHAPES.size - 1)]
        val angle = if (burst || shapeId != SPARK_STREAK) {
            Random.nextFloat() * 2f * PI.toFloat()
        } else {
            // A streak leaves the line to one side, the way sparks fly off a grinder.
            val side = if (Random.nextBoolean()) 1f else -1f
            atan2(dirY, dirX) + side * (PI.toFloat() / 2f) + (Random.nextFloat() - 0.5f) * 1.6f
        }
        val speedDp = if (burst) p.burstSpeedMin + Random.nextFloat() * p.burstSpeedSpan
            else p.speedMin + Random.nextFloat() * p.speedSpan
        x[i] = cx
        y[i] = cy
        vx[i] = cos(angle) * speedDp * density
        vy[i] = sin(angle) * speedDp * density
        sizePx[i] = (p.sizeMin + Random.nextFloat() * p.sizeSpan) * density
        spinDegPerS[i] = Random.nextFloat() * 720f - 360f
        gravityPxS2[i] = p.gravity * density
        lifeMs[i] = (p.lifeMin + Random.nextInt(p.lifeSpan)) * (if (burst) 5 else 4) / 4
        shape[i] = shapeId
        argb[i] = argbColor
        bornAt[i] = now
        nowMs = now
        revision++
        if (!active) active = true
    }

    /** One frame: repaints, and sleeps once every sparkle has gone out. */
    fun frame(now: Long) {
        nowMs = now
        var any = false
        for (i in 0 until CAPACITY) {
            if (bornAt[i] > 0 && now - bornAt[i] < lifeMs[i]) {
                any = true
                break
            }
        }
        if (!any) active = false
    }

    fun clear() {
        bornAt.fill(0L)
        active = false
    }

    /** How each shape moves, in dp, dp/s, dp/s² and ms. */
    private class ShapePhysics(
        val speedMin: Float,
        val speedSpan: Float,
        val burstSpeedMin: Float,
        val burstSpeedSpan: Float,
        val gravity: Float,
        val lifeMin: Int,
        val lifeSpan: Int,
        val sizeMin: Float,
        val sizeSpan: Float,
    )

    companion object {
        const val CAPACITY = 192

        private val SHAPES = arrayOf(
            // Stars hang and drift up a little as they twinkle out.
            ShapePhysics(15f, 45f, 80f, 140f, -12f, 450, 400, 5f, 4f),
            // Streaks are thrown hard and fall fast.
            ShapePhysics(90f, 110f, 180f, 200f, 520f, 260, 200, 7f, 5f),
            // Flakes flutter down.
            ShapePhysics(40f, 70f, 120f, 160f, 380f, 600, 400, 3.5f, 2.5f),
            // Dust barely moves.
            ShapePhysics(8f, 28f, 60f, 120f, 30f, 350, 450, 1.6f, 1.4f),
        )
    }
}

/** How far a glide travels between two sparkles, at an amount of 1. */
private const val SHED_SPACING_DP = 9f

/** The most sparkles one stroke sheds in a frame; a flung finger sheds no more. */
private const val MAX_SHED_PER_FRAME = 8

/** Sparkles in a lift burst at an amount of 1. */
private const val BURST_SPARKLES = 16

/** The step a glide is walked in to find the keys it crossed between two frames. */
private const val CROSS_STEP_DP = 8f

/** A unit four-point star, outer points at 1, for [drawSparkShape] to scale. */
internal fun unitStar(): Path = Path().apply {
    val inner = 0.26f
    moveTo(0f, -1f)
    quadraticTo(inner * 0.35f, -inner * 0.35f, 1f, 0f)
    quadraticTo(inner * 0.35f, inner * 0.35f, 0f, 1f)
    quadraticTo(-inner * 0.35f, inner * 0.35f, -1f, 0f)
    quadraticTo(-inner * 0.35f, -inner * 0.35f, 0f, -1f)
    close()
}

/**
 * Draws one sparkle of [shapeId] at ([px], [py]), [sizePx] across, in [color] at
 * [alpha]. [dirX], [dirY] is where a streak is heading (its tail trails behind),
 * [turnDeg] how far a flake has turned, [twinkle] 0..1 how bright a star or a
 * speck is this frame. [star] is [unitStar], made once by the caller. Shared
 * with the editor's picker tile.
 */
internal fun DrawScope.drawSparkShape(
    shapeId: Int,
    px: Float,
    py: Float,
    sizePx: Float,
    color: Color,
    alpha: Float,
    dirX: Float,
    dirY: Float,
    turnDeg: Float,
    twinkle: Float,
    star: Path,
) {
    if (alpha <= 0f) return
    when (shapeId) {
        SPARK_STAR -> {
            val r = sizePx / 2f * (0.65f + 0.35f * twinkle)
            translate(px, py) {
                scale(r, r, pivot = Offset.Zero) { drawPath(star, color, alpha = alpha) }
            }
        }
        SPARK_STREAK -> {
            val len = hypot(dirX, dirY)
            if (len > 0f) {
                drawLine(
                    color,
                    start = Offset(px, py),
                    end = Offset(px - dirX / len * sizePx, py - dirY / len * sizePx),
                    strokeWidth = (sizePx * 0.26f).coerceAtLeast(1f),
                    cap = StrokeCap.Round,
                    alpha = alpha,
                )
            }
        }
        SPARK_FLAKE -> rotate(turnDeg, pivot = Offset(px, py)) {
            drawRect(color, Offset(px - sizePx / 2f, py - sizePx * 0.3f), Size(sizePx, sizePx * 0.6f), alpha = alpha)
        }
        else -> drawCircle(color, sizePx / 2f, Offset(px, py), alpha = alpha * (0.55f + 0.45f * twinkle))
    }
}

/**
 * The sparkle layer over the trail: composed only while sparkles live, its
 * clock stopping with them, like the press burst's.
 */
@Composable
internal fun BoxScope.TrailSparksOverlay(field: TrailSparkField) {
    if (!field.active) return
    val star = remember { unitStar() }
    LaunchedEffect(field) {
        while (field.active) {
            withFrameMillis { field.frame(SystemClock.uptimeMillis()) }
        }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
        field.revision
        val now = field.nowMs
        for (i in 0 until TrailSparkField.CAPACITY) {
            val born = field.bornAt[i]
            if (born == 0L) continue
            val age = now - born
            val life = field.lifeMs[i]
            if (age < 0 || age >= life) continue
            val t = age / 1000f
            val g = field.gravityPxS2[i]
            val px = field.x[i] + field.vx[i] * t
            val py = field.y[i] + field.vy[i] * t + 0.5f * g * t * t
            val remaining = 1f - age / life.toFloat()
            drawSparkShape(
                shapeId = field.shape[i],
                px = px,
                py = py,
                // A sparkle shrinks a little as it goes out.
                sizePx = field.sizePx[i] * (0.55f + 0.45f * remaining),
                color = Color(field.argb[i]),
                alpha = remaining,
                dirX = field.vx[i],
                dirY = field.vy[i] + g * t,
                turnDeg = field.spinDegPerS[i] * t,
                twinkle = 0.5f + 0.5f * sin(age * 0.03f + i * 1.7f),
                star = star,
            )
        }
    }
}

// ---- touch rings -------------------------------------------------------------

/** How long a ring takes to spread out and fade. */
internal const val RING_MS = 520L

/**
 * The rings round a touch: where each one went down or lifted, and when. Fed
 * from the typing grid's own pointer pass ([touchBloomFingers]) with the
 * theme's [TouchRingMode], and drawn by [TouchRingsOverlay]. One per process,
 * like [TouchBloom]; positions are in root space.
 */
internal object TouchRings {

    private const val CAPACITY = 12

    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val bornAt = LongArray(CAPACITY)
    private var head = 0

    var revision by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set
    var nowMs by mutableLongStateOf(0L)
        private set

    fun add(x: Float, y: Float, now: Long) {
        xs[head] = x
        ys[head] = y
        bornAt[head] = now
        head = (head + 1) % CAPACITY
        nowMs = now
        revision++
        if (!active) active = true
    }

    fun frame(now: Long) {
        nowMs = now
        if (bornAt.none { it > 0 && now - it < RING_MS }) active = false
    }

    /** Calls [each] with every ring still spreading: its root position and how far through it is, 0..1. */
    inline fun forEachLive(now: Long, each: (x: Float, y: Float, progress: Float) -> Unit) {
        for (i in 0 until capacity()) {
            val born = bornAtIndex(i)
            if (born == 0L) continue
            val age = now - born
            if (age < 0 || age >= RING_MS) continue
            each(xAt(i), yAt(i), age / RING_MS.toFloat())
        }
    }

    @PublishedApi internal fun capacity(): Int = CAPACITY
    @PublishedApi internal fun bornAtIndex(i: Int): Long = bornAt[i]
    @PublishedApi internal fun xAt(i: Int): Float = xs[i]
    @PublishedApi internal fun yAt(i: Int): Float = ys[i]
}

/** The smallest a ring starts, in dp. */
private const val RING_START_DP = 6f

/**
 * The touch rings, in [color], spreading to [sizeDp] across. [origin] is this
 * layer's root offset, read in the draw. Composed only while a ring spreads.
 */
@Composable
internal fun BoxScope.TouchRingsOverlay(color: Color, sizeDp: Float, origin: () -> Offset) {
    if (!TouchRings.active) return
    LaunchedEffect(Unit) {
        while (TouchRings.active) {
            withFrameMillis { TouchRings.frame(SystemClock.uptimeMillis()) }
        }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
        TouchRings.revision
        val now = TouchRings.nowMs
        val at = origin()
        val start = RING_START_DP * density
        val end = (sizeDp * density / 2f).coerceAtLeast(start * 2f)
        TouchRings.forEachLive(now) { x, y, progress ->
            val left = 1f - progress
            // Fast out, slowing as it spreads, like a ripple.
            val reach = 1f - left * left * left
            val radius = start + (end - start) * reach
            val center = Offset(x - at.x, y - at.y)
            drawCircle(color, radius * 0.92f, center, alpha = left * left * 0.14f)
            drawCircle(
                color,
                radius,
                center,
                alpha = (left * 0.9f).coerceIn(0f, 1f),
                style = Stroke(width = (1f + 3f * left) * density),
            )
        }
    }
}

// ---- the glide's effects -----------------------------------------------------

/**
 * Runs what a glide does beyond drawing its line, a frame at a time while any
 * of [strokes] is on screen: lights each key the finger crosses ([lightKeys],
 * through [ThemePulse], which the key lighting, the press splashes and a
 * reacting board read), sheds the trail's sparkles into [sparks] as the finger
 * moves, and throws a burst where a glide lifts. [origin] is the grid's root
 * offset; the strokes are in the grid's space and [keyRects] in root space.
 * [solid] is the trail's one colour, for a theme that gave it no others.
 */
@Composable
internal fun GlideTrailFx(
    strokes: Array<GlideTrail>,
    trail: KbTrail,
    lightKeys: Boolean,
    sparks: TrailSparkField,
    keyRects: KeyRects,
    origin: () -> Offset,
    solid: Color,
    headWidthDp: Float,
) {
    if (!lightKeys && !trail.sheds) return
    // Read here so the loop starts with a glide and stops after it; each flips
    // twice a stroke, so this recomposes twice a stroke and never per sample.
    if (strokes.none { it.visible }) return
    val density = LocalDensity.current.density
    LaunchedEffect(trail, lightKeys, solid, headWidthDp, density) {
        val n = strokes.size
        val lastX = FloatArray(n) { Float.NaN }
        val lastY = FloatArray(n)
        val carry = FloatArray(n)
        val lastCell = arrayOfNulls<Rect>(n)
        val held = BooleanArray(n)
        val crossStep = CROSS_STEP_DP * density
        val spacing = SHED_SPACING_DP * density / trail.sparkleAmount
        val jitter = headWidthDp * density * 0.35f
        val shed = trail.sparkles
        while (true) {
            val now = withFrameMillis { SystemClock.uptimeMillis() }
            var any = false
            for (i in 0 until n) {
                val s = strokes[i]
                if (s.visible) any = true
                // A chord's band and a flick's line are not glides: they light
                // nothing on the way and throw nothing.
                val gliding = s.visible && !s.released && !s.straight && !s.flick
                if (!gliding) {
                    if (held[i] && trail.liftBurst && s.visible && s.released && !s.straight && !s.flick) {
                        burst(sparks, s.headX, s.headY, trail, shed, solid, now, density)
                    }
                    held[i] = false
                    lastX[i] = Float.NaN
                    continue
                }
                held[i] = true
                if (lastX[i].isNaN()) {
                    // Not before its first sample: until then its start is the
                    // last stroke's, and the walk from there would light keys
                    // this one never went near.
                    if (s.sampleCount(s.revision) == 0) continue
                    // The key the stroke started on lit at its own touch-down.
                    lastX[i] = s.startX
                    lastY[i] = s.startY
                    carry[i] = 0f
                    lastCell[i] = keyRects.cellAt(origin() + Offset(s.startX, s.startY))
                }
                val hx = s.headX
                val hy = s.headY
                val dx = hx - lastX[i]
                val dy = hy - lastY[i]
                val dist = hypot(dx, dy)
                if (dist < 0.5f) continue
                if (lightKeys) {
                    lastCell[i] = lightCrossed(keyRects, origin(), lastX[i], lastY[i], dx, dy, dist, crossStep, lastCell[i])
                }
                if (shed != null) {
                    var c = carry[i] + dist
                    var thrown = 0
                    if (c >= spacing) {
                        val head = trailColorAt(trail.colors, 1f, trail.flowPhase(now), solid)
                        val px = -dy / dist
                        val py = dx / dist
                        while (c >= spacing && thrown < MAX_SHED_PER_FRAME) {
                            c -= spacing
                            val along = (1f - c / dist).coerceIn(0f, 1f)
                            val off = (Random.nextFloat() * 2f - 1f) * jitter
                            val shape = sparkShapeFor(shed)
                            sparks.emit(
                                lastX[i] + dx * along + px * off,
                                lastY[i] + dy * along + py * off,
                                dx, dy, shape, sparkColor(shape, head, trail.colors), now, density, burst = false,
                            )
                            thrown++
                        }
                        // A finger faster than the cap would bank sparkles it can never throw.
                        if (thrown == MAX_SHED_PER_FRAME) c = 0f
                    }
                    carry[i] = c
                }
                lastX[i] = hx
                lastY[i] = hy
            }
            if (!any) break
        }
    }
}

/**
 * Lights each key the glide crossed on its way from ([x0], [y0]) by ([dx], [dy]),
 * walked in steps of [step] so a fast finger that jumped a key between two
 * frames still lights it. [last] is the key lit last, which stays lit rather
 * than lighting again while the finger is on it. Returns the key lit last.
 */
private fun lightCrossed(
    rects: KeyRects,
    origin: Offset,
    x0: Float,
    y0: Float,
    dx: Float,
    dy: Float,
    dist: Float,
    step: Float,
    last: Rect?,
): Rect? {
    var current = last
    val steps = ceil(dist / step).toInt().coerceIn(1, 64)
    for (k in 1..steps) {
        val f = k / steps.toFloat()
        val cell = rects.cellAt(Offset(origin.x + x0 + dx * f, origin.y + y0 + dy * f)) ?: continue
        if (cell != current) {
            ThemePulse.press(cell.center)
            current = cell
        }
    }
    return current
}

/** A lift burst of the trail's sparkles, or of stars when it sheds none along the line. */
private fun burst(
    sparks: TrailSparkField,
    x: Float,
    y: Float,
    trail: KbTrail,
    shed: TrailSparkleKind?,
    solid: Color,
    now: Long,
    density: Float,
) {
    val kind = shed ?: TrailSparkleKind.SPARKLES
    val head = trailColorAt(trail.colors, 1f, trail.flowPhase(now), solid)
    val count = (BURST_SPARKLES * trail.sparkleAmount).roundToInt().coerceIn(6, 48)
    repeat(count) {
        val shape = sparkShapeFor(kind)
        sparks.emit(x, y, 0f, 0f, shape, sparkColor(shape, head, trail.colors), now, density, burst = true)
    }
}

/**
 * A sparkle's colour: confetti in any of the trail's colours, the rest in the
 * colour at the head made paler, so they read as light rather than as paint.
 */
private fun sparkColor(shape: Int, head: Color, colors: List<Color>): Int = when (shape) {
    SPARK_FLAKE -> (if (colors.isEmpty()) head else colors[Random.nextInt(colors.size)]).toArgb()
    SPARK_STREAK -> lerp(head, Color.White, 0.3f).toArgb()
    else -> lerp(head, Color.White, 0.5f).toArgb()
}
