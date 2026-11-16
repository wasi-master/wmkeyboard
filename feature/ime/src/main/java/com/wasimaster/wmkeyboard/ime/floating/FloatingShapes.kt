package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * The outline the floating button and its slots are drawn in (#933). Each is
 * fitted to the square box it is given, centred, so a change of shape never
 * moves the button or changes how much of the screen it takes.
 *
 * [roundness] (#1372), 0 to 100, rounds off a shape's corners: a polygon's
 * each a curve that starts that share of the way to halfway along its sides,
 * the rounded square's further towards a circle. The round outlines have
 * none to round.
 */
fun floatingShape(shape: FloatingButtonShape, roundness: Int = 0): Shape {
    val amount = roundness.coerceIn(0, 100) / 100f
    return when (shape) {
        FloatingButtonShape.CIRCLE -> CircleShape
        FloatingButtonShape.ROUNDED_SQUARE -> RoundedCornerShape(28 + (22 * amount).roundToInt())
        FloatingButtonShape.OVAL -> OvalShape
        FloatingButtonShape.SQUIRCLE -> SquircleShape
        FloatingButtonShape.HEART -> HeartShape
        else -> if (amount <= 0f) polygonShapes.getValue(shape) else roundedPolygonShape(shape, amount)
    }
}

/**
 * Where the glyph sits in [shape]'s box (#1372), as a share of the box to
 * move it from the box's middle: the middle of the shape itself. A pentagon
 * or a star fitted to its box stands on its points, its middle below the
 * box's, and a glyph in the box's middle looked pushed up against its top.
 * Zero for the shapes whose middle is the box's.
 */
fun floatingIconOffset(shape: FloatingButtonShape): Offset {
    val corners = floatingPolygon(shape, UNIT_BOX) ?: return Offset.Zero
    // The corners' mean is the middle of a regular polygon or star.
    val x = corners.sumOf { it.x.toDouble() } / corners.size
    val y = corners.sumOf { it.y.toDouble() } / corners.size
    return Offset((x - 0.5).toFloat(), (y - 0.5).toFloat())
}

private val UNIT_BOX = Size(1f, 1f)

/**
 * How large the glyph is drawn in each outline, as a share of the box: the
 * pointed ones leave less room in the middle than a disc does.
 */
fun floatingIconScale(shape: FloatingButtonShape): Float = when (shape) {
    FloatingButtonShape.STAR -> 0.34f
    FloatingButtonShape.DIAMOND, FloatingButtonShape.HEART -> 0.38f
    FloatingButtonShape.OVAL, FloatingButtonShape.PENTAGON -> 0.42f
    else -> 0.46f
}

/**
 * The corners of [shape] in a box [size] wide and high, for the outlines that
 * are polygons, the square among them (#1372), else null. Every corner is inside the box and one at least
 * touches its edge, so a polygon is as large as the disc it stands in for.
 */
fun floatingPolygon(shape: FloatingButtonShape, size: Size): List<Offset>? {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) / 2f
    return when (shape) {
        FloatingButtonShape.SQUARE -> listOf(
            Offset(0f, 0f),
            Offset(size.width, 0f),
            Offset(size.width, size.height),
            Offset(0f, size.height),
        )
        FloatingButtonShape.DIAMOND -> regularPolygon(4, center, radius, -HALF_PI)
        FloatingButtonShape.PENTAGON -> fitted(regularPolygon(5, center, radius, -HALF_PI), size)
        FloatingButtonShape.HEXAGON -> regularPolygon(6, center, radius, -HALF_PI)
        FloatingButtonShape.OCTAGON -> fitted(regularPolygon(8, center, radius, (PI / 8).toFloat()), size)
        FloatingButtonShape.STAR -> fitted(star(5, center, radius, radius * STAR_INNER, -HALF_PI), size)
        else -> null
    }
}

/** [sides] corners on a circle of [radius] round [center], the first at [rotation] radians. */
internal fun regularPolygon(sides: Int, center: Offset, radius: Float, rotation: Float): List<Offset> =
    List(sides) { i ->
        val angle = rotation + 2f * PI.toFloat() * i / sides
        Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
    }

/** A star of [points] tips on [outer], with the dips between them on [inner]. */
internal fun star(points: Int, center: Offset, outer: Float, inner: Float, rotation: Float): List<Offset> =
    List(points * 2) { i ->
        val angle = rotation + PI.toFloat() * i / points
        val r = if (i % 2 == 0) outer else inner
        Offset(center.x + r * cos(angle), center.y + r * sin(angle))
    }

/**
 * Grows a polygon to fill the box and centres it: a pentagon or a star
 * on its circle is shorter than the circle and sits high in it, and an
 * octagon standing on a flat side falls short of the circle all round.
 */
private fun fitted(corners: List<Offset>, size: Size): List<Offset> {
    val left = corners.minOf { it.x }
    val top = corners.minOf { it.y }
    val width = corners.maxOf { it.x } - left
    val height = corners.maxOf { it.y } - top
    val scale = min(size.width / width, size.height / height)
    val dx = (size.width - width * scale) / 2f
    val dy = (size.height - height * scale) / 2f
    return corners.map { Offset(dx + (it.x - left) * scale, dy + (it.y - top) * scale) }
}

private fun polygonShape(shape: FloatingButtonShape) = GenericShape { size, _ ->
    val corners = floatingPolygon(shape, size) ?: return@GenericShape
    moveTo(corners[0].x, corners[0].y)
    for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
    close()
}

/**
 * [shape]'s polygon with every corner rounded off (#1372): the corner's two
 * sides are cut [amount] of the way to their middles, and a curve that
 * meets both cuts along the sides takes its place. At 1 the cuts meet, and
 * the outline is curves all the way round.
 */
private fun roundedPolygonShape(shape: FloatingButtonShape, amount: Float) = GenericShape { size, _ ->
    val corners = floatingPolygon(shape, size) ?: return@GenericShape
    roundedPolygon(this, corners, amount)
}

internal fun roundedPolygon(path: Path, corners: List<Offset>, amount: Float) {
    val n = corners.size
    val cut = amount.coerceIn(0f, 1f) / 2f
    for (i in 0 until n) {
        val corner = corners[i]
        val before = corners[(i + n - 1) % n]
        val after = corners[(i + 1) % n]
        val from = corner + (before - corner) * cut
        val to = corner + (after - corner) * cut
        if (i == 0) path.moveTo(from.x, from.y) else path.lineTo(from.x, from.y)
        path.quadraticTo(corner.x, corner.y, to.x, to.y)
    }
    path.close()
}

private val polygonShapes: Map<FloatingButtonShape, Shape> = listOf(
    FloatingButtonShape.SQUARE,
    FloatingButtonShape.DIAMOND,
    FloatingButtonShape.PENTAGON,
    FloatingButtonShape.HEXAGON,
    FloatingButtonShape.OCTAGON,
    FloatingButtonShape.STAR,
).associateWith(::polygonShape)

/** Wider than tall, as an egg on its side. */
private val OvalShape = GenericShape { size, _ ->
    val height = size.height * OVAL_HEIGHT
    addOval(Rect(0f, (size.height - height) / 2f, size.width, (size.height + height) / 2f))
}

/** A superellipse, |x|⁴ + |y|⁴ = 1: a square with its corners rounded all the way along. */
private val SquircleShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0 until SQUIRCLE_STEPS) {
        val t = 2.0 * PI * i / SQUIRCLE_STEPS
        val c = cos(t)
        val s = sin(t)
        val x = cx + cx * (sign(c) * abs(c).pow(SQUIRCLE_POWER)).toFloat()
        val y = cy + cy * (sign(s) * abs(s).pow(SQUIRCLE_POWER)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Two lobes over a point, filling the box side to side. */
private val HeartShape = GenericShape { size, _ ->
    heart(this, size.width, size.height)
}

private fun heart(path: Path, w: Float, h: Float) {
    path.moveTo(w * 0.5f, h * 0.94f)
    path.cubicTo(w * 0.18f, h * 0.72f, 0f, h * 0.52f, 0f, h * 0.32f)
    path.cubicTo(0f, h * 0.14f, w * 0.14f, h * 0.04f, w * 0.29f, h * 0.04f)
    path.cubicTo(w * 0.39f, h * 0.04f, w * 0.46f, h * 0.10f, w * 0.5f, h * 0.18f)
    path.cubicTo(w * 0.54f, h * 0.10f, w * 0.61f, h * 0.04f, w * 0.71f, h * 0.04f)
    path.cubicTo(w * 0.86f, h * 0.04f, w, h * 0.14f, w, h * 0.32f)
    path.cubicTo(w, h * 0.52f, w * 0.82f, h * 0.72f, w * 0.5f, h * 0.94f)
    path.close()
}

private const val HALF_PI = (PI / 2).toFloat()
private const val STAR_INNER = 0.5f
private const val OVAL_HEIGHT = 0.78f
private const val SQUIRCLE_STEPS = 72

/** 2 / n for the superellipse's n = 4. */
private const val SQUIRCLE_POWER = 0.5
