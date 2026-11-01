package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthRevealTest {
    @Test
    fun theRowTheUserCameForDecidesTheDepthOffered() {
        val reveal = DepthReveal(arrived = true, opensFolds = true)
        assertNull(reveal.needed)
        reveal.note(SettingLevel.ADVANCED)
        reveal.note(SettingLevel.STANDARD)
        assertEquals(SettingLevel.ADVANCED, reveal.needed)
        reveal.landedOn = SettingLevel.STANDARD
        assertEquals(SettingLevel.STANDARD, reveal.needed)
        assertEquals(SettingsDepth.STANDARD, reveal.needed?.depthShowing())
    }

    @Test
    fun aFoldStaysOpenUntilTheUserClosesIt() {
        val reveal = DepthReveal(arrived = true, opensFolds = true)
        assertTrue(reveal.holdsOpen("typing/advanced"))
        reveal.letGo("typing/advanced")
        assertFalse(reveal.holdsOpen("typing/advanced"))
        assertTrue(reveal.holdsOpen("typing/other"))
    }

    @Test
    fun aLinkToAWholeScreenOpensNoFold() {
        assertFalse(DepthReveal(arrived = true, opensFolds = false).holdsOpen("typing/advanced"))
    }

    @Test
    fun aRowNamedOnAScreenAlreadyOpenOpensItsFolds() {
        val reveal = DepthReveal(arrived = false, opensFolds = false)
        assertFalse(reveal.holdsOpen("typing/advanced"))
        reveal.arrive(opensFolds = true)
        assertTrue(reveal.arrived)
        assertTrue(reveal.holdsOpen("typing/advanced"))
    }

    @Test
    fun everyLevelIsShownByTheDepthOfferedForIt() {
        for (level in SettingLevel.entries) assertTrue(level.depthShowing().shows(level))
    }
}
