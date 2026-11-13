package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LongState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import android.os.SystemClock
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One favoured letter's key [cell] grown by the ground the bias won it, and how
 * much bigger the one is than the other.
 *
 * [area] is the cell plus that gain rather than the claimed region outright,
 * because on a grid whose keys differ in width the two are not the same
 * rectangle. The hit test is nearest-centre, so the line between a wide key and
 * a narrow one already falls *inside* the wide key: measured against its own
 * cell, a favoured letter on such a row reads as having shrunk. What the
 * drawing is for is the difference the bias made, and that is what this holds.
 *
 * [scale] is the smaller of the two side ratios, so a label drawn at it stays
 * inside the area on both axes.
 */
@Immutable
internal data class AutopilotArea(val area: Rect, val cell: Rect, val scale: Float)

/**
 * The area as the "Show the effect" setting draws it, with every side's growth
 * multiplied [by] the user's number. 1.0 gives the area back untouched.
 *
 * The growth is what is multiplied, not the rectangle: a side that claimed
 * nothing extra stays on the cell's own edge, so raising the number stretches
 * the difference between the letters rather than inflating all of them. Each
 * side is scaled from its own cell edge, which keeps a letter that grew to one
 * side lopsided instead of recentring it.
 */
internal fun AutopilotArea.drawnAt(by: Float): AutopilotArea {
    if (by == 1f) return this
    val drawn = Rect(
        left = cell.left - (cell.left - area.left) * by,
        top = cell.top - (cell.top - area.top) * by,
        right = cell.right + (area.right - cell.right) * by,
        bottom = cell.bottom + (area.bottom - cell.bottom) * by,
    )
    return AutopilotArea(
        area = drawn,
        cell = cell,
        scale = minOf(drawn.width / cell.width, drawn.height / cell.height),
    )
}

/**
 * A letter's area never reaches further from its centre than this many key
 * widths, matching the cap the hit test itself applies. Without it a letter
 * with no neighbour on one side (the edge of a row) would draw an area running
 * off the board.
 */
private const val AutopilotMaxReach = 1.3f

/**
 * A letter is only drawn when it is this likely next, as a share of the
 * likeliest one. Everything the word list mentions in passing is filtered out,
 * so what is on the board is the handful of letters autopilot is really
 * favouring rather than a wash of near-misses.
 */
private const val AutopilotMinBias = 0.15f

/** At most this many areas are drawn at once, so the board stays readable. */
private const val AutopilotMaxAreas = 3

/**
 * How long after the last key press the drawing stays up. Autopilot itself has
 * no such window — it goes on nudging whatever is composing — but the drawing
 * is about the word being typed, and moving the caret into an old word starts
 * a composing run nobody asked to see the insides of.
 */
internal const val AutopilotIdleMs = 3_500L

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
 * Every edge is worked out twice: once with this letter's weight, and once as
 * if the word list had said nothing at all. The difference is the ground the
 * bias won, and a letter that won none of it — because its neighbours are as
 * likely as it is, or likelier — is dropped rather than drawn.
 *
 * The plain claim is the baseline rather than the drawn key, and that
 * distinction is the whole of issue #76's "wrong letters": the hit test is
 * nearest-centre, so on a row of mixed key widths the plain boundary already
 * sits inside the wider key. Measured against the cell, every letter beside a
 * narrow key looked shrunken and was thrown away — leaving whichever letters
 * happened to have same-width neighbours, not the likeliest ones.
 *
 * [bounds] and [centers] are both in the key grid's own space. Letters missing
 * from either are skipped — the two maps are filled by the same positioning
 * callback, but a layout change fills them key by key.
 */
internal fun autopilotAreas(
    centers: Map<Int, Offset>,
    bounds: Map<Int, Rect>,
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
        val cell = bounds[ch.code] ?: continue
        val center = centers[ch.code] ?: continue
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
        for ((otherCp, otherCenter) in centers) {
            if (otherCp == ch.code) continue
            // A neighbour outside the BMP has no bias entry, so it weighs 1.
            val other = if (otherCp <= 0xFFFF) otherCp.toChar() else null
            val dx = otherCenter.x - center.x
            val dy = otherCenter.y - center.y
            if (abs(dy) < cell.height * 0.5f) {
                if (dx > 0f && (right == 0f || dx < right)) {
                    right = dx
                    rightW = other?.let(::weight) ?: 1f
                } else if (dx < 0f && (left == 0f || -dx < left)) {
                    left = -dx
                    leftW = other?.let(::weight) ?: 1f
                }
            }
            if (abs(dx) < cell.width * 0.5f) {
                if (dy > 0f && (down == 0f || dy < down)) {
                    down = dy
                    downW = other?.let(::weight) ?: 1f
                } else if (dy < 0f && (up == 0f || -dy < up)) {
                    up = -dy
                    upW = other?.let(::weight) ?: 1f
                }
            }
        }
        // The share of the gap this letter's weight claims. With no neighbour
        // on that side the gap is the one an identical key would sit across.
        fun claim(gap: Float, half: Float, neighbour: Float): Float {
            val span = if (gap > 0f) gap else half * 2f
            val other = if (gap > 0f) neighbour else 1f
            return (span * own / (own + other)).coerceAtMost(reach)
        }
        // The same edge with nothing favoured anywhere: the plain nearest-centre
        // boundary, which is the midpoint of the gap whatever the two keys
        // measure. This, not the cell, is what growth is judged against.
        fun plain(gap: Float, half: Float) = if (gap > 0f) gap / 2f else half
        val halfW = cell.width / 2f
        val halfH = cell.height / 2f
        val claimL = claim(left, halfW, leftW)
        val claimR = claim(right, halfW, rightW)
        val claimU = claim(up, halfH, upW)
        val claimD = claim(down, halfH, downW)
        val plainL = plain(left, halfW)
        val plainR = plain(right, halfW)
        val plainU = plain(up, halfH)
        val plainD = plain(down, halfH)
        // Nothing won on an axis means nothing to draw: the neighbours are as
        // likely as this letter, or likelier, and the boundary stayed put or
        // moved the wrong way.
        if (claimL + claimR <= plainL + plainR) continue
        if (claimU + claimD <= plainU + plainD) continue
        // Drawn as the key plus what the bias won it, so a favoured letter is
        // never drawn smaller than the key the user is looking at.
        val area = Rect(
            left = cell.left - (claimL - plainL).coerceAtLeast(0f),
            top = cell.top - (claimU - plainU).coerceAtLeast(0f),
            right = cell.right + (claimR - plainR).coerceAtLeast(0f),
            bottom = cell.bottom + (claimD - plainD).coerceAtLeast(0f),
        )
        areas[ch] = AutopilotArea(
            area = area,
            cell = cell,
            scale = minOf(area.width / cell.width, area.height / cell.height),
        )
    }
    // Ranked by how likely the letter is, not by how much room it won. The two
    // part company on a crowded row — the likeliest letter can be boxed in by
    // its neighbours and grow the least of the lot — and it is the ranking the
    // user is reading off the board.
    val top = bias.entries.maxByOrNull { it.value }?.value ?: return emptyMap()
    return areas.entries
        .filter { (bias[it.key] ?: 0f) >= top * AutopilotMinBias }
        .sortedByDescending { bias[it.key] ?: 0f }
        .take(AutopilotMaxAreas)
        .associate { it.key to it.value }
}

/**
 * The area with both edges pulled inside a board [width] by [height].
 *
 * A letter on the first or last column has no neighbour to share a gap with, so
 * it grows against an imaginary one and can reach past the edge of the
 * keyboard. Nothing out there can be pressed, and a key drawn half off the
 * screen looks like a fault rather than a hint.
 */
internal fun AutopilotArea.clampedTo(width: Float, height: Float): AutopilotArea {
    if (width <= 0f || height <= 0f) return this
    if (area.left >= 0f && area.top >= 0f && area.right <= width && area.bottom <= height) {
        return this
    }
    val clamped = Rect(
        left = area.left.coerceIn(0f, width),
        top = area.top.coerceIn(0f, height),
        right = area.right.coerceIn(0f, width),
        bottom = area.bottom.coerceIn(0f, height),
    )
    return AutopilotArea(
        area = clamped,
        cell = cell,
        scale = minOf(clamped.width / cell.width, clamped.height / cell.height),
    )
}

/**
 * Autopilot made visible: each favoured letter drawn at the size its touch area
 * has grown to, and the boundary that area claimed.
 *
 * Both halves are opt-in and independent — one shows what the strength setting
 * is doing while typing, the other is for tuning it. They draw the *same*
 * rectangle, the exaggeration included, so the outline always traces the key it
 * is drawn around rather than sitting a gap outside it.
 *
 * Drawn over the keys rather than by them: a key that resized itself would move
 * the grid under the finger, and reading the live next-letter distribution in a
 * key would cost the whole board its per-keystroke skip.
 *
 * A plain [Canvas] and unclickable boxes take no touches, so the presses these
 * areas describe still land on the keys underneath.
 */
@Composable
internal fun BoxScope.AutopilotOverlay(
    centers: Map<Int, Offset>,
    bounds: Map<Int, Rect>,
    bias: Map<Char, Float>,
    strength: Float,
    keyWidth: Float,
    /** The key grid's own size, which nothing drawn here reaches past. */
    boardSize: Size,
    /** A glide stroke is on the board, so it is not this overlay's to draw on. */
    glideActive: Boolean,
    /** Uptime of the last tap-typed key; see [AutopilotIdleMs]. */
    lastKeyPress: LongState,
    /** The letter as the key draws it: shift, numerals and styles applied. */
    label: (Char) -> String,
    settings: KeyboardSettings,
    palette: KeyPalette,
    kb: KbTheme,
    showEffect: Boolean,
    /** How much bigger than life [showEffect] draws a letter; 1.0 is true size. */
    visualScale: Float,
    outline: Boolean,
    /**
     * The outline a letter's key is drawn in when it is not the theme's (a
     * honeycomb's hexagons, [HexFaceShape]), so the grown key is drawn as the
     * same shape, larger. Null for the theme's shape.
     */
    shapeOf: (Char) -> Shape? = { null },
) {
    // Resolved here rather than by the caller so the live maps are read in this
    // leaf's own scope: a key reporting its position then invalidates the
    // overlay instead of the whole board.
    // Typing is what this describes. A glide owns the board for the length of
    // its stroke, and a caret dropped into an old word opens a composing run
    // the user never asked to see the workings of — neither should light the
    // grid up. Read here, in the leaf, so a keystroke stamp does not invalidate
    // the board on its way past.
    val idle = SystemClock.uptimeMillis() - lastKeyPress.longValue > AutopilotIdleMs
    val claimed = if (glideActive || idle) {
        emptyMap()
    } else {
        autopilotAreas(centers, bounds, bias, strength, keyWidth)
    }
    // The exaggeration and the clamp are applied once, here, because the face
    // and the outline below have to agree down to the pixel.
    val areas = claimed.mapValues {
        it.value.drawnAt(visualScale).clampedTo(boardSize.width, boardSize.height)
    }
    // Typing › Autopilot › Animate: the areas ease to their new size and the
    // letters fade as they gain and lose favour, instead of jumping per key.
    if (kb.motion.autopilot) {
        AnimatedAutopilotAreas(
            areas = areas,
            // A stroke takes the board at once; only typing eases.
            instant = glideActive,
            label = label,
            settings = settings,
            palette = palette,
            kb = kb,
            showEffect = showEffect,
            outline = outline,
            shapeOf = shapeOf,
        )
        return
    }
    if (areas.isEmpty()) return
    val density = LocalDensity.current
    val gapH = keyGapH(settings)
    val gapV = keyGapV(settings)
    val shape = kb.keyShape(bleedDp = gapH.value)
    if (showEffect) {
        for ((ch, grown) in areas) {
            val area = grown.area
            val keyShape = shapeOf(ch) ?: shape
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
                    .padding(horizontal = gapH, vertical = gapV)
                    .background(palette.key, keyShape)
                    .then(
                        // The theme's own key border, so a grown key is the same
                        // key drawn larger and not a plain slab over the board.
                        kb.keyBorder
                            ?.takeIf { kb.keyBorderWidthDp > 0f }
                            ?.let { Modifier.border(kb.keyBorderWidthDp.dp, it, keyShape) }
                            ?: Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(ch),
                    fontSize = (LetterLabelSp * settings.fontScale * grown.scale).sp,
                    fontWeight = if (settings.accessibility.boldLabels) FontWeight.Bold else FontWeight.Medium,
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
            // The same inset the face above takes from its cell, so the line
            // lands on the drawn key's edge and not a gap outside it.
            val insetX = gapH.toPx()
            val insetY = gapV.toPx()
            for ((ch, grown) in areas) {
                val face = grown.area
                val width = face.width - insetX * 2
                val height = face.height - insetY * 2
                if (width <= 0f || height <= 0f) continue
                val keyShape = shapeOf(ch)
                if (keyShape != null) {
                    val drawn = keyShape.createOutline(Size(width, height), layoutDirection, this)
                    translate(face.left + insetX, face.top + insetY) { drawOutline(drawn, kb.accent, style = stroke) }
                    continue
                }
                drawRect(
                    color = kb.accent,
                    topLeft = Offset(face.left + insetX, face.top + insetY),
                    size = Size(width, height),
                    style = stroke,
                )
            }
        }
    }
}

/**
 * [AutopilotOverlay] with motion: every favoured letter's area springs to its
 * new size and place as the bias moves, and a letter fades in as it gains
 * favour and out as it loses it.
 *
 * The same rectangle, inset and outline as the still drawing, drawn by boxes
 * that take no touches: the hit test reads the bias, never these, so easing the
 * drawing changes nothing a press lands on. The rectangle is read in the layout
 * and draw passes only, so a frame of the spring re-lays out a box rather than
 * recomposing the overlay.
 */
@Composable
private fun AnimatedAutopilotAreas(
    areas: Map<Char, AutopilotArea>,
    instant: Boolean,
    label: (Char) -> String,
    settings: KeyboardSettings,
    palette: KeyPalette,
    kb: KbTheme,
    showEffect: Boolean,
    outline: Boolean,
    shapeOf: (Char) -> Shape?,
) {
    val motion = remember { AutopilotMotion() }
    val shown = motion.settle(areas, instant)
    if (shown.isEmpty()) return
    val gapH = keyGapH(settings)
    val gapV = keyGapV(settings)
    val shape = kb.keyShape(bleedDp = gapH.value)
    for (entry in shown) {
        key(entry.char) {
            val keyShape = shapeOf(entry.char) ?: shape
            val hex = keyShape !== shape
            val target = entry.target
            val leaving = entry.leaving
            val rect = animateRectAsState(
                target.area,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = AutopilotSpringStiffness),
                label = "autopilotArea",
            )
            // Born at nothing, so a letter gaining favour never shows whole for
            // a frame before it fades in.
            val fade = remember { Animatable(0f) }
            LaunchedEffect(leaving) {
                fade.animateTo(if (leaving) 0f else 1f, tween(AutopilotFadeMs))
                if (leaving) motion.drop(entry)
            }
            Box(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .offset { IntOffset(rect.value.left.roundToInt(), rect.value.top.roundToInt()) }
                    .layout { measurable, _ ->
                        val r = rect.value
                        val width = r.width.roundToInt().coerceAtLeast(0)
                        val height = r.height.roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        alpha = fade.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .padding(horizontal = gapH, vertical = gapV)
                    .then(
                        if (showEffect) {
                            Modifier
                                .background(palette.key, keyShape)
                                .then(
                                    kb.keyBorder
                                        ?.takeIf { kb.keyBorderWidthDp > 0f }
                                        ?.let { Modifier.border(kb.keyBorderWidthDp.dp, it, keyShape) }
                                        ?: Modifier,
                                )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (outline) {
                            Modifier.drawWithContent {
                                drawContent()
                                val stroke = Stroke(width = 1.dp.toPx())
                                if (hex) {
                                    drawOutline(keyShape.createOutline(size, layoutDirection, this), kb.accent, style = stroke)
                                } else {
                                    drawRect(color = kb.accent, style = stroke)
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (showEffect) {
                    Text(
                        text = label(entry.char),
                        // Laid out at the key's own size and scaled with the
                        // area as it moves, so the letter grows with its box
                        // rather than stepping a size per keystroke.
                        fontSize = (LetterLabelSp * settings.fontScale).sp,
                        fontWeight = if (settings.accessibility.boldLabels) FontWeight.Bold else FontWeight.Medium,
                        color = palette.keyText,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val r = rect.value
                            val cell = target.cell
                            if (cell.width > 0f && cell.height > 0f) {
                                val grown = minOf(r.width / cell.width, r.height / cell.height)
                                scaleX = grown
                                scaleY = grown
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One letter's area on screen, and whether it is on its way out. */
private class AutopilotEntry(val char: Char, var target: AutopilotArea) {
    var leaving = false
}

/**
 * The letters drawn, including the ones fading out. Plain fields worked out in
 * composition, so running it twice with the same areas changes nothing;
 * [version] is bumped when a letter has finished fading, to drop it.
 */
private class AutopilotMotion {
    private val entries = LinkedHashMap<Char, AutopilotEntry>()
    private var version by mutableIntStateOf(0)

    fun settle(areas: Map<Char, AutopilotArea>, instant: Boolean): List<AutopilotEntry> {
        // Read, so a letter finishing its fade recomposes the overlay.
        check(version >= 0)
        if (instant) entries.clear()
        for ((ch, area) in areas) {
            val entry = entries.getOrPut(ch) { AutopilotEntry(ch, area) }
            entry.target = area
            entry.leaving = false
        }
        for (entry in entries.values) {
            if (entry.char !in areas) entry.leaving = true
        }
        return entries.values.toList()
    }

    fun drop(entry: AutopilotEntry) {
        if (entry.leaving && entries[entry.char] === entry) {
            entries.remove(entry.char)
            version++
        }
    }
}

/** Snappy enough to settle in about 120 ms, with no overshoot. */
private const val AutopilotSpringStiffness = 2_500f

/** A letter fading in as it gains favour, or out as it loses it. */
private const val AutopilotFadeMs = 120
