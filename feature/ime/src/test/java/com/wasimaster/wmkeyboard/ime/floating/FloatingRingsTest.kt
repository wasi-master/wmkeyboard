package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** More than eight quick menu slots (#1172): a second ring outside the first. */
class FloatingRingsTest {

    private val anchor = Offset(1000f, 1200f)

    private fun rings(count: Int) = floatingRings(
        count = count,
        anchor = anchor,
        width = 1080f,
        height = 2400f,
        radius = 200f,
        spacing = 220f,
        margin = 90f,
    )

    private fun fan(count: Int) = floatingFan(count, anchor, 1080f, 2400f, 200f, 220f, 90f)

    @Test
    fun eightOrFewerSlotsSitWhereTheyAlwaysDid() {
        assertEquals(fan(5), rings(5))
        assertEquals(fan(8), rings(8))
    }

    @Test
    fun theSlotsPastEightGoInsideTheFirstRingWhereTheyFit() {
        // Eight 220 apart on the right edge settle at about 564, leaving a
        // ring at about 344 inside, whose half circle holds five (#1400).
        val centers = rings(13)
        assertEquals(13, centers.size)
        assertEquals(fan(8), centers.take(8))
        val first = centers.take(8).minOf { (it - anchor).getDistance() }
        centers.drop(8).forEach { assertTrue((it - anchor).getDistance() < first) }
    }

    @Test
    fun whatDoesNotFitInsideGoesOutside() {
        val centers = rings(16)
        val first = centers.take(8).maxOf { (it - anchor).getDistance() }
        val inner = centers.take(8).minOf { (it - anchor).getDistance() }
        centers.subList(8, 13).forEach { assertTrue((it - anchor).getDistance() < inner) }
        centers.drop(13).forEach { assertTrue((it - anchor).getDistance() > first) }
    }

    @Test
    fun noTwoSlotsCrowdEachOther() {
        for (count in 9..16) {
            val centers = rings(count)
            for (i in centers.indices) for (j in i + 1 until centers.size) {
                assertTrue("$count: $i and $j", (centers[i] - centers[j]).getDistance() >= 220f * 0.8f - 0.5f)
            }
        }
    }

    @Test
    fun withNoRoomInsideTheRestGoOutside() {
        // A first ring hardly wider than the button leaves nothing inside it.
        val centers = floatingRings(10, anchor, 1080f, 2400f, radius = 400f, spacing = 100f, margin = 90f)
        val first = centers.take(8).maxOf { (it - anchor).getDistance() }
        centers.drop(8).forEach { assertTrue((it - anchor).getDistance() > first) }
    }

    @Test
    fun halfACircleHoldsAsManyAsItsLengthAllows() {
        assertEquals(4, innerRingRoom(270f, 220f))
        assertEquals(1, innerRingRoom(50f, 220f))
    }
}
