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
