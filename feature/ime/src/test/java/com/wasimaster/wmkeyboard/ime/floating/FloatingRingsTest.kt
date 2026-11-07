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
    fun theSlotsPastEightGoOnARingFurtherOut() {
        val centers = rings(13)
        assertEquals(13, centers.size)
        assertEquals(fan(8), centers.take(8))
        val inner = centers.take(8).maxOf { (it - anchor).getDistance() }
        centers.drop(8).forEach { assertTrue((it - anchor).getDistance() > inner) }
    }
}
