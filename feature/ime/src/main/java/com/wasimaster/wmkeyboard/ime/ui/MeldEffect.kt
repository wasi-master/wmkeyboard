package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The meld press effect ([com.wasimaster.wmkeyboard.core.theme.BoardEffectKind.MELD]):
 * the pressed key reaches out to the keys around it in glassy bridges, the
 * way drops of liquid run together, and lets go again as the press fades.
 *
 * Ported from Expressive Keyboard's glass merge (GlassMerge.kt,
 * github.com/kotlalipopus/Expressive-Keyboard, MIT licence). The shader is
 * theirs with two changes: the bridge colour is a uniform, so a theme can
 * tint it, and the press strength comes from the age of the press rather
 * than a key's own press animation, which this keyboard does not expose.
 *
 * The shader fills only what lies between the keys (inside a key its mask is
 * zero), so the layer can sit over the keys like the other press effects
 * without covering a label.
 */

/** Most keys a press reaches toward. The shader's arrays are this long. */
private const val MAX_NEIGHBORS = 8

/** How far apart two key faces may be and still meld, in dp. */
private const val MELD_REACH_DP = 14f

/** Most presses melding at once; older ones are left to fade unseen. */
internal const val MELD_MAX_ACTIVE = 4

/** Room round the pressed key for the bridges and their soft shadow, in dp. */
private const val MELD_PAD_DP = 22f

private const val MELD_SRC = """
uniform float4 key;
uniform float4 near[8];
uniform float nearR[8];
uniform float count;
uniform float radius;
uniform float k;
uniform float band;
uniform float night;
uniform float press;
uniform float3 tint;

float box(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 b = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - b + rad;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

float smin(float a, float b, float s) {
    float h = max(s - abs(a - b), 0.0) / s;
    return min(a, b) - h * h * s * 0.25;
}

float field(float2 p) {
    float own = box(p, key, radius);
    float d = own;
    for (int i = 0; i < 8; i++) {
        if (float(i) < count) d = min(d, smin(own, box(p, near[i], nearR[i]), k));
    }
    return d;
}

float solid(float2 p) {
    float d = box(p, key, radius);
    for (int i = 0; i < 8; i++) {
        if (float(i) < count) d = min(d, box(p, near[i], nearR[i]));
    }
    return d;
}

half4 main(float2 p) {
    float d = field(p);
    float s = solid(p);
    float between = smoothstep(-0.5, 0.9, s);
    float inside = 1.0 - smoothstep(-0.55, 0.55, d);
    float mask = between * inside;
    float shadow = smoothstep(0.0, 1.4, d) * (1.0 - smoothstep(1.8, 5.5, d));
    if (mask <= 0.002) {
        float sh = shadow * 0.14 * (1.0 - night);
        if (sh > 0.002) return half4(0.0, 0.0, 0.0, sh);
        return half4(0.0);
    }
    float2 n = float2(field(p + float2(0.75, 0.0)) - d, field(p + float2(0.0, 0.75)) - d);
    n = n / max(length(n), 0.0001);
    float r = -d;
    float rim = smoothstep(0.0, 0.5, r) * (1.0 - smoothstep(band * 0.5, band, r));
    rim *= rim;
    float2 light = normalize(float2(-0.55, -0.83));
    float spec = pow(clamp(dot(n, light), 0.0, 1.0), 7.0);
    float sheen = clamp(-n.y, 0.0, 1.0);
    float weld = (1.0 - smoothstep(0.0, 1.8, s)) * between;
    float grad = 1.0 - 0.35 * clamp((p.y - key.y) / max(key.w - key.y, 1.0), 0.0, 1.0);
    float fill = mix(0.22, 0.12, night) * grad;
    float a = fill * between;
    a += rim * (0.30 + 0.16 * sheen + 0.22 * spec);
    a += weld * 0.08;
    a *= mix(0.55, 1.0, clamp(press, 0.0, 1.0));
    a = clamp(a, 0.0, 0.86);
    float3 col = mix(tint, float3(1.0), clamp(rim * 0.9 + spec * 0.55, 0.0, 1.0));
    return half4(col * a, a);
}
"""

/**
 * How far into its meld a press [ageMs] old is, out of [lifeMs]: the bridges
 * run out over the first tenth of a second, hold, and draw back in.
 */
internal fun meldPress(ageMs: Long, lifeMs: Long): Float {
    val rise = (ageMs / 90f).coerceIn(0f, 1f)
    val hold = 140f
    if (ageMs <= hold) return rise
    val u = ((ageMs - hold) / (lifeMs - hold).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    val f = 1f - u
    return rise * f * f * (3f - 2f * f)
}

/**
 * Draws melds. One per press kit: it holds the compiled shader (Android 13
 * and later; null before, or if the driver refuses it) and the arrays its
 * uniforms are filled from, so a frame allocates nothing but the brush.
 */
internal class MeldPaint {

    /** A RuntimeShader, typed loosely so the class loads on phones that have none. */
    private val shader: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(MELD_SRC) }.getOrNull()
        } else {
            null
        }

    private val nearValues = FloatArray(MAX_NEIGHBORS * 4)
    private val nearRadii = FloatArray(MAX_NEIGHBORS)
    private val near = ArrayList<Rect>(MAX_NEIGHBORS * 2)

    /**
     * One press's meld. [faces] are the key faces in this layer's space,
     * [pressX], [pressY] where the press landed in the same space, [press]
     * 0..1 from [meldPress]. [tint] is the bridge's colour; null takes the
     * cool grey or near white Expressive Keyboard uses on a light or dark board.
     */
    fun draw(
        scope: DrawScope,
        faces: List<Rect>,
        pressX: Float,
        pressY: Float,
        press: Float,
        radiusPx: Float,
        night: Boolean,
        tint: Color?,
    ) {
        if (press <= 0.01f || faces.isEmpty()) return
        val at = Offset(pressX, pressY)
        val self = faces.firstOrNull { it.contains(at) }
            ?: faces.minByOrNull { (it.center - at).getDistanceSquared() }
            ?: return
        val reach = MELD_REACH_DP * scope.density
        near.clear()
        for (f in faces) {
            if (f == self) continue
            if (gapBetween(self, f) <= reach) near += f
        }
        near.sortBy { gapBetween(self, it) }
        val count = min(near.size, MAX_NEIGHBORS)
        if (count == 0) return
        val half = min(self.width, self.height) / 2f
        val restR = radiusPx.coerceAtMost(half)
        val ownR = restR + (half - restR) * 0.35f * press
        val color = tint ?: if (night) Color(0xFFDBEBFF) else Color(0xFF66738F)
        val rs = shader
        if (rs != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            scope.drawShaded(rs as RuntimeShader, self, count, ownR, radiusPx, press, night, color)
        } else {
            // Before Android 13: a plain bridge across each gap the meld reaches,
            // as thick as the keys' shared side allows and fading with the press.
            val fill = color.copy(alpha = (if (night) 0.16f else 0.22f) * press)
            scope.drawBridges(self, count, restR, press, fill)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun DrawScope.drawShaded(
        rs: RuntimeShader,
        self: Rect,
        count: Int,
        ownR: Float,
        radiusPx: Float,
        press: Float,
        night: Boolean,
        color: Color,
    ) {
        nearValues.fill(0f)
        nearRadii.fill(0f)
        for (i in 0 until count) {
            val r = near[i]
            nearValues[i * 4] = r.left
            nearValues[i * 4 + 1] = r.top
            nearValues[i * 4 + 2] = r.right
            nearValues[i * 4 + 3] = r.bottom
            nearRadii[i] = radiusPx.coerceAtMost(min(r.width, r.height) / 2f)
        }
        rs.setFloatUniform("key", self.left, self.top, self.right, self.bottom)
        rs.setFloatUniform("near", nearValues)
        rs.setFloatUniform("nearR", nearRadii)
        rs.setFloatUniform("count", count.toFloat())
        rs.setFloatUniform("radius", ownR)
        // A bridge forms once k is more than twice the gap between two keys.
        rs.setFloatUniform("k", (28f * press).coerceAtLeast(0.5f) * density)
        rs.setFloatUniform("band", 2.6f * density)
        rs.setFloatUniform("night", if (night) 1f else 0f)
        rs.setFloatUniform("press", press)
        rs.setFloatUniform("tint", color.red, color.green, color.blue)
        val pad = MELD_PAD_DP * density
        drawRect(
            ShaderBrush(rs),
            Offset(self.left - pad, self.top - pad),
            Size(self.width + pad * 2, self.height + pad * 2),
        )
    }

    private fun DrawScope.drawBridges(self: Rect, count: Int, restR: Float, press: Float, fill: Color) {
        for (i in 0 until count) {
            val n = near[i]
            val top = max(self.top, n.top) + restR
            val bottom = min(self.bottom, n.bottom) - restR
            val left = max(self.left, n.left) + restR
            val right = min(self.right, n.right) - restR
            if (bottom > top && (n.left >= self.right || n.right <= self.left)) {
                // Side by side: a bridge across the gap between them.
                val x0 = min(self.right, n.right) - restR
                val x1 = max(self.left, n.left) + restR
                val mid = (top + bottom) / 2f
                val h = (bottom - top) / 2f * (0.35f + 0.45f * press)
                drawRoundRect(fill, Offset(min(x0, x1), mid - h), Size(abs(x1 - x0), h * 2), CornerRadius(h))
            } else if (right > left) {
                // One above the other.
                val y0 = min(self.bottom, n.bottom) - restR
                val y1 = max(self.top, n.top) + restR
                val mid = (left + right) / 2f
                val h = (right - left) / 2f * (0.35f + 0.45f * press)
                drawRoundRect(fill, Offset(mid - h, min(y0, y1)), Size(h * 2, abs(y1 - y0)), CornerRadius(h))
            }
        }
    }

    private fun gapBetween(a: Rect, b: Rect): Float {
        val dx = max(0f, max(b.left - a.right, a.left - b.right))
        val dy = max(0f, max(b.top - a.bottom, a.top - b.bottom))
        return max(dx, dy)
    }
}

/**
 * A little grid of keys round ([cx], [cy]) for the pictures of the effect
 * (theme cards, the picker tile), which have no keyboard of their own: three
 * by three, [span] across a key, the middle one pressed.
 */
internal fun meldSampleFaces(cx: Float, cy: Float, span: Float): List<Rect> {
    val w = span * 0.9f
    val h = span * 1.1f
    val gap = span * 0.16f
    val out = ArrayList<Rect>(9)
    for (row in -1..1) for (col in -1..1) {
        val x = cx + col * (w + gap) - w / 2f
        val y = cy + row * (h + gap) - h / 2f
        out += Rect(x, y, x + w, y + h)
    }
    return out
}
