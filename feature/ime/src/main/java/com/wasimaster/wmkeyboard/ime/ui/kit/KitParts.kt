package com.wasimaster.wmkeyboard.ime.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The pieces the revamped tool panels are built from: the hero ring, a stat
 * tile, the action bar along the bottom, pager dots, a segmented pill, a
 * progress rail. Everything reads the keyboard theme (its chip and card
 * shapes, its glass, its outline) and nothing holds state of its own beyond
 * an animation, so a theme reaches all of them at once.
 */

/**
 * A 270° ring open at the bottom, the way a dial is: the track in the
 * theme's modifier-key colour, the accent filled to [fraction], a brighter
 * tip at the end of the fill. [fraction] is read in the draw pass alone, so
 * a glide redraws the arc and nothing else; null is a dormant ring with no
 * fill. [content] sits in the middle.
 */
@Composable
internal fun HeroRing(
    fraction: () -> Float?,
    modifier: Modifier = Modifier,
    stroke: Dp = 10.dp,
    /** Seven small marks around the track, as a dial has. */
    ticks: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val kb = LocalKbTheme.current
    val track = kb.modifierKey
    val accent = kb.accent
    val tip = maxContrastOn(accent).copy(alpha = 0.9f)
    val tick = kb.secondaryText.copy(alpha = 0.45f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val radius = min(size.width, size.height) / 2f - w
            val centre = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(centre.x - radius, centre.y - radius)
            val arc = Size(radius * 2, radius * 2)
            drawArc(track, RING_START, RING_SWEEP, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
            if (ticks) {
                for (i in 0..RING_TICKS) {
                    val angle = Math.toRadians((RING_START + RING_SWEEP * i / RING_TICKS).toDouble())
                    val inner = radius - w * 1.4f
                    val outer = radius - w * 0.9f
                    drawLine(
                        tick,
                        Offset(centre.x + inner * cos(angle).toFloat(), centre.y + inner * sin(angle).toFloat()),
                        Offset(centre.x + outer * cos(angle).toFloat(), centre.y + outer * sin(angle).toFloat()),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            val f = fraction()?.coerceIn(0f, 1f) ?: return@Canvas
            if (f <= 0f) return@Canvas
            drawArc(accent, RING_START, RING_SWEEP * f, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
            val end = Math.toRadians((RING_START + RING_SWEEP * f).toDouble())
            drawCircle(tip, w * 0.22f, Offset(centre.x + radius * cos(end).toFloat(), centre.y + radius * sin(end).toFloat()))
        }
        content()
    }
}

/** A stat in a soft tile: a small caps label, the number large in tabular digits, its unit under. */
@Composable
internal fun StatTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    /** Its place in a row that pops in together; see [popIn]. */
    index: Int = 0,
    animate: Boolean = false,
    popKey: Any? = Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .popIn(index, animate, popKey)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
            .chipBorder(kb, shape)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.uppercase(), color = kb.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1)
        Text(
            value,
            color = kb.suggestionText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
        Text(unit, color = kb.secondaryText.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1)
    }
}

/** One control on an [ActionBar]: the first with [primary] is the accent pill, the rest are round icons. */
internal class KitAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The bar along the bottom of a panel: the one thing to do as an accent
 * pill that takes the width, the other ways as round icon buttons beside
 * it, each naming itself on a hold.
 */
@Composable
internal fun ActionBar(actions: List<KitAction>, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val animate = kb.motion.any
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { action ->
            val source = remember { MutableInteractionSource() }
            val alpha = if (action.enabled) 1f else 0.4f
            if (action.primary) {
                val shape = kb.chipShape()
                Row(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .pressScale(source, animate)
                        .clip(shape)
                        .background(kb.accent.copy(alpha = kb.accent.alpha * alpha))
                        .clickable(enabled = action.enabled, interactionSource = source, indication = null) {
                            feedback()
                            action.onClick()
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val fg = maxContrastOn(kb.accent)
                    if (action.icon != null) {
                        Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(action.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val shape = kb.chipShape()
                IconHint(action.label) {
                    Box(
                        Modifier
                            .size(height)
                            .pressScale(source, animate)
                            .clip(shape)
                            .frostGlass(kb, shape, FrostSurface.CHIPS)
                            .background(kb.chip.copy(alpha = kb.chip.alpha * alpha))
                            .chipBorder(kb, shape)
                            .clickable(enabled = action.enabled, interactionSource = source, indication = null) {
                                feedback()
                                action.onClick()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = action.label, tint = kb.chipText.copy(alpha = kb.chipText.alpha * alpha), modifier = Modifier.size(18.dp))
                        } else {
                            Text(action.label.take(1), color = kb.chipText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/** Dots for a pager: the current one stretched into a short bar, the move eased. */
@Composable
internal fun PagerDots(count: Int, index: Int, modifier: Modifier = Modifier, animate: Boolean = true) {
    val kb = LocalKbTheme.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val on = i == index
            val width by animateDpAsState(if (on) DOT_BAR else DOT, if (animate) tween(DOT_MS) else tween(0), label = "kit-dot")
            Box(
                Modifier
                    .size(width = width, height = DOT)
                    .clip(CircleShape)
                    .background(if (on) kb.accent else kb.secondaryText.copy(alpha = 0.35f)),
            )
        }
    }
}

/**
 * Labels on one pill, the chosen one under a fill that slides to it. Lifted
 * from the Send panel's Link | Nearby (#729), with any number of segments.
 */
@Composable
internal fun SegmentedPill(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentWidth: Dp = 72.dp,
    height: Dp = 30.dp,
    enabled: (Int) -> Boolean = { true },
    focusedIndex: Int? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val offset by animateDpAsState(
        targetValue = segmentWidth * selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
        animationSpec = if (kb.reduceMotion) tween(0) else tween(SEGMENT_MS),
        label = "kit-segment",
    )
    Box(
        modifier
            .height(height)
            .width(segmentWidth * labels.size)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(shape)
                .background(kb.chipActive),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val can = enabled(i)
                Box(
                    Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .focusRing(focusedIndex == i, shape)
                        .clickable(enabled = can, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            feedback()
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = (if (on) kb.chipActiveText else kb.chipText).copy(alpha = if (can) 1f else 0.4f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One step of a [ProgressRail]: its name, how far along it is (1 = done), and whether it is the one under way. */
internal class RailStep(val label: String, val fraction: Float, val active: Boolean)

/**
 * Steps side by side, each a name over a thin bar: done ones full, the one
 * under way filling, the rest dim. A run's phases in a panel header.
 */
@Composable
internal fun RowScope.ProgressRail(steps: List<RailStep>, modifier: Modifier = Modifier, animate: Boolean = true) {
    val kb = LocalKbTheme.current
    Row(modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEach { step ->
            val done = step.fraction >= 1f
            val fill by animateFloatAsState(step.fraction.coerceIn(0f, 1f), if (animate) tween(GLIDE_MS) else tween(0), label = "kit-rail")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    step.label,
                    color = when {
                        step.active -> kb.suggestionText
                        done -> kb.secondaryText
                        else -> kb.secondaryText.copy(alpha = 0.5f)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val track = kb.divider
                val accent = kb.accent
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(track, size = size, cornerRadius = r)
                    if (fill > 0f) drawRoundRect(accent, size = Size(size.width * fill, size.height), cornerRadius = r)
                }
            }
        }
    }
}

private const val RING_START = 135f
private const val RING_SWEEP = 270f
private const val RING_TICKS = 6
private val DOT = 6.dp
private val DOT_BAR = 18.dp
private const val DOT_MS = 200
private const val SEGMENT_MS = 200
