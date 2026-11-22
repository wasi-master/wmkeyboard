package com.wasimaster.wmkeyboard.core.theme

import java.util.Locale

/**
 * Gboard's raised key as a [KeySkinSpec].
 *
 * Gboard draws `elevation` on a key top as a hard-edged band of `shadow_color`
 * under the face: the face sits `elevation` dp higher than the key's foot, and
 * the strip between them is the shadow colour, with no blur. Rboard's 3D packs
 * (`3D_BlackMcLaren` and its nine siblings) are built entirely on it: black
 * keys with an orange edge on an orange slab. The platform shadow this app
 * draws for [ThemeSpec.keyElevationDp] is a soft blur that, coloured orange on
 * a black board, came out as a faint glow, nothing like the theme.
 *
 * The skin is one slot that every key falls back to: the slab, the face in the
 * key's own colour, the pressed colour faded in while held, and the edge. Its
 * design units are close to dp at an ordinary key size (a 32 × 48 drawing on a
 * key about that big), and the stretch bands keep the corners, the edge and
 * the slab at their size on a wide space bar.
 */
object GboardKeySkin {

    private const val W = 32f
    private const val H = 48f

    /** Gboard's own key radius when a theme leaves it unsaid. */
    const val DEFAULT_RADIUS = 6f

    private const val MAX_DEPTH = 8f
    private const val MAX_EDGE = 4f
    private const val MAX_RADIUS = 11f

    /** Bézier handle length for a quarter circle. */
    private const val KAPPA = 0.5523f

    fun raised(depth: Float, radius: Float, edge: Float): KeySkinSpec {
        val d = depth.coerceIn(0f, MAX_DEPTH)
        val e = edge.coerceIn(0f, MAX_EDGE)
        val r = radius.coerceIn(0f, MAX_RADIUS)
        val half = e / 2f
        val face = roundRect(half, half, W - half, H - d - half, r)
        val layers = buildList {
            add(SkinLayer(d = roundRect(0f, d, W, H, r), fill = SkinPaint(color = "shadow")))
            add(SkinLayer(d = face, fill = SkinPaint(color = "face")))
            add(
                SkinLayer(
                    d = face,
                    fill = SkinPaint(color = "keyPressed"),
                    press = SkinPress(opacity = listOf(0f, 1f)),
                ),
            )
            if (e > 0f) add(SkinLayer(d = face, stroke = SkinPaint(color = "border"), strokeWidth = e))
        }
        val fixed = r + e
        val slot = SkinSlot(
            size = listOf(W, H),
            stretchX = listOf(fixed, W - fixed),
            stretchY = listOf(fixed, H - d - fixed),
            layers = layers,
        )
        return KeySkinSpec(slots = mapOf(SkinSlotName.LETTER to slot))
    }

    /** A rounded rectangle as SVG path data; [r] is held to what the rectangle can take. */
    internal fun roundRect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): String {
        val rr = minOf(r, (x1 - x0) / 2f, (y1 - y0) / 2f).coerceAtLeast(0f)
        if (rr == 0f) return "M${n(x0)} ${n(y0)}H${n(x1)}V${n(y1)}H${n(x0)}Z"
        val c = rr * (1f - KAPPA)
        return buildString {
            append("M${n(x0 + rr)} ${n(y0)}")
            append("H${n(x1 - rr)}")
            append("C${n(x1 - c)} ${n(y0)} ${n(x1)} ${n(y0 + c)} ${n(x1)} ${n(y0 + rr)}")
            append("V${n(y1 - rr)}")
            append("C${n(x1)} ${n(y1 - c)} ${n(x1 - c)} ${n(y1)} ${n(x1 - rr)} ${n(y1)}")
            append("H${n(x0 + rr)}")
            append("C${n(x0 + c)} ${n(y1)} ${n(x0)} ${n(y1 - c)} ${n(x0)} ${n(y1 - rr)}")
            append("V${n(y0 + rr)}")
            append("C${n(x0)} ${n(y0 + c)} ${n(x0 + c)} ${n(y0)} ${n(x0 + rr)} ${n(y0)}")
            append("Z")
        }
    }

    private fun n(v: Float): String = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
}
