package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonShape
import com.wasimaster.wmkeyboard.core.settings.FloatingMenuAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The button's outlines (#933) and where a ring's slots are drawn on their way out (#923). */
class FloatingShapesTest {

    private val box = Size(100f, 100f)

    @Test
    fun everyPolygonFitsItsBoxAndReachesAnEdge() {
        val polygons = FloatingButtonShape.entries.mapNotNull { shape -> floatingPolygon(shape, box)?.let { shape to it } }
        assertEquals(
            setOf(
                FloatingButtonShape.SQUARE,
                FloatingButtonShape.DIAMOND,
                FloatingButtonShape.PENTAGON,
                FloatingButtonShape.HEXAGON,
                FloatingButtonShape.OCTAGON,
                FloatingButtonShape.STAR,
            ),
            polygons.map { it.first }.toSet(),
        )
        for ((shape, corners) in polygons) {
            for (c in corners) {
                assertTrue("$shape $c", c.x in -0.01f..100.01f && c.y in -0.01f..100.01f)
            }
            val touches = corners.any { it.x < 0.5f || it.x > 99.5f || it.y < 0.5f || it.y > 99.5f }
            assertTrue("$shape is smaller than its box", touches)
        }
    }

    @Test
    fun anOddPolygonIsCentredUpAndDown() {
        for (shape in listOf(FloatingButtonShape.PENTAGON, FloatingButtonShape.STAR)) {
            val corners = floatingPolygon(shape, box)
            assertNotNull(corners)
            assertEquals(100f - corners!!.maxOf { it.y }, corners.minOf { it.y }, 0.01f)
        }
    }

    @Test
    fun aGlyphSitsInTheMiddleOfAPentagonOrAStarNotOfItsBox() {
        // Each stands on its points, so its middle is below the box's (#1372).
        for (shape in listOf(FloatingButtonShape.PENTAGON, FloatingButtonShape.STAR)) {
            val offset = floatingIconOffset(shape)
            assertEquals("$shape x", 0f, offset.x, 0.001f)
            assertTrue("$shape y ${offset.y}", offset.y in 0.03f..0.08f)
        }
        for (shape in listOf(FloatingButtonShape.CIRCLE, FloatingButtonShape.SQUARE, FloatingButtonShape.HEXAGON)) {
            assertEquals(Offset.Zero.x, floatingIconOffset(shape).x, 0.001f)
            assertEquals(Offset.Zero.y, floatingIconOffset(shape).y, 0.001f)
        }
    }

    @Test
    fun theRoundOutlinesAreNoPolygons() {
        for (shape in listOf(FloatingButtonShape.CIRCLE, FloatingButtonShape.OVAL, FloatingButtonShape.HEART)) {
            assertNull(floatingPolygon(shape, box))
        }
    }

    @Test
    fun aStarAlternatesTipsAndDips() {
        val center = Offset(50f, 50f)
        val corners = star(5, center, 50f, 25f, 0f)
        assertEquals(10, corners.size)
        corners.forEachIndexed { i, c ->
            assertEquals(if (i % 2 == 0) 50f else 25f, (c - center).getDistance(), 0.01f)
        }
    }

    @Test
    fun everyAnimationEndsWithTheSlotAtItsPlace() {
        val anchor = Offset(300f, 600f)
        val target = Offset(180f, 520f)
        for (animation in FloatingMenuAnimation.entries) {
            val end = floatingSlotPosition(animation, anchor, target, 1f)
            assertEquals("$animation x", target.x, end.x, 0.01f)
            assertEquals("$animation y", target.y, end.y, 0.01f)
            assertEquals(1f, floatingSlotScale(animation, 1f), 0.01f)
        }
    }

    @Test
    fun springStartsAtTheButtonAndTheInPlaceOnesNeverMove() {
        val anchor = Offset(300f, 600f)
        val target = Offset(180f, 520f)
        assertEquals(anchor, floatingSlotPosition(FloatingMenuAnimation.SPRING, anchor, target, 0f))
        for (animation in listOf(FloatingMenuAnimation.POP, FloatingMenuAnimation.FADE, FloatingMenuAnimation.NONE)) {
            assertEquals(target, floatingSlotPosition(animation, anchor, target, 0.3f))
        }
        val swept = floatingSlotPosition(FloatingMenuAnimation.SWEEP, anchor, target, 0f)
        assertTrue((swept - anchor).getDistance() < (target - anchor).getDistance())
    }
}
