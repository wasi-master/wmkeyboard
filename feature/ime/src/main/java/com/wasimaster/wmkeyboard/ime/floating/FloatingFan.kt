package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the quick menu's [count] slots go round a button centred on [anchor]
 * (#755), on a screen [width] by [height], all in pixels.
 *
 * The slots fan out on an arc that faces the middle of the screen, so a button
 * resting on the right edge opens to the left, one in a top corner opens down
 * and inwards. The facing is worked out on the screen as fractions of its
 * sides, not in pixels: on a tall phone the pixel centre is so far above or
 * below a button near either end that the arc turned almost straight up or
 * down. Neighbours sit at least [spacing] apart along the arc: when half a
 * circle at [radius] is too short for that, the arc grows rather than
 * crowding.
 *
 * Every slot is kept [margin] inside the screen by turning the whole arc,
 * the least it takes, away from the edge it would cross, and when no turn
 * does it, by widening the arc so it sweeps a narrower angle: from a button
 * on an edge, half a circle always pokes past that edge at both ends. Clamping
 * each slot on its own, as this once did, pushed the ones past an edge onto
 * the same line and stacked them on top of each other. A crowded menu deep in
 * a corner that still fits no way is slid in whole instead.
 */
fun floatingFan(
    count: Int,
    anchor: Offset,
    width: Float,
    height: Float,
    radius: Float,
    spacing: Float,
    margin: Float,
): List<Offset> {
    if (count <= 0) return emptyList()
    val facing = atan2(0.5f - anchor.y / height, 0.5f - anchor.x / width)
    // Half a circle holds count - 1 gaps of `spacing` once the radius allows it.
    val baseRing = max(radius, if (count > 1) spacing * (count - 1) / PI.toFloat() else radius)
    val maxX = max(margin, width - margin)
    val maxY = max(margin, height - margin)
    fun overflow(slots: List<Offset>): Float = slots.maxOf { p ->
        maxOf(margin - p.x, p.x - maxX, margin - p.y, p.y - maxY, 0f)
    }
    var best: List<Offset> = emptyList()
    var bestOverflow = Float.MAX_VALUE
    for (grow in 0..FAN_MAX_GROW_STEPS) {
        val ring = baseRing * (1f + FAN_GROW_STEP * grow)
        val step = if (count > 1) min(spacing / ring, PI.toFloat() / (count - 1)) else 0f
        for (turn in 0..FAN_MAX_TURN_STEPS) {
            for (sign in if (turn == 0) intArrayOf(1) else intArrayOf(1, -1)) {
                val first = facing + sign * turn * FAN_TURN_STEP - step * (count - 1) / 2f
                val slots = List(count) { i ->
                    val angle = first + step * i
                    Offset(anchor.x + ring * cos(angle), anchor.y + ring * sin(angle))
                }
                val over = overflow(slots)
                if (over <= 0f) return slots
                if (over < bestOverflow) {
                    best = slots
                    bestOverflow = over
                }
            }
        }
    }
    // Nothing fits: slide the closest try in whole, which keeps its gaps.
    val dx = (margin - best.minOf { it.x }).coerceAtLeast(0f) - (best.maxOf { it.x } - maxX).coerceAtLeast(0f)
    val dy = (margin - best.minOf { it.y }).coerceAtLeast(0f) - (best.maxOf { it.y } - maxY).coerceAtLeast(0f)
    return best.map { Offset((it.x + dx).coerceIn(margin, maxX), (it.y + dy).coerceIn(margin, maxY)) }
}

/**
 * [floatingFan] for a menu of any size up to two rings (#1172): the first
 * [FLOATING_RING_SIZE] slots on the arc they always had, so a menu of eight or
 * fewer looks as it did, and the rest on a second arc.
 *
 * That arc is inside the first, one [spacing] nearer the button, for as many
 * of the rest as its half circle holds (#1400): eight slots fan out far
 * enough from the button to leave room there, and a slot inside is a shorter
 * reach than one outside. What does not fit inside goes on an arc outside the
 * first, one [spacing] further out, as all of them once did. The arcs are a
 * [spacing] apart, so no slot on one can crowd one on another; and should
 * fitting them on screen have turned or widened the inner arc into the others
 * anyway, the rest all go outside instead. A hold's slide reaches every ring.
 */
fun floatingRings(
    count: Int,
    anchor: Offset,
    width: Float,
    height: Float,
    radius: Float,
    spacing: Float,
    margin: Float,
): List<Offset> {
    if (count <= FLOATING_RING_SIZE) return floatingFan(count, anchor, width, height, radius, spacing, margin)
    val first = floatingFan(FLOATING_RING_SIZE, anchor, width, height, radius, spacing, margin)
    // Out from wherever the first arc settled, which grows past [radius] to fit its slots.
    val firstRadius = first.maxOf { (it - anchor).getDistance() }
    val rest = count - FLOATING_RING_SIZE
    fun outside(n: Int) = floatingFan(n, anchor, width, height, firstRadius + spacing, spacing, margin)
    val insideRadius = firstRadius - spacing
    val fits = if (insideRadius < radius) 0 else (rest).coerceAtMost(innerRingRoom(insideRadius, spacing))
    if (fits > 0) {
        val inside = floatingFan(fits, anchor, width, height, insideRadius, spacing, margin)
        val beyond = if (fits < rest) outside(rest - fits) else emptyList()
        val all = first + inside + beyond
        if (inside.all { (it - anchor).getDistance() <= insideRadius + 1f } && apart(all, spacing * MIN_GAP_SHARE)) {
            return all
        }
    }
    return first + outside(rest)
}

/** How many slots fit on half a circle of [ring] with neighbours [spacing] apart (#1400). */
internal fun innerRingRoom(ring: Float, spacing: Float): Int =
    if (spacing <= 0f) 0 else (PI.toFloat() * ring / spacing).toInt() + 1

/** Every two of [slots] at least [gap] apart. */
private fun apart(slots: List<Offset>, gap: Float): Boolean {
    val min = gap * gap
    for (i in slots.indices) for (j in i + 1 until slots.size) {
        if ((slots[i] - slots[j]).getDistanceSquared() < min) return false
    }
    return true
}

/** How close two slots on different rings may come, as a share of [floatingRings]' spacing. */
private const val MIN_GAP_SHARE = 0.8f

/** How many slots the first ring holds before a second one starts (#1172): the eight it always held. */
const val FLOATING_RING_SIZE = 8

/** How far the arc turns per try while looking for a fit: 5°, up to a half turn either way. */
private const val FAN_TURN_STEP = (PI / 36).toFloat()
private const val FAN_MAX_TURN_STEPS = 36

/** How much wider the arc gets per round of turns that found no fit, which narrows its sweep. */
private const val FAN_GROW_STEP = 0.15f
private const val FAN_MAX_GROW_STEPS = 4

/** The slot within [reach] of [point], nearest first, or null. */
fun floatingSlotAt(point: Offset, centers: List<Offset>, reach: Float): Int? {
    var best: Int? = null
    var bestDistance = reach * reach
    centers.forEachIndexed { i, c ->
        val d = (c - point).getDistanceSquared()
        if (d <= bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best
}
