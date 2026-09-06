package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One favoured letter's claimed touch area, and how much bigger that is than
 * the key's own cell.
 *
 * [scale] is the smaller of the two side ratios, so a label drawn at it stays
 * inside the area on both axes.
 */
@Immutable
internal data class AutopilotArea(val area: Rect, val scale: Float)

/**
 * A letter's area never reaches further from its centre than this many key
 * widths, matching the cap the hit test itself applies. Without it a letter
 * with no neighbour on one side (the edge of a row) would draw an area running
 * off the board.
 */
private const val AutopilotMaxReach = 1.3f

/** Below this much growth an area is not worth drawing: it reads as a wobble. */
private const val AutopilotMinGrowth = 1.03f

/** At most this many areas are drawn at once, so the board stays readable. */
private const val AutopilotMaxAreas = 5

/**
 * The touch area each favoured letter has claimed, keyed by letter.
 *
 * The hit test scores a letter by `distance / (1 + strength × bias)`, so the
 * boundary between two letters sits where those scores meet: along the line
 * joining their centres, at the share of the gap their weights give them. This
 * turns that into a rectangle by asking the question once per side, against the
 * nearest neighbour on that side — which is what the user sees as "the edge
 * moved". A side with no neighbour grows against a plain unfavoured one.
 *
 * The result is the area the finger is judged against, not a decoration: a
 * letter squeezed by a likelier neighbour comes back *smaller* than its cell,
 * and is dropped by the growth floor rather than drawn as a shrunken key.
 *
 * [bounds] and [centers] are both in the key grid's own space. Letters missing
 * from either are skipped — the two maps are filled by the same positioning
 * callback, but a layout change fills them key by key.
 */
internal fun autopilotAreas(
    centers: Map<Char, Offset>,
    bounds: Map<Char, Rect>,
    bias: Map<Char, Float>,
    strength: Float,
    keyWidth: Float,
): Map<Char, AutopilotArea> {
    if (keyWidth <= 0f || bias.isEmpty() || bounds.isEmpty()) return emptyMap()
    fun weight(ch: Char) = 1f + strength * (bias[ch] ?: 0f)
    val reach = keyWidth * AutopilotMaxReach
    val areas = LinkedHashMap<Char, AutopilotArea>()
    for ((ch, favour) in bias) {
        if (favour <= 0f) continue
        val cell = bounds[ch] ?: continue
        val center = centers[ch] ?: continue
        val own = weight(ch)
        // The nearest neighbour on each side, and how far away it is. A key is
        // a neighbour horizontally when the rows overlap and vertically when
        // the columns do, which is what keeps a staggered row's own keys as its
        // left and right neighbours rather than the row below.
        var left = 0f
        var right = 0f
        var up = 0f
        var down = 0f
        var leftW = 1f
        var rightW = 1f
        var upW = 1f
        var downW = 1f
        for ((other, otherCenter) in centers) {
            if (other == ch) continue
            val dx = otherCenter.x - center.x
            val dy = otherCenter.y - center.y
            if (abs(dy) < cell.height * 0.5f) {
                if (dx > 0f && (right == 0f || dx < right)) {
                    right = dx
                    rightW = weight(other)
                } else if (dx < 0f && (left == 0f || -dx < left)) {
                    left = -dx
                    leftW = weight(other)
                }
            }
            if (abs(dx) < cell.width * 0.5f) {
                if (dy > 0f && (down == 0f || dy < down)) {
                    down = dy
                    downW = weight(other)
                } else if (dy < 0f && (up == 0f || -dy < up)) {
                    up = -dy
                    upW = weight(other)
                }
            }
        }
        // The share of the gap this letter's weight claims. With no neighbour
        // on that side the gap is the one an identical key would sit across.
        fun side(gap: Float, half: Float, neighbour: Float): Float {
            val span = if (gap > 0f) gap else half * 2f
            val other = if (gap > 0f) neighbour else 1f
            return (span * own / (own + other)).coerceAtMost(reach)
        }
        val halfW = cell.width / 2f
        val halfH = cell.height / 2f
        val area = Rect(
            left = center.x - side(left, halfW, leftW),
            top = center.y - side(up, halfH, upW),
            right = center.x + side(right, halfW, rightW),
            bottom = center.y + side(down, halfH, downW),
        )
        val grown = minOf(area.width / cell.width, area.height / cell.height)
        if (grown < AutopilotMinGrowth) continue
        areas[ch] = AutopilotArea(area, grown)
    }
    if (areas.size <= AutopilotMaxAreas) return areas
    return areas.entries
        .sortedByDescending { bias[it.key] ?: 0f }
        .take(AutopilotMaxAreas)
        .associate { it.key to it.value }
}

/**
 * Autopilot made visible: each favoured letter drawn at the size its touch area
 * has grown to, and the exact boundary that area claimed.
 *
 * Both halves are opt-in and independent — one shows what the strength setting
 * is doing while typing, the other is for tuning it. Drawn over the keys rather
 * than by them: a key that resized itself would move the grid under the finger,
 * and reading the live next-letter distribution in a key would cost the whole
 * board its per-keystroke skip.
 *
 * A plain [Canvas] and unclickable boxes take no touches, so the presses these
 * areas describe still land on the keys underneath.
 */
@Composable
internal fun BoxScope.AutopilotOverlay(
    centers: Map<Char, Offset>,
    bounds: Map<Char, Rect>,
    bias: Map<Char, Float>,
    strength: Float,
    keyWidth: Float,
    /** The letter as the key draws it: shift, numerals and styles applied. */
    label: (Char) -> String,
    settings: KeyboardSettings,
    palette: KeyPalette,
    kb: KbTheme,
    showEffect: Boolean,
    outline: Boolean,
) {
    // Resolved here rather than by the caller so the live maps are read in this
    // leaf's own scope: a key reporting its position then invalidates the
    // overlay instead of the whole board.
    val areas = autopilotAreas(centers, bounds, bias, strength, keyWidth)
    if (areas.isEmpty()) return
    val density = LocalDensity.current
    val shape = kb.keyShape(bleedDp = keyGapH(settings).value)
    if (showEffect) {
        for ((ch, grown) in areas) {
            val area = grown.area
            Box(
                modifier = Modifier
                    // The real key underneath is what TalkBack reads: this is
                    // the same letter drawn a second time, and announcing it
                    // would put a stray "e" in the middle of the grid.
                    .clearAndSetSemantics { }
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(
                        width = with(density) { area.width.toDp() },
                        height = with(density) { area.height.toDp() },
                    )
                    .padding(horizontal = keyGapH(settings), vertical = keyGapV(settings))
                    .background(palette.key, shape)
                    .then(
                        // The theme's own key border, so a grown key is the same
                        // key drawn larger and not a plain slab over the board.
                        kb.keyBorder
                            ?.takeIf { kb.keyBorderWidthDp > 0f }
                            ?.let { Modifier.border(kb.keyBorderWidthDp.dp, it, shape) }
                            ?: Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(ch),
                    fontSize = (LetterLabelSp * settings.fontScale * grown.scale).sp,
                    fontWeight = if (settings.boldKeyLabels) FontWeight.Bold else FontWeight.Medium,
                    color = palette.keyText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (outline) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(width = 1.dp.toPx())
            for (grown in areas.values) {
                drawRect(
                    color = kb.accent,
                    topLeft = grown.area.topLeft,
                    size = grown.area.size,
                    style = stroke,
                )
            }
        }
    }
}
