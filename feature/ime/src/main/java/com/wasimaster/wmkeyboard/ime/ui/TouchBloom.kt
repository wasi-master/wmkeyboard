package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.wasimaster.wmkeyboard.core.theme.KeyPressMotion
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fingers the theme's touch bloom answers: where each one is, when it went
 * down and when it lifted. The key grid writes it from its own pointer pass
 * (taps and glides alike, so the light and the dip follow a gliding finger);
 * the bloom layers and every key's dip read it.
 *
 * One per process, like [ThemePulse]. Positions are in root space. A move is
 * two float writes; a down or a lift bumps [revision], which is what wakes the
 * frame loop ([TouchBloomClock]) that went to sleep when the last key settled.
 * Everything that draws reads [frame], and only in a draw or layer block, so a
 * frame of the bloom repaints and re-transforms and never recomposes a key.
 */
internal object TouchBloom {

    private const val SLOTS = 5

    private val ids = LongArray(SLOTS) { -1L }
    private val xs = FloatArray(SLOTS)
    private val ys = FloatArray(SLOTS)
    private val downAt = LongArray(SLOTS)
    /** 0 while the finger is still down. */
    private val upAt = LongArray(SLOTS)

    /** Bumped on every down and lift. */
    var revision by mutableIntStateOf(0)
        private set

    /** The clock the drawing reads: the frame time while anything moves, frozen once it has settled. */
    var frame by mutableLongStateOf(0L)
        internal set

    fun down(id: Long, x: Float, y: Float, now: Long) {
        // A free slot, else one that has settled, else the oldest touch.
        var slot = -1
        var oldest = Long.MAX_VALUE
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L || (upAt[i] != 0L && now - upAt[i] > MAX_SETTLE_MS)) {
                slot = i
                break
            }
            if (downAt[i] < oldest) {
                oldest = downAt[i]
                slot = i
            }
        }
        ids[slot] = id
        xs[slot] = x
        ys[slot] = y
        downAt[slot] = now
        upAt[slot] = 0L
        revision++
    }

    fun move(id: Long, x: Float, y: Float) {
        val slot = held(id)
        if (slot < 0) return
        xs[slot] = x
        ys[slot] = y
    }

    fun up(id: Long, now: Long) {
        val slot = held(id)
        if (slot < 0) return
        upAt[slot] = now.coerceAtLeast(downAt[slot] + 1)
        revision++
    }

    /** Lifts every finger still down: the grid lost its pointers without seeing them lift. */
    fun liftAll(now: Long) {
        var any = false
        for (i in 0 until SLOTS) {
            if (ids[i] != -1L && upAt[i] == 0L) {
                upAt[i] = now.coerceAtLeast(downAt[i] + 1)
                any = true
            }
        }
        if (any) revision++
    }

    private fun held(id: Long): Int {
        for (i in 0 until SLOTS) if (ids[i] == id && upAt[i] == 0L) return i
        return -1
    }

    /** Whether nothing is left moving: every finger up and its spring and light spent. */
    fun settled(now: Long, wobble: Float): Boolean {
        val settle = settleMs(wobble)
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L) continue
            if (upAt[i] == 0L) return false
            if (now - upAt[i] < settle) return false
        }
        return true
    }

    /**
     * How far a key centred at ([cx], [cy]) in root space is pushed in: 1 is a
     * full dip, below 0 is the overshoot past rest as it springs back. The
     * deepest touch wins, so two fingers do not dig twice as deep.
     */
    fun dipAt(cx: Float, cy: Float, radiusPx: Float, wobble: Float, now: Long): Float {
        val r2 = radiusPx * radiusPx
        var best = 0f
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L) continue
            val dx = cx - xs[i]
            val dy = cy - ys[i]
            val d2 = dx * dx + dy * dy
            if (d2 >= r2) continue
            val q = 1f - d2 / r2
            val held = if (upAt[i] == 0L) STILL_DOWN else upAt[i] - downAt[i]
            val v = q * q * dipEnvelope(now - downAt[i], held, wobble)
            if (abs(v) > abs(best)) best = v
        }
        return best
    }

    /**
     * Which side of its key the touch that last answered [pressIn] landed on,
     * -1 (left) to 1 (right). Read straight after the call; the draw that
     * reads it runs on the same thread.
     */
    var pressSide = 0f
        private set

    /**
     * How far in the key whose root rect is [bounds] is pushed by a finger on
     * it, as [dipAt] measures it but with no falloff and nothing for a finger
     * on another key: the key-only motions move the pressed key and no other.
     * A finger that has lifted counts where it lifted, so the key springs back
     * on its own.
     */
    fun pressIn(bounds: Rect, wobble: Float, now: Long): Float {
        var best = 0f
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L) continue
            val x = xs[i]
            val y = ys[i]
            if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) continue
            val held = if (upAt[i] == 0L) STILL_DOWN else upAt[i] - downAt[i]
            val v = dipEnvelope(now - downAt[i], held, wobble)
            if (abs(v) > abs(best)) {
                best = v
                val half = bounds.width / 2f
                pressSide = if (half > 0f) ((x - bounds.center.x) / half).coerceIn(-1f, 1f) else 0f
            }
        }
        return best
    }

    /** Calls [each] with every touch whose light has not faded: its root position and its light, 0..1. */
    inline fun forEachLit(now: Long, each: (x: Float, y: Float, light: Float) -> Unit) {
        for (i in 0 until slotCount()) {
            if (idAt(i) == -1L) continue
            val up = upTimeAt(i)
            val light = lightEnvelope(now - downTimeAt(i), if (up == 0L) STILL_DOWN else up - downTimeAt(i))
            if (light > 0.005f) each(xAt(i), yAt(i), light)
        }
    }

    @PublishedApi internal fun slotCount(): Int = SLOTS
    @PublishedApi internal fun idAt(i: Int): Long = ids[i]
    @PublishedApi internal fun xAt(i: Int): Float = xs[i]
    @PublishedApi internal fun yAt(i: Int): Float = ys[i]
    @PublishedApi internal fun downTimeAt(i: Int): Long = downAt[i]
    @PublishedApi internal fun upTimeAt(i: Int): Long = upAt[i]
}

// ---- the spring -------------------------------------------------------------
//
// Closed-form rather than integrated: a key's dip is a pure function of how
// long ago the finger went down and lifted, so forty keys read the same clock
// with no per-key state and nothing to carry across a frame or a theme change.

/** The held time of a touch whose finger is still down. A sentinel, not a null: this runs per key per frame. */
internal const val STILL_DOWN = -1L

/** How fast a key sinks under the finger, rad/s (about 6 Hz). */
private const val PRESS_OMEGA = 38f

/** How fast it springs back, rad/s (about 3.2 Hz): slow enough to see the wobble. */
private const val RETURN_OMEGA = 20f

/** How long the light takes to fade once the finger lifts. */
internal const val LIGHT_FADE_MS = 450L

/** The longest any spring rings, at full wobble; past it a lifted touch is spent. */
internal const val MAX_SETTLE_MS = 1400L

/** Damping ratio for [wobble] 0..1: critically damped at 0, a few visible swings at 1. */
internal fun dampingFor(wobble: Float): Float = 1f - 0.78f * wobble.coerceIn(0f, 1f)

/**
 * The step response of a spring with natural frequency [omega] and damping
 * [zeta] at [tSec]: 0 at rest, settling to 1, overshooting it when [zeta] < 1.
 */
internal fun springStep(tSec: Float, omega: Float, zeta: Float): Float {
    if (tSec <= 0f) return 0f
    if (zeta >= 0.999f) {
        val wt = omega * tSec
        return 1f - exp(-wt) * (1f + wt)
    }
    val wd = omega * sqrt(1f - zeta * zeta)
    val decay = exp(-zeta * omega * tSec)
    return 1f - decay * (cos(wd * tSec) + zeta * omega / wd * sin(wd * tSec))
}

/**
 * How far in a key is [sinceDownMs] after the finger went down, at full reach:
 * it sinks while the finger is down ([heldMs] is [STILL_DOWN]) and, [heldMs] after the press, springs back
 * from wherever it had got to, past rest and back by [wobble].
 */
internal fun dipEnvelope(sinceDownMs: Long, heldMs: Long, wobble: Float): Float {
    val zeta = dampingFor(wobble)
    // The press is firmer than the release: a finger landing should not bounce.
    val pressZeta = (zeta + 0.3f).coerceAtMost(1f)
    if (heldMs == STILL_DOWN || sinceDownMs < heldMs) return springStep(sinceDownMs / 1000f, PRESS_OMEGA, pressZeta)
    val atLift = springStep(heldMs / 1000f, PRESS_OMEGA, pressZeta)
    val sinceLift = (sinceDownMs - heldMs) / 1000f
    if (sinceLift * 1000f > MAX_SETTLE_MS) return 0f
    return atLift * (1f - springStep(sinceLift, RETURN_OMEGA, zeta))
}

/** How lit a touch is, 0..1: quick to come up, fading over [LIGHT_FADE_MS] once lifted. */
internal fun lightEnvelope(sinceDownMs: Long, heldMs: Long): Float {
    if (heldMs == STILL_DOWN || sinceDownMs < heldMs) return lightRise(sinceDownMs)
    val fade = 1f - (sinceDownMs - heldMs).toFloat() / LIGHT_FADE_MS
    if (fade <= 0f) return 0f
    return lightRise(heldMs) * fade * fade
}

private fun lightRise(ms: Long): Float = 1f - exp(-ms / 70f)

/** How long a lifted touch keeps anything moving at [wobble]. */
internal fun settleMs(wobble: Float): Long {
    // The envelope of a damped spring falls under 1% after ln(100) / (zeta * omega).
    val spring = (4.6f / (dampingFor(wobble) * RETURN_OMEGA) * 1000f).toLong()
    return max(LIGHT_FADE_MS, spring).coerceAtMost(MAX_SETTLE_MS)
}

// ---- drawing ----------------------------------------------------------------

/** How much smaller a key at full dip is. */
private const val DIP_SCALE = 0.08f

/** How far down a key at full dip moves, in dp. */
private const val DIP_DROP_DP = 1.5f

/**
 * Runs the bloom's frame clock: asleep until a finger goes down, then a frame
 * at a time until every touch has settled. Composed once per board while the
 * theme has a touch bloom.
 */
@Composable
internal fun TouchBloomClock(bloom: KbTouchBloom) {
    LaunchedEffect(bloom.wobble) {
        snapshotFlow { TouchBloom.revision }.collectLatest {
            while (true) {
                val now = withFrameMillis { SystemClock.uptimeMillis() }
                TouchBloom.frame = now
                if (TouchBloom.settled(now, bloom.wobble)) break
            }
        }
    }
}

/**
 * The light from the finger. Two are composed around the key grid: [behind]
 * the keys, the full bloom across the board, showing in the gaps and through
 * see-through keys; and over them a fainter wash, so the light still reaches a
 * theme whose keys are solid.
 */
@Composable
internal fun TouchBloomLayer(bloom: KbTouchBloom, behind: Boolean, modifier: Modifier = Modifier) {
    if (bloom.light <= 0f) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawWithCache {
                val radius = bloom.radiusDp * density * (if (behind) 1f else 0.6f)
                // Centred on zero and moved into place per touch, so the shader
                // is built once per size and colour rather than once per frame.
                val brush = Brush.radialGradient(
                    0f to bloom.color,
                    0.3f to bloom.color.copy(alpha = bloom.color.alpha * 0.5f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = radius,
                )
                val strength = bloom.light * (if (behind) 1f else 0.35f)
                onDrawBehind {
                    val now = TouchBloom.frame
                    val at = origin
                    TouchBloom.forEachLit(now) { x, y, light ->
                        // The light swells outwards as it comes up.
                        val grow = 0.7f + 0.3f * light
                        translate(x - at.x, y - at.y) {
                            scale(grow, grow, pivot = Offset.Zero) {
                                drawCircle(
                                    brush, radius, Offset.Zero, alpha = (strength * light).coerceIn(0f, 1f),
                                    blendMode = if (behind) BlendMode.SrcOver else BlendMode.Screen,
                                )
                            }
                        }
                    }
                }
            },
    )
}

/**
 * A key's dip: smaller and a little lower the nearer the finger is, springing
 * back when it lifts. A layer of its own, its transform set in the layer block,
 * so the bloom moving re-transforms the key and never redraws or recomposes it.
 * [bounds] is the key's root rect, read live.
 */
internal fun Modifier.touchDip(bloom: KbTouchBloom, bounds: () -> Rect): Modifier = graphicsLayer {
    applyDip(bloom, bounds())
}

private fun GraphicsLayerScope.applyDip(bloom: KbTouchBloom, bounds: Rect) {
    val now = TouchBloom.frame
    if (bounds.isEmpty) return
    if (bloom.motion != KeyPressMotion.SINK) {
        applyKeyMotion(bloom, bounds, now)
        return
    }
    val c = bounds.center
    val d = TouchBloom.dipAt(c.x, c.y, bloom.radiusDp * density, bloom.wobble, now) * bloom.dip
    if (d == 0f) return
    pressMotion(KeyPressMotion.SINK, d, 0f)
}

/**
 * The key-only motions: the key under the finger grows, shrinks, rises, tips
 * or squashes, by [KbTouchBloom.dip] at full press, and rings back past rest
 * by the wobble when the finger lifts (the envelope goes below zero there, so
 * a grown key briefly shrinks, a risen key briefly sinks).
 */
private fun GraphicsLayerScope.applyKeyMotion(bloom: KbTouchBloom, bounds: Rect, now: Long) {
    val d = TouchBloom.pressIn(bounds, bloom.wobble, now) * bloom.dip
    if (d == 0f) return
    pressMotion(bloom.motion, d, TouchBloom.pressSide)
}

/**
 * A key [d] of the way into [motion] (below zero on the spring back), [side]
 * being where the finger landed across it, -1 to 1. Shared with the editor's
 * preview tile, which plays a press by hand.
 */
internal fun GraphicsLayerScope.pressMotion(motion: KeyPressMotion, d: Float, side: Float) {
    when (motion) {
        KeyPressMotion.SINK -> {
            scaleX = 1f - DIP_SCALE * d
            scaleY = 1f - DIP_SCALE * d
            translationY = DIP_DROP_DP * density * d
        }
        KeyPressMotion.GROW -> {
            scaleX = 1f + GROW_SCALE * d
            scaleY = 1f + GROW_SCALE * d
        }
        KeyPressMotion.SHRINK -> {
            scaleX = 1f - SHRINK_SCALE * d
            scaleY = 1f - SHRINK_SCALE * d
        }
        KeyPressMotion.RISE -> {
            translationY = -RISE_DP * density * d
            scaleX = 1f + RISE_SCALE * d
            scaleY = 1f + RISE_SCALE * d
        }
        KeyPressMotion.TILT -> {
            // Toward the side the finger landed on; a hit dead centre still tips a little.
            rotationZ = TILT_DEG * d * (if (side < 0f) -1f else 1f) * (0.6f + 0.4f * abs(side))
        }
        KeyPressMotion.JELLY -> {
            scaleX = 1f + JELLY_SCALE * d
            scaleY = 1f - JELLY_SCALE * d
            // Squashed toward the board rather than about its middle.
            transformOrigin = JellyOrigin
        }
        // The next three are Expressive Keyboard's press animations, as the
        // same scale, lift and fade it builds every one of its presets from.
        KeyPressMotion.POP -> {
            scaleX = 1f + POP_SCALE * d
            scaleY = 1f + POP_SCALE * d
            translationY = -POP_LIFT_DP * density * d
        }
        KeyPressMotion.SQUISH -> {
            scaleX = 1f + SQUISH_WIDE * d
            scaleY = 1f - SQUISH_FLAT * d
            translationY = SQUISH_DROP_DP * density * d
            transformOrigin = JellyOrigin
        }
        KeyPressMotion.FADE -> {
            scaleX = 1f - FADE_SCALE * d
            scaleY = 1f - FADE_SCALE * d
            // The spring back takes d below zero; a key is never more than opaque.
            alpha = (1f - FADE_ALPHA * d).coerceIn(0f, 1f)
        }
    }
}

/** A popped key grows by this much and hops this far, in dp. */
private const val POP_SCALE = 0.1f
private const val POP_LIFT_DP = 5f

/** A squished key widens and flattens by these shares and drops this far, in dp. */
private const val SQUISH_WIDE = 0.12f
private const val SQUISH_FLAT = 0.18f
private const val SQUISH_DROP_DP = 2f

/** A fading key shrinks by this much and loses this share of its opacity. */
private const val FADE_SCALE = 0.03f
private const val FADE_ALPHA = 0.5f

/** How much larger a grown key is at full press. */
private const val GROW_SCALE = 0.22f

/** How much smaller a shrunk key is at full press. */
private const val SHRINK_SCALE = 0.24f

/** How far a risen key lifts, in dp, and how much nearer it looks. */
private const val RISE_DP = 6f
private const val RISE_SCALE = 0.06f

/** How far a tipped key turns at full press, in degrees. */
private const val TILT_DEG = 9f

/** How far a jelly key squashes. */
private const val JELLY_SCALE = 0.16f

private val JellyOrigin = TransformOrigin(0.5f, 1f)

/**
 * The key preview bubble lit from below, where the finger is, in the bloom's
 * colour. Drawn over the bubble's fill and under its label.
 */
internal fun Modifier.touchBloomGlow(bloom: KbTouchBloom?): Modifier {
    if (bloom == null || bloom.light <= 0f) return this
    return drawWithCache {
        val brush = Brush.radialGradient(
            0f to bloom.color.copy(alpha = bloom.color.alpha * bloom.light * 0.55f),
            1f to Color.Transparent,
            center = Offset(size.width / 2f, size.height),
            radius = max(size.width, size.height),
        )
        onDrawBehind { drawRect(brush, blendMode = BlendMode.Screen) }
    }
}
