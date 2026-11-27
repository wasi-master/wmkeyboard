package com.wasimaster.wmkeyboard.ime.handwriting.onyx

import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnyxStrokeTest {
    private val canvas = IntSize(200, 100)

    @Test
    fun `standard cumulative point list finalizes on end without mid-stroke cutoff`() {
        val collector = OnyxStrokeCollector()

        assertNull(collector.begin(HwPoint(10f, 10f, 100L), canvas))
        assertTrue(collector.inStroke)
        collector.move(HwPoint(20f, 15f, 108L))
        val list = listOf(
            HwPoint(10f, 10f, 100L),
            HwPoint(15f, 12f, 104L),
            HwPoint(20f, 15f, 108L),
            HwPoint(30f, 20f, 112L),
        )
        assertNull(collector.onPointList(list, canvas))
        assertTrue(collector.inStroke)

        val stroke = collector.end(HwPoint(30f, 20f, 112L), canvas)!!
        assertFalse(collector.inStroke)
        assertEquals(list, stroke.points)
    }

    @Test
    fun `chunked mid-stroke point lists merge into one stroke instead of truncating`() {
        val collector = OnyxStrokeCollector()

        collector.begin(HwPoint(10f, 10f, 100L), canvas)
        assertNull(collector.onPointList(listOf(HwPoint(10f, 10f, 100L), HwPoint(20f, 20f, 105L)), canvas))
        assertNull(collector.onPointList(listOf(HwPoint(20f, 20f, 105L), HwPoint(40f, 30f, 110L)), canvas))

        val stroke = collector.end(HwPoint(50f, 35f, 115L), canvas)!!
        assertEquals(
            listOf(
                HwPoint(10f, 10f, 100L),
                HwPoint(20f, 20f, 105L),
                HwPoint(40f, 30f, 110L),
                HwPoint(50f, 35f, 115L),
            ),
            stroke.points,
        )
    }

    @Test
    fun `short tap without point list callback still produces two-point stroke`() {
        val collector = OnyxStrokeCollector()

        collector.begin(HwPoint(42f, 28f, 500L), canvas)
        val stroke = collector.end(HwPoint(42f, 28f, 506L), canvas)!!

        assertEquals(listOf(HwPoint(42f, 28f, 500L), HwPoint(42f, 28f, 501L)), stroke.points)
    }

    @Test
    fun `late duplicate point list arriving after end is ignored`() {
        val collector = OnyxStrokeCollector()
        val pts = listOf(HwPoint(10f, 12f, 200L), HwPoint(30f, 18f, 210L), HwPoint(50f, 22f, 220L))

        collector.begin(pts[0], canvas)
        collector.move(pts[1])
        val ended = collector.end(pts[2], canvas)!!
        assertEquals(pts, ended.points)

        assertNull(collector.onPointList(pts, canvas))
    }

    @Test
    fun `buffered firmware delivering point list without begin or end still submits`() {
        val collector = OnyxStrokeCollector()
        val pts = listOf(HwPoint(15f, 25f, 300L), HwPoint(45f, 35f, 310L))

        val stroke = collector.onPointList(pts, canvas)!!
        assertEquals(pts, stroke.points)
    }

    @Test
    fun `zero firmware timestamps get monotonic fallback steps`() {
        val raw = listOf(HwPoint(10f, 10f, 0L), HwPoint(20f, 20f, 0L), HwPoint(30f, 30f, 0L))

        val stroke = normalizedOnyxStroke(raw, canvas, fallbackBaseTimeMs = 1_000L)!!

        assertEquals(
            listOf(HwPoint(10f, 10f, 1_000L), HwPoint(20f, 20f, 1_005L), HwPoint(30f, 30f, 1_010L)),
            stroke.points,
        )
    }

    @Test
    fun `points are copied and clamped to the canvas`() {
        val points = listOf(HwPoint(-5f, 20f, 10L), HwPoint(250f, 125f, 20L))

        val stroke = normalizedOnyxStroke(points, IntSize(100, 80))!!

        assertEquals(listOf(HwPoint(0f, 20f, 10L), HwPoint(100f, 80f, 20L)), stroke.points)
        assertEquals(listOf(HwPoint(-5f, 20f, 10L), HwPoint(250f, 125f, 20L)), points)
    }

    @Test
    fun `empty input and an unlaid-out canvas submit nothing`() {
        assertNull(normalizedOnyxStroke(emptyList(), IntSize(100, 80)))
        assertNull(normalizedOnyxStroke(listOf(HwPoint(1f, 2f, 3L)), IntSize.Zero))
    }
}
