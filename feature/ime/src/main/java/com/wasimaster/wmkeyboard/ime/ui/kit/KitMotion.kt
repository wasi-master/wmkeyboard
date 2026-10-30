package com.wasimaster.wmkeyboard.ime.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/*
 * The motion the revamped tool panels share: one spring vocabulary, a
 * staggered pop-in for things that arrive together, a squash while pressed,
 * and a number that glides to its value. Every piece takes its gate as a
 * parameter and, with it off, draws the end state at once — no animation, no
 * delay — so callers pass `LocalKbTheme.current.motion.any` (or `.panels`)
 * and nothing else. Springs rather than tweens where something moves: a
 * spring retargets from its current speed when the value changes under it,
 * so a tap mid-flight never snaps.
 */

/** Settles without overshoot: fills, progress, things sliding into place. */
internal fun <T> kitSpringGentle(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Overshoots a little on the way in: things that arrive. */
internal fun <T> kitSpringBouncy(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

/** A beat between neighbours that pop in together. */
internal const val KIT_STAGGER_MS = 45L

/**
 * Grows in from 0.85 and clear, [index] beats after the first of its row.
 * Plays again when [key] changes. With [animate] off the content is drawn
 * straight, no layer at all.
 */
@Composable
internal fun Modifier.popIn(index: Int, animate: Boolean, key: Any? = Unit): Modifier {
    if (!animate) return this
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(index * KIT_STAGGER_MS)
        appear.animateTo(1f, kitSpringBouncy())
    }
    return graphicsLayer {
        val s = 0.85f + 0.15f * appear.value
        scaleX = s
        scaleY = s
        alpha = appear.value.coerceIn(0f, 1f)
    }
}

/**
 * Squashes to 0.96 while [source] is pressed, back on a spring. Pair with a
 * clickable that uses the same [source].
 */
@Composable
internal fun Modifier.pressScale(source: InteractionSource, animate: Boolean): Modifier {
    if (!animate) return this
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, kitSpringGentle(), label = "kit-press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * [value] as a state that glides to each new value over [ms], or snaps with
 * [animate] off. Read it inside a draw or in its own small scope, so a frame
 * of the glide redraws that and not the caller.
 */
@Composable
internal fun animateGlide(value: Float, animate: Boolean, ms: Int = GLIDE_MS): State<Float> =
    animateFloatAsState(value, if (animate) tween(ms) else snap(), label = "kit-glide")

private const val PRESS_SCALE = 0.96f

/** Between two reports 200 ms apart, so a live number never snaps. */
internal const val GLIDE_MS = 220
