package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.withFrameMillis
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_EFFECT_DURATION_MS
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The theme's physics for one burst, resolved once per theme rather than per
 * press. Held as a value the spawn path reads, so pressing a key allocates
 * nothing; every field is already clamped to its range by KbTheme.
 */
internal data class EffectPhysics(
    val speed: Float = 1f,
    val spread: Float = 1f,
    val size: Float = 1f,
    val gravity: Float = 1f,
    val durationMs: Int = DEFAULT_EFFECT_DURATION_MS,
    /** Flat colour every particle tints to; null keeps the artwork's own colours. */
    val tint: ColorFilter? = null,
    /** Each particle picks its own hue, which one [tint] cannot express. */
    val randomTint: Boolean = false,
)

/**
 * The key-press particle burst, built on the glide trail's architecture: all
 * per-particle data lives in preallocated plain arrays invisible to Compose,
 * with one `revision` counter subscribed inside the draw lambda and one
 * `active` flag that composes the canvas and runs the frame loop. A spawn is
 * array writes plus those two state bumps — no composition work rides the
 * press path — and when the last particle dies the loop exits, so an idle
 * keyboard spends nothing.
 *
 * Physics is stateless: a particle stores its birth position, velocity and
 * time, and the draw computes where it is from its age. The frame loop only
 * invalidates the draw and retires the dead.
 *
 * The theme's knobs are baked into each particle at spawn — into the velocity
 * and size arrays that already existed, plus a gravity, lifetime and tint of
 * its own. That costs three arrays and buys correctness across a theme change:
 * particles already in flight keep the physics they were born with instead of
 * teleporting onto the new theme's curve mid-arc.
 */
@Stable
internal class ParticleField {

    val x = FloatArray(CAPACITY)
    val y = FloatArray(CAPACITY)
    val vx = FloatArray(CAPACITY)
    val vy = FloatArray(CAPACITY)
    val sizePx = FloatArray(CAPACITY)
    val spinDegPerS = FloatArray(CAPACITY)
    val bornAt = LongArray(CAPACITY)
    val glyphIndex = IntArray(CAPACITY)
    val gravityPxS2 = FloatArray(CAPACITY)
    val lifeMs = IntArray(CAPACITY)

    /**
     * Per-particle tint. Built at spawn, never in the draw: a `ColorFilter`
     * per particle per frame would allocate the one thing this whole design
     * exists to avoid.
     */
    val tint = arrayOfNulls<ColorFilter>(CAPACITY)

    private var head = 0

    var revision by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set

    /** The frame clock's last stamp; what the draw measures ages against. */
    var nowMs by mutableLongStateOf(0L)
        private set

    fun spawn(
        cx: Float,
        cy: Float,
        count: Int,
        glyphCount: Int,
        now: Long,
        physics: EffectPhysics = EffectPhysics(),
    ) {
        if (glyphCount <= 0) return
        // Angles fan around straight up, so a burst reads as celebration
        // rather than as rain; spread opens that cone from a jet to a spray.
        val arc = SPREAD_ARC_RAD * physics.spread
        repeat(count) {
            val i = head
            head = (head + 1) % CAPACITY
            val angle = -PI / 2 + (Random.nextFloat() - 0.5) * arc
            val speed = (SPEED_MIN_PX_S + Random.nextFloat() * SPEED_SPAN_PX_S) * physics.speed
            x[i] = cx
            y[i] = cy
            vx[i] = (cos(angle) * speed).toFloat()
            vy[i] = (sin(angle) * speed).toFloat()
            sizePx[i] = (Random.nextFloat() * 0.5f + 0.75f) * physics.size
            spinDegPerS[i] = Random.nextFloat() * 360f - 180f
            gravityPxS2[i] = GRAVITY_PX_S2 * physics.gravity
            lifeMs[i] = physics.durationMs
            tint[i] = if (physics.randomTint) randomTint() else physics.tint
            bornAt[i] = now
            glyphIndex[i] = Random.nextInt(glyphCount)
        }
        nowMs = now
        revision++
        if (!active) active = true
    }

    /** One frame: repaints, and puts the field to sleep once everything died. */
    fun frame(now: Long) {
        nowMs = now
        if (bornAt.indices.none { bornAt[it] > 0 && now - bornAt[it] < lifeMs[it] }) {
            active = false
        }
    }

    fun clear() {
        bornAt.fill(0L)
        tint.fill(null)
        active = false
    }

    companion object {
        const val CAPACITY = 48
        const val GRAVITY_PX_S2 = 1400f

        /** The default cone, in radians; the spread knob scales it. */
        const val SPREAD_ARC_RAD = 1.0

        /** Launch speed before the theme's multiplier, in pixels per second. */
        const val SPEED_MIN_PX_S = 320f
        const val SPEED_SPAN_PX_S = 280f

        /**
         * A particle's own colour under RANDOM: full value and high but not
         * total saturation, which keeps the hues bright without the neon cast
         * a fully saturated wheel gives.
         */
        private fun randomTint(): ColorFilter =
            ColorFilter.tint(Color.hsv(Random.nextFloat() * 360f, 0.85f, 1f))
    }
}

/** The grid's particle field, or null when no effect can ever spawn. */
internal val LocalParticleField = staticCompositionLocalOf<ParticleField?> { null }

/** How many particles one press throws, before the theme's intensity. */
private const val BASE_BURST = 5

/** Burst size for the active theme; 0 disables spawning entirely. */
internal fun burstCount(kb: KbTheme): Int =
    if (kb.keyEffect == null || kb.reduceMotion) {
        0
    } else {
        (BASE_BURST * kb.keyEffectIntensity).roundToInt().coerceIn(1, 12)
    }

/**
 * The theme's physics as the field wants it. Built in composition, once per
 * theme, so the press path only reads it — and the `ColorFilter` a tint needs
 * is made here rather than per particle.
 */
@Composable
internal fun rememberEffectPhysics(kb: KbTheme): EffectPhysics =
    remember(
        kb.keyEffectSpeed,
        kb.keyEffectSpread,
        kb.keyEffectSize,
        kb.keyEffectGravity,
        kb.keyEffectDurationMs,
        kb.keyEffectTint,
        kb.keyEffectRandomTint,
    ) {
        EffectPhysics(
            speed = kb.keyEffectSpeed,
            spread = kb.keyEffectSpread,
            size = kb.keyEffectSize,
            gravity = kb.keyEffectGravity,
            durationMs = kb.keyEffectDurationMs,
            tint = kb.keyEffectTint?.let { ColorFilter.tint(it) },
            randomTint = kb.keyEffectRandomTint,
        )
    }

/**
 * The glyphs a text-based effect kind throws; one particle picks one at
 * random. CUSTOM_IMAGE has no glyphs — its particle kinds are the theme's
 * own image files, loaded in [rememberEffectGlyphs].
 */
internal fun effectGlyphs(kind: KeyEffectKind, param: String): List<String> = when (kind) {
    KeyEffectKind.STARS -> listOf("⭐", "🌟", "✨")
    KeyEffectKind.HEARTS -> listOf("❤️", "💖", "💜")
    KeyEffectKind.SPARKLE -> listOf("✨", "❇️", "💫")
    KeyEffectKind.CONFETTI -> listOf("🎊", "🎉", "🟡", "🔴", "🔵")
    KeyEffectKind.CUSTOM_IMAGE -> emptyList()
    KeyEffectKind.EMOJI -> {
        // Each grapheme-ish chunk is one particle kind. A BreakIterator would
        // be exact; splitting on code points pairs surrogates well enough for
        // the emoji people actually type, and a broken chunk just draws tofu
        // in a 600 ms particle.
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < param.length && chunks.size < 8) {
            val end = param.offsetByCodePoints(i, 1)
            chunks.add(param.substring(i, end))
            i = end
        }
        chunks.filter { it.isNotBlank() }.ifEmpty { listOf("🎉") }
    }
}

/**
 * The particle bitmaps: pre-rasterized emoji glyphs for the text-based kinds
 * (text layout per frame would be the whole frame budget), or the theme's own
 * image files for CUSTOM_IMAGE, decoded off the main thread through the same
 * cache every other theme image uses. Empty while a decode is landing — the
 * spawn lambda upstream stays null and presses simply throw nothing yet.
 */
@Composable
internal fun rememberEffectGlyphs(kb: KbTheme): List<ImageBitmap> {
    val kind = kb.keyEffect ?: return emptyList()
    if (kind == KeyEffectKind.CUSTOM_IMAGE) {
        val images by produceState(emptyList<ImageBitmap>(), kb.keyEffectImages) {
            value = kb.keyEffectImages.mapNotNull { path ->
                BackgroundBitmapCache.load(path, 0f, EFFECT_IMAGE_PX, EFFECT_IMAGE_PX)
                    ?.asImageBitmap()
            }
        }
        return images
    }
    return remember(kind, kb.keyEffectParam) {
        effectGlyphs(kind, kb.keyEffectParam).map { rasterizeGlyph(it) }
    }
}

/** Decode edge for a custom particle image; a particle is a few dozen dp. */
private const val EFFECT_IMAGE_PX = 96

private const val GLYPH_PX = 56

private fun rasterizeGlyph(glyph: String): ImageBitmap {
    val bitmap = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = GLYPH_PX * 0.8f
        textAlign = Paint.Align.CENTER
    }
    val baseline = GLYPH_PX / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(glyph, GLYPH_PX / 2f, baseline, paint)
    return bitmap.asImageBitmap()
}

/**
 * The burst layer over the key grid: composed only while particles live, its
 * frame loop exiting with them. Sits with the glide-trail canvas — above the
 * keys and the decals, below the popup windows.
 */
@Composable
internal fun BoxScope.KeyPressEffectsOverlay(field: ParticleField, glyphs: List<ImageBitmap>) {
    if (!field.active || glyphs.isEmpty()) return
    LaunchedEffect(field) {
        while (field.active) {
            withFrameMillis { field.frame(it) }
        }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
        field.revision
        val now = field.nowMs
        for (i in 0 until ParticleField.CAPACITY) {
            val born = field.bornAt[i]
            if (born == 0L) continue
            val age = now - born
            val life = field.lifeMs[i]
            if (age < 0 || age >= life) continue
            val t = age / 1000f
            val px = field.x[i] + field.vx[i] * t
            val py = field.y[i] + field.vy[i] * t + 0.5f * field.gravityPxS2[i] * t * t
            val remaining = 1f - age / life.toFloat()
            val bitmap = glyphs[field.glyphIndex[i] % glyphs.size]
            // Height is the particle's size; width follows the bitmap so a
            // custom PNG keeps its proportions (emoji glyphs are square).
            val edgeH = (GLYPH_PX * field.sizePx[i] * density / 2.5f).roundToInt()
            val edgeW = (edgeH * bitmap.width / bitmap.height.toFloat()).roundToInt()
            rotate(
                degrees = field.spinDegPerS[i] * t,
                pivot = androidx.compose.ui.geometry.Offset(px, py),
            ) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(px.roundToInt() - edgeW / 2, py.roundToInt() - edgeH / 2),
                    dstSize = IntSize(edgeW, edgeH),
                    alpha = remaining.coerceIn(0f, 1f),
                    colorFilter = field.tint[i],
                )
            }
        }
    }
}
