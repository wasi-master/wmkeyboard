package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

/*
 * A glowing key's light on the keys round it (#1473): a single-key style with
 * a glow spill washes its neighbours' faces in its colour, strongest on the
 * edge that faces it, the way a lit key on a backlit board colours the caps
 * beside it. Held, the key's glow can grow brighter or dimmer, and the wash
 * follows.
 *
 * Each glowing key publishes where it is to the board's [KeyGlowSpill]; each
 * key works out once, in its draw cache, which of those reach it, and paints
 * their wash under its label. A press of a glowing key repaints only the keys
 * its light reaches, because only those read its press.
 */

/** The board's glowing keys, for their neighbours; null on a board with none. */
internal val LocalKeyGlowSpill = staticCompositionLocalOf<KeyGlowSpill?> { null }

/** One key's light, as its neighbours see it. [bounds] is its face in root space. */
@Immutable
internal data class GlowSource(
    val bounds: Rect,
    val color: Color?,
    val pressedColor: Color?,
    val pressedStrength: Float,
    val spill: Float,
    /** The key's own press, read only in its neighbours' draws. */
    val pressed: State<Boolean>,
)

/** Every glowing key with a spill on one board, by a token of the key's own. */
internal class KeyGlowSpill {

    private val sources = mutableStateMapOf<Any, GlowSource>()

    fun put(token: Any, source: GlowSource) {
        if (sources[token] != source) sources[token] = source
    }

    fun remove(token: Any) {
        sources.remove(token)
    }

    /**
     * The lights that reach [face] (root space), [self]'s own left out. Reads
     * the map, so it belongs in a draw cache: a key moving or a style changing
     * prepares the paint again, a press does not.
     */
    fun near(face: Rect, self: Any): List<GlowSource> {
        if (face.isEmpty) return emptyList()
        var out: ArrayList<GlowSource>? = null
        for ((token, source) in sources) {
            if (token === self) continue
            if (!source.reach().overlaps(face)) continue
            val list = out ?: ArrayList<GlowSource>(2)
            list.add(source)
            out = list
        }
        return out ?: emptyList()
    }
}

/** How far past its own face a key's light reaches, as a multiple of its shorter side. */
private const val SPILL_REACH = 1.1f

/** How strong the wash is on the nearest edge at a spill of 1, before the press scales it. */
private const val SPILL_PEAK = 0.6f

private fun GlowSource.reachPx(): Float = min(bounds.width, bounds.height) * SPILL_REACH

private fun GlowSource.reach(): Rect = bounds.inflate(reachPx())

/**
 * The wash one key takes from the lights round it, fitted to the key's face:
 * one gradient per light and colour, made here so a press only picks one and
 * draws it.
 */
internal class SpillPaint private constructor(private val lights: Array<Light>) {

    private class Light(val source: GlowSource, val rest: ShaderBrush?, val held: ShaderBrush?)

    /** The wash over [outline], under the label. */
    fun draw(scope: DrawScope, outline: Outline) {
        for (light in lights) {
            val source = light.source
            val down = source.pressed.value
            val brush = (if (down) light.held else light.rest) ?: continue
            val strength = if (down) source.pressedStrength else 1f
            val alpha = (source.spill * SPILL_PEAK * strength).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            scope.drawOutline(outline, brush, alpha = alpha)
        }
    }

    companion object {
        /** The paint for a key whose face sits at [faceTopLeft] (root space); null when no light reaches it. */
        fun of(sources: List<GlowSource>, faceTopLeft: Offset): SpillPaint? {
            if (sources.isEmpty()) return null
            val lights = Array(sources.size) { i ->
                val source = sources[i]
                val center = source.bounds.center - faceTopLeft
                val reach = source.reachPx()
                val rx = source.bounds.width / 2f + reach
                val ry = source.bounds.height / 2f + reach
                val restColor = source.color
                val heldColor = source.pressedColor ?: restColor
                val rest = restColor?.let { EllipseGlow(center, rx, ry, it) }
                Light(
                    source,
                    rest,
                    if (heldColor == restColor) rest else heldColor?.let { EllipseGlow(center, rx, ry, it) },
                )
            }
            return SpillPaint(lights)
        }
    }
}

/**
 * A radial falloff of [color] stretched to an ellipse round a key, so a wide
 * key (Shift, the space bar) lights its ends as far as its sides: full across
 * the key itself, then fading out by its reach.
 */
private class EllipseGlow(
    private val center: Offset,
    private val rx: Float,
    private val ry: Float,
    color: Color,
) : ShaderBrush() {

    private val colors = listOf(
        color,
        color,
        color.copy(alpha = color.alpha * 0.45f),
        color.copy(alpha = color.alpha * 0.12f),
        color.copy(alpha = 0f),
    )

    override fun createShader(size: Size): Shader {
        val shader = RadialGradientShader(Offset.Zero, 1f, colors, STOPS)
        shader.setLocalMatrix(
            android.graphics.Matrix().apply {
                setScale(rx, ry)
                postTranslate(center.x, center.y)
            },
        )
        return shader
    }

    private companion object {
        val STOPS = listOf(0f, 0.3f, 0.55f, 0.8f, 1f)
    }
}
