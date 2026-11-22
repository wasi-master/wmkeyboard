package com.wasimaster.wmkeyboard.core.settings

import kotlin.math.abs
import kotlin.math.atan2

/**
 * The ways a swipe can leave the floating button while it is fixed in place
 * (#1171), each of which can run its own [FloatingAction]. [degrees] is the
 * direction on screen, clockwise from straight right, as the screen's y axis
 * points down.
 */
enum class FlickDirection(val degrees: Float) {
    UP(270f),
    UP_RIGHT(315f),
    RIGHT(0f),
    DOWN_RIGHT(45f),
    DOWN(90f),
    DOWN_LEFT(135f),
    LEFT(180f),
    UP_LEFT(225f),
}

/**
 * Which of the [bound] directions a swipe of ([dx], [dy]) pixels means. Each
 * direction answers to a fan of [angles] degrees centred on it (#1171),
 * [DEFAULT_FLICK_ANGLE] when it has none of its own: a swipe counts for a
 * direction while it is within half that fan, and where two fans overlap it
 * goes to the one it sits deeper in, measured against each fan's own half.
 * So a wider fan takes ground from its narrower neighbours, and with every
 * fan equal the nearest direction wins. With only up, down, left and right bound, a diagonal swipe
 * still lands on the nearer of its two, rather than on an unbound diagonal
 * that does nothing. Null for a swipe outside every fan.
 */
fun flickDirectionOf(
    dx: Float,
    dy: Float,
    bound: Collection<FlickDirection>,
    angles: Map<FlickDirection, Int> = emptyMap(),
): FlickDirection? {
    if (bound.isEmpty() || (dx == 0f && dy == 0f)) return null
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
    return bound
        .map { direction ->
            val half = flickAngleOf(direction, angles) / 2f
            direction to angleBetween(angle, direction.degrees) / half
        }
        .filter { it.second <= 1f }
        .minByOrNull { it.second }
        ?.first
}

/** The fan [direction] answers to, in degrees: its own from [angles], clamped to [FlickAngleRange], or the default. */
fun flickAngleOf(direction: FlickDirection, angles: Map<FlickDirection, Int>): Int =
    angles[direction]?.coerceIn(FlickAngleRange.first, FlickAngleRange.last) ?: DEFAULT_FLICK_ANGLE

private fun angleBetween(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/**
 * The fan a direction answers to unless set otherwise: its own 45° and half of
 * each neighbour's, so 67.5° either side of it.
 */
const val DEFAULT_FLICK_ANGLE = 135

/** Bounds for a direction's fan, in degrees (#1171). The sliders share them. */
val FlickAngleRange = 20..240

/** How far a swipe from the fixed button goes before it counts as one, in dp, unless set otherwise. */
const val DEFAULT_FLICK_DISTANCE_DP = 28

/** Bounds for [SummonButtonSettings.flickDistanceDp], in dp (#1171). The slider shares them. */
val FlickDistanceDpRange = 12..96

/** The stored form of the fans: `UP=60,RIGHT=150`. Directions left at [DEFAULT_FLICK_ANGLE] are left out. */
fun encodeFlickAngles(angles: Map<FlickDirection, Int>): String =
    FlickDirection.entries.mapNotNull { direction ->
        angles[direction]?.coerceIn(FlickAngleRange.first, FlickAngleRange.last)
            ?.takeIf { it != DEFAULT_FLICK_ANGLE }
            ?.let { "${direction.name}=$it" }
    }.joinToString(",")

/** The inverse of [encodeFlickAngles]; malformed entries are dropped. */
fun decodeFlickAngles(raw: String?): Map<FlickDirection, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',').mapNotNull { entry ->
        val at = entry.indexOf('=')
        if (at <= 0) return@mapNotNull null
        val direction = FlickDirection.entries.firstOrNull { it.name == entry.substring(0, at).trim() }
            ?: return@mapNotNull null
        val degrees = entry.substring(at + 1).trim().toIntOrNull()
            ?.coerceIn(FlickAngleRange.first, FlickAngleRange.last) ?: return@mapNotNull null
        direction to degrees
    }.toMap()
}

/**
 * The stored form: `UP=tool:VOICE,LEFT=system:BACK`. Neither a direction
 * nor an action's token holds a comma or an equals sign. Unbound directions,
 * and actions this build does not know, are left out.
 */
fun encodeFloatingFlicks(flicks: Map<FlickDirection, FloatingAction>): String =
    FlickDirection.entries.mapNotNull { direction ->
        flicks[direction]?.takeIf { it != FloatingAction.None }?.let { "${direction.name}=${it.token}" }
    }.joinToString(",")

/** The inverse of [encodeFloatingFlicks]; malformed entries are dropped. */
fun decodeFloatingFlicks(raw: String?): Map<FlickDirection, FloatingAction> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',').mapNotNull { entry ->
        val at = entry.indexOf('=')
        if (at <= 0) return@mapNotNull null
        val direction = FlickDirection.entries.firstOrNull { it.name == entry.substring(0, at).trim() }
            ?: return@mapNotNull null
        val action = FloatingAction.parse(entry.substring(at + 1).trim())
            ?.takeIf { it != FloatingAction.None } ?: return@mapNotNull null
        direction to action
    }.toMap()
}
