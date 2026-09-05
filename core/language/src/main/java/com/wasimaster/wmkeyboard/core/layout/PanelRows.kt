package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.roundToInt

/**
 * How a panel layout's rows share the key area.
 *
 * A panel is drawn into exactly the height the key rows would have taken (the
 * keyboard window must not change height when a tool opens), so its rows
 * cannot each simply be a key tall. The rule: a row where a
 * [KeyAction.Field] cell *starts* is a **flex** row and shares whatever height
 * the other rows leave; every other row is **fixed** at the key height its
 * `rowHeights` multiplier gives it, so the abc / space / backspace row under
 * an emoji grid is the same height as the keys it stands in for. A layout
 * with no field at all — the text-editing pad — has nothing to give the
 * spare height to, so every row flexes by weight, which is how that pad has
 * always been drawn.
 *
 * Pure arithmetic, shared by the keyboard and the editor's preview so the two
 * cannot disagree, and unit tested on its own.
 */

/**
 * Which rows of [rows] are flex rows: those holding a field cell that starts
 * there. A field spanning three rows makes only its first row flex; the rows
 * it reaches into are sized by their own keys, and the field is simply as tall
 * as the rows it covers.
 */
fun panelFlexRows(rows: List<List<Key>>): BooleanArray {
    val flex = BooleanArray(rows.size)
    for (r in rows.indices) {
        if (rows[r].any { it.action is KeyAction.Field }) flex[r] = true
    }
    return flex
}

/**
 * The top edge of every row, plus the bottom of the last, in pixels — an array
 * of `rows + 1` entries whose last is exactly [totalPx].
 *
 * [fixedPx] is what each row would take as a fixed row (its scaled key height
 * plus the vertical gaps); [weights] is each row's `rowHeights` multiplier,
 * already clamped; [flex] marks the flex rows. Flex rows split
 * `totalPx − Σ fixed` by weight. When no row flexes, all of them do. When the
 * fixed rows alone overrun [totalPx] — a nine-row panel on a short keyboard —
 * they are scaled down together so the sum still lands on [totalPx]: the panel
 * absorbs the shortfall, the window never grows.
 *
 * Tops are accumulated as a float and rounded one by one, so rounding cannot
 * leave a seam between two rows or a stray pixel at the bottom.
 */
fun panelRowTops(
    fixedPx: IntArray,
    weights: FloatArray,
    flex: BooleanArray,
    totalPx: Int,
): IntArray {
    val count = fixedPx.size
    require(weights.size == count && flex.size == count) { "row arrays must agree" }
    val tops = IntArray(count + 1)
    if (count == 0) return tops
    val total = totalPx.coerceAtLeast(0)

    val anyFlex = flex.any { it }
    val flexRow = if (anyFlex) flex else BooleanArray(count) { true }

    var fixedSum = 0f
    var weightSum = 0f
    for (r in 0 until count) {
        if (flexRow[r]) weightSum += weights[r].coerceAtLeast(0f) else fixedSum += fixedPx[r].coerceAtLeast(0)
    }
    // Fixed rows that do not fit are scaled together; flex rows then get zero.
    val fixedScale = if (fixedSum > total && fixedSum > 0f) total / fixedSum else 1f
    val slack = (total - fixedSum * fixedScale).coerceAtLeast(0f)

    var covered = 0f
    for (r in 0 until count) {
        covered += if (flexRow[r]) {
            if (weightSum > 0f) slack * (weights[r].coerceAtLeast(0f) / weightSum) else slack / flexRow.count { it }
        } else {
            fixedPx[r].coerceAtLeast(0) * fixedScale
        }
        tops[r + 1] = covered.roundToInt().coerceIn(tops[r], total)
    }
    tops[count] = total
    return tops
}
