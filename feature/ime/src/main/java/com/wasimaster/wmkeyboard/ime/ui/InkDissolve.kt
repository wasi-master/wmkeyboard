package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.wasimaster.wmkeyboard.core.settings.InkFade
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.ime.HandwritingUi

/** How long recognised ink takes to dissolve, and how far it draws in as it goes. */
private const val InkDissolveMs = 200
private const val InkShrink = 0.12f
private const val InkRise = 0.25f

/**
 * The ink a recognition just took, dissolving where it was written: it fades
 * and draws in on itself as the word lands in the text, so the hand-off from
 * canvas to field reads as one movement instead of the ink blinking out.
 *
 * The service clears the strokes in the same update that commits the word,
 * so the hand-off is spotted here, in composition, as ink that was being
 * recognised turning into no ink at all. An undo or a cleared canvas leaves
 * no such trace and still vanishes at once. Spotted in composition, not in an
 * effect, so the frame that loses the strokes already draws them dissolving.
 */
@Stable
internal class InkDissolve {
    private var seenStrokes: List<HwStroke> = emptyList()
    private var seenRecognizing = false

    /** Bumped for every dissolve; the effect that runs it is keyed on it. */
    var generation = 0
        private set

    var ink: List<HwStroke> = emptyList()
        private set

    /** The middle of the ink, which it draws in towards. */
    var pivot: Offset = Offset.Zero
        private set

    /** 0 just recognised, 1 gone; null with nothing dissolving. */
    var progress: Animatable<Float, AnimationVector1D>? = null
        private set

    fun observe(strokes: List<HwStroke>, recognizing: Boolean, motion: Boolean) {
        if (strokes === seenStrokes && recognizing == seenRecognizing) return
        if (motion && strokes.isEmpty() && seenStrokes.isNotEmpty() && seenRecognizing) {
            ink = seenStrokes
            pivot = middleOf(seenStrokes)
            progress = Animatable(0f)
            generation++
        }
        seenStrokes = strokes
        seenRecognizing = recognizing
    }

    fun settle(done: Animatable<Float, AnimationVector1D>) {
        if (progress !== done) return
        progress = null
        ink = emptyList()
    }

    private fun middleOf(strokes: List<HwStroke>): Offset {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (stroke in strokes) {
            for (point in stroke.points) {
                if (point.x < left) left = point.x
                if (point.x > right) right = point.x
                if (point.y < top) top = point.y
                if (point.y > bottom) bottom = point.y
            }
        }
        return if (left > right) Offset.Zero else Offset((left + right) / 2f, (top + bottom) / 2f)
    }
}

/** The dissolve for [hw]'s canvas; none under reduce motion or power saving. */
@Composable
internal fun rememberInkDissolve(hw: HandwritingUi): InkDissolve {
    val motion = LocalKbTheme.current.motion.any
    val dissolve = remember { InkDissolve() }
    dissolve.observe(hw.strokes, hw.recognizing, motion)
    LaunchedEffect(dissolve.generation) {
        val progress = dissolve.progress ?: return@LaunchedEffect
        progress.animateTo(1f, tween(InkDissolveMs, easing = FastOutSlowInEasing))
        dissolve.settle(progress)
    }
    return dissolve
}

/**
 * Draws [dissolve]'s ink at its point in the dissolve. [color] is the ink as
 * it last stood, dimmed for recognition, so the fade carries on from there.
 */
internal fun DrawScope.drawDissolvingInk(
    dissolve: InkDissolve,
    color: Color,
    style: Stroke,
    fade: InkFade = InkFade.DISSOLVE,
) {
    val progress = dissolve.progress ?: return
    val p = progress.value
    if (p >= 1f || fade == InkFade.NONE) return
    val ink = if (fade == InkFade.SHRINK) color else color.copy(alpha = color.alpha * (1f - p))
    val shrink = when (fade) {
        InkFade.DISSOLVE -> InkShrink
        InkFade.SHRINK -> 1f
        else -> 0f
    }
    val rise = if (fade == InkFade.RISE) -size.height * InkRise * p else 0f
    translate(top = rise) {
        scale(1f - shrink * p, dissolve.pivot) {
            for (stroke in dissolve.ink) drawPath(pathOf(stroke.points), color = ink, style = style)
        }
    }
}
