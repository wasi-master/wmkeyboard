package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.perf.DeviceMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePerformanceTest {

    @Test
    fun `a short burst of strain does not raise the level`() {
        val tracker = LoadLevelTracker(escalateAfterMs = 2_000L)
        assertFalse(tracker.update(LoadLevel.HEAVY, now = 0L))
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 500L))
        // The strain starts over, so the wait does too.
        assertFalse(tracker.update(LoadLevel.HEAVY, now = 1_000L))
        assertFalse(tracker.update(LoadLevel.HEAVY, now = 2_500L))
        assertEquals(LoadLevel.NORMAL, tracker.level)
        assertTrue(tracker.update(LoadLevel.HEAVY, now = 3_000L))
        assertEquals(LoadLevel.HEAVY, tracker.level)
    }

    @Test
    fun `an urgent reading raises the level at once`() {
        val tracker = LoadLevelTracker()
        assertTrue(tracker.update(LoadLevel.ELEVATED, now = 0L, urgent = true))
        assertEquals(LoadLevel.ELEVATED, tracker.level)
    }

    @Test
    fun `the level comes down one step per calm spell`() {
        val tracker = LoadLevelTracker(calmMs = 30_000L)
        tracker.update(LoadLevel.HEAVY, now = 0L, urgent = true)
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 1_000L))
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 30_000L))
        assertTrue(tracker.update(LoadLevel.NORMAL, now = 31_000L))
        assertEquals(LoadLevel.ELEVATED, tracker.level)
        // The next step needs a calm spell of its own.
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 40_000L))
        assertTrue(tracker.update(LoadLevel.NORMAL, now = 61_000L))
        assertEquals(LoadLevel.NORMAL, tracker.level)
    }

    @Test
    fun `a reading at the level breaks the calm`() {
        val tracker = LoadLevelTracker(calmMs = 30_000L)
        tracker.update(LoadLevel.ELEVATED, now = 0L, urgent = true)
        tracker.update(LoadLevel.NORMAL, now = 1_000L)
        tracker.update(LoadLevel.ELEVATED, now = 20_000L)
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 25_000L))
        assertFalse(tracker.update(LoadLevel.NORMAL, now = 50_000L))
        assertTrue(tracker.update(LoadLevel.NORMAL, now = 55_000L))
    }

    @Test
    fun `rising again soon after a fall doubles the calm needed`() {
        val tracker = LoadLevelTracker(escalateAfterMs = 0L, calmMs = 30_000L, maxCalmMs = 100_000L)
        tracker.update(LoadLevel.HEAVY, now = 0L)
        tracker.update(LoadLevel.NORMAL, now = 1_000L)
        tracker.update(LoadLevel.NORMAL, now = 31_000L)
        assertEquals(LoadLevel.ELEVATED, tracker.level)
        // Stilling the theme brought the frames back; moving it again lost them.
        tracker.update(LoadLevel.HEAVY, now = 40_000L)
        assertEquals(60_000L, tracker.calmNeededMs)
        tracker.update(LoadLevel.NORMAL, now = 41_000L)
        tracker.update(LoadLevel.NORMAL, now = 101_000L)
        tracker.update(LoadLevel.HEAVY, now = 110_000L)
        // Capped.
        assertEquals(100_000L, tracker.calmNeededMs)
    }

    @Test
    fun `the heavy view stills the theme and leaves the stored choices alone`() {
        val stored = KeyboardSettings()
        val heavy = stored.underLoad(LoadLevel.HEAVY)
        assertTrue(heavy.reduceMotion)
        assertFalse(heavy.accessibility.themeMotion)
        assertFalse(heavy.octopus.enabled)
        // Everything else is the user's.
        assertEquals(stored.haptics, heavy.haptics)
        assertEquals(stored.gestureTyping, heavy.gestureTyping)
        assertEquals(stored.keyboardThemeId, heavy.keyboardThemeId)
    }

    @Test
    fun `lighter levels and the off switch change nothing in the settings`() {
        val stored = KeyboardSettings()
        assertSame(stored, stored.underLoad(LoadLevel.NORMAL))
        assertSame(stored, stored.underLoad(LoadLevel.ELEVATED))
        val off = stored.copy(powerSaving = stored.powerSaving.copy(adaptToLoad = false))
        assertSame(off, off.underLoad(LoadLevel.HEAVY))
    }

    @Test
    fun `adapting is on by default and is not a power saving drop`() {
        val defaults = PowerSavingSettings()
        assertTrue(defaults.adaptToLoad)
        // Switching it off must not read as a change of bundle.
        assertEquals(defaults.preset, defaults.copy(adaptToLoad = false).preset)
    }

    @Test
    fun `a low phone runs the hidden budgets one step tighter`() {
        val idleLow = DeviceLoad(LoadLevel.NORMAL, DeviceClass.LOW)
        assertEquals(LoadLevel.ELEVATED, idleLow.budgetLevel)
        assertEquals(MotionBudget.ELEVATED, MotionBudget.of(idleLow))
        assertEquals(40L, idleLow.suggestionDebounceFloorMs())
        val idleMid = DeviceLoad(LoadLevel.NORMAL, DeviceClass.MID)
        assertEquals(MotionBudget.NORMAL, MotionBudget.of(idleMid))
        assertEquals(16L, idleMid.suggestionDebounceFloorMs())
        assertEquals(1, idleMid.backgroundWorkStretch())
        assertEquals(3, DeviceLoad(LoadLevel.HEAVY).backgroundWorkStretch())
    }

    @Test
    fun `frame caps leave room for the vsync`() {
        // 60 fps is a 16 ms frame; the wait stops short so the next vsync keeps the cap.
        assertEquals(12L, MotionBudget.NORMAL.boardEffectGapMs)
        assertEquals(29L, MotionBudget.NORMAL.themeFlowGapMs)
        assertEquals(0L, MotionBudget(boardEffectFps = 0, themeFlowFps = 0).boardEffectGapMs)
    }

    @Test
    fun `phones are classed by memory and processor`() {
        assertEquals(DeviceClass.LOW, classifyDevice(true, 8_000, 8, 3_000, 0))
        assertEquals(DeviceClass.LOW, classifyDevice(false, 3_000, 8, 2_200, 0))
        assertEquals(DeviceClass.LOW, classifyDevice(false, 4_000, 8, 1_800, 0))
        // A Helio G99 with 6 GB.
        assertEquals(DeviceClass.MID, classifyDevice(false, 5_800, 8, 2_200, 0))
        assertEquals(DeviceClass.HIGH, classifyDevice(false, 11_500, 8, 3_200, 0))
        assertEquals(DeviceClass.HIGH, classifyDevice(false, 7_000, 8, 2_600, 33))
        // A kernel that hides its clocks is not held against the phone.
        assertEquals(DeviceClass.MID, classifyDevice(false, 6_000, 8, 0, 0))
    }

    @Test
    fun `frames and pass costs map to levels`() {
        assertEquals(LoadLevel.NORMAL, frameLoad(frames = 10, janky = 10))
        assertEquals(LoadLevel.NORMAL, frameLoad(frames = 60, janky = 5))
        assertEquals(LoadLevel.ELEVATED, frameLoad(frames = 60, janky = 8))
        assertEquals(LoadLevel.HEAVY, frameLoad(frames = 60, janky = 20))
        assertEquals(LoadLevel.NORMAL, workLoad(40L))
        assertEquals(LoadLevel.ELEVATED, workLoad(120L))
        assertEquals(LoadLevel.HEAVY, workLoad(300L))
    }

    @Test
    fun `televisions are classed against their own bar`() {
        // A 2 GB Amlogic box with A55 cores at 1.9 GHz.
        assertEquals(DeviceClass.LOW, classifyDevice(false, 2_000, 4, 1_900, 0, television = true))
        // 3 GB and 2 GHz would be a mid phone, but is a low television.
        assertEquals(DeviceClass.MID, classifyDevice(false, 3_800, 8, 2_000, 0))
        assertEquals(DeviceClass.LOW, classifyDevice(false, 3_800, 8, 2_000, 0, television = true))
        // A big box is mid at best: no television is high.
        assertEquals(DeviceClass.MID, classifyDevice(false, 8_000, 8, 3_000, 33, television = true))
    }

    @Test
    fun `a low television always runs the heavy view and tight budgets`() {
        val box = DeviceLoad(LoadLevel.NORMAL, DeviceClass.LOW, television = true)
        assertEquals(LoadLevel.HEAVY, box.viewLevel)
        assertEquals(LoadLevel.ELEVATED, box.budgetLevel)
        assertEquals(15_000L, box.idleReleaseMs(120_000L))
        // A mid television: lighter budgets, nothing visible taken away.
        val mid = DeviceLoad(LoadLevel.NORMAL, DeviceClass.MID, television = true)
        assertEquals(LoadLevel.NORMAL, mid.viewLevel)
        assertEquals(LoadLevel.ELEVATED, mid.budgetLevel)
        assertEquals(120_000L, mid.idleReleaseMs(120_000L))
        // A low phone keeps its look but lets models go sooner.
        val phone = DeviceLoad(LoadLevel.NORMAL, DeviceClass.LOW)
        assertEquals(LoadLevel.NORMAL, phone.viewLevel)
        assertEquals(45_000L, phone.idleReleaseMs(120_000L))
    }

    @Test
    fun `memory is tight on low-RAM phones and on televisions up to more RAM`() {
        assertTrue(DeviceMemory.isTight(lowRamDevice = true, totalMb = 8_000, television = false))
        assertTrue(DeviceMemory.isTight(lowRamDevice = false, totalMb = 2_000, television = false))
        assertFalse(DeviceMemory.isTight(lowRamDevice = false, totalMb = 4_000, television = false))
        assertTrue(DeviceMemory.isTight(lowRamDevice = false, totalMb = 4_000, television = true))
        assertFalse(DeviceMemory.isTight(lowRamDevice = false, totalMb = 0, television = true))
    }
}
