package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_EFFECT_DURATION_MS
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The particle field's lifecycle: spawning wakes it, the frame clock puts it
 * back to sleep once every particle is past its lifetime, and the ring buffer
 * takes any amount of hammering without growing.
 */
class ParticleFieldTest {

    @Test
    fun `spawn wakes the field and frame retires it`() {
        val field = ParticleField()
        val life = DEFAULT_EFFECT_DURATION_MS.toLong()
        assertFalse(field.active)
        field.spawn(10f, 10f, count = 5, glyphCount = 3, now = 1_000L)
        assertTrue(field.active)
        // Mid-life: still alive.
        field.frame(1_000L + life / 2)
        assertTrue(field.active)
        // Past every particle's lifetime: the loop's exit condition.
        field.frame(1_000L + life + 1)
        assertFalse(field.active)
    }

    @Test
    fun `the sleep check follows each particle's own duration`() {
        val field = ParticleField()
        field.spawn(0f, 0f, 1, 1, now = 1_000L, physics = EffectPhysics(durationMs = 2_000))
        // A short-lived burst arriving later must not retire the long one.
        field.spawn(0f, 0f, 1, 1, now = 1_100L, physics = EffectPhysics(durationMs = 250))
        field.frame(1_500L)
        assertTrue(field.active)
        field.frame(3_001L)
        assertFalse(field.active)
    }

    @Test
    fun `physics scales velocity and size, and negative gravity floats upward`() {
        val fast = ParticleField()
        fast.spawn(0f, 0f, 8, 1, now = 1_000L, physics = EffectPhysics(speed = 2f, size = 2f))
        val slow = ParticleField()
        slow.spawn(0f, 0f, 8, 1, now = 1_000L, physics = EffectPhysics(speed = 0.5f, size = 0.5f))
        fun speedOf(f: ParticleField, i: Int) = hypot(f.vx[i], f.vy[i])
        assertTrue((0 until 8).all { speedOf(fast, it) > speedOf(slow, it) })
        assertTrue((0 until 8).all { fast.sizePx[it] > slow.sizePx[it] })

        val floaty = ParticleField()
        floaty.spawn(0f, 0f, 4, 1, now = 1_000L, physics = EffectPhysics(gravity = -1f))
        assertTrue(floaty.gravityPxS2.take(4).all { it < 0f })
    }

    @Test
    fun `spread narrows the cone toward straight up`() {
        val wide = ParticleField()
        wide.spawn(0f, 0f, 24, 1, now = 1_000L, physics = EffectPhysics(spread = 1f))
        val narrow = ParticleField()
        narrow.spawn(0f, 0f, 24, 1, now = 1_000L, physics = EffectPhysics(spread = 0.1f))
        // Horizontal share of the velocity: the cone's width, speed aside.
        fun fan(f: ParticleField) =
            (0 until 24).maxOf { abs(f.vx[it]) / hypot(f.vx[it], f.vy[it]) }
        assertTrue(fan(narrow) < fan(wide))
        // Every particle still leaves the key upward, at any spread.
        assertTrue((0 until 24).all { wide.vy[it] < 0f })
    }

    @Test
    fun `a flat tint is shared and clear releases it`() {
        val field = ParticleField()
        val red = ColorFilter.tint(Color.Red)
        field.spawn(0f, 0f, 4, 1, now = 1_000L, physics = EffectPhysics(tint = red))
        // One filter instance across the burst — the draw allocates nothing.
        assertTrue((0 until 4).all { field.tint[it] === red })
        field.clear()
        assertTrue(field.tint.all { it == null })
    }

    @Test
    fun `random tint gives particles their own colours`() {
        val field = ParticleField()
        field.spawn(0f, 0f, 12, 1, now = 1_000L, physics = EffectPhysics(randomTint = true))
        val filters = (0 until 12).map { field.tint[it] }
        assertTrue(filters.all { it != null })
        // Not one shared instance: that is what separates RANDOM from a tint.
        assertTrue(filters.distinct().size > 1)
    }

    @Test
    fun `the ring buffer never grows past capacity`() {
        val field = ParticleField()
        repeat(100) { field.spawn(0f, 0f, count = 12, glyphCount = 1, now = 5_000L) }
        // Every slot is at most CAPACITY entries; the arrays are the capacity.
        assertEquals(ParticleField.CAPACITY, field.bornAt.size)
        assertTrue(field.bornAt.all { it == 0L || it == 5_000L })
    }

    @Test
    fun `spawn with no glyphs is a no-op`() {
        val field = ParticleField()
        field.spawn(0f, 0f, count = 5, glyphCount = 0, now = 1_000L)
        assertFalse(field.active)
    }

    @Test
    fun `clear puts the field to sleep at once`() {
        val field = ParticleField()
        field.spawn(0f, 0f, count = 5, glyphCount = 1, now = 1_000L)
        field.clear()
        assertFalse(field.active)
        assertTrue(field.bornAt.all { it == 0L })
    }

    @Test
    fun `emoji param splits into per-code-point glyphs and never comes back empty`() {
        assertEquals(listOf("🎉", "🔥"), effectGlyphs(KeyEffectKind.EMOJI, "🎉🔥"))
        assertEquals(listOf("🎉"), effectGlyphs(KeyEffectKind.EMOJI, ""))
        assertTrue(effectGlyphs(KeyEffectKind.STARS, "").isNotEmpty())
    }

    @Test
    fun `custom image kind has no text glyphs — its bitmaps come from files`() {
        assertTrue(effectGlyphs(KeyEffectKind.CUSTOM_IMAGE, "ignored").isEmpty())
    }
}
