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
 * Which of the [bound] directions a swipe of ([dx], [dy]) pixels means: the
 * one nearest its angle, within [FLICK_REACH_DEGREES]. With only up, down,
 * left and right bound, a diagonal swipe still lands on the nearer of its
 * two, rather than on an unbound diagonal that does nothing. Null for a
 * swipe nowhere near any of them.
 */
fun flickDirectionOf(dx: Float, dy: Float, bound: Collection<FlickDirection>): FlickDirection? {
    if (bound.isEmpty() || (dx == 0f && dy == 0f)) return null
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
    return bound
        .map { it to angleBetween(angle, it.degrees) }
        .filter { it.second <= FLICK_REACH_DEGREES }
        .minByOrNull { it.second }
        ?.first
}

private fun angleBetween(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** How far off a direction a swipe may be and still count as it: its own 45° and half of each neighbour's. */
const val FLICK_REACH_DEGREES = 67.5f

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
