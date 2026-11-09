package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.wasimaster.wmkeyboard.core.theme.MaterialEdge
import com.wasimaster.wmkeyboard.core.theme.MaterialTexture
import com.wasimaster.wmkeyboard.core.theme.ResolvedMaterial
import com.wasimaster.wmkeyboard.core.theme.hueShift
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A key material, prepared for one key at one size: every outline, brush and
 * tile it needs is built here, in the key's draw cache, so the press path only
 * draws. [drawUnder] goes before the key's fill (the cartoon key's hard
 * shadow), [drawOver] after it (the cap, the grain, the edge, the sheen).
 *
 * The raised cap is the one primitive a press changes: the top face sinks
 * towards the skirt, which is what makes a 3D key read as pressed when the
 * colour alone would not.
 */
internal class MaterialPaint private constructor(
    private val material: ResolvedMaterial,
    private val outline: Outline,
    private val size: Size,
    private val px: Float,
    private val restTop: FaceGeometry?,
    private val downTop: FaceGeometry?,
    private val restFace: Color,
    private val downFace: Color,
    private val texture: ShaderBrush?,
    private val edgeColor: Color,
    private val inner: Outline?,
    private val iridescent: Brush?,
    private val fullPath: Path,
    private val soft: SoftShadow?,
) {

    /** The top face of a raised cap: its outline, where it sits, and the light across it. */
    private class FaceGeometry(val outline: Outline, val offset: Offset, val size: Size, val light: Brush, val gloss: Brush?)

    fun drawUnder(scope: DrawScope, down: Boolean) = with(scope) {
        soft?.draw(this, down)
        if (material.edge == MaterialEdge.INK) {
            val drop = (if (down) 0.6f else 2.4f) * px * (material.edgeWidthDp / 2f).coerceIn(0.6f, 1.6f)
            translate(0f, drop) { drawOutline(outline, edgeColor) }
        }
    }

    fun drawOver(scope: DrawScope, down: Boolean) = with(scope) {
        val top = if (down) downTop else restTop
        if (top != null) {
            // The skirt is the whole key, darkened; the face sits on it.
            val face = if (down) downFace else restFace
            drawOutline(outline, darken(face, material.skirtShade))
            translate(top.offset.x, top.offset.y) {
                drawOutline(top.outline, face)
                drawOutline(top.outline, top.light)
                texture?.let { drawOutline(top.outline, it, alpha = material.textureOpacity) }
                iridescent?.let { drawOutline(top.outline, it) }
                top.gloss?.let { drawOutline(top.outline, it) }
            }
        } else {
            texture?.let { drawOutline(outline, it, alpha = material.textureOpacity) }
            iridescent?.let { drawOutline(outline, it) }
            if (material.gloss > 0f) drawOutline(outline, glossBrush(size, material, Offset.Zero))
        }
        drawEdge(down)
    }

    private fun DrawScope.drawEdge(down: Boolean) {
        val width = material.edgeWidthDp * px
        when (material.edge) {
            MaterialEdge.NONE -> Unit
            MaterialEdge.STITCH -> inner?.let {
                translate(STITCH_INSET_DP * px, STITCH_INSET_DP * px) {
                    drawOutline(
                        it, edgeColor,
                        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 3f * px))),
                    )
                }
            }
            MaterialEdge.GLOW -> {
                for (step in 3 downTo 1) {
                    drawOutline(outline, edgeColor.copy(alpha = edgeColor.alpha * 0.1f * (4 - step)), style = Stroke(width = width * (1 + step * 2)))
                }
                drawOutline(outline, if (down) lerp(edgeColor, Color.White, 0.4f) else edgeColor, style = Stroke(width = width))
            }
            MaterialEdge.INK -> drawOutline(outline, edgeColor, style = Stroke(width = width * 2f))
            MaterialEdge.RIM -> {
                drawOutline(
                    outline,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.45f), 0.35f to Color.Transparent,
                        0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f),
                        startY = 0f, endY = size.height,
                    ),
                    style = Stroke(width = width * 1.4f),
                )
            }
            MaterialEdge.INSET -> clipPath(fullPath) {
                translate(0f, width * 1.2f) {
                    drawOutline(outline, Color.Black.copy(alpha = if (down) 0.4f else 0.28f), style = Stroke(width = width * 2.4f))
                }
            }
            MaterialEdge.DOUBLE -> {
                drawOutline(outline, edgeColor, style = Stroke(width = width * 1.6f))
                inner?.let {
                    translate(STITCH_INSET_DP * px, STITCH_INSET_DP * px) {
                        drawOutline(it, lerp(edgeColor, Color.White, 0.35f).copy(alpha = 0.7f), style = Stroke(width = width))
                    }
                }
            }
            // A dash of next to no length with a round cap is a dot; the gap
            // between dots scales with their size so the ring reads as even.
            MaterialEdge.DOTTED -> drawOutline(
                outline, edgeColor,
                style = Stroke(
                    width = width,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, width * DOT_PITCH)),
                ),
            )
            MaterialEdge.BEVEL -> clipPath(fullPath) {
                // Whole pixels, so the bands stay crisp the way a game block's do.
                val b = max(1f, width.roundToInt().toFloat())
                val w = size.width
                val h = size.height
                if (w > b * 2 && h > b * 2) {
                    val lit = Color.White.copy(alpha = 0.4f)
                    val shade = Color.Black.copy(alpha = 0.36f)
                    // Pushed in, the light and the shade change sides.
                    val tl = if (down) shade else lit
                    val br = if (down) lit else shade
                    drawRect(tl, Offset.Zero, Size(w - b, b))
                    drawRect(tl, Offset(0f, b), Size(b, h - b * 2))
                    drawRect(br, Offset(0f, h - b), Size(w, b))
                    drawRect(br, Offset(w - b, 0f), Size(b, h - b))
                }
            }
        }
    }

    companion object {
        /**
         * [material] fitted to a key of [size] wearing [shape], resting in
         * [face] and held in [pressedFace]. [hue] varies the iridescence from
         * key to key, so a holographic board shimmers across itself rather than
         * repeating one sticker.
         */
        fun of(
            material: ResolvedMaterial,
            shape: Shape,
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
            face: Color,
            pressedFace: Color,
            accent: Color,
            hue: Float,
        ): MaterialPaint {
            val px = density.density
            val outline = shape.createOutline(size, layoutDirection, density)
            fun top(depthDp: Float): FaceGeometry? {
                if (material.bevelDp <= 0f) return null
                val inset = material.topInsetDp * px
                val depth = depthDp * px
                val w = (size.width - inset * 2).coerceAtLeast(1f)
                val h = (size.height - depth - inset).coerceAtLeast(1f)
                val topSize = Size(w, h)
                val offset = Offset(inset, inset * 0.5f + (material.bevelDp * px - depth))
                return FaceGeometry(
                    outline = shape.createOutline(topSize, layoutDirection, density),
                    offset = offset,
                    size = topSize,
                    light = lightBrush(topSize, material),
                    gloss = if (material.gloss > 0f) glossBrush(topSize, material, Offset.Zero) else null,
                )
            }
            val edgeColor = material.edgeColor?.let { Color(it.toInt()) } ?: when (material.edge) {
                MaterialEdge.GLOW -> accent
                MaterialEdge.INK -> if (face.luminance() > 0.5f) Color(0xFF15161A) else Color(0xFF050506)
                MaterialEdge.STITCH -> inkOn(face, 0.45f)
                else -> darken(face, 0.35f)
            }
            val inner = if (material.edge == MaterialEdge.STITCH || material.edge == MaterialEdge.DOUBLE) {
                val inset = STITCH_INSET_DP * px * 2
                if (size.width > inset * 2 && size.height > inset * 2) {
                    shape.createOutline(Size(size.width - inset, size.height - inset), layoutDirection, density)
                } else {
                    null
                }
            } else {
                null
            }
            val texture = MaterialTextures.brush(material.texture)
            val iridescent = if (material.iridescence > 0f) {
                val a = material.iridescence * 0.4f
                val colors = (0..6).map { hueShift(Color(0xFFFF5A7A), it * 60f + hue).copy(alpha = a) }
                Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
            } else {
                null
            }
            return MaterialPaint(
                material = material,
                outline = outline,
                size = size,
                px = px,
                restTop = top(material.bevelDp),
                downTop = top(material.bevelDp * PRESSED_BEVEL_SHARE),
                restFace = face,
                downFace = pressedFace,
                texture = texture,
                edgeColor = edgeColor,
                inner = inner,
                iridescent = iridescent,
                fullPath = outline.toPath(),
                soft = if (material.softShadow > 0f) {
                    SoftShadow.of(material, shape, size, layoutDirection, density, face)
                } else {
                    null
                },
            )
        }

        /** How much of the cap's height is left standing while it is held. */
        private const val PRESSED_BEVEL_SHARE = 0.3f

        /** How far a stitch or an inner ring sits inside the outline. */
        private const val STITCH_INSET_DP = 2.5f

        /** Centre-to-centre spacing of a dotted edge, in dot widths. */
        private const val DOT_PITCH = 2.4f
    }
}

/**
 * The two soft shadows of a neumorphic key, light toward the light and dark
 * away from it. A blur would need a mask filter, which older phones draw in
 * software, so each shadow is a stack of the key's own outline grown a little
 * more each time and drawn faint: where they all overlap, at the key's edge,
 * it is darkest, and it thins out to nothing at the reach. The outlines are
 * made here, once per key size; a press only draws them, fainter and closer.
 */
internal class SoftShadow private constructor(
    private val rings: Array<Outline>,
    private val spreads: FloatArray,
    private val toLight: Offset,
    private val lightColor: Color,
    private val darkColor: Color,
) {
    fun draw(scope: DrawScope, down: Boolean) = with(scope) {
        // Held, the key sinks into the board: its shadows draw in and fade.
        val k = if (down) PRESSED_SHARE else 1f
        for (i in rings.indices) {
            val e = spreads[i] * (if (down) 0.6f else 1f)
            translate(-toLight.x * k - e, -toLight.y * k - e) { drawOutline(rings[i], darkColor, alpha = k) }
            translate(toLight.x * k - e, toLight.y * k - e) { drawOutline(rings[i], lightColor, alpha = k) }
        }
    }

    companion object {
        private const val RINGS = 6
        private const val PRESSED_SHARE = 0.35f

        fun of(
            material: ResolvedMaterial,
            shape: Shape,
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
            face: Color,
        ): SoftShadow {
            val px = density.density
            val s = material.softShadow
            // Kept short so a shadow stays in the gap rather than on the next key.
            val reach = (3.5f + 3.5f * s) * px
            val rad = material.lightAngleDeg * PI.toFloat() / 180f
            val shift = reach * 0.45f
            val toLight = Offset(-sin(rad) * shift, -cos(rad) * shift)
            val opaque = face.copy(alpha = 1f)
            val lightBoard = opaque.luminance() > 0.5f
            // A pale board takes a bright highlight and a gentle shade; a dark
            // one barely any highlight (it would read as a grey halo) and a deep shade.
            val lightEdge = s * if (lightBoard) 0.9f else 0.18f
            val darkEdge = s * if (lightBoard) 0.5f else 0.75f
            val lit = lerp(opaque, Color.White, if (lightBoard) 0.9f else 0.45f)
            val dark = lerp(opaque, Color.Black, if (lightBoard) 0.4f else 0.65f)
            val rings = Array(RINGS) { i ->
                val grow = reach * (i + 1) / RINGS
                shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, density)
            }
            val spreads = FloatArray(RINGS) { reach * (it + 1) / RINGS }
            // Each ring's alpha, so that all of them stacked reach the edge strength.
            fun layer(edge: Float): Float = 1f - (1f - edge.coerceIn(0f, 0.95f)).pow(1f / RINGS)
            return SoftShadow(
                rings, spreads, toLight,
                lightColor = lit.copy(alpha = layer(lightEdge)),
                darkColor = dark.copy(alpha = layer(darkEdge)),
            )
        }
    }
}

/**
 * A label drawn as a grid of dots, the way a dot-matrix display shows text:
 * the label is drawn into a layer of its own and only the dots of a fixed
 * grid are kept. After Expressive Keyboard's dot-matrix labels.
 */
internal fun Modifier.dotMatrixLabel(cellDp: Float = DOT_MATRIX_CELL_DP): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val mask = DotMatrixMask.brush((cellDp * density).roundToInt().coerceAtLeast(2))
        onDrawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }

/** About 2.3 dp from dot to dot: fine enough to read a letter, coarse enough to see the dots. */
private const val DOT_MATRIX_CELL_DP = 2.3f

/** One opaque dot per cell on a clear ground, made once per cell size. */
private object DotMatrixMask {
    private val tiles = HashMap<Int, ImageBitmap>()

    fun brush(cellPx: Int): ShaderBrush {
        val tile = synchronized(tiles) {
            tiles.getOrPut(cellPx) {
                val bmp = Bitmap.createBitmap(cellPx, cellPx, Bitmap.Config.ARGB_8888)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                }
                android.graphics.Canvas(bmp).drawCircle(cellPx / 2f, cellPx / 2f, cellPx * 0.4f, paint)
                bmp.asImageBitmap()
            }
        }
        return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

private fun darken(c: Color, t: Float): Color = lerp(c, Color.Black.copy(alpha = c.alpha), t)

/** Towards whichever of black and white reads on [c]. */
private fun inkOn(c: Color, t: Float): Color =
    lerp(c, if (c.luminance() > 0.45f) Color.Black else Color.White, t).copy(alpha = 1f)

/** The direction the light falls across a face, from the lit side to the shaded one. */
private fun lightAxis(size: Size, material: ResolvedMaterial): Pair<Offset, Offset> {
    val rad = material.lightAngleDeg * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = cos(rad)
    val c = Offset(size.width / 2f, size.height / 2f)
    val half = (abs(dx) * size.width + abs(dy) * size.height) / 2f
    return Offset(c.x - dx * half, c.y - dy * half) to Offset(c.x + dx * half, c.y + dy * half)
}

private fun lightBrush(size: Size, material: ResolvedMaterial): Brush {
    val (from, to) = lightAxis(size, material)
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.08f + material.gloss * 0.12f),
        0.5f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.1f),
        start = from,
        end = to,
    )
}

/** The specular highlight: bright along the lit edge, gone by just past the middle. */
private fun glossBrush(size: Size, material: ResolvedMaterial, offset: Offset): Brush {
    val (from, to) = lightAxis(size, material)
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = material.gloss * 0.55f),
        0.45f to Color.White.copy(alpha = material.gloss * 0.12f),
        0.55f to Color.Transparent,
        start = from + offset,
        end = to + offset,
    )
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/**
 * The material textures, drawn once per process as small grey tiles and
 * repeated across the keys. Grey with alpha, so a tile darkens and lightens
 * whatever key colour it lies on rather than painting a colour of its own.
 */
internal object MaterialTextures {

    private const val TILE = 128
    private val cache = HashMap<MaterialTexture, ImageBitmap>()

    fun brush(texture: MaterialTexture): ShaderBrush? {
        if (texture == MaterialTexture.NONE) return null
        val tile = synchronized(cache) { cache.getOrPut(texture) { draw(texture).asImageBitmap() } }
        return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }

    private fun draw(texture: MaterialTexture): Bitmap {
        val px = IntArray(TILE * TILE)
        val random = Random(texture.ordinal * 7919 + 17)
        fun put(x: Int, y: Int, light: Boolean, alpha: Float) {
            val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
            val i = ((y.mod(TILE)) * TILE) + x.mod(TILE)
            px[i] = (a shl 24) or (if (light) 0xFFFFFF else 0x000000)
        }
        when (texture) {
            MaterialTexture.NONE -> Unit
            MaterialTexture.NOISE -> for (y in 0 until TILE) for (x in 0 until TILE) {
                val v = random.nextFloat()
                put(x, y, v > 0.5f, abs(v - 0.5f) * 0.5f)
            }
            MaterialTexture.LINEN -> for (y in 0 until TILE) for (x in 0 until TILE) {
                val warp = if ((x / 2 + y / 2) % 2 == 0) 0.12f else 0f
                val thread = if (x % 3 == 0 || y % 3 == 0) 0.14f else 0f
                put(x, y, (x + y) % 2 == 0, warp + thread + random.nextFloat() * 0.05f)
            }
            MaterialTexture.DOTS -> for (y in 0 until TILE) for (x in 0 until TILE) {
                val dx = (x % 8) - 4f
                val dy = (y % 8) - 4f
                if (dx * dx + dy * dy <= 2.5f) put(x, y, false, 0.35f)
            }
            MaterialTexture.SCANLINES -> for (y in 0 until TILE) for (x in 0 until TILE) {
                if (y % 3 == 0) put(x, y, false, 0.45f)
            }
            MaterialTexture.BRUSHED -> {
                // Long horizontal strokes of light and dark, a few pixels apart.
                val rows = FloatArray(TILE) { random.nextFloat() - 0.5f }
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val v = rows[y] + (random.nextFloat() - 0.5f) * 0.15f
                    put(x, y, v > 0f, abs(v) * 0.55f)
                }
            }
            MaterialTexture.WOOD -> for (y in 0 until TILE) for (x in 0 until TILE) {
                val wobble = sin(x * 0.05f) * 4f + sin(x * 0.013f + 1.3f) * 7f
                val ring = sin((y + wobble) * 0.55f) + 0.35f * sin((y + wobble) * 1.7f)
                val grain = random.nextFloat() * 0.06f
                put(x, y, ring > 0.8f, (abs(ring) * 0.16f + grain).coerceAtMost(0.4f))
            }
            MaterialTexture.STONE -> {
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val v = random.nextFloat()
                    put(x, y, v > 0.55f, abs(v - 0.55f) * 0.35f)
                }
                repeat(90) {
                    val cx = random.nextInt(TILE)
                    val cy = random.nextInt(TILE)
                    val light = random.nextBoolean()
                    val r = 1 + random.nextInt(2)
                    for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r) put(cx + dx, cy + dy, light, 0.4f)
                }
            }
            MaterialTexture.PAPER -> {
                for (y in 0 until TILE) for (x in 0 until TILE) put(x, y, random.nextBoolean(), random.nextFloat() * 0.08f)
                repeat(70) {
                    // Fibres: short curved strands at every angle.
                    var x = random.nextFloat() * TILE
                    var y = random.nextFloat() * TILE
                    val angle = random.nextFloat() * 2f * PI.toFloat()
                    val bend = (random.nextFloat() - 0.5f) * 0.15f
                    val length = 6 + random.nextInt(12)
                    var a = angle
                    repeat(length) {
                        put(x.toInt(), y.toInt(), false, 0.18f)
                        x += cos(a); y += sin(a); a += bend
                    }
                }
            }
            MaterialTexture.CARBON -> for (y in 0 until TILE) for (x in 0 until TILE) {
                // A two-over-two twill: diagonal blocks alternating in sheen.
                val cell = 8
                val bx = x / cell
                val by = y / cell
                val u = (x % cell) / cell.toFloat()
                val v = (y % cell) / cell.toFloat()
                val horizontal = (bx + by) % 2 == 0
                val shade = if (horizontal) sin(v * PI.toFloat()) else sin(u * PI.toFloat())
                put(x, y, shade > 0.6f, if (shade > 0.6f) (shade - 0.6f) * 0.45f else (0.6f - shade) * 0.5f)
            }
            MaterialTexture.PIXELS -> {
                // Square cells, mostly the plain colour, some lighter or darker:
                // the stone and earth of a game block. 8 px cells, so 16 to the tile.
                val cell = 8
                for (cy in 0 until TILE / cell) for (cx in 0 until TILE / cell) {
                    val v = random.nextFloat()
                    if (v < 0.45f) continue
                    val light = v > 0.75f
                    val a = 0.1f + random.nextFloat() * 0.16f
                    for (y in 0 until cell) for (x in 0 until cell) put(cx * cell + x, cy * cell + y, light, a)
                }
            }
            MaterialTexture.PLANKS -> {
                // Boards four cells deep with a dark seam under each, a shade of
                // their own, a grain line here and there and the odd darker cell.
                val cell = 8
                val boardRows = 4
                val cols = TILE / cell
                val tones = FloatArray(TILE / cell / boardRows) { (random.nextFloat() - 0.5f) * 0.12f }
                for (cy in 0 until TILE / cell) {
                    val board = cy / boardRows
                    val tone = tones[board]
                    for (cx in 0 until cols) {
                        val seam = cy % boardRows == boardRows - 1
                        val join = (cx + board * 5) % cols == 0
                        val knot = random.nextInt(9) == 0
                        val (light, a) = when {
                            seam -> false to 0.34f
                            join -> false to 0.26f
                            knot -> false to 0.14f
                            else -> (tone > 0f) to abs(tone)
                        }
                        for (y in 0 until cell) for (x in 0 until cell) put(cx * cell + x, cy * cell + y, light, a)
                    }
                }
            }
            MaterialTexture.LEATHER -> {
                for (y in 0 until TILE) for (x in 0 until TILE) put(x, y, false, random.nextFloat() * 0.06f)
                repeat(240) {
                    val cx = random.nextInt(TILE)
                    val cy = random.nextInt(TILE)
                    val r = 1 + random.nextInt(3)
                    for (dy in -r..r) for (dx in -r..r) {
                        val d = dx * dx + dy * dy
                        if (d <= r * r) put(cx + dx, cy + dy, d < r, if (d < r) 0.08f else 0.22f)
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888)
    }
}

/**
 * The text shadow that draws [material]'s label treatment on a label in
 * [label], or null when it has none. Sizes are fixed in pixels at a phone's
 * density, which is the size the effect was tuned at; a glow is meant to be
 * soft, not exact.
 */
@androidx.compose.runtime.Composable
internal fun materialLabelShadow(material: ResolvedMaterial, label: Color): androidx.compose.ui.graphics.Shadow? {
    val px = androidx.compose.ui.platform.LocalDensity.current.density
    val tint = material.labelColor?.let { Color(it.toInt()) }
    return when (material.label) {
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.NONE -> null
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.GLOW ->
            androidx.compose.ui.graphics.Shadow(tint ?: label, Offset.Zero, 8f * px)
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.DEBOSS ->
            androidx.compose.ui.graphics.Shadow(tint ?: Color.White.copy(alpha = 0.45f), Offset(0f, 1f * px), 0.5f * px)
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.EMBOSS ->
            androidx.compose.ui.graphics.Shadow(tint ?: Color.Black.copy(alpha = 0.5f), Offset(0f, 1.2f * px), 1f * px)
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.SHADOW ->
            androidx.compose.ui.graphics.Shadow(tint ?: Color.Black.copy(alpha = 0.55f), Offset(0f, 2f * px), 3f * px)
        // Not a shadow at all: the dots are a mask over the label, [dotMatrixLabel].
        com.wasimaster.wmkeyboard.core.theme.MaterialLabel.DOTS -> null
    }
}
