package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.theme.BoardEffectKind
import com.wasimaster.wmkeyboard.core.theme.BoardEffectSpec
import com.wasimaster.wmkeyboard.core.theme.KeyLightingPattern
import com.wasimaster.wmkeyboard.core.theme.KeyLightingSpec
import com.wasimaster.wmkeyboard.core.theme.KeyLightingTarget
import com.wasimaster.wmkeyboard.core.theme.KeyPressMotion
import com.wasimaster.wmkeyboard.core.theme.TrailSparkleKind

/*
 * The small moving tiles the theme editor's pickers show beside each press
 * effect, key colour and key motion, drawn by the same code the keyboard
 * draws them with, so a tile is the effect itself and not a picture of it.
 * Each plays one press over and over; [animate] false holds a single frame,
 * which is what the editor passes under reduce motion.
 */

/** The colours a tile is drawn in: the theme's own, as stored ARGB. */
@Immutable
data class SwatchColors(val board: Long, val key: Long, val keyText: Long, val accent: Long)

/** How big a picker tile is. */
val EffectSwatchSize: Dp = 44.dp

/** A moving-board kind in a tile: the effect over the board, a press kind splashing on a key in the middle. */
@Composable
fun BoardEffectPickerTile(
    kind: BoardEffectKind,
    spec: BoardEffectSpec?,
    colors: SwatchColors,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val effect = remember(kind, spec, colors.accent, colors.keyText) {
        resolveBoardEffect(
            (spec ?: BoardEffectSpec(kind = kind.name)).copy(kind = kind.name),
            Color(colors.accent.toInt()),
            Color(colors.keyText.toInt()),
        )
    } ?: return
    SwatchFrame(colors, modifier) {
        if (kind.onPress) MiniKey(colors)
        // Only the press kinds move here: two dozen moving boards running at
        // once in a list would cost more than the list is worth.
        BoardEffectSwatch(effect, Modifier.matchParentSize(), animate = animate && kind.onPress)
    }
}

/**
 * A key lighting in a tile: one key lit and fading again as a press would,
 * each press in its own colour when the colour mode gives it one.
 */
@Composable
fun KeyLightPickerTile(spec: KeyLightingSpec, colors: SwatchColors, animate: Boolean, modifier: Modifier = Modifier) {
    val accent = Color(colors.accent.toInt())
    val lighting = remember(spec, colors.accent) {
        resolveLighting(spec, accent) ?: resolveLighting(spec.copy(pattern = KeyLightingPattern.KEY.name), accent)
    } ?: return
    val kit = remember(lighting) { LightingKit(lighting) }
    val clock = swatchClock(animate)
    val keyColor = Color(colors.key.toInt())
    val behind = lighting.target == KeyLightingTarget.GLOW
    SwatchFrame(colors, modifier) {
        Canvas(Modifier.matchParentSize()) {
            val now = clock.longValue
            val cell = miniKeyRect()
            val radius = CornerRadius(MINI_KEY_RADIUS_DP * density)
            val cycle = SWATCH_CYCLE_MS
            val round = now / cycle
            val since = now % cycle
            val decay = lighting.decayMs.coerceAtMost(SWATCH_CYCLE_MS.toInt() - 100)
            // Held still at the brightest point when not animating.
            val level = if (!animate) 1f else (1f - since.toFloat() / decay).coerceIn(0f, 1f)
            val pick = kit.indexFor(round * 7919L)
            val color = kit.colors[pick]
            val a = (lighting.intensity * level).coerceIn(0f, 1f)
            val reach = lighting.sizeDp * density
            if (behind && a > 0.01f) lightKey(lighting, kit, cell, 0, pick, color, a, radius, reach, now)
            drawRoundRect(keyColor, cell.topLeft, cell.size, radius)
            if (!behind && a > 0.01f) lightKey(lighting, kit, cell, 0, pick, color, a, radius, reach, now)
        }
        Text("A", color = Color(colors.keyText.toInt()), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
    }
}

/** A key motion in a tile: one key pressed and let go, ringing back by [wobble]. */
@Composable
fun KeyMotionPickerTile(
    motion: KeyPressMotion,
    dip: Float,
    wobble: Float,
    colors: SwatchColors,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val clock = swatchClock(animate)
    // A tile at a dip too shallow to see would read as a broken preview.
    val depth = dip.coerceAtLeast(0.5f)
    SwatchFrame(colors, modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(MINI_KEY_DP.dp)
                .graphicsLayer {
                    val d = if (!animate) {
                        depth
                    } else {
                        dipEnvelope(clock.longValue % SWATCH_CYCLE_MS, SWATCH_HOLD_MS, wobble) * depth
                    }
                    if (d != 0f) pressMotion(motion, d, 0.6f)
                }
                .background(Color(colors.key.toInt()), RoundedCornerShape(MINI_KEY_RADIUS_DP.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = Color(colors.keyText.toInt()), fontSize = 13.sp)
        }
    }
}

@Composable
private fun SwatchFrame(colors: SwatchColors, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .size(EffectSwatchSize)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(colors.board.toInt())),
        content = content,
    )
}

@Composable
private fun BoxScope.MiniKey(colors: SwatchColors) {
    Box(
        Modifier
            .align(Alignment.Center)
            .size(MINI_KEY_DP.dp)
            .background(Color(colors.key.toInt()), RoundedCornerShape(MINI_KEY_RADIUS_DP.dp)),
    )
}

/**
 * A glide trail in a tile: a short wave of comet in [trailColors], tail to head
 * (empty draws it in [solid]), with its [glow] and a few of its [sparkles].
 * The colours flow along it at [flow] while [animate].
 */
@Composable
fun TrailPickerTile(
    trailColors: List<Long>,
    solid: Long,
    glow: Float,
    flow: Float,
    sparkles: TrailSparkleKind?,
    colors: SwatchColors,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val moving = animate && flow > 0f && trailColors.size > 1
    val clock = swatchClock(moving)
    val palette = remember(trailColors) { trailColors.map { Color(it.toInt()) } }
    val star = remember { unitStar() }
    SwatchFrame(colors, modifier) {
        Canvas(Modifier.matchParentSize()) {
            val trail = KbTrail(colors = palette, flow = if (moving) flow else 0f)
            val phase = trail.flowPhase(clock.longValue)
            val solidColor = Color(solid.toInt())
            var prev = tileTrailPoint(0f)
            for (k in 1..TILE_TRAIL_STEPS) {
                val u = k / TILE_TRAIL_STEPS.toFloat()
                val p = tileTrailPoint(u)
                val c = trailColorAt(palette, u, phase, solidColor)
                // Thin at the tail, full at the head, as the comet is.
                val width = (1.2f + 3.6f * u) * density
                if (glow > 0f) {
                    drawLine(c, prev, p, strokeWidth = width * GLOW_OUTER_WIDTH, cap = StrokeCap.Round, alpha = 0.3f * glow)
                }
                drawLine(c, prev, p, strokeWidth = width, cap = StrokeCap.Round)
                prev = p
            }
            if (sparkles != null) {
                val head = trailColorAt(palette, 1f, phase, solidColor)
                for (j in TILE_SPARKS.indices) {
                    val (u, side) = TILE_SPARKS[j]
                    val shape = if (sparkles == TrailSparkleKind.MIXED) j % 4 else sparkShapeFor(sparkles)
                    val at = tileTrailPoint(u)
                    val tint = if (shape == SPARK_FLAKE && palette.isNotEmpty()) palette[j % palette.size] else lerp(head, Color.White, 0.45f)
                    drawSparkShape(
                        shapeId = shape,
                        px = at.x,
                        py = at.y + side * 6f * density,
                        sizePx = 6f * density,
                        color = tint,
                        alpha = 1f,
                        dirX = 0f,
                        dirY = side,
                        turnDeg = 30f * j,
                        twinkle = 1f,
                        star = star,
                    )
                }
            }
        }
    }
}

/** Where a tile's trail is [u] of the way along: one wave across the tile. */
private fun DrawScope.tileTrailPoint(u: Float): Offset = Offset(
    size.width * (0.14f + 0.72f * u),
    size.height * (0.5f + 0.22f * kotlin.math.sin(u * 2f * kotlin.math.PI.toFloat())),
)

private const val TILE_TRAIL_STEPS = 16

/** Where a tile's sparkles sit along its trail, and to which side of it. */
private val TILE_SPARKS = listOf(0.3f to -1f, 0.5f to 1f, 0.72f to -1f, 0.9f to 1f)

/**
 * The tile's clock in milliseconds, ticking a frame at a time while [animate].
 * Read in a draw or layer block, so a tick repaints the tile and never
 * recomposes it.
 */
@Composable
private fun swatchClock(animate: Boolean): MutableLongState {
    val now = remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) withFrameMillis { now.longValue = it }
        }
    }
    return now
}

/** The middle key of a tile, in its own space. */
private fun DrawScope.miniKeyRect(): Rect {
    val edge = MINI_KEY_DP * density
    return Rect(Offset((size.width - edge) / 2f, (size.height - edge) / 2f), Size(edge, edge))
}

private const val MINI_KEY_DP = 26f
private const val MINI_KEY_RADIUS_DP = 6f

/** One press every this long in a tile, held down for [SWATCH_HOLD_MS]. */
private const val SWATCH_CYCLE_MS = 1400L
private const val SWATCH_HOLD_MS = 160L
