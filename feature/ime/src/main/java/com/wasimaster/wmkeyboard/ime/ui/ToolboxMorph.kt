package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether the toolbox button can play [ToolboxBackMorph] rather than the
 * generic swap: both of its glyphs are the stock ones. A pack that replaces
 * either draws something this motion knows nothing about.
 */
@Composable
internal fun toolboxMorphAvailable(slot: String): Boolean {
    if (slot != IconSlots.CHROME_TOOLBOX && slot != IconSlots.CHROME_PANEL_BACK) return false
    val icons = LocalIconSet.current
    return icons.resolve(IconSlots.CHROME_TOOLBOX) == null && icons.resolve(IconSlots.CHROME_PANEL_BACK) == null
}

/**
 * The stock toolbox glyph (Material Symbols `grid_view`) turning into the
 * stock back chevron (`chevron_left`) and back again.
 *
 * Both icons are 2-unit strokes on a 24-unit grid, which is what makes a real
 * morph possible: the chevron is the left half of a 45° diamond outline whose
 * centre-line corners sit at (9.41, 12), (14, 7.41) and (14, 16.59). So three
 * tiles sink into the top-left one, that tile slides to (14, 12) and turns
 * into the diamond, and its right half is eaten back to the top and bottom
 * points. What is left is the chevron, to the pixel.
 *
 * Every shape is a stroked centre line with round caps and joins. A square
 * whose centre line has corner radius 1 strokes to the tile's 2-unit outer
 * rounding and sharp inner corner, and a sharp corner with a round join
 * strokes to the chevron's rounded tip, so both ends match the static icons.
 *
 * Driven by one linear progress value with the phases eased inside it, so
 * closing is exactly the opening played backwards, from wherever it was.
 */
@Composable
internal fun ToolboxBackMorph(back: Boolean, description: String, modifier: Modifier, tint: Color, brush: Brush?) {
    val progress by animateFloatAsState(
        targetValue = if (back) 1f else 0f,
        animationSpec = tween(ToolboxMorphMs, easing = LinearEasing),
        label = "toolboxMorph",
    )
    val painter = remember { ToolboxMorphPainter() }
    Spacer(
        modifier
            .size(24.dp)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            // Read in the draw phase only: the frames redraw, never recompose.
            .drawBehind { painter.draw(this, progress, tint, brush) },
    )
}

/** How long [ToolboxBackMorph] takes end to end. */
private const val ToolboxMorphMs = 340

/** Material 3's emphasized curve: quick to leave, long settle. */
private val MorphEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun phase(t: Float, from: Float, to: Float): Float =
    MorphEasing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Tile centres of `grid_view`, in the 24-unit viewport: TL, TR, BL, BR. */
private const val TileNear = 7f
private const val TileFar = 17f

/** Half the centre-line side of a tile, and of the diamond the chevron comes from. */
private const val TileHalf = 3f
private val DiamondHalf = 4.589f / sqrt(2f)

/** Where the diamond sits: its left point is the chevron's tip. */
private const val DiamondX = 14f
private const val DiamondY = 12f

private class ToolboxMorphPainter {
    private val shape = Path()
    private val visible = Path()
    private val measure = PathMeasure()

    fun draw(scope: DrawScope, t: Float, tint: Color, brush: Brush?) = with(scope) {
        val unit = min(size.width, size.height) / 24f
        val ox = (size.width - 24f * unit) / 2f
        val oy = (size.height - 24f * unit) / 2f
        val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun paint(path: Path, alpha: Float) {
            if (brush != null) drawPath(path, brush, alpha, stroke) else drawPath(path, tint, alpha, stroke)
        }
        // The tiles' rounding goes first, so the diamond is sharp (and its
        // tip a round join, like the chevron's) before any of it is eaten.
        val corner = 1f - phase(t, 0f, 0.42f)

        // The three that go, staggered: top-right, bottom-right, bottom-left.
        // Each shrinks, turns and fades into the diamond's centre.
        for (k in 0..2) {
            val e = phase(t, k * 0.07f, 0.32f + k * 0.07f)
            if (e >= 1f) continue
            val (x, y) = when (k) {
                0 -> TileFar to TileNear
                1 -> TileFar to TileFar
                else -> TileNear to TileFar
            }
            square(
                cx = ox + lerp(x, DiamondX, e) * unit,
                cy = oy + lerp(y, DiamondY, e) * unit,
                half = TileHalf * (1f - e) * unit,
                radius = corner * unit,
                turnDeg = 45f * e,
            )
            paint(shape, 1f - e)
        }

        // The top-left tile becomes the diamond, then the chevron.
        val a = phase(t, 0f, 0.55f)
        val half = lerp(TileHalf, DiamondHalf, a)
        square(
            cx = ox + lerp(TileNear, DiamondX, a) * unit,
            cy = oy + lerp(TileNear, DiamondY, a) * unit,
            half = half * unit,
            radius = corner * unit,
            turnDeg = 45f * a,
        )
        val b = phase(t, 0.42f, 1f)
        if (b <= 0f) {
            paint(shape, 1f)
        } else {
            // The path starts at the corner that ends up as the diamond's
            // right point, so trimming both ends eats the right half back
            // towards the top and bottom points together.
            measure.setPath(shape, false)
            val length = measure.length
            val cut = b * length / 4f
            visible.reset()
            measure.getSegment(cut, length - cut, visible, true)
            paint(visible, 1f)
        }
    }

    /**
     * Rebuilds [shape] as a square centre line around ([cx], [cy]), turned
     * [turnDeg] clockwise, with quadratic corners of [radius]. It starts on
     * the corner that a 45° turn brings to the right point, and runs right,
     * bottom, left, top from there.
     */
    private fun square(cx: Float, cy: Float, half: Float, radius: Float, turnDeg: Float) {
        val rad = Math.toRadians(turnDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // Local corners (before the turn): top-right, bottom-right, bottom-left, top-left.
        val lx = floatArrayOf(half, half, -half, -half)
        val ly = floatArrayOf(-half, half, half, -half)
        val r = radius.coerceAtMost(half)
        fun px(x: Float, y: Float) = cx + x * c - y * s
        fun py(x: Float, y: Float) = cy + x * s + y * c
        shape.reset()
        // Begin at the middle of the first corner's rounding, so the trim
        // stays symmetric while the corners are still round.
        for (i in 0..4) {
            val j = i % 4
            val prev = (j + 3) % 4
            val next = (j + 1) % 4
            // Points r along each edge away from corner j.
            val inX = lx[j] + (lx[prev] - lx[j]) / (2f * half).coerceAtLeast(1e-3f) * r
            val inY = ly[j] + (ly[prev] - ly[j]) / (2f * half).coerceAtLeast(1e-3f) * r
            val outX = lx[j] + (lx[next] - lx[j]) / (2f * half).coerceAtLeast(1e-3f) * r
            val outY = ly[j] + (ly[next] - ly[j]) / (2f * half).coerceAtLeast(1e-3f) * r
            when (i) {
                0 -> {
                    // Midpoint of the quadratic from in → out around the corner.
                    val mx = 0.25f * inX + 0.5f * lx[j] + 0.25f * outX
                    val my = 0.25f * inY + 0.5f * ly[j] + 0.25f * outY
                    val hx = 0.5f * (lx[j] + outX)
                    val hy = 0.5f * (ly[j] + outY)
                    shape.moveTo(px(mx, my), py(mx, my))
                    shape.quadraticTo(px(hx, hy), py(hx, hy), px(outX, outY), py(outX, outY))
                }
                4 -> {
                    val mx = 0.25f * inX + 0.5f * lx[j] + 0.25f * outX
                    val my = 0.25f * inY + 0.5f * ly[j] + 0.25f * outY
                    val hx = 0.5f * (inX + lx[j])
                    val hy = 0.5f * (inY + ly[j])
                    shape.lineTo(px(inX, inY), py(inX, inY))
                    shape.quadraticTo(px(hx, hy), py(hx, hy), px(mx, my), py(mx, my))
                }
                else -> {
                    shape.lineTo(px(inX, inY), py(inX, inY))
                    shape.quadraticTo(px(lx[j], ly[j]), py(lx[j], ly[j]), px(outX, outY), py(outX, outY))
                }
            }
        }
        shape.close()
    }
}
