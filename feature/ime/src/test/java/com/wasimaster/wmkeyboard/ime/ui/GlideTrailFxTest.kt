package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.theme.GlideTrailSpec
import com.wasimaster.wmkeyboard.core.theme.MAX_TRAIL_COLORS
import com.wasimaster.wmkeyboard.core.theme.TRAIL_FLOW_RANGE
import com.wasimaster.wmkeyboard.core.theme.TRAIL_RING_SIZE_RANGE
import com.wasimaster.wmkeyboard.core.theme.TouchRingMode
import com.wasimaster.wmkeyboard.core.theme.TrailSparkleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glide trail's theme look: its colours along the line, its resolved ranges and its sparkle field. */
class GlideTrailFxTest {

    private val red = Color(0xFFFF0000)
    private val blue = Color(0xFF0000FF)
    private val green = Color(0xFF00FF00)

    private fun close(a: Color, b: Color) {
        assertEquals(a.red, b.red, 0.02f)
        assertEquals(a.green, b.green, 0.02f)
        assertEquals(a.blue, b.blue, 0.02f)
    }

    @Test
    fun `a trail with no colours draws its one colour`() {
        assertEquals(green, trailColorAt(emptyList(), 0.3f, Float.NaN, green))
        assertEquals(red, trailColorAt(listOf(red), 0.9f, 0.4f, green))
    }

    @Test
    fun `a still trail runs from its first colour at the tail to its last at the head`() {
        val colors = listOf(red, green, blue)
        close(red, trailColorAt(colors, 0f, Float.NaN, Color.Black))
        close(green, trailColorAt(colors, 0.5f, Float.NaN, Color.Black))
        close(blue, trailColorAt(colors, 1f, Float.NaN, Color.Black))
        // Past either end it holds the end colour rather than wrapping.
        close(blue, trailColorAt(colors, 1.4f, Float.NaN, Color.Black))
        close(red, trailColorAt(colors, -0.2f, Float.NaN, Color.Black))
    }

    @Test
    fun `a flowing trail moves its colours toward the head`() {
        val colors = listOf(red, green, blue)
        // A third of a turn on, the colour that was at the tail sits a third of the way up.
        close(red, trailColorAt(colors, 1f / 3f, 1f / 3f, Color.Black))
        // And a whole turn brings every colour back where it started.
        close(trailColorAt(colors, 0.2f, 0f, Color.Black), trailColorAt(colors, 0.2f, 1f, Color.Black))
        // Below zero wraps round rather than clamping.
        close(blue, trailColorAt(colors, 0f, 1f / 3f, Color.Black))
    }

    @Test
    fun `the flow phase is NaN for a still trail and wraps within a turn`() {
        assertTrue(KbTrail(colors = listOf(red, blue)).flowPhase(5_000L).isNaN())
        // One colour has nothing to flow between.
        assertTrue(KbTrail(colors = listOf(red), flow = 1f).flowPhase(5_000L).isNaN())
        val phase = KbTrail(colors = listOf(red, blue), flow = 0.5f).flowPhase(1_500L)
        assertEquals(0.75f, phase, 1e-4f)
        // A long uptime stays inside one turn.
        val late = KbTrail(colors = listOf(red, blue), flow = 1.35f).flowPhase(9_876_543_210L)
        assertTrue(late in 0f..1f)
    }

    @Test
    fun `a spec resolves with its ranges held and unknown kinds dropped`() {
        val trail = resolveTrail(
            GlideTrailSpec(
                colors = List(MAX_TRAIL_COLORS + 3) { 0xFF112233 },
                flow = 9f,
                glow = -1f,
                sparkles = "glitter_bomb",
                ring = "both",
                ringSizeDp = 5f,
                lightsKeys = false,
            ),
        )
        assertEquals(MAX_TRAIL_COLORS, trail.colors.size)
        assertEquals(TRAIL_FLOW_RANGE.endInclusive, trail.flow)
        assertEquals(0f, trail.glow)
        assertNull(trail.sparkles)
        assertEquals(TouchRingMode.BOTH, trail.ring)
        assertEquals(TRAIL_RING_SIZE_RANGE.start, trail.ringSizeDp)
        assertFalse(trail.lightsKeys)
        assertEquals(TrailSparkleKind.DUST, resolveTrail(GlideTrailSpec(sparkles = "dust")).sparkles)
    }

    @Test
    fun `a still trail keeps its look and loses what moves`() {
        val moving = KbTrail(
            colors = listOf(red, blue),
            flow = 1f,
            glow = 0.6f,
            sparkles = TrailSparkleKind.SPARKS,
            liftBurst = true,
            ring = TouchRingMode.PRESS,
        )
        val still = moving.still()
        assertEquals(moving.colors, still.colors)
        assertEquals(0.6f, still.glow)
        assertEquals(0f, still.flow)
        assertNull(still.sparkles)
        assertFalse(still.liftBurst)
        assertNull(still.ring)
        assertFalse(still.sheds)
        assertTrue(moving.sheds)
    }

    @Test
    fun `sparkles wake the field and the clock puts it back to sleep`() {
        val field = TrailSparkField()
        assertFalse(field.active)
        field.emit(10f, 10f, 1f, 0f, SPARK_STAR, 0xFFFFFFFF.toInt(), now = 1_000L, density = 2f, burst = false)
        assertTrue(field.active)
        field.frame(1_100L)
        assertTrue(field.active)
        // Past the longest life any shape gets, bursts included.
        field.frame(1_000L + 2_000L)
        assertFalse(field.active)
    }

    @Test
    fun `the sparkle field takes any amount of sparkles without growing`() {
        val field = TrailSparkField()
        repeat(TrailSparkField.CAPACITY * 3) {
            field.emit(0f, 0f, 0f, 1f, it % 4, 0, now = 1_000L + it, density = 1f, burst = it % 2 == 0)
        }
        assertEquals(TrailSparkField.CAPACITY, field.bornAt.size)
        assertTrue(field.bornAt.all { it > 0L })
        field.clear()
        assertFalse(field.active)
    }

    @Test
    fun `a streak along the line leaves it sideways`() {
        val field = TrailSparkField()
        repeat(40) {
            field.emit(0f, 0f, 1f, 0f, SPARK_STREAK, 0, now = 1_000L, density = 1f, burst = false)
        }
        // Moving right, every streak heads mostly up or down, never straight back along the line.
        for (i in 0 until 40) {
            assertTrue("streak $i", kotlin.math.abs(field.vy[i]) > kotlin.math.abs(field.vx[i]) * 0.2f)
        }
    }

    @Test
    fun `rings ring only as the mode asks`() {
        assertTrue(TouchRingMode.PRESS.onPress)
        assertFalse(TouchRingMode.PRESS.onLift)
        assertTrue(TouchRingMode.LIFT.onLift)
        assertFalse(TouchRingMode.LIFT.onPress)
        assertTrue(TouchRingMode.BOTH.onPress && TouchRingMode.BOTH.onLift)
    }

    @Test
    fun `a ring spreads for its time and then the layer sleeps`() {
        TouchRings.add(5f, 5f, now = 10_000L)
        assertTrue(TouchRings.active)
        var seen = 0
        TouchRings.forEachLive(10_000L + RING_MS / 2) { _, _, progress ->
            seen++
            assertEquals(0.5f, progress, 0.01f)
        }
        assertEquals(1, seen)
        TouchRings.frame(10_000L + RING_MS + 1)
        assertFalse(TouchRings.active)
    }
}
