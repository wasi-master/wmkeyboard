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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
) {
    val glow = lighting.target == KeyLightingTarget.GLOW
    if (glow != behind) return
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
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
        paintLighting(lighting, cells(), now, origin, radiusDp)
    }
}

/** How much longer than the decay a ripple keeps travelling after its press. */
private const val REACTIVE_TAIL_MS = 600L

private fun DrawScope.paintLighting(lighting: KbLighting, cells: List<Rect>, now: Long, origin: Offset, radiusDp: Float) {
    if (cells.isEmpty()) return
    val t = now / 1000f * lighting.speed
    val width = size.width.coerceAtLeast(1f)
    val radius = CornerRadius(radiusDp * density)
    val reach = lighting.sizeDp * density
    for ((index, cellRoot) in cells.withIndex()) {
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
            else -> reactiveLevel(lighting, cell, origin, now)
        }
        if (level <= 0.01f) continue
        val drift = if (lighting.pattern == KeyLightingPattern.STEADY) 0f else t * 20f
        val hue = if (lighting.hueSpanDeg > 0f) u * lighting.hueSpanDeg + drift else 0f
        val color = hueShift(lighting.color, hue)
        val a = (lighting.intensity * level).coerceIn(0f, 1f)
        when (lighting.target) {
            KeyLightingTarget.FACE -> drawRoundRect(
                color.copy(alpha = a * 0.55f), cell.topLeft, cell.size, radius, blendMode = BlendMode.Screen,
            )
            KeyLightingTarget.EDGE -> drawRoundRect(
                color.copy(alpha = a), cell.topLeft, cell.size, radius,
                style = Stroke(width = (lighting.sizeDp / 4f).coerceIn(1f, 4f) * density),
            )
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
        }
    }
}

/** How lit [cell] is by the recent presses, 0..1. */
private fun reactiveLevel(lighting: KbLighting, cell: Rect, origin: Offset, now: Long): Float {
    var level = 0f
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
        level = max(level, hit)
    }
    return level
}

private fun hash(i: Int): Float {
    var h = i * 2654435761.toInt()
    h = h xor (h ushr 15)
    return (h and 0xFFFF) / 65535f
}
