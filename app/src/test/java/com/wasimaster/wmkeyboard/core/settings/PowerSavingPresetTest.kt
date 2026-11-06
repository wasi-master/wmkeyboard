package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PowerSavingPreset]: the bundles over the drop switches. */
class PowerSavingPresetTest {

    @Test
    fun `the shipped defaults are the balanced bundle`() {
        assertEquals(PowerSavingPreset.BALANCED, PowerSavingSettings().preset)
    }

    @Test
    fun `each bundle reads back as itself`() {
        for (preset in listOf(PowerSavingPreset.LIGHT, PowerSavingPreset.BALANCED, PowerSavingPreset.AGGRESSIVE)) {
            assertEquals(preset, PowerSavingSettings().withPreset(preset).preset)
        }
    }

    @Test
    fun `a hand-mixed set reads as custom and custom writes nothing`() {
        val mixed = PowerSavingSettings(dropHaptics = false)
        assertEquals(PowerSavingPreset.CUSTOM, mixed.preset)
        assertEquals(mixed, mixed.withPreset(PowerSavingPreset.CUSTOM))
    }

    @Test
    fun `bundles never drop the popup or the statistics`() {
        val stale = PowerSavingSettings(dropKeyPopup = true, dropTypingStats = true)
        assertEquals(PowerSavingPreset.CUSTOM, stale.preset)
        for (preset in listOf(PowerSavingPreset.LIGHT, PowerSavingPreset.BALANCED, PowerSavingPreset.AGGRESSIVE)) {
            val set = stale.withPreset(preset)
            assertFalse(set.dropKeyPopup)
            assertFalse(set.dropTypingStats)
        }
    }

    @Test
    fun `a bundle leaves the triggers alone`() {
        val armed = PowerSavingSettings(manual = true, trigger = PowerSavingTrigger.EITHER, batteryPercent = 33)
        val set = armed.withPreset(PowerSavingPreset.AGGRESSIVE)
        assertTrue(set.manual)
        assertEquals(PowerSavingTrigger.EITHER, set.trigger)
        assertEquals(33, set.batteryPercent)
        assertTrue(set.dropGestureTyping)
    }

    @Test
    fun `moving themes go in every bundle and the floating button only in the aggressive one`() {
        val light = PowerSavingSettings().withPreset(PowerSavingPreset.LIGHT)
        val balanced = PowerSavingSettings().withPreset(PowerSavingPreset.BALANCED)
        val aggressive = PowerSavingSettings().withPreset(PowerSavingPreset.AGGRESSIVE)
        assertTrue(light.dropMovingThemes && balanced.dropMovingThemes && aggressive.dropMovingThemes)
        assertFalse(light.dropFloatingButton)
        assertFalse(balanced.dropFloatingButton)
        assertTrue(aggressive.dropFloatingButton)
    }
}
