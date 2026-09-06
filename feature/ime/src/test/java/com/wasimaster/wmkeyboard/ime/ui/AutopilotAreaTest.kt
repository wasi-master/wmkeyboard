package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind "Outline the touch areas": the rectangle each favoured
 * letter has claimed has to be the boundary the hit test itself would draw, or
 * the setting is a decoration that lies about what the keyboard is doing.
 */
class AutopilotAreaTest {

    /** Three keys in a row, 100 wide and 100 tall, centres at 50, 150 and 250. */
    private val centers = mapOf(
        'a' to Offset(50f, 50f),
        'b' to Offset(150f, 50f),
        'c' to Offset(250f, 50f),
    )
    private val bounds = mapOf(
        'a' to Rect(0f, 0f, 100f, 100f),
        'b' to Rect(100f, 0f, 200f, 100f),
        'c' to Rect(200f, 0f, 300f, 100f),
    )

    private fun areas(bias: Map<Char, Float>, strength: Float = 0.5f) =
        autopilotAreas(centers, bounds, bias, strength, keyWidth = 100f)

    @Test
    fun `a favoured letter claims its share of the gap to each neighbour`() {
        // Weight 1.5 against two plain neighbours: the boundary sits 1.5/2.5 of
        // the 100 units between the centres, so 60 either side of centre 150.
        val area = areas(mapOf('b' to 1f))['b']!!.area
        assertEquals(90f, area.left, 0.01f)
        assertEquals(210f, area.right, 0.01f)
        assertEquals(1.2f, areas(mapOf('b' to 1f))['b']!!.scale, 0.01f)
    }

    @Test
    fun `two favoured letters split the gap they share`() {
        // Equal weights meet in the middle, exactly where they met unfavoured.
        val both = areas(mapOf('a' to 1f, 'b' to 1f))
        assertEquals(100f, both['a']!!.area.right, 0.01f)
        assertEquals(100f, both['b']!!.area.left, 0.01f)
        // Each still grows outward, away from the letter it is level with.
        assertTrue(both['a']!!.area.left < 0f)
        assertTrue(both['b']!!.area.right > 200f)
    }

    @Test
    fun `strength scales how far the boundary moves`() {
        val gentle = areas(mapOf('b' to 1f), strength = 0.1f)['b']!!.area
        val hard = areas(mapOf('b' to 1f), strength = 1.0f)['b']!!.area
        assertTrue(gentle.width < hard.width)
        // And never past the reach cap: one key width from the centre.
        assertTrue(hard.right <= 250f)
    }

    @Test
    fun `a letter squeezed by a likelier neighbour is not drawn`() {
        // 'a' is favoured, 'b' more so: 'a' loses ground and there is nothing
        // to show. Only the letter that actually grew comes back.
        val drawn = areas(mapOf('a' to 0.2f, 'b' to 1f))
        assertNull(drawn['a'])
        assertTrue(drawn.containsKey('b'))
    }

    @Test
    fun `nothing is drawn without a distribution, a grid or a width`() {
        assertTrue(areas(emptyMap()).isEmpty())
        assertTrue(autopilotAreas(centers, bounds, mapOf('b' to 1f), 0.5f, keyWidth = 0f).isEmpty())
        assertTrue(autopilotAreas(centers, emptyMap(), mapOf('b' to 1f), 0.5f, 100f).isEmpty())
    }

    /**
     * A whole row favoured equally claims nothing: every boundary is shared
     * between two equal weights and lands where it always was. The effect is a
     * difference between letters, not a size the strength setting dials up.
     */
    @Test
    fun `a level distribution moves no boundary`() {
        assertTrue(areas(mapOf('a' to 1f, 'b' to 1f, 'c' to 1f))['b'] == null)
    }

    /** The board stays readable: a wide distribution is capped, likeliest first. */
    @Test
    fun `at most five areas are drawn, and they are the likeliest`() {
        val many = ('a'..'z').toList()
        val manyCenters = many.mapIndexed { i, ch -> ch to Offset(50f + i * 100f, 50f) }.toMap()
        val manyBounds = many.mapIndexed { i, ch ->
            ch to Rect(i * 100f, 0f, i * 100f + 100f, 100f)
        }.toMap()
        // Every other letter is expected, in descending order, so each favoured
        // letter has two plain neighbours to take ground from.
        val bias = many.mapIndexed { i, ch -> ch to if (i % 2 == 0) 1f - i / 100f else 0f }.toMap()
        val drawn = autopilotAreas(manyCenters, manyBounds, bias, 0.5f, 100f)
        assertEquals(5, drawn.size)
        assertEquals(listOf('a', 'c', 'e', 'g', 'i'), drawn.keys.sorted())
    }

    /**
     * "Size of the effect" multiplies the growth, not the rectangle, and never
     * touches the area the press is judged against.
     */
    @Test
    fun `the drawn size multiplies the growth alone`() {
        val claimed = areas(mapOf('b' to 1f))['b']!!
        // 90..210 around a 100..200 cell: 10 units of growth on each side.
        assertEquals(90f, claimed.area.left, 0.01f)
        val doubled = claimed.drawnAt(2f)
        assertEquals(80f, doubled.area.left, 0.01f)
        assertEquals(220f, doubled.area.right, 0.01f)
        assertEquals(1.4f, doubled.scale, 0.01f)
        // The cell, and so the area the next multiplication starts from, is kept.
        assertEquals(claimed.cell, doubled.cell)
        assertEquals("×1 is the true size", claimed, claimed.drawnAt(1f))
    }

    /** A side that claimed nothing stays put: the difference is what grows. */
    @Test
    fun `the drawn size leaves an unclaimed side on the cell edge`() {
        // 'b' grows sideways only: above and below it there is no neighbour, so
        // check the axis it did share. 'a' and 'b' are level, so their shared
        // edge did not move and must not move when the drawing is exaggerated.
        val level = areas(mapOf('a' to 1f, 'b' to 1f))['b']!!
        assertEquals(100f, level.area.left, 0.01f)
        assertEquals(100f, level.drawnAt(3f).area.left, 0.01f)
    }

    /** The strength setting the user sees maps onto the pull the hit test uses. */
    @Test
    fun `the strength setting maps onto the hit test`() {
        assertEquals(0.5f, smartHitStrength(5), 0.001f)
        assertEquals(0.1f, smartHitStrength(1), 0.001f)
        assertEquals(1.0f, smartHitStrength(10), 0.001f)
        assertEquals("out of range is clamped", 1.0f, smartHitStrength(99), 0.001f)
    }
}
