package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import com.wasimaster.wmkeyboard.core.settings.MotionBudget
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_DECAY_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_RADIUS_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_SCALE_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_SPEED_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_WOBBLE_RANGE
import com.wasimaster.wmkeyboard.core.theme.FontMotionSpec
import com.wasimaster.wmkeyboard.core.theme.FontMotionTarget
import com.wasimaster.wmkeyboard.core.theme.FontMotionTrigger
import com.wasimaster.wmkeyboard.core.theme.KeyLightingPattern
import com.wasimaster.wmkeyboard.core.theme.MAX_FONT_MOTIONS
import com.wasimaster.wmkeyboard.core.theme.fontMotionTargetOrDefault
import com.wasimaster.wmkeyboard.core.theme.fontMotionTriggerOrNull
import com.wasimaster.wmkeyboard.core.theme.keyLightingPatternOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---- the resolved spec ------------------------------------------------------

/** One [FontMotionSpec] with its trigger known and its ranges enforced. */
@Immutable
data class KbFontMotionItem(
    val trigger: FontMotionTrigger,
    val pattern: KeyLightingPattern,
    val axes: Map<String, Float>,
    val allKeys: Boolean,
    val speed: Float,
    val radiusDp: Float,
    val decayMs: Int,
    val wobble: Float,
    /** The label's size at the peak, 1 for unchanged. */
    val scale: Float = 1f,
)

/** A theme's font motions, ready to drive; see [ThemeSpec.fontMotions]. */
@Immutable
data class KbFontMotion(val items: List<KbFontMotionItem>) {
    /** Whether a finger has to be followed: touch and glide motions. */
    val followsFingers: Boolean
        get() = items.any { it.trigger == FontMotionTrigger.TOUCH || it.trigger == FontMotionTrigger.GLIDE }

    /** Whether any motion changes the labels' size, which needs no variable font. */
    val scales: Boolean
        get() = items.any { it.scale != 1f }

    /** Whether the press path has to feed [ThemePulse]: reactive patterns and typing speed. */
    val readsPresses: Boolean
        get() = items.any {
            it.trigger == FontMotionTrigger.ENERGY || (it.trigger == FontMotionTrigger.PATTERN && it.pattern.reactive)
        }
}

/** [specs] ready to drive, or null when none of them can move anything. */
internal fun resolveFontMotion(specs: List<FontMotionSpec>): KbFontMotion? {
    val items = specs.take(MAX_FONT_MOTIONS).mapNotNull { spec ->
        val trigger = fontMotionTriggerOrNull(spec.trigger) ?: return@mapNotNull null
        val axes = spec.axes.filterValues { it.isFinite() }
        val scale = spec.scale.finite(1f).coerceIn(FONT_MOTION_SCALE_RANGE)
        if (axes.isEmpty() && scale == 1f) return@mapNotNull null
        KbFontMotionItem(
            trigger = trigger,
            pattern = keyLightingPatternOrNull(spec.pattern) ?: KeyLightingPattern.WAVE,
            axes = axes,
            allKeys = fontMotionTargetOrDefault(spec.target) == FontMotionTarget.ALL,
            speed = spec.speed.finite(1f).coerceIn(FONT_MOTION_SPEED_RANGE),
            radiusDp = spec.radiusDp.finite(96f).coerceIn(FONT_MOTION_RADIUS_RANGE),
            decayMs = spec.decayMs.coerceIn(FONT_MOTION_DECAY_RANGE),
            wobble = spec.wobble.finite(0f).coerceIn(FONT_MOTION_WOBBLE_RANGE),
            scale = scale,
        )
    }
    return items.takeIf { it.isNotEmpty() }?.let(::KbFontMotion)
}

private fun Float.finite(fallback: Float): Float = if (isFinite()) this else fallback

// ---- levels -----------------------------------------------------------------

/**
 * Where each axis of a label draws: [rest] moved toward each motion's peak by
 * that motion's level, the furthest move winning where two motions drive one
 * axis, held to the font. Pure, so the tests can pin it.
 */
internal fun morphedAxes(
    rest: Map<String, Float>,
    variations: FontVariations,
    moves: List<Pair<Map<String, Float>, Float>>,
): Map<String, Float> {
    val delta = HashMap<String, Float>()
    for ((peaks, level) in moves) {
        if (abs(level) < LEVEL_EPSILON) continue
        for ((tag, peak) in peaks) {
            val axis = variations.axis(tag) ?: continue
            val from = rest[tag] ?: variations.restValue(tag, LABEL_WEIGHT) ?: axis.default
            val d = (axis.clamp(peak) - from) * level
            if (abs(d) > abs(delta[tag] ?: 0f)) delta[tag] = d
        }
    }
    if (delta.isEmpty()) return rest
    val out = HashMap(rest)
    for ((tag, d) in delta) {
        val axis = variations.axis(tag) ?: continue
        val from = rest[tag] ?: variations.restValue(tag, LABEL_WEIGHT) ?: axis.default
        out[tag] = axis.clamp(from + d)
    }
    return out
}

/**
 * [axes] snapped to [steps] steps across each axis's range, so a motion asks
 * for a bounded set of cuts of the font (each is a typeface, and a glyph cache
 * of its own) rather than a new one every frame.
 */
internal fun quantizeAxes(axes: Map<String, Float>, variations: FontVariations, steps: Int): Map<String, Float> {
    val out = HashMap<String, Float>(axes.size)
    for ((tag, value) in axes) {
        val axis = variations.axis(tag)
        if (axis == null || axis.max <= axis.min || steps <= 0) {
            out[tag] = value
            continue
        }
        val span = axis.max - axis.min
        val k = ((value - axis.min) / span * steps).roundToInt().coerceIn(0, steps)
        out[tag] = axis.min + span * k / steps
    }
    return out
}

/** How the letters near a pressing finger swell: rising fast while it is down, settling back after it lifts. */
internal fun swellEnvelope(sinceDownMs: Long, heldMs: Long, decayMs: Int, wobble: Float): Float {
    val rise = { ms: Long -> 1f - exp(-ms / SWELL_RISE_MS) }
    if (heldMs == STILL_DOWN || sinceDownMs < heldMs) return rise(sinceDownMs)
    val atLift = rise(heldMs)
    val sinceLift = (sinceDownMs - heldMs).toFloat()
    if (sinceLift > decayMs * 2f) return 0f
    // A damped spring from where it got to back to rest, its period set by the
    // settle time, ringing past rest by the bounce.
    val zeta = dampingFor(wobble)
    val omega = 4.6f / (zeta * decayMs / 1000f)
    return atLift * (1f - springStep(sinceLift / 1000f, omega, zeta))
}

/** Short enough that a quick tap, down for 70 ms or so, reaches nearly the whole swell. */
private const val SWELL_RISE_MS = 30f

/** The weight key labels are asked for (Medium), which an untuned weight axis rests at. */
private const val LABEL_WEIGHT = 500
private const val LEVEL_EPSILON = 0.01f

// ---- fingers ----------------------------------------------------------------

/**
 * The fingers the font motion answers, fed alongside [TouchBloom] from the key
 * grid's pointer pass. Kept apart from it because a glide needs where the
 * finger has been, not only where it is: the letters it passed settle back
 * behind it. One per process, like [TouchBloom]; root space; written and read
 * on the main thread.
 */
internal object FontMotionFingers {

    private const val SLOTS = 5
    private const val TRAIL = 24
    private const val TRAIL_GAP_MS = 16L

    private val ids = LongArray(SLOTS) { -1L }
    private val xs = FloatArray(SLOTS)
    private val ys = FloatArray(SLOTS)
    private val downXs = FloatArray(SLOTS)
    private val downYs = FloatArray(SLOTS)
    private val downAt = LongArray(SLOTS)
    private val upAt = LongArray(SLOTS)
    private val travelled = FloatArray(SLOTS)
    private val trailX = Array(SLOTS) { FloatArray(TRAIL) }
    private val trailY = Array(SLOTS) { FloatArray(TRAIL) }
    private val trailT = Array(SLOTS) { LongArray(TRAIL) }
    private val trailHead = IntArray(SLOTS)

    /** Bumped on every down and lift: what wakes the motion's clock. */
    var revision by mutableIntStateOf(0)
        private set

    /** The newest event's time, for the clock to know when everything has settled. */
    var lastEventAt = 0L
        private set

    fun down(id: Long, x: Float, y: Float, now: Long) {
        var slot = 0
        var oldest = Long.MAX_VALUE
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L || upAt[i] != 0L && now - upAt[i] > MAX_SETTLE_MS * 2) {
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
        downXs[slot] = x
        downYs[slot] = y
        downAt[slot] = now
        upAt[slot] = 0L
        travelled[slot] = 0f
        trailT[slot].fill(0L)
        trailHead[slot] = 0
        lastEventAt = now
        revision++
    }

    fun move(id: Long, x: Float, y: Float, now: Long) {
        val slot = held(id)
        if (slot < 0) return
        travelled[slot] = max(travelled[slot], hypot(x - downXs[slot], y - downYs[slot]))
        xs[slot] = x
        ys[slot] = y
        val head = trailHead[slot]
        val last = (head - 1 + TRAIL) % TRAIL
        if (now - trailT[slot][last] >= TRAIL_GAP_MS) {
            trailX[slot][head] = x
            trailY[slot][head] = y
            trailT[slot][head] = now
            trailHead[slot] = (head + 1) % TRAIL
        }
        lastEventAt = now
    }

    fun up(id: Long, now: Long) {
        val slot = held(id)
        if (slot < 0) return
        upAt[slot] = now.coerceAtLeast(downAt[slot] + 1)
        lastEventAt = now
        revision++
    }

    fun liftAll(now: Long) {
        var any = false
        for (i in 0 until SLOTS) {
            if (ids[i] != -1L && upAt[i] == 0L) {
                upAt[i] = now.coerceAtLeast(downAt[i] + 1)
                any = true
            }
        }
        if (any) {
            lastEventAt = now
            revision++
        }
    }

    /** Whether any finger is still down. */
    fun anyDown(): Boolean {
        for (i in 0 until SLOTS) if (ids[i] != -1L && upAt[i] == 0L) return true
        return false
    }

    /**
     * The swell at ([cx], [cy]) from fingers that tap rather than glide: the
     * nearest pressing finger within [radiusPx], strongest at its centre.
     */
    fun touchLevel(cx: Float, cy: Float, radiusPx: Float, glideFromPx: Float, decayMs: Int, wobble: Float, now: Long): Float {
        var best = 0f
        val r2 = radiusPx * radiusPx
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L || travelled[i] >= glideFromPx) continue
            val d2 = sq(cx - xs[i]) + sq(cy - ys[i])
            if (d2 >= r2) continue
            val q = 1f - d2 / r2
            val held = if (upAt[i] == 0L) STILL_DOWN else upAt[i] - downAt[i]
            val v = q * q * swellEnvelope(now - downAt[i], held, decayMs, wobble)
            if (abs(v) > abs(best)) best = v
        }
        return best
    }

    /**
     * The swell at ([cx], [cy]) from a gliding finger: full where it is now,
     * fading along the path it took over [decayMs], so the letters behind it
     * settle back as it moves on. After the lift the whole trail fades.
     */
    fun glideLevel(cx: Float, cy: Float, radiusPx: Float, glideFromPx: Float, decayMs: Int, now: Long): Float {
        var best = 0f
        val r2 = radiusPx * radiusPx
        for (i in 0 until SLOTS) {
            if (ids[i] == -1L || travelled[i] < glideFromPx) continue
            if (upAt[i] == 0L) {
                val d2 = sq(cx - xs[i]) + sq(cy - ys[i])
                if (d2 < r2) best = max(best, sq(1f - d2 / r2))
            }
            for (k in 0 until TRAIL) {
                val t = trailT[i][k]
                if (t == 0L) continue
                val age = (now - t).toFloat() / decayMs
                if (age >= 1f || age < 0f) continue
                val d2 = sq(cx - trailX[i][k]) + sq(cy - trailY[i][k])
                if (d2 >= r2) continue
                best = max(best, sq(1f - d2 / r2) * (1f - age))
            }
        }
        return best
    }

    /** Whether anything a finger set going is still moving. */
    fun settled(now: Long, decayMs: Int): Boolean =
        !anyDown() && now - lastEventAt > decayMs * 2L + SETTLE_TAIL_MS

    private fun held(id: Long): Int {
        for (i in 0 until SLOTS) if (ids[i] == id && upAt[i] == 0L) return i
        return -1
    }

    private fun sq(v: Float) = v * v
    private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y)
    private const val SETTLE_TAIL_MS = 200L
}

// ---- the driver -------------------------------------------------------------

/**
 * Moves the key labels' font. Each label that can move registers a
 * [FontMorphNode]; on each tick the driver works out every label's axes from
 * the theme's motions, snaps them to a step, and redraws only the labels whose
 * step changed. Main thread only; one per process, like the keyboard.
 */
internal object FontMotionDriver {

    private val nodes = ArrayList<FontMorphNode>()

    /** The motions and the font they move; null while the theme has none. */
    var motion: KbFontMotion? = null
    var fonts: BoardFonts = BoardFonts.None

    /** The board's left edge and width in root space, for the patterns that sweep across it. */
    var boardLeft = 0f
    var boardWidth = 1f
    var density = 1f

    /** Shift, eased: 0 off, 1 on. */
    private var shiftFrom = 0f
    private var shiftTo = 0f
    private var shiftSince = 0L

    fun register(node: FontMorphNode) {
        nodes += node
    }

    fun unregister(node: FontMorphNode) {
        nodes -= node
    }

    fun setShift(on: Boolean, now: Long) {
        val target = if (on) 1f else 0f
        if (target == shiftTo) return
        shiftFrom = shiftLevel(now, wobble = 0f)
        shiftTo = target
        shiftSince = now
    }

    private fun shiftLevel(now: Long, wobble: Float): Float {
        val t = (now - shiftSince) / SHIFT_EASE_MS
        if (t >= 3f) return shiftTo
        val zeta = dampingFor(wobble)
        val eased = springStep(t * SHIFT_EASE_MS / 1000f, 4.6f / (zeta * SHIFT_EASE_MS / 1000f), zeta)
        return shiftFrom + (shiftTo - shiftFrom) * eased
    }

    fun shiftMoving(now: Long): Boolean = now - shiftSince < SHIFT_EASE_MS * 3

    /** The resolver the labels' typefaces are loaded through; set with the motion. */
    var resolver: FontFamily.Resolver? = null

    /** One paint for every moving label: they draw one after another on the main thread. */
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG)

    /**
     * One tick: every label's step worked out and the changed ones redrawn.
     * Returns whether anything is still moving, for the clock to go on.
     */
    fun tick(now: Long, budget: MotionBudget): Boolean {
        val m = motion ?: return false
        val variations = fonts.variations
        val steps = levelSteps(budget)
        val glideFrom = GLIDE_FROM_DP * density
        var live = false
        val moves = ArrayList<Pair<Map<String, Float>, Float>>(m.items.size)
        for (index in nodes.indices) {
            val node = nodes[index]
            val cell = node.cell
            if (cell.isEmpty) continue
            moves.clear()
            var grow = 0f
            for (item in m.items) {
                if (!item.allKeys && !node.letter) continue
                val level = levelOf(item, cell, index, now, glideFrom)
                if (abs(level) > LEVEL_EPSILON) live = true
                // Size and axes both move on the one grid of steps. A size that
                // followed the level every frame drew each growing letter at a
                // new pixel size every frame, and the renderer rasterizes and
                // uploads a glyph per size: that, not the labels' own drawing,
                // was what dropped frames. On the grid a label redraws only
                // when its step changes, and every size it reaches is one the
                // glyph cache has already seen. A step is under 2% of size.
                val snapped = snapLevel(level, steps)
                val g = (item.scale - 1f) * snapped
                if (abs(g) > abs(grow)) grow = g
                if (variations != null && item.axes.isNotEmpty()) moves += item.axes to snapped
            }
            val rest = node.rest(fonts)
            val step = if (variations == null || moves.isEmpty()) {
                null
            } else {
                morphedAxes(rest, variations, moves).takeIf { it != rest }
            }
            val scale = 1f + grow
            if (step != node.step || scale != node.scale) {
                node.step = step
                node.scale = scale
                node.redraw()
            }
        }
        return live || m.items.any { item -> itemRunning(item, now) }
    }

    /**
     * [level] snapped to [steps] steps between rest and peak. Snapped by level
     * rather than across each axis's whole range, so a motion over a short
     * stretch of an axis still moves in as many steps as one over all of it.
     */
    private fun snapLevel(level: Float, steps: Int): Float = (level * steps).roundToInt() / steps.toFloat()

    private fun levelSteps(budget: MotionBudget): Int = if (budget == MotionBudget.NORMAL) STEPS_NORMAL else STEPS_REDUCED

    /**
     * Every cut of the font [motion] can ask for on its own, one motion at a
     * time, from a little past rest on the far side (a spring's overshoot) to
     * a little past its peak: what [warm] builds ahead of the first press.
     * Two motions meeting on one key ask for cuts outside this list, and
     * those are built when they come.
     */
    fun warmPlan(motion: KbFontMotion, fonts: BoardFonts, budget: MotionBudget): List<Map<String, Float>> {
        val variations = fonts.variations ?: return emptyList()
        val steps = levelSteps(budget)
        val over = steps / WARM_OVERSHOOT_DIVISOR
        val rest = fonts.keyAxes
        val out = LinkedHashSet<Map<String, Float>>()
        for (item in motion.items) {
            if (item.axes.isEmpty()) continue
            for (k in -over..steps + over) {
                val morphed = morphedAxes(rest, variations, listOf(item.axes to k / steps.toFloat()))
                if (morphed != rest) out += morphed
            }
        }
        return out.toList()
    }

    /** Where the labels rest, for the ones whose own axes differ from the board's. */
    fun restSettings(): List<Map<String, Float>> = nodes.mapTo(LinkedHashSet()) { it.rest(fonts) }.toList()

    /** The weights the moving labels draw in, Medium and Normal before any has been laid out. */
    fun labelWeights(): List<FontWeight> {
        val seen = nodes.mapTo(LinkedHashSet()) { it.style.fontWeight ?: FontWeight.Normal }
        return if (seen.isEmpty()) listOf(FontWeight.Medium, FontWeight.Normal) else seen.toList()
    }

    /** Puts every label back to rest, as the motion goes away. */
    fun rest() {
        for (node in nodes) {
            if (node.step != null || node.scale != 1f) {
                node.step = null
                node.scale = 1f
                node.redraw()
            }
        }
    }

    private fun levelOf(item: KbFontMotionItem, cell: Rect, index: Int, now: Long, glideFrom: Float): Float {
        val c = cell.center
        return when (item.trigger) {
            FontMotionTrigger.PATTERN -> if (item.pattern.reactive) {
                var best = 0f
                val keySpan = max(cell.width, cell.height)
                ThemePulse.forEachRecent(now, item.decayMs.toLong() + REACTIVE_TAIL_MS) { px, py, ageMs ->
                    val hit = pressLightingLevel(item.pattern, item.speed, cell, Offset(px, py), ageMs / item.decayMs.toFloat(), keySpan)
                    if (hit > best) best = hit
                }
                best
            } else {
                val u = ((c.x - boardLeft) / boardWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
                idleLightingLevel(item.pattern, now / 1000f * item.speed, u, index)
            }
            FontMotionTrigger.TOUCH -> FontMotionFingers.touchLevel(
                c.x, c.y, item.radiusDp * density, glideFrom, item.decayMs, item.wobble, now,
            )
            FontMotionTrigger.GLIDE -> FontMotionFingers.glideLevel(
                c.x, c.y, item.radiusDp * density, glideFrom, item.decayMs, now,
            )
            FontMotionTrigger.SHIFT -> shiftLevel(now, item.wobble)
            FontMotionTrigger.ENERGY -> ThemePulse.energy(now, item.decayMs)
        }
    }

    /** Whether [item] still has frames to draw even where no label is off rest right now. */
    private fun itemRunning(item: KbFontMotionItem, now: Long): Boolean = when (item.trigger) {
        FontMotionTrigger.PATTERN -> !item.pattern.reactive ||
            now - ThemePulse.lastPressAt < item.decayMs + REACTIVE_TAIL_MS
        FontMotionTrigger.TOUCH, FontMotionTrigger.GLIDE -> !FontMotionFingers.settled(now, item.decayMs)
        FontMotionTrigger.SHIFT -> shiftMoving(now)
        FontMotionTrigger.ENERGY -> ThemePulse.energy(now, item.decayMs) > LEVEL_EPSILON
    }

    /** Steps between rest and peak: each a cut of the font, all made ahead of the first press. */
    private const val STEPS_NORMAL = 24
    private const val STEPS_REDUCED = 12
    private const val SHIFT_EASE_MS = 180f
    /** How far a finger has to travel before it counts as gliding, in dp. */
    private const val GLIDE_FROM_DP = 18f
    private const val REACTIVE_TAIL_MS = 600L
    /** A third of the steps again past each end, for the springs that ring past rest and peak. */
    private const val WARM_OVERSHOOT_DIVISOR = 3
}

/**
 * Runs the font motion's clock for a key grid while the theme has a motion
 * and the font has axes (or a motion grows the labels): a frame loop, paced
 * by [MotionBudget.fontMotionFps], every frame on a normal budget,
 * that sleeps whenever nothing moves and wakes on a press, a finger or Shift.
 */
@Composable
internal fun FontMotionLayer(motion: KbFontMotion, fonts: BoardFonts, shifted: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val now = SystemClock.uptimeMillis()
    // Set in composition, read by the loop: plain fields on a main-thread object.
    FontMotionDriver.motion = motion
    FontMotionDriver.fonts = fonts
    FontMotionDriver.density = density
    FontMotionDriver.setShift(shifted, now)
    val pulse = if (motion.readsPresses) ThemePulse.revision else 0
    val fingers = if (motion.followsFingers) FontMotionFingers.revision else 0
    var wake by remember { mutableStateOf(0) }
    LaunchedEffect(motion, fonts, pulse, fingers, shifted, wake) {
        while (true) {
            val t = withFrameMillis { SystemClock.uptimeMillis() }
            if (!FontMotionDriver.tick(t, ThemeFramePacing.budget)) break
            paceFontMotionFrame()
        }
    }
    // Cuts the font at every step the motion will ask for, and loads each
    // one's typeface, off the main thread as the theme arrives. Without it the
    // first sweep of a motion builds a cut per step inside the draw pass, a
    // file opened and parsed per new step, and the first presses stutter.
    val resolver = LocalFontFamilyResolver.current
    FontMotionDriver.resolver = resolver
    LaunchedEffect(motion, fonts, resolver) {
        withFrameMillis { }
        val plan = FontMotionDriver.warmPlan(motion, fonts, ThemeFramePacing.budget)
        val weights = FontMotionDriver.labelWeights()
        val rests = FontMotionDriver.restSettings()
        withContext(Dispatchers.Default) {
            for (axes in rests + plan) {
                for (weight in weights) fonts.typefaceAt(axes, weight, resolver)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(motion) {
        onDispose { FontMotionDriver.rest() }
    }
    Box(
        modifier.onGloballyPositioned {
            val left = it.positionInRoot().x
            val width = it.size.width.toFloat()
            if (left != FontMotionDriver.boardLeft || width != FontMotionDriver.boardWidth) {
                FontMotionDriver.boardLeft = left
                FontMotionDriver.boardWidth = width
                wake++
            }
        },
    )
}

// ---- the label --------------------------------------------------------------

/**
 * A key label that the theme's font motion can move. At rest it draws the
 * label as laid out; off rest it draws the same text in the font cut at its
 * current step instead, centred on the same place and scaled down only if the
 * heavier cut would spill far past the label's own box. Read in the draw
 * phase alone, so a frame of motion repaints a label and recomposes nothing.
 *
 * [style] is the label's whole style (size, weight, colour, shadow), as the
 * label's own `Text` draws it; [own] the key's own axes over the board's.
 */
internal fun Modifier.fontMorph(text: String, style: TextStyle, letter: Boolean, own: Map<String, Float>?): Modifier =
    // A layer of its own, so a frame that redraws this label re-records this
    // label alone. Without it the redraw reached the key's whole drawing
    // (the shape, its border, its lighting), and every moving label paid
    // that each frame: 22 ms frames against 15 on a still theme.
    this.graphicsLayer() then FontMorphElement(text, style, letter, own)

private data class FontMorphElement(
    val text: String,
    val style: TextStyle,
    val letter: Boolean,
    val own: Map<String, Float>?,
) : ModifierNodeElement<FontMorphNode>() {
    override fun create() = FontMorphNode(text, style, letter, own)

    override fun update(node: FontMorphNode) {
        node.text = text
        node.style = style
        node.letter = letter
        node.own = own
        node.redraw()
    }
}

internal class FontMorphNode(
    var text: String,
    var style: TextStyle,
    var letter: Boolean,
    var own: Map<String, Float>?,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {

    /** The key's cell in root space, for the motions that ask where a key is. */
    var cell: Rect = Rect.Zero
        private set

    /** The axes to draw at; null at rest. Set by the driver. */
    var step: Map<String, Float>? = null

    /** How big to draw the label, 1 at rest. Set by the driver. */
    var scale: Float = 1f

    private var size = IntSize.Zero

    /** Where the label rests: the board's own values, with the key's laid over them. */
    fun rest(fonts: BoardFonts): Map<String, Float> {
        val mine = own ?: return fonts.keyAxes
        return fonts.keyAxes + mine
    }

    fun redraw() {
        if (isAttached) invalidateDraw()
    }

    override fun onAttach() {
        FontMotionDriver.register(this)
    }

    override fun onDetach() {
        FontMotionDriver.unregister(this)
        step = null
        scale = 1f
    }

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        // The key's face is the label's parent box; a label on its own counts its own bounds.
        cell = (coordinates.parentLayoutCoordinates ?: coordinates).boundsInRoot()
    }

    override fun ContentDrawScope.draw() {
        val at = step
        val grow = scale
        val pivot = Offset(this.size.width / 2f, this.size.height / 2f)
        val resolver = FontMotionDriver.resolver
        val typeface = if (at == null || resolver == null) {
            null
        } else {
            FontMotionDriver.fonts.typefaceAt(at, style.fontWeight ?: FontWeight.Normal, resolver)
        }
        if (typeface == null) {
            // Only the size moves, or the cut is not there: the label as laid
            // out, scaled about its centre.
            if (grow == 1f) drawContent() else scale(grow, pivot = pivot) { this@draw.drawContent() }
            return
        }
        // The cut drawn straight from its typeface: one glyph run, no text
        // layout, which a moving label would otherwise build afresh nearly
        // every frame (24 steps a motion, past any layout cache's reach).
        val paint = FontMotionDriver.paint
        paint.typeface = typeface
        paint.textSize = if (style.fontSize.isSp) style.fontSize.toPx() else paint.textSize
        paint.color = style.color.takeIf { it.isSpecified }?.toArgb() ?: android.graphics.Color.WHITE
        val shadow = style.shadow
        if (shadow != null && shadow.color.isSpecified && shadow.blurRadius > 0f) {
            paint.setShadowLayer(shadow.blurRadius, shadow.offset.x, shadow.offset.y, shadow.color.toArgb())
        } else {
            paint.clearShadowLayer()
        }
        val w = paint.measureText(text)
        val metrics = paint.fontMetrics
        // Centred the way the label's own Text sits in its box: a box one
        // line tall, the baseline the ascent down from its top.
        val baseline = this.size.height / 2f - (metrics.ascent + metrics.descent) / 2f
        // A heavier or wider cut, and a grown one, is let spill a little
        // round its box, which the key has room for; past that it is drawn
        // smaller rather than over the next key.
        val room = this.size.width * SPILL
        val drawn = if (w * grow > room && w > 0f) room / w else grow
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.scale(drawn, drawn, pivot.x, pivot.y)
        canvas.drawText(text, pivot.x - w / 2f, baseline, paint)
        canvas.restore()
    }

    private companion object {
        const val SPILL = 2.2f
    }
}
